"""OpenCV input for a Tapo RTSP camera or an offline fixture.

Video capture runs in a short-lived Python/OpenCV helper. Native diagnostics are
suppressed in that process: FFmpeg can otherwise log the complete RTSP URL,
including credentials, even when OpenCV's own logging is disabled. As a result,
RTSP failures intentionally do not distinguish bad credentials, a camera that
is offline, and an unsupported stream. Use an OpenCV build with FFmpeg support.
"""

from __future__ import annotations

import ipaddress
import json
import math
import os
import re
import struct
import subprocess
import sys
import threading
import time
from collections.abc import Iterator
from pathlib import Path
from typing import BinaryIO
from urllib.parse import quote, urlsplit

import cv2
import numpy as np

_OPEN_TIMEOUT_MS = 4_000
_READ_TIMEOUT_MS = 3_000
_STARTUP_TIMEOUT = 10.0
_STALL_TIMEOUT = 6.0
_INITIAL_BACKOFF = 0.5
_MAX_BACKOFF = 8.0
_HEARTBEAT_INTERVAL = 1.0
_HEADER = struct.Struct("!II")
_STILL_SUFFIXES = {
    ".avif", ".bmp", ".dib", ".exr", ".gif", ".hdr", ".jp2", ".jpe", ".jpeg",
    ".jpg", ".pbm", ".pfm", ".pgm", ".png", ".pnm", ".ppm", ".ras", ".sr",
    ".tif", ".tiff", ".webp",
}
# Exclude playlists, concat scripts and other demuxers that can open secondary
# sources. Allow only filesystem access in the offline worker, never FFmpeg TCP.
_LOCAL_FORMATS = "mov,mp4,m4a,3gp,3g2,mj2,avi,matroska,webm,mpeg,mpegts,asf,flv,ogg,mjpeg"


def rtsp_url(host: str, username: str, password: str, stream: str = "stream1", port: int = 554) -> str:
    """Build a Tapo RTSP URL. Treat the returned URL as a secret."""
    if stream not in ("stream1", "stream2"):
        raise ValueError("Stream must be stream1 or stream2.")
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("RTSP port must be an integer from 1 to 65535.")
    if not isinstance(username, str) or not isinstance(password, str):
        raise ValueError("Camera credentials must be strings.")
    authority = _host_authority(host)
    try:
        user, secret = quote(username, safe=""), quote(password, safe="")
    except UnicodeError:
        raise ValueError("Camera credentials must be valid Unicode.") from None
    return f"rtsp://{user}:{secret}@{authority}:{port}/{stream}"


def _host_authority(host: str) -> str:
    if not isinstance(host, str) or not host or "%" in host:
        raise ValueError("Camera host must be an IPv4, IPv6, or DNS hostname.")
    bracketed = host.startswith("[") and host.endswith("]")
    address = host[1:-1] if bracketed else host
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        ip = None
    if isinstance(ip, ipaddress.IPv6Address):
        return f"[{ip.compressed}]"
    if isinstance(ip, ipaddress.IPv4Address) and not bracketed:
        return str(ip)
    if not bracketed and len(host) <= 253:
        try:
            name = host.encode("idna").decode("ascii")
        except UnicodeError:
            name = ""
        bare_name = name.removesuffix(".")
        if (
            0 < len(bare_name) <= 253
            and not re.fullmatch(r"[0-9.]+", bare_name)
            and all(re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", part)
                    for part in bare_name.split("."))
        ):
            return name
    raise ValueError("Camera host must be an IPv4, IPv6, or DNS hostname.")


def frames(source: str | Path, *, fps: float = 4.0, reconnect: bool = False) -> Iterator[np.ndarray]:
    """Yield OpenCV BGR frames, sampling at most one frame per input image.

    RTSP uses TCP, drains continuously, and discards stale frames while the
    caller is busy. An open/read failure raises a sanitized RuntimeError, or
    retries with bounded exponential backoff when reconnect is true. Ctrl+C
    interrupts iteration; close the iterator explicitly if stopping early and
    keeping a reference to it.

    A local still yields once; a local video is sampled by media time without
    real-time sleeps and ends at EOF, regardless of reconnect. Both require a
    regular local file. An unknown timestamp and source rate fall back to one
    output per decoded frame. The live helper passes raw BGR over a pipe, so
    high-resolution streams at high sampling rates have additional copy cost.
    """
    try:
        rate = float(fps)
    except (TypeError, ValueError, OverflowError):
        raise ValueError("FPS must be a positive finite number.") from None
    if not math.isfinite(rate) or rate <= 0 or not math.isfinite(1.0 / rate):
        raise ValueError("FPS must be a positive finite number with a representable interval.")

    if isinstance(source, str) and source[:7].lower() == "rtsp://":
        _validate_live_source(source)
        yield from _live_frames(source, rate, reconnect)
        return

    path = Path(source)
    if _unc_path(path) or not path.is_file():
        raise ValueError("Source must be an RTSP URL or an existing regular local fixture.")
    path = path.resolve()
    if _unc_path(path):
        raise ValueError("Source must be an RTSP URL or an existing regular local fixture.")
    if path.suffix.lower() in _STILL_SUFFIXES:
        try:
            frame = cv2.imdecode(np.fromfile(path, dtype=np.uint8), cv2.IMREAD_COLOR)
        except (OSError, ValueError, cv2.error):
            raise RuntimeError("Could not decode the local image fixture.") from None
        if frame is None:
            raise RuntimeError("Could not decode the local image fixture.")
        yield frame
    else:
        yield from _local_video_frames(path, rate)


def _unc_path(path: Path) -> bool:
    return str(path).startswith(("\\\\", "//"))


def _validate_live_source(source: str) -> None:
    # Bound the stdin request and reject control characters that URL parsers
    # may otherwise silently strip. Do not expose parser errors or their causes.
    try:
        if len(source.encode("utf-8")) > 3000 or any(ord(ch) <= 32 or ord(ch) == 127 for ch in source):
            raise ValueError
        parsed = urlsplit(source)
        _host_authority(parsed.hostname or "")
        if parsed.port == 0 or parsed.fragment:
            raise ValueError
    except (ValueError, UnicodeError):
        raise ValueError("Invalid RTSP source URL.") from None


def _start_worker(kind: str, source: str, fps: float) -> subprocess.Popen:
    env = os.environ.copy()
    if kind == "live":
        env["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
    else:
        env["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = f"protocol_whitelist;file|format_whitelist;{_LOCAL_FORMATS}"
    # No credentials in argv or environment; stderr is discarded at process
    # creation, which covers native FFmpeg messages (not just Python stderr).
    proc = None
    try:
        proc = subprocess.Popen(
            [sys.executable, "-I", "-B", "-u", str(Path(__file__).resolve()), "--worker", kind, str(fps)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            env=env,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0,
        )
        assert proc.stdin is not None
        with proc.stdin:
            proc.stdin.write(json.dumps(source, ensure_ascii=False).encode("utf-8") + b"\n")
        return proc
    except (OSError, ValueError):
        if proc is not None:
            _stop_worker(proc)
        raise RuntimeError("Could not start the OpenCV input helper.") from None
    except BaseException:
        if proc is not None:
            _stop_worker(proc)
        raise


def _stop_worker(proc: subprocess.Popen) -> None:
    if proc.poll() is None:
        proc.kill()
        proc.wait()


def _read_packet(pipe: BinaryIO) -> np.ndarray | None:
    header = pipe.read(_HEADER.size)
    if len(header) != _HEADER.size:
        raise EOFError
    height, width = _HEADER.unpack(header)
    if height == width == 0:
        return None
    data = pipe.read(height * width * 3)
    if len(data) != height * width * 3:
        raise EOFError
    return np.frombuffer(data, dtype=np.uint8).reshape(height, width, 3)


def _local_video_frames(path: Path, fps: float) -> Iterator[np.ndarray]:
    proc = _start_worker("local", str(path), fps)
    assert proc.stdout is not None
    try:
        with proc.stdout:
            while True:
                try:
                    frame = _read_packet(proc.stdout)
                except EOFError:
                    break
                if frame is not None:
                    yield frame.copy()
        if proc.wait() != 0:
            raise RuntimeError("Could not decode the local video fixture.")
    finally:
        _stop_worker(proc)


def _live_session(source: str, fps: float) -> Iterator[np.ndarray]:
    proc = _start_worker("live", source, fps)
    assert proc.stdout is not None
    pipe = proc.stdout
    changed = threading.Condition()
    latest: np.ndarray | None = None
    generation = 0
    received = time.monotonic()
    finished = False

    def drain() -> None:
        nonlocal latest, generation, received, finished
        try:
            with pipe:
                while True:
                    frame = _read_packet(pipe)
                    with changed:
                        received = time.monotonic()
                        if frame is not None:
                            latest = frame
                            generation += 1
                        changed.notify()
        except (EOFError, OSError):
            pass
        finally:
            with changed:
                finished = True
                changed.notify()

    reader = threading.Thread(target=drain, name="rtsp-frames", daemon=True)
    try:
        reader.start()
        delivered = 0
        while True:
            with changed:
                while True:
                    remaining = received + (_STALL_TIMEOUT if generation else _STARTUP_TIMEOUT) - time.monotonic()
                    if finished or remaining <= 0:
                        raise RuntimeError("RTSP stream stopped or timed out; check camera settings and connectivity.")
                    if generation != delivered:
                        frame, delivered = latest, generation
                        break
                    # Bounded waits also let Ctrl+C interrupt on Windows.
                    changed.wait(min(remaining, 0.25))
            assert frame is not None
            yield frame.copy()
    finally:
        _stop_worker(proc)
        if reader.ident is not None:
            reader.join()
        else:
            pipe.close()


def _live_frames(source: str, fps: float, reconnect: bool) -> Iterator[np.ndarray]:
    delay = _INITIAL_BACKOFF
    while True:
        started = time.monotonic()
        try:
            yield from _live_session(source, fps)
        except RuntimeError:
            if not reconnect:
                raise
        if time.monotonic() - started > _MAX_BACKOFF * 2:
            delay = _INITIAL_BACKOFF
        time.sleep(delay)
        delay = min(delay * 2, _MAX_BACKOFF)


def _next_sample(now: float, due: float, interval: float) -> float:
    # Advance against the existing sampling grid without accumulating gaps
    # or duplicating frames when the requested rate is higher than the input.
    return now + (interval - (now - due) % interval)


def _sample_video(cap: cv2.VideoCapture, fps: float) -> Iterator[np.ndarray]:
    native_fps = cap.get(cv2.CAP_PROP_FPS)
    step = 1.0 / native_fps if math.isfinite(native_fps) and native_fps > 0 else 1.0 / fps
    interval = 1.0 / fps
    previous = -step
    due = 0.0
    origin = None
    while True:
        ok, frame = cap.read()
        if not ok or frame is None:
            return
        stamp = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if origin is None:
            origin = stamp if math.isfinite(stamp) and stamp >= 0 else 0.0
        stamp -= origin
        if not math.isfinite(stamp) or stamp <= previous:
            stamp = previous + step
        previous = stamp
        if stamp + min(1e-9, interval / 100) >= due:
            yield frame
            due = _next_sample(max(due, stamp), due, interval)


def _emit(pipe: BinaryIO, frame: np.ndarray) -> None:
    height, width = frame.shape[:2]
    pipe.write(_HEADER.pack(height, width))
    pipe.write(np.ascontiguousarray(frame).tobytes())


def _run_worker(kind: str, fps: float) -> int:
    source = json.loads(sys.stdin.buffer.readline())
    pipe = sys.stdout.buffer
    params = (cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, _OPEN_TIMEOUT_MS,
              cv2.CAP_PROP_READ_TIMEOUT_MSEC, _READ_TIMEOUT_MS) if kind == "live" else ()
    cap = cv2.VideoCapture(source, cv2.CAP_FFMPEG, params)
    try:
        if not cap.isOpened():
            return 1
        if kind == "local":
            emitted = False
            for frame in _sample_video(cap, fps):
                _emit(pipe, frame)
                emitted = True
            return 0 if emitted else 1

        interval = 1.0 / fps
        due = last_sent = time.monotonic()
        while True:
            ok, frame = cap.read()
            if not ok or frame is None:
                return 1
            now = time.monotonic()
            if now >= due:
                _emit(pipe, frame)
                due = _next_sample(now, due, interval)
                last_sent = now
            elif now - last_sent >= _HEARTBEAT_INTERVAL:
                pipe.write(_HEADER.pack(0, 0))
                last_sent = now
    finally:
        cap.release()


if __name__ == "__main__" and len(sys.argv) == 4 and sys.argv[1] == "--worker":
    sys.exit(_run_worker(sys.argv[2], float(sys.argv[3])))

"""Frame input checks using offline media and mock live workers only."""

from contextlib import closing
from io import BytesIO
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import threading
import unittest
from unittest.mock import Mock, patch

import cv2
import numpy as np

from companion import source


class URLTests(unittest.TestCase):
    def test_rtsp_url_accepts_ipv4_ipv6_and_dns_and_quotes_credentials(self):
        self.assertEqual(source.rtsp_url("192.168.0.2", "alice", "pw"), "rtsp://alice:pw@192.168.0.2:554/stream1")
        expected = "rtsp://a%3Ab:p%40ss%20%2F%3F%23%25%C3%A9@[2001:db8::2]:8554/stream2"
        for host in ("2001:0db8::2", "[2001:0db8::2]"):
            with self.subTest(host=host):
                self.assertEqual(source.rtsp_url(host, "a:b", "p@ss /?#%é", "stream2", 8554), expected)
        self.assertEqual(source.rtsp_url("camera.lan.", "u", "p"), "rtsp://u:p@camera.lan.:554/stream1")

    def test_invalid_authorities_streams_and_ports(self):
        for host in ("", "10.0.0.999", "127.1", "https://camera", "camera/path", "camera:554", "name@camera",
                     "[192.168.1.2]", "[::1", "bad_label", "-bad", "bad-", "bad..lan", "host\n"):
            with self.subTest(host=host), self.assertRaisesRegex(ValueError, "Camera host"):
                source.rtsp_url(host, "user", "secret")
        for port in (0, 65536, -1, True, "554"):
            with self.subTest(port=port), self.assertRaisesRegex(ValueError, "RTSP port"):
                source.rtsp_url("camera", "user", "secret", port=port)
        with self.assertRaisesRegex(ValueError, "stream1 or stream2"):
            source.rtsp_url("camera", "user", "secret", stream="secret")

    def test_malformed_rtsp_and_non_rtsp_inputs_are_sanitized(self):
        for url in ("rtsp://user:secret@camera:nope/stream1", "rtsp://user:secret@[::/stream1",
                    "https://user:secret@camera/video", "//server/share/video.avi"):
            with self.subTest(url=url), patch.object(source, "_start_worker") as worker:
                with self.assertRaises(ValueError) as raised:
                    next(source.frames(url))
                self.assertNotIn("secret", str(raised.exception))
                worker.assert_not_called()


class OfflineTests(unittest.TestCase):
    def test_still_yields_once_without_opening_capture(self):
        fixture = np.full((16, 24, 3), (3, 67, 129), dtype=np.uint8)
        with TemporaryDirectory() as dirname, patch.object(source, "_start_worker") as worker:
            path = Path(dirname) / "tést.PNG"
            cv2.imencode(".png", fixture)[1].tofile(path)
            (decoded,) = source.frames(path, reconnect=True)
            np.testing.assert_array_equal(decoded, fixture)
            worker.assert_not_called()

    def test_local_video_samples_media_time_even_if_reconnect_is_true(self):
        with TemporaryDirectory() as dirname:
            path = Path(dirname) / "video.avi"
            writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), 10, (24, 16))
            if not writer.isOpened():
                writer.release()
                self.skipTest("OpenCV build does not provide an MJPEG writer")
            try:
                for index in range(10):
                    writer.write(np.full((16, 24, 3), 15 * index, dtype=np.uint8))
            finally:
                writer.release()
            captured = list(source.frames(path, fps=4, reconnect=True))
            self.assertEqual(len(captured), 4)
            np.testing.assert_allclose([frame[0, 0, 0] for frame in captured], [0, 45, 75, 120], atol=2)

    def test_constant_timestamps_fall_back_to_native_frame_rate(self):
        cap = Mock()
        fixture = [np.full((2, 2, 3), i, dtype=np.uint8) for i in range(10)]
        cap.get.side_effect = lambda prop: 10 if prop == cv2.CAP_PROP_FPS else 0
        cap.read.side_effect = [(True, frame) for frame in fixture] + [(False, None)]
        self.assertEqual([int(frame[0, 0, 0]) for frame in source._sample_video(cap, 4)], [0, 3, 5, 8])

    def test_invalid_fps_and_bad_image(self):
        for fps in (float("inf"), float("nan"), 0, -2, 5e-324, "bad"):
            with self.subTest(fps=fps), self.assertRaisesRegex(ValueError, "FPS"):
                next(source.frames("does-not-exist", fps=fps))
        with TemporaryDirectory() as dirname:
            path = Path(dirname) / "test.png"
            path.write_bytes(b"not an image")
            with self.assertRaisesRegex(RuntimeError, "local image"):
                next(source.frames(path))


class LiveTests(unittest.TestCase):
    def test_worker_isolated_from_native_stderr_argv_and_env(self):
        proc = Mock()
        proc.stdin = Mock()
        proc.stdin.__enter__ = Mock(return_value=proc.stdin)
        proc.stdin.__exit__ = Mock()
        url = "rtsp://user:camera-secret@camera.lan:554/stream1"
        with patch.object(source.subprocess, "Popen", return_value=proc) as start:
            self.assertIs(source._start_worker("live", url, 4), proc)
        argv, = start.call_args.args
        options = start.call_args.kwargs
        self.assertNotIn("camera-secret", repr(argv))
        self.assertNotIn("camera-secret", repr(options["env"]))
        self.assertEqual(options["env"]["OPENCV_FFMPEG_CAPTURE_OPTIONS"], "rtsp_transport;tcp")
        self.assertEqual(options["stderr"], subprocess.DEVNULL)
        self.assertEqual(json.loads(proc.stdin.write.call_args.args[0]), url)
        with patch.object(source.subprocess, "Popen", return_value=proc) as start:
            source._start_worker("local", "fixture.mp4", 4)
        self.assertIn("protocol_whitelist;file|format_whitelist;", start.call_args.kwargs["env"]["OPENCV_FFMPEG_CAPTURE_OPTIONS"])

    def test_reconnect_is_optional_and_uses_capped_backoff(self):
        def interrupted(*_):
            raise RuntimeError("sanitized")
            yield  # Make this a generator that fails during iteration.

        url = "rtsp://u:p@camera.lan:554/stream1"
        with patch.object(source, "_live_session", side_effect=interrupted) as connect, patch.object(source.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "sanitized"):
                next(source.frames(url))
            connect.assert_called_once()
            sleep.assert_not_called()
        with patch.object(source, "_live_session", side_effect=interrupted) as connect, patch.object(source.time, "sleep") as sleep:
            sleep.side_effect = [None] * 5 + [KeyboardInterrupt()]
            with self.assertRaises(KeyboardInterrupt):
                next(source.frames(url, reconnect=True))
            self.assertEqual(connect.call_count, 6)
            self.assertEqual([call.args[0] for call in sleep.call_args_list], [0.5, 1, 2, 4, 8, 8])

    def test_drains_to_most_recent_frame_and_closes_worker(self):
        done = threading.Event()
        received = threading.Event()
        proc = Mock(stdout=BytesIO())
        pixels = [np.full((2, 2, 3), i, dtype=np.uint8) for i in range(3)]
        parts = iter(pixels)

        def packet(_):
            try:
                return next(parts)
            except StopIteration:
                received.set()
                done.wait(2)
                raise EOFError from None

        with patch.object(source, "_start_worker", return_value=proc), patch.object(source, "_read_packet", side_effect=packet), patch.object(source, "_stop_worker", side_effect=lambda _: done.set()) as stop:
            with closing(source.frames("rtsp://u:p@camera.lan:554/stream1")) as iterator:
                first = next(iterator)
                self.assertTrue(received.wait(2))
                if first[0, 0, 0] != 2:
                    first = next(iterator)
                np.testing.assert_array_equal(first, pixels[-1])
            stop.assert_called_once_with(proc)
            self.assertTrue(proc.stdout.closed)

    def test_stalled_worker_times_out_and_is_stopped(self):
        done = threading.Event()
        proc = Mock(stdout=BytesIO())

        def packet(_):
            done.wait(2)
            raise EOFError

        with patch.object(source, "_start_worker", return_value=proc), patch.object(source, "_read_packet", side_effect=packet), patch.object(source, "_stop_worker", side_effect=lambda _: done.set()) as stop, patch.object(source, "_STARTUP_TIMEOUT", 0.02):
            with self.assertRaisesRegex(RuntimeError, "RTSP stream stopped or timed out") as raised:
                next(source.frames("rtsp://user:camera-secret@camera.lan:554/stream1"))
            self.assertNotIn("camera-secret", str(raised.exception))
            stop.assert_called_once_with(proc)


if __name__ == "__main__":
    unittest.main()

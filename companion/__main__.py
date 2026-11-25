"""Command-line pilot; all detection and matching happen on this PC."""

from __future__ import annotations

import argparse
from collections import Counter
from contextlib import closing
from getpass import GetPassWarning, getpass
import json
import math
from pathlib import Path
import sys
from time import perf_counter
import warnings

from .models import model_path

DEFAULT_DATA = Path(__file__).resolve().parent / 'local-data'


def _positive_int(value: str) -> int:
    n = int(value)
    if n < 1:
        raise argparse.ArgumentTypeError('must be at least 1')
    return n


def _range(value: str, low: float, high: float) -> float:
    n = float(value)
    if not math.isfinite(n) or not low <= n <= high:
        raise argparse.ArgumentTypeError(f'must be between {low} and {high}')
    return n


def _input_options(parser: argparse.ArgumentParser) -> None:
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--host', help='LAN address of the camera; use a saved local account or prompt')
    source.add_argument('--media', type=Path, help='path to a local image or video')
    parser.add_argument('--stream', choices=('stream1', 'stream2'), default='stream1')
    parser.add_argument('--prompt-account', action='store_true', help='ignore the saved camera account for this run')
    parser.add_argument('--password-stdin', action='store_true',
                        help='read the camera password from one piped line instead of prompting')
    parser.add_argument('--username', help='local camera username, used with --password-stdin')


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, default=DEFAULT_DATA, help='private directory for models/face templates')
    sub = parser.add_subparsers(required=True, dest='cmd')
    models = sub.add_parser('models', help='download and verify local model weights')
    models.add_argument('--faces', action='store_true', help='also download face models')
    probe = sub.add_parser('probe', help='open the source and show the first frame dimensions (no capture saved)')
    _input_options(probe)
    watch = sub.add_parser('watch', help='print local JSON detection events; does not send Android alerts')
    _input_options(watch)
    watch.add_argument('--camera', default='stairs', help='label to put in events')
    watch.add_argument('--fps', type=lambda s: _range(s, .5, 10), default=3.0, help='target inference samples per second (default: 3)')
    watch.add_argument('--score', type=lambda s: _range(s, .05, .99), default=.45, help='minimum object score')
    watch.add_argument('--zone', type=float, nargs=4, metavar=('LEFT', 'TOP', 'RIGHT', 'BOTTOM'), default=(0, 0, 1, 1),
                       help='optional normalized rectangle; filter by bottom-center of object')
    watch.add_argument('--confirm', type=_positive_int, default=2, help='consecutive object samples (use 1 for a still photo)')
    watch.add_argument('--max-frames', type=_positive_int, help='short test: process up to N frames, no reconnect')
    watch.add_argument('--faces', action='store_true', help='opt in to matching enrolled faces')
    watch.add_argument('--face-debug', action='store_true', help='with --faces, print aggregate face diagnostics (no images or names in the summary)')
    enroll = sub.add_parser('enroll', help='replace the local face samples for one consenting person')
    enroll.add_argument('name')
    enroll.add_argument('photos', nargs='+', type=Path)
    sub.add_parser('names', help='list enrolled identities')
    remove = sub.add_parser('remove', help='delete an enrolled identity (previous printed events are not deleted)')
    remove.add_argument('name')
    save_account = sub.add_parser('save-account', help='save a local camera account encrypted for this Windows user')
    save_account.add_argument('--host', required=True)
    save_account.add_argument('--username', help='prefill the local camera username; password is always prompted')
    save_account.add_argument('--password-stdin', action='store_true',
                              help='read the password from one piped line instead of prompting')
    save_account.add_argument('--no-verify', action='store_true',
                              help='save without first confirming the camera accepts these credentials')
    forget_account = sub.add_parser('forget-account', help='delete a saved local camera account')
    forget_account.add_argument('--host', required=True)
    return parser


def _prompt_account(username: str | None = None) -> tuple[str, str]:
    """Ask for the local camera account without echoing or storing what was typed."""
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise ValueError('No saved camera account. Run save-account once in an interactive PowerShell window.')
    if username is None:
        sys.stderr.write('Camera account username (not your TP-Link login): ')
        sys.stderr.flush()
        username = sys.stdin.readline().strip()
    with warnings.catch_warnings():
        warnings.simplefilter('error', GetPassWarning)
        try:
            password = getpass('Camera account password (typing stays hidden; press Enter when done): ',
                               stream=sys.stderr)
        except GetPassWarning:
            raise ValueError('The terminal cannot mask password input; use an interactive PowerShell window.') from None
    if not username or not password:
        raise ValueError('Camera username and password must not be empty.')
    return username, password


def _input(args: argparse.Namespace) -> str | Path:
    from .source import rtsp_url

    if args.media is not None:
        if not args.media.is_file():
            raise ValueError('Local media file does not exist.')
        return args.media.resolve()
    if not args.host:
        raise ValueError('A camera host or local media file is required.')
    # Never accept a camera credential in argv or an environment variable.
    if args.password_stdin:
        password = sys.stdin.readline().rstrip('\r\n')
        if not args.username or not password:
            raise ValueError('--password-stdin needs --username and one piped password line.')
        account = (args.username, password)
    elif args.prompt_account:
        account = _prompt_account()
    else:
        from .credentials import load_account

        account = load_account(args.host)
        if account is None:
            account = _prompt_account()
    username, password = account
    return rtsp_url(args.host, username, password, args.stream)


def _face_library(data: Path):
    from .faces import FaceLibrary

    models = data / 'models'
    return FaceLibrary(model_path(models, 'yunet'), model_path(models, 'sface'), data / 'faces.json')


def _watch(args: argparse.Namespace) -> None:
    import cv2
    from .detection import Detector
    from .events import Events, inside, valid_zone
    from .source import frames

    if args.face_debug and not args.faces:
        raise ValueError('--face-debug requires --faces.')
    zone = tuple(args.zone)
    if not valid_zone(zone):
        raise ValueError('Zone must be LEFT TOP RIGHT BOTTOM with increasing coordinates in 0..1.')
    cv2.setNumThreads(6)  # bounded CPU parallelism for one feed; the GPU stays idle
    detector = Detector(model_path(args.data_dir / 'models', 'yolox'), threshold=args.score)
    library = _face_library(args.data_dir) if args.faces else None
    if library is not None and not library.names():
        print('No face profiles enrolled: person events will be unnamed.', file=sys.stderr)
    track = Events(args.camera, confirm=args.confirm)
    source = _input(args)
    count = 0
    face_counts: Counter[str] = Counter()
    object_counts: Counter[str] = Counter()
    best_similarity: float | None = None
    largest_rejected_face_px: float | None = None
    started = perf_counter()

    def diagnose(reason: str, similarity: float | None, pixels: float | None) -> None:
        nonlocal best_similarity, largest_rejected_face_px
        face_counts[reason] += 1
        if similarity is not None and (best_similarity is None or similarity > best_similarity):
            best_similarity = similarity
        if pixels is not None and (largest_rejected_face_px is None or pixels > largest_rejected_face_px):
            largest_rejected_face_px = pixels

    try:
        with closing(frames(source, fps=args.fps, reconnect=args.host is not None and args.max_frames is None)) as stream:
            for count, frame in enumerate(stream, 1):
                height, width = frame.shape[:2]
                hits = detector.detect(frame)
                people = sum(d.label == 'person' for d in hits)
                in_zone = [d for d in hits if inside(d.box, zone, width, height)]
                # Count what the detector actually saw, so an empty window is obvious.
                object_counts.update({d.label: 1 for d in hits})
                if people:
                    object_counts['frames_with_person'] += 1
                # Do not risk attaching one person's face to another when people overlap.
                identify = None
                if library is not None and people == 1:
                    def identify(d):
                        x1, y1, x2, y2 = d.box
                        return library.identify(frame[y1:y2, x1:x2], diagnostic=diagnose if args.face_debug else None)
                if args.face_debug:
                    if people > 1:
                        face_counts['frames_skipped_multiple_people'] += 1
                timestamp = perf_counter() if args.host is not None else (count - 1) / args.fps
                for event in track.step(in_zone, timestamp, identify):
                    if args.face_debug and event['type'] == 'identified':
                        face_counts['confirmed_events'] += 1
                    print(json.dumps(event, ensure_ascii=True), flush=True)
                if args.max_frames is not None and count >= args.max_frames:
                    break
    finally:
        if args.face_debug:
            summary = {
                'sampled_frames': count,
                'seconds_watched': round(perf_counter() - started, 1),
            }
            if object_counts.get('person', 0):
                summary['detector_counts'] = dict(sorted(object_counts.items()))
            else:
                summary['detector_counts'] = {}
                summary['note'] = 'no person was detected in any sampled frame; nothing was considered for recognition'
            summary['face_counts'] = dict(sorted(face_counts.items()))
            if best_similarity is not None:
                summary['best_similarity'] = round(best_similarity, 3)
            if largest_rejected_face_px is not None:
                summary['largest_too_small_face_px'] = round(largest_rejected_face_px, 1)
            print('Face diagnostics: ' + json.dumps(summary), file=sys.stderr, flush=True)
    if not count:
        raise ValueError('No readable frames from the source.')
    print(f'Processed {count} sampled frame(s).', file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.cmd == 'models':
            keys = ('yolox', 'yunet', 'sface') if args.faces else ('yolox',)
            for key in keys:
                print(f'Verified: {model_path(args.data_dir / "models", key, fetch=True).name}', flush=True)
        elif args.cmd == 'probe':
            from .source import frames

            source = _input(args)
            with closing(frames(source, reconnect=False)) as stream:
                frame = next(stream, None)
                if frame is None:
                    raise ValueError('No readable frames from the source.')
                height, width = frame.shape[:2]
                print(f'Video verified: {width} x {height} (no frame saved).')
        elif args.cmd == 'watch':
            _watch(args)
        elif args.cmd == 'enroll':
            count = _face_library(args.data_dir).enroll(args.name, args.photos)
            print(f'Enrollment updated: {count} sample(s).')
        elif args.cmd == 'remove':
            if not _face_library(args.data_dir).remove(args.name):
                print('No such enrolled name.', file=sys.stderr)
                return 1
            print('Enrollment removed.')
        elif args.cmd == 'names':
            print(json.dumps(_face_library(args.data_dir).names(), ensure_ascii=True))
        elif args.cmd == 'save-account':
            from .credentials import save_account
            from .source import frames, rtsp_url

            if args.password_stdin:
                username = args.username or ''
                password = sys.stdin.readline().rstrip('\r\n')
                if not username or not password:
                    raise ValueError('Both --username and a piped password line are required.')
            else:
                try:
                    username, password = _prompt_account(args.username)
                except KeyboardInterrupt:
                    print('Cancelled before saving. Nothing was saved.', file=sys.stderr)
                    return 130
            if not args.no_verify:
                # Catch a typo now instead of at the next test run.
                print('Checking the camera accepts these credentials (a few seconds)...', file=sys.stderr, flush=True)
                with closing(frames(rtsp_url(args.host, username, password, 'stream1'), reconnect=False)) as stream:
                    if next(stream, None) is None:
                        raise ValueError('No readable frames; the account was not saved.')
            save_account(args.host, username, password)
            print(f'Saved the camera account for {args.host}, encrypted for this Windows user.')
        elif args.cmd == 'forget-account':
            from .credentials import forget_account

            if not forget_account(args.host):
                print('No saved account for that host.', file=sys.stderr)
                return 1
            print('Saved camera account removed.')
    except KeyboardInterrupt:
        print('Stopped: the run was interrupted (Ctrl+C, or the terminal sent an interrupt).', file=sys.stderr)
        return 130
    except (ValueError, RuntimeError) as exc:
        # Source-layer errors are intentionally credential-free.
        print(f'Error: {exc}', file=sys.stderr)
        return 1
    except OSError:
        print('Error: camera connection, local storage or model download could not be accessed.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

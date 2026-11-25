"""Fetch verified weights from the official OpenCV Zoo release paths."""

from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
from urllib.request import urlopen

WEIGHTS = {
    'yolox': ('object_detection_yolox', 'object_detection_yolox_2022nov.onnx',
              35858002, 'c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063'),
    'yunet': ('face_detection_yunet', 'face_detection_yunet_2023mar.onnx',
              232589, '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'),
    'sface': ('face_recognition_sface', 'face_recognition_sface_2021dec.onnx',
              38696353, '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'),
}
BASE = 'https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models'


def _valid(path: Path, size: int, digest: str) -> bool:
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == digest


def model_path(directory: Path, key: str, *, fetch: bool = False) -> Path:
    folder, filename, size, digest = WEIGHTS[key]
    path = directory / filename
    if _valid(path, size, digest):
        return path
    if not fetch:
        raise ValueError(f'Missing or invalid {filename}; run the models command first.')
    directory.mkdir(parents=True, exist_ok=True)
    # A partial download never replaces a verified model.
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, suffix='.part', delete=False) as tmp:
            tmp_path = Path(tmp.name)
            total = 0
            with urlopen(f'{BASE}/{folder}/{filename}', timeout=30) as response:
                while chunk := response.read(256 * 1024):
                    total += len(chunk)
                    if total > size:
                        raise ValueError(f'Unexpected download size for {filename}.')
                    tmp.write(chunk)
        if not _valid(tmp_path, size, digest):
            raise ValueError(f'Checksum mismatch for {filename}.')
        tmp_path.replace(path)
        return path
    finally:
        if tmp_path is not None:
            tmp_path.unlink(missing_ok=True)

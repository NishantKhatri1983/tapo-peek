"""Local, opt-in household face templates using OpenCV YuNet and SFace.

The caller controls consent, enrollment and whether matching is enabled. The
library stores names and unit embeddings, not photos. Templates are sensitive
biometric data; keep the database in a private local directory. A match is a
heuristic label, not authentication or a liveness check.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import tempfile
from collections.abc import Callable
from pathlib import Path
from threading import Lock
from time import sleep

import cv2
import numpy as np

_DB_VERSION = 1
_EMBEDDING_SIZE = 128
_DETECT_SCORE = 0.60  # Include less certain detections when ruling out extra faces.
_MIN_FACE_SCORE = 0.88
_MIN_FACE_PX = 64
_MAX_DETECT_SIDE = 960
_MIN_CONTRAST = 12.0
_MIN_SHARPNESS = 70.0  # Variance of the Laplacian on a fixed-size grayscale ROI.
_MIN_MATCH_SCORE = 0.60
_MIN_MATCH_MARGIN = 0.10
_SCORE_EPSILON = 1e-6  # Reject numerical borderline cases, not just exact ties.

# Case-folded name -> (display name, matrix of normalized SFace embeddings).
_Entries = dict[str, tuple[str, np.ndarray]]
FaceDiagnostic = Callable[[str, float | None, float | None], None]


class _FaceRejected(ValueError):
    def __init__(self, reason: str, message: str, pixels: float | None = None):
        super().__init__(message)
        self.reason = reason
        self.pixels = pixels


class FaceLibrary:
    """Manage a small local household roster.

    Reads see complete database snapshots, including updates from a separate
    process (e.g. removing a name while the watcher runs). Mutations within this
    instance are serialized; use only one enrollment/removal writer at a time
    across processes or instances.
    """

    def __init__(self, yunet_path: Path, sface_path: Path, database_path: Path):
        self._database_path = Path(database_path)
        self._lock = Lock()  # YuNet's input size and DNNs are mutable.
        detector_bytes = Path(yunet_path).read_bytes()
        recognizer_bytes = Path(sface_path).read_bytes()
        self._model_sha256 = hashlib.sha256(recognizer_bytes).hexdigest()
        self._load()  # Refuse to start with a corrupt or incompatible database.

        # Python reads the paths (including Windows Unicode names); OpenCV's
        # ONNX buffer overloads avoid the native filename encoding limitations.
        empty_config = np.empty(0, dtype=np.uint8)
        self._detector = cv2.FaceDetectorYN.create(
            "onnx", np.frombuffer(detector_bytes, dtype=np.uint8), empty_config,
            (320, 320), _DETECT_SCORE,
        )
        self._recognizer = cv2.FaceRecognizerSF.create(
            "onnx", np.frombuffer(recognizer_bytes, dtype=np.uint8), empty_config,
        )

    def enroll(self, name: str, photo_paths: list[Path]) -> int:
        """Replace this name's sample set and return the number of samples.

        Names are case-insensitive. Every input must be a readable photo with
        exactly one clear face; its embedding must agree with the other new
        samples, and must not already look like another enrolled identity.
        ValueError rejects the whole enrollment without changing the database.
        """
        name = _clean_name(name)
        if not photo_paths:
            raise ValueError("Enrollment requires at least one photo")
        key = name.casefold()
        with self._lock:
            entries = self._load()
            samples: list[np.ndarray] = []
            for index, path in enumerate(photo_paths, start=1):
                try:
                    data = np.frombuffer(Path(path).read_bytes(), dtype=np.uint8)
                    image = cv2.imdecode(data, cv2.IMREAD_COLOR) if data.size else None
                    if image is None:
                        raise ValueError("could not decode the image")
                    sample = self._embedding(image)
                except (OSError, ValueError, cv2.error) as exc:
                    reason = str(exc) if isinstance(exc, ValueError) else "could not read or process the image"
                    raise ValueError(f"Enrollment photo {index}: {reason}") from exc

                if any(float(previous @ sample) <= _MIN_MATCH_SCORE + _SCORE_EPSILON
                       for previous in samples):
                    raise ValueError("Enrollment photos do not agree on one identity")
                if any(_similarity(vectors, sample) >= _MIN_MATCH_SCORE - _SCORE_EPSILON
                       for other_key, (_, vectors) in entries.items() if other_key != key):
                    raise ValueError("An enrollment photo resembles another enrolled identity")
                samples.append(sample)

            entries[key] = (name, np.stack(samples))
            self._save(entries)
            return len(samples)

    def remove(self, name: str) -> bool:
        """Remove a case-insensitive name and its templates, if present."""
        key = _clean_name(name).casefold()
        with self._lock:
            entries = self._load()
            if key not in entries:
                return False
            del entries[key]
            self._save(entries)
            return True

    def names(self) -> list[str]:
        """Return display names sorted without regard to case."""
        with self._lock:
            return [name for name, _ in sorted(self._load().values(), key=lambda entry: entry[0].casefold())]

    def identify(self, person_crop: np.ndarray, *, diagnostic: FaceDiagnostic | None = None) -> tuple[str, float] | None:
        """Return one enrolled display name and cosine similarity, or None.

        Expects a uint8 BGR person crop. A match must clear the absolute cutoff
        and lead the best sample of *any other identity* by the required margin.
        The optional callback receives a reason, best score and smaller face
        dimension where available. It does not receive pixels, names or embeddings.
        """
        with self._lock:
            match, reason, score, pixels = self._identify(person_crop)
        if diagnostic is not None:
            diagnostic(reason, score, pixels)
        return match

    def _identify(self, crop: np.ndarray) -> tuple[tuple[str, float] | None, str, float | None, float | None]:
        try:
            entries = self._load()
        except (OSError, ValueError):
            return None, 'profile_store_unavailable', None, None
        if not entries:
            return None, 'no_profiles', None, None
        try:
            probe = self._embedding(crop)
        except _FaceRejected as exc:
            return None, exc.reason, None, exc.pixels
        except (ValueError, cv2.error):
            return None, 'face_processing_error', None, None
        ranked = sorted(
            ((_similarity(samples, probe), name) for name, samples in entries.values()),
            reverse=True,
        )
        score, name = ranked[0]
        if score <= _MIN_MATCH_SCORE + _SCORE_EPSILON:
            return None, 'below_match_threshold', score, None
        if len(ranked) > 1 and score - ranked[1][0] <= _MIN_MATCH_MARGIN + _SCORE_EPSILON:
            return None, 'ambiguous_match', score, None
        return (name, score), 'frame_matched', score, None

    def _embedding(self, image: np.ndarray) -> np.ndarray:
        if (not isinstance(image, np.ndarray) or image.dtype != np.uint8
                or image.ndim != 3 or image.shape[2] != 3):
            raise ValueError("expected a uint8 BGR image")
        height, width = image.shape[:2]
        if min(width, height) < _MIN_FACE_PX:
            raise _FaceRejected('person_crop_too_small', 'the image is too small for face matching')

        # Limit YuNet's work on large photos; check quality and align on original pixels.
        image = np.ascontiguousarray(image)
        detection_image = image
        if max(width, height) > _MAX_DETECT_SIDE:
            scale = _MAX_DETECT_SIDE / max(width, height)
            size = (max(1, round(width * scale)), max(1, round(height * scale)))
            detection_image = cv2.resize(image, size, interpolation=cv2.INTER_AREA)
        detect_h, detect_w = detection_image.shape[:2]
        self._detector.setInputSize((detect_w, detect_h))
        _, faces = self._detector.detect(detection_image)
        if faces is None or len(faces) == 0:
            raise _FaceRejected('no_face', 'expected exactly one detected face (none found)')
        if faces.shape != (1, 15):
            raise _FaceRejected('multiple_faces', 'expected exactly one detected face')
        face = np.asarray(faces[0], dtype=np.float32).copy()
        if not np.isfinite(face).all() or face[14] < _MIN_FACE_SCORE:
            raise _FaceRejected('weak_face_detection', 'face detection confidence is too low')
        face[:14:2] *= width / detect_w
        face[1:14:2] *= height / detect_h

        x, y, w, h = (float(v) for v in face[:4])
        if min(w, h) < _MIN_FACE_PX:
            raise _FaceRejected('face_too_small', 'the detected face is too small', min(w, h))
        if (x < 0 or y < 0 or x + w > width or y + h > height
                or (face[4:14:2] < 0).any() or (face[4:14:2] >= width).any()
                or (face[5:14:2] < 0).any() or (face[5:14:2] >= height).any()):
            raise _FaceRejected('face_clipped', 'the detected face is clipped by the image edge')

        # Ignore the outer 10% on each edge so background detail is less likely
        # to make a blurry face look sharp.
        inner = image[int(y + 0.1 * h):int(y + 0.9 * h), int(x + 0.1 * w):int(x + 0.9 * w)]
        gray = cv2.cvtColor(inner, cv2.COLOR_BGR2GRAY)
        gray = cv2.resize(gray, (_MIN_FACE_PX, _MIN_FACE_PX), interpolation=cv2.INTER_AREA)
        if gray.std() < _MIN_CONTRAST or cv2.Laplacian(gray, cv2.CV_64F).var() < _MIN_SHARPNESS:
            raise _FaceRejected('face_blurry_or_low_contrast', 'the detected face is blurry or has low contrast')

        aligned = self._recognizer.alignCrop(image, face)
        values = np.asarray(self._recognizer.feature(aligned), dtype=np.float64).reshape(-1)
        if values.size != _EMBEDDING_SIZE or not np.isfinite(values).all():
            raise ValueError("invalid SFace embedding")
        norm = float(np.linalg.norm(values))
        if not math.isfinite(norm) or norm <= 1e-12:
            raise ValueError("invalid SFace embedding norm")
        return values / norm

    def _load(self) -> _Entries:
        try:
            raw = self._database_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return {}
        except UnicodeError as exc:
            raise ValueError("Invalid face database encoding") from exc

        try:
            doc = json.loads(raw, object_pairs_hook=_unique_object)
            if (not isinstance(doc, dict) or type(doc.get("version")) is not int
                    or doc["version"] != _DB_VERSION or not isinstance(doc.get("identities"), dict)):
                raise ValueError("unexpected format or version")
            if doc.get("sface_sha256") != self._model_sha256:
                raise ValueError("templates were enrolled with a different SFace model")
            entries: _Entries = {}
            for name, rows in doc["identities"].items():
                if _clean_name(name) != name or name.casefold() in entries:
                    raise ValueError("invalid or duplicate identity name")
                if not isinstance(rows, list) or not rows:
                    raise ValueError("identity has no embeddings")
                for row in rows:
                    if (not isinstance(row, list) or len(row) != _EMBEDDING_SIZE
                            or not all(type(value) in (int, float) for value in row)):
                        raise ValueError("invalid embedding shape or values")
                matrix = np.asarray(rows, dtype=np.float64)
                if (not np.isfinite(matrix).all() or (np.abs(matrix) > 1.0).any()
                        or not np.allclose(np.linalg.norm(matrix, axis=1), 1.0, atol=1e-6, rtol=0)):
                    raise ValueError("embedding is not a finite unit vector")
                entries[name.casefold()] = (name, matrix)
            return entries
        except (ValueError, OverflowError, RecursionError) as exc:
            raise ValueError(f"Invalid face database: {exc}") from exc

    def _save(self, entries: _Entries) -> None:
        doc = {
            "version": _DB_VERSION,
            "sface_sha256": self._model_sha256,
            "identities": {name: samples.tolist() for name, samples in entries.values()},
        }
        parent = self._database_path.parent
        parent.mkdir(parents=True, exist_ok=True)
        temp_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=parent, prefix=".face-library-", suffix=".tmp", delete=False,
            ) as temp:
                temp_path = Path(temp.name)
                json.dump(doc, temp, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
                temp.write("\n")
                temp.flush()
                os.fsync(temp.fileno())
            # Close before replacing on Windows. A concurrent reader can briefly
            # deny rename access; retry only the Windows sharing violation.
            for attempt in range(4):
                try:
                    os.replace(temp_path, self._database_path)
                    break
                except PermissionError as exc:
                    if getattr(exc, "winerror", None) != 32 or attempt == 3:
                        raise
                    sleep(0.01 * (attempt + 1))
        finally:
            if temp_path is not None:
                temp_path.unlink(missing_ok=True)


def _clean_name(name: str) -> str:
    if not isinstance(name, str) or not name.strip() or not name.strip().isprintable():
        raise ValueError("Use a non-empty printable name")
    return name.strip()


def _similarity(samples: np.ndarray, probe: np.ndarray) -> float:
    return float(np.clip(np.max(samples @ probe), -1.0, 1.0))


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    obj: dict[str, object] = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("duplicate database key")
        obj[key] = value
    return obj

from __future__ import annotations

import json
import math
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np

from companion.faces import FaceLibrary


def unit(*values: float) -> np.ndarray:
    feature = np.zeros(128, dtype=np.float64)
    feature[:len(values)] = values
    return feature / np.linalg.norm(feature)


def detection(*, score: float = 0.98, width: float = 80.0) -> np.ndarray:
    return np.array([16, 16, width, 80, 35, 41, 75, 41, 56, 58, 40, 76, 72, 76, score], dtype=np.float32)


class FaceLibraryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.yunet = self.root / "détect.onnx"
        self.yunet.write_bytes(b"fake YuNet")
        self.sface = self.root / "visage.onnx"
        self.sface.write_bytes(b"fake SFace")
        self.database = self.root / "local" / "faces.json"
        self.image = np.random.default_rng(31).integers(0, 256, (112, 112, 3), dtype=np.uint8)
        self.photo = self.root / "téšt.png"
        _, encoded = cv2.imencode(".png", self.image)
        self.photo.write_bytes(encoded.tobytes())

        # Fake the DNN output, not the image decoding or quality checks.
        detector = self.enterContext(patch("companion.faces.cv2.FaceDetectorYN"))
        self.detector = detector.create.return_value
        self.detector.detect.return_value = (1, detection()[None, :])
        recognizer = self.enterContext(patch("companion.faces.cv2.FaceRecognizerSF"))
        self.recognizer = recognizer.create.return_value
        self.recognizer.feature.return_value = (5 * unit(1)).reshape(1, -1)
        self.library = FaceLibrary(self.yunet, self.sface, self.database)

    def test_replace_remove_and_reload_without_storing_photos(self) -> None:
        self.assertEqual(self.library.names(), [])
        self.assertIsNone(self.library.identify(self.image))
        self.detector.detect.assert_not_called()
        self.assertFalse(self.database.exists())
        self.assertEqual(self.library.enroll("  Núria  ", [self.photo, self.photo]), 2)
        self.assertEqual(self.library.names(), ["Núria"])
        doc = json.loads(self.database.read_text(encoding="utf-8"))
        self.assertEqual(set(doc), {"version", "sface_sha256", "identities"})
        self.assertEqual(set(doc["identities"]), {"Núria"})
        np.testing.assert_allclose(doc["identities"]["Núria"], [unit(1), unit(1)])
        self.assertEqual(list(self.database.parent.iterdir()), [self.database])

        watcher = FaceLibrary(self.yunet, self.sface, self.database)
        self.assertEqual(watcher.identify(self.image), ("Núria", 1.0))
        self.recognizer.feature.return_value = unit(0, 1)
        self.assertEqual(self.library.enroll("NÚRIA", [self.photo]), 1)
        self.assertEqual(watcher.names(), ["NÚRIA"])
        self.assertEqual(watcher.identify(self.image), ("NÚRIA", 1.0))
        self.recognizer.feature.return_value = unit(1)
        self.assertIsNone(watcher.identify(self.image))
        self.assertTrue(self.library.remove("núria"))
        self.assertFalse(self.library.remove("núria"))
        self.assertEqual(watcher.names(), [])
        self.assertIsNone(watcher.identify(self.image))
        self.assertEqual(json.loads(self.database.read_text(encoding="utf-8"))["identities"], {})

    def test_threshold_and_margin_are_strict_and_per_identity(self) -> None:
        self.library.enroll("Bea", [self.photo, self.photo])
        cases = (
            (unit(0.60, 0, 0.80), None),
            (unit(0.59, 0, math.sqrt(1 - 0.59**2)), None),
            (unit(0.61, 0, math.sqrt(1 - 0.61**2)), "Bea"),
        )
        for probe, expected in cases:
            with self.subTest(score=probe[0]):
                self.recognizer.feature.return_value = probe
                match = self.library.identify(self.image)
                self.assertEqual(match[0] if match else None, expected)

        self.recognizer.feature.return_value = unit(0, 1)
        self.library.enroll("Al", [self.photo])
        self.assertEqual(self.library.names(), ["Al", "Bea"])
        for first, second, expected in ((.70, .70, None), (.70, .61, None), (.70, .60, None), (.71, .59, "Bea")):
            with self.subTest(first=first, second=second):
                self.recognizer.feature.return_value = unit(first, second, math.sqrt(1 - first**2 - second**2))
                match = self.library.identify(self.image)
                self.assertEqual(match[0] if match else None, expected)
                if match:
                    self.assertAlmostEqual(match[1], first)

    def test_rejects_tiny_multiple_clipped_weak_and_blurry_crops(self) -> None:
        self.library.enroll("Bea", [self.photo])
        clipped = detection()
        clipped[0] = -1
        cases = (
            (self.image[:63, :], detection()[None, :]),
            (self.image.astype(np.float64), detection()[None, :]),
            (self.image[:, :, :2], detection()[None, :]),
            (self.image, None),
            (self.image, detection(width=63)[None, :]),
            (self.image, detection(score=.87)[None, :]),
            (self.image, np.stack([detection(), detection(score=.65)])),
            (self.image, clipped[None, :]),
            (np.full_like(self.image, 125), detection()[None, :]),
            (np.tile(np.linspace(30, 210, 112, dtype=np.uint8)[None, :, None], (112, 1, 3)), detection()[None, :]),
        )
        for index, (image, faces) in enumerate(cases):
            with self.subTest(index=index):
                self.recognizer.alignCrop.reset_mock()
                self.detector.detect.return_value = (1, faces)
                self.assertIsNone(self.library.identify(image))
                self.recognizer.alignCrop.assert_not_called()

    def test_diagnostics_distinguish_quality_from_similarity_without_reporting_a_name(self) -> None:
        self.library.enroll('Bea', [self.photo])
        seen = []
        def report(*fields):
            seen.append(fields)
        for faces, expected in ((None, ('no_face', None, None)),
                                (detection(width=48)[None, :], ('face_too_small', None, 48))):
            self.detector.detect.return_value = (1, faces)
            self.assertIsNone(self.library.identify(self.image, diagnostic=report))
            self.assertEqual(seen.pop(), expected)
        self.detector.detect.return_value = (1, detection()[None, :])
        self.recognizer.feature.return_value = unit(0, 1)
        self.assertIsNone(self.library.identify(self.image, diagnostic=report))
        self.assertEqual(seen.pop(), ('below_match_threshold', 0.0, None))
        self.recognizer.feature.return_value = unit(1)
        self.assertEqual(self.library.identify(self.image, diagnostic=report), ('Bea', 1.0))
        self.assertEqual(seen.pop(), ('frame_matched', 1.0, None))

    def test_noncontiguous_bgr_crop_is_supported(self) -> None:
        self.library.enroll("Bea", [self.photo])
        frame = np.zeros((128, 128, 3), dtype=np.uint8)
        frame[4:116, 5:117] = self.image
        crop = frame[4:116, 5:117]
        self.assertFalse(crop.flags.c_contiguous)
        self.assertEqual(self.library.identify(crop), ("Bea", 1.0))

    def test_ambiguous_or_invalid_replacement_preserves_existing_templates(self) -> None:
        self.library.enroll("Bea", [self.photo])
        before = self.database.read_bytes()
        with self.assertRaisesRegex(ValueError, "non-empty printable name"):
            self.library.enroll(" \n ", [self.photo])
        with self.assertRaisesRegex(ValueError, "at least one"):
            self.library.enroll("Bea", [])
        with self.assertRaisesRegex(ValueError, "photo 2"):
            self.library.enroll("Bea", [self.photo, self.root / "missing.png"])
        with self.assertRaisesRegex(ValueError, "another enrolled identity"):
            self.library.enroll("Al", [self.photo])
        self.recognizer.feature.side_effect = [unit(1), unit(0, 1)]
        with self.assertRaisesRegex(ValueError, "do not agree"):
            self.library.enroll("Bea", [self.photo, self.photo])
        self.recognizer.feature.side_effect = None
        self.detector.detect.return_value = (2, np.stack([detection(), detection()]))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.library.enroll("Bea", [self.photo])
        self.assertEqual(self.database.read_bytes(), before)

    def test_invalid_dnn_vectors_do_not_match(self) -> None:
        self.library.enroll("Bea", [self.photo])
        for feature in (np.zeros(128), np.full(128, np.nan), np.full(128, np.inf), np.ones(127)):
            with self.subTest(dtype=feature.dtype, size=feature.size, first=feature[0]):
                self.recognizer.feature.return_value = feature
                self.assertIsNone(self.library.identify(self.image))

    def test_corrupted_database_fails_closed(self) -> None:
        self.library.enroll("Bea", [self.photo])
        doc = json.loads(self.database.read_text(encoding="utf-8"))
        doc["identities"]["Bea"][0][0] = 0.75
        self.database.write_text(json.dumps(doc), encoding="utf-8")
        self.assertIsNone(self.library.identify(self.image))
        with self.assertRaisesRegex(ValueError, "unit vector"):
            FaceLibrary(self.yunet, self.sface, self.database)
        with self.assertRaisesRegex(ValueError, "unit vector"):
            self.library.enroll("Bea", [self.photo])

    def test_different_sface_model_cannot_use_enrolled_templates(self) -> None:
        self.library.enroll("Bea", [self.photo])
        self.sface.write_bytes(b"different SFace")
        with self.assertRaisesRegex(ValueError, "different SFace model"):
            FaceLibrary(self.yunet, self.sface, self.database)

    def test_windows_sharing_violation_is_retried(self) -> None:
        self.library.enroll("Bea", [self.photo])
        real_replace = os.replace
        locked = PermissionError("sharing violation")
        locked.winerror = 32
        attempts = iter((locked, None))

        def retry(source: Path, target: Path) -> None:
            failure = next(attempts)
            if failure is not None:
                raise failure
            real_replace(source, target)

        with (patch("companion.faces.sleep") as sleep,
              patch("companion.faces.os.replace", side_effect=retry) as replace):
            self.assertTrue(self.library.remove("Bea"))
            self.assertEqual(replace.call_count, 2)
            sleep.assert_called_once()
        self.assertEqual(self.library.names(), [])

    def test_failed_atomic_replacement_preserves_database_and_cleans_temp(self) -> None:
        self.library.enroll("Bea", [self.photo])
        before = self.database.read_bytes()
        with patch("companion.faces.os.replace", side_effect=PermissionError("locked")):
            with self.assertRaises(PermissionError):
                self.library.remove("Bea")
        self.assertEqual(self.database.read_bytes(), before)
        self.assertEqual(list(self.database.parent.iterdir()), [self.database])


if __name__ == "__main__":
    unittest.main()

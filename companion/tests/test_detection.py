"""YOLOX adapter tests with synthetic frames and synthetic model output."""

import math
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import cv2
import numpy as np

from companion.detection import Detection, Detector


def _prediction(rows, stride, cell, center, size, objectness, classes):
    """Write an undecoded YOLOX candidate at an explicit stride grid cell."""
    start, side = {8: (0, 80), 16: (6400, 40), 32: (8000, 20)}[stride]
    col, row = cell
    slot = rows[0, start + row * side + col]
    slot[:5] = (
        center[0] / stride - col,
        center[1] / stride - row,
        math.log(size[0] / stride),
        math.log(size[1] / stride),
        objectness,
    )
    for class_id, score in classes.items():
        slot[5 + class_id] = score


class DetectorTests(unittest.TestCase):
    def setUp(self):
        self.rows = np.zeros((1, 8400, 85), dtype=np.float32)
        self.net = Mock()
        self.net.getUnconnectedOutLayersNames.return_value = ("output",)
        self.net.forward.return_value = (self.rows,)
        loader = patch("companion.detection.cv2.dnn.readNetFromONNX", return_value=self.net)
        self.loader = loader.start()
        self.addCleanup(loader.stop)
        reader = patch.object(Path, 'read_bytes', return_value=b'fixture')
        reader.start()
        self.addCleanup(reader.stop)

    def test_raw_rgb_float_nchw_and_top_left_letterbox(self):
        detector = Detector(Path("model.onnx"))
        self.loader.assert_called_once()
        self.assertEqual(self.loader.call_args.args[0].tobytes(), b'fixture')
        self.net.setPreferableBackend.assert_called_once_with(cv2.dnn.DNN_BACKEND_OPENCV)
        self.net.setPreferableTarget.assert_called_once_with(cv2.dnn.DNN_TARGET_CPU)
        for height, width in ((160, 320), (320, 160)):
            with self.subTest(height=height, width=width):
                frame = np.full((height, width, 3), (10, 20, 30), dtype=np.uint8)
                frame[0, 0] = (42, 60, 82)
                self.assertEqual(detector.detect(frame), [])
                (blob,), _ = self.net.setInput.call_args
                self.assertEqual(blob.shape, (1, 3, 640, 640))
                self.assertEqual(blob.dtype, np.float32)
                self.assertTrue(blob.flags.c_contiguous)
                self.net.forward.assert_called_with(("output",))

                np.testing.assert_array_equal(blob[0, :, 0, 0], [82, 60, 42])
                # With 2x INTER_LINEAR resize this pixel is 75% of the first pixel.
                np.testing.assert_array_equal(blob[0, :, 0, 1], [69, 50, 34])
                np.testing.assert_array_equal(blob[0, :, 2 * height - 1, 2 * width - 1], [30, 20, 10])
                if height < width:
                    self.assertTrue(np.all(blob[:, :, 2 * height :, :] == 114))
                else:
                    self.assertTrue(np.all(blob[:, :, :, 2 * width :] == 114))

    def test_all_strides_class_aware_nms_and_coco_labels(self):
        # Three different classes share a box but must survive NMS; same-class duplicate must not.
        _prediction(self.rows, 8, (9, 5), (80, 48), (32, 32), 0.9, {0: 0.9})
        _prediction(self.rows, 8, (10, 5), (80, 48), (32, 32), 0.9, {0: 0.77})
        _prediction(self.rows, 16, (4, 2), (80, 48), (32, 32), 0.8, {15: 0.9})
        _prediction(self.rows, 32, (2, 1), (80, 48), (32, 32), 0.8, {16: 0.7})
        frame = np.zeros((160, 320, 3), dtype=np.uint8)
        detector = Detector(Path("model.onnx"))

        detections = detector.detect(frame)

        self.assertEqual([det.label for det in detections], ["person", "cat", "dog"])
        for det, score in zip(detections, (0.81, 0.72, 0.56)):
            self.assertIsInstance(det, Detection)
            self.assertEqual(det.box, (32, 16, 48, 32))
            self.assertAlmostEqual(det.score, score, places=6)
        # Calling detect again on the same tensor must not decode it twice.
        self.assertEqual(detector.detect(frame), detections)

    def test_best_coco_class_and_joint_confidence(self):
        # Cat clears threshold but must not be returned when bicycle is the top class.
        _prediction(self.rows, 8, (4, 5), (32, 40), (16, 16), 0.9, {1: 0.9, 15: 0.7})
        # High class probability cannot compensate for low objectness.
        _prediction(self.rows, 8, (8, 9), (64, 72), (16, 16), 0.4, {16: 0.99})
        # No separate 0.5 objectness gate: 0.48 * 1.0 clears the default threshold.
        _prediction(self.rows, 8, (12, 13), (96, 104), (16, 16), 0.48, {0: 1.0})
        frame = np.zeros((320, 320, 3), dtype=np.uint8)

        detections = Detector(Path("model.onnx")).detect(frame)

        self.assertEqual([det.label for det in detections], ["person"])
        self.assertAlmostEqual(detections[0].score, 0.48, places=6)
        self.assertEqual(Detector(Path("model.onnx"), threshold=0.5).detect(frame), [])

    def test_absolute_exclusive_boxes_clip_and_discard_padding_only(self):
        _prediction(self.rows, 8, (0, 0), (1, 1), (16, 16), 0.9, {0: 0.9})
        _prediction(self.rows, 8, (79, 39), (639, 319), (16, 16), 0.9, {16: 0.8})
        _prediction(self.rows, 8, (30, 55), (240, 440), (16, 16), 0.9, {15: 0.9})
        frame = np.zeros((160, 320, 3), dtype=np.uint8)

        detections = Detector(Path("model.onnx")).detect(frame)

        self.assertEqual({det.label: det.box for det in detections}, {
            "person": (0, 0, 5, 5),
            "dog": (315, 155, 320, 160),
        })

    def test_rejects_incompatible_model_output_shape(self):
        frame = np.zeros((16, 16, 3), dtype=np.uint8)
        self.net.forward.return_value = (np.zeros((1, 85, 8400), dtype=np.float32),)
        with self.assertRaisesRegex(ValueError, "OpenCV Zoo YOLOX_s output"):
            Detector(Path("model.onnx")).detect(frame)


if __name__ == "__main__":
    unittest.main()

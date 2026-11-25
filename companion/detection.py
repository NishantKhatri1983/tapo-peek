"""CPU adapter for the OpenCV Zoo YOLOX_s 2022nov COCO ONNX model.

Input and output conventions follow OpenCV Zoo's Python demo and YOLOX wrapper:
https://github.com/opencv/opencv_zoo/tree/main/models/object_detection_yolox
"""

from dataclasses import dataclass
from math import ceil, floor
from pathlib import Path

import cv2
import numpy as np


_INPUT_SIZE = 640
_STRIDES = (8, 16, 32)
_NUM_CLASSES = 80
_NMS_IOU = 0.5
_LABELS = {0: "person", 15: "cat", 16: "dog"}


def _make_grid() -> tuple[np.ndarray, np.ndarray]:
    grids = []
    strides = []
    for stride in _STRIDES:
        side = _INPUT_SIZE // stride
        x, y = np.meshgrid(np.arange(side), np.arange(side))
        grids.append(np.stack((x, y), axis=-1).reshape(-1, 2))
        strides.append(np.full((side * side, 1), stride))
    return np.concatenate(grids), np.concatenate(strides)


_GRID, _STRIDE_PER_ROW = _make_grid()
_OUTPUT_SHAPE = (1, len(_GRID), 5 + _NUM_CLASSES)


@dataclass(frozen=True)
class Detection:
    """One detection in source pixels: (x1, y1, x2, y2), exclusive end."""

    label: str
    score: float
    box: tuple[int, int, int, int]


class Detector:
    def __init__(self, model_path: Path, threshold: float = 0.45):
        if not 0.0 <= threshold <= 1.0:
            raise ValueError("Detection threshold must be between 0 and 1")
        self._threshold = threshold
        # Read the bytes in Python so Windows paths with Unicode also work.
        self._net = cv2.dnn.readNetFromONNX(np.frombuffer(model_path.read_bytes(), dtype=np.uint8))
        self._net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
        self._net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
        self._output_names = self._net.getUnconnectedOutLayersNames()

    def detect(self, bgr_frame: np.ndarray) -> list[Detection]:
        """Detect people, cats and dogs in a BGR image from OpenCV."""
        if bgr_frame.ndim != 3 or bgr_frame.shape[2] != 3 or bgr_frame.size == 0:
            raise ValueError("Expected a non-empty three-channel BGR image")
        height, width = bgr_frame.shape[:2]
        scale = min(_INPUT_SIZE / height, _INPUT_SIZE / width)
        resized_width, resized_height = int(width * scale), int(height * scale)

        # Zoo's demo uses raw RGB values, top-left letterboxing, and no mean or scaling.
        rgb = cv2.cvtColor(bgr_frame, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR)
        padded = np.full((_INPUT_SIZE, _INPUT_SIZE, 3), 114, dtype=np.float32)
        padded[:resized_height, :resized_width] = resized
        blob = np.ascontiguousarray(padded.transpose(2, 0, 1)[None, ...])

        self._net.setInput(blob)
        outputs = self._net.forward(self._output_names)
        if len(outputs) != 1 or outputs[0].shape != _OUTPUT_SHAPE:
            raise ValueError(f"Expected one OpenCV Zoo YOLOX_s output of shape {_OUTPUT_SHAPE}")
        rows = outputs[0][0]

        # The export returns raw box regressions and already-activated probabilities.
        # Decode stride grids and use top-left XYWH for OpenCV's NMS.
        boxes = rows[:, :4].copy()
        boxes[:, :2] = (boxes[:, :2] + _GRID) * _STRIDE_PER_ROW
        boxes[:, 2:] = np.exp(boxes[:, 2:]) * _STRIDE_PER_ROW
        boxes[:, :2] -= boxes[:, 2:] / 2

        scores = rows[:, 4:5] * rows[:, 5:]
        class_ids = scores.argmax(axis=1)
        best_scores = scores[np.arange(len(scores)), class_ids]
        keep = cv2.dnn.NMSBoxesBatched(
            boxes.tolist(), best_scores.tolist(), class_ids.tolist(), self._threshold, _NMS_IOU
        )

        detections = []
        for index in np.asarray(keep).reshape(-1):
            label = _LABELS.get(class_ids[index])
            if label is None:
                continue
            x, y, w, h = map(float, boxes[index])
            # Undo the top-left letterbox and round outward to enclose the pixel region.
            x1 = max(0, floor(x / scale))
            y1 = max(0, floor(y / scale))
            x2 = min(width, ceil((x + w) / scale))
            y2 = min(height, ceil((y + h) / scale))
            if x1 < x2 and y1 < y2:
                detections.append(Detection(label, float(best_scores[index]), (x1, y1, x2, y2)))
        return detections

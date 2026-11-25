"""Short-lived tracks, zone filtering and conservative identity updates."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import math
from typing import Callable
from uuid import uuid4

from .detection import Detection

Box = tuple[int, int, int, int]
Zone = tuple[float, float, float, float]


def valid_zone(zone: Zone) -> bool:
    left, top, right, bottom = zone
    return (all(math.isfinite(x) for x in zone)
            and 0 <= left < right <= 1 and 0 <= top < bottom <= 1)


def inside(box: Box, zone: Zone, width: int, height: int) -> bool:
    x = (box[0] + box[2]) / (2 * width)
    y = box[3] / height
    return zone[0] <= x <= zone[2] and zone[1] <= y <= zone[3]


def iou(a: Box, b: Box) -> float:
    overlap = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    area = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1])
    return overlap / (area - overlap) if overlap else 0.0


@dataclass
class _Track:
    label: str
    box: Box
    seen: float
    event_id: str
    hits: int = 0
    sent: bool = False
    named: bool = False
    candidate: str | None = None
    votes: int = 0

    def reset_face(self) -> None:
        self.candidate = None
        self.votes = 0


class Events:
    def __init__(self, camera: str, confirm: int = 2, expires: float = 2.5):
        self.camera = camera
        self.confirm = confirm
        self.expires = expires
        self._tracks: list[_Track] = []

    def _event(self, row: _Track, d: Detection, name: str | None = None, score: float | None = None) -> dict:
        event = {
            'event_id': row.event_id,
            'time': datetime.now(timezone.utc).isoformat(timespec='milliseconds'),
            'camera': self.camera,
            'type': 'identified' if name is not None else 'detected',
            'label': d.label,
            'object_score': round(d.score, 3),
        }
        if name is not None and score is not None:
            event.update(name=name, face_similarity=round(score, 3))
        return event

    def step(self, hits: list[Detection], elapsed: float,
             identity: Callable[[Detection], tuple[str, float] | None] | None = None) -> list[dict]:
        # Class-local greedy IoU matching. It is not reliable for two people crossing, so during
        # those frames we deliberately do not attempt identity (the caller can also gate by frame).
        old = [t for t in self._tracks if elapsed - t.seen <= self.expires]
        available = set(range(len(old)))
        out = []
        people = sum(d.label == 'person' for d in hits)
        new = []
        for d in sorted(hits, key=lambda item: item.score, reverse=True):
            choices = [(iou(old[i].box, d.box), -i) for i in available if old[i].label == d.label]
            overlap, negative_index = max(choices, default=(0.0, 0))
            if overlap >= .25:
                index = -negative_index
                available.remove(index)
                track = old[index]
                track.box, track.seen = d.box, elapsed
                if overlap < .5:
                    track.reset_face()
            else:
                track = _Track(d.label, d.box, elapsed, uuid4().hex)
                new.append(track)
            track.hits += 1
            if not track.sent and track.hits >= self.confirm:
                track.sent = True
                out.append(self._event(track, d))
            if people == 1 and identity and d.label == 'person' and not track.named:
                result = identity(d)
                if result is None:
                    track.reset_face()
                else:
                    name, score = result
                    if name == track.candidate:
                        track.votes += 1
                    else:
                        track.candidate, track.votes = name, 1
                    if track.sent and track.votes >= 3:
                        track.named = True
                        out.append(self._event(track, d, name, score))
            else:
                track.reset_face()
        for i in available:
            if not old[i].sent:
                old[i].hits = 0  # generic detections must be seen in successive samples
            old[i].reset_face()
        self._tracks = old + new
        return out

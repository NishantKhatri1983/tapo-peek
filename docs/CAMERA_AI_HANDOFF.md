# Camera AI pilot: handoff notes

Context for continuing this work outside DSH (for example in Claude Code). Read `companion/README.md` for commands. This file records the decisions, measurements and open problems.

## Goal

Make Tapo Peek smarter than "motion/person detected": know whether it is a **person, cat or dog**, and optionally **which household member** it is. Everything runs locally on an always-on Windows laptop. No cloud inference, no subscription, no MCP server needed for detection.

## Where things stand

- `companion/` is a working **Windows Python pilot** (CLI only). It reads a Tapo RTSP stream, detects person/cat/dog, optionally matches enrolled faces, and prints JSON events to stdout.
- It is **not yet connected to the Android app**. The APK and its alerts are unchanged.
- Live RTSP works against the target camera. Person detection works live. **Face recognition does not yet work from the stairs camera** (see findings).
- 36 unit tests pass: `.\companion\.runtime\python\python.exe -B -m unittest discover -s companion/tests`.

## Target camera

- Tapo **C110, hardware v2.0, firmware 1.5.4** (read from Tapo app > Device Settings > Device Info).
- 3 MP. Tapo's own detection offers **person and motion only**: no familiar-face recognition, no pet detection on this model. That is why the laptop does the AI.
- RTSP works with a separate "Camera Account" created in the Tapo app (not the TP-Link cloud login). `stream1` = 1920x1080, `stream2` = lower quality.
- Cameras with built-in local face recognition exist (C260, C560WS) and the H500 hub can add it to compatible cameras, but that is vendor-closed and not exposed to a PC as a feed.

## Architecture

```
Tapo C110 --RTSP/TCP--> companion/source.py  (isolated OpenCV worker, newest-frame only)
                              |
                       companion/detection.py   YOLOX_s (OpenCV Zoo, COCO): person=0 cat=15 dog=16
                              |
                       companion/events.py      IoU tracks, zone filter, N-sample confirm, name votes
                              |  (only if exactly one person in frame)
                       companion/faces.py       YuNet detect + SFace embed, cosine match vs local roster
                              |
                       JSON lines on stdout   ->  (future) bridge to Tapo Peek / phone
```

Other modules: `models.py` (downloads and SHA-256-verifies weights), `credentials.py` (Windows DPAPI-encrypted saved camera account, stored under `%LOCALAPPDATA%\TapoPeek`, outside the repo), `save-account.ps1` (password via secure prompt).

Event model: a `detected` event fires once an object is seen in consecutive samples. A later `identified` event carries the **same `event_id`** plus `name` and `face_similarity`, after 3 agreeing sampled matches. So a pop-up can open immediately on "person" and update its label in place.

## Measured results (this laptop: Core Ultra 9 290HX, 23 GB RAM, RTX 5070 Laptop; inference is CPU-only)

- Offline fixtures: person + dog found together (scores about 0.91); cat alone about 0.90; end-to-end sample video produced one event each for person, dog and cat.
- Enrolling a public sample portrait and matching it from a person crop worked (similarity 0.94), a different face was rejected, and removal took effect immediately.
- CPU YOLOX_s latency per frame: about 440 ms on 2 threads, 330 ms on 4, 250 ms on 6. Pilot defaults to 6 threads and 3 samples/sec.
- Live C110, 90 sampled frames, one person walking out and back: 2 to 3 `detected` person events. One event per pass, as expected (the tracker is not designed to merge a leave-and-return).

## Key finding: faces from the stairs camera are the weak link

Live run with `--faces --face-debug` over 90 frames (person visible in 42):

| Outcome | Frames |
|---|---|
| no face found | 16 |
| face found but under 64 px | 16 (largest was 62.8 px) |
| weak or blurry/low-contrast face | 8 |
| usable face, but no match to the enrolled photo | 2 (best similarity 0.358, cutoff 0.60) |

Interpretation:
- Mostly a **capture** problem (angle, distance, lighting, motion), not a model problem. Faces are almost 64 px but not quite.
- The enrolment photo was a **passport photo**, which may differ a lot from the camera's view. Do not lower the threshold on 2 frames of evidence.
- Frigate docs say the same: recognition range is bounded by the camera's identification range, avoid infrared images for training, and prefer a handful of varied, clear samples over many similar ones.

## Suggested next steps

1. **Collect better evidence.** Re-run in daylight with the person pausing briefly in front of the camera, and read the `Face diagnostics:` line. Aim for several `frame_matched` counts.
2. **If faces are usable but do not match:** add 3 to 5 enrolment samples captured from the C110 itself (different light and angle), not only studio photos. `enroll` replaces a person's samples atomically, and rejects photos that resemble another enrolled person.
3. **If faces are still too small:** change the camera position/angle, try `--stream stream1` only, and consider a tighter `--zone` near the best face position. Do not lower thresholds first.
4. **Bridge to the phone.** Options: (a) laptop sends a push (e.g. ntfy) or HTTP call that the Android app turns into the existing pop-up; (b) a small local HTTP/SSE endpoint the app polls. Reuse `DetectListener.handleDetection(...)` so quiet hours, snooze and cool-down still apply. Remember Android Doze limits (needs high-priority push or a foreground connection).
5. **Run unattended.** Package as a Windows scheduled task or service with auto-restart; add event log rotation. Verify concurrent RTSP viewers (phone Peek + laptop) on the C110 do not drop the stream.
6. Decide on cat vs dog alert behavior: COCO has separate `cat` and `dog` classes, but check false positives at night/IR before relying on them.
7. Optional: compare against Blue Iris 6 (built-in face recognition, paid) or Frigate (needs Linux/WSL, not installed here) if the DIY route stalls.

## Research summary (checked 25 Nov 2026)

- **Blue Iris 6.1.6.4** (24 Nov 2026): commercial Windows NVR, built-in object, face and plate AI. https://blueirissoftware.com/?page_id=344
- **Frigate 0.18.3**: MIT, local face recognition (FaceNet small / ArcFace large) and person/cat/dog labels, MQTT events; Docker/Linux preferred, Windows unofficial. https://docs.frigate.video/configuration/face_recognition/ and https://docs.frigate.video/frigate/installation/
- **Agent DVR 8.3.2.0**: native Windows, built-in AI needs a paid license. https://www.ispyconnect.com/docs/agent/ai-servers
- **Scrypted**: Windows NVR needs the paid desktop app; docs show a coarse "animal" class. https://docs.scrypted.app/scrypted-nvr/features.md
- **CodeProject.AI** is source-available (SSPL), last GitHub release 2.9.5 (Dec 2024).
- Chosen models: OpenCV Zoo YOLOX (Apache-2.0), YuNet (MIT), SFace (Apache-2.0). https://github.com/opencv/opencv_zoo
- Tapo RTSP/ONVIF: https://www.tp-link.com/us/support/faq/4465/

## Privacy and safety rules (keep these)

- This repo is **public**. Never commit photos, `companion/local-data/` (models and `faces.json`), `companion/.runtime/`, camera credentials, or LAN addresses.
- Face templates are sensitive biometric data and are **not application-encrypted**. Enrol only people who agreed. Keep them out of Git, logs, sync folders and MCP.
- A name is a heuristic, not authentication. Never unlock anything or suppress a security alert based on it. Always keep a generic "person detected" fallback.
- Credentials are never passed in argv or logged; errors from the stream layer are deliberately generic.

## Setup on a fresh machine

```powershell
git clone https://github.com/NishantKhatri1983/tapo-peek.git
cd tapo-peek
git checkout camera-ai-pilot
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r companion/requirements.txt
.\.venv\Scripts\python.exe -m companion models --faces
.\.venv\Scripts\python.exe -m companion probe --host CAMERA_LAN_IP
```

The first Windows sandbox used a portable CPython 3.12 under `companion/.runtime/` (git-ignored). Any 64-bit Python 3.11+ with `numpy` and `opencv-python-headless` (versions in `companion/requirements.txt`) works. If `python` opens the Microsoft Store, it is the Store alias; install Python or disable the alias.

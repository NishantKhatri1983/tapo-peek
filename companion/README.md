# Windows camera AI pilot

This optional companion reads one Tapo RTSP feed, detects `person`, `cat`, and `dog` on the laptop, and prints JSON events to stdout. Optional household face matching runs entirely on the laptop. It does not change the Android APK, open its pop-ups, upload images or save video. Once weights are downloaded, inference works without internet. It is a foreground pilot, not an installed 24/7 service.

## Start on Windows

With 64-bit CPython 3.12 installed, run these commands in PowerShell **from the repository root**. A portable Python with the requirements installed works too; replace `.\.venv\Scripts\python.exe` consistently with its path.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r companion/requirements.txt
.\.venv\Scripts\python.exe -m companion models
```

First try a local test image, then test the high-quality camera stream. Replace `CAMERA_LAN_IP` with the camera address.

```powershell
.\.venv\Scripts\python.exe -m companion watch --media C:/path/to/test.jpg --confirm 1
.\.venv\Scripts\python.exe -m companion probe --host CAMERA_LAN_IP
.\.venv\Scripts\python.exe -m companion watch --host CAMERA_LAN_IP --max-frames 40
```

### Save the camera account once

To stop retyping the local **Camera Account** (not the TP-Link login) on every test, save it once:

```powershell
.\.venv\Scripts\python.exe -m companion save-account --host CAMERA_LAN_IP --username CAMERA_USERNAME
```

The password is always prompted, never taken from the command line. **Typing stays invisible** â€” no asterisks appear â€” so press Enter when you are done; do not Ctrl+C at that prompt, which cancels the save. Before storing anything, the command opens the camera once and only saves if it can read a frame, so a typo fails immediately instead of at the next test.

If the prompt misbehaves in a particular console, use the bundled helper instead. It asks for the password with PowerShell's own secure prompt and passes it in on stdin:

```powershell
.\companion\save-account.ps1 -CameraHost CAMERA_LAN_IP -Username CAMERA_USERNAME
```

`--no-verify` skips the one-frame check, for saving an account for a camera that is currently offline.

### If the password prompt behaves oddly

Some embedded terminals (IDE panes, in-app consoles) don't pass keystrokes to the child process's hidden input mode, so a prompt can appear to hang after Enter, or a run can end with an unexplained interrupt. Run the tests in a normal **Windows Terminal / standalone PowerShell** window. If you need to bypass the prompt for a one-off run, note that Windows PowerShell 5.1 **cannot pipe from a `try/finally` block** (`An empty pipe element is not allowed`), so assign the value first:

```powershell
$sec = Read-Host 'Camera account password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
$plain | .\companion\.runtime\python\python.exe -m companion watch --host CAMERA_LAN_IP --username CAMERA_USERNAME --password-stdin --faces --face-debug
Remove-Variable plain, sec, ptr
```

The password is read from the pipe, never typed at an invisible prompt and never in command history.

Windows encrypts both values with **DPAPI in current-user scope** and stores them under `%LOCALAPPDATA%\TapoPeek\camera-accounts\`. Only your Windows user on this PC can decrypt them; another user, another PC, or a copied file cannot. After that, `probe` and `watch` reuse it with no prompt. `--prompt-account` forces a fresh prompt for one run, and `forget-account --host CAMERA_LAN_IP` deletes the saved copy.

This is deliberately **not** written into the repository or any config file in the source tree: a committed password would be exposed to everyone with the repo, and Git history keeps deleted secrets. Nothing readable is stored â€” verify with `Get-Content` on the `.dpapi` file if you want to confirm it is ciphertext.

Omit `--max-frames` to keep watching and retry connection failures. Ctrl+C stops it. `--stream stream2` selects the lower-quality stream; use the default `stream1` first for faces. The app targets up to 3 inference samples/sec by default (`--fps` overrides it); actual throughput depends on the machine and feed. `--zone 0.2 0.15 0.8 0.95` limits events to a normalized rectangle, testing the bottom center of each box. Omit the zone for full-frame detection. The Tapo app's activity zones are not imported. If Peek playback has trouble when the pilot is running, close the pilot and test concurrent streams before adding a restream.

## Optional local face matching

Enroll only people who have agreed. Use clear, front-facing, color photos with one visible face, drawn from a few different sessions and lighting conditions. Do not copy any images into the repository outside the ignored private data directory. Set `--data-dir` before the subcommand to store private data elsewhere (for example an access-controlled local directory, not synced to cloud storage).

```powershell
.\.venv\Scripts\python.exe -m companion models --faces
.\.venv\Scripts\python.exe -m companion enroll 'Example' C:/private/face1.jpg C:/private/face2.jpg
.\.venv\Scripts\python.exe -m companion names
.\.venv\Scripts\python.exe -m companion watch --host CAMERA_LAN_IP --faces
.\.venv\Scripts\python.exe -m companion remove 'Example'
```

`enroll` **replaces** that person's previous examples only after all new examples pass checks. Run enrollment or removal commands one at a time. The database stores names and numerical face templates, not reference photos. Templates are sensitive biometric data, **not application-encrypted**: protect the Windows account and local directory, and keep them out of Git, logs, backups you do not control, and MCP. The camera's RTSP feed is normally unencrypted: keep it on the trusted LAN or a VPN; do not forward its ports. `remove` deletes the local enrollment, not names in previously redirected output.

A generic `detected` event is emitted once an object appears in consecutive samples. For a single person, a confident name can come later as a separate `identified` event with the **same event ID**, after three consistent sampled matches. More than one detected person blocks matching on that frame. Low-quality or uncertain faces stay unnamed. `face_similarity` is a model similarity, not a probability. A person event is not proof of identity and should not unlock doors or suppress security alerts. Test in actual daylight, backlighting, and IR, with moving people and different camera angles; if the footage doesn't show a clear face, tune the camera placement before lowering thresholds.

### If a person is detected but not named

Add `--face-debug` to a `watch --faces` run. When the run ends (or on Ctrl+C), stderr shows an aggregate summary. `detector_counts` is what the object detector saw; an empty one with a `note` means **no person appeared during the run**, so recognition was never attempted. `face_counts` explains what happened to each person frame:

| Key | Meaning |
|---|---|
| `no_face` | Person detected, but no face found in the box (back turned, too far, motion blur) |
| `face_too_small` | A face was found but under 64 px; `largest_too_small_face_px` shows the best attempt |
| `weak_face_detection` | Face found but below the detection confidence |
| `face_blurry_or_low_contrast` | Face found but too soft or flat to embed |
| `face_clipped` / `multiple_faces` | Face ran off the crop edge, or more than one face in the crop |
| `below_match_threshold` | A usable face did not resemble any enrollment; `best_similarity` shows how close |
| `ambiguous_match` | Close to two enrolled people, so left unnamed |
| `frame_matched` | Matched this frame; needs three consistent frames to confirm a name |

`face_too_small` dominating means the person is simply too far from the lens for names to work â€” move closer or change the camera position rather than lowering thresholds. No images, names, or templates are printed in the summary.

## Development

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s companion/tests -v
```

The first download verifies OpenCV Zoo weights against pinned SHA-256 hashes from the official LFS pointers. The ONNX models are not distributed in this repository: [YOLOX (Apache-2.0)](https://github.com/opencv/opencv_zoo/tree/main/models/object_detection_yolox), [YuNet (MIT)](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet), and [SFace (Apache-2.0)](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface).

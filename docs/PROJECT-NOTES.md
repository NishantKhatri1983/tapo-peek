# tapo-peek: project notes

Why things are the way they are, and how we got here. Written so the work can be picked up from the repository alone (new machine, new chat, or a new person). Facts here were learned the hard way; the code does not record them.

Companion project: **stairs-desktop**, a separate Windows app with the same idea (always-on-top camera window). The two are different programs in different repositories and must not be mixed. Lessons that apply to both are in both notes.

## How we got here (iterations)

| Version | Date | What changed |
|---|---|---|
| v1.0 | 2026-11-10 | Floating live view over local RTSP (drag, pinch, double-tap width), pop-up on Tapo detection alerts, lock-screen wake-up, quiet hours, snooze, camera finder with live preview |
| v1.1 | 2026-11-13 | Motion-only pop-ups by default, "wait for calm" instead of a fixed cool-down, optional ONVIF camera watch, chime, second camera, activity log, Material 3 UI |
| v1.2 | 2026-11-13 | Open the second camera by hand (button, long-press shortcut, "icon opens both" switch) |
| v1.3 | 2026-11-13 | Screen stays on while you watch a hand-opened window (Settings switch) |
| v1.4 | 2026-11-17 | Choose when a detection pop-up closes by itself (15 s to 10 min, separate for floating window and lock screen) |
| v1.5 | 2026-11-28 | Up to four cameras (2 x 2 grid), Wi-Fi auto-find of cameras, button to hide or show Stairs in the pop-up |

Every release is tagged `vX.Y` and has a GitHub release with the APK (debug-signed, for sideloading). Keep doing that so any old version can be restored.

## Decisions and the reasons behind them

- **Reacts to Tapo's own alerts instead of talking to the camera.** The app reads the Tapo app's notifications (notification access). It needs no cloud login and no extra load on the camera. The cost: it only fires when Tapo sends an alert, and the camera name must appear in Tapo's alert text ("Camera name in the Tapo app" setting).
- **Motion zone only by default.** Person-zone alerts fired for people just passing on the stairs. Motion / Person / Both is a setting.
- **"Wait for calm" instead of a fixed cool-down.** A fixed window re-opened the pop-up for every movement of someone who stayed at the door. Now the quiet period slides forward with each movement seen during it, so one visit gives one pop-up. After 10 minutes of continuous movement it pops up once more. Settings keys are `calm*`; older `cool*` values are deliberately not reused.
- **Quiet hours default to 23:00 to 07:00 and silently suppress automatic pop-ups.** This once looked like "the app missed an alert". Check quiet hours and snooze first when an expected pop-up did not appear. The activity log on the phone records every alert and the decision made.
- **Hand-opened windows keep the screen on; detection pop-ups do not.** Watching on purpose should not time out. Known side effect (open item below): a window left open keeps the screen on for ever.
- **Detection pop-ups always show Stairs and all cameras.** The "hide Stairs" choice in v1.5 applies only to windows opened by hand, so hiding Stairs can never cause a missed Stairs alert. The choice is remembered (`stairsHidden`). The lock-screen hand-over to the floating window also follows the remembered choice (not yet changed to "always all cameras").
- **Hidden cameras are not streamed.** Switching a camera off in the pop-up releases its player. No data, no battery.
- **Extra cameras are always muted.** Only Stairs carries sound, so the mute button does nothing while Stairs is hidden.
- **Debug-signed APKs.** Installs update in place only if built with the same debug key; otherwise uninstall first. Never install an older APK over a newer one.
- **Tried and dropped: always-on AI (Frigate with face recognition) on the laptop.** People were detected well, but face matching stayed weak from the high, downward camera angle, and the load on an always-on laptop was not acceptable. It was uninstalled. Do not propose always-on NVR or AI again; if recognition returns, think of something light such as Tapo's own face feature or event-triggered snapshots.

## Facts about Tapo cameras (learned by testing)

- RTSP URL: `rtsp://user:pass@<ip>:554/stream1` is the HD main stream (1920 x 1080), `stream2` is the SD sub-stream (640 x 360). The user and password are the **Camera Account** made in the Tapo app (camera, Settings, Advanced Settings, Camera Account), not the TP-Link cloud login.
- About **three local connections** at once are allowed. Extra clients are refused.
- **Repeated wrong logins lock the camera account for a while, for every device.** One client retrying a wrong password (for example a retry loop) locks the phone app out too. Both apps therefore stop after a few refusals. Power-cycling the camera or waiting clears it.
- Camera **names live in the TP-Link cloud**, not on the camera, so the app cannot read them over the LAN. Cameras are labelled by the user in Settings.
- Cameras can be found by scanning the /24 network for port 554. The phone does this with its own Wi-Fi network; the desktop app scans the adapters that have a gateway.

## Open items and ideas

- **Battery: a hand-opened pop-up never times out**, and with "keep the screen on" (default on) the screen never turns off, so the screen-off stop never fires either. Options discussed: an idle timeout (recommended), keep-screen-on off by default, or a hard maximum open time. Not decided or built.
- Lock-screen hand-over could always show all cameras (like a detection pop-up), ignoring the hidden-Stairs choice.
- Cameras in the pop-up cannot be chosen individually beyond "Stairs on/off" and "other cameras on/off".
- The camera account is stored in app-private storage on the device (not encrypted beyond the Android sandbox).

## Working agreements

- Discuss the plan and get a yes before changing behaviour or installing anything on the user's devices.
- Tag every pushed version, keep the release notes as the change log, attach the APK.
- Never retry rejected camera logins in a loop (see lockout above).

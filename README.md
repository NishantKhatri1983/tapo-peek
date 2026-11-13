# tapo-peek

One tap (or one motion alert) and your Tapo camera appears in a small floating window on your Android phone or tablet. No opening the Tapo app, no hunting for the right camera.

> Unofficial. Not affiliated with or endorsed by TP-Link. "Tapo" is a trademark of its owner; it is used here only to say which cameras this works with.

## What it does

- **One-tap live view.** A launcher icon opens the camera straight into a floating window over whatever you are doing. Drag to move, pinch to resize, double-tap to switch between full and half width, tap for mute / pause / close. Size and position are remembered. When you open it yourself the screen stays on until you close it (a Settings switch turns this off).
- **Pops up on detection.** When the Tapo app posts a "Motion Detected" / person alert for your camera, the window opens by itself.
- **Wakes a locked screen.** If the phone or tablet is locked or asleep, the camera appears on the lock screen (the device stays locked), then closes after 40 seconds.
- **Quiet hours and pause.** Skip automatic pop-ups overnight, or tap the snooze button on a pop-up (15 min to 2 h) when you know someone is at the door.
- **Choose what pops up.** Motion zone only (default), person zone only, or both, so people just passing on the stairs do not trigger it.
- **Wait for calm.** After you close a pop-up, nothing new opens until the camera has seen no movement for a while (2 min by default), so someone who stays at the door gives one pop-up, not one per movement.
- **Chime.** Optional sound when a pop-up opens: pick any system notification sound, and optionally play it on the alarm volume so it is heard even with notifications muted.
- **Camera watch (optional, off by default).** Listens to the camera's own motion events (ONVIF), which catches movement Tapo does not alert on.
- **Second camera.** Optionally shows a second camera under the first in pop-ups. Open it by hand too: a camera button in the window adds or removes it, press and hold the icon and pick "Both cameras", or turn on "Home-screen icon opens both cameras" in Settings.
- **Activity log on the phone.** Every alert, watcher trigger and pop-up decision is written to a small on-device log, so you can see afterwards what happened while you were away.
- **Works away from home.** Anything that can reach the camera's LAN address works, for example a Tailscale subnet router.

The video comes straight from the camera over RTSP on your own network. Nothing goes through a third-party server.

## Requirements

- Android 8.0+ (tested on Android 16/17: OnePlus/ColorOS and a Samsung tablet)
- A Tapo camera with RTSP enabled: in the Tapo app open the camera, then Settings, Advanced Settings, **Camera Account**, and create a username and password
- The Tapo app installed and signed in on the same device, with notifications on for the camera (the pop-up reacts to Tapo's own alerts)

## Setup

1. Build or install the APK (see below).
2. Open **Stairs** (the app name is just a label), enter the camera account, tap **Find cameras on Wi-Fi**, and pick your camera. A preview plays so you can check it is the right one.
3. Set **Camera name in the Tapo app** to a word from the camera's name (it must appear in Tapo's alert text).
4. Allow the permissions the settings screen asks for:
   - **Display over other apps**: the floating window
   - **Notification access**: lets the app read Tapo's alerts
   - **Lock-screen alerts**: notifications plus *Full screen notifications*, to wake a locked device
5. Exclude the app from battery optimisation (see device notes).

## Device notes

Some vendors freeze or restrict background apps, so the lock-screen wake-up needs a few extra switches:

| Setting | Why |
|---|---|
| Allow background activity / "Never sleeping apps" | otherwise the system freezes the app while the screen is off and it misses the alert |
| Banners / pop-up notifications for the app **and** its "Movement on the stairs" notification type | the wake-up uses a full-screen notification, which only fires at high priority |
| Full screen notifications | Samsung and Pixel have a normal switch (Settings, Apps, Special access). ColorOS has none: turn on Developer options, "Disable system optimizations", run the command below, then turn the option off again |

```bash
adb shell appops set com.nishant.stairscam USE_FULL_SCREEN_INTENT allow
```

## Testing without waiting for motion

```bash
adb shell am broadcast -n com.nishant.stairscam/.TestTrigger --ei delay 15
```

This simulates a detection. Keep the 15 second delay: a broadcast sent from the shell is briefly allowed to open screens from the background, so an immediate test can pass where a real alert would be blocked.

## Build

Needs JDK 17+ and the Android SDK (Android Studio creates `local.properties` for you).

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How it works

| Piece | Role |
|---|---|
| `FloatService` | the floating window (overlay + foreground service), RTSP playback with Media3 |
| `DetectListener` | notification listener that watches the Tapo app's alerts |
| `LockCamActivity` | lock-screen camera view, launched by a full-screen notification |
| `CamActivity` | picture-in-picture fallback when the overlay permission is missing |
| `SettingsActivity` | camera account, camera finder, permissions, quiet hours, pause |

## Limitations and security

- Live view needs network access to the camera (same Wi-Fi, or VPN into it).
- It reacts to Tapo's alerts, so it only fires when Tapo sends one.
- The camera account is stored in the app's private storage on the device, not encrypted beyond Android's app sandbox. Use a dedicated camera account, not your TP-Link cloud password.
- RTSP here is unencrypted LAN traffic, as with any RTSP client.

## License

MIT, see [LICENSE](LICENSE).

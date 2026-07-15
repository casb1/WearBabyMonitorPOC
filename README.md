# Wear Baby Monitor POC 0.4

A two-module Android proof of concept that uses a Wear OS watch as a room-noise sensor and a paired Android phone as the alarm receiver.

> **Not a certified safety device.** Android, Wear OS, Bluetooth/Wi-Fi, permissions, notification settings, batteries, and hardware can fail. Use a dedicated baby monitor where dependable monitoring is required.

## Modules

- `watch`: records microphone audio locally, calibrates to the room, detects sustained sound, and sends alerts.
- `phone`: acknowledges alerts, raises audible/haptic alarms, and warns about lost heartbeats, stopped monitoring, and low watch battery.

The watch is deliberately **silent and non-vibrating**. All audible and haptic alerts happen on the phone only.

## Reliability included

- Eight-second room-noise calibration.
- Sustained RMS sound detection.
- Unique alert IDs, duplicate suppression, acknowledgements, and retries.
- Ten-second heartbeats with battery and monitoring state.
- Warning after 30 seconds without an established-session heartbeat.
- Warning after 45 seconds if the watch never connects.
- Immediate warning when the watch reports that monitoring stopped.
- One-shot low-battery warning at 20%, reset after charging above 25%.
- Local phone alarm test.
- End-to-end watch-to-phone test with acknowledgement.
- Android 15-compatible `connectedDevice` phone foreground service rather than the six-hour-limited `dataSync` type.

## Build on GitHub

1. Create an empty GitHub repository.
2. Upload this project at the repository root.
3. Push to `main` or run **Actions → Build Android APKs → Run workflow**.
4. Open the completed workflow run.
5. Download the `baby-monitor-debug-apks` artifact.
6. Extract and install:
   - `baby-monitor-phone-debug.apk` on the phone.
   - `baby-monitor-watch-debug.apk` on the watch.

GitHub Actions uses JDK 17, Gradle 8.9, Android SDK 35, and builds both APKs from the same checkout and debug signing identity. Matching package names and signatures are required for Wear OS Data Layer communication.

## First physical test

1. Install and open the phone app.
2. Grant notifications and tap **ENABLE RECEIVER**.
3. Tap **TEST PHONE ALARM** and verify the phone makes the expected sound and vibration.
4. Open **PHONE ALERT SETTINGS** and confirm the alert channel is not muted.
5. Install and open the watch app.
6. Grant microphone and notification permissions.
7. Tap **TEST PHONE** on the watch. The phone should alarm and the watch should display **Phone confirmed** without making sound or vibrating.
8. Tap **START** on the watch and keep the room quiet for eight seconds.
9. Make a sustained sound near the watch.

See [`docs/TEST_CHECKLIST.md`](docs/TEST_CHECKLIST.md) before relying on longer tests.

## Important source locations

- Detection and calibration: `watch/src/main/java/com/example/wearbabymonitor/NoiseMonitorService.kt`
- Watch test and controls: `watch/src/main/java/com/example/wearbabymonitor/MainActivity.kt`
- Phone alert receiver: `phone/src/main/java/com/example/wearbabymonitor/BabyMonitorListenerService.kt`
- Phone heartbeat watchdog: `phone/src/main/java/com/example/wearbabymonitor/PhoneWatchdogService.kt`
- GitHub build: `.github/workflows/android.yml`

## Current limitations

- Detection is amplitude-based, not cry classification.
- Calibration can be distorted if the room is loud during the initial eight seconds.
- Notification behavior remains partly user-controlled through Android channel settings and Do Not Disturb.
- There is no live audio stream.
- There is no encrypted application-level payload; Data Layer transport itself is restricted to matching package/signing identities and encrypted by Google Play services.
- The project has not yet been compiled or exercised on the target physical devices from this environment. The first GitHub Actions run is the compilation gate.
## v0.4.2 rebuild

This archive is a complete project with all files at the ZIP root. It replaces the deprecated Wear OS `BIND_LISTENER` manifest action and uses path-specific `MESSAGE_RECEIVED` filters. `VERSION.txt` confirms that the correct archive was extracted.


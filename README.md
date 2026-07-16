# Wear Baby Monitor POC v0.6.2

A deliberately small proof of concept with two Android application modules:

- `watch`: records microphone amplitude in a foreground service, calibrates against room noise, and sends an alert through the Wear OS Data Layer after sustained sound.
- `phone`: receives alerts, acknowledges them, produces the audible/vibrating alarm, and warns when watch heartbeat messages stop.

## Safety boundary

This is a technical proof of concept, not a certified baby monitor or safety device. It has not been validated for unattended use. Bluetooth, Google Play services, background restrictions, battery state, microphone availability, and notification settings can all interrupt delivery.

## Watch silence guarantee within this app

The watch app does not intentionally emit sound or vibration:

- its foreground notification channel has no sound and vibration disabled;
- its notification is explicitly silent;
- watch buttons and views have sound effects and haptic feedback disabled;
- all audible/vibrating alerts are created by the phone module only.

## Replacing an older broken checkout

After extracting this ZIP over the repository, run:

```bash
bash tools/remove_obsolete_tracked_files.sh
python3 tools/verify_source.py
```

The cleanup script removes old Kotlin files and the accidental `tener declarations"` terminal-output file before committing.

## Build

GitHub Actions runs a source preflight and then:

```bash
gradle --no-daemon --stacktrace --warning-mode all :phone:assembleDebug :watch:assembleDebug
```

The successful workflow artifact contains:

- `baby-monitor-phone-v0.6.2-debug.apk`
- `baby-monitor-watch-v0.6.2-debug.apk`

The workflow intentionally does not run lint as a build gate. Android compilation and APK packaging are the acceptance gate for this POC.

## First device test

1. Install the phone APK on the phone and the watch APK on the paired watch.
2. Open the phone app, grant notification permission, and tap **ENABLE RECEIVER**.
3. Open the watch app, grant microphone and notification permissions, and tap **START**.
4. Wait through calibration, then tap **TEST PHONE**.
5. Confirm the phone alarms and the watch displays **Phone confirmed** without sound or vibration.

See `docs/TEST_CHECKLIST.md` before longer testing.


## v0.6.0 usability pass

- The phone shows receiver, watch monitoring, and watch battery state without exposing heartbeat timestamps.
- A real noise event creates a persistent active-alert state until acknowledged in the phone app.
- The phone keeps the eight most recent alert and acknowledgement events with timestamps.
- The watch offers Low, Medium, and High sensitivity. Medium preserves the detector behavior from v0.5.1. Changes apply the next time monitoring starts.


## v0.6.2 watch reliability pass

- The watch screen is vertically scrollable so every control is reachable on a round display.
- Detection now measures how much sound activity occurs within a rolling 2.2-second interval. Brief quiet gaps no longer erase all progress.
- High favors intermittent quiet sounds, Medium stays near the previous working sensitivity, and Low requires more sustained activity.
- This remains general sound-activity detection, not baby-cry classification or machine learning.


## v0.6.2 small reliability pass

- The watch UI restores the actual monitoring/calibration state when reopened instead of always displaying Stopped.
- **Recalibrate Room** restarts the eight-second baseline calibration without a full stop/start cycle. Keep the room quiet during calibration.
- Fatal microphone read errors now stop monitoring and report an audio error instead of leaving a foreground service that appears active but cannot hear anything.

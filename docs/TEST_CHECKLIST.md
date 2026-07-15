# Physical-device test checklist

Do not treat the POC as dependable until these tests pass on the actual phone and Galaxy Watch 7.

## Installation and permissions

- Install phone and watch APKs produced by the same GitHub Actions run.
- Open the phone app, allow notifications, enable the receiver, and run **TEST PHONE ALARM**.
- Open **PHONE ALERT SETTINGS** and confirm sound and vibration are enabled on the phone.
- Open the watch app, grant microphone and notification permissions, and verify the watch remains silent.

## End-to-end transport

- Tap **TEST PHONE** on the watch.
- Confirm the phone alarms and the watch displays **Phone confirmed**.
- Repeat with Bluetooth disabled but both devices online over Wi-Fi to test Data Layer routing.
- Repeat with the phone screen off.

## Monitoring

- Start the watch in a quiet room and leave eight seconds for calibration.
- Produce a sustained voice-level sound near the watch.
- Confirm the phone alerts within a few seconds.
- Confirm a brief click or bump does not normally trigger it.
- Confirm the watch makes no sound and does not vibrate during any alert or warning.

## Failure paths

- Disable Bluetooth/network connectivity and confirm the phone warns within about 30 seconds after an established session.
- Enable the receiver without starting the watch and confirm the phone warns after about 45 seconds.
- Stop monitoring on the watch and confirm the phone warns that monitoring stopped.
- Force-stop the watch app and confirm the phone reports the stale heartbeat.
- Deny or revoke microphone permission and confirm the phone receives a stopped-monitoring warning when possible.
- Let the watch fall to 20% battery and confirm the phone warns only once, not every heartbeat.

## Endurance

- Run for a full nap.
- Run for at least six continuous hours to verify the phone service no longer hits the Android 15 `dataSync` timeout.
- Record watch battery drain per hour and phone battery drain per hour.
- Check Samsung battery settings if either app is stopped unexpectedly.

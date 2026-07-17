# Physical-device test checklist

## Build and installation

- GitHub Actions finishes the `Build phone and watch debug APKs` step successfully.
- The workflow provides separate PHONE-only and WATCH-only artifacts, each containing one APK.
- Extract the artifacts; do not install an artifact ZIP as an APK bundle.
- Install only the `INSTALL-ON-PHONE` APK on the phone and only the `INSTALL-ON-WATCH` APK on the watch.
- Phone and watch installs use APKs from the same workflow run.

## Silence on watch

- Starting monitoring produces no sound.
- Starting monitoring produces no vibration.
- Tapping START, STOP, and TEST PHONE produces no app-generated sound or haptic feedback.
- Noise detection and retry status changes remain visual-only on the watch.

## Alert path

- Phone receiver is enabled before starting the watch monitor.
- TEST PHONE causes a phone alarm.
- Watch changes to Phone confirmed after acknowledgement.
- Repeated delivery of one alert ID does not create duplicate phone alarms.
- With the phone receiver disabled, TEST PHONE ends with Test failed and produces no phone alarm.
- With the baby-monitor alert channel disabled in Android settings, TEST PHONE ends with Test failed and phone history records the blocked alert once.
- Re-enable the alert channel and confirm TEST PHONE succeeds again.

## Monitoring and failures

- Watch sends battery and monitoring heartbeat status.
- Phone warns after heartbeat loss.
- Phone warns when watch monitoring stops.
- Watch shows Phone disconnected when no paired phone node is available.
- Low watch battery status appears visually on watch and as a warning on phone.

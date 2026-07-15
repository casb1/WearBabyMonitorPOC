#!/usr/bin/env bash
set -euo pipefail

# Run after extracting the v0.5.0 ZIP over an existing checkout.
# It removes tracked files from older broken versions that are not part of v0.5.0.
allowed_file() {
  case "$1" in
    .github/workflows/android.yml|.gitignore|README.md|VERSION.txt|build.gradle.kts|gradle.properties|settings.gradle.kts|docs/TEST_CHECKLIST.md|phone/build.gradle.kts|phone/src/main/AndroidManifest.xml|phone/src/main/java/com/example/wearbabymonitor/BabyMonitorListenerService.java|phone/src/main/java/com/example/wearbabymonitor/MainActivity.java|phone/src/main/java/com/example/wearbabymonitor/NotificationChannels.java|phone/src/main/java/com/example/wearbabymonitor/PhoneWatchdogService.java|phone/src/main/java/com/example/wearbabymonitor/Protocol.java|watch/build.gradle.kts|watch/src/main/AndroidManifest.xml|watch/src/main/java/com/example/wearbabymonitor/MainActivity.java|watch/src/main/java/com/example/wearbabymonitor/NoiseMonitorService.java|watch/src/main/java/com/example/wearbabymonitor/Protocol.java|watch/src/main/java/com/example/wearbabymonitor/WatchMessageListenerService.java|tools/remove_obsolete_tracked_files.sh|tools/verify_source.py)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

while IFS= read -r -d '' file; do
  if ! allowed_file "$file"; then
    rm -rf -- "$file"
    printf 'Removed obsolete tracked path: %s\n' "$file"
  fi
done < <(git -c core.quotePath=false ls-files -z)

# Remove now-empty source/resource directories left by the Kotlin version.
find phone watch -type d -empty -delete 2>/dev/null || true

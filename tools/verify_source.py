#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
errors: list[str] = []

for manifest in [
    root / "phone/src/main/AndroidManifest.xml",
    root / "watch/src/main/AndroidManifest.xml",
]:
    try:
        ET.parse(manifest)
    except Exception as exc:
        errors.append(f"Invalid XML in {manifest.relative_to(root)}: {exc}")

all_text = "\n".join(
    path.read_text(encoding="utf-8")
    for path in root.rglob("*")
    if path.is_file() and path.suffix in {".java", ".xml", ".kts", ".yml"}
)

if "com.google.android.gms.wearable.BIND_LISTENER" in all_text:
    errors.append("Deprecated BIND_LISTENER action is still present")

expected = {
    "/baby_noise": root / "phone/src/main/AndroidManifest.xml",
    "/baby_status": root / "phone/src/main/AndroidManifest.xml",
    "/baby_ack": root / "watch/src/main/AndroidManifest.xml",
}
for path, manifest in expected.items():
    if path not in manifest.read_text(encoding="utf-8"):
        errors.append(f"{path} is missing from {manifest.relative_to(root)}")

watch_source = (root / "watch/src/main/java/com/example/wearbabymonitor/NoiseMonitorService.java").read_text(encoding="utf-8")
for required in [
    "channel.setSound(null, null)",
    "channel.enableVibration(false)",
]:
    if required not in watch_source:
        errors.append(f"Watch silence guard missing: {required}")

kotlin_files = list(root.rglob("*.kt"))
if kotlin_files:
    errors.append("Unexpected Kotlin source remains: " + ", ".join(str(p.relative_to(root)) for p in kotlin_files))

if errors:
    print("Source preflight failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print("Source preflight passed")

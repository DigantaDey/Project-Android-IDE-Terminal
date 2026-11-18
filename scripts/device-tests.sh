#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/reports/device-screenshots
capture() {
  status=$?
  adb logcat -d -t 1800 > app/build/reports/device-logcat.txt || true
  if [[ "${FORGE_VISUAL_REPORT:-false}" == "true" ]]; then
    adb logcat -d -s ForgeVisual:I '*:S' > app/build/reports/device-visual.log || true
  fi
  adb pull /sdcard/Android/data/com.forgeide.app/files/test-screenshots app/build/reports/device-screenshots || true
  adb pull /data/local/tmp/forge-workspace.png app/build/reports/device-screenshots/workspace.png || true
  adb shell rm -f /data/local/tmp/forge-workspace.png || true
  exit "$status"
}
trap capture EXIT
adb shell rm -f /data/local/tmp/forge-workspace.png
args=()
if [[ "${FORGE_VISUAL_REPORT:-false}" == "true" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.visualReport=true")
fi
./gradlew :app:connectedDebugAndroidTest "${args[@]}" --stacktrace 2>&1 | tee device-ci.log
python3 scripts/device-test-summary.py app/build/outputs/androidTest-results

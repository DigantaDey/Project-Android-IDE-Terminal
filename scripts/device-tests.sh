#!/usr/bin/env bash
# Run only on disposable test installations: instrumentation uses fixture projects and a fake vault token.
set -euo pipefail
results=app/build/outputs/androidTest-results/instrumentation
mkdir -p app/build/reports/device-screenshots "$results"
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
if [[ "${FORGE_PREBUILT_APKS:-false}" != "true" ]]; then
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
fi
# CI downloads these exact APKs from its successful build job. Device jobs never resolve Maven dependencies.
app=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
test -s "$app"
test -s "$tests"
adb install -r -t "$app"
adb install -r -t "$tests"
adb shell rm -f /data/local/tmp/forge-workspace.png
args=()
if [[ "${FORGE_VISUAL_REPORT:-false}" == "true" ]]; then
  args+=(-e visualReport true)
fi
rm -f "$results/TEST-device.xml"
adb shell am instrument -w -r "${args[@]}" com.forgeide.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee device-ci.log
python3 scripts/instrumentation-report.py device-ci.log "$results/TEST-device.xml"
python3 scripts/device-test-summary.py "$results"

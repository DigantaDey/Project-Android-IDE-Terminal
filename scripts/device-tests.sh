#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/reports/device-screenshots
capture() {
  status=$?
  adb logcat -d -t 1800 > app/build/reports/device-logcat.txt || true
  adb pull /sdcard/Android/data/com.forgeide.app/files/test-screenshots app/build/reports/device-screenshots || true
  exit "$status"
}
trap capture EXIT
./gradlew :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee device-ci.log

#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
cd "$AIRLOOM_ROOT"
./scripts/build.sh --max-workers=1 -Pandroid.experimental.lint.useK2Uast=false :core:test :data:testDebugUnitTest :playback:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || "$1" != --android || "$2" != emulator-* ]]; then
    echo 'Usage: scripts/check.sh [--android emulator-SERIAL]. Use an isolated test AVD.' >&2
    exit 1
  fi
  airloom_test_serial="$2"
  adb -s "$airloom_test_serial" install -r -t --no-streaming app/build/outputs/apk/debug/app-debug.apk
  adb -s "$airloom_test_serial" install -r -t --no-streaming app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb -s "$airloom_test_serial" install -r -t --no-streaming data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk
  airloom_report_dir="$AIRLOOM_ROOT/artifacts/check"
  mkdir -p "$airloom_report_dir"
  adb -s "$airloom_test_serial" shell am instrument -w -e notClass tv.airloom.data.CataloguePerformanceTest tv.airloom.data.test/androidx.test.runner.AndroidJUnitRunner | tee "$airloom_report_dir/data.txt"
  rg -q '^OK \(' "$airloom_report_dir/data.txt"
  ! rg -q 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$airloom_report_dir/data.txt"
  adb -s "$airloom_test_serial" shell am instrument -w tv.airloom.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$airloom_report_dir/app.txt"
  rg -q '^OK \(' "$airloom_report_dir/app.txt"
  ! rg -q 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$airloom_report_dir/app.txt"
fi

#!/usr/bin/env bash
# Runs the end-to-end test on the booted emulator and collects logs and screenshots.
# Uses `am instrument` directly (instead of connectedAndroidTest) so the app is not
# uninstalled before the screenshots are pulled off the device.
set -euo pipefail
mkdir -p logs screens

gradle --no-daemon :app:installDebug :app:installDebugAndroidTest 2>&1 | tee logs/install.log

adb logcat -c || true
adb logcat -v time > logs/logcat.txt 2>&1 &
LOGCAT_PID=$!

set +e
adb shell am instrument -w -e class dev.ahmed.iptv.EndToEndTest \
  dev.ahmed.iptv.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee logs/e2e.log
set -e

sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true
grep -E "dev\.ahmed|ExoPlayer|AndroidRuntime|ffmpeg|FFmpeg|MediaCodec|TestRunner|System\.err|CCodec" logs/logcat.txt \
  | tail -n 4000 > logs/logcat-filtered.txt || true

adb exec-out run-as dev.ahmed.iptv tar c files/screens > screens.tar 2>/dev/null || true
tar xf screens.tar -C screens --strip-components=2 2>/dev/null || true
ls -la screens || true

grep -q "OK (1 test)" logs/e2e.log

#!/usr/bin/env bash
# Task B acceptance: 10-minute capture -> encoder soak on the phone, watching for crashes,
# memory growth and MediaProjection token reuse errors. Run with the phone on USB debugging,
# start "Test pattern" or "Mirror phone" in the app, then:
#   android-sender/test/soak-encoder.sh [minutes] [package]
set -euo pipefail
MIN="${1:-10}"; PKG="${2:-com.rokidmirror.sender.debug}"
adb logcat -c
END=$(( $(date +%s) + MIN*60 ))
echo "time,pss_kb,fps,kbps,encode_ms,frames"
while [ "$(date +%s)" -lt "$END" ]; do
  PSS=$(adb shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS:/ {print $3; exit}')
  LINE=$(adb logcat -d -s RokidMirror/Encoder | grep encoder_stats | tail -1 || true)
  FPS=$(echo "$LINE" | grep -o 'fps=[0-9.]*' | cut -d= -f2); KB=$(echo "$LINE" | grep -o 'kbps=[0-9]*' | cut -d= -f2)
  ENC=$(echo "$LINE" | grep -o 'encode_ms=[0-9.]*' | cut -d= -f2); FR=$(echo "$LINE" | grep -o 'frame=[0-9]*' | cut -d= -f2)
  echo "$(date +%H:%M:%S),${PSS:-},${FPS:-},${KB:-},${ENC:-},${FR:-}"
  sleep 10
done
echo "--- crashes / projection errors ---"
adb logcat -d | grep -E "FATAL EXCEPTION|SecurityException.*projection|Media projections require a foreground service" || echo "none"

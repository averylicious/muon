#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo "Usage: $0 ADB_SERIAL http://LAN_IP:7814 TRACK_ID" >&2
  exit 2
fi
serial=$1
origin=$2
track=$3
[[ "$track" =~ ^[0-9]+$ ]]
origin_pattern='^https?://[][0-9a-fA-F:.]+$'
[[ "$origin" =~ $origin_pattern ]] || { echo "Use a numeric LAN origin" >&2; exit 2; }
if [[ "$serial" == *:* ]]; then adb connect "$serial"; fi
adb -s "$serial" get-state
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$(mktemp)
trap 'rm -f "$result"' EXIT
adb -s "$serial" shell am instrument -w -r \
  -e class dev.avery.muon.DirectPlaybackTest \
  -e tauonOrigin "$origin" -e trackId "$track" \
  dev.avery.muon.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
# am instrument can exit zero even when a test fails.
rg -q 'OK \(1 test\)' "$result"

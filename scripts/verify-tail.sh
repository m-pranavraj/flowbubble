#!/usr/bin/env bash
set -u
export MSYS_NO_PATHCONV=1
ADB="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S="emulator-5556"

echo "=== HISTORY (the honest outcome log) ==="
"$ADB" -s "$S" shell run-as com.flowbubble.app cat shared_prefs/flowbubble_history.xml 2>/dev/null \
  | tr ',' '\n' | grep -E 'kind|summary|status' | sed 's/&#34;/"/g' | head -60

echo "=== ROUTINES ==="
"$ADB" -s "$S" shell run-as com.flowbubble.app cat shared_prefs/flowbubble_routines.xml 2>/dev/null | head -10

echo "=== PREFS ==="
"$ADB" -s "$S" shell run-as com.flowbubble.app cat shared_prefs/flowbubble_prefs.xml 2>/dev/null | head -20
"$ADB" -s "$S" logcat -d | grep -iE "genai|speechrecogn|FlowBubbleDebug" | tail -15

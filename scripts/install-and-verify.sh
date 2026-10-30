#!/usr/bin/env bash
set -u
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ADB_EXE="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL="emulator-5556"
APK="D:/mobileos/app/build/outputs/apk/debug/app-debug.apk"
OUT="D:/mobileos/verify"
mkdir -p "$OUT"

adb() { "$ADB_EXE" -s "$SERIAL" "$@"; }

say() {
  local phrase="$1"
  local tag="$2"
  adb shell "am broadcast -n com.flowbubble.app/.DebugSimulateReceiver --es text '$phrase'" >/dev/null 2>&1
  sleep "${3:-8}"
  adb exec-out screencap -p > "$OUT/$tag.png" 2>/dev/null
  echo "--- said: $phrase"
  adb shell dumpsys window 2>/dev/null | grep -i mCurrentFocus | head -1
  adb logcat -d -s FlowBubbleDebug:V 2>/dev/null | grep -F "text=$phrase" | tail -1
  adb logcat -d -s AndroidRuntime:E 2>/dev/null | tail -3
}

echo "=== install ==="
adb install -r "$APK" 2>&1 | tail -1

echo "=== permissions ==="
adb shell pm grant com.flowbubble.app android.permission.RECORD_AUDIO 2>/dev/null
adb shell pm grant com.flowbubble.app android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell appops set com.flowbubble.app SYSTEM_ALERT_WINDOW allow 2>/dev/null
adb shell settings put secure enabled_accessibility_services com.flowbubble.app/com.flowbubble.app.access.FlowAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.flowbubble.app/com.flowbubble.app.notifications.FlowNotificationService

echo "=== fresh app start ==="
adb shell am force-stop com.flowbubble.app
adb shell pm clear com.flowbubble.app >/dev/null 2>&1
adb shell pm grant com.flowbubble.app android.permission.RECORD_AUDIO
adb shell pm grant com.flowbubble.app android.permission.POST_NOTIFICATIONS
adb shell appops set com.flowbubble.app SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services com.flowbubble.app/com.flowbubble.app.access.FlowAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.flowbubble.app/com.flowbubble.app.notifications.FlowNotificationService 2>/dev/null
adb shell cmd notification allow_dnd com.flowbubble.app 2>/dev/null
adb shell am start -n com.flowbubble.app/.MainActivity >/dev/null 2>&1
sleep 5
adb shell am start-foreground-service -n com.flowbubble.app/com.flowbubble.app.service.OverlayService -a com.flowbubble.app.action.START >/dev/null 2>&1
sleep 3
echo "bubble windows: $(adb shell dumpsys window windows 2>/dev/null | grep -c 'com.flowbubble.app')"
adb exec-out screencap -p > "$OUT/00_bubble.png"

adb logcat -c

echo "=== TEST: open any app by alias ==="
say 'open chrome' 01_open_chrome 10

echo "=== TEST: generic in-app search ==="
adb shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 4
say 'search this app for airplane mode' 02_search_settings 14

echo "=== TEST: read the screen ==="
say 'read this screen' 03_read_screen 12

echo "=== TEST: gesture vocabulary ==="
say 'open the menu' 04_open_menu 9
say 'swipe left' 05_swipe_left 9
say 'scroll down twice' 06_scroll 9
say 'dismiss the popup' 07_dismiss 9

echo "=== TEST: code-mixed + typo rescue ==="
say 'open youtube' 08_open_youtube 10
say 'kholo flipkart' 09_kholo 10
say 'opem youube' 10_rescue 10

echo "=== TEST: follow-ups and routines ==="
say 'save this as routine morning brief' 11_save_routine 8
say 'run routine morning brief' 12_run_routine 10

echo "=== TEST: router gate (notification listener revoked) ==="
adb shell cmd notification deny_listener com.flowbubble.app/com.flowbubble.app.notifications.FlowNotificationService 2>/dev/null
sleep 2
say 'read my notifications' 13_gate_notifications 8
adb shell cmd notification allow_listener com.flowbubble.app/com.flowbubble.app.notifications.FlowNotificationService 2>/dev/null
sleep 2

echo "=== TEST: router still lets a no-permission command through ==="
say 'set a timer for 2 minutes' 14_timer_no_gate 8
say 'turn on do not disturb for 1 minute' 14b_dnd 10

echo "=== TEST: agent trace with per-step evidence ==="
adb shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 4
say 'open chrome and search for weather' 15_agent_trace 20

bash D:/mobileos/scripts/verify-tail.sh

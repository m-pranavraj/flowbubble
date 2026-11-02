#!/usr/bin/env bash
# Focused device check for the three fixes: misheard-verb rescue, no phantom
# "Insertion failed", and whether a cold app open needs a fixed wait or a polled one.
set -u
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ADB_EXE="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL="emulator-5556"
APK="D:/mobileos/app/build/outputs/apk/debug/app-debug.apk"
OUT="D:/mobileos/verify"
mkdir -p "$OUT"

adb() { "$ADB_EXE" -s "$SERIAL" "$@"; }

hist() {
  adb shell run-as com.flowbubble.app cat shared_prefs/flowbubble_history.xml 2>/dev/null \
    | sed 's/&quot;/"/g' | tr ',' '\n' | grep -E '"(kind|summary|status)"' \
    | sed 's/^ *"//; s/":$//; s/": //' | head -"${1:-9}"
}

# The grant can be dropped by anything that touches the package state, and a probe that silently
# runs without it reports "permission missing" for every command, which looks like an app bug.
# Match "Bound services:" - our package also appears in the client list while unbound, so a plain
# grep reports a false positive.
a11y_bound() {
  adb shell dumpsys accessibility 2>/dev/null \
    | sed -n '/Bound services:/p' | grep -q "Your Bubble voice access"
}

ensure_a11y() {
  if a11y_bound; then return 0; fi
  local current
  current=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
  echo "    [a11y grant lost, re-applying; was '$current']"
  adb shell settings put secure enabled_accessibility_services \
    com.flowbubble.app/com.flowbubble.app.access.FlowAccessibilityService >/dev/null 2>&1
  adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
  for _ in 1 2 3 4 5 6 7 8; do
    sleep 1
    if a11y_bound; then return 0; fi
  done
  echo "    [a11y service still not bound]"
}

say() {
  local phrase="$1" tag="$2"
  ensure_a11y
  adb shell "am broadcast -n com.flowbubble.app/.DebugSimulateReceiver --es text '$phrase'" >/dev/null 2>&1
  sleep "${3:-9}"
  adb exec-out screencap -p > "$OUT/fix_$tag.png" 2>/dev/null
  echo "--- said: $phrase"
  adb shell dumpsys window 2>/dev/null | grep -i mCurrentFocus | head -1
  echo "    newest history:"
  hist 9 | sed 's/^/      /'
}

echo "=== install + permissions ==="
adb install -r "$APK" 2>&1 | tail -1
adb shell pm clear com.flowbubble.app >/dev/null 2>&1
adb shell pm grant com.flowbubble.app android.permission.RECORD_AUDIO
adb shell pm grant com.flowbubble.app android.permission.POST_NOTIFICATIONS
adb shell appops set com.flowbubble.app SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services com.flowbubble.app/com.flowbubble.app.access.FlowAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.flowbubble.app/com.flowbubble.app.notifications.FlowNotificationService
adb shell am start -n com.flowbubble.app/.MainActivity >/dev/null 2>&1
sleep 5
adb shell am start-foreground-service -n com.flowbubble.app/com.flowbubble.app.service.OverlayService -a com.flowbubble.app.action.START >/dev/null 2>&1
sleep 3
adb logcat -c
# A heads-up notification sits over the app bar and hides the search icon, which is not what is
# under test here.
adb shell settings put global heads_up_notifications_enabled 0 >/dev/null 2>&1

echo "=== FIX 1: misheard verb must suggest the right app ==="
say 'opem youube' 1_rescue 10

echo "=== FIX 2: an unmatched app request must not also log a failed insertion ==="
say 'kholo flipkart' 2_kholo 10

echo "=== FIX 3 probe: chain that must open search from an icon (Settings) ==="
adb shell am force-stop com.android.settings >/dev/null 2>&1
adb shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1
sleep 1
# The chain needs ~15 s uninterrupted; the next probe presses BACK, which aborts a running chain
# and leaves only "requested" in the history.
say 'open settings and search for display' 3_chain_settings 34

echo "=== FIX 3 probe: same idea in a page that has no search at all ==="
# Any pending confirmation would swallow the next broadcast, and the previous probe leaves the
# Settings search screen up - which does have a field, so the premise would be wrong.
adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 1
adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 1
adb shell am start -a android.settings.DEVICE_INFO_SETTINGS >/dev/null 2>&1
sleep 4
say 'search this app for airplane mode' 4_no_search 16

echo "=== FIX 3 probe: cold YouTube chain (may show an update dialog) ==="
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2
adb shell am force-stop com.google.android.youtube >/dev/null 2>&1
sleep 1
say 'open youtube and search for lofi beats' 5_chain_cold 30

echo "=== the founder's example: open chrome and search for weather ==="
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2
adb shell am force-stop com.android.chrome >/dev/null 2>&1
sleep 1
say 'open chrome and search for weather' 6_chain_chrome 34

echo "=== FINAL HISTORY ==="
hist 30
echo "=== CRASHES ==="
adb logcat -d -s AndroidRuntime:E 2>/dev/null | tail -20

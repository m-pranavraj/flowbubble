#!/usr/bin/env bash
# Measures whether the chain's screen wait leaves as soon as the screen settles instead of
# sleeping a fixed 900 ms. Order matters: pm clear must happen BEFORE the accessibility grant,
# otherwise the grant is wiped and every command reports "Voice access is off".
set -u
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ADB_EXE="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL="emulator-5556"
APK="D:/mobileos/app/build/outputs/apk/debug/app-debug.apk"
OUT="D:/mobileos/verify"
mkdir -p "$OUT"

adb() { "$ADB_EXE" -s "$SERIAL" "$@"; }

a11y_bound() {
  adb shell dumpsys accessibility 2>/dev/null \
    | sed -n '/Bound services:/p' | grep -q "Your Bubble voice access"
}

# The system reconciles enabled services asynchronously after pm clear, so a grant written too
# soon is reverted. Wait for that to finish, then set the grant and require a real binding.
grant_a11y() {
  # A fresh install can bind the service in a dead state, so flip the switch off and on the way a
  # person would in Settings instead of only asserting it is enabled.
  adb shell settings put secure enabled_accessibility_services "" >/dev/null 2>&1
  sleep 2
  adb shell settings put secure enabled_accessibility_services \
    com.flowbubble.app/com.flowbubble.app.access.FlowAccessibilityService >/dev/null 2>&1
  adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
  for _ in $(seq 1 30); do
    sleep 1
    if a11y_bound && ! adb shell settings get secure enabled_accessibility_services | grep -q null; then
      return 0
    fi
  done
  return 1
}

echo "=== clean install ==="
adb install -r "$APK" 2>&1 | tail -1
adb shell pm clear com.flowbubble.app >/dev/null 2>&1
sleep 4
adb shell pm grant com.flowbubble.app android.permission.RECORD_AUDIO
adb shell pm grant com.flowbubble.app android.permission.POST_NOTIFICATIONS
adb shell appops set com.flowbubble.app SYSTEM_ALERT_WINDOW allow

echo "=== a11y grant (after the clear settles) ==="
if ! grant_a11y; then echo "ABORT: service never bound"; exit 1; fi
echo "  access: bound"

adb shell cmd notification allow_listener \
  com.flowbubble.app/com.flowbubble.app.notifications.FlowNotificationService >/dev/null 2>&1
adb shell settings put global heads_up_notifications_enabled 0 >/dev/null 2>&1
adb shell am start -n com.flowbubble.app/.MainActivity >/dev/null 2>&1
sleep 5
adb shell am start-foreground-service \
  -n com.flowbubble.app/com.flowbubble.app.service.OverlayService -a com.flowbubble.app.action.START >/dev/null 2>&1
sleep 3
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 1

echo "=== chain: open chrome and search for weather ==="
adb shell am force-stop com.android.chrome >/dev/null 2>&1
sleep 1
if ! a11y_bound; then echo "  re-granting right before the command"; grant_a11y || exit 1; fi
adb logcat -c
started=$(date +%s%3N)
adb shell "am broadcast -n com.flowbubble.app/.DebugSimulateReceiver --es text 'open chrome and search for weather'" >/dev/null 2>&1
sleep 11
adb exec-out screencap -p > "$OUT/timing_mid.png" 2>/dev/null
sleep 9
finished=$(date +%s%3N)
adb exec-out screencap -p > "$OUT/timing_panel.png" 2>/dev/null

echo "  wall clock for the whole chain: $((finished - started)) ms"
echo "=== panel text ==="
adb shell uiautomator dump /sdcard/panel.xml >/dev/null 2>&1
adb shell cat /sdcard/panel.xml 2>/dev/null \
  | sed 's/></>\n</g' | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//' \
  | grep -v '^$' | head -40

echo "=== focus ==="
adb shell dumpsys window 2>/dev/null | grep -i mCurrentFocus | head -1

echo "=== history ==="
adb shell run-as com.flowbubble.app cat shared_prefs/flowbubble_history.xml 2>/dev/null \
  | sed 's/&quot;/"/g' | tr ',' '\n' | grep -E '"(kind|summary|status)"' \
  | sed 's/^ *"//; s/":$//; s/": //' | head -12

echo "=== crashes ==="
adb logcat -d -s AndroidRuntime:E 2>/dev/null | tail -10

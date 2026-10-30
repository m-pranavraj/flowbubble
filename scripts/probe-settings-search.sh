#!/usr/bin/env bash
# Measures how long Settings takes to expose a real editable node after its search pill is tapped.
# Prints the editable count at each poll so a fixed settle delay can be replaced with a real number.
set -u
export MSYS_NO_PATHCONV=1
ADB="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S="emulator-5556"
adb() { "$ADB" -s "$S" "$@"; }

adb shell am force-stop com.android.settings
adb shell am force-stop com.google.android.settings.intelligence
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1
sleep 3

adb shell uiautomator dump /sdcard/fb.xml >/dev/null 2>&1
XML=$(adb shell cat /sdcard/fb.xml 2>/dev/null | tr -d '\r')
echo "=== home: nodes mentioning search ==="
echo "$XML" | tr '<' '\n<' | grep -i "search" | sed -E 's/.*(resource-id="[^"]*").*(text|content-desc)="[^"]*".*(clickable="[^"]*").*(bounds="[^"]*").*/\1 \2 \3 \4/' | head -6
BOUNDS=$(echo "$XML" | tr '<' '\n<' | grep -i 'Search settings' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
echo "pill bounds: $BOUNDS"
[ -z "$BOUNDS" ] && { echo "no search pill on home - aborting"; exit 1; }
read -r X1 Y1 X2 Y2 <<<"$(echo "$BOUNDS" | grep -oE '[0-9]+' | tr '\n' ' ')"
CX=$(( (X1 + X2) / 2 )); CY=$(( (Y1 + Y2) / 2 ))
echo "tapping $CX $CY"
adb shell input tap "$CX" "$CY"

for t in 0.3 0.7 1.2 2.0 3.0 4.5; do
  sleep "$t"
  adb shell uiautomator dump /sdcard/fb.xml >/dev/null 2>&1
  D=$(adb shell cat /sdcard/fb.xml 2>/dev/null | tr -d '\r')
  EDIT=$(echo "$D" | tr '<' '\n<' | grep -c 'class="android.widget.EditText"')
  FOCUS=$(adb shell dumpsys window 2>/dev/null | grep -i mCurrentFocus | head -1 | grep -oE '[a-z.]+/[.a-zA-Z]+')
  HINT=$(echo "$D" | tr '<' '\n<' | grep 'class="android.widget.EditText"' | head -1 | grep -oE '(text|hint|focused)="[^"]*"' | tr '\n' ' ')
  echo "  after ${t}s: EditText=$EDIT focus=$FOCUS first=[$HINT]"
done

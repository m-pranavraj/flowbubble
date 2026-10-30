#!/usr/bin/env bash
# Device proof for Hindi commands, injected as the recognizer would emit them:
#   1. "यूट्यूब खोलो"                 -> YouTube in the foreground
#   2. "क्रोम में मौसम खोजो"            -> in-app search inside Chrome
#   3. "प्रणव से चैट करो"               -> Messages conversation with Pranav
#   4. "आवाज़ बढ़ाओ"                    -> exact media volume reported back
#   5. "पाँच मिनट का टाइमर लगाओ"        -> timer request
set -u
ADB="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="-s emulator-5556"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.flowbubble.app"
LABEL="FlowBubble voice access"
OUT="verify/hindi"
mkdir -p "$OUT"

a11y_bound() {
  "$ADB" $DEV shell dumpsys accessibility 2>/dev/null \
    | sed -n '/Bound services:/p' | grep -q "$LABEL"
}

say() {
  "$ADB" $DEV shell "am broadcast -p $PKG -a $PKG.DEBUG_SIMULATE --es text '$1'" >/dev/null 2>&1
}

focus() {
  "$ADB" $DEV shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus
}

# Screenshots taken on a fixed sleep kept catching the launcher mid-transition.
wait_focus() {
  local pkg="$1" seconds="${2:-12}" i=0
  while [ "$i" -lt "$seconds" ]; do
    if focus | grep -q "$pkg"; then return 0; fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

history() {
  "$ADB" $DEV shell run-as "$PKG" cat shared_prefs/flowbubble_history.xml 2>/dev/null \
    | grep -o '<string name="entries">[^<]*</string>' | head -c 500
  echo
}

shot() {
  "$ADB" $DEV exec-out screencap -p > "$OUT/$1.png" 2>/dev/null
}

echo "== install =="
"$ADB" $DEV shell am force-stop "$PKG"
"$ADB" $DEV install -r "$APK"

echo "== accessibility =="
if ! a11y_bound; then
  "$ADB" $DEV shell settings put secure enabled_accessibility_services "$PKG/$PKG.access.FlowAccessibilityService"
  "$ADB" $DEV shell settings put secure accessibility_enabled 1
fi
"$ADB" $DEV shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2
"$ADB" $DEV shell am start-foreground-service "$PKG/.service.OverlayService" >/dev/null 2>&1
sleep 1
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 1
a11y_bound && echo "a11y bound" || echo "a11y NOT bound"

echo "== 1. youtube kholo =="
say $'\u092F\u0942\u091F\u094D\u092F\u0942\u092C \u0916\u094B\u0932\u094B'
wait_focus com.google.android.youtube 12
sleep 1
focus
shot 1-youtube
history

echo "== 2. chrome mein mausam khojo =="
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 1
say $'\u0915\u094D\u0930\u094B\u092E \u092E\u0947\u0902 \u092E\u094C\u0938\u092E \u0916\u094B\u091C\u094B'
wait_focus com.android.chrome 15
sleep 10
focus
shot 2-chrome-search
history

echo "== 3. pranav se chat karo =="
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 1
say $'\u092A\u094D\u0930\u0923\u0935 \u0938\u0947 \u091A\u0948\u091F \u0915\u0930\u094B'
wait_focus com.google.android.apps.messaging 10
sleep 4
focus
shot 3-chat
history

echo "== 4. aavaaz badhao =="
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 1
BEFORE=$("$ADB" $DEV shell cmd media_session volume --stream 3 --get 2>/dev/null)
say $'\u0906\u0935\u093E\u091C\u093C \u092C\u0922\u093C\u093E\u0913'
sleep 3
AFTER=$("$ADB" $DEV shell cmd media_session volume --stream 3 --get 2>/dev/null)
echo "volume before=$BEFORE after=$AFTER"
shot 4-volume
history

echo "== 5. timer =="
say $'\u092A\u093E\u0901\u091A \u092E\u093F\u0928\u091F \u0915\u093E \u091F\u093E\u0907\u092E\u0930 \u0932\u0917\u093E\u0913'
sleep 4
focus
# The countdown appears in the status bar, so leave the launcher visible for the shot.
shot 5-timer
history

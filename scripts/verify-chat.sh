#!/usr/bin/env bash
# Device proof for chat opening:
#   1. "go to chat of pranav"      -> Messages conversation, recorded as done
#   2. "chat with pranav on whatsapp" -> honest "not installed" panel, then its Messages fallback
#   3. a contact with no number    -> honest "no number" panel
set -u
ADB="C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="-s emulator-5556"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.flowbubble.app"
LABEL="FlowBubble voice access"
OUT="verify/chat"
mkdir -p "$OUT"

a11y_bound() {
  "$ADB" $DEV shell dumpsys accessibility 2>/dev/null \
    | sed -n '/Bound services:/p' | grep -q "$LABEL"
}

say() {
  "$ADB" $DEV shell "am broadcast -p $PKG -a $PKG.DEBUG_SIMULATE --es text '$1'" >/dev/null 2>&1
}

history() {
  "$ADB" $DEV shell run-as "$PKG" cat shared_prefs/flowbubble_history.xml 2>/dev/null \
    | grep -o '<string name="entries">[^<]*</string>' | head -c 700
  echo
}

echo "== install =="
"$ADB" $DEV shell am force-stop "$PKG"
"$ADB" $DEV install -r "$APK"

echo "== contacts =="
PHONES=$("$ADB" $DEV shell 'content query --uri content://com.android.contacts/data \
  --projection raw_contact_id:mimetype:data1' 2>/dev/null | grep -c phone_v2)
if [ "$PHONES" -eq 0 ]; then
  "$ADB" $DEV shell 'content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:1 --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Pranav' >/dev/null 2>&1
  "$ADB" $DEV shell 'content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:1 --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
    --bind data1:s:+919876543210 --bind data2:i:2' >/dev/null 2>&1
fi
"$ADB" $DEV shell 'content query --uri content://com.android.contacts/data \
  --projection raw_contact_id:mimetype:data1' 2>/dev/null | sed 's/^/  /'

echo "== permissions =="
"$ADB" $DEV shell pm grant "$PKG" android.permission.READ_CONTACTS

echo "== accessibility =="
"$ADB" $DEV shell settings put secure enabled_accessibility_services '""'
"$ADB" $DEV shell settings put secure accessibility_enabled 0
sleep 2
"$ADB" $DEV shell settings put secure enabled_accessibility_services \
  "$PKG/$PKG.access.FlowAccessibilityService"
"$ADB" $DEV shell settings put secure accessibility_enabled 1
for _ in $(seq 1 40); do a11y_bound && break; sleep 1; done
if ! a11y_bound; then echo "FAIL: accessibility never bound"; exit 1; fi
echo "  bound"

echo "== start overlay =="
"$ADB" $DEV shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
"$ADB" $DEV shell am start-foreground-service -n "$PKG/.service.OverlayService" \
  -a "$PKG.action.START" >/dev/null 2>&1
sleep 2
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 1

echo "== 1: go to chat of pranav =="
"$ADB" $DEV logcat -c
say "go to chat of pranav"
sleep 8
"$ADB" $DEV exec-out screencap -p > "$OUT/1-chat-8s.png"
"$ADB" $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1 | sed 's/^/  /'
history

echo "== 2: chat with pranav on whatsapp =="
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 2
say "chat with pranav on whatsapp"
sleep 3
"$ADB" $DEV exec-out screencap -p > "$OUT/2-whatsapp-missing.png"
"$ADB" $DEV shell input tap 540 1852
sleep 8
"$ADB" $DEV exec-out screencap -p > "$OUT/2b-messages-fallback.png"
history

echo "== 3: a contact with no number =="
"$ADB" $DEV shell 'content insert --uri content://com.android.contacts/raw_contacts \
  --bind account_type:s: --bind account_name:s:' >/dev/null 2>&1
NEW=$("$ADB" $DEV shell 'content query --uri content://com.android.contacts/raw_contacts \
  --projection _id' 2>/dev/null | tail -1 | grep -o '_id=[0-9]*' | cut -d= -f2)
if [ -n "$NEW" ]; then
  "$ADB" $DEV shell "content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:$NEW --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'Pranav Old'" \
    >/dev/null 2>&1
fi
"$ADB" $DEV shell input keyevent KEYCODE_HOME
sleep 1
say "go to chat of pranav old"
sleep 3
"$ADB" $DEV exec-out screencap -p > "$OUT/3-no-number.png"
history

echo "== crash check =="
"$ADB" $DEV shell dumpsys accessibility 2>/dev/null | grep -i 'Crashed services' | sed 's/^/  /'
echo "== debug log =="
"$ADB" $DEV logcat -d -s FlowBubbleDebug 2>/dev/null | tail -8 | sed 's/^/  /'
echo "== done: $OUT =="

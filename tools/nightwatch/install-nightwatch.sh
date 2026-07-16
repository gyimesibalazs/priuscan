#!/usr/bin/env bash
# (Re)install the nightwatch helper on the head unit: a root/shell watcher that applies the
# app-written `ui_night_mode` secure setting DEVICE-WIDE via `cmd uimode night yes/no`.
#
# Why it exists (K706, Android 10 / SDK 29):
#   - the app holds WRITE_SECURE_SETTINGS -> writing ui_night_mode PERSISTS but does not APPLY;
#   - UiModeManager.setNightMode is a silent no-op: mNightModeLocked=true and
#     MODIFY_DAY_NIGHT_MODE is NOT pm-grantable ("not a changeable permission type");
#   - `cmd uimode night ...` from shell/root DOES apply -> this watcher bridges the two.
#
# Install is via `adb remount` (overlayfs scratch on the super partition; plain rw remount
# fails). The overlay CAN be lost (factory reset / ROM update / scratch drop) -> the watcher
# then silently disappears and "auto dark stops working": re-run this script.
#
# Usage: tools/nightwatch/install-nightwatch.sh <head-unit-ip[:port]>   (default port 9876)
set -euo pipefail
cd "$(dirname "$0")"
D="${1:?usage: install-nightwatch.sh <ip[:port]>}"; [[ "$D" == *:* ]] || D="$D:9876"
adb connect "$D" >/dev/null
echo ">> target: $D"

echo ">> current state:"
adb -s "$D" shell 'ls -l /system/bin/priuscan-nightwatch.sh /system/etc/init/priuscan-nightwatch.rc 2>&1; pgrep -f priuscan-nightwatch >/dev/null && echo "watcher: RUNNING" || echo "watcher: NOT running"'

echo ">> adb remount (overlayfs) ..."
adb -s "$D" remount >/dev/null 2>&1 || { adb -s "$D" root >/dev/null 2>&1; sleep 1; adb connect "$D" >/dev/null; adb -s "$D" remount; }

echo ">> install script + init rc ..."
adb -s "$D" push nightwatch.sh /system/bin/priuscan-nightwatch.sh >/dev/null
adb -s "$D" shell chmod 755 /system/bin/priuscan-nightwatch.sh
adb -s "$D" push priuscan-nightwatch.rc /system/etc/init/priuscan-nightwatch.rc >/dev/null
adb -s "$D" shell chmod 644 /system/etc/init/priuscan-nightwatch.rc
adb -s "$D" shell 'restorecon /system/bin/priuscan-nightwatch.sh /system/etc/init/priuscan-nightwatch.rc 2>/dev/null' || true

echo ">> start NOW (detached; init picks it up from the .rc on the next boot) ..."
adb -s "$D" shell 'pgrep -f priuscan-nightwatch >/dev/null || setsid /system/bin/sh /system/bin/priuscan-nightwatch.sh </dev/null >/dev/null 2>&1 &' || true
sleep 2

echo ">> verify: flip the setting like the app does ..."
adb -s "$D" shell settings put secure ui_night_mode 2; sleep 3
N=$(adb -s "$D" shell dumpsys uimode 2>/dev/null | grep -oE 'mNightMode=[0-9]' | head -1)
adb -s "$D" shell settings put secure ui_night_mode 1; sleep 3
DAY=$(adb -s "$D" shell dumpsys uimode 2>/dev/null | grep -oE 'mNightMode=[0-9]' | head -1)
echo ">> after put 2: $N (expect mNightMode=2); after put 1: $DAY (expect mNightMode=1)"
[ "$N" = "mNightMode=2" ] && [ "$DAY" = "mNightMode=1" ] \
  && echo ">> NIGHTWATCH OK — the app's auto dark now flips the whole system" \
  || echo "!! verify failed — check: adb -s $D shell pgrep -f priuscan-nightwatch"

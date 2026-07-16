#!/usr/bin/env bash
# (Re)install + start the nightwatch helper on the head unit: a root/shell watcher that applies
# the app-written `ui_night_mode` secure setting DEVICE-WIDE via `cmd uimode night yes/no`.
#
# Why it exists (K706, Android 10 / SDK 29):
#   - the app holds WRITE_SECURE_SETTINGS -> writing ui_night_mode PERSISTS but does not APPLY;
#   - UiModeManager.setNightMode is a silent no-op: mNightModeLocked=true and
#     MODIFY_DAY_NIGHT_MODE is NOT pm-grantable ("not a changeable permission type");
#   - `cmd uimode night ...` from shell/root DOES apply -> this watcher bridges the two.
#
# Install is via `adb remount` (overlayfs scratch on the super partition; a plain rw remount
# fails). The overlay can be lost (factory reset / ROM update) -> re-run this script.
#
# Persistence across a HEAD-UNIT REBOOT comes from the install-recovery.sh hook below, NOT
# from the .rc: init parses all rc files BEFORE mount_all mounts the adb-remount overlay, so
# an overlay-added .rc is never seen (see the long comment at the hook).
# "Start now" (this script, without a reboot) uses a detached `( setsid ... & )` -- a plain
# backgrounded adb-shell job would be SIGHUP'd when the shell exits; the new session survives.
#
# Usage: tools/nightwatch/install-nightwatch.sh <head-unit-ip[:port]>   (default port 9876)
set -euo pipefail
cd "$(dirname "$0")"
D="${1:?usage: install-nightwatch.sh <ip[:port]>}"; [[ "$D" == *:* ]] || D="$D:9876"
# the watcher's ARGS end in this exact string -> matches ONLY the watcher, never our own
# ps/grep/dumpsys command lines (the earlier pgrep -f self-matched and matched zombies)
WPAT='sh /system/bin/priuscan-nightwatch.sh$'
running() { adb -s "$D" shell "ps -A -o PID,ARGS 2>/dev/null | grep -E '$WPAT' | grep -v grep" 2>/dev/null; }

adb connect "$D" >/dev/null
echo ">> target: $D"

echo ">> before:"
adb -s "$D" shell 'ls -l /system/bin/priuscan-nightwatch.sh /system/etc/init/priuscan-nightwatch.rc 2>&1' | sed 's/^/   /'
if running >/dev/null; then echo "   watcher: RUNNING"; else echo "   watcher: not running"; fi

echo ">> adb remount (overlayfs) ..."
adb -s "$D" remount >/dev/null 2>&1 || { adb -s "$D" root >/dev/null 2>&1 || true; sleep 1; adb connect "$D" >/dev/null; adb -s "$D" remount; }

echo ">> install script + init rc ..."
adb -s "$D" push nightwatch.sh /system/bin/priuscan-nightwatch.sh >/dev/null
adb -s "$D" shell chmod 755 /system/bin/priuscan-nightwatch.sh
adb -s "$D" push priuscan-nightwatch.rc /system/etc/init/priuscan-nightwatch.rc >/dev/null
adb -s "$D" shell chmod 644 /system/etc/init/priuscan-nightwatch.rc
adb -s "$D" shell 'restorecon /system/bin/priuscan-nightwatch.sh /system/etc/init/priuscan-nightwatch.rc 2>/dev/null' || true

# BOOT PERSISTENCE — the .rc above is NOT enough on this ROM: init parses every *.rc
# (incl. /system/etc/init) BEFORE `mount_all` brings up the adb-remount overlay, so an
# overlay-ADDED rc file is invisible to init forever (proof: base-ROM services from that
# same dir run, ours never registers -> `getprop init.svc.priuscan_nw` stays empty).
# Hook a BASE-ROM script that init execs LATE instead: flash_recovery (defined in /init.rc,
# runs as root, class main) execs /system/bin/install-recovery.sh after mount_all, so the
# overlay content IS visible by then. Idempotent; keeps a .priuscan-bak of the original.
echo ">> boot hook into install-recovery.sh (init can't see overlay-added .rc) ..."
adb -s "$D" shell 'grep -q priuscan-nightwatch /system/bin/install-recovery.sh 2>/dev/null && echo "   hook: already present" || {
  cp /system/bin/install-recovery.sh /system/bin/install-recovery.sh.priuscan-bak 2>/dev/null
  printf "\n# priuscan nightwatch boot hook (see tools/nightwatch/install-nightwatch.sh)\n( setsid /system/bin/sh /system/bin/priuscan-nightwatch.sh </dev/null >/dev/null 2>&1 & )\n" >> /system/bin/install-recovery.sh
  echo "   hook: added"
}'

echo ">> (re)start now: kill any old/zombie instance, launch detached ..."
# get PIDs from the ANCHORED ps list (excludes this kill command's own line -- a
# `pgrep -f priuscan-nightwatch.sh` would match, and kill, its own shell -> exit 137)
adb -s "$D" shell "ps -A -o PID,ARGS 2>/dev/null | grep -E '$WPAT' | grep -v grep | awk '{print \$1}' | xargs -r kill -9 2>/dev/null; true"
sleep 1
# ( setsid ... & ) in a subshell: new session (survives the adb shell exiting) + fully detached I/O
adb -s "$D" shell '( setsid /system/bin/sh /system/bin/priuscan-nightwatch.sh </dev/null >/dev/null 2>&1 & )'
sleep 2

echo ">> verify process is ALIVE (real cmdline, not a self-match):"
PROC="$(running || true)"
if [ -n "$PROC" ]; then echo "   $PROC"; else echo "   !! watcher did NOT stay alive"; fi

echo ">> verify it APPLIES both ways (flip the setting like the app does) ..."
adb -s "$D" shell settings put secure ui_night_mode 2; sleep 4
N=$(adb -s "$D" shell dumpsys uimode 2>/dev/null | grep -oE 'mNightMode=[0-9]' | head -1)
adb -s "$D" shell settings put secure ui_night_mode 1; sleep 4
DAY=$(adb -s "$D" shell dumpsys uimode 2>/dev/null | grep -oE 'mNightMode=[0-9]' | head -1)
echo "   set=2 -> $N (want mNightMode=2) ;  set=1 -> $DAY (want mNightMode=1)"

if [ -n "$PROC" ] && [ "$N" = "mNightMode=2" ] && [ "$DAY" = "mNightMode=1" ]; then
  echo ">> NIGHTWATCH OK -- the app's auto dark now flips the whole system."
  echo ">> REBOOT CHECK (boot persistence): restart the head unit, then run:"
  echo "     adb -s $D shell \"ps -A -o ARGS | grep -E 'priuscan-nightwatch.sh\$'\""
  echo "   (a running process there = the install-recovery.sh boot hook works.)"
else
  echo "!! FAILED. Debug: adb -s $D shell 'cmd uimode night yes' should flip the UI; if it does,"
  echo "   the watcher isn't staying alive -- check SELinux (getenforce) / the .sh path."
fi

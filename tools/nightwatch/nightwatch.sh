#!/system/bin/sh
# Applies the app-written secure setting ui_night_mode device-wide via cmd uimode
# (the app has WRITE_SECURE_SETTINGS but can't call the locked setNightMode itself:
# this K706 head unit has mNightModeLocked=true and MODIFY_DAY_NIGHT_MODE is not
# pm-grantable — "not a changeable permission type"). Runs as root/shell from init
# (service priuscan_nw) or a detached setsid shell; ~zero cost (2 s poll, no fork
# unless the value changed).
last=""
while true; do
  want=$(settings get secure ui_night_mode 2>/dev/null)
  if [ "$want" != "$last" ]; then
    if [ "$want" = "2" ]; then cmd uimode night yes >/dev/null 2>&1; fi
    if [ "$want" = "1" ]; then cmd uimode night no  >/dev/null 2>&1; fi
    last="$want"
  fi
  sleep 2
done

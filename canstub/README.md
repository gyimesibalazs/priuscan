# canstub: replacement for the head unit's factory CAN service

The K706 head unit's reverse-camera app (`com.qf.backcar`) draws steering-dependent trajectory
lines, but only from data served by the ROM's CAN service (`com.qf.vehicle`, `QF_Canbus.apk`),
which reads a CAN-decoder box on a UART. This car has no such box, so the lines never move.

`canstub` is an app with the **same package name** that implements the binder interface the ROM
apps use (`ICanBusServiceFeature`, reverse-engineered) and feeds the steering angle it receives
from the PriusCAN app (`com.qf.vehicle.action.swa` broadcast, firmware >= 3.53).

Everything else the factory app did (CAN-box A/C, door, radar pages, original-car settings) is
gone while the stub is installed. None of it worked without a CAN box.

## Install (once; needs root adb, the head unit reboots)

```
D=<ip>:9876
adb -s $D shell "mkdir -p /data/local/qf_backup && cp /system/priv-app/QF_Canbus/QF_Canbus.apk /data/local/qf_backup/"
adb -s $D shell "mount -o rw,remount /system && mv /system/priv-app/QF_Canbus/QF_Canbus.apk /system/priv-app/QF_Canbus/QF_Canbus.apk.off"
adb -s $D reboot                      # package manager drops the factory package
./gradlew :canstub:assembleDebug      # with the Linux SDK in local.properties
adb -s $D install -r canstub/build/outputs/apk/debug/canstub-debug.apk
adb -s $D shell dumpsys deviceidle whitelist +com.qf.vehicle     # the ROM kills non-whitelisted apps
adb -s $D shell 'kill $(pidof com.qf.backcar)'                   # persistent: restarts and binds
```

Verify: `dumpsys activity services com.qf.vehicle | grep binding=` lists `com.qf.framework` and
`com.qf.backcar`. Updating the stub later is a plain `adb install -r`.

## Restore the factory app

```
adb -s $D uninstall com.qf.vehicle
adb -s $D shell "mount -o rw,remount /system && mv /system/priv-app/QF_Canbus/QF_Canbus.apk.off /system/priv-app/QF_Canbus/QF_Canbus.apk"
adb -s $D reboot
```

## Protocol notes

* Clients bind with `setPackage("com.qf.vehicle")` + action `com.qf.vehicle.service.ACTION_CAN_SERVICE`;
  no signature check. `com.qf.framework` only keeps the service alive; `com.qf.backcar` and the
  launchers register callbacks.
* Steering reaches the camera view only through `addSWCAngleCallBack` (transaction 7) and
  `ICanBusServiceCallback.onGetPackedData` (transaction 2) with the frame
  `98 06 06 | angle lo, hi(bit7 = negative) | rate lo, hi | index lo, hi | FF`. The index
  (0 = full left, 45 = straight, 90 = full right) selects one of 91 built-in track images.
* Dead ends, verified in the factory dex: the `com.qf.vehicle.action.swa` broadcast receiver in
  the camera app is never registered, and the service's `forwardPackedData` is a no-op.
* The camera app also loads custom track images from `/system/config/wheelangle/`.

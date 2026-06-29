#!/usr/bin/env bash
# Build the latest debug APK (with the Linux SDK) and install it on the head unit.
#
#   tools/install-app.sh [--no-build] [IP]
#
#   --no-build : skip the build, install the existing APK
#   IP         : head-unit IP (otherwise the hotspot range is scanned)
#
# Note: local.properties holds the user's WINDOWS SDK path (they build from the
# Windows side). Before building from WSL we temporarily switch it to the Linux SDK
# and restore it afterwards -- otherwise gradle FAILS SILENTLY and the stale APK
# gets installed. Override the Linux SDK path with ANDROID_SDK_LINUX.
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
# shellcheck source=tools/priuscan-device.sh
source "$ROOT/tools/priuscan-device.sh"

PKG=hu.codingo.priuscan
APK=app/build/outputs/apk/debug/app-debug.apk
LINUX_SDK="${ANDROID_SDK_LINUX:-$HOME/Android/Sdk}"
CANSVC=app/src/main/java/hu/codingo/priuscan/CanService.kt

NOBUILD=0; IPARG=""
for a in "$@"; do
  case "$a" in
    --no-build) NOBUILD=1 ;;
    *)          IPARG="$a" ;;
  esac
done

if [ "$NOBUILD" -eq 0 ]; then
  echo ">> Building (Linux SDK: $LINUX_SDK) ..."
  [ -d "$LINUX_SDK" ] || { echo "!! Linux SDK not found: $LINUX_SDK (set ANDROID_SDK_LINUX)"; exit 1; }
  cp local.properties /tmp/priuscan-lp.bak 2>/dev/null || true
  echo "sdk.dir=$LINUX_SDK" > local.properties
  if ./gradlew :app:assembleDebug; then BUILT=1; else BUILT=0; fi
  [ -f /tmp/priuscan-lp.bak ] && cp /tmp/priuscan-lp.bak local.properties || true
  [ "$BUILT" -eq 1 ] || { echo "!! Build failed"; exit 1; }
fi
[ -f "$APK" ] || { echo "!! No APK: $APK (run without --no-build)"; exit 1; }
echo ">> APK: $(ls -la --time-style=+'%Y-%m-%d %H:%M:%S' "$APK" | awk '{print $6, $7}')"

echo ">> Finding head unit ..."
DEV="$(find_device "$IPARG")" || { echo "!! Head unit not reachable. Pass the IP: tools/install-app.sh <ip>"; exit 1; }
echo ">> Installing on $DEV"
adb -s "$DEV" install -r "$APK"

# re-grant the special perm for device-wide auto dark mode (UiModeManager.setNightMode +
# Settings.Secure ui_night_mode). A reinstall can revoke it -> the system dark mode then
# silently stops following (the in-app theme still works without it).
adb -s "$DEV" shell pm grant hu.codingo.priuscan android.permission.WRITE_SECURE_SETTINGS 2>/dev/null \
  && echo ">> WRITE_SECURE_SETTINGS granted (auto dark mode)" \
  || echo "!! could not grant WRITE_SECURE_SETTINGS (auto system dark mode may not work)"

FW="$(grep -oE 'BUNDLED_FW = [0-9]+' "$CANSVC" | grep -oE '[0-9]+' || true)"
echo ">> Done. Bundled FW: ${FW:-?}  (OTA it on the head unit if the ESP is older)"

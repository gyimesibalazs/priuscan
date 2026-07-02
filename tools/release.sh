#!/usr/bin/env bash
# Publish a GitHub Release the in-app updater (AppUpdater.kt) consumes:
#   - the debug APK (user-confirmed self-install),
#   - update.json {versionCode, versionName, fw},
#   - every geofence <region>.bgf (so the app can sync data without a full APK update).
#
# Requires: `gh auth login` once. Usage: tools/release.sh [tag]
# Bump versionCode/versionName in app/build.gradle.kts BEFORE releasing, or the app won't see it.
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
VN=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | sed -E 's/.*"([^"]+)"/\1/')
FW=$(grep -oE 'BUNDLED_FW = [0-9]+' app/src/main/java/hu/codingo/priuscan/CanService.kt | grep -oE '[0-9]+')
TAG="${1:-app-v$VN}"
APK=app/build/outputs/apk/debug/app-debug.apk
GEO=app/src/main/assets/geofence

echo ">> App v$VN (versionCode $VC), FW $FW, tag $TAG"

# build with the Linux SDK (WSL: local.properties holds the Windows path). The restore runs
# via an EXIT trap: with `set -e` a failing gradlew would otherwise skip an inline restore,
# leaving local.properties on the Linux path — and the NEXT run's backup would then overwrite
# the user's Windows SDK path for good. Backup in-repo (gitignored), not /tmp (WSL clears it).
LP_BAK="local.properties.bak"
cp local.properties "$LP_BAK" 2>/dev/null || true
trap '[ -f "$LP_BAK" ] && mv "$LP_BAK" local.properties' EXIT
echo "sdk.dir=${ANDROID_SDK_LINUX:-$HOME/Android/Sdk}" > local.properties
./gradlew :app:assembleDebug
[ -f "$APK" ] || { echo "!! build produced no APK"; exit 1; }

# the asset MUST be literally "update.json" (AppUpdater looks for that name) -> stage in a temp dir
RELDIR=$(mktemp -d)
printf '{"versionCode":%s,"versionName":"%s","fw":%s}\n' "$VC" "$VN" "$FW" > "$RELDIR/update.json"

NREG=$(ls "$GEO"/*.bgf 2>/dev/null | wc -l)
gh release create "$TAG" \
  --title "PriusCAN $VN" \
  --notes "App v$VN (versionCode $VC) · firmware v$FW · $NREG geofence regions. Open Settings in the app to update." \
  "$APK" \
  "$RELDIR/update.json" \
  "$GEO"/*.bgf
echo ">> released $TAG ($NREG regions)"

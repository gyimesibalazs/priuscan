#!/usr/bin/env bash
# Pull log files (.jsonl/.gz) and CAN dumps (.txt) from the head unit into carlogs/.
# Downloads ONLY what is missing locally or LARGER on the head unit (e.g. the growing
# daily JSONL) -- already-complete files are skipped.
#
#   tools/pull-logs.sh [IP]
#
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
# shellcheck source=tools/priuscan-device.sh
source "$ROOT/tools/priuscan-device.sh"

PKG=hu.codingo.priuscan
LOGDIR="/storage/emulated/0/Android/data/$PKG/files/logs"
DEST="$ROOT/carlogs"
mkdir -p "$DEST"

echo ">> Finding head unit ..."
DEV="$(find_device "${1:-}")" || { echo "!! Head unit not reachable. Pass the IP: tools/pull-logs.sh <ip>"; exit 1; }
echo ">> Device: $DEV"

# remote listing: size + name (log/dump extensions only)
listing="$(adb -s "$DEV" exec-out run-as "$PKG" sh -c "ls -l '$LOGDIR/'" 2>/dev/null | tr -d '\r')" \
  || { echo "!! Cannot access the logs dir ($LOGDIR)"; exit 1; }

new=0; skip=0
while read -r size name; do
  [ -n "$name" ] || continue
  base="$(basename "$name")"
  dst="$DEST/$base"
  if [ -f "$dst" ]; then
    local_sz="$(wc -c < "$dst")"
    if [ "$local_sz" -ge "$size" ]; then
      echo "   = $base (have: ${local_sz}b)"; skip=$((skip+1)); continue
    fi
    echo "   ^ $base (grew: ${local_sz}b -> ${size}b)"
  else
    echo "   v $base (${size}b)"
  fi
  adb -s "$DEV" exec-out run-as "$PKG" cat "$LOGDIR/$base" > "$dst"
  new=$((new+1))
done < <(echo "$listing" | awk '/\.jsonl|\.gz|\.txt/ {print $5, $NF}')

echo ">> Done: $new downloaded/updated, $skip skipped -> $DEST"

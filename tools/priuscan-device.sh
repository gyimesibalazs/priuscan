#!/usr/bin/env bash
# Shared helper: locate and connect the head unit (network adb on :9876).
# The hotspot IP is DYNAMIC, so we scan the range. Override with:
#   - an argument:        find_device 172.20.10.4
#   - the PRIUSCAN_IP env: export PRIUSCAN_IP=172.20.10.4
#
# Robustness: EVERY adb call is bounded with `timeout` because a wedged adb server
# hangs indefinitely (even `adb kill-server` hangs) -- a hard `pkill -9 adb` reset is
# used to recover. Each candidate is first probed with a fast TCP check (bash /dev/tcp)
# so dead IPs cost ~0.3s instead of adb's slow timeout.
# On success it prints "ip:port" to stdout and status to stderr.
PORT="${PRIUSCAN_PORT:-9876}"
PROBE_TIMEOUT="${PRIUSCAN_PROBE_TIMEOUT:-0.6}"   # generous: WSL->hotspot round-trip can be slow

_adb()   { timeout 6 adb "$@"; }                                  # bound every adb call
_probe() { timeout "$PROBE_TIMEOUT" bash -c "exec 3<>/dev/tcp/$1/$PORT" 2>/dev/null; }

# Ensure a responsive adb server. A wedged server hangs even on kill-server, so if a
# bounded start-server times out we hard-kill the daemon and restart it.
_ensure_adb() {
  if timeout 4 adb start-server >/dev/null 2>&1; then return 0; fi
  echo "   .. adb wedged -> hard reset (pkill)" >&2
  pkill -9 adb >/dev/null 2>&1; sleep 0.3
  timeout 8 adb start-server >/dev/null 2>&1 || true
}

# Try to bring up ip:PORT and verify it is a real device. Prints "ip:port" + returns 0 on success.
# An "offline" entry gets one disconnect+reconnect (the network adb is flaky).
_connect() {
  local ip="$1" st
  for _try in 1 2; do
    _adb connect "$ip:$PORT" >/dev/null 2>&1
    st="$(_adb -s "$ip:$PORT" get-state 2>/dev/null | tr -d '\r\n')"
    [ "$st" = "device" ] && { echo "$ip:$PORT"; return 0; }
    _adb disconnect "$ip:$PORT" >/dev/null 2>&1
  done
  return 1
}

find_device() {
  _ensure_adb
  local cand ip dev
  if   [ -n "${1:-}" ];           then cand="${1%:*}"
  elif [ -n "${PRIUSCAN_IP:-}" ]; then cand="${PRIUSCAN_IP%:*}"
  else
    # already-connected head unit? (e.g. connected manually) -> use it, no scan needed
    dev="$(_adb devices 2>/dev/null | awk -v p=":$PORT" '$1 ~ p && $2=="device"{print $1; exit}')"
    [ -n "$dev" ] && { echo "$dev"; return 0; }
    # the hotspot IP is dynamic: scan the whole /28 client range (.2 .. .14)
    cand=""; for n in $(seq 2 14); do cand="$cand 172.20.10.$n"; done
  fi
  # fast /dev/tcp probe -> adb connect only the open ones (an explicit IP is always tried)
  local explicit=0; [ -n "${1:-}${PRIUSCAN_IP:-}" ] && explicit=1
  for ip in $cand; do
    if _probe "$ip" || [ "$explicit" = 1 ]; then   # for an explicit IP, connect even if the probe fails
      echo "   .. $ip:$PORT -> adb connect" >&2
      dev="$(_connect "$ip")" && { echo "$dev"; return 0; }
    else
      echo "   .. $ip:$PORT closed" >&2
    fi
  done
  return 1
}

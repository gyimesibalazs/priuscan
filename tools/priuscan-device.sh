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
PROBE_TIMEOUT="${PRIUSCAN_PROBE_TIMEOUT:-0.3}"

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

find_device() {
  _ensure_adb
  local cand ip st
  if   [ -n "${1:-}" ];           then cand="${1%:*}"
  elif [ -n "${PRIUSCAN_IP:-}" ]; then cand="${PRIUSCAN_IP%:*}"
  else cand="172.20.10.2 172.20.10.3 172.20.10.4 172.20.10.5 172.20.10.6 172.20.10.7"
  fi
  for ip in $cand; do
    if ! _probe "$ip"; then echo "   .. $ip:$PORT closed" >&2; continue; fi
    echo "   .. $ip:$PORT open -> adb connect" >&2
    _adb connect "$ip:$PORT" >/dev/null 2>&1
    st="$(_adb -s "$ip:$PORT" get-state 2>/dev/null | tr -d '\r\n')"
    if [ "$st" = "device" ]; then echo "$ip:$PORT"; return 0; fi
    # "offline" -> one disconnect+reconnect, then re-check
    _adb disconnect "$ip:$PORT" >/dev/null 2>&1
    _adb connect "$ip:$PORT" >/dev/null 2>&1
    st="$(_adb -s "$ip:$PORT" get-state 2>/dev/null | tr -d '\r\n')"
    if [ "$st" = "device" ]; then echo "$ip:$PORT"; return 0; fi
    echo "   .. $ip:$PORT not a device (state: ${st:-none})" >&2
    _adb disconnect "$ip:$PORT" >/dev/null 2>&1
  done
  return 1
}

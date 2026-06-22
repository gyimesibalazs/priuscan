#!/usr/bin/env bash
# Shared helper: locate and connect the head unit (network adb on :9876).
# The hotspot IP is DYNAMIC, so we scan the range. Override with:
#   - an argument:        find_device 172.20.10.4
#   - the PRIUSCAN_IP env: export PRIUSCAN_IP=172.20.10.4
#
# Speed: each candidate is first probed with a FAST TCP check (bash /dev/tcp, 0.2s)
# and `adb connect` only runs on an open port — so we don't wait on adb's slow
# timeout for dead IPs. (No telnet/nc needed, just bash's built-in /dev/tcp + timeout.)
# On success it prints "ip:port" to stdout and status to stderr.
PORT="${PRIUSCAN_PORT:-9876}"
PROBE_TIMEOUT="${PRIUSCAN_PROBE_TIMEOUT:-0.2}"   # plenty on a LAN

_probe() { timeout "$PROBE_TIMEOUT" bash -c "exec 3<>/dev/tcp/$1/$PORT" 2>/dev/null; }

find_device() {
  adb start-server >/dev/null 2>&1
  local cand ip
  if   [ -n "${1:-}" ];           then cand="${1%:*}"
  elif [ -n "${PRIUSCAN_IP:-}" ]; then cand="${PRIUSCAN_IP%:*}"
  else cand="172.20.10.2 172.20.10.3 172.20.10.4 172.20.10.5 172.20.10.6 172.20.10.7"
  fi
  for ip in $cand; do
    if ! _probe "$ip"; then echo "   .. $ip:$PORT closed" >&2; continue; fi
    echo "   .. $ip:$PORT open -> adb connect" >&2
    adb connect "$ip:$PORT" >/dev/null 2>&1
    if adb -s "$ip:$PORT" get-state 2>/dev/null | grep -q '^device$'; then
      echo "$ip:$PORT"; return 0
    fi
    # "offline" -> a kill+reconnect usually fixes it
    adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
    adb connect "$ip:$PORT" >/dev/null 2>&1
    if adb -s "$ip:$PORT" get-state 2>/dev/null | grep -q '^device$'; then
      echo "$ip:$PORT"; return 0
    fi
    adb disconnect "$ip:$PORT" >/dev/null 2>&1
  done
  return 1
}

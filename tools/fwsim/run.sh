#!/usr/bin/env bash
# Build the fwsim harness (real firmware code) and replay the car logs through it.
#   tools/fwsim/run.sh                                  # all carlogs since the start
#   tools/fwsim/run.sh --since 2026-06-20               # only from a date
#   tools/fwsim/run.sh --fuel-corr 1.0 --since 2026-06-20
#   tools/fwsim/run.sh --json 'carlogs/priuscan-2026062*.jsonl*'
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
g++ -O2 -std=gnu++17 -I. tools/fwsim/fwsim.cpp -o tools/fwsim/fwsim
exec python3 tools/fwsim/replay.py --bin tools/fwsim/fwsim "$@"

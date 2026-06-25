#!/usr/bin/env bash
# Pipelined per-(sub)region geofence build -> one <unit>.bgf each, into ./build/.
# Countries are auto-split into Geofabrik first-level sub-regions (DE->16 states, CZ->14, NL->12).
#   ./run-batch.sh                         # default country list
#   ./run-batch.sh "HU,AT,DE"              # custom (comma list)
set -euo pipefail
cd "$(dirname "$0")"; mkdir -p build
REGIONS="${1:-HU,AT,SK,SI,HR,RS,BA,ME,CZ,BE,NL,RO,UA,DE}"
docker build -q -t geofence-build . >/dev/null
docker run --rm -v "$(pwd)/build:/build" geofence-build batch --region "$REGIONS" --out /build
echo ">> .bgf files:"; ls -la build/*.bgf

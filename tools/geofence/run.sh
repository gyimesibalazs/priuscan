#!/usr/bin/env bash
# Build the image + produce the geofence into ./build/belterulet.bgf
#   ./run.sh                       # Hungary (default)
#   ./run.sh "HU,AT,SK,DE"         # multiple countries -> one combined .bgf (Geofabrik)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
docker build -t geofence-build .
docker run --rm -v "$(pwd)/build:/build" geofence-build \
  all --region "${1:-HU}" --source geofabrik --out /build --test-points test/hu-points.csv
echo ">> output: $(pwd)/build/belterulet.bgf"; ls -la build/belterulet.bgf

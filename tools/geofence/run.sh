#!/usr/bin/env bash
# Build the image + produce the Hungary belterület geofence into ./build/belterulet.bgf
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
docker build -t geofence-build .
docker run --rm -v "$(pwd)/build:/build" geofence-build \
  all --region "${1:-HU}" --out /build --test-points test/hu-points.csv --verbose
echo ">> output: $(pwd)/build/belterulet.bgf"
ls -la build/belterulet.bgf

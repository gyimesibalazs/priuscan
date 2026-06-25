# geofence-build — belterület (built-up area) geofence

Builds a compact **H3 res-9 cell set** of Hungary's built-up areas (OSM
`landuse=residential`, merged into contiguous settlement footprints) so the
Android app can answer *"am I inside a built-up area / belterület"* in O(1) at
runtime — e.g. to warn when entering/leaving a town (speed-limit context).

Output: **`belterulet.bgf`** (~52 KB for Hungary), committed to
`app/src/main/assets/belterulet.bgf`.

## Run (Docker)

```bash
./run.sh            # builds the image + produces ./build/belterulet.bgf for HU
./run.sh AT         # another region by ISO 3166-1 code
```

or directly:

```bash
docker build -t geofence-build .
docker run --rm -v "$PWD/build:/build" geofence-build \
  all --region HU --out /build --test-points test/hu-points.csv
```

## Pipeline (`geofence_build.py`)

`all` runs: **fetch → extract → cellify → pack → verify** (each is also a
standalone subcommand on `./build`).

1. **fetch** — Overpass query for `landuse=residential` ways + relations in the
   region → `raw.json` (~27 MB for HU).
2. **extract** — build shapely polygons (multipolygon **relations are stitched
   with `polygonize`** — their outer rings are split across many member ways),
   **dissolve + buffer ~55 m** so parcels merge across streets into the real
   settlement footprint → `belterulet.geojson`.
3. **cellify** — `polygon_to_cells_experimental(..., "overlap")` at res 9 (so
   parcels smaller than a ~175 m cell are still covered), then `compact_cells`
   down to res `--min-res` (5) → sorted `uint64` set.
4. **pack** — delta + varint + zstd → `belterulet.bgf`.
5. **verify** — round-trip + `test/hu-points.csv` (belt/külterület points) + size.

Key options: `--region`, `--res 9`, `--min-res 5`, `--buffer-deg 0.0006`,
`--landuse-tags "landuse=residential"` (add `|commercial|retail` to widen),
`--no-compact`. See `geofence_build.py --help`.

## `.bgf` format

```
MAGIC "BGF1" (4) | version u8 | res_max u8 | res_min u8 | flags u8 |
cell_count u32 LE | bbox 4×f32 LE (minLat,minLng,maxLat,maxLng) |
zstd( per-cell delta of sorted H3 uint64, varint LEB128 )
```

## Android lookup (consumer side)

Load once from `assets/`: zstd-decode → delta-decode → `LongOpenHashSet` (fastutil)
or sorted `long[]`. Then per GPS fix:

```kotlin
fun isInside(lat: Double, lng: Double): Boolean {
    if (lat < minLat || lat > maxLat || lng < minLng || lng > maxLng) return false  // bbox pre-cut
    var c = H3.latLngToCell(lat, lng, RES_MAX)            // 9
    for (r in RES_MAX downTo RES_MIN) {                   // 9..5 (compacted set is mixed-res)
        if (cellSet.contains(c)) return true
        if (r > RES_MIN) c = H3.cellToParent(c, r - 1)
    }
    return false
}
```

Fire the belterület warning when `isInside` **flips** vs the previous fix.
Deps: `com.uber:h3`, `com.github.luben:zstd-jni`, optional `fastutil`.

## Hungary result (current `.bgf`)

| | |
|---|---|
| cells (res 7-9, compacted) | 80 186 |
| file size | 53 679 B (~52 KB) |
| bbox | lat 45.76–48.58, lng 16.16–22.87 |
| verify test points | 8/8 |

Reproducibility: `build/cells.meta.json` records the region, params and build
timestamp. Re-run `./run.sh` any time to rebuild from fresh OSM data.

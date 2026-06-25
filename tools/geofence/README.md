# geofence-build — belterület (built-up area) geofence

Builds compact **H3 res-9 cell sets** of built-up areas (OSM `landuse=residential`,
merged into contiguous settlement footprints) so the Android app can answer *"am I
inside a built-up area / belterület"* for a GPS fix in O(log n) — e.g. to show a
city/road icon, tell the firmware to split city km, and warn about headlights on the
open road.

Output: one **`<region>.bgf`** per region, bundled under
`app/src/main/assets/geofence/`. The app loads them all and picks by bounding box.

## Run (Docker)

```bash
./run-batch.sh                       # default country list (HU + neighbours + DE/CZ/NL/BE/BA/ME)
./run-batch.sh "HU,AT,DE"            # custom — comma list of ISO codes
```

Countries are **auto-split into their first-level Geofabrik sub-regions** (DE → 16
states, CZ → 14, NL → 12; everything else stays whole) so no single download/processing
step is huge. The build is **pipelined**: while one region's `.pbf` is being processed
the next one is already downloading; each `.pbf` is deleted right after use (peak disk ≈
one `.pbf`). Output lands in `./build/<region>.bgf`.

## Pipeline (`geofence_build.py`)

Source = **Geofabrik** per-region `.osm.pbf` processed with **osmium-tool** (reliable for
big countries; `osmium export` assembles multipolygon relations natively). Per region:

1. **download** the `.pbf` from Geofabrik (URL resolved from `index-v1.json`).
2. **osmium** `tags-filter` `landuse=residential` → `export -f geojsonseq` (assembled polygons).
3. **cellify** — per polygon: small buffer (~55 m, merges parcels across streets) +
   `polygon_to_cells_experimental(..., "overlap")` at res 9 (so parcels smaller than a
   ~175 m cell are still covered) → res-9 set.
4. **compact** down to res `--min-res` (5) → sorted `uint64`.
5. **pack** — delta + varint + zstd → `<region>.bgf`.

Commands: `batch` (per-region `.bgf`, pipelined — the normal one), `all` (one combined
`.bgf` from several regions), `units` (print the sub-region expansion), `verify`.
Key options: `--region`, `--res 9`, `--min-res 5`, `--buffer-deg 0.0006`,
`--landuse-tags "landuse=residential"` (add `|commercial|retail` to widen). Overpass is
still available (`--source overpass`) for one-off small regions.

## `.bgf` format

```
MAGIC "BGF1" (4) | version u8 | resMax u8 | resMin u8 | flags u8 |
cellCount u32 LE | bbox 4×f32 LE (minLat,minLng,maxLat,maxLng) |
zstd( per-cell delta of sorted H3 uint64, varint LEB128 )
```

## Android side (`BelteruletGeofence.kt`)

Loads every `assets/geofence/*.bgf` once (zstd-jni decode → delta-decode → sorted
`long[]` + bbox). Per GPS fix: bbox pre-cut, then `H3.latLngToCell(lat,lng,resMax)` and a
`resMax→resMin` parent-walk binary search. H3 + zstd are native: the `.so` for H3 live in
`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}` (extracted from the `com.uber:h3` jar);
zstd-jni is pulled as `@aar`. On a city↔road flip the app sends `G1`/`G0` to the ESP.

## Coverage (current)

Per-(sub)region `.bgf` for: **HU, AT, SK, SI, HR, RS, BA, ME, CZ (14), NL (12), BE, RO,
UA, DE (16 states)**. Typical sizes: a small country ~5–80 KB, a German state ~30–150 KB;
the full bundle is ~2–3 MB. Re-run `./run-batch.sh` any time to rebuild from fresh OSM
data; `build/batch-report.json` records the per-region cell counts.

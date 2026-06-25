#!/usr/bin/env python3
"""
geofence-build — OSM belterület (landuse=residential) -> H3 res9 compacted cell set -> .bgf

Pipeline: fetch (Overpass) -> extract (shapely union) -> cellify (H3 res9 + compact)
-> pack (delta+varint+zstd) -> verify. The .bgf is loaded by the Android app for a fast
"am I inside a built-up area" lookup (see README / app side).

.bgf format:
  MAGIC "BGF1" | version u8 | res_max u8 | res_min u8 | flags u8 |
  cell_count u32 LE | bbox 4x f32 LE (minLat,minLng,maxLat,maxLng) | zstd(delta+varint)
"""
import argparse, json, os, struct, sys, time
import requests
import h3
import zstandard as zstd
from shapely.geometry import Polygon, MultiPolygon, mapping, LineString
from shapely.ops import unary_union, polygonize
from shapely.validation import make_valid

OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
MAGIC = b"BGF1"

def log(*a): print("[geofence]", *a, file=sys.stderr, flush=True)

# ----------------------------------------------------------------- fetch
def overpass(q, otimeout):
    last = None
    for url in OVERPASS_URLS:
        try:
            log("Overpass POST", url)
            r = requests.post(url, data={"data": q}, timeout=otimeout + 120,
                              headers={"User-Agent": "priuscan-geofence/1.0"})
            if r.status_code == 200:
                return r.json()
            log("  HTTP", r.status_code, r.text[:120]); last = r.status_code
        except Exception as e:
            log("  err", e); last = e
        time.sleep(4)
    raise RuntimeError(f"Overpass failed: {last}")

def fetch(region, tags_ways, out, otimeout=1800):
    sel = "".join(
        f'way["{k}"~"{v}"](area.a);relation["{k}"~"{v}"](area.a);' if "~" in f"{k}={v}" or "|" in v
        else f'way["{k}"="{v}"](area.a);relation["{k}"="{v}"](area.a);'
        for k, v in tags_ways)
    q = (f'[out:json][timeout:{otimeout}];\n'
         f'area["ISO3166-1"="{region}"]->.a;\n({sel});\nout geom;')
    log("query:\n" + q)
    data = overpass(q, otimeout)
    with open(out, "w") as f:
        json.dump(data, f)
    log("fetched", len(data.get("elements", [])), "elements ->", out)
    return data

# ----------------------------------------------------------------- extract
def _ring(geom):
    if not geom or len(geom) < 4:
        return None
    pts = [(p["lon"], p["lat"]) for p in geom]
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    try:
        p = Polygon(pts)
        if not p.is_valid:
            p = make_valid(p)
        return p if (not p.is_empty and p.area > 0) else None
    except Exception:
        return None

def extract(data, min_area_deg2=0.0, buffer_deg=0.0, out_geojson=None):
    polys = []
    for el in data.get("elements", []):
        t = el.get("type")
        if t == "way":
            p = _ring(el.get("geometry"))
            if p: polys.append(p)
        elif t == "relation":
            # multipolygon: the outer ring is split into MANY member way segments -> stitch them
            # with polygonize (noded). (Inner/hole rings get filled too -> fine for "belterület".)
            lines = [LineString([(p["lon"], p["lat"]) for p in m["geometry"]])
                     for m in el.get("members", [])
                     if m.get("geometry") and len(m["geometry"]) >= 2]
            if lines:
                for poly in polygonize(unary_union(lines)):
                    if poly.area > 0:
                        polys.append(poly if poly.is_valid else make_valid(poly))
    log("residential polygons:", len(polys))
    if not polys:
        raise RuntimeError("no residential polygons found")
    u = unary_union(polys)                       # dissolve overlapping/touching foltok
    if buffer_deg > 0:                            # grow + MERGE parcels across narrow streets ->
        u = unary_union(u.buffer(buffer_deg))    # contiguous settlement footprint (the actual belterület)
    if u.geom_type == "Polygon":
        u = MultiPolygon([u])
    if min_area_deg2 > 0:                          # drop tiny stray foltok
        u = MultiPolygon([g for g in u.geoms if g.area >= min_area_deg2]) or u
    log("dissolved ->", len(u.geoms), "parts, bbox", [round(v, 4) for v in u.bounds])
    if out_geojson:
        with open(out_geojson, "w") as f:
            json.dump(mapping(u), f)
    return u

# ----------------------------------------------------------------- cellify
def _poly_cells(poly, res):
    ext = [(y, x) for (x, y) in poly.exterior.coords]          # shapely (lng,lat) -> h3 (lat,lng)
    holes = [[(y, x) for (x, y) in r.coords] for r in poly.interiors]
    # 'overlap' containment: a cell counts if it OVERLAPS the polygon (not just centre-inside),
    # so residential parcels SMALLER than a res9 cell (~175 m) are still covered.
    return h3.polygon_to_cells_experimental(h3.LatLngPoly(ext, *holes), res, "overlap")

def cellify(mpoly, res, compact, min_res):
    geoms = list(mpoly.geoms) if mpoly.geom_type == "MultiPolygon" else [mpoly]
    cells = set()
    for i, poly in enumerate(geoms):
        for sub in (poly.geoms if poly.geom_type == "MultiPolygon" else [poly]):
            try:
                cells.update(_poly_cells(sub, res))
            except Exception as e:
                log("  poly skip:", e)
        if (i + 1) % 1000 == 0:
            log("  cellify", i + 1, "/", len(geoms), "->", len(cells), "cells")
    log("res", res, "cells (raw):", len(cells))
    cl = list(cells)
    if compact:
        cl = h3.compact_cells(cl)
        log("compacted:", len(cl))
        expanded = []
        for c in cl:
            if h3.get_resolution(c) < min_res:     # enforce min_res so the app's parent-walk finds it
                expanded.extend(h3.cell_to_children(c, min_res))
            else:
                expanded.append(c)
        cl = expanded
        log("after min_res", min_res, ":", len(cl),
            "resolutions", sorted(set(h3.get_resolution(c) for c in cl)))
    return sorted(h3.str_to_int(c) for c in cl)

# ----------------------------------------------------------------- pack
def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7f; n >>= 7
        out.append(b | 0x80 if n else b)
        if not n: return out

def _uvarint(buf, i):
    n = s = 0
    while True:
        b = buf[i]; i += 1
        n |= (b & 0x7f) << s
        if not (b & 0x80): return n, i
        s += 7

def pack(ints, res_max, res_min, bbox, out):
    payload = bytearray(); prev = 0
    for v in ints:
        payload += _varint(v - prev); prev = v
    blob = zstd.ZstdCompressor(level=19, write_content_size=True).compress(bytes(payload))
    hdr = bytearray(MAGIC) + bytes([1, res_max, res_min, 0])   # [0:8] magic+ver+res_max+res_min+flags
    hdr += struct.pack("<I", len(ints))                        # [8:12]  cell_count
    hdr += struct.pack("<ffff", *bbox)                         # [12:28] minLat,minLng,maxLat,maxLng
    with open(out, "wb") as f:
        f.write(hdr); f.write(blob)
    total = len(hdr) + len(blob)
    log("packed", len(ints), "cells ->", out, total, "bytes (zstd blob", len(blob), ")")
    return total

def unpack(path):
    raw = open(path, "rb").read()
    assert raw[:4] == MAGIC, "bad magic"
    res_max, res_min = raw[5], raw[6]
    n = struct.unpack("<I", raw[8:12])[0]
    bbox = struct.unpack("<ffff", raw[12:28])
    payload = zstd.ZstdDecompressor().decompress(raw[28:])
    ints = []; i = 0; prev = 0
    for _ in range(n):
        d, i = _uvarint(payload, i); prev += d; ints.append(prev)
    return ints, res_max, res_min, bbox

# ----------------------------------------------------------------- verify
def lookup(cellset, lat, lng, res_max, res_min):
    c = h3.latlng_to_cell(lat, lng, res_max)
    for r in range(res_max, res_min - 1, -1):
        if h3.str_to_int(c) in cellset:
            return True
        if r > res_min:
            c = h3.cell_to_parent(c, r - 1)
    return False

def verify(bgf, cell_count, test_csv=None):
    ints, res_max, res_min, bbox = unpack(bgf)
    cellset = set(ints)
    rt = (len(ints) == cell_count)
    log("round-trip:", "OK" if rt else f"MISMATCH {len(ints)} vs {cell_count}")
    rep = {"cell_count": len(ints), "res_max": res_max, "res_min": res_min,
           "bbox": list(bbox), "file_bytes": os.path.getsize(bgf), "roundtrip": rt}
    if test_csv and os.path.exists(test_csv):
        passed = total = 0
        for line in open(test_csv):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split(",")
            if len(p) < 3:
                continue
            lat, lng = float(p[0]), float(p[1])
            exp = p[2].strip().lower() in ("1", "true", "in", "belt", "yes")
            got = lookup(cellset, lat, lng, res_max, res_min)
            total += 1; passed += (got == exp)
            if got != exp:
                log(f"  FAIL {lat},{lng} exp={exp} got={got}  {p[3] if len(p) > 3 else ''}")
        rep["test_passed"], rep["test_total"] = passed, total
        log("test points:", passed, "/", total)
    return rep

# ----------------------------------------------------------------- main
def parse_tags(s):
    out = []
    for part in s.split(","):
        part = part.strip()
        if "=" in part:
            k, v = part.split("=", 1)
            out.append((k.strip(), v.strip()))
    return out

def main():
    ap = argparse.ArgumentParser(prog="geofence-build")
    ap.add_argument("cmd", choices=["fetch", "extract", "cellify", "pack", "verify", "all"])
    ap.add_argument("--region", default="HU")
    ap.add_argument("--res", type=int, default=9)
    ap.add_argument("--min-res", type=int, default=5)
    ap.add_argument("--out", default="./build")
    ap.add_argument("--landuse-tags", default="landuse=residential")
    ap.add_argument("--min-area", type=float, default=0.0, help="drop foltok smaller than this (deg^2)")
    ap.add_argument("--buffer-deg", type=float, default=0.0006,
                    help="grow+merge built-up parcels by this (deg, ~0.0006=~55 m) -> contiguous belterület")
    ap.add_argument("--no-compact", action="store_true")
    ap.add_argument("--overpass-timeout", type=int, default=1800)
    ap.add_argument("--test-points", default=None)
    ap.add_argument("--name", default="belterulet")
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    raw = os.path.join(a.out, "raw.json")
    fgb = os.path.join(a.out, f"{a.name}.geojson")
    meta_p = os.path.join(a.out, "cells.meta.json")
    cells_p = os.path.join(a.out, "cells.u64")
    bgf = os.path.join(a.out, f"{a.name}.bgf")
    tags = parse_tags(a.landuse_tags)
    compact = not a.no_compact

    def do_fetch():
        return fetch(a.region, tags, raw, a.overpass_timeout)
    def do_extract(data=None):
        if data is None: data = json.load(open(raw))
        return extract(data, a.min_area, a.buffer_deg, fgb)
    def do_cellify(u=None):
        if u is None:
            from shapely.geometry import shape
            u = shape(json.load(open(fgb)))
        ints = cellify(u, a.res, compact, a.min_res)
        bbox = u.bounds  # (minLng,minLat,maxLng,maxLat)
        meta = {"region": a.region, "res": a.res, "min_res": a.min_res,
                "compact": compact, "cell_count": len(ints), "tags": a.landuse_tags,
                "bbox_latlng": [bbox[1], bbox[0], bbox[3], bbox[2]],
                "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        with open(cells_p, "wb") as f:
            import array; array.array("Q", ints).tofile(f)
        json.dump(meta, open(meta_p, "w"), indent=2)
        log("meta ->", meta_p)
        return ints, meta
    def do_pack(ints=None, meta=None):
        if ints is None:
            import array
            arr = array.array("Q"); arr.frombytes(open(cells_p, "rb").read()); ints = list(arr)
        if meta is None: meta = json.load(open(meta_p))
        b = meta["bbox_latlng"]
        pack(ints, a.res, a.min_res, (b[0], b[1], b[2], b[3]), bgf)
        return bgf
    def do_verify(meta=None):
        if meta is None: meta = json.load(open(meta_p))
        rep = verify(bgf, meta["cell_count"], a.test_points)
        json.dump(rep, open(os.path.join(a.out, "verify-report.json"), "w"), indent=2)
        return rep

    if a.cmd == "fetch":   do_fetch()
    elif a.cmd == "extract": do_extract()
    elif a.cmd == "cellify": do_cellify()
    elif a.cmd == "pack":    do_pack()
    elif a.cmd == "verify":  do_verify()
    elif a.cmd == "all":
        data = do_fetch()
        u = do_extract(data)
        ints, meta = do_cellify(u)
        do_pack(ints, meta)
        rep = do_verify(meta)
        log("DONE:", bgf, "|", rep)

if __name__ == "__main__":
    main()

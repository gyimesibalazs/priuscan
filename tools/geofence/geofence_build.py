#!/usr/bin/env python3
"""
geofence-build — OSM built-up areas (landuse=residential) -> H3 res9 compacted cell set -> .bgf

Source = Geofabrik per-country .osm.pbf (default; reliable + reproducible, handles big
countries) processed with osmium-tool, OR Overpass (--source overpass, small regions only).
Multi-region: --region "HU,AT,DE,..." merges all into ONE .bgf.

Pipeline: fetch (download pbf + osmium tags-filter residential + osmium export geojsonseq)
-> cellify (per-polygon buffer + 'overlap' containment at res9; scales, no giant union)
-> compact (down to res min) -> pack (delta+varint+zstd) -> verify.

.bgf: MAGIC "BGF1" | ver u8 | res_max u8 | res_min u8 | flags u8 | cell_count u32 LE |
      bbox 4xf32 LE (minLat,minLng,maxLat,maxLng) | zstd(per-cell delta, varint LEB128)
"""
import argparse, json, os, struct, subprocess, sys, time
import h3
import zstandard as zstd
from shapely.geometry import shape
from shapely.validation import make_valid

MAGIC = b"BGF1"
ISO2GF = {  # ISO 3166-1 -> Geofabrik region id (the script auto-expands to sub-regions via the index)
    "HU": "hungary",   "AT": "austria",  "SK": "slovakia", "UA": "ukraine", "RO": "romania",
    "RS": "serbia",    "HR": "croatia",  "SI": "slovenia", "DE": "germany", "CZ": "czech-republic",
    "NL": "netherlands", "BE": "belgium", "BA": "bosnia-herzegovina", "ME": "montenegro",
    "PL": "poland", "IT": "italy", "FR": "france", "CH": "switzerland", "PT": "portugal", "ES": "spain",
}
_IDX = None

def log(*a): print("[geofence]", *a, file=sys.stderr, flush=True)
def sh(*cmd): log("$", " ".join(cmd)); subprocess.run(cmd, check=True)

def load_index(out_dir):
    """Geofabrik index-v1.json: id -> properties, and parent -> [child ids]."""
    global _IDX
    if _IDX is None:
        import requests
        p = os.path.join(out_dir, "geofabrik-index.json")
        if not os.path.exists(p):
            log("download geofabrik index-v1.json")
            open(p, "w").write(requests.get("https://download.geofabrik.de/index-v1.json", timeout=120).text)
        feats = [f["properties"] for f in json.load(open(p))["features"]]
        ch = {}
        for x in feats:
            if x.get("parent"): ch.setdefault(x["parent"], []).append(x["id"])
        _IDX = ({x["id"]: x for x in feats}, ch)
    return _IDX

def expand_units(regions, out_dir):
    """Country/ISO -> its FIRST-LEVEL Geofabrik sub-regions if they exist (DE->16 states, CZ->14,
    NL->12), else the country itself. So the caller just asks for countries; the script splits."""
    byid, ch = load_index(out_dir)
    units = []
    for r in regions:
        cid = ISO2GF.get(r.upper(), r.lower())
        kids = sorted(ch.get(cid, []))
        units += kids if kids else [cid]
    return units

def pbf_url(unit, out_dir):
    byid, _ = load_index(out_dir)
    return byid[ISO2GF.get(unit.upper(), unit.lower())]["urls"]["pbf"]

# ------------------------------------------------------------ fetch (Geofabrik + osmium)
def osmium_tags(tags):
    # tags = [("landuse","residential|commercial"), ...] -> ["wr/landuse=residential,commercial", ...]
    return [f"wr/{k}={v.replace('|', ',')}" for k, v in tags]

def download_pbf(unit, out_dir):
    pbf = os.path.join(out_dir, f"{unit}.osm.pbf")
    if not os.path.exists(pbf):
        sh("curl", "-sL", "--retry", "3", "--fail", "-o", pbf, pbf_url(unit, out_dir))
    return pbf

def pbf_to_geojsonseq(unit, pbf, tags, out_dir, suffix="res"):
    res = os.path.join(out_dir, f"{unit}.{suffix}.pbf")
    gjs = os.path.join(out_dir, f"{unit}.{suffix}.geojsonseq")
    log(unit, "pbf", os.path.getsize(pbf) // 1_000_000, f"MB; osmium filter+export ({suffix})")
    sh("osmium", "tags-filter", "--overwrite", "-o", res, pbf, *osmium_tags(tags))
    sh("osmium", "export", "--overwrite", "-f", "geojsonseq", "-o", gjs, res)
    os.remove(res)
    return gjs

def fetch_geofabrik(unit, tags, out_dir):     # single unit, no pipeline (used by `all`)
    return pbf_to_geojsonseq(unit, download_pbf(unit, out_dir), tags, out_dir)

# ------------------------------------------------------------ fetch (Overpass, small only)
def fetch_overpass(region, tags, out_dir, otimeout=1800):
    import requests
    sel = "".join(f'way["{k}"~"{v}"](area.a);relation["{k}"~"{v}"](area.a);' if "|" in v
                  else f'way["{k}"="{v}"](area.a);relation["{k}"="{v}"](area.a);' for k, v in tags)
    q = f'[out:json][timeout:{otimeout}];\narea["ISO3166-1"="{region}"]->.a;\n({sel});\nout geom;'
    r = requests.post("https://overpass-api.de/api/interpreter", data={"data": q},
                      timeout=otimeout + 120, headers={"User-Agent": "priuscan-geofence/1.0"})
    r.raise_for_status()
    raw = os.path.join(out_dir, f"{region}.overpass.json")
    json.dump(r.json(), open(raw, "w"))
    return raw

# ------------------------------------------------------------ polygons
def polys_from_geojsonseq(path):
    n = 0
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip().lstrip("\x1e")
        if not line:
            continue
        try:
            geom = json.loads(line).get("geometry")
            if not geom:
                continue
            g = shape(geom)
        except Exception:
            continue
        if g.is_empty or g.geom_type not in ("Polygon", "MultiPolygon") or g.area <= 0:
            continue
        if not g.is_valid:
            g = make_valid(g)
        n += 1
        yield g
    log("  polygons read:", n)

def lines_from_geojsonseq(path):
    """Yield LineString / MultiLineString geoms (roads). add_cells() buffers them into corridors."""
    n = 0
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip().lstrip("\x1e")
        if not line:
            continue
        try:
            geom = json.loads(line).get("geometry")
            if not geom:
                continue
            g = shape(geom)
        except Exception:
            continue
        if g.is_empty or g.geom_type not in ("LineString", "MultiLineString"):
            continue
        n += 1
        yield g
    log("  lines read:", n)

def polys_from_overpass(path):
    from shapely.geometry import Polygon, LineString
    from shapely.ops import unary_union, polygonize
    data = json.load(open(path))
    for el in data.get("elements", []):
        if el.get("type") == "way":
            geom = el.get("geometry") or []
            if len(geom) >= 4:
                pts = [(p["lon"], p["lat"]) for p in geom]
                if pts[0] != pts[-1]: pts.append(pts[0])
                try:
                    p = Polygon(pts)
                    if not p.is_valid: p = make_valid(p)
                    if p.area > 0: yield p
                except Exception: pass
        elif el.get("type") == "relation":
            lines = [LineString([(p["lon"], p["lat"]) for p in m["geometry"]])
                     for m in el.get("members", []) if m.get("geometry") and len(m["geometry"]) >= 2]
            if lines:
                for poly in polygonize(unary_union(lines)):
                    if poly.area > 0: yield poly

# ------------------------------------------------------------ cellify (per polygon, scalable)
def add_cells(poly_iter, res, buffer_deg, acc):
    """Add res-level H3 cells (overlap containment) for each polygon (buffered) into acc set."""
    cnt = 0
    for poly in poly_iter:
        if buffer_deg > 0:
            poly = poly.buffer(buffer_deg)
        for sub in (poly.geoms if poly.geom_type == "MultiPolygon" else [poly]):
            try:
                ext = [(y, x) for (x, y) in sub.exterior.coords]
                holes = [[(y, x) for (x, y) in r.coords] for r in sub.interiors]
                acc.update(h3.polygon_to_cells_experimental(h3.LatLngPoly(ext, *holes), res, "overlap"))
            except Exception:
                pass
        cnt += 1
        if cnt % 50000 == 0:
            log("    ", cnt, "polys ->", len(acc), "cells")
    return cnt

def compact_set(cell_strs, min_res):
    cl = h3.compact_cells(list(cell_strs))
    out = []
    for c in cl:
        if h3.get_resolution(c) < min_res:
            out.extend(h3.cell_to_children(c, min_res))
        else:
            out.append(c)
    return sorted(h3.str_to_int(c) for c in out)

# ------------------------------------------------------------ pack / unpack
def _varint(n):
    o = bytearray()
    while True:
        b = n & 0x7f; n >>= 7
        o.append(b | 0x80 if n else b)
        if not n: return o

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
    hdr = bytearray(MAGIC) + bytes([1, res_max, res_min, 0])
    hdr += struct.pack("<I", len(ints)) + struct.pack("<ffff", *bbox)
    open(out, "wb").write(bytes(hdr) + blob)
    log("packed", len(ints), "cells ->", out, len(hdr) + len(blob), "bytes (blob", len(blob), ")")

def unpack(path):
    raw = open(path, "rb").read()
    assert raw[:4] == MAGIC
    n = struct.unpack("<I", raw[8:12])[0]
    bbox = struct.unpack("<ffff", raw[12:28])
    payload = zstd.ZstdDecompressor().decompress(raw[28:])
    ints = []; i = 0; prev = 0
    for _ in range(n):
        d, i = _uvarint(payload, i); prev += d; ints.append(prev)
    return ints, raw[5], raw[6], bbox

# ------------------------------------------------------------ verify
def lookup(cellset, lat, lng, res_max, res_min):
    c = h3.latlng_to_cell(lat, lng, res_max)
    for r in range(res_max, res_min - 1, -1):
        if h3.str_to_int(c) in cellset:
            return True
        if r > res_min:
            c = h3.cell_to_parent(c, r - 1)
    return False

def verify(bgf, cell_count, test_csv):
    ints, res_max, res_min, bbox = unpack(bgf)
    cs = set(ints)
    rep = {"cell_count": len(ints), "res_max": res_max, "res_min": res_min,
           "bbox": list(bbox), "file_bytes": os.path.getsize(bgf), "roundtrip": len(ints) == cell_count}
    log("round-trip:", "OK" if rep["roundtrip"] else "MISMATCH")
    if test_csv and os.path.exists(test_csv):
        p = t = 0
        for line in open(test_csv):
            line = line.strip()
            if not line or line.startswith("#"): continue
            f = line.split(",")
            if len(f) < 3: continue
            exp = f[2].strip().lower() in ("1", "true", "in", "belt", "yes")
            got = lookup(cs, float(f[0]), float(f[1]), res_max, res_min)
            t += 1; p += (got == exp)
            if got != exp: log(f"  FAIL {f[0]},{f[1]} exp={exp} got={got} {f[3] if len(f)>3 else ''}")
        rep["test_passed"], rep["test_total"] = p, t
        log("test points:", p, "/", t)
    return rep

# ------------------------------------------------------------ main
def cells_bbox(ints):
    lls = [h3.cell_to_latlng(h3.int_to_str(i)) for i in ints]
    lats = [x for x, _ in lls]; lngs = [y for _, y in lls]
    return (min(lats), min(lngs), max(lats), max(lngs))

def main():
    ap = argparse.ArgumentParser(prog="geofence-build")
    ap.add_argument("cmd", choices=["all", "batch", "units", "verify"])
    ap.add_argument("--region", default="HU", help="ISO code(s) or comma list: HU,AT,DE,... (auto-split to sub-regions)")
    ap.add_argument("--source", choices=["geofabrik", "overpass"], default="geofabrik")
    ap.add_argument("--res", type=int, default=9)
    ap.add_argument("--min-res", type=int, default=5)
    ap.add_argument("--buffer-deg", type=float, default=0.0006)
    ap.add_argument("--landuse-tags", default="landuse=residential")
    # gyorsforgalmi úthálózat: carve the motorway/expressway corridor OUT of the built-up set so a
    # car on it doesn't read "belterület" (no city-km, no headlight warning). HU: motorway=autópálya,
    # trunk=autóút. Empty = no subtraction.
    ap.add_argument("--subtract-tags", default="")
    ap.add_argument("--subtract-buffer-deg", type=float, default=0.00025)  # ~28 m each side (road + GPS slop)
    ap.add_argument("--out", default="./build")
    ap.add_argument("--name", default="belterulet")
    ap.add_argument("--test-points", default=None)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    bgf = os.path.join(a.out, f"{a.name}.bgf")
    meta_p = os.path.join(a.out, f"{a.name}.meta.json")
    tags = [(k.strip(), v.strip()) for p in a.landuse_tags.split(",") if "=" in p
            for k, v in [p.split("=", 1)]]
    sub_tags = [(k.strip(), v.strip()) for p in a.subtract_tags.split(",") if "=" in p
                for k, v in [p.split("=", 1)]]
    regions = [r.strip().upper() for r in a.region.split(",") if r.strip()]

    if a.cmd == "verify":
        meta = json.load(open(meta_p))
        json.dump(verify(bgf, meta["cell_count"], a.test_points),
                  open(os.path.join(a.out, "verify-report.json"), "w"), indent=2)
        return

    if a.cmd == "units":                                  # just print the expanded unit list
        for u in expand_units(regions, a.out):
            print(u)
        return

    if a.cmd == "batch":
        # PIPELINED per-unit build: a downloader thread fetches the NEXT pbf while the main thread
        # processes the current one; each unit -> its own <unit>.bgf; pbf deleted right after use.
        import threading, queue
        units = expand_units(regions, a.out)
        log("units (", len(units), "):", " ".join(units))
        q = queue.Queue(maxsize=1)                        # hold 1 prefetched pbf
        def downloader():
            for u in units:
                try: q.put((u, download_pbf(u, a.out)))
                except Exception as e: log("!! download", u, "FAILED:", e); q.put((u, None))
            q.put(None)
        threading.Thread(target=downloader, daemon=True).start()
        results = []
        while True:
            item = q.get()
            if item is None: break
            u, pbf = item
            if pbf is None: results.append([u, "download-failed"]); continue
            try:
                gjs = pbf_to_geojsonseq(u, pbf, tags, a.out)
                acc = set(); add_cells(polys_from_geojsonseq(gjs), a.res, a.buffer_deg, acc)
                os.remove(gjs)
                ints = compact_set(acc, a.min_res)
                if not ints: results.append([u, "empty"]); continue
                obgf = os.path.join(a.out, f"{u}.bgf")
                pack(ints, a.res, a.min_res, cells_bbox(ints), obgf)
                results.append([u, len(ints), os.path.getsize(obgf)])
            except Exception as e:
                log("!! process", u, "FAILED:", e); results.append([u, "process-failed"])
            finally:
                if os.path.exists(pbf): os.remove(pbf)    # free disk so the next download can proceed
        log("===== BATCH DONE ====="); [log("  ", r) for r in results]
        json.dump({"results": results, "tags": a.landuse_tags, "res": a.res, "min_res": a.min_res,
                   "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())},
                  open(os.path.join(a.out, "batch-report.json"), "w"), indent=2)
        return

    # all: accumulate the (auto-expanded) units into ONE combined .bgf
    units = expand_units(regions, a.out) if a.source == "geofabrik" else [r.lower() for r in regions]
    acc = set(); roads = set(); t0 = time.time()
    for u in units:
        log("===== unit", u, "=====")
        try:
            if a.source == "geofabrik":
                pbf = download_pbf(u, a.out)
                belt_gjs = pbf_to_geojsonseq(u, pbf, tags, a.out, "res")     # built-up areas (polygons)
                add_cells(polys_from_geojsonseq(belt_gjs), a.res, a.buffer_deg, acc)
                os.remove(belt_gjs)
                if sub_tags:                                                  # roads to carve out (lines)
                    road_gjs = pbf_to_geojsonseq(u, pbf, sub_tags, a.out, "roads")
                    add_cells(lines_from_geojsonseq(road_gjs), a.res, a.subtract_buffer_deg, roads)
                    os.remove(road_gjs)
                os.remove(pbf)
            else:
                src = fetch_overpass(u, tags, a.out)
                add_cells(polys_from_overpass(src), a.res, a.buffer_deg, acc)
                p = os.path.join(a.out, u + ".overpass.json")
                if os.path.exists(p): os.remove(p)
        except Exception as e:
            log("!! unit", u, "FAILED:", e)
    if not acc: raise SystemExit("no cells produced")
    belt_n = len(acc)
    if roads:
        acc.difference_update(roads)
        log("subtracted gyorsforgalmi:", belt_n, "-", len(roads), "road cells ->", len(acc),
            "(", belt_n - len(acc), "removed)")
    ints = compact_set(acc, a.min_res)
    log("compacted ->", len(ints), "cells")
    bbox = cells_bbox(ints)
    pack(ints, a.res, a.min_res, bbox, bgf)
    json.dump({"regions": regions, "units": units, "cell_count": len(ints), "bbox_latlng": list(bbox),
               "tags": a.landuse_tags, "subtract_tags": a.subtract_tags, "res": a.res, "min_res": a.min_res,
               "belt_cells_res": belt_n, "road_cells_res": len(roads), "removed_res": belt_n - len(acc),
               "subtract_buffer_deg": a.subtract_buffer_deg, "seconds": round(time.time() - t0),
               "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}, open(meta_p, "w"), indent=2)
    rep = verify(bgf, len(ints), a.test_points)
    json.dump(rep, open(os.path.join(a.out, "verify-report.json"), "w"), indent=2)
    log("DONE:", bgf, rep)

if __name__ == "__main__":
    main()

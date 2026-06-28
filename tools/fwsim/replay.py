#!/usr/bin/env python3
"""
replay.py — feed PriusCAN JSON logs into the fwsim harness (the REAL firmware code) and print
the recomputed trip-computer data + detected refuels.

The firmware is SIMULATED, not reimplemented: fwsim.cpp #includes prius_parse.h and runs
compute_derived() per sample. This script only parses the logs (gz-aware), sorts by wall-clock
ts, and streams them to the binary.

Usage:
    tools/fwsim/run.sh                                  # build + replay carlogs/*.jsonl*
    tools/fwsim/run.sh --since 2026-06-20 --fuel-corr 1.0
    python3 replay.py --bin ./fwsim 'carlogs/priuscan-2026062*.jsonl*' --json

Output: per-refuel JSON lines (completed tank dist/fuel/l100), then a summary + the total &
current-tank slots. Distances are odo-anchored (gap-immune); fuel is integrated over logged
samples -> see "coverage" / "*_full_est" to correct for un-logged gaps.
"""
import argparse, glob, gzip, json, subprocess, sys, datetime

def lines(path):
    op = gzip.open if path.endswith(".gz") else open
    with op(path, "rt", errors="ignore") as f:
        for ln in f:
            try: yield json.loads(ln)
            except Exception: pass

def collect(globs, since_ms):
    rows = []
    files = sorted(set(p for g in globs for p in glob.glob(g)))
    for p in files:
        for d in lines(p):
            ts = d.get("ts")
            if not ts or (since_ms and ts < since_ms): continue
            rows.append((ts, d))
    rows.sort(key=lambda r: r[0])
    return files, rows

def refuel_candidates(rows, delta=25, gap_min=10, settle_s=90, bin_min=5, lookback_min=60, dedup_min=75):
    """Analysis (NOT the firmware): real refuels happen during key-off, and the head unit often
    reconnects with the car already MOVING -> the firmware's parked-plateau auto-detect can't see
    them. Pipeline: CLEAN fuelIn (drop the <3% shutdown glitch + the first `settle_s` after each
    >gap_min gap = boot bounce) -> bin into `bin_min`-minute medians (kills the ~minute-scale slosh
    on a full tank) -> flag a refuel where a bin's level rises >= delta above the MIN level of the
    recent `lookback_min`. A sustained level shift, so full-tank slosh (oscillates, returns) and
    in-drive wobble can't trip it; catches fills even mid-segment (logging continued across boots)."""
    import statistics
    gap_ms = gap_min * 60_000
    clean = []; prev_ts = None; seg_start = None
    for ts, d in rows:
        if prev_ts is None or ts - prev_ts >= gap_ms: seg_start = ts
        prev_ts = ts
        f = d.get("fuelIn")
        if f is None or f < 3 or ts - seg_start < settle_s * 1000: continue
        clean.append((ts, f))
    if not clean: return []
    bm = bin_min * 60_000
    bins = {}
    for ts, f in clean: bins.setdefault(ts // bm, []).append(f)
    keys = sorted(bins); lvl = {k: statistics.median(bins[k]) for k in keys}
    nlb = max(1, lookback_min // bin_min)
    cand = []; last = -10**18
    for j, k in enumerate(keys):
        prev_keys = [keys[p] for p in range(max(0, j - nlb), j)]
        if len(prev_keys) < 2: continue
        base = statistics.median([lvl[p] for p in prev_keys])   # median (a lone glitch bin can't drop it)
        ts = k * bm
        if lvl[k] - base >= delta and ts - last > dedup_min * 60_000:
            cand.append((ts, base, lvl[k])); last = ts
    return cand

# keys the firmware never reads as VKey inputs (containers / app-side / output-only) -> skip
SKIP = {"slots", "rhN", "ohN", "fCorr", "door", "cruise", "belt", "ts", "lat", "lng",
        "epoch", "fuelTs", "offTs", "offN", "fw", "city"}

def to_line(ts, d, fc_override):
    city = d.get("city"); city = city if isinstance(city, int) else -1
    fc = fc_override if fc_override is not None else (d.get("fCorr") or 1.0)
    parts = [f"{int(ts)}", f"{city}", f"{fc}"]
    for k, v in d.items():
        if k in SKIP or v is None or isinstance(v, (list, dict, bool)): continue
        if isinstance(v, (int, float)): parts.append(f"{k}={v}")
    return " ".join(parts)

def run_sim(binpath, rows, fc_override):
    """Stream rows through the fwsim harness; return (refuels[], summary, slots[])."""
    proc = subprocess.Popen([binpath], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    for ts, d in rows:
        proc.stdin.write(to_line(ts, d, fc_override) + "\n")
    proc.stdin.close()
    out = proc.stdout.read(); proc.wait()
    refuels, summary, slots = [], None, []
    for ln in out.splitlines():
        try: o = json.loads(ln)
        except Exception: continue
        if o.get("event") == "refuel": refuels.append(o)
        elif o.get("summary"): summary = o
        elif "slot" in o: slots.append(o)
    return out, refuels, summary, slots

def fmt_summary(s):
    return (f"  distance (odo-anchored, complete): {s['odo_dist']:.1f} km\n"
            f"  logged distance (spd-integral):    {s['logged_dist']:.1f} km  -> coverage {s['coverage']*100:.1f}%\n"
            f"  fuel (logged):            {s['fuel_logged']:.2f} L  ->  {s['l100_logged']:.2f} l/100km\n"
            f"  fuel (gap-corrected est): {s['fuel_full_est']:.2f} L  ->  {s['l100_full_est']:.2f} l/100km")

def fmt_slot(sl):
    return (f"{sl['dist']:.1f} km | {sl['fuel']:.2f} L | {sl['l100']:.2f} l/100km | "
            f"EV {sl['ev']:.1f} km ({sl['ev_pct']:.0f}%) | city {sl['city_dist']:.1f} km "
            f"({sl['city_pct']:.0f}%) EV {sl['city_ev']:.1f} | {sl['kmh']:.0f} km/h avg")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("globs", nargs="*", default=["carlogs/priuscan-*.jsonl*"])
    ap.add_argument("--bin", default="tools/fwsim/fwsim")
    ap.add_argument("--since", default=None, help="YYYY-MM-DD: ignore samples before this date")
    ap.add_argument("--fuel-corr", type=float, default=None, help="override fuel_corr for every sample")
    ap.add_argument("--per-tank", action="store_true", help="also break totals down per tank (split at refuel candidates)")
    ap.add_argument("--json", action="store_true", help="raw JSON lines from the harness (full run)")
    a = ap.parse_args()

    since_ms = None
    if a.since:
        since_ms = int(datetime.datetime.strptime(a.since, "%Y-%m-%d").replace(
            tzinfo=datetime.timezone.utc).timestamp() * 1000)

    files, rows = collect(a.globs or ["carlogs/priuscan-*.jsonl*"], since_ms)
    print(f"[replay] {len(files)} files, {len(rows)} samples"
          + (f", fuel_corr={a.fuel_corr}" if a.fuel_corr is not None else ""), file=sys.stderr)

    out, refuels, summary, slots = run_sim(a.bin, rows, a.fuel_corr)
    if a.json:
        print(out, end=""); return

    def epoch(e): return datetime.datetime.fromtimestamp(e, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M") if e else "?"
    def epms(ms): return datetime.datetime.fromtimestamp(ms/1000, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M")

    print(f"\n=== FW-DETECTED REFUELS ({len(refuels)}) ===  (parked + plateau, exactly as the ESP would)")
    for r in refuels:
        print(f"  {epoch(r['epoch'])}  odo {r['odo']}  tank: {r['tank_dist']:.1f} km  "
              f"{r['tank_fuel']:.2f} L  {r['l100']:.2f} l/100km  (EV {r['tank_ev']:.1f} km)")

    cand = refuel_candidates(rows)
    print(f"\n=== REFUEL CANDIDATES ({len(cand)}) ===  (analysis, not the FW: fuelIn level jump across a"
          f" key-off; the FW misses these when the car was already moving on reconnect -> use manual 'K')")
    for ts, b, n in cand:
        print(f"  {epms(ts)}  fuelIn {b:.0f}% -> {n:.0f}%  (+{n-b:.0f})")

    if summary:
        print(f"\n=== TOTALS (over the whole window) ===")
        print(f"  samples {summary['samples']}")
        print(fmt_summary(summary))
    for sl in slots:
        print(f"\n  [{sl['slot']}]  " + fmt_slot(sl))

    if a.per_tank and cand:
        # split rows at each refuel candidate -> one window per tank
        bounds = [c[0] for c in cand] + [rows[-1][0] + 1]
        print(f"\n=== PER-TANK (split at refuel candidates) ===")
        for i in range(len(bounds) - 1):
            seg = [(ts, d) for ts, d in rows if bounds[i] <= ts < bounds[i + 1]]
            if len(seg) < 50: continue
            _, _, s2, sl2 = run_sim(a.bin, seg, a.fuel_corr)
            tot = next((x for x in sl2 if x["slot"] == "total"), None)
            print(f"\n  TANK {i+1}: {epms(bounds[i])} -> {epms(bounds[i+1]-1)}")
            if s2: print(fmt_summary(s2))
            if tot: print(f"  trip: " + fmt_slot(tot))

if __name__ == "__main__":
    main()

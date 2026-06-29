#!/usr/bin/env python3
"""
seed.py — derive firmware persistence SEED values (trip slots + refuel history) from the logs,
using the fwsim harness (real firmware code). Output: a human summary + ready-to-paste C++
TripSlot literals for prius_persist.h.

Mapping (what the user asked for):
  slot[1] tank     = the CURRENT (in-progress) tank  -> last detected refuel .. now
  slot[0] lifetime = everything since the start of the logs (sum of all tanks)
  slot[2] oil      = same as lifetime (since log start)
  rhist[]          = each COMPLETED tank (between consecutive refuels), newest pushed last

Integrated quantities (fuel/ev/city/move/regen/brake) are gap-corrected by 1/coverage (the missing
logged distance was driven, just not logged); distance is odo-anchored (already complete).
"""
import sys, os, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "geofence"))
import replay as R

BIN = "tools/fwsim/fwsim"
GLOBS = ["carlogs/priuscan-*.jsonl*"]
GEOFENCE = "app/src/main/assets/geofence/hungary.bgf"

def run_window(rows, gf):
    _, _, summary, slots = R.run_sim(BIN, rows, None, gf)
    tot = next((x for x in slots if x["slot"] == "total"), None)
    cov = summary["coverage"] if summary else 1.0
    return tot, max(cov, 0.5)

def correct(tot, cov):
    k = 1.0 / cov
    return dict(epoch=int(tot["epoch"]), odo=int(tot["odo"]), dist=tot["dist"],
                ev=tot["ev"] * k, fuel=tot["fuel"] * k, move_s=tot["move_s"] * k,
                regen_e=tot["regen_e"] * k, brake_e=tot["brake_e"] * k,
                city_dist=tot["city_dist"] * k, city_ev=tot["city_ev"] * k, cov=cov)

def lit(s, epoch=None):
    e = s["epoch"] if epoch is None else epoch
    return ("{ %du, %du, %.2ff, %.2ff, %.3ff, %.0ff, %.0ff, %.0ff, %.2ff, %.2ff }"
            % (e, s["odo"], s["dist"], s["ev"], s["fuel"], s["move_s"],
               s["regen_e"], s["brake_e"], s["city_dist"], s["city_ev"]))

def ep(e): return datetime.datetime.fromtimestamp(e, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M") if e else "0"
def epms(ms): return ep(ms / 1000)

def main():
    gf = R.Geofence(GEOFENCE)
    files, rows = R.collect(GLOBS, None)
    cand = R.refuel_candidates(rows)
    print(f"[seed] {len(files)} files, {len(rows)} samples, {len(cand)} refuel candidate(s)", file=sys.stderr)

    bounds = [c[0] for c in cand]
    # completed tanks = windows BETWEEN consecutive refuels; current tank = after the last refuel
    completed = []
    for i in range(len(bounds) - 1):
        seg = [(t, d) for t, d in rows if bounds[i] <= t < bounds[i + 1]]
        tot, cov = run_window(seg, gf)
        if tot: completed.append((bounds[i], correct(tot, cov)))
    cur_rows = [(t, d) for t, d in rows if t >= bounds[-1]] if bounds else rows
    cur_tot, cur_cov = run_window(cur_rows, gf)
    cur = correct(cur_tot, cur_cov)
    full_tot, full_cov = run_window(rows, gf)
    life = correct(full_tot, full_cov)

    print("\n================ SEED SUMMARY ================")
    print(f"\nslot[1] TANK (current, since {ep(cur['epoch'])}):")
    print(f"  {cur['dist']:.1f} km | {cur['fuel']:.2f} L | {cur['fuel']/cur['dist']*100:.2f} l/100km "
          f"| EV {cur['ev']:.1f} km | city {cur['city_dist']:.1f} km (EV {cur['city_ev']:.1f}) "
          f"| move {cur['move_s']/3600:.1f} h | cov {cur['cov']*100:.0f}%")
    print(f"\nslot[0]/slot[2] LIFETIME / OIL (since log start {ep(life['epoch'])}):")
    print(f"  {life['dist']:.1f} km | {life['fuel']:.2f} L | {life['fuel']/life['dist']*100:.2f} l/100km "
          f"| EV {life['ev']:.1f} km | city {life['city_dist']:.1f} km (EV {life['city_ev']:.1f}) "
          f"| move {life['move_s']/3600:.1f} h | cov {life['cov']*100:.0f}%")
    print(f"\nrefuel history ({len(completed)} completed tank(s)):")
    for start, s in completed:
        print(f"  tank from {epms(start)}: {s['dist']:.1f} km | {s['fuel']:.2f} L "
              f"| {s['fuel']/s['dist']*100:.2f} l/100km | EV {s['ev']:.1f} | city {s['city_dist']:.1f} | cov {s['cov']*100:.0f}%")

    print("\n================ C++ literals (prius_persist.h) ================")
    print(f"// lifetime  slot[0] (epoch 0 = never resets)\n{lit(life, epoch=0)}")
    print(f"// tank      slot[1]\n{lit(cur)}")
    print(f"// oil       slot[2] (epoch 0)\n{lit(life, epoch=0)}")
    print(f"// rhist (oldest first):")
    for start, s in completed:
        print(f"{lit(s)},")

if __name__ == "__main__":
    main()

# fwsim — firmware trip-computer replay

Recompute **every trip-computer value** from the JSON car logs by replaying them through the
**actual firmware code** — `fwsim.cpp` `#include`s `prius_parse.h` and calls `compute_derived()`
per sample, so there is **no reimplementation drift**. Use it to cross-check the device, validate a
fix before flashing, or recover trip/fuel/refuel data the device lost to a reboot.

## Run
```bash
tools/fwsim/run.sh                                   # build + replay carlogs/*.jsonl*
tools/fwsim/run.sh --since 2026-06-20 --per-tank     # one date onward, per-tank breakdown
tools/fwsim/run.sh --fuel-corr 1.0 --per-tank        # force a fuel_corr (calibration sweep)
tools/fwsim/run.sh --json                            # raw JSON lines from the harness
```
`run.sh` compiles `fwsim` (g++) then calls `replay.py`. Needs `python3` only (no extra deps).

## What it reports
- **FW-detected refuels** — exactly what the ESP's parked-plateau auto-detect would fire. (Often 0
  for a tank filled across an un-logged key-off: the parked window isn't in the logs / the car was
  already moving when the head unit reconnected — which is what the manual `K` command is for.)
- **Refuel candidates** — analysis (not the FW): a sustained fuelIn level jump across a key-off,
  robust to the 0%/100% shutdown+boot gauge glitches and to full-tank slosh.
- **Totals / per-tank** — distance (odo-anchored → gap-immune), fuel, EV km/%, city km/%, avg
  l/100km, avg km/h. Fuel is integrated over logged samples; **coverage** + **gap-corrected est**
  account for un-logged key-off gaps.

## How it stays faithful
- `replay.py` parses the logs (gz-aware), sorts by wall-clock `ts`, and streams each sample to the
  C++ harness as `<ts_ms> <city_state> <fuel_corr> key=val …` (keys = firmware `KEYS[]`/VKey names).
- The harness sets `V[]`, sets `city_state`/`fuel_corr`, models a **reboot on a >5 s gap**
  (RAM-clear: `derive_init`, trap, refuel-detect state — slots & `fuel_ref` persist as NVS would),
  then calls `compute_derived()`. State starts **fresh** so the output is the trip data computed
  purely from the given logs.

## Validated (2026-06-28)
Replaying the 06-20→06-27 tank: gap-corrected fuel **45.89 L @ fuel_corr=1.0** vs the **46.26 L**
pump fill (752 km, 98% coverage) ⇒ true `fuel_corr = 1.008` (the value already set). Model faithful:
median(logged `fuel` / harness rate) = 0.999.

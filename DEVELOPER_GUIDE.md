# PriusCAN — Developer Guide

A complete CAN/OBD telemetry and diagnostics system for the **Toyota Prius Gen3
(XW30 / ZVW30, 2009–2015)**, built from an ESP32 firmware (ESPHome) plus an
Android Kotlin/Compose app that runs on the car's head unit.

This document is the single source of truth for continuing development. It
describes the architecture, the hardware, every source file, the data formats,
the calibration status, and the open tasks.

---

## 1. System overview

```
   ┌─────────────┐    CAN 500k     ┌──────────────┐   USB serial    ┌────────────────┐
   │  Prius OBD  │◄───────────────►│ ESP32-C3 +   │◄───────────────►│  Head unit     │
   │  port (CAN) │   diag + bcast  │ SN65HVD230   │  JSON @115200   │  (Android app) │
   └─────────────┘                 │ (ESPHome fw) │                 └───────┬────────┘
                                    └──────────────┘                         │ MQTT (opportunistic)
                                                                             ▼
                                                                      ┌──────────────┐
                                                                      │ Home Assistant│
                                                                      │ + InfluxDB    │
                                                                      └──────────────┘
```

* **ESP32-C3 Super Mini** reads the car's CAN bus through an **SN65HVD230**
  transceiver. Firmware = ESPHome (`prius_can_full_v2.yaml`) plus a pure-C++
  parser header (`prius_parse.h`).
* Firmware polls four diagnostic ECUs over ISO-TP and listens to a couple of
  passive broadcast frames, decodes everything into a value store, and emits a
  single-line JSON object on the native USB serial port (~4 Hz).
* The **Android app** (head unit) reads that JSON over USB, displays it, raises
  audible/visual warnings, shows a draggable always-on status overlay and a
  top-down door-open overlay (pre-rendered PNGs), and opportunistically pushes
  data to Home Assistant via MQTT with an offline JSONL buffer.

### Why this design
* No Wi-Fi/OTA in production: the device is powered from and talks over the head
  unit's USB. This keeps the 3.3 V rail clean (no RF current spikes on the
  single-core C3) and means zero standby drain (no +12V from OBD pin 16).
* All PID formulas are taken from the canonical **PriusChat "GenIII Prius
  4-24-12" Torque CSV** (author: usbseawolf2000) — every scale/offset is
  verified against that source, not guessed.

---

## 2. Hardware & wiring

| OBD-II pin | Signal     | Connects to                         |
|-----------:|------------|-------------------------------------|
| 6          | CAN-H      | SN65HVD230 CANH                     |
| 14         | CAN-L      | SN65HVD230 CANL                     |
| 5          | Signal GND | Common ground (transceiver + ESP)   |
| 16         | +12V       | **NOT connected** (USB powers it)   |

SN65HVD230 ↔ ESP32-C3:

| Transceiver | ESP32-C3 |
|-------------|----------|
| D (TXD)     | GPIO6 (`tx_pin`) |
| R (RXD)     | GPIO5 (`rx_pin`) |
| VCC         | 3V3 (the '230 is a 3.3 V part — direct, no level shifter) |
| RS          | GND (high-speed mode) |

**Critical notes**
* The car's bus is already terminated at both ends (≈60 Ω across pins 6–14 with
  ignition off). **Remove the 120 Ω terminator (R2) from the blue SN65HVD230
  breakout** — a third terminator drops the bus to ~40 Ω.
* If CAN is silent after flashing, swap `tx_pin`/`rx_pin` in the YAML — some
  breakouts label TX/RX from the transceiver's perspective rather than the MCU's.
* Native USB on the C3 enumerates as VID `0x303A` / PID `0x1003` (the C6 is
  `0x1001`). Both PIDs are registered in the app's USB device filter & prober.

---

## 3. Firmware (ESPHome)

Two files, which **must sit next to each other** in the ESPHome config dir
(e.g. `/home/gazdi/dev/`):

* `prius_can_full_v2.yaml` — the ESPHome config (board, sensors, CAN bus,
  polling intervals). It `includes: - prius_parse.h`.
* `prius_parse.h` — **pure C++**, zero ESPHome dependency. Decodes CAN frames
  into a `std::map<string,float>` value store (`prius::V`) and builds the JSON
  output. The YAML calls into it; the YAML (not the header) owns the `can0`
  object and the sensor `publish_state` calls.

### Separation of concerns (important!)
The header was deliberately rewritten to be ESPHome-agnostic because the
ESPHome `id()` macro behaves differently **outside** a YAML lambda (it returns a
value, not the Sensor object). So:

* The header never touches `id(...)` or `can0`.
* YAML template-sensor lambdas read `prius::V.get("key")` and `return {}` on NaN.
* The CAN `on_frame` lambda calls `prius::on_can_frame(resp_id, x, millis())`;
  if it returns a non-zero ID, the YAML sends the ISO-TP flow-control frame via
  `id(can0).send_data(fc-8, false, {0x30,0x00,0x05,...})`. Note `.send_data`
  (dot, not `->`) because `id(can0)` yields the object.
* An 80 ms `interval` lambda calls `prius::next_request(rid, req, millis())` and
  sends the request **only if it returns true** (it returns false while a
  multi-frame ISO-TP transfer is still in flight — see ISO-TP timing below).
* A 500 ms interval calls `prius::compute_derived()`, a 250 ms interval calls
  `prius::emit_json(extra)` (the 4 Hz JSON line).

### ISO-TP handling & full request↔response serialization
Multi-frame responses (e.g. `2101`, the 14 block voltages) arrive as
first-frame → flow-control → consecutive-frames. The polling loop must **not**
fire a new request while a transfer is in progress, otherwise the transfer is
aborted and the data never completes.

`next_request` enforces this in two stages:
1. **In-progress guard** — while an ISO-TP transfer is mid-flight it waits (up
   to a 60 ms timeout, tracked via `tp_started_ms`).
2. **Full serialization** (`awaiting` / `await_resp`) — after sending a request
   it refuses to send another until that request's response has *completed*
   (single frame, multi-frame, or even a negative `7F`), or 60 ms elapses.
   This closes the earlier race: between sending a request and its first frame
   arriving, `tp_active` was still false, so the next 80 ms tick could fire a
   second request and another ECU's first frame would clobber the shared
   `tp_buf`/`tp_src`. Only one request is ever on the bus at a time now.

This was the suspected root cause of the engine-ECU (7E0) data **freezing**:
the engine is `POLL[0]`, answered once right after boot, then its subsequent
multi-frame responses got clobbered while the battery ECU (which happened to
win the timing) kept updating. Since `prius::V` never clears a key, the engine
values then stayed frozen at their startup snapshot — looking like "responds
once when the app starts". Verify on-vehicle after flashing; if it persists,
next suspects are the app asserting DTR/RTS on connect (resets the ESP) and/or
a missing `3E 00` TesterPresent keep-alive to 7E0.

### Polled ECUs and PIDs
Response ID = request ID + 8.

| Req ID | Resp ID | ECU            | PIDs (mode 21 unless noted) |
|-------:|--------:|----------------|-----------------------------|
| 0x7E0  | 0x7E8   | Engine         | 2101, 2149, 213C |
| 0x7E2  | 0x7EA   | Hybrid         | 015B (std SoC), 2101, 2161/62/67/68, 2170/71/74/75/7D, 2181, 2187, 2192, 2195, 2198, 218E, 219B, 21E1, 21C1 |
| 0x7C4  | 0x7CC   | A/C            | 2121, 2124, 2129, 2149, 213C, 214B, 2153 |
| 0x7B0  | 0x7B8   | ABS/brake      | 2107 (brake pressure) |

The polling cadence is **event-driven** (one request on the bus at a time, see
ISO-TP below) and each PID carries a `divider` (run every 1/3/10 cycles).

**Removed since v3.9:** the **body ECU (0x7C0/0x7C8) is gone entirely** (v3.18 —
its `bodyV`/`oilDist` were unused). The ABS **wheel-speed** poll (7B0 2103) and
the **G-sensor** poll (7B0 2147) were dropped (v3.9): wheel speeds now come from
the **0x0AA broadcast**, the G/steer signals from passive broadcasts (§9). The
2147 response decoder is kept only for the `raw0..raw7` calibration dump. The
A/C ambient poll (7C4 2122) was dropped (v3.10).

Passive broadcast frames (no request):
* **0x620** — doors (`byte5`) **+ raw ambient light level** (`bytes[2:3]`, BE).
  Doors: `bit5 (0x20)` = front-left/driver, `bit4 (0x10)` = front-right/passenger,
  `bit3 (0x08)` = rear-right, `bit2 (0x04)` = rear-left, `bit1 (0x02)` = trunk
  (rears published separately; L/R is a best guess). Ambient light (`b2:b3`,
  16-bit, **inverted**): ~31 bright .. ~600 dark; the instrument cluster flips its
  day/night illumination at ~100. Confirmed with a 3-pulse lamp test on the sensor.
  → `ambL` key; the app drives dark mode from it (threshold 100, ±10 hysteresis).
* **0x622** — exterior lights, `byte3` bitmask (decoded from a labelled light test):
  `0x10` position, `0x20` low beam, `0x40` high beam, `0x08` front fog, `0x04` rear fog.
  → `lights` key; shown on the Driving tab.
* **0x0AA** — ABS wheel speeds (replaces the 7B0 2103 poll, v3.9): four `u16` BE,
  `×0.01 − 67.67` km/h in order **FR, FL, RR, RL**. `wDif` (front L/R %) derived from them.
  → `wFL/wFR/wRL/wRR/wDif`.
* **0x49B** — drive mode, `byte4` (v3.13, confirmed with an EV/ECO/PWR switch test):
  `bit1 (0x02)` EV, `bit3 (0x08)` ECO, `bit2 (0x04)` PWR (EV is independent of the
  ECO/PWR selector). → `dmode` (0 normal / 1 eco / 2 pwr) + `ev` (0/1).
* **0x399** — cruise **active** (set/holding), `byte1 bit1 (0x02)` (v3.14, found
  empirically: on only when speed is held with the engine working, not while coasting
  or on driver pedal). Replaces the unconfirmed 0x5C8. → `cruise` (in the YAML `extra`).
  The cruise **set-speed** is *not* on this car's bus (0x1D2/0x1D3 absent); the 0x1D3
  decoder (`setSpd`) is kept but stays `null`.

### JSON output schema
One object per line on stdout (USB serial), ~4 Hz. All numeric values are
floats with 2 decimals, or `null` if not yet received. Keys (see
`emit_json` KEYS array in `prius_parse.h` for the authoritative list):

```
Engine:   ct rpm spd load maf map iat thr pedal vbat amb run fuel engNm injml injus
Hybrid:   soc mg1t mg1r mg1q mg2t mg2r mg2q inv1 inv2 btu btl vl vh invct invwp acw
Battery:  hvA hvdis hvchg bmin bmax blkD weakB maxR tHot hvAir tb1 tb2 tb3
          b01..b14 (individual block voltages)
A/C:      cabin setT comp evap solar acAmb blower acPress
ABS:      wFL wFR wRL wRR wDif (from 0x0AA) gLat gFwd steer brkP
Drive:    gear turn setSpd (null on this car) dmode (0=norm/1=eco/2=pwr) ev (0/1)
Fuel/odo: fuelIn (%) fuelL (calibrated litres) odo (km) gasB (pedal %)
          lights (0x622 bitmask) ambL (0x620 light level)
Fans/ECU: battFan ecuMode fanMode dtcCur dtcHist
Derived:  ctR (coolant rate °C/min) fuel (l/h) wpW (water-pump warn 0/1/2)
          cellW (HV cell warn 0/1/2)
Learned:  cwL (learned cell warn) wblk (weakest block #) wz (weak-block Z-score)
          capAh capKwh (self-learned pack capacity, coulomb counting)
Trip:     slots (array of the 8 live TripSlots, see below) · rhN/ohN (history counts) · fCorr (fuel correction ×)
Integers: epoch (unix s, host time) fuelTs (last-refuel epoch) fw (firmware version, major*100+minor)
          offTs/offN (last NVS save epoch / counter)
State:    door (bitmask 0x80 drv / 0x40 pass / 0x08 rear-L / 0x04 rear-R / 0x01 trunk) cruise belt
Debug:    eFF eOK eLen loops · raw0..raw7 (0x7B8/2147 response — G-sensor cal)
```

**Trip slots** (firmware-owned, persisted in NVS; the app only displays). Every
counter is one uniform `TripSlot {epoch, odo, dist, ev, fuel, move_s, regen_e,
brake_e, city_dist, city_ev, start_frac}`. The regular line carries a `"slots"` array of 8 live
slots in order: `[0] since-boot (memory only)`, `[1] lifetime`, `[2] tank`, `[3] oil`,
`[4-6] A/B/C`, `[7] home-departure`. Each slot object is `{e,o,d,v,f,m,r,cd,ce}`
(**r = recovered electrical energy in kWh** (`regen_e/3.6e6`, fw ≥3.47; older firmware sent
the regen ratio % — the app zeroes `r` for fw <347); **`cd` = city km, `ce` = city-EV km** —
accumulated only while the app's geofence reports `in_city`, see the `G1`/`G0` command;
the top-level `"city"` flag echoes the current state). The app derives avg consumption (`fuel/dist`),
avg speed (`dist/move_s`) and a recovered-km equivalent (`r × 15 km/kWh`, the measured EV
efficiency). Refuel history (`rhist`) and oil-change history (`ohist`), 50 entries each,
are fetched on demand (`H` / `HO`).

`epoch`, `fuelTs`, `fw` are appended by `emit_json` as **integers** (a unix epoch /
version does not fit a 24-bit-mantissa float, so they bypass the float `V[]` store).

Signals reverse-engineered from passive 0x6xx broadcasts (see §9): `fuelIn` (fuel %,
from `0x612` byte[5] /255×100), `odo` (km, `0x611` bytes[5..7]), rear doors (`0x620`
byte[5] / `0x626` byte[2]).

### Derived values & warnings (`compute_derived`)
* **fuel** (l/h) — **injector-based** (primary): `INJ_K * injml * rpm * k(spd)`,
  0 when rpm < 100. `injml` (injected volume) tracks the *real* commanded fuel, so it
  includes power-enrichment **and** decel fuel-cut (injectors off → 0, where MAF
  over-counted). `INJ_K = 0.00976` (raw); the pump calibration is **speed-dependent**
  (fw ≥3.49): `k(spd) = fuel_corr × clamp(1.2422 − 0.002804·spd, 0.90, 1.25)` — fit to
  two full pump-measured tanks (46.26 L @54 km/h avg, 43.8 L @71 km/h avg): the raw model
  under-reads at low speed and over-reads on the highway; both tanks reproduce to ~1 %.
  **MAF fallback** (`maf/14.7*3600/745`, same k) only while the injector PID hasn't
  arrived. The single source `V[FUEL]` feeds the instant display and trip accumulation.
* **fuel_corr** — user fuel-consumption correction (×, default 1.0), set via the `F`
  command, persisted as an ESPHome global. Refine at a full refuel (accumulated L vs
  pump litres). Affects instant *and* trip fuel.
* **wpW** (water-pump warning, 0/1/2): coolant absolute thresholds + coolant
  rise-rate (`ctR`) + the direct `wpRun` pump-running bit.
* **cellW** (HV cell warning, 0/1/2): **the self-learning layer is the primary
  detector once mature** (`lz_n ≥ 300` under-load samples → `cellW = learned`); before
  maturity it falls back to data-informed fixed block-delta thresholds (warn 1.20 /
  err 1.60 V dload). Coarse hard-safety always overrides: `maxR ≥ 40/60` mΩ, `tb ≥ 55 °C`.
  Validated against a real drive log where the old fixed thresholds false-alarmed while
  the learned layer (and `maxR ≤ 25` mΩ) correctly read healthy. See §8.
* **capAh / capKwh** (self-learned capacity): coulomb counting over the SoC
  **peak↔trough** swing (a fixed anchor never moves 10 % on the Prius's narrow
  oscillating SoC band — the running min/max + the charge integral at each does).
  kWh uses the averaged pack voltage `vl`. Persisted in NVS. **Smoothed in v3.16**:
  `CAP_MIN_DSOC 8→15 %` (bigger swing required → far less noise), `CAP_ALPHA 0.25→0.05`
  (slower EWMA, settles ~±0.4 Ah), and a `CAP_CHG_EFF = 0.93` NiMH **coulombic
  efficiency** applied to charge spans so the result is *dischargeable* Ah.
* **Trip slots** (unified `TripSlot`, see the schema above): one per-tick delta
  (`dist`/`ev`/`fuel`/`move_s`/regen energies) is added to **every** slot; they differ
  only in *when* they reset. **Distance is the real odometer delta** (`odo` from 0x611,
  drift-free) for any slot with a stamped start odo — the speed integral only fills it
  until the odo is known (v3.12, fixes the ~0.4 % speed-integral drift). Moving time
  counts stops ≤180 s (and a just-started, still-standing car does **not** count until it
  first moves — `stop_s` starts high). **Regen ratio** (`r`) accumulates the kinetic
  energy shed and the energy recovered into the pack **only while the brake pedal is
  pressed** (`brkP > 0.55`); v3.11 dropped the old `hvA < −5` trigger (which fired while
  coasting with the engine charging — not regen — and inflated the ratio >100 %) and
  clamps the ratio to 100 %. Persisted in NVS (power-off / refuel / reset) and restored
  first at boot, behind a load guard. The since-boot slot is memory-only (resets each
  power-on). Slots can be **copied** from since-boot/from-home into A/B/C (`C` command, v3.15).
* **Refuel** (`fuelTs` = `slot[1].epoch`): **plateau-based since v3.19, works while
  MOVING since v3.49** — a jump of `REFUEL_DELTA` (25 %) above the tracked baseline
  (`fuel_ref`) arms the detector; it tracks the peak while `fuelIn` keeps rising and
  records **exactly one event** once the level stops rising for `REFUEL_STABLE_MS` (8 s).
  There is deliberately NO "parked" requirement: the head unit (which USB-powers the ESP)
  usually reconnects only after the car is already driving away from the pump, so a
  parked-only detector never saw the fill; the 8 s plateau alone rejects glitches/slosh
  (25 % > the ~±20 % full-tank slosh). The baseline follows the gauge both ways but
  ignores the <2 % shutdown glitch (else a boot-time full tank looks like a +100 jump).
  On the event: push `slot[1]` into `rhist`, reset it, stamp `cur_epoch` + odometer
  (host-time back-correction as before). Detection is gated on `persist_loaded`.
  Manual fallback: the `K` command ("Tankoltam" button) commits a refuel by hand.
* **fuelL** (calibrated tank litres, v3.20) — see the tank-calibration note in §6.

### Serial command protocol (app/PC → ESP)
The firmware reads `\n`-terminated text commands from the USB-Serial/JTAG console
(`read_host_serial`, non-blocking) and acts on them:

| cmd | meaning |
|-----|---------|
| `T<unix>\n` | head-unit wall-clock time (no Wi-Fi/NTP → the head unit is the only time source) |
| `D1\n` / `D1 <lo> <hi>\n` | CAN dump on (default filter 0x500–0x6FF + dedup, or a custom range) |
| `D2\n` | CAN dump on, **unknown frames only** — every ID we don't already decode (`dump_is_known`), deduped. One discovery capture surfaces all still-unknown signals (lights/turn/brake/L-R doors…). |
| `D0\n` | CAN dump off |
| `O<size>\n` | begin serial OTA of a `size`-byte image (see §10) |
| `F<factor>\n` | fuel-consumption correction (× 0.5–2.0), persisted in `fuel_corr_g` |
| `R<2..6>\n` | reset trip slot *i* (2=oil 3..5=A/B/C 6=home); oil push to `ohist` first |
| `H\n` / `HO\n` | dump the refuel / oil-change history array (on demand) |
| `B\n` | dump **per-block internal resistance** — `{"rblk":[…14…],"rn":N}` (v3.17; not in the 4 Hz line) |
| `C<dst><src>\n` | copy a live trip into slot *dst* (3=A 4=B 5=C) from *src* (`B`=since-boot, `H`=from-home) (v3.15) |
| `G0\n` / `G1\n` / `G2\n` | app **geofence** state → `city_state`: **0** unknown (no fix / area not covered by any `.bgf`), **1** belterület, **2** országút. Persisted across key-off; **city km / city-EV km** (each slot's `cd`/`ce`) accrue only in state 1; the top-level `"city"` echoes 0/1/2 (v3.42) |

CAN dump emits `#XXX [len] BB BB..\n` lines for frames in `[lo,hi]`, **deduped**
(only on change) → opening a door makes the relevant frame pop out (how the 0x620
door / 0x611 odo / 0x612 fuel signals were found). The output path (`out_write`) and
`emit_json` are non-blocking so a backed-up host never stalls the main loop;
overflow is counted in `out_dropped`. `prius_dump.yaml` remains as a standalone
fallback dumper.

---

## 4. Android app

Package `hu.codingo.priuscan`, minSdk 26, Kotlin + Jetpack Compose. ~1700 LOC.

### Source files

| File | Responsibility |
|------|----------------|
| `MainActivity.kt` | Compose main screen: redesigned **dashboard top** (see below), grouped sensor list, 14 HV block list + per-block internal-resistance section, trip switcher, Settings navigation. |
| `CanService.kt` | Foreground service (START_STICKY). Owns the USB serial reader with auto-reconnect; builds the status-bar notifications + the draggable status overlay; raises warnings (sound + overlay); drives the door overlay; relays state to `HaPusher`. |
| `SerialLink.kt` | usb-serial-for-android wrapper; CDC-ACM driver registration for the C3 (0x303A/0x1003) and C6 (0x303A/0x1001) native USB; line reader. |
| `CanState.kt` | `Fields` object (display groups + units) and `CanState` (parses one JSON line; `d(key)`/`i(key)` accessors; `door`, `gear`, `coolant`, `wpWarn`, `cellWarn`). |
| `OverlayManager.kt` | System-alert overlay windows: warning popup, the draggable status strip, and the door overlay host. The container is built once and reused (shown/hidden, not rebuilt). |
| `DoorImageView.kt` | Door overlay: loads the pre-rendered `assets/car_<doormask hex>.png` for the current door combination and draws it scaled-to-fit (small LruCache). No GL/Filament. **Door mask → mesh mapping note:** the model's fl/fr naming is swapped vs reality (0x80 driver→Door_fr, 0x40 passenger→Door_fl); see `render/render_combos.py`. |
| `HaPusher.kt` | MQTT client (Paho). Opportunistic connect; offline JSONL backlog (`ha_backlog.jsonl`, 2 MB cap, timestamped); publishes fresh state to `<prefix>/state`, backlog to `<prefix>/backlog`; auto-publishes MQTT Discovery configs for every field incl. b01..b14. Host field accepts full URI (tcp/ssl/ws/wss). |
| `SettingsScreen.kt` | Compose settings: status-bar item checkboxes (ct/soc/rpm/cons), MQTT host/port/user/pass/prefix, push interval. |
| `Prefs.kt` | SharedPreferences wrapper (incl. saved status-overlay x/y). |
| `BootReceiver.kt` | Starts the service on `BOOT_COMPLETED`. |

### Dashboard top (redesign)
The main screen now leads with a compact dashboard:
* big **coolant temperature** + a **status-icon strip** that shows an icon **only when
  the state is active** (exterior lights, cruise, brake — a brake-pedal PNG, distinguishing
  physical brake from regen-only —, EV, ECO/PWR badges, seatbelt). Icons are mdi vector
  drawables; the strip wraps to a second row (`FlowRow`).
* two **`Canvas`-drawn gauges**: a **tank** gauge (`fuelIn` %, with the calibrated
  **`fuelL` litres** under the %) and a **SoC battery** gauge (mapped over the ~35–70 %
  operating band, with a charge arrow when `hvA < 0`).
* a justified **ODO row**: gear · speed · rpm · odometer · consumption (replaces the old
  separate odo/fuelIn rows).
* the **trip detail** line reads `X km / Y EV km (Z%)`, and the per-block
  internal-resistance section periodically pulls fresh data (`CanService.fetchBlockR()`
  every 5 s → the `B` command, parses the `rblk` reply). Trip A/B/C can be filled from
  the since-boot / from-home trip via `CanService.copySlot()` (the `C` command).

### Status overlay (and why not the notification bar)
The head-unit ROM (K706, Android 10) does **not** render third-party
notification icons in its system status bar, so the status-bar-notification
approach is invisible there. Instead `OverlayManager.updateStatus` draws the
selected values as an always-on `TYPE_APPLICATION_OVERLAY` strip. It is
**draggable anywhere** (touch-drag, position persisted in `Prefs.statusX/Y`).
Note: an app overlay can never sit *on top of* the system status bar (window
z-order is fixed by type), so it lives below/around it. The per-value
custom-drawn notification icons are still posted (work on ROMs that show them).

### Door overlay (pre-rendered PNGs)
Filament 3D was dropped: on this head unit a `SurfaceView` GL swapchain inside
an overlay window did not composite **and** stalled the main thread (ANR). The
door overlay is now **pre-baked**: `render/render_combos.py` (Blender, headless
Cycles) renders all 32 door combinations of `render/prius.glb` from a strict
top-down ortho camera (nose up), framed to the all-doors-open bounding box so
nothing clips, transparent background, ~480 px. Output is
`app/src/main/assets/car_<doormask hex>.png` (e.g. `car_00.png` closed,
`car_cd.png` everything open). `DoorImageView` just loads the file matching
`mask & 0xCD`. Re-bake after a model/pivot change:
`blender -b -P render/render_combos.py -- render/prius.glb app/src/main/assets`.
**Verify driver-side in-car**; if the wrong side opens, swap the bit→mesh rows
in `render_combos.py` (and re-render).

### Home Assistant / history
HA's recorder cannot accept back-dated state, so historical drive data must go
to **InfluxDB** (which accepts an explicit timestamp). Intended chain:
`device logs (with ts) → opportunistic burst → VPS endpoint → InfluxDB → HA/Grafana`.
The device-side JSONL buffer + burst is implemented in `HaPusher`; the VPS
endpoint and InfluxDB location are **still an open decision** (see §6).

---

## 5. Build & flash

### Firmware
```bash
# files prius_can_full_v2.yaml + prius_parse.h in the same dir
esphome run prius_can_full_v2.yaml      # USB cable; BOOT button if needed
esphome logs prius_can_full_v2.yaml     # watch the JSON stream / dumps
```
Production config has **no Wi-Fi/OTA/web_server** (commented-in temporarily only
for G-sensor calibration). Debug via the USB serial monitor.

### Android app (WSL build, Windows install)
Prereqs in WSL: `openjdk-17-jdk`, Android cmdline-tools, env vars
(`ANDROID_HOME`, `JAVA_HOME`, `PATH`), then
`sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"`, and
`local.properties` with `sdk.dir=$HOME/Android/Sdk`.

```bash
cd priuscan
./gradlew assembleDebug        # debug build is correct: self-signed, installable
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Install from Windows (WSL can't see USB by default): copy the APK out via
`\\wsl$\...` and `adb install -r app-debug.apk`. Keep the project on the WSL
native FS (`~/...`), not `/mnt/c`, for speed.

Use **debug**, not release — no signing key needed, no minification (which can
trip up Paho). The APK is ~17 MB (Filament was removed; the door overlay is
pre-rendered PNGs now).

> **WSL gotcha:** if `local.properties` points `sdk.dir` at a **Windows** SDK path,
> the Gradle build **silently fails** (no APK) and the *previous* APK gets installed —
> always check the APK timestamp. Point `sdk.dir` at the **Linux** SDK
> (`$HOME/Android/Sdk`).

### Helper scripts (`tools/`)
* `install-app.sh [--no-build] [IP]` — build the debug APK with the **Linux** SDK and
  install    it on the head unit over **network adb** (port `:9876`).
* `pull-logs.sh [IP]` — download new `.jsonl`/`.gz` logs + CAN dumps into `carlogs/`
  (only what's missing locally or larger on the device, e.g. the growing daily JSONL).
* `priuscan-device.sh` — shared helper that finds the head unit on the (dynamic) hotspot
  IP: a fast `/dev/tcp` probe first, then `adb connect` only on an open port.
* `serial_ota.py` — PC-side serial-OTA tester (§10).

---

## 6. Calibration status & open tasks

### Verified working (from real logs)
* Engine (after ISO-TP fix): ct, rpm, spd, maf, load, fuel.
* Hybrid drive: MG1/MG2 rpm/torque (signed), pack current −55..+72 A, VH 215–500 V.
* Battery: blocks 15–16 V, delta 0.05–0.37 V (healthy), temps 32–35 °C,
  weakB rotates (no persistent weak block).
* A/C: cabin, compressor, evaporator, A/C power, ambient.
* ABS: four wheel speeds (now from the **0x0AA** broadcast), steering angle, brake pressure.
* Doors: 0x620 byte5 mapping verified by dump.
* Drive mode (`dmode`/`ev`, 0x49B) and cruise **active** (`cruise`, 0x399) — confirmed
  with switch tests.
* **Trip distance** now drift-free (odometer-delta, v3.12); **regen ratio** brake-pedal-only
  and ≤100 % (v3.11); **pack-capacity** learning smoothed (v3.16); **per-block internal
  resistance** (v3.17) reads sane (~few mΩ) but the absolute level still wants a known-good
  reference (it tracks *relative* change reliably).
* **Tank litres** (`fuelL`) calibrated — see the tank-calibration note below.

### Open
1. **G-sensor (`gLat`/`gFwd`)** — the saturating 7B0 2147 poll was **abandoned**;
   the values now come from passive broadcasts: `gLat` from **0x4A1** (signed16 BE),
   `gFwd` from **0x320** byte4 (stored as `154 − b4`). Both are emitted as **raw counts**,
   **not yet calibrated** to m/s². A controlled test is still needed: a known
   level-ground drive (steady accel/braking + a known incline) to fit centre + scale.
   The old 2147 decoder is kept only for `raw0..raw7`.
2. **Rear doors L/R** — now split (bit2 0x04 = rear-L, bit3 0x08 = rear-R, a
   best guess). Confirm L/R by opening one rear door; swap the two
   `publish_state` lines in the 0x620 handler if reversed.
3. **Engine ECU "responds once"** — engine values froze at the startup snapshot.
   Fixed in firmware by full request↔response serialization (§3 ISO-TP). Re-log
   after flashing to confirm `ct`/`rpm`/`spd`/`fuel` update continuously; if not,
   try not asserting DTR/RTS in `SerialLink.kt` (avoids resetting the ESP on
   connect) and/or a periodic `3E 00` TesterPresent to 7E0. PriusChat confirms
   Gen3 mode-21 PIDs are normally loggable continuously.
4. **InfluxDB location** — decide HA host vs Netcup VPS for the history chain.
5. **DTC read-out (mode 03/07)** — `dtcCur`/`dtcHist` give *counts*; decoding the
   actual P-codes is a natural next feature (the ISO-TP machinery already exists).
6. **HV cell-warning calibration** — `cellW` now blends a fixed-threshold safety
   path with a **self-learning weak-block layer** (`learn_update`/`learn_verdict`,
   see §8). The learn thresholds (`LRN_K1/K2`, `LRN_ALPHA`, `LRN_LOAD_A`) and the
   provisional under-load delta thresholds are **estimates** — tune them from a
   real driving log (the app's local JSONL store, §4) before trusting absolute levels.
7. **Cruise set-speed** — only cruise **active** (0x399) is decoded; the **set-speed
   value** (`setSpd`, decoded from 0x1D2/0x1D3 on other Gen3 segments) is **absent on
   this car's bus**, so the value can at best be *inferred* (e.g. held vehicle speed).
8. **Daily flash health/trip ring** (v3.17, NVS namespace `priusday`) — implemented
   (one record/day, 8-year ring, written on shutdown/quiet detection, boot seeds the
   per-block-R display). **Needs on-device verification** that records accumulate and
   roll over correctly across real power cycles.

### Tank-litre calibration (`fuelL`, v3.20; constants re-fit v3.50)
The gauge `fuelIn` (0x612 byte5, 0..100 %) is non-linear at the ends, so litres are
derived with three constants (`prius_parse.h`): `TANK_FULL = 47` L (validated by a
46.26 L fill — the tank went to ~0.7 L), `FUEL_HEAD = 6.8` L (the top plateau where the
gauge stays pinned at 100 %), `FUEL_MEAS = 33.8` L (the span the gauge actually measures
0..100 %), and `FUEL_BOTTOM = TANK_FULL − FUEL_HEAD − FUEL_MEAS ≈ 6.4` L (reserve below
0 %). Fit: common-slope regression of k(spd)-model fuel burned vs `fuelIn`% across two
full pump-measured tanks (tools/fwsim replay); the gauge- and consumption-based remaining
litres then agree to ~1 L over the whole measured span. On a fresh tank the firmware
**assumes full and counts down** (`fuel_since_refuel`) until the gauge stably leaves
100 % (`tank_known` — anchor calibration, `FUEL_LEAVE_MS` streak); thereafter
`fuelL = anchor − consumed`. The anchor is persisted WITH a calibration-version key
(`gver` = `GAUGE_CAL_VER`): after an OTA that changes HEAD/MEAS, a stale anchor is
discarded (it would bias `fuelL` by ~1–2 L) and re-calibrated from the live gauge.
The app shows `fuelL` (L) under the % on the tank gauge.

### Deliberately NOT done (safety / not feasible)
* SRS airbag DTCs — separate diag protocol, Techstream territory.
* A/C set-point control — no write command exists in the CSV (read-only).
* Door lock/unlock, odometer/oil reset — undocumented UDS writes, risky / wrong bus.
* Bidirectional actuator tests — except the documented battery-cooling-fan speed
  (3081 06xx) and beeper toggles (7C0 3BA7xx), which exist but are out of scope.

---

## 7. Provenance of PID formulas
All diagnostic PID scales/offsets come from the PriusChat **"GenIII Prius
4-24-12" Torque CSV** (metric variant), grouped by ECU header + mode/PID. The
door/cruise broadcast frames are **not** in that CSV — they were reverse
engineered from dumps (`prius_dump.yaml`) and are the least-certain part. When
adding PIDs, take the equation from the CSV (letters A,B,C… map to payload bytes
d[0], d[1], d[2]… after the mode+PID echo).

---

## 8. Battery-health monitoring (research summary)

Full report with citations and caveats: **`BATTERY_HEALTH.md`**. Short version —
how to assess the NiMH pack purely from the passively logged signals
(`b01..b14`, `hvA`, `tb1..3`/`hvAir`, `maxR`, `blkD`, `soc`):

* **Goal 1 — early weak-block / asymmetry (best-supported, passive):** statistical
  anomaly detection on the per-block voltage stream — moving-window entropy,
  **Z-score "abnormity coefficient"**, normalized CV, CVA+KDE+Mahalanobis.
  Use **SD/MAD/Z-score, not IQR** (IQR over-flags). Threshold must be
  **load/condition-aware** (raw thresholds cause many false positives on real data).
* **Goal 2 — SOH / IR growth / capacity fade:** rising **internal resistance**
  (SoC- and temperature-dependent → normalize by SoC+temp bins), under-load
  voltage depression, self-discharge; EOL ≡ 80 % of rated Ah. Voltage spread is
  load-dependent: **high discharge current** makes weak-block/IR differences
  obvious (I·R dominates) — compute block stats **conditioned on `|hvA|` bins**.
* **Goal 3 — RUL:** data-driven ML (lowest avg error), adaptive Kalman online,
  and the NiMH-specific **Palmgren–Miner** fatigue damage-accumulation model.

**Implemented here:** a **self-learning weak-block layer** in `prius_parse.h`
(`learn_update`/`learn_verdict`): per-block Z-score vs the pack mean, learned via
a per-block EWMA (adaptive data-relative baseline), updated **only under load**
(`|hvA|>30 A`) with a maturity gate. Outputs `cwL`/`wblk`/`wz` and feeds `cellW`
as `max(fixed, learned)`. State (`lz[14]+lz_n`) persists in the **NVS blob** (`prius_persist.h`, single source
of truth — the redundant `restore_value` globals were dropped in v3.19). It detects
**relative asymmetry/change, not absolute SOH** — absolute SOH/RUL needs a
known-good reference. Validate the learned baseline against the app's local JSONL
log (§4) before tuning the thresholds.

**Per-block internal resistance (v3.17).** A **trigger-event** method (`rblk_event`):
on each fresh complete block-voltage set, if the pack current stepped `|dI| > R_DI`
(40 A) since the previous set, one `R_i = −dV_i/dI` sample per block is pushed into a
**rolling window** (`R_WIN = 60`); the published value is the window **median** (robust
to the temp/SoC floor). Validated on real logs: N=60 / |dI|>40 A gives ~±0.4 mΩ
within-day single-block scatter, well below the mΩ-per-month aging signal. The window
is **RAM-only** (refills in 1–2 min of varied driving); the latest medians ride along in
the daily flash record and **seed the display at boot**. It is **slow-changing → emitted
on demand** (`B` command → `{"rblk":[…],"rn":N}`) and auto every ~15 events — **never in
the 4 Hz line**.

**Pack-capacity smoothing (v3.16).** `CAP_MIN_DSOC` 8→15 % (bigger swing → far less
noise), `CAP_ALPHA` 0.25→0.05 (slower EWMA), and a `CAP_CHG_EFF = 0.93` NiMH coulombic
efficiency on charge spans (so the result is *dischargeable* Ah, not the larger charge-in Ah).

**Daily flash health/trip ring (v3.17).** A **separate NVS namespace `priusday`** holds a
~8-year ring (`DAILY_N = 2920`, one `DailyRec` per day) that is **independent of the
trip-slot blob** (no magic bump / data loss). One record accumulates across the day's
drives — distance/EV/fuel/moving-time and per-drive battery throughput (`drive_batt_in/out`)
are **delta-folded** (`boot_folded`/`batt_*_folded`, fixed in v3.18 so a save on each
ignition-off doesn't double-count the since-boot totals), while health fields (per-block R,
TB1..3, capacity, odo) store the latest. It is written on the **shutdown/quiet detection**
(0x1C4 silent > 0.8 s) and at boot it seeds the per-block-R display from the most recent record.

**Key caveat:** nearly all strong statistical methods were validated on Li-ion,
not NiMH — the math transfers but the numeric thresholds must be re-tuned for
NiMH (flat plateau, memory-effect voltage depression, temperature sensitivity).
ICA/DVA is likely unusable on NiMH (flat plateau suppresses dQ/dV peaks).

---

## 9. Body / comfort broadcasts (0x6xx) — reverse-engineered

Decoded passively (no poll) via the app-controlled CAN dump (§3). Decoders live in
`on_broadcast()` and the YAML `canbus on_frame` list.

| ID | byte(s) | signal | notes |
|----|---------|--------|-------|
| `0x611` | 5..7 (24-bit) | **odometer (km)** | exact match: `0x0676D0 = 423632 km` |
| `0x612` | 5 | **fuel level** | `% = b5/255×100`; calibrated: 5/10 dash segments = b5≈129 ≈ 50 % → linear 0..255 |
| `0x620` | 5 (`0x0C`) | **rear door open** | toggles with a rear door; L/R not yet split |
| `0x626` | 2 (`0x30`) | rear door (mirror) | changes in lockstep with `0x620` b5 |
| `0x63B`,`0x619` | b5..7 | trip/odo fine counters | fast-rising while moving |
| `0x610` | 2 | speed-like | 0 parked, ~100 cruising (redundant with the 0x0B4 broadcast) |
| `0x0AA` | 0..7 (4×u16 BE) | **ABS wheel speeds** | `×0.01 − 67.67` km/h, order FR,FL,RR,RL — replaces the 7B0 2103 poll (v3.9) |
| `0x49B` | 4 | **drive mode** | bit1(0x02)=EV, bit3(0x08)=ECO, bit2(0x04)=PWR — switch-test confirmed (v3.13) |
| `0x399` | 1 | **cruise active** | bit1(0x02), set/holding only; empirically confirmed, replaced 0x5C8 (v3.14) |

> **Body ECU removed (v3.18):** the 0x7C0 polls / 0x7C8 responses (with `bodyV` +
> `oilDist`) were dropped — they were never used. The diag ECUs are now Engine (7E0),
> Hybrid (7E2), A/C (7C4), ABS (7B0) only.

`0x5A4` (the "gas gauge" some sources cite) is **NOT present** on this car's powertrain
segment — fuel had to come from the instrument-cluster `0x612`. The car's **`solar`**
(A/C sun-load sensor, climate ECU `0x7CC` PID `0x24`) is the ambient-light signal used
for auto dark mode (§11). `byte[1]` of the cluster frames is an alive/rolling bit, not data.

**Still open:** rear door L/R split, lights/turn/brake (need a targeted one-thing-at-a-time
dump — none were actuated in the daytime drives captured so far).

## 10. Serial OTA & app-driven firmware update

The head-unit app can flash the ESP over the **same USB link** (no Wi-Fi/OTA, no
download mode, no re-enumeration), and it **preserves NVS** (only the inactive OTA
partition + otadata are written by `esp_ota_*`).

**Versioning.** `FW_VERSION` (in `prius_parse.h`) is `major*100+minor` (e.g. `204` =
v2.4), emitted as the integer `fw` in the JSON. The APK bundles `assets/firmware.bin`
(the ESPHome app image) + a `BUNDLED_FW` constant (`CanService`); the app offers an
update when `fw < BUNDLED_FW`. **Bump both together** when shipping new firmware in the app.

**Protocol** (app `doSerialOta` ↔ firmware `prius_ota.h`):
```
app  O<size>\n              ; size = raw image bytes
ESP  K\n                    ; esp_ota_begin done (inactive partition erased)
app  <base64 of ≤600 raw bytes>\n   ; per chunk
ESP  A\n                    ; ack -> next chunk     (E\n on error)
...
ESP  D\n                    ; esp_ota_end + set_boot_partition done -> reboot
```
**Why base64 + a dedicated task:** the USB-Serial/JTAG **console mangles raw binary**
(control bytes break `read()`), so the image is sent as base64 text lines. The receiver
runs in a **dedicated FreeRTOS task** using the `usb_serial_jtag_read_bytes()` driver
API — a loop-based receiver failed because `esp_ota_begin`'s erase + main-loop stalls
starved the VFS `read()`. During OTA the main loop's `read_host_serial` and `emit_json`
are gated off (`ota_active`).

**Bootstrap:** the *running* firmware must already contain the OTA receiver, so the
first OTA-capable build is flashed once from a PC (`esphome run`). From then on the app
flashes new versions over serial.

**PC test tool:** `tools/serial_ota.py` mirrors `doSerialOta` exactly, so the firmware
receiver can be tested from Windows/WSL without the app:
`python serial_ota.py <PORT> .esphome/build/prius-can/.pioenvs/prius-can/firmware.bin`.

## 11. Theme: light/dark + car-driven dark mode

The app follows the **system** light/dark by default (`PriusTheme` +
`isSystemInDarkTheme`); background/text use `MaterialTheme.colorScheme.*`
(`onBackground`/`onSurfaceVariant`), accent/status colors stay fixed.

Optionally (Settings → Display) the dark/light state is driven by the **car's own
`solar` sun-load sensor** (not an Android light sensor): `CanService` sets `carDark`
with hysteresis (dark `solar<6`, light `solar>20`). When enabled it also attempts a
**device-wide** night mode (`UiModeManager.setNightMode` + `Settings.Secure ui_night_mode`)
which needs a one-time grant:
```
adb shell pm grant hu.codingo.priuscan android.permission.WRITE_SECURE_SETTINGS
```
If the grant/permission isn't honored the in-app theme still follows `carDark`.

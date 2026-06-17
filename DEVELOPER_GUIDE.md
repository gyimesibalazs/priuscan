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
  single-line JSON object on the native USB serial port (~2 Hz).
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
* A 1 s interval calls `prius::compute_derived()`, a 500 ms interval calls
  `prius::emit_json(extra)`.

### ISO-TP handling & full request↔response serialization
Multi-frame responses (e.g. `2101`, the 14 block voltages) arrive as
first-frame → flow-control → consecutive-frames. The polling loop must **not**
fire a new request while a transfer is in progress, otherwise the transfer is
aborted and the data never completes.

`next_request` enforces this in two stages:
1. **In-progress guard** — while an ISO-TP transfer is mid-flight it waits (up
   to a 120 ms timeout, tracked via `tp_started_ms`).
2. **Full serialization** (`awaiting` / `await_resp`) — after sending a request
   it refuses to send another until that request's response has *completed*
   (single frame, multi-frame, or even a negative `7F`), or 120 ms elapses.
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
| 0x7C4  | 0x7CC   | A/C            | 2121, 2122, 2124, 2129, 2149, 213C, 214B, 2153 |
| 0x7B0  | 0x7B8   | ABS/brake      | 2103, 2107, 2147 |
| 0x7C0  | 0x7C8   | Body           | 2113, 2129, 2141 |

Passive broadcast frames (no request):
* **0x620** — doors. `byte5` bits (verified by dump):
  `bit5 (0x20)` = front-left/driver, `bit4 (0x10)` = front-right/passenger,
  `bit3 (0x08)` = rear-right, `bit2 (0x04)` = rear-left, `bit1 (0x02)` = trunk.
  The two rears are now published **separately** (`door_rear_left` /
  `door_rear_right`); the L/R assignment is a best guess — swap the two
  `publish_state` lines if a dump shows it reversed.
* **0x5C8** — cruise main switch (byte2 bit4) — *tentative, verify in dump*.

### JSON output schema
One object per line on stdout (USB serial), ~2 Hz. All numeric values are
floats with 2 decimals, or `null` if not yet received. Keys (see
`emit_json` KEYS array in `prius_parse.h` for the authoritative list):

```
Engine:   ct rpm spd load maf map iat thr pedal vbat amb run fuel engNm injml injus
Hybrid:   soc mg1t mg1r mg1q mg2t mg2r mg2q inv1 inv2 btu btl vl vh invct invwp acw
Battery:  hvA hvdis hvchg bmin bmax blkD weakB maxR tHot hvAir tb1 tb2 tb3
          b01..b14 (individual block voltages)
A/C:      cabin setT comp evap solar acAmb blower acPress
ABS:      wFL wFR wRL wRR wDif gLat gFwd steer brkP
Body:     bodyV fuelIn oilDist
Fans/ECU: battFan ecuMode fanMode dtcCur dtcHist
Derived:  ctR (coolant rate °C/min) fuel (l/h) wpW (water-pump warn 0/1/2)
          cellW (HV cell warn 0/1/2)
State:    door (bitmask 0x80 drv / 0x40 pass / 0x08 rear-L / 0x04 rear-R / 0x01 trunk) cruise
Debug:    raw0..raw7 (raw bytes of the 0x7B8/2147 response — G-sensor cal)
```

### Derived values & warnings (`compute_derived`)
* **fuel** (l/h) = `maf / 14.7 * 3600 / 745` (stoichiometric AFR, petrol density);
  0 when rpm < 100 (engine off / EV mode).
* **wpW** (water-pump warning, 0/1/2): coolant absolute thresholds + coolant
  rise-rate (`ctR`) + the direct `wpRun` pump-running bit.
* **cellW** (HV cell warning, 0/1/2): block delta under load vs rest +
  internal resistance (`maxR`, mΩ thresholds 40/60) + battery temperature spread.

### Dump mode
`prius_dump.yaml` is a separate, standalone config (no parser header) for
reverse-engineering passive broadcast frames. It prints only 0x500–0x6FF frames
and only when their content **changes**, so opening a door makes exactly the
relevant frame pop out. This is how the 0x620 door mapping was found.

---

## 4. Android app

Package `hu.codingo.priuscan`, minSdk 26, Kotlin + Jetpack Compose. ~1700 LOC.

### Source files

| File | Responsibility |
|------|----------------|
| `MainActivity.kt` | Compose main screen: header (key gauges), grouped sensor list, 14 HV block list, Settings navigation. |
| `CanService.kt` | Foreground service (START_STICKY). Owns the USB serial reader with auto-reconnect; builds the status-bar notifications + the draggable status overlay; raises warnings (sound + overlay); drives the door overlay; relays state to `HaPusher`. |
| `SerialLink.kt` | usb-serial-for-android wrapper; CDC-ACM driver registration for the C3 (0x303A/0x1003) and C6 (0x303A/0x1001) native USB; line reader. |
| `CanState.kt` | `Fields` object (display groups + units) and `CanState` (parses one JSON line; `d(key)`/`i(key)` accessors; `door`, `gear`, `coolant`, `wpWarn`, `cellWarn`). |
| `OverlayManager.kt` | System-alert overlay windows: warning popup, the draggable status strip, and the door overlay host. The container is built once and reused (shown/hidden, not rebuilt). |
| `DoorImageView.kt` | Door overlay: loads the pre-rendered `assets/car_<doormask hex>.png` for the current door combination and draws it scaled-to-fit (small LruCache). No GL/Filament. **Door mask → mesh mapping note:** the model's fl/fr naming is swapped vs reality (0x80 driver→Door_fr, 0x40 passenger→Door_fl); see `render/render_combos.py`. |
| `HaPusher.kt` | MQTT client (Paho). Opportunistic connect; offline JSONL backlog (`ha_backlog.jsonl`, 2 MB cap, timestamped); publishes fresh state to `<prefix>/state`, backlog to `<prefix>/backlog`; auto-publishes MQTT Discovery configs for every field incl. b01..b14. Host field accepts full URI (tcp/ssl/ws/wss). |
| `SettingsScreen.kt` | Compose settings: status-bar item checkboxes (ct/soc/rpm/cons), MQTT host/port/user/pass/prefix, push interval. |
| `Prefs.kt` | SharedPreferences wrapper (incl. saved status-overlay x/y). |
| `BootReceiver.kt` | Starts the service on `BOOT_COMPLETED`. |

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

---

## 6. Calibration status & open tasks

### Verified working (from real logs)
* Engine (after ISO-TP fix): ct, rpm, spd, maf, load, fuel.
* Hybrid drive: MG1/MG2 rpm/torque (signed), pack current −55..+72 A, VH 215–500 V.
* Battery: blocks 15–16 V, delta 0.05–0.37 V (healthy), temps 32–35 °C,
  weakB rotates (no persistent weak block).
* A/C: cabin, compressor, evaporator, A/C power, ambient.
* ABS: four wheel speeds, steering angle, brake pressure.
* Doors: 0x620 byte5 mapping verified by dump.

### Open
1. **G-sensor (`gLat`/`gFwd`)** — only swings between the two extremes
   (−25.11/+24.91), never near 0. Formula matches the CSV and `steer`
   (same response, bytes 3–4) works, so the payload indexing is right but the
   G bytes read wrong. **Use `raw0..raw7` on the web UI** (temporarily re-enabled
   Wi-Fi) to find the real byte, then fix `case 0x47` in `prius_parse.h`.
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

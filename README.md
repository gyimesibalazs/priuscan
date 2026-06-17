# PriusCAN

Full CAN/OBD telemetry & diagnostics for the **Toyota Prius Gen3 (XW30 / ZVW30)**.

* **Firmware** — ESP32-C3 + SN65HVD230, ESPHome (`prius_can_full_v2.yaml` +
  `prius_parse.h`). Polls four diagnostic ECUs over ISO-TP + passive broadcast
  frames; emits one JSON line per ~0.5 s on USB serial.
* **Android app** (`app/`) — Kotlin + Compose head-unit app: live dashboard,
  a draggable always-on status overlay (the head-unit ROM hides third-party
  notification icons), audible/visual warnings, a top-down door-open overlay
  built from pre-rendered PNGs (one per door combination), Home Assistant MQTT
  push with offline buffer.
* **`render/`** — Blender script (`render_combos.py`) + source model
  (`prius.glb`) that bakes the 32 door-state PNGs into `app/src/main/assets`.

It reads ~80 signals — engine, full hybrid drive, complete HV battery
diagnostics (14 block voltages, internal resistance, current, temps), A/C, ABS,
body — using formulas from the canonical PriusChat GenIII Torque CSV.

## Docs
* **`DEVELOPER_GUIDE.md`** — architecture, wiring, every source file, JSON
  schema, calibration status, open tasks. **Start here.**
* **`CLAUDE.md`** — operational brief + hard rules for Claude Code CLI.

## Quick start
Firmware: put `prius_can_full_v2.yaml` and `prius_parse.h` in the same dir, then
`esphome run prius_can_full_v2.yaml`.

App (from repo root): `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

Door overlay PNGs are committed under `app/src/main/assets/car_*.png`; to
re-bake them after a model/pivot change: `blender -b -P render/render_combos.py
-- render/prius.glb app/src/main/assets`.

## Continue with Claude Code
```bash
cd priuscan
claude            # CLAUDE.md is picked up automatically
```

## Hardware
OBD pin 6→CANH, 14→CANL, 5→GND; SN65HVD230 D→GPIO6, R→GPIO5, VCC→3V3, RS→GND.
Remove the breakout's 120 Ω terminator. Powered from the head-unit USB
(OBD pin 16 / +12V not connected). See `DEVELOPER_GUIDE.md` §2.

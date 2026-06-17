# CLAUDE.md — guidance for Claude Code working in this repo

Read `DEVELOPER_GUIDE.md` first for the full architecture. This file is the
quick operational brief.

## What this project is
PriusCAN: ESP32 (ESPHome) CAN/OBD reader for a Toyota Prius Gen3 (XW30) + an
Android head-unit app that displays/logs the data and pushes it to Home
Assistant. Two halves:

* **Firmware**: `prius_can_full_v2.yaml` (ESPHome) + `prius_parse.h` (pure C++).
  They must stay together; the YAML `includes: - prius_parse.h`.
* **Android app**: `app/` — Kotlin + Jetpack Compose, package `hu.codingo.priuscan`.

## Hard rules / gotchas (don't regress these)
1. **`prius_parse.h` must stay ESPHome-free.** No `id(...)`, no `can0`, no
   `publish_state` in the header. The header only decodes into `prius::V`
   (a `std::map<string,float>`) and builds JSON. The YAML bridges to ESPHome.
2. In YAML lambdas, `id(can0).send_data(...)` uses a **dot**, not `->`
   (`id(can0)` returns the object, not a pointer).
3. Template-sensor lambdas read `prius::V.get("key")` and must `return {}`
   (not 0) when `std::isnan(v)`.
4. **ISO-TP timing**: never issue a new poll while a multi-frame transfer is in
   flight. `next_request(...)` returns false until the transfer completes or the
   120 ms timeout expires. Don't "simplify" this away — it's the fix for the
   7E0 engine-data freeze.
5. **All PID formulas come from the PriusChat GenIII Torque CSV.** Letters
   A,B,C… in the CSV equations map to payload bytes `d[0], d[1], d[2]…` (after
   the mode+PID echo). Don't invent scales/offsets.
6. **Child of the model naming quirk**: in `Prius3DView.kt`, door mask 0x80
   (driver) maps to mesh `Door_fr`, and 0x40 (passenger) to `Door_fl` — the GLB
   names are swapped vs reality. Keep firmware bitmask and app mapping consistent.
7. Production firmware has **no Wi-Fi/OTA/web_server**. They're only temporarily
   enabled for G-sensor calibration (raw0..raw7 on the web UI).

## Build & test
* Firmware: `esphome run prius_can_full_v2.yaml` (USB), `esphome logs ...` to watch.
* App: `cd app && ./gradlew assembleDebug` (from repo root: `./gradlew :app:assembleDebug`).
  Debug build only — self-signed, no minification. APK at
  `app/build/outputs/apk/debug/app-debug.apk`.
* There are no unit tests yet; validation is done against real car logs (JSON
  stream) and CAN dumps. When changing a PID, sanity-check ranges against §6 of
  the guide.

## JSON contract
The firmware emits one JSON object per line at ~2 Hz on USB serial. The
authoritative key list is the `KEYS[]` array in `prius_parse.h::emit_json`. The
app's `CanState`/`Fields` must stay in sync with it, and `HaPusher` MQTT
discovery too. If you add a key in the header, update all three.

## Top open tasks (see DEVELOPER_GUIDE §6 for detail)
1. Fix G-sensor byte mapping (`case 0x47` in `prius_parse.h`) using raw0..raw7.
2. Separate rear-door L/R bits (targeted dump).
3. Confirm fuel/rpm/spd live after the ISO-TP fix (new drive log).
4. Decide InfluxDB location for the HA history chain.
5. Implement DTC read-out (mode 03/07) + P-code decoding.

## Style
* Comments in the codebase are currently a mix of Hungarian and English; the
  user is a Hungarian-speaking senior software architect. Match the file you're
  editing. New developer-facing docs in English.
* Prefer targeted edits over full-file rewrites.
* The user is measurement-first: validate against real data, flag uncertain
  calibrations explicitly rather than presenting guesses as facts.

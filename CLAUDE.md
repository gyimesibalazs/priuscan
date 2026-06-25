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
   flight. The YAML serializes polls on `ready_for_next` with a **60 ms** lost-
   response fallback (`(now - last_req_ms) > 60`). The longest transfer (2181 block
   voltages, ~20 ms) completes well under it. Don't "simplify" this away — it's the
   fix for the 7E0 engine-data freeze. (Tight window is intentional; was 120 ms.)
5. **All PID formulas come from the PriusChat GenIII Torque CSV.** Letters
   A,B,C… in the CSV equations map to payload bytes `d[0], d[1], d[2]…` (after
   the mode+PID echo). Don't invent scales/offsets.
6. **Child of the model naming quirk**: in `Prius3DView.kt`, door mask 0x80
   (driver) maps to mesh `Door_fr`, and 0x40 (passenger) to `Door_fl` — the GLB
   names are swapped vs reality. Keep firmware bitmask and app mapping consistent.
7. Production firmware has **no Wi-Fi/OTA/web_server**. They're only temporarily
   enabled for G-sensor calibration (raw0..raw7 on the web UI).
8. **The Android app is multilingual — every user-facing UI string is a string
   resource, translated to all supported locales.** Base (English) lives in
   `app/src/main/res/values/strings.xml`; per-language overrides in
   `values-<lang>/` (currently `hu, de, fr, es, it, pt, nl, pl`). Sensor labels
   and group titles are `@StringRes` ids on `CanState.Field`/`Fields.groups`;
   Compose uses `stringResource(...)`, `CanService`/`OverlayManager` use
   `getString(...)`, `HaPusher` uses `ctx.getString(...)` for HA discovery names.
   **Never hardcode UI text.** When adding/changing a UI string: add the key to
   the English base AND every `values-<lang>/strings.xml` (same key set + same
   `printf` format specifiers in all of them — the build/runtime depends on it).
   Exceptions that are intentionally NOT localized: brand strings (`"Prius CAN"`,
   `priuscan`), gear letters (`P/R/N/D/B`), and bare units rendered next to
   numbers.

## Build & test
* Firmware: `esphome compile prius_can_full_v2.yaml` (the user OTAs; assistant does
  compile + bundle the `.bin` into `app/src/main/assets/firmware.bin`, never flashes).
  Host-test pure logic by `g++ -std=gnu++17 -I. <test>.cpp` including `prius_parse.h`.
* App: `./gradlew :app:assembleDebug`. **From WSL the build fails SILENTLY** because
  `local.properties` holds the user's Windows SDK path — temporarily swap it to the
  Linux SDK (`sdk.dir=/home/gazdi/Android/Sdk`), build, restore. Otherwise `adb install`
  re-installs the STALE old APK. Verify the APK timestamp is fresh.
* Tooling: `tools/install-app.sh` (build with the Linux SDK + install over network adb),
  `tools/pull-logs.sh` (download new logs/dumps to `carlogs/`). `carlogs/` is gitignored.
* No unit tests; validation is against real car logs (JSON) and CAN dumps. When changing
  a PID, sanity-check ranges against §6 of the guide.

## JSON contract
The firmware emits one JSON object per line at ~4 Hz on USB serial. The authoritative
key list is the `KEYS[]` array (order = the `VKey` enum) in `prius_parse.h`; the emitted
subset + formatting is `FIELDS[]` (each prefix must equal the key and end in `":`, and
its `preflen` must equal `strlen(prefix)` — host-tested). `CanState`/`Fields` and `HaPusher`
MQTT discovery must stay in sync; if you add a key, update all three. Trip data rides in a
`slots` array (8 entries: boot, lifetime, tank, oil, A, B, C, home), not flat fields.
`door`/`cruise`/`belt` are added by the YAML, not in `KEYS[]`.

**Host→ESP commands** (`parse_host_line`): `T<unix>` set time, `D0/D1/D2` dump,
`H`/`HO` refuel/oil history, `F<factor>` fuel correction, `R<2..6>` reset slot,
`C<dst><src>` copy a live trip into A/B/C (src `B`=since-boot/`H`=from-home),
`B` emit per-block internal resistance. **On-demand responses** (not in the 4 Hz line):
`{"rhist":…}`, `{"ohist":…}`, `{"rblk":[…14…],"rn":N}`.

**Flash persistence**: trip slots + histories + learned capacity/weak-block in ONE NVS
blob (`prius_persist.h`, namespace `priuscan`, magic `0x5043000x` — bump = old data lost,
so avoid changing `TripSlot`). A SEPARATE NVS namespace `priusday` holds an 8-year daily
health/trip ring (`DailyRec`, one record/day accumulated across drives, written on the
shutdown/quiet detection). NVS partition is ~446 KB.

## Top open tasks (see DEVELOPER_GUIDE §6 for detail)
1. G-sensor calibration: passive cal fails (gFwd/gLat don't track dv/dt or v²·steer); needs
   a controlled test. The `raw0..raw7` debug poll (2147) is currently removed.
2. Separate rear-door L/R bits (targeted dump).
3. Cruise: the SET bit (`0x399 b1.bit1`) is empirically inferred — confirm with a controlled
   on/off test; the set-SPEED value is not on the broadcast (0x1D2/0x1D3 absent).
4. Decide InfluxDB location for the HA history chain (deep battery-health trend lives there).
5. Implement DTC read-out (mode 03/07) + P-code decoding.
6. Verify on-device: the daily flash ring + per-block-R boot seed (only host-tested).

## Style
* Comments in the codebase are currently a mix of Hungarian and English; the
  user is a Hungarian-speaking senior software architect. Match the file you're
  editing. New developer-facing docs in English.
* Prefer targeted edits over full-file rewrites.
* The user is measurement-first: validate against real data, flag uncertain
  calibrations explicitly rather than presenting guesses as facts.

#pragma once
// Prius CAN parse logic — PURE C++, no ESPHome dependency.
// Every formula comes from the PriusChat GenIII Torque CSV (verified).
// Sending on can0 and publishing the sensors is done by the YAML; this header
// only decodes and writes into its own value store (prius::V[]).
//
// Phase 2B: a FIXED-INDEX float array (V[]) instead of the old std::map<string,float>.
// On the hot path (parse/emit/compute) there is no string hashing and no heap allocation;
// emit_json is an integer-based formatter (no software double dtoa) -> the C3 doesn't
// drop consecutive frames because of an ill-timed allocation/dtoa spike.

#include <vector>
#include <map>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>

namespace prius {

// --- Value-store indexes. The [CT .. LOOPS] order is BIT-FOR-BIT the order of
//     the JSON keys (KEYS / FIELDS). B01..B14 is CONTIGUOUS so that V[B01+i] is
//     indexable. RAW0..RAW7 after LOOPS does NOT go into the JSON (only the YAML
//     web UI reads it for G-sensor calibration), hence they come after N_JSON. ---
enum VKey {
  CT, RPM, SPD, SOC, LOAD, MAF, MAP, IAT, THR, PEDAL, VBAT, AMB, RUN, FUEL,
  MG1T, MG1R, MG1Q, MG2T, MG2R, MG2Q, INV1, INV2, BTU, BTL, VL, VH, INVCT, INVWP, ACW,
  HVA, HVDIS, HVCHG, BMIN, BMAX, BLKD, WEAKB, MAXR, THOT, HVAIR, TB1, TB2, TB3,
  B01, B02, B03, B04, B05, B06, B07, B08, B09, B10, B11, B12, B13, B14,
  CABIN, SETT, COMP, EVAP, SOLAR,
  WFL, WFR, WRL, WRR, WDIF, GLAT, GFWD, STEER, BRKP,
  ENGNM, INJML, INJUS, BATTFAN, ECUMODE, FANMODE, DTCCUR, DTCHIST,
  ACAMB, BLOWER, ACPRESS, BODYV, FUELIN, OILDIST,
  CTR, WPRUN, WPW, CELLW,
  ELEN, EFF, EOK, LOOPS,
  GEAR, TURN, SETSPD,           // from passive broadcast frames (0x127/0x614/0x1D3)
  CWL, WBLK, WZ,                // self-learning weak-block layer: level / block index / EWMA z
  TDIST, TFUEL, TAVG,           // since start: distance (km) / fuel (l) / average (l/100km)
  CAPAH, CAPKWH,                // self-learned pack capacity: Ah / kWh (coulomb counting)
  TMOVE, TSPD,                  // since boot: active moving time (s) / avg speed (km/h)
  TEV,                          // since boot: pure-EV distance (km, engine off + moving)
  ODO,                          // odometer (km), from 0x611 broadcast bytes[5..7]
  GASB,                         // gas pedal %, from 0x245 b2 (0..200 -> /2 = 0..100)
  LIGHTS,                       // exterior lights bitmask, 0x622 b3 (0x10 pos,0x20 low,0x40 high,0x08 fFog,0x04 rFog)
  AMBL,                         // raw ambient light level, 0x620 b2:b3 BE (~31 bright .. ~600 dark, inverted)
  DMODE,                        // drive mode, 0x49B b4: 0=normal, 1=ECO (0x08), 2=PWR (0x04)
  EVMODE,                       // EV mode on/off, 0x49B b4 bit1 (0x02)
  N_JSON,                       // number of fields that go into the JSON (length of FIELDS)
  RAW0 = N_JSON, RAW1, RAW2, RAW3, RAW4, RAW5, RAW6, RAW7,
  V_COUNT
};

// The single value store. Static init: every field is NAN ("no data yet" -> JSON
// null). The inline lambda runs once at program startup (ESPHome-free).
inline float V[V_COUNT];
inline bool V_inited = []{ for (int i = 0; i < V_COUNT; i++) V[i] = NAN; return true; }();

// Key names for the string getter and the contract check. The [0, N_JSON) part
// is BIT-FOR-BIT the old emit_json KEYS[] array; at the end the non-JSON raw0..raw7.
inline const char *KEYS[V_COUNT] = {
  "ct","rpm","spd","soc","load","maf","map","iat","thr","pedal","vbat","amb","run","fuel",
  "mg1t","mg1r","mg1q","mg2t","mg2r","mg2q","inv1","inv2","btu","btl","vl","vh","invct","invwp","acw",
  "hvA","hvdis","hvchg","bmin","bmax","blkD","weakB","maxR","tHot","hvAir","tb1","tb2","tb3",
  "b01","b02","b03","b04","b05","b06","b07","b08","b09","b10","b11","b12","b13","b14",
  "cabin","setT","comp","evap","solar",
  "wFL","wFR","wRL","wRR","wDif","gLat","gFwd","steer","brkP",
  "engNm","injml","injus","battFan","ecuMode","fanMode","dtcCur","dtcHist",
  "acAmb","blower","acPress","bodyV","fuelIn","oilDist",
  "ctR","wpRun","wpW","cellW",
  "eLen","eFF","eOK","loops",
  "gear","turn","setSpd",
  "cwL","wblk","wz",
  "tDist","tFuel","tAvg",
  "capAh","capKwh",
  "tMove","tSpd","tEv",
  "odo","gasB","lights","ambL","dmode","ev",
  "raw0","raw1","raw2","raw3","raw4","raw5","raw6","raw7",
};

// Thin string getter ONLY for the YAML template lambdas (cold path, not the parse).
// The hot path reads/writes V[VKey] directly. Unknown key -> NAN.
inline float get(const char *k) {
  for (int i = 0; i < V_COUNT; i++)
    if (std::strcmp(KEYS[i], k) == 0) return V[i];
  return NAN;
}

// ===== Non-blocking serial output (shared by emit_json and the CAN dump) =====
// stdout (USB Serial/JTAG) is set non-blocking so a slow/backed-up host can never
// stall the main loop. A bounded residual buffer preserves byte order; overflow is
// counted in out_dropped instead of blocking. ESPHome-free (POSIX write).
inline bool     out_init = false;
inline char     out_res[512]; inline int out_res_n = 0;   // bounded residual
inline uint32_t out_dropped = 0;                          // bytes dropped on FIFO-full
inline void out_write(const char *p, int len) {
  if (!out_init) { int fl = fcntl(1, F_GETFL, 0); fcntl(1, F_SETFL, fl | O_NONBLOCK); out_init = true; }
  if (out_res_n) {                                  // drain the residual first (order!)
    int w = write(1, out_res, out_res_n);
    if (w > 0) { if (w < out_res_n) memmove(out_res, out_res + w, out_res_n - w); out_res_n -= w; }
  }
  if (out_res_n) {                                  // still backed up -> stash if it fits
    if (out_res_n + len <= (int)sizeof(out_res)) { memcpy(out_res + out_res_n, p, len); out_res_n += len; }
    else out_dropped++;
    return;
  }
  int w = write(1, p, len); if (w < 0) w = 0;
  if (w < len) { int rem = len - w;                 // partial -> stash the remainder
    if (rem <= (int)sizeof(out_res)) { memcpy(out_res, p + w, rem); out_res_n = rem; } else out_dropped++; }
}

// ===== Firmware version + serial OTA request =====
// The app bundles a versioned firmware image and offers an update when FW_VERSION
// is older than the bundled one. "O<size>\n" over serial starts a serial OTA: the
// running firmware writes the streamed image to the inactive OTA partition via the
// IDF esp_ota API (preserves NVS), then reboots. The OTA loop runs in the YAML.
inline constexpr int FW_VERSION = 317;   // 3.17: per-block R (trigger window) + daily flash health/trip ring
inline bool ota_request = false;         // set by "O" command, consumed by YAML
inline uint32_t ota_size = 0;            // image size to receive

// ===== App-controlled CAN dump (filtered + deduped) =====
// The app toggles this over serial (D1/D0). Frames in [dump_lo, dump_hi] are emitted
// as "#XXX [len] BB BB..\n", deduped (only on change) to keep the rate low.
inline int      dump_cmd = -1;                 // -1 none, 0/1 requested state (consumed by YAML)
inline uint16_t dump_lo = 0x500, dump_hi = 0x6FF;
inline bool     dump_unknown = false;          // D2: dump every frame we DON'T already decode
inline std::map<uint16_t, std::vector<uint8_t>> dump_last;   // dedup state
inline void dump_clear() { dump_last.clear(); }

// IDs we already decode (broadcasts + diag req/resp + our own tx) -> skipped in D2 mode
// so a discovery capture surfaces only the still-unknown frames.
inline bool dump_is_known(uint16_t id) {
  switch (id) {
    case 0x1C4: case 0x0B4: case 0x127: case 0x614: case 0x1D3: case 0x5C8:
    case 0x5A4: case 0x610: case 0x611: case 0x612: case 0x620: case 0x626:
    case 0x4A1: case 0x320: case 0x245: case 0x025: case 0x622: case 0x0AA:  // decoded broadcasts
    case 0x7DF: case 0x7E0: case 0x7E2: case 0x7C4: case 0x7B0: case 0x7C0:  // our requests
    case 0x7E8: case 0x7EA: case 0x7CC: case 0x7B8: case 0x7C8:              // diag responses
      return true;
    default: return false;
  }
}
inline void dump_frame(uint16_t id, const uint8_t *d, int n) {
  if (dump_unknown) { if (dump_is_known(id)) return; }       // D2: only unknown frames
  else if (id < dump_lo || id > dump_hi) return;             // D1: ID range filter
  auto it = dump_last.find(id);                              // dedup: only on change
  if (it != dump_last.end() && (int)it->second.size() == n && !memcmp(it->second.data(), d, n)) return;
  dump_last[id].assign(d, d + n);
  char line[80]; int off = snprintf(line, sizeof(line), "#%03X [%d]", (unsigned)id, n);
  for (int i = 0; i < n; i++) off += snprintf(line + off, sizeof(line) - off, " %02X", d[i]);
  line[off++] = '\n'; out_write(line, off);
}

// ===== Refuel detection + last-refuel timestamp (persisted) =====
// fuelIn (0x5A4 broadcast, ~0..40 scale) jumps up on refuel. We track a slow
// baseline and, when parked, a jump >= REFUEL_DELTA marks a refuel -> record the
// wall-clock epoch. The epoch persists via an ESPHome global (uint32). Declared
// before the host-time block because parse_host_line back-corrects these (#4).
inline float fuel_ref = -1;             // slow-tracked fuel-level baseline
inline uint32_t last_refuel_epoch = 0;  // unix seconds of the last detected refuel
inline float fuel_corr = 1.0f;          // user fuel-consumption correction (calibrate to the pump);
                                        // app sets it via "F<value>", persisted as an ESPHome global

// ===== Power-off (ignition) detection + NVS persistence =====
// TEST stage: on ignition-off we save only the shutdown epoch + a save counter, so the
// Android side can validate persistence (offTs ~= when it was switched off, offN grows
// every power-cycle). Detector = bus-silence: the fast powertrain frame (0x1C4) stops
// within ~1 s of ignition-off. Real learned data will be added once persistence is proven.
inline uint32_t last_pwr_ms = 0;        // millis() of the last 0x1C4 (fast powertrain) frame
inline bool     persist_done = false;   // one save per power-down (re-armed when bus is alive)
inline uint32_t boot_off_epoch = 0;     // restored last-shutdown epoch  -> JSON "offTs"
inline uint32_t boot_off_n = 0;         // restored save counter         -> JSON "offN"
inline bool     rblk_request = false;   // app sent "B"  -> emit per-block resistance (YAML consumes)
inline bool     hist_request = false;   // app sent "H"  -> emit refuel history (YAML consumes)
inline bool     ohist_request = false;  // app sent "HO" -> emit oil-change history
inline int      reset_req = -1;         // app sent "R<i>" -> reset slot i (YAML consumes)
inline int      copy_dst  = -1;         // app sent "C<dst><src>" -> copy a live trip into slot dst (YAML consumes)
inline char     copy_src  = 0;          // 'B' = since-boot, 'H' = from-home
inline bool     persist_loaded = false; // true after the boot NVS read -> only THEN may we write
inline uint32_t last_refuel_ms = 0;     // millis() at refuel (for pending correction)
inline uint32_t refuel_seen_ms = 0;     // when fin first crossed the up-jump (confirm window)
inline bool refuel_pending = false;     // refuel seen BEFORE host time was known -> correct later
inline bool refuel_dirty = false;       // YAML flushes this to the persistent global
inline constexpr float REFUEL_DELTA = 12.0f;   // fuelIn is now % (0..100): a significant up-jump

// ===== Host wall-clock time (pushed from the head unit over serial) =====
// The production firmware has no Wi-Fi/NTP, so the head unit is the only time
// source. The app periodically writes "T<unix_seconds>\n"; we read it (non-blocking
// stdin = USB Serial/JTAG) and keep an epoch base. ESPHome-free (POSIX read; the
// YAML passes in millis()). A unix epoch does NOT fit a float, so it is emitted as
// an integer by emit_json, never stored in the float V[].
inline uint32_t host_epoch = 0, host_epoch_ms = 0;
inline bool host_time_set = false;
inline uint32_t cur_epoch(uint32_t now_ms) {
  return host_time_set ? host_epoch + (now_ms - host_epoch_ms) / 1000u : 0u;
}
inline void parse_host_line(const char *line, uint32_t now_ms) {
  if (line[0] == 'O') {                        // serial OTA: "O<size>" -> stream the image
    unsigned sz = 0;
    if (sscanf(line + 1, " %u", &sz) == 1 && sz > 1000) { ota_size = sz; ota_request = true; }
    return;
  }
  if (line[0] == 'D') {                        // CAN dump: D0 off, D1[lo hi] range, D2 unknown-only
    if (line[1] == '0') { dump_cmd = 0; }
    else if (line[1] == '2') { dump_cmd = 1; dump_unknown = true; }
    else {
      dump_cmd = 1; dump_unknown = false; unsigned lo, hi;
      if (sscanf(line + 2, " %x %x", &lo, &hi) == 2) { dump_lo = (uint16_t)lo; dump_hi = (uint16_t)hi; }
      else { dump_lo = 0x500; dump_hi = 0x6FF; }
    }
    return;
  }
  if (line[0] == 'B') { rblk_request = true; return; }   // emit per-block internal resistance
  if (line[0] == 'H') { if (line[1] == 'O') ohist_request = true; else hist_request = true; return; }  // H/HO history
  if (line[0] == 'F') {                                   // fuel correction: "F<factor>" (e.g. F1.05)
    float f; if (sscanf(line + 1, " %f", &f) == 1 && f > 0.5f && f < 2.0f) fuel_corr = f;  // YAML flushes it to NVS
    return;
  }
  if (line[0] == 'R') {                                   // reset distance slot: "R<1..4>"
    int i = line[1] - '0';
    if (i >= 2 && i <= 6) reset_req = i;
    return;
  }
  if (line[0] == 'C') {                                   // copy a live trip INTO A/B/C: "C<dst><src>"
    int dst = line[1] - '0';                               // dst: 3=A 4=B 5=C
    if (dst >= 3 && dst <= 5 && (line[2] == 'B' || line[2] == 'H')) { copy_dst = dst; copy_src = line[2]; }
    return;                                                 // deferred -> YAML calls copy_slot (slot[] declared later)
  }
  if (line[0] != 'T') return;
  uint32_t e = 0;
  for (const char *p = line + 1; *p >= '0' && *p <= '9'; ++p) e = e * 10u + (uint32_t)(*p - '0');
  if (e > 1000000000u) {
    host_epoch = e; host_epoch_ms = now_ms; host_time_set = true;
    // a refuel detected before we had real time -> back-correct its epoch now
    if (refuel_pending) {
      last_refuel_epoch = host_epoch - (host_epoch_ms - last_refuel_ms) / 1000u;
      refuel_pending = false; refuel_dirty = true;
    }
  }
}
inline void read_host_serial(uint32_t now_ms) {
  static bool init = false; static char buf[40]; static int n = 0;
  if (!init) { int fl = fcntl(0, F_GETFL, 0); fcntl(0, F_SETFL, fl | O_NONBLOCK); init = true; }
  char c;
  while (read(0, &c, 1) == 1) {
    if (c == '\n' || c == '\r') { buf[n] = 0; if (n) parse_host_line(buf, now_ms); n = 0; }
    else if (n < (int)sizeof(buf) - 1) buf[n++] = c;
  }
}


static inline float u16(const uint8_t *d, int i) { return (float)((d[i] << 8) | d[i + 1]); }

// ISO-TP reassembly buffer in a FIXED array (no heap realloc on the hot RX path).
// 64 is plenty: the longest response, the 2181 block voltages, is ~30 bytes.
inline uint8_t tp_buf[64];
inline int  tp_len = 0;       // the expected total length (from the FF)
inline int  tp_got = 0;       // bytes collected so far
inline bool tp_active = false;
inline uint16_t tp_src = 0;
inline uint32_t tp_started_ms = 0;

// Event-driven polling: only ONE request can be on the bus at a time. The header
// signals when a new request may be sent; the actual send_data is in the YAML.
//   ready_for_next = true  -> the previous response was fully processed (single-
//                             frame parse, or the last consecutive frame ->
//                             tp_active false), the next request can go out.
//   last_req_ms            -> the time the last request was sent out (the YAML
//                             passes in millis()); the YAML computes a fallback
//                             from it for a lost response (restarts after ~60 ms).
// This drives the polling cadence instead of the old fixed timer + tp_active/awaiting
// 120 ms blocking (see next_request / on_can_frame). The ISO-TP reassembly
// (first/consecutive/flow control) is unchanged; only WHEN the next request starts
// has changed.
inline bool ready_for_next = true;   // whether a new request may be sent
inline uint32_t last_req_ms = 0;     // time of the last request (YAML millis())
inline uint16_t await_resp = 0;      // the CAN ID of the expected response (= request + 8)

// DEBUG counters for diagnosing the engine ECU (0x7E8):
//   eng_ff = how many first frames (FF) came from the engine ECU
//   eng_ok = how many 2101 responses completed successfully
// If eng_ff >> eng_ok -> the CF frames are being lost (RX drop/timing), not the session.
inline uint32_t eng_ff = 0, eng_ok = 0;

// divider = out of how many "logical" cycles this PID should run once. The cycle
// counter (poll_cycle) increments whenever the list wraps around. next_request skips
// those for which (poll_cycle % divider) != 0 -> the fast (driving) PIDs come
// frequently, the slowly changing ones rarely. Classification: see the divider field.
struct PollItem { uint16_t req; uint8_t mode; uint8_t pid; uint8_t divider; };
inline const PollItem POLL[] = {
  // --- divider=1: every cycle (fast-changing, driving dynamics) ---
  {0x7E0, 0x21, 0x01, 1},                            // engine: ct,rpm,spd,maf
  {0x7E2, 0x21, 0x01, 1},                            // hybrid 2101
  {0x7E2, 0x21, 0x98, 1},                            // HV current/dis/chg
  // --- divider=3: every 3rd cycle (temperatures, MG/inverter, A/C) ---
  {0x7E2, 0x01, 0x5B, 3},                            // std SoC (slow change)
  {0x7E2, 0x21, 0x92, 3}, {0x7E2, 0x21, 0x81, 3},
  {0x7E2, 0x21, 0x87, 3}, {0x7E2, 0x21, 0x95, 3},
  {0x7E2, 0x21, 0x61, 3}, {0x7E2, 0x21, 0x62, 3},
  {0x7E2, 0x21, 0x67, 3}, {0x7E2, 0x21, 0x68, 3},
  {0x7E2, 0x21, 0x70, 3}, {0x7E2, 0x21, 0x71, 3},
  {0x7E2, 0x21, 0x74, 3}, {0x7E2, 0x21, 0x75, 3}, {0x7E2, 0x21, 0x7D, 3},
  {0x7C4, 0x21, 0x21, 3}, {0x7C4, 0x21, 0x29, 3}, {0x7C4, 0x21, 0x49, 3},
  {0x7C4, 0x21, 0x4B, 3}, {0x7C4, 0x21, 0x24, 3},
  {0x7C4, 0x21, 0x3C, 3}, {0x7C4, 0x21, 0x53, 3},    // A/C
  {0x7B0, 0x21, 0x07, 3},                            // brake pedal pressure (2107)
  // --- divider=10: every 10th cycle (slow/infrequent diagnostics, body) ---
  {0x7E0, 0x21, 0x49, 10}, {0x7E0, 0x21, 0x3C, 10},  // engine torque, injector
  {0x7E2, 0x21, 0x8E, 10}, {0x7E2, 0x21, 0x9B, 10},  // cooling fan
  {0x7E2, 0x21, 0xE1, 10}, {0x7E2, 0x21, 0xC1, 10},  // DTC count, model code
  {0x7C0, 0x21, 0x13, 10}, {0x7C0, 0x21, 0x41, 10},  // body
};
inline const int POLL_N = sizeof(POLL) / sizeof(POLL[0]);
inline int poll_i = 0;
inline uint32_t poll_cycle = 0;      // how many full passes the list made (JSON: loops)

// The self-learning layer signals that a new 2181 block set has arrived (used below).
inline bool blocks_fresh = false;

// p/n: raw payload pointer+length (NO std::vector -> no per-frame alloc).
inline void parse_payload(uint16_t resp_id, const uint8_t *p, int n) {
  if (n < 2) return;
  uint8_t mode = p[0], pid = p[1];
  const uint8_t *d = p + 2;
  int dn = n - 2;

  if (resp_id == 0x7EA && mode == 0x41 && pid == 0x5B) {
    if (dn >= 1) V[SOC] = d[0] * 20.0f / 51.0f;
    return;
  }
  if (mode != 0x61) return;

  if (resp_id == 0x7E8 && pid == 0x01) {
    eng_ok++;                 // DEBUG: how many 2101 completed successfully
    V[ELEN] = (float)dn;      // DEBUG: the length of the last response
    if (dn < 14) return;
    V[LOAD] = d[0] * 20.0f / 51.0f;
    V[MAF] = u16(d, 3) / 100.0f;
    V[MAP] = (float)d[5];
    V[IAT] = (float)d[6] - 40.0f;
    V[CT] = (float)d[8] - 40.0f;
    V[RPM] = u16(d, 9) / 4.0f;
    V[SPD] = (float)d[11];
    V[RUN] = u16(d, 12);
    return;
  }
  if (resp_id == 0x7E8 && pid == 0x49) {   // actual engine torque
    if (dn >= 5) V[ENGNM] = u16(d,3) - 32768.0f;
    return;
  }
  if (resp_id == 0x7E8 && pid == 0x3C) {   // injector: ml (10x) + duration us
    if (dn >= 4) {
      V[INJML] = u16(d,0) * 2.047f / 65535.0f;
      V[INJUS] = u16(d,2);
    }
    return;
  }

  if (resp_id == 0x7EA) {
    switch (pid) {
      case 0x01:
        if (dn < 21) return;
        V[AMB] = (float)d[3] - 40.0f;
        V[THR] = d[11] * 20.0f / 51.0f;
        V[PEDAL] = d[12] * 20.0f / 51.0f;
        V[VBAT] = u16(d, 19) / 1000.0f;
        return;
      case 0x61: if (dn>=5){ V[MG1T]=(float)d[0]-40; V[MG1R]=u16(d,3)-32768;} return;
      case 0x62: if (dn>=5){ V[MG2T]=(float)d[0]-40; V[MG2R]=u16(d,3)-32768;} return;
      case 0x67: if (dn>=2) V[MG1Q] = u16(d,0)/8.0f-4096; return;
      case 0x68: if (dn>=2) V[MG2Q] = u16(d,0)/8.0f-4096; return;
      case 0x70: if (dn>=1) V[INV1]=(float)d[0]-40; return;
      case 0x71: if (dn>=1) V[INV2]=(float)d[0]-40; return;
      case 0x74:
        if (dn < 9) return;
        V[BTU]=(float)d[0]-40; V[BTL]=(float)d[1]-40;
        V[VL] = u16(d,5)/2.0f; V[VH] = u16(d,7)/2.0f;
        return;
      case 0x75:
        if (dn < 4) return;
        V[WPRUN] = (d[0] & 0x10) ? 1 : 0;
        V[INVWP] = u16(d,1); V[INVCT]=(float)d[3]-40;
        return;
      case 0x7D: if (dn>=3) V[ACW] = d[2]*50.0f; return;
      case 0x81:
        if (dn < 28) return;
        for (int i = 0; i < 14; i++)
          V[B01 + i] = u16(d, 2*i) * 79.99f / 65535.0f;
        blocks_fresh = true;   // new block set -> the self-learning layer may update
        return;
      case 0x87:
        if (dn < 8) return;
        V[HVAIR] = u16(d,0)*255.9f/65535.0f-50;
        V[TB1] = u16(d,2)*255.9f/65535.0f-50;
        V[TB2] = u16(d,4)*255.9f/65535.0f-50;
        V[TB3] = u16(d,6)*255.9f/65535.0f-50;
        return;
      case 0x92:
        if (dn < 15) return;
        V[BMIN] = u16(d,0)*79.99f/65535.0f;
        V[BMAX] = u16(d,3)*79.99f/65535.0f;
        V[BLKD] = ((float)((d[3]-d[0])*256 + d[4]-d[1]))*79.99f/65535.0f;
        V[WEAKB] = (float)d[2];
        V[THOT] = u16(d,13);
        return;
      case 0x95: {
        if (dn < 14) return;
        uint8_t mx = 0;
        for (int i=0;i<14;i++) if (d[i]>mx) mx=d[i];
        V[MAXR] = (float)mx;
        return;
      }
      case 0x98:
        if (dn < 8) return;
        V[HVA] = u16(d,0)/100.0f-327.68f;
        V[HVDIS] = d[2]/2.0f-64;
        V[HVCHG] = d[3]/2.0f-64;
        return;
      case 0x8E:   // battery cooling fan %
        if (dn >= 1) V[BATTFAN] = d[0]/2.0f;
        return;
      case 0x9B:   // ECU control mode + cooling fan mode
        if (dn >= 2) { V[ECUMODE] = d[0]; V[FANMODE] = d[1]; }
        return;
      case 0xE1:   // number of DTCs
        if (dn >= 2) { V[DTCCUR] = d[0]; V[DTCHIST] = d[1]; }
        return;
    }
    return;
  }

  if (resp_id == 0x7CC) {
    switch (pid) {
      case 0x21: if (dn>=1) V[CABIN] = d[0]*63.75f/255.0f-6.5f; return;
      case 0x24: if (dn>=1) V[SOLAR] = (float)d[0]; return;
      case 0x29: if (dn>=1) V[SETT] = d[0]*15.0f/255.0f+17.5f; return;
      case 0x49: if (dn>=2) V[COMP] = u16(d,0); return;
      case 0x4B: if (dn>=1) V[EVAP] = d[0]*89.25f/255.0f-29.7f; return;
      case 0x22: if (dn>=1) V[ACAMB] = d[0]*89.25f/255.0f-23.3f; return;
      case 0x3C: if (dn>=1) V[BLOWER] = d[0]*31.0f/255.0f; return;
      case 0x53: if (dn>=1) V[ACPRESS] = d[0]*3.75105f/255.0f-0.45668f; return;
    }
    return;
  }

  if (resp_id == 0x7B8) {
    switch (pid) {
      case 0x47:
        if (dn < 5) return;
        // OLD G/steer source (saturated, replaced): lateral=0x4A1, longitudinal=0x320 b4,
        // steering=0x025 broadcast. Keep only the raw0..raw7 debug dump here.
        for (int i=0; i<8 && i<dn; i++)
          V[RAW0 + i] = (float)d[i];
        return;
      case 0x07: if (dn>=1) V[BRKP] = d[0]/51.0f; return;
    }
    return;
  }

  if (resp_id == 0x7C8) {   // body ECU
    switch (pid) {
      case 0x13: if (dn>=1) V[BODYV] = d[0]/10.0f; return;
      case 0x41: if (dn>=1) V[OILDIST] = d[0]*2514600.0f/15625.0f; return;
    }
    return;
  }
}

// Decoding of passive broadcast frames (no request, high rate). Only writes to V
// when the frame arrives; if the given ID is not on our bus, the value stays NAN
// (JSON null) -> the addition is non-destructive. Source of the bit offsets:
// commaai/opendbc "toyota_prius_2010_pt.dbc" (Gen3/XW30). There is no send_data
// here, this is a pure decoder (ESPHome-free).
inline volatile bool can_paused = false;   // set on OTA start: stop all CAN processing
inline void on_broadcast(uint16_t id, const std::vector<uint8_t> &x) {
  if (can_paused) return;                   // OTA in progress -> free the CPU for the flash
  switch (id) {
    case 0x1C4:  // POWERTRAIN: ENGINE_RPM (fast rpm, instead of/alongside the slow 7E0/2101)
      if (x.size() >= 2) V[RPM] = (float)((x[0] << 8) | x[1]);
      return;
    case 0x0B4:  // SPEED: vehicle speed (0.0062 mph -> km/h)
      if (x.size() >= 7) V[SPD] = (float)((x[5] << 8) | x[6]) * 0.0062f * 1.60934f;
      return;
    case 0x127:  // GEAR_PACKET: upper nibble. 0=P 1=R 2=N 3=D 4=B
      if (x.size() >= 6) V[GEAR] = (float)((x[5] >> 4) & 0x0F);
      return;
    case 0x614:  // STEERING_LEVERS: index. 1=left 2=right 3=none
      if (x.size() >= 4) V[TURN] = (float)((x[3] >> 4) & 0x03);
      return;
    case 0x1D3:  // PCM_CRUISE_2: SET_SPEED (kph)
      if (x.size() >= 3) V[SETSPD] = (float)x[2];
      return;
    case 0x4A1:  // VSC: LATERAL acceleration, byte[0..1] signed16 BE (RAW; calibrate -> m/s2)
      if (x.size() >= 2) V[GLAT] = (float)(int16_t)(((uint16_t)x[0] << 8) | x[1]);
      return;
    case 0x320:  // VSC: LONGITUDINAL acceleration, byte[4] (RAW; ~154 = 0g, drops on accel ->
      // store as (center - b4) so positive = forward accel. Calibrate center+scale -> m/s2.
      if (x.size() >= 5) V[GFWD] = 154.0f - (float)x[4];
      return;
    case 0x245:  // gas pedal: byte[2] is 0..200 -> /2 = 0..100 %
      if (x.size() >= 3) V[GASB] = (float)x[2] * 0.5f;
      return;
    case 0x025:  // steering angle: byte[0..1] 12-bit, 2048 = centre, ~0.245 deg/count
      if (x.size() >= 2) V[STEER] = (float)((int)(((x[0] & 0x0F) << 8) | x[1]) - 2048) * 0.245f;
      return;
    case 0x0AA:  // ABS wheel speeds (replaces the 7B0 2103 poll): 4x u16 BE, *0.01 - 67.67 km/h (FR,FL,RR,RL)
      if (x.size() >= 8) {
        V[WFR] = (float)(((uint16_t)x[0]<<8)|x[1]) * 0.01f - 67.67f;
        V[WFL] = (float)(((uint16_t)x[2]<<8)|x[3]) * 0.01f - 67.67f;
        V[WRR] = (float)(((uint16_t)x[4]<<8)|x[5]) * 0.01f - 67.67f;
        V[WRL] = (float)(((uint16_t)x[6]<<8)|x[7]) * 0.01f - 67.67f;
        float fr = V[WFR], fl = V[WFL];
        if (fr + fl > 20.0f) V[WDIF] = 200.0f * fabsf(fr - fl) / (fr + fl);
      }
      return;
    case 0x611:  // Combination meter: odometer in km, bytes[5..7] (24-bit)
      if (x.size() >= 8) V[ODO] = (float)(((uint32_t)x[5] << 16) | ((uint32_t)x[6] << 8) | x[7]);
      return;
    case 0x612:  // Combination meter: fuel level = byte[5] as % (b5/255*100). Calibrated
      // from dumps: 5/10 gauge segments = b5 129 ~= 50% -> linear 0..255 = empty..full.
      // NB: 0xFF is a real "full tank" value, so we DON'T drop it here; the boot-transient
      // false refuel (b5 bouncing 0<->255 for ~1 min) is handled by a warm-up gate in
      // compute_derived instead, so a genuinely full tank still reads 100%.
      if (x.size() >= 6) V[FUELIN] = (float)x[5] * 100.0f / 255.0f;
      return;
    case 0x622:  // exterior lights, byte[3] bitmask: 0x10 position, 0x20 low beam,
      // 0x40 high beam, 0x08 front fog, 0x04 rear fog (decoded from the labelled light test)
      if (x.size() >= 4) V[LIGHTS] = (float)(x[3] & 0x7C);
      return;
    case 0x620:  // body ECU: RAW ambient light level, bytes[2:3] BE, inverted (~31 bright ..
      // ~600 dark). Confirmed with a 3-pulse lamp test. (doors are byte[5], handled in the YAML.)
      if (x.size() >= 4) V[AMBL] = (float)(((uint16_t)x[2] << 8) | x[3]);
      return;
    case 0x49B:  // drive mode, byte[4]: bit1(0x02)=EV, bit3(0x08)=ECO, bit2(0x04)=PWR
      // (confirmed with a 3x EV/ECO/PWR switch test). EV is independent of the ECO/PWR selector.
      if (x.size() >= 5) {
        V[DMODE]  = (float)((x[4] & 0x08) ? 1 : (x[4] & 0x04) ? 2 : 0);   // 0 normal / 1 eco / 2 pwr
        V[EVMODE] = (float)((x[4] & 0x02) ? 1 : 0);
      }
      return;
  }
}

// Return: if >0, the YAML should send flow control to the (returned - 8) ID.
inline uint16_t on_can_frame(uint16_t resp_id, const std::vector<uint8_t> &x, uint32_t now_ms) {
  if (can_paused || x.empty()) return 0;    // OTA in progress -> stop ISO-TP/diag processing
  uint8_t pci = x[0] >> 4;
  if (pci == 0x0) {
    uint8_t len = x[0] & 0x0F;
    if (len == 0 || (int)x.size() < 1+len) return 0;
    parse_payload(resp_id, x.data()+1, len);   // directly from the frame, no copy
    // single-frame response (even a negative 7F): the request is closed -> the next can come
    if (resp_id == await_resp) ready_for_next = true;
    return 0;
  } else if (pci == 0x1) {
    if (resp_id == 0x7E8) eng_ff++;   // DEBUG: engine-ECU first frame
    tp_len = ((x[0] & 0x0F) << 8) | x[1];
    if (tp_len > (int)sizeof(tp_buf)) tp_len = (int)sizeof(tp_buf);  // overflow protection
    tp_got = 0;
    for (size_t i = 2; i < x.size() && tp_got < (int)sizeof(tp_buf); i++)
      tp_buf[tp_got++] = x[i];
    tp_active = true; tp_src = resp_id;
    tp_started_ms = now_ms;
    return resp_id;
  } else if (pci == 0x2 && tp_active && tp_src == resp_id) {
    for (size_t i = 1; i < x.size() && tp_got < (int)sizeof(tp_buf); i++)
      tp_buf[tp_got++] = x[i];
    if (tp_got >= tp_len) {
      tp_active = false;
      parse_payload(resp_id, tp_buf, tp_len);
      // multi-frame response complete (after the last CF): the next can come
      if (resp_id == await_resp) ready_for_next = true;
    }
  }
  return 0;
}

// Selecting the next due PID and assembling the request. The scheduling
// (WHEN we call it) is decided in the YAML based on ready_for_next / last_req_ms; here
// we ONLY select WHAT we send: the next PID in line for which
// (poll_cycle % divider) == 0. Non-due PIDs are skipped (we don't waste
// a tick on them). The divider=1 items are always due, so the loop
// always finds something to send within at most POLL_N steps.
// Return: true if there is a new request (out filled in).
inline bool next_request(uint16_t &req_id, std::vector<uint8_t> &out, uint32_t now_ms) {
  // on the fallback path (lost response) an active ISO-TP state may have remained -> clear it
  tp_active = false;
  for (int tries = 0; tries < POLL_N; tries++) {
    const PollItem &it = POLL[poll_i];
    uint32_t cyc = poll_cycle;
    poll_i++;
    if (poll_i >= POLL_N) { poll_i = 0; poll_cycle++; }   // the list wrapped around
    if ((cyc % it.divider) != 0) continue;                // not due -> skip
    req_id = it.req;
    out = {0x02, it.mode, it.pid, 0, 0, 0, 0, 0};
    await_resp = req_id + 8;     // OBD: the response ID = request ID + 8
    ready_for_next = false;      // request goes out -> we don't send another until the response
    last_req_ms = now_ms;        // for the fallback calculation in the YAML
    return true;
  }
  return false;
}

inline float ct_hist[13]; inline int ct_n = 0;
inline float dload = 0, drest = 0;

// ===================== SELF-LEARNING WEAK-BLOCK LAYER =====================
// Goal 1 (early indication of a weak block / asymmetry) WITHOUT OFFLINE FITTING.
// Method (from the research): per-block Z-score, learned with a per-block EWMA
// -> an adaptive, data-relative baseline instead of a fixed threshold. Learns ONLY
// under load (|hvA|>LRN_LOAD_A), because at high discharge current the I*R highlights
// the weak block. We use mean/SD (not median/MAD): at n=14, if the blocks are nearly
// identical, the MAD degenerates to 0 (per the research MAD/SD > IQR; the SD here is
// the more stable one for the small sample). The EWMA highlights over time the
// PERSISTENTLY deviant block. The learned state (lz[14]+lz_n) persists via the YAML
// into an ESPHome global.
// IMPORTANT: this is a RELATIVE deviation (this block relative to its own/the pack
// baseline) -> it indicates asymmetry/change, NOT absolute SOH. The thresholds (LRN_K*)
// and alpha are to be tuned from a real car log; until then the value is complemented
// by the fixed cellW.
inline float lz[14] = {0};        // per-block EWMA (robust) z-score
inline uint32_t lz_n = 0;         // number of valid (under-load) learning samples
// (blocks_fresh declared above, since parse_payload already uses it)

inline constexpr float    LRN_ALPHA  = 0.02f; // EWMA rate (~50-sample time constant)
inline constexpr float    LRN_LOAD_A = 30.0f; // we learn above this |hvA| (under load)
inline constexpr uint32_t LRN_NMIN   = 300;   // after this many samples the baseline is "mature"
inline constexpr float    LRN_K1     = 1.6f;  // warning threshold (|z|)
inline constexpr float    LRN_K2     = 2.4f;  // fault threshold (|z|)

// Updates the learned baseline on a new block set (only under load).
inline void learn_update() {
  for (int i=0;i<14;i++) if (std::isnan(V[B01+i])) return;   // only a complete set
  float amps = V[HVA];
  if (std::isnan(amps) || fabsf(amps) <= LRN_LOAD_A) return; // only under load
  float sum = 0; for (int i=0;i<14;i++) sum += V[B01+i];
  float mu = sum/14.0f;
  float var = 0; for (int i=0;i<14;i++){ float dv=V[B01+i]-mu; var += dv*dv; }
  var /= 14.0f;
  float sd = sqrtf(var);
  if (sd < 1e-4f) return;                     // degenerate (all equal)
  for (int i=0;i<14;i++) {
    float z = (V[B01+i]-mu)/sd;               // z-score relative to the pack average
    lz[i] = (1.0f-LRN_ALPHA)*lz[i] + LRN_ALPHA*z;
  }
  if (lz_n < 0x7FFFFFFF) lz_n++;
}

// Evaluates the learned baseline: fills WBLK/WZ/CWL, returns the level.
inline int learn_verdict() {
  int worst = -1; float wzv = 0.0f;
  for (int i=0;i<14;i++) if (lz[i] < wzv) { wzv = lz[i]; worst = i; }
  V[WBLK] = (worst >= 0) ? (float)(worst+1) : NAN;
  V[WZ]   = (worst >= 0) ? wzv : NAN;
  if (lz_n < LRN_NMIN) { V[CWL] = NAN; return 0; }   // not yet mature -> no verdict
  int lvl = 0;
  if (-wzv >= LRN_K2) lvl = 2; else if (-wzv >= LRN_K1) lvl = 1;
  V[CWL] = (float)lvl;
  return lvl;
}

// Persistence toward the YAML (ESPHome global, restore_value). 15 floats: lz[14]+lz_n.
inline void learn_export(float *o) { for (int i=0;i<14;i++) o[i]=lz[i]; o[14]=(float)lz_n; }
inline void learn_import(const float *in) {
  for (int i=0;i<14;i++) lz[i]=in[i];
  lz_n = (uint32_t)in[14];
}

// Trip integrators (since boot/start). The YAML passes millis() so the header
// stays ESPHome-free; dt comes from the real timestamp (robust to interval jitter).
inline uint32_t derive_last_ms = 0;
inline bool derive_init = false;
inline float trap_sp = NAN;   // previous speed/fuel-rate for trapezoidal integration
inline float trap_fl = NAN;
// current stop duration (s), shared by all slots (the "stops <=180 s still count as moving"
// bridge for traffic lights). Starts HIGH so a just-started, still-standing car does NOT count
// moving time until it actually moves once (then a move resets it to 0 and the bridge applies).
inline float stop_s = 1000.0f;

// ===== Unified trip slots (firmware-owned). ONE record type for every counter. =====
struct TripSlot {
  uint32_t epoch;   // start time (unix s)
  uint32_t odo;     // odometer (km) at start  -> absolute reference
  float dist;       // km
  float ev;         // pure-EV km
  float fuel;       // l
  float move_s;     // active moving seconds (stops <=180 s count)
  float regen_e;    // J recovered into the pack while braking
  float brake_e;    // J of kinetic energy shed while braking (ratio = regen_e/brake_e)
};
inline TripSlot boot_slot = {};        // SINCE BOOT — memory only, NOT persisted
inline TripSlot slot[7] = {};          // PERSISTED: [0]=lifetime(never reset) [1]=tank
                                       // [2]=oil-service [3..5]=user [6]=home-departure(app auto-reset)
inline constexpr int NSLOT = 7;
inline constexpr int HISTN = 50;       // rotating history depth (refuel + oil-change)
inline TripSlot rhist[HISTN] = {}; inline uint8_t rhist_n = 0, rhist_head = 0;  // refuel history
inline TripSlot ohist[HISTN] = {}; inline uint8_t ohist_n = 0, ohist_head = 0;  // oil-change history
inline bool persist_request = false;   // ask the YAML to flush NVS (refuel / reset)

inline uint32_t cur_odo() { float o = V[ODO]; return std::isnan(o) ? 0u : (uint32_t)o; }
inline void slot_start(TripSlot &s, uint32_t epoch, uint32_t odo) { s = {}; s.epoch = epoch; s.odo = odo; }
inline void slot_add(TripSlot &s, float dd, float de, float df, float dm, float dre, float dbe) {
  s.dist += dd; s.ev += de; s.fuel += df; s.move_s += dm; s.regen_e += dre; s.brake_e += dbe;
}
// reset a slot (2..5): oil-service (2) is pushed into the oil history first; 3..5 are user
// trips. Slot 0 (lifetime) and 1 (tank, reset only on refuel) are not user-resettable here.
inline void reset_slot(int i, uint32_t epoch) {
  if (i < 2 || i > 6) return;        // [2]=oil [3..5]=user [6]=home; [0]/[1] not resettable here
  if (i == 2) { ohist[ohist_head] = slot[2]; ohist_head = (ohist_head + 1) % HISTN; if (ohist_n < HISTN) ohist_n++; }
  slot_start(slot[i], epoch, cur_odo());
  persist_request = true;
}

// Snapshot a live trip INTO a user slot (A/B/C). The destination then keeps accumulating
// from the source's start point (since-boot or from-home), and is persisted.
inline void copy_slot(int dst, char src) {
  if (dst < 3 || dst > 5) return;
  if (src == 'B')      slot[dst] = boot_slot;   // since boot (memory-only source)
  else if (src == 'H') slot[dst] = slot[6];     // from home
  else return;
  persist_request = true;
}

// Self-learning pack capacity via coulomb counting between SoC points (persisted).
// capacity[Ah] = integral(I dt) / dSoC; learned by EWMA over many spans. kWh uses
// the measured pack voltage VL (no cell-count assumption). NOTE: relies on the BMS
// SoC and integrates current, so it is an ESTIMATE that improves over drives; NiMH
// charge inefficiency (~5-10%) biases it slightly.
inline float cap_ah = 0;        // learned capacity (Ah), EWMA
inline uint32_t cap_n = 0;      // number of accepted spans
inline float vl_avg = 0;        // EWMA of pack voltage VL (for kWh)
// peak/trough window: the Prius keeps SoC in a narrow oscillating band, so a fixed
// anchor rarely moves 10%. Track the running SoC min/max in a window and the charge
// integral at each -> the peak-to-trough swing gives a usable span. (transient)
inline float cap_smin = NAN, cap_smax = NAN;  // running SoC min/max in the window
inline float cap_q_lo = 0, cap_q_hi = 0;      // charge integral at smin / smax
inline float charge_ah = 0;                   // running charge integral (Ah)

inline constexpr float    CAP_ALPHA    = 0.05f;  // EWMA weight over spans (slow: settles ~+-0.4 Ah)
inline constexpr float    CAP_MIN_DSOC = 15.0f;  // % SoC swing required for a span (bigger = far less noise)
inline constexpr float    CAP_CHG_EFF  = 0.93f;  // NiMH coulombic efficiency: charge spans overstate Ah
inline constexpr float    CAP_MIN_AH   = 2.0f;   // plausibility window (reject glitches)
inline constexpr float    CAP_MAX_AH   = 15.0f;

inline void cap_export(float *o) { o[0]=cap_ah; o[1]=(float)cap_n; o[2]=vl_avg; }
inline void cap_import(const float *in) { cap_ah=in[0]; cap_n=(uint32_t)in[1]; vl_avg=in[2]; }

// Per-block internal resistance from current-step TRIGGER EVENTS. On each fresh block-voltage
// set, if |dI| since the previous set > R_DI, one R_i = -dV_i/dI sample is pushed into a per-block
// rolling window; the published value is the window MEDIAN (robust). Validated on real logs:
// N=60 / |dI|>40A gives ~+-0.4 mOhm within-day single-block scatter (the temp/SoC floor); the
// median is far below the mOhm-per-month aging signal. RAM-only (fresh each boot, fills in ~1-2 min
// of varied driving); the latest medians (r_med) ride along in the daily flash record and seed the
// display at boot. Slow-changing -> emitted ON DEMAND ("B") + auto every ~15 events; NOT in the line.
inline constexpr int   R_WIN = 60;       // rolling window of trigger events
inline constexpr float R_DI  = 40.0f;    // |dI| (A) trigger between consecutive block-V sets
inline int16_t  r_win[14][R_WIN];        // R samples x10 (0.1 mOhm); <=0 = invalid slot
inline uint8_t  r_head = 0, r_cnt = 0;
inline float    r_prev_i = NAN, r_prev_v[14]; inline bool r_prev_ok = false;
inline uint32_t r_events = 0;            // total accepted trigger events
inline float    r_med[14] = {0};         // latest medians (mOhm); persisted via the daily record

inline float rblk_med(int i) {           // window median, NAN until enough fresh events
  float t[R_WIN]; int m = 0;
  for (int k=0;k<r_cnt;k++) { int16_t v=r_win[i][k]; if (v>0) t[m++]=v/10.0f; }
  if (m < 8) return NAN;
  std::nth_element(t, t+m/2, t+m); float hi=t[m/2];
  if (m & 1) return hi;
  std::nth_element(t, t+m/2-1, t+m); return 0.5f*(t[m/2-1]+hi);
}
inline float rblk_mohm(int i) {          // live median; keeps r_med fresh; falls back to r_med
  float v = rblk_med(i);
  if (!std::isnan(v)) { r_med[i] = v; return v; }
  return (r_med[i] > 0.1f) ? r_med[i] : NAN;
}
inline void rblk_event() {               // call on each fresh COMPLETE block-voltage set
  for (int i=0;i<14;i++) if (std::isnan(V[B01+i])) return;
  float a = V[HVA]; if (std::isnan(a)) return;
  if (r_prev_ok) {
    float dI = a - r_prev_i;
    if (fabsf(dI) > R_DI) {
      for (int i=0;i<14;i++) {
        float R = -(V[B01+i]-r_prev_v[i])/dI*1000.0f;
        r_win[i][r_head] = (R>2.0f && R<60.0f) ? (int16_t)lroundf(R*10.0f) : -1;
      }
      r_head = (uint8_t)((r_head+1)%R_WIN); if (r_cnt<R_WIN) r_cnt++; r_events++;
      if (r_events % 15u == 0u) rblk_request = true;   // auto-publish ~every 15 events
    }
  }
  r_prev_i = a; for (int i=0;i<14;i++) r_prev_v[i]=V[B01+i]; r_prev_ok = true;
}
inline void rblk_seed(const float *r) { for (int i=0;i<14;i++) r_med[i]=r[i]; }   // boot, from daily rec
inline void emit_rblocks() {             // on-demand response: {"rblk":[...14...],"rn":N}
  static char buf[256]; char *p = buf; char *end = buf + sizeof(buf);
  p += snprintf(p, end-p, "{\"rblk\":[");
  for (int i=0;i<14;i++) {
    if (i) *p++ = ',';
    float r = rblk_mohm(i);
    if (std::isnan(r)) { std::memcpy(p,"null",4); p+=4; }
    else p += snprintf(p, end-p, "%.1f", r);
  }
  p += snprintf(p, end-p, "],\"rn\":%u}\n", (unsigned)r_events);
  int total=(int)(p-buf), off=0;
  for (int t=0; t<200000 && off<total; t++) { int w=write(1,buf+off,total-off); if (w>0) off+=w; }
}

// per-DRIVE battery throughput (reset each boot; folded into the daily flash record on shutdown)
inline float drive_batt_in = 0, drive_batt_out = 0;   // Ah charged-in / discharged-out this drive

// One daily flash record (8-year ring, key = ring slot). Accumulating fields SUM across the day's
// drives; ts start=min, end=max; health fields = latest. ~78 bytes. Kept OUT of TripSlot so the
// existing persisted trip data is never touched (no magic bump / data loss).
struct DailyRec {
  uint32_t day;          // epoch / 86400 (0 = empty)
  uint32_t start_ts;     // first drive start of the day (min)
  uint32_t end_ts;       // last shutdown of the day (max)
  uint32_t odo;          // latest odometer
  float    dist, ev, fuel, move_s;   // accumulated across the day
  float    batt_in, batt_out, cap;   // batt Ah accumulated; capacity = latest
  int16_t  r[14];        // per-block R x10 (0.1 mOhm), latest
  int16_t  tb[3];        // TB1/TB2/TB3 x10 degC, latest
};
inline int16_t tb_x10(int key) { float v=V[key]; return std::isnan(v)?0:(int16_t)lroundf(v*10.0f); }
// Fold the current drive (boot_slot + drive batt + latest health) into today's record (pure).
inline void daily_accumulate(DailyRec &d, uint32_t today, uint32_t end_epoch) {
  if (d.day != today) { std::memset(&d,0,sizeof(d)); d.day = today; }   // new day -> reset
  uint32_t s = boot_slot.epoch ? boot_slot.epoch : end_epoch;
  if (d.start_ts == 0 || s < d.start_ts) d.start_ts = s;
  if (end_epoch > d.end_ts) d.end_ts = end_epoch;
  d.dist += boot_slot.dist; d.ev += boot_slot.ev; d.fuel += boot_slot.fuel; d.move_s += boot_slot.move_s;
  d.batt_in += drive_batt_in; d.batt_out += drive_batt_out;
  uint32_t o = cur_odo(); if (o) d.odo = o;
  d.cap = cap_ah;
  for (int i=0;i<14;i++) { float r=rblk_mohm(i); d.r[i] = std::isnan(r)?0:(int16_t)lroundf(r*10.0f); }
  d.tb[0]=tb_x10(TB1); d.tb[1]=tb_x10(TB2); d.tb[2]=tb_x10(TB3);
}

inline uint32_t derive_boot_ms = 0;   // first compute_derived call (for the boot warm-up)
inline void compute_derived(uint32_t now_ms) {
  if (derive_boot_ms == 0) derive_boot_ms = now_ms;
  // stamp the since-boot slot's start once host time + odometer are available
  // (epoch back-dated to boot so the "since start" chip shows the real start time/odo).
  if (boot_slot.epoch == 0 && host_time_set)
    boot_slot.epoch = cur_epoch(now_ms) - (uint32_t)((now_ms - derive_boot_ms) / 1000u);
  if (boot_slot.odo == 0) { uint32_t o = cur_odo(); if (o) boot_slot.odo = o; }
  // Fuel rate (l/h). Primary: the INJECTOR (injml = injected volume per injection, ~ * rpm =
  // volume/time) -> tracks real fuel incl. power-enrichment AND decel fuel-cut. Calibrated K
  // against the car trip computer (tank trip 2026-06-20: 3.05 L / 63.9 km). Fallback to the
  // MAF stoichiometric estimate (AFR 14.7) only while the injector PID hasn't arrived yet.
  float maf = V[MAF], r = V[RPM], inj = V[INJML];
  // l/h per (injml * rpm). Calibrated to the PUMP-REAL consumption: the car trip computer reads
  // ~5.6% low (5.2 shown vs 5.5 real on a full 45-47 L tank), so 0.00923 (car-match) * 5.5/5.2.
  constexpr float INJ_K = 0.00976f;            // fuel_corr (default 1.0) fine-tunes at a full refuel
  if (r < 100) V[FUEL] = 0.0f;                  // engine off
  else if (!std::isnan(inj)) V[FUEL] = INJ_K * inj * r * fuel_corr;       // injector-based (incl. enrichment / fuel-cut)
  else if (!std::isnan(maf)) V[FUEL] = maf/14.7f*3600.0f/745.0f * fuel_corr;   // MAF fallback

  float ct = V[CT], rate = NAN;
  if (!std::isnan(ct)) {
    ct_hist[ct_n % 13] = ct;
    if (ct_n >= 3) {
      int oldest = (ct_n >= 13) ? (ct_n-12) : 0;
      rate = (ct - ct_hist[oldest % 13]) * 60.0f / ((ct_n<13?ct_n:12)*5.0f);
      V[CTR] = rate;
    }
    ct_n++;
  }
  int wp = 0;
  if (!std::isnan(ct)) {
    if (ct>=105) wp=2; else if (ct>=100) wp=1;
    if (ct>88 && !std::isnan(rate)) { if (rate>=6) wp=2; else if (rate>=3 && wp<1) wp=1; }
    if (V[WPRUN]>0.5f && ct>95 && !std::isnan(rate) && rate>=4 && wp<2) wp=2;
  }
  V[WPW] = (float)wp;

  // peak-hold of the block delta (kept only for the pre-maturity fallback)
  float dlt = V[BLKD], amps = V[HVA];
  if (!std::isnan(dlt)) {
    bool load = !std::isnan(amps) && fabsf(amps)>15;
    if (load) dload = fmaxf(dload*0.95f, dlt); else drest = fmaxf(drest*0.95f, dlt);
  }

  // self-learning layer: learns only on a new block set (~1 Hz), not on every 0.5s tick
  if (blocks_fresh) { learn_update(); rblk_event(); blocks_fresh = false; }
  int learned = learn_verdict();   // fills CWL/WBLK/WZ; >0 only once the baseline is mature

  // HV cell warning. The learned, condition-aware layer is the PRIMARY detector once
  // mature (validated against a real drive log: it correctly reports healthy while the
  // absolute block-delta path false-alarmed on normal acceleration spikes). Until the
  // baseline matures, fall back to the fixed block-delta thresholds.
  int cw;
  if (lz_n >= LRN_NMIN) {
    cw = learned;
  } else {
    // data-informed fallback: a healthy pack spikes blkD to ~0.97 V under load, so
    // the warn/error levels sit above that (avoids false alarms before maturity)
    cw = 0;
    if (dload>=1.20f || drest>=0.40f) cw = 1;
    if (dload>=1.60f || drest>=0.60f) cw = 2;
  }
  // coarse hard-safety overrides (slow, reliable signals) regardless of source:
  float mr = V[MAXR];
  if (!std::isnan(mr) && mr>=40 && cw<1) cw = 1;
  if (!std::isnan(mr) && mr>=60) cw = 2;
  float t1=V[TB1], t2=V[TB2], t3=V[TB3];
  if (!std::isnan(t1)&&!std::isnan(t2)&&!std::isnan(t3)) {
    float tmax=fmaxf(t1,fmaxf(t2,t3)), tmin=fminf(t1,fminf(t2,t3));
    if (tmax>=55) cw=2; else if (tmax-tmin>10 && cw<1) cw=1;
  }
  V[CELLW] = (float)cw;
  V[EFF] = (float)eng_ff;   // DEBUG: engine-ECU FF counter
  V[EOK] = (float)eng_ok;   // DEBUG: successful 2101 counter
  V[LOOPS] = (float)poll_cycle;  // how many full POLL cycles the scheduler went through

  // pack-voltage average for the kWh conversion (no dt needed -> also on the first call)
  float so = V[SOC], vl = V[VL];
  if (!std::isnan(vl) && vl > 0) vl_avg = (vl_avg <= 0) ? vl : (0.99f*vl_avg + 0.01f*vl);

  // trip + moving time + pack-capacity coulomb counting (need dt)
  if (derive_init) {
    float dt_h = (float)(now_ms - derive_last_ms) / 3600000.0f;   // ms -> hours
    float dt_s = dt_h * 3600.0f;
    float sp = V[SPD], fl = V[FUEL], amps = V[HVA];
    // Compute the per-tick deltas ONCE, then add them to every slot (boot + 0..5). Trapezoidal
    // (average prev+current rate) removes the left-Riemann lag on the ramping speed.
    float d_dist = 0, d_fuel = 0, d_ev = 0, d_move = 0, d_re = 0, d_be = 0;
    if (!std::isnan(sp)) {
      float a = ((std::isnan(trap_sp) ? sp : trap_sp) + sp) * 0.5f;
      if (a > 0) d_dist = a * dt_h;                                       // km
    }
    if (!std::isnan(fl)) {
      float a = ((std::isnan(trap_fl) ? fl : trap_fl) + fl) * 0.5f;
      if (a > 0) d_fuel = a * dt_h;                                       // l
    }
    // active moving time: stops up to 180 s still count as "moving" (shared stop timer)
    if (!std::isnan(sp)) {
      if (sp > 2.0f) { stop_s = 0; d_move = dt_s; }
      else { stop_s += dt_s; if (stop_s <= 180.0f) d_move = dt_s; }
    }
    // pure-EV distance: moving with the engine off (rpm < 100)
    float rp = V[RPM];
    if (!std::isnan(sp) && sp > 2.0f && !std::isnan(rp) && rp < 100.0f) d_ev = sp * dt_h;
    // regen ratio: while the BRAKE PEDAL is pressed AND decelerating, accumulate the kinetic
    // energy shed (1/2 m dv^2, m~1450 kg) and the energy recovered into the pack (-hvA*VL while
    // hvA<0). The trigger is the brake PEDAL only (brkP) — the old "or hvA<-5" also fired while
    // coasting with the engine charging from fuel (NOT regen), which inflated the ratio >100%.
    {
      float brk = V[BRKP], vn = sp, vp = std::isnan(trap_sp) ? sp : trap_sp;
      if (!std::isnan(vn) && !std::isnan(vp) && vn < vp && vn >= 0) {
        bool braking = !std::isnan(brk) && brk > 0.55f;   // brake pedal pressed
        if (braking) {
          float vpm = vp / 3.6f, vnm = vn / 3.6f;
          d_be = 0.5f * 1450.0f * (vpm*vpm - vnm*vnm);                    // J shed
          if (!std::isnan(amps) && amps < 0 && !std::isnan(vl) && vl > 0) d_re = (-amps * vl) * dt_s;  // J recovered
        }
      }
    }
    // accumulate into every slot (the only difference between slots is WHEN they reset)
    slot_add(boot_slot, d_dist, d_ev, d_fuel, d_move, d_re, d_be);
    for (int i = 0; i < NSLOT; i++) slot_add(slot[i], d_dist, d_ev, d_fuel, d_move, d_re, d_be);
    // distance = real odometer delta (drift-free) for any slot with a stamped start odo; the
    // spd-integral above only fills it until the odo is known / for slots without a start odo
    // (e.g. lifetime). Fixes the ~0.4% speed-integral drift seen vs the odometer.
    {
      uint32_t co = cur_odo();
      if (co) {
        if (boot_slot.odo && co >= boot_slot.odo) boot_slot.dist = (float)(co - boot_slot.odo);
        for (int i = 0; i < NSLOT; i++)
          if (slot[i].odo && co >= slot[i].odo) slot[i].dist = (float)(co - slot[i].odo);
      }
    }

    // running charge integral for the capacity estimate (needs dt)
    if (!std::isnan(amps)) {                              // signed Ah + per-drive in/out split
      charge_ah += amps * dt_h;
      if (amps < 0) drive_batt_in += -amps * dt_h; else drive_batt_out += amps * dt_h;
    }
  }
  derive_last_ms = now_ms;
  trap_sp = V[SPD]; trap_fl = V[FUEL];   // remember for the next trapezoid step
  derive_init = true;

  // pack capacity: SoC peak-to-trough swing vs the charge integral (runs each call so
  // the very first SoC extreme is captured before it moves)
  if (!std::isnan(so)) {
    if (std::isnan(cap_smin)) { cap_smin = cap_smax = so; cap_q_lo = cap_q_hi = charge_ah; }
    if (so > cap_smax) { cap_smax = so; cap_q_hi = charge_ah; }
    if (so < cap_smin) { cap_smin = so; cap_q_lo = charge_ah; }
    if (cap_smax - cap_smin >= CAP_MIN_DSOC) {
      // charge span (max reached via charging -> charge_ah lower at the peak): apply the NiMH
      // coulombic efficiency so the result is DISCHARGEABLE Ah, not the (larger) charge-in Ah.
      float eff = (cap_q_hi < cap_q_lo) ? CAP_CHG_EFF : 1.0f;
      float span = eff * fabsf(cap_q_hi - cap_q_lo) / ((cap_smax - cap_smin) / 100.0f);  // dischargeable Ah
      if (span >= CAP_MIN_AH && span <= CAP_MAX_AH) {
        cap_ah = (cap_n == 0) ? span : (1.0f-CAP_ALPHA)*cap_ah + CAP_ALPHA*span;
        cap_n++;
      }
      cap_smin = cap_smax = so; cap_q_lo = cap_q_hi = charge_ah;   // reset window
    }
  }
  // dashboard "trip computer" = SINCE BOOT (boot_slot, not persisted)
  V[TDIST] = boot_slot.dist;
  V[TFUEL] = boot_slot.fuel;
  V[TAVG]  = (boot_slot.dist > 0.1f) ? (boot_slot.fuel / boot_slot.dist * 100.0f) : NAN;  // l/100km
  V[CAPAH]  = (cap_n > 0) ? cap_ah : NAN;
  V[CAPKWH] = (cap_n > 0 && vl_avg > 0) ? (cap_ah * vl_avg / 1000.0f) : NAN;
  V[TMOVE] = boot_slot.move_s;
  V[TSPD]  = (boot_slot.move_s > 5.0f) ? (boot_slot.dist / (boot_slot.move_s / 3600.0f)) : NAN;   // km/h since boot
  V[TEV]   = boot_slot.ev;

  // refuel detection: fuelIn (%) jumps up significantly while parked -> record the time
  float fin = V[FUELIN], sp2 = V[SPD];
  if (!std::isnan(fin)) {
    if (fuel_ref < 0) fuel_ref = fin;
    bool parked = std::isnan(sp2) || sp2 < 3.0f;
    // Gate refuel detection until the NVS load has completed: fuel_ref is then either the
    // restored baseline or 100% (empty-flash default), so the boot fuelIn transient can't be
    // mistaken for a refuel. (Replaces the old 60 s boot warm-up.)
    if (persist_loaded && parked && fin - fuel_ref >= REFUEL_DELTA) {
      // up-jump seen; require it to PERSIST >=1.5 s so a 1-sample spike does not trigger
      if (refuel_seen_ms == 0) refuel_seen_ms = now_ms;
      else if (now_ms - refuel_seen_ms >= 1500) {
        // push the just-finished tank (slot[1]) into the rotating refuel history, then restart it
        rhist[rhist_head] = slot[1];
        rhist_head = (rhist_head + 1) % HISTN;
        if (rhist_n < HISTN) rhist_n++;
        uint32_t ep = host_time_set ? cur_epoch(now_ms) : 0u;
        slot_start(slot[1], ep, cur_odo());                        // new tank starts now
        if (host_time_set) { last_refuel_epoch = ep; refuel_pending = false; }
        else { last_refuel_ms = now_ms; refuel_pending = true; }   // correct later (#4)
        refuel_dirty = true; persist_request = true;               // flush NVS on refuel
        fuel_ref = fin; refuel_seen_ms = 0;
      }
    } else {
      refuel_seen_ms = 0;                       // dropped back -> it was a transient spike
      fuel_ref += 0.01f * (fin - fuel_ref);     // slow follow (consumption + noise)
    }
  }
}

// JSON field table = the SINGLE source of the output contract. The order is
// BIT-FOR-BIT the old KEYS[] (CT..LOOPS). prefix = a READY quoted key with a colon;
// preflen = PRE-computed length (no runtime strlen); dec = number of decimals.
// dec=0: rpm/spd/load/run/weakB/dtc*/blower/ecuMode/fanMode/wpRun/wpW/cellW/
// injus; dec=3: injml; the rest 2 (matching the old %.2f).
struct Field { uint16_t idx; const char *prefix; uint8_t preflen; uint8_t dec; };
inline const Field FIELDS[] = {
  {CT,"\"ct\":",5,2}, {RPM,"\"rpm\":",6,0}, {SPD,"\"spd\":",6,0}, {SOC,"\"soc\":",6,2},
  {LOAD,"\"load\":",7,0}, {MAF,"\"maf\":",6,2}, {MAP,"\"map\":",6,2}, {IAT,"\"iat\":",6,2},
  {THR,"\"thr\":",6,2}, {PEDAL,"\"pedal\":",8,2}, {VBAT,"\"vbat\":",7,2}, {AMB,"\"amb\":",6,2},
  {RUN,"\"run\":",6,0}, {FUEL,"\"fuel\":",7,2},
  {MG1T,"\"mg1t\":",7,2}, {MG1R,"\"mg1r\":",7,2}, {MG1Q,"\"mg1q\":",7,2},
  {MG2T,"\"mg2t\":",7,2}, {MG2R,"\"mg2r\":",7,2}, {MG2Q,"\"mg2q\":",7,2},
  {INV1,"\"inv1\":",7,2}, {INV2,"\"inv2\":",7,2}, {BTU,"\"btu\":",6,2}, {BTL,"\"btl\":",6,2},
  {VL,"\"vl\":",5,2}, {VH,"\"vh\":",5,2}, {INVCT,"\"invct\":",8,2}, {INVWP,"\"invwp\":",8,2},
  {ACW,"\"acw\":",6,2},
  {HVA,"\"hvA\":",6,2}, {HVDIS,"\"hvdis\":",8,2}, {HVCHG,"\"hvchg\":",8,2},
  {BMIN,"\"bmin\":",7,2}, {BMAX,"\"bmax\":",7,2}, {BLKD,"\"blkD\":",7,2},
  {WEAKB,"\"weakB\":",8,0}, {MAXR,"\"maxR\":",7,2}, {THOT,"\"tHot\":",7,2},
  {HVAIR,"\"hvAir\":",8,2}, {TB1,"\"tb1\":",6,2}, {TB2,"\"tb2\":",6,2}, {TB3,"\"tb3\":",6,2},
  {B01,"\"b01\":",6,2}, {B02,"\"b02\":",6,2}, {B03,"\"b03\":",6,2}, {B04,"\"b04\":",6,2},
  {B05,"\"b05\":",6,2}, {B06,"\"b06\":",6,2}, {B07,"\"b07\":",6,2}, {B08,"\"b08\":",6,2},
  {B09,"\"b09\":",6,2}, {B10,"\"b10\":",6,2}, {B11,"\"b11\":",6,2}, {B12,"\"b12\":",6,2},
  {B13,"\"b13\":",6,2}, {B14,"\"b14\":",6,2},
  {CABIN,"\"cabin\":",8,2}, {SETT,"\"setT\":",7,2}, {COMP,"\"comp\":",7,2},
  {EVAP,"\"evap\":",7,2}, {SOLAR,"\"solar\":",8,2},
  {WFL,"\"wFL\":",6,2}, {WFR,"\"wFR\":",6,2}, {WRL,"\"wRL\":",6,2}, {WRR,"\"wRR\":",6,2},
  {WDIF,"\"wDif\":",7,2}, {GLAT,"\"gLat\":",7,2}, {GFWD,"\"gFwd\":",7,2},
  {STEER,"\"steer\":",8,2}, {BRKP,"\"brkP\":",7,2},
  {ENGNM,"\"engNm\":",8,2}, {INJML,"\"injml\":",8,3}, {INJUS,"\"injus\":",8,0},
  {BATTFAN,"\"battFan\":",10,2}, {ECUMODE,"\"ecuMode\":",10,0}, {FANMODE,"\"fanMode\":",10,0},
  {DTCCUR,"\"dtcCur\":",9,0}, {DTCHIST,"\"dtcHist\":",10,0},
  {ACAMB,"\"acAmb\":",8,2}, {BLOWER,"\"blower\":",9,0}, {ACPRESS,"\"acPress\":",10,2},
  {BODYV,"\"bodyV\":",8,2}, {FUELIN,"\"fuelIn\":",9,2}, {OILDIST,"\"oilDist\":",10,2},
  {CTR,"\"ctR\":",6,2}, {WPRUN,"\"wpRun\":",8,0}, {WPW,"\"wpW\":",6,0}, {CELLW,"\"cellW\":",8,0},
  {ELEN,"\"eLen\":",7,2}, {EFF,"\"eFF\":",6,2}, {EOK,"\"eOK\":",6,2}, {LOOPS,"\"loops\":",8,2},
  {GEAR,"\"gear\":",7,0}, {TURN,"\"turn\":",7,0}, {SETSPD,"\"setSpd\":",9,0},
  {CWL,"\"cwL\":",6,0}, {WBLK,"\"wblk\":",7,0}, {WZ,"\"wz\":",5,2},
  // trip computer / slots are NOT flat fields anymore -> emitted as the "slots" array below
  {CAPAH,"\"capAh\":",8,2}, {CAPKWH,"\"capKwh\":",9,2},
  {ODO,"\"odo\":",6,0}, {GASB,"\"gasB\":",7,0}, {LIGHTS,"\"lights\":",9,0},
  {AMBL,"\"ambL\":",7,0}, {DMODE,"\"dmode\":",8,0}, {EVMODE,"\"ev\":",5,0},
};
inline const int FIELDS_N = sizeof(FIELDS) / sizeof(FIELDS[0]);

inline const int32_t POW10[4] = {1, 10, 100, 1000};

// Integer-based number->text (NO snprintf/%f -> no software double dtoa).
// NaN -> "null"; sign; integer part hand-converted; fractional part in dec digits
// with leading/trailing zeros. Advances the p pointer forward.
inline void put_num(char *&p, float v, uint8_t dec) {
  if (std::isnan(v)) { std::memcpy(p, "null", 4); p += 4; return; }
  if (v < 0) { *p++ = '-'; v = -v; }
  int32_t scaled = (int32_t)lrintf(v * (float)POW10[dec]);
  int32_t ip = scaled / POW10[dec];
  int32_t fp = scaled % POW10[dec];
  char tmp[12]; int n = 0;
  if (ip == 0) tmp[n++] = '0';
  else while (ip > 0) { tmp[n++] = (char)('0' + ip % 10); ip /= 10; }
  while (n > 0) *p++ = tmp[--n];
  if (dec > 0) {
    *p++ = '.';
    for (int j = dec - 1; j >= 0; j--) { p[j] = (char)('0' + fp % 10); fp /= 10; }
    p += dec;
  }
}

// Append an unsigned integer as decimal text (epoch values don't fit a float).
inline void put_uint(char *&p, uint32_t v) {
  char tmp[12]; int n = 0;
  if (v == 0) tmp[n++] = '0';
  else while (v > 0) { tmp[n++] = (char)('0' + v % 10); v /= 10; }
  while (n > 0) *p++ = tmp[--n];
}

// One TripSlot as compact JSON, using the integer formatters (NO dtoa -> hot-path safe):
// {"e":epoch,"o":odo,"d":dist,"v":ev,"f":fuel,"m":move_s,"r":regen%}
inline void put_slot(char *&p, const TripSlot &s) {
  std::memcpy(p, "{\"e\":", 5); p += 5; put_uint(p, s.epoch);
  std::memcpy(p, ",\"o\":", 5); p += 5; put_uint(p, s.odo);
  std::memcpy(p, ",\"d\":", 5); p += 5; put_num(p, s.dist, 1);
  std::memcpy(p, ",\"v\":", 5); p += 5; put_num(p, s.ev, 1);
  std::memcpy(p, ",\"f\":", 5); p += 5; put_num(p, s.fuel, 2);
  std::memcpy(p, ",\"m\":", 5); p += 5; put_num(p, s.move_s, 0);
  std::memcpy(p, ",\"r\":", 5); p += 5; put_num(p, s.brake_e > 1.0f ? fminf(100.0f, s.regen_e / s.brake_e * 100.0f) : 0.0f, 0);
  *p++ = '}';
}

inline void emit_json(const char *door_cruise_extra, uint32_t now_ms) {
  char buf[3072];   // worst-case line is ~2 KB now (many fields) -> headroom against overflow
  char *p = buf;
  *p++ = '{';
  for (int i = 0; i < FIELDS_N; i++) {
    const Field &f = FIELDS[i];
    std::memcpy(p, f.prefix, f.preflen); p += f.preflen;
    put_num(p, V[f.idx], f.dec);
    *p++ = ',';
  }
  // wall-clock epoch + last-refuel epoch as INTEGERS (a float can't hold them)
  std::memcpy(p, "\"epoch\":", 8); p += 8; put_uint(p, cur_epoch(now_ms)); *p++ = ',';
  std::memcpy(p, "\"fuelTs\":", 9); p += 9; put_uint(p, last_refuel_epoch); *p++ = ',';
  std::memcpy(p, "\"fw\":", 5); p += 5; put_uint(p, (uint32_t)FW_VERSION); *p++ = ',';
  std::memcpy(p, "\"offTs\":", 8); p += 8; put_uint(p, boot_off_epoch); *p++ = ',';
  std::memcpy(p, "\"offN\":", 7); p += 7; put_uint(p, boot_off_n); *p++ = ',';
  // ALL live slots in one array (the app derives everything). Order: [0]=since-boot,
  // [1]=lifetime, [2]=tank, [3]=oil, [4..6]=Trip A/B/C. History records are on-demand ("H");
  // here we only send their COUNTS (rhN/ohN) so the UI knows whether data exists.
  std::memcpy(p, "\"slots\":[", 9); p += 9;
  put_slot(p, boot_slot);
  for (int i = 0; i < NSLOT; i++) { *p++ = ','; put_slot(p, slot[i]); }
  *p++ = ']'; *p++ = ',';
  std::memcpy(p, "\"rhN\":", 6); p += 6; put_uint(p, rhist_n); *p++ = ',';
  std::memcpy(p, "\"ohN\":", 6); p += 6; put_uint(p, ohist_n); *p++ = ',';
  std::memcpy(p, "\"fCorr\":", 8); p += 8; put_num(p, fuel_corr, 3); *p++ = ',';
  if (door_cruise_extra) {       // passed in by the YAML: "door":..,"cruise":..
    size_t n = std::strlen(door_cruise_extra);
    std::memcpy(p, door_cruise_extra, n); p += n;
  }
  *p++ = '}';
  *p++ = '\n';
  out_write(buf, (int)(p - buf));
}

// one TripSlot as JSON: e=epoch o=odo d=dist v=ev f=fuel m=move_s r=regen%
inline void emit_slot_json(char *&p, char *end, const TripSlot &s) {
  float r = s.brake_e > 1.0f ? fminf(100.0f, s.regen_e / s.brake_e * 100.0f) : 0.0f;
  p += snprintf(p, end - p, "{\"e\":%u,\"o\":%u,\"d\":%.1f,\"v\":%.1f,\"f\":%.2f,\"m\":%.0f,\"r\":%.0f}",
                (unsigned)s.epoch, (unsigned)s.odo, s.dist, s.ev, s.fuel, s.move_s, r);
}
// On-demand history dump: ONE array (50 records max ~4.3 KB) so a single response never
// blows the buffer. The live slots (with their epochs) come from the regular line, so the
// history responses carry only the records. "H" -> refuel history, "HO" -> oil-change history.
inline void emit_hist_array(const char *key, const TripSlot *arr, uint8_t n, uint8_t head) {
  static char buf[6144]; char *p = buf; char *end = buf + sizeof(buf);
  p += snprintf(p, end - p, "{\"%s\":[", key);
  for (uint8_t k = 0; k < n && p < end - 120; k++) {
    if (k) *p++ = ',';
    emit_slot_json(p, end, arr[(uint8_t)((head + HISTN - n + k) % HISTN)]);   // oldest first
  }
  p += snprintf(p, end - p, "]}\n");
  int total = (int)(p - buf), off = 0;
  for (int tries = 0; off < total && tries < 200000; tries++) {
    int w = write(1, buf + off, total - off);
    if (w > 0) off += w;
  }
}
inline void emit_history() { emit_hist_array("rhist", rhist, rhist_n, rhist_head); }
inline void emit_ohist()   { emit_hist_array("ohist", ohist, ohist_n, ohist_head); }

} // namespace prius

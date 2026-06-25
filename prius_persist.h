#pragma once
// PriusCAN persistence over NVS (ESP-IDF). IDF-aware -> kept OUT of prius_parse.h.
// The ESP is the single source of truth: on a power-off signal (and on refuel / slot reset)
// it writes ALL firmware-owned state into ONE NVS blob, and restores it FIRST at boot. The
// Android app only displays. NVS is already initialised by ESPHome at boot.
#include "nvs.h"
#include <cstring>
#include "prius_parse.h"

namespace prius {

inline uint32_t save_count = 0;   // running NVS-save counter (seeded from the blob at boot)

// Binary layout persisted to NVS. Bump VER on any field change (old blob then ignored).
struct PBlob {
  uint32_t magic;            // 0x50430000 | VER
  uint32_t off_n, off_ts;    // save counter + last save epoch (persistence proof)
  float    fuel_ref;         // fuel-level baseline (refuel detection)
  uint32_t refuel_epoch;     // = last_refuel_epoch (current tank start)
  TripSlot slot[7];          // [0]=lifetime [1]=tank [2]=oil [3..5]=user [6]=home-departure
  uint8_t  rhist_n, rhist_head, ohist_n, ohist_head;
  TripSlot rhist[HISTN];     // refuel history
  TripSlot ohist[HISTN];     // oil-change history
  float    cap_ah; uint32_t cap_n; float vl_avg;   // learned capacity
  float    lz[14]; uint32_t lz_n;                   // learned weak-block layer
};
inline constexpr uint32_t PBLOB_MAGIC = 0x50430006;  // "PC" + ver 6 (TripSlot += city_dist, city_ev)

// ---- previous on-flash layout (magic ...05, 8-field TripSlot) -> read once + migrate forward ----
struct OldTripSlotV5 { uint32_t epoch, odo; float dist, ev, fuel, move_s, regen_e, brake_e; };
struct OldPBlobV5 {
  uint32_t magic, off_n, off_ts; float fuel_ref; uint32_t refuel_epoch;
  OldTripSlotV5 slot[7]; uint8_t rhist_n, rhist_head, ohist_n, ohist_head;
  OldTripSlotV5 rhist[HISTN], ohist[HISTN];
  float cap_ah; uint32_t cap_n; float vl_avg; float lz[14]; uint32_t lz_n;
};
inline constexpr uint32_t PBLOB_MAGIC_V5 = 0x50430005;
inline void migrate_slot_v5(TripSlot &n, const OldTripSlotV5 &o) {
  n.epoch = o.epoch; n.odo = o.odo; n.dist = o.dist; n.ev = o.ev; n.fuel = o.fuel;
  n.move_s = o.move_s; n.regen_e = o.regen_e; n.brake_e = o.brake_e; n.city_dist = 0; n.city_ev = 0;
}

} // namespace prius

// Save everything (power-off / refuel / slot reset). epoch = current wall clock.
inline void prius_persist_save(uint32_t epoch) {
  if (!prius::persist_loaded) return;          // never write before the boot read completed
  nvs_handle_t h;
  if (nvs_open("priuscan", NVS_READWRITE, &h) != ESP_OK) return;
  static prius::PBlob b;                        // static -> off the stack (~3.5 KB)
  std::memset(&b, 0, sizeof(b));
  b.magic = prius::PBLOB_MAGIC;
  b.off_n = ++prius::save_count;
  b.off_ts = epoch;
  b.fuel_ref = prius::fuel_ref;
  b.refuel_epoch = prius::last_refuel_epoch;
  std::memcpy(b.slot, prius::slot, sizeof(b.slot));
  b.rhist_n = prius::rhist_n; b.rhist_head = prius::rhist_head;
  b.ohist_n = prius::ohist_n; b.ohist_head = prius::ohist_head;
  std::memcpy(b.rhist, prius::rhist, sizeof(b.rhist));
  std::memcpy(b.ohist, prius::ohist, sizeof(b.ohist));
  b.cap_ah = prius::cap_ah; b.cap_n = prius::cap_n; b.vl_avg = prius::vl_avg;
  std::memcpy(b.lz, prius::lz, sizeof(b.lz)); b.lz_n = prius::lz_n;
  nvs_set_blob(h, "blob", &b, sizeof(b));
  if (!std::isnan(prius::cons_ema)) {                    // distance-to-empty consumption EMA (separate
    uint32_t cb; std::memcpy(&cb, &prius::cons_ema, 4);  // key -> no PBlob struct change / magic risk)
    nvs_set_u32(h, "cema", cb);
  }
  { uint32_t ab; std::memcpy(&ab, &prius::fuel_anchor, 4);   // virtual-gauge anchor + calibrated flag
    nvs_set_u32(h, "fanc", ab);                             // (consumption itself lives in the tank slot[1])
    nvs_set_u32(h, "tkn", prius::tank_known ? 1u : 0u); }
  nvs_commit(h);
  nvs_close(h);
}

// Restore at boot (call FIRST, before other logic). If there is no valid blob yet (first boot
// after the v3.0 magic bump), seed the TANK slot from the reconstructed 2026-06-20 12:42 refuel
// instead of zero, so the current tank shows real data right away. Once v3.0 saves its own blob
// this seed is never used again (the real, accumulating data takes over).
inline void prius_persist_load() {
  bool loaded = false;
  nvs_handle_t h;
  if (nvs_open("priuscan", NVS_READONLY, &h) == ESP_OK) {
    static prius::PBlob b; size_t sz = sizeof(b);
    if (nvs_get_blob(h, "blob", &b, &sz) == ESP_OK && sz == sizeof(b) && b.magic == prius::PBLOB_MAGIC) {
      prius::save_count = b.off_n;
      prius::boot_off_n = b.off_n; prius::boot_off_epoch = b.off_ts;
      prius::fuel_ref = b.fuel_ref;
      prius::last_refuel_epoch = b.refuel_epoch;
      std::memcpy(prius::slot, b.slot, sizeof(prius::slot));
      prius::rhist_n = b.rhist_n; prius::rhist_head = b.rhist_head;
      prius::ohist_n = b.ohist_n; prius::ohist_head = b.ohist_head;
      std::memcpy(prius::rhist, b.rhist, sizeof(prius::rhist));
      std::memcpy(prius::ohist, b.ohist, sizeof(prius::ohist));
      prius::cap_ah = b.cap_ah; prius::cap_n = b.cap_n; prius::vl_avg = b.vl_avg;
      std::memcpy(prius::lz, b.lz, sizeof(prius::lz)); prius::lz_n = b.lz_n;
      loaded = true;
    } else {                                     // no v6 blob -> try the previous (v5) layout + migrate
      static prius::OldPBlobV5 ob; size_t osz = sizeof(ob);
      if (nvs_get_blob(h, "blob", &ob, &osz) == ESP_OK && osz == sizeof(ob)
          && ob.magic == prius::PBLOB_MAGIC_V5) {
        prius::save_count = ob.off_n; prius::boot_off_n = ob.off_n; prius::boot_off_epoch = ob.off_ts;
        prius::fuel_ref = ob.fuel_ref; prius::last_refuel_epoch = ob.refuel_epoch;
        for (int i = 0; i < 7; i++) prius::migrate_slot_v5(prius::slot[i], ob.slot[i]);
        prius::rhist_n = ob.rhist_n; prius::rhist_head = ob.rhist_head;
        prius::ohist_n = ob.ohist_n; prius::ohist_head = ob.ohist_head;
        for (int i = 0; i < prius::HISTN; i++) {
          prius::migrate_slot_v5(prius::rhist[i], ob.rhist[i]);
          prius::migrate_slot_v5(prius::ohist[i], ob.ohist[i]);
        }
        prius::cap_ah = ob.cap_ah; prius::cap_n = ob.cap_n; prius::vl_avg = ob.vl_avg;
        std::memcpy(prius::lz, ob.lz, sizeof(prius::lz)); prius::lz_n = ob.lz_n;
        loaded = true;
        prius::persist_request = true;           // re-save in the v6 format on the next flush
      }
    }
    uint32_t cb; if (nvs_get_u32(h, "cema", &cb) == ESP_OK) std::memcpy(&prius::cons_ema, &cb, 4);
    uint32_t ab, tk;                                        // virtual-gauge anchor + calibrated flag
    if (nvs_get_u32(h, "fanc", &ab) == ESP_OK) std::memcpy(&prius::fuel_anchor, &ab, 4);
    if (nvs_get_u32(h, "tkn",  &tk) == ESP_OK) prius::tank_known = (tk != 0);
    nvs_close(h);
  }
  if (!loaded) {
    // one-time seed (no blob yet): TANK = {epoch, odo, dist, ev, fuel, move_s, regen_e, brake_e}
    // from carlogs/priuscan-20260620.jsonl integrated 12:42 -> ~20:08.
    prius::slot[1] = { 1781952172u, 423903u, 63.9f, 20.6f, 2.85f, 5337.0f, 4070000.0f, 7022000.0f };
    prius::last_refuel_epoch = 1781952172u;
    prius::fuel_ref = 100.0f;   // tank is full -> don't let the first reading look like a refuel
  }
  // the virtual gauge's "consumed since refuel" IS the tank slot's fuel (persisted in the blob) --
  // sync it so fuelL = anchor - consumed continues across key-off (no re-anchor to the bouncy gauge).
  prius::fuel_since_refuel = prius::slot[1].fuel;
  prius::persist_loaded = true;                // boot read attempt done -> writes allowed
}

// ---- daily health/trip ring on flash (separate NVS namespace "priusday"; 8-year ring) ----
// One record per day, accumulated across the day's drives, overwritten in place; a new day moves
// to the next ring slot; at 8 years the oldest is overwritten. Independent of the trip-slot blob.
inline constexpr uint32_t DAILY_N = 2920;    // ~8 years of one-record-per-day

// Fold the current drive into today's record on shutdown (also refuel/parked). epoch = wall clock.
inline void prius_daily_save(uint32_t epoch) {
  if (!prius::persist_loaded || epoch < 1000000000u) return;
  nvs_handle_t h;
  if (nvs_open("priusday", NVS_READWRITE, &h) != ESP_OK) return;
  uint32_t head = 0; nvs_get_u32(h, "head", &head); head %= DAILY_N;
  uint32_t today = epoch / 86400u;
  static prius::DailyRec d; std::memset(&d, 0, sizeof(d));
  char key[8]; snprintf(key, sizeof(key), "d%04u", (unsigned)head);
  size_t sz = sizeof(d); nvs_get_blob(h, key, &d, &sz);        // load head record (day=0 if none)
  if (d.day != 0 && d.day != today) {                          // new day -> next ring slot
    head = (head + 1) % DAILY_N;
    snprintf(key, sizeof(key), "d%04u", (unsigned)head);
    std::memset(&d, 0, sizeof(d));
  }
  prius::daily_accumulate(d, today, epoch);                    // pure fold-in (boot_slot + health)
  nvs_set_blob(h, key, &d, sizeof(d));
  nvs_set_u32(h, "head", head);
  nvs_commit(h);
  nvs_close(h);
}

// Boot: seed the per-block resistance display from the most recent daily record.
inline void prius_daily_load() {
  nvs_handle_t h;
  if (nvs_open("priusday", NVS_READONLY, &h) != ESP_OK) return;
  uint32_t head = 0;
  if (nvs_get_u32(h, "head", &head) == ESP_OK) {
    static prius::DailyRec d; size_t sz = sizeof(d);
    char key[8]; snprintf(key, sizeof(key), "d%04u", (unsigned)(head % DAILY_N));
    if (nvs_get_blob(h, key, &d, &sz) == ESP_OK && d.day > 0) {
      float r[14]; for (int i = 0; i < 14; i++) r[i] = d.r[i] / 10.0f;
      prius::rblk_seed(r);
    }
  }
  nvs_close(h);
}

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
inline constexpr uint32_t PBLOB_MAGIC = 0x50430005;  // "PC" + ver 5 (7 slots: +home-departure)

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
    }
    nvs_close(h);
  }
  if (!loaded) {
    // one-time seed (no blob yet): TANK = {epoch, odo, dist, ev, fuel, move_s, regen_e, brake_e}
    // from carlogs/priuscan-20260620.jsonl integrated 12:42 -> ~20:08.
    prius::slot[1] = { 1781952172u, 423903u, 63.9f, 20.6f, 2.85f, 5337.0f, 4070000.0f, 7022000.0f };
    prius::last_refuel_epoch = 1781952172u;
    prius::fuel_ref = 100.0f;   // tank is full -> don't let the first reading look like a refuel
  }
  prius::persist_loaded = true;                // boot read attempt done -> writes allowed
}

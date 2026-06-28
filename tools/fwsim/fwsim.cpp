// fwsim — replay PriusCAN JSON logs through the REAL firmware code (prius_parse.h) to recompute
// every trip-computer value, including refuel auto-detection. No reimplementation -> no drift:
// it #includes the actual header and calls compute_derived() exactly as the ESP does.
//
// Protocol on stdin, one line per log sample (emitted by replay.py), whitespace-separated:
//     <ts_ms> <city_state|-1> <fuel_corr> key=val key=val ...
// keys are the firmware JSON keys (== KEYS[] / VKey names); unknown keys are ignored. The harness
// sets V[], models a reboot on a >5 s gap (RAM-clear, NVS-persisted slots survive), runs
// compute_derived(), and prints one JSON line per detected refuel + a final summary.
//
// State starts FRESH (empty slots, fuel_ref unset) so the output is the trip data computed purely
// from the given logs (per-tank, split at each detected refuel). Distances are odo-anchored (gap-
// immune); fuel is integrated over logged samples only -> a coverage figure lets you correct it.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <string>
#include <unordered_map>
#include "prius_parse.h"
using namespace prius;

static void emit_slot(const char *nm, const TripSlot &s) {
    double l100 = s.dist > 0.1 ? s.fuel / s.dist * 100.0 : 0.0;
    double kmh  = s.move_s > 5 ? s.dist / (s.move_s / 3600.0) : 0.0;
    double evp  = s.dist > 0.1 ? s.ev / s.dist * 100.0 : 0.0;
    double cityp= s.dist > 0.1 ? s.city_dist / s.dist * 100.0 : 0.0;
    printf("{\"slot\":\"%s\",\"epoch\":%u,\"odo\":%u,\"dist\":%.2f,\"ev\":%.2f,\"fuel\":%.3f,"
           "\"move_s\":%.0f,\"city_dist\":%.2f,\"city_ev\":%.2f,\"l100\":%.2f,\"kmh\":%.1f,"
           "\"ev_pct\":%.1f,\"city_pct\":%.1f}\n",
           nm, s.epoch, s.odo, s.dist, s.ev, s.fuel, s.move_s, s.city_dist, s.city_ev,
           l100, kmh, evp, cityp);
}

int main() {
    std::unordered_map<std::string,int> kidx;
    for (int i = 0; i < V_COUNT; i++) kidx[KEYS[i]] = i;

    char *buf = nullptr; size_t cap = 0; ssize_t len;
    bool first = true; double t0 = 0, prev_ts = 0;
    const double GAP_MS = 5000;
    uint8_t prev_rhn = 0; uint32_t prev_tank_ep = 0;
    long samples = 0; double log_dist = 0, prev_sp = NAN, cov_prev_ts = 0;  // parallel spd-integral

    host_time_set = true; host_epoch_ms = 0;

    while ((len = getline(&buf, &cap, stdin)) > 0) {
        char *save = nullptr;
        char *tok = strtok_r(buf, " \t\r\n", &save);
        if (!tok) continue;
        double ts = atof(tok);
        tok = strtok_r(nullptr, " \t\r\n", &save); int city = tok ? atoi(tok) : -1;
        tok = strtok_r(nullptr, " \t\r\n", &save); float fc = tok ? atof(tok) : 1.0f;

        if (first) { t0 = ts; host_epoch = (uint32_t)(ts / 1000.0); first = false; prev_ts = ts; }
        double now = ts - t0;
        bool gap = (ts - prev_ts) > GAP_MS;
        if (gap) {   // model a reboot: clear in-RAM integration state; slots + fuel_ref persist (NVS)
            derive_init = false; derive_boot_ms = 0; trap_sp = NAN; trap_fl = NAN;
            fuel_fin_prev = NAN; refuel_peak = 0; refuel_seen_ms = 0; fuel_lowcnt = 0; prev_sp = NAN;
        }
        prev_ts = ts;
        if (city >= 0 && city <= 2) city_state = (uint8_t)city;
        if (fc > 0.4f && fc < 2.5f) fuel_corr = fc;

        while ((tok = strtok_r(nullptr, " \t\r\n", &save))) {
            char *eq = strchr(tok, '='); if (!eq) continue; *eq = 0;
            auto it = kidx.find(tok); if (it == kidx.end()) continue;
            V[it->second] = (float)atof(eq + 1);
        }

        // coverage: parallel trapezoid spd-integral over logged (non-gap) samples, so we can tell
        // what fraction of the odo distance was actually logged (-> correct the integrated fuel).
        float sp = V[SPD];
        if (!gap && !std::isnan(sp) && !std::isnan(prev_sp)) {
            double dth = (ts - cov_prev_ts) / 3600000.0;
            double a = (prev_sp + sp) * 0.5; if (a > 0) log_dist += a * dth;
        }
        cov_prev_ts = ts; prev_sp = sp;

        compute_derived((uint32_t)now);
        samples++;

        if (rhist_n != prev_rhn || slot[1].epoch != prev_tank_ep) {
            uint8_t last = (uint8_t)((rhist_head + HISTN - 1) % HISTN);
            const TripSlot &r = rhist[last];
            printf("{\"event\":\"refuel\",\"epoch\":%u,\"odo\":%u,\"tank_dist\":%.2f,"
                   "\"tank_fuel\":%.3f,\"tank_ev\":%.2f,\"l100\":%.2f}\n",
                   r.epoch, r.odo, r.dist, r.fuel, r.ev, r.dist > 0.1 ? r.fuel / r.dist * 100.0 : 0.0);
            prev_rhn = rhist_n; prev_tank_ep = slot[1].epoch;
        }
    }

    double cover = boot_slot.dist > 0.1 ? log_dist / boot_slot.dist : 0.0;
    double fuel_corr_full = cover > 0.01 ? boot_slot.fuel / cover : boot_slot.fuel;
    printf("{\"summary\":1,\"samples\":%ld,\"refuels\":%u,\"odo_dist\":%.1f,\"logged_dist\":%.1f,"
           "\"coverage\":%.3f,\"fuel_logged\":%.3f,\"fuel_full_est\":%.3f,"
           "\"l100_logged\":%.2f,\"l100_full_est\":%.2f}\n",
           samples, rhist_n, boot_slot.dist, log_dist, cover, boot_slot.fuel, fuel_corr_full,
           boot_slot.dist > 0.1 ? boot_slot.fuel / boot_slot.dist * 100.0 : 0.0,
           boot_slot.dist > 0.1 ? fuel_corr_full / boot_slot.dist * 100.0 : 0.0);
    emit_slot("total", boot_slot);
    emit_slot("tank", slot[1]);
    return 0;
}

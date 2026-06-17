#pragma once
// Prius CAN parse-logika — TISZTA C++, semmi ESPHome-fuggoseg.
// Minden keplet a PriusChat GenIII Torque CSV-bol (verifikalt).
// A can0 kuldest es a szenzor-publikalast a YAML vegzi; ez a header
// csak dekodol es egy sajat ertektarba (prius::V) ir.

#include <vector>
#include <map>
#include <string>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <cstdio>

namespace prius {

struct Store {
  std::map<std::string, float> m;
  void set(const char *k, float v) { m[k] = v; }
  float get(const char *k) const {
    auto it = m.find(k);
    return it == m.end() ? NAN : it->second;
  }
};
inline Store V;

static inline float u16(const uint8_t *d, int i) { return (float)((d[i] << 8) | d[i + 1]); }

inline std::vector<uint8_t> tp_buf;
inline int  tp_len = 0;
inline bool tp_active = false;
inline uint16_t tp_src = 0;
inline uint32_t tp_started_ms = 0;

// Keres<->valasz sorositas: egyszerre csak EGY keres lehet a buszon. Amig az
// elozo keresre varunk valaszt (meg az elso keret/FF sem jott meg), ne kuldjunk
// ujabbat, kulonben egy masik ECU valasza elklappolhatja a most epulo multi-
// frame atvitelt (ez okozta a motor-ECU akadozasat a suru pollnal).
inline bool awaiting = false;        // kerest kuldtunk, valasz meg nem zarult le
inline uint16_t await_resp = 0;      // a vart valasz CAN ID-ja (= keres + 8)
inline uint32_t await_started = 0;

// DEBUG-szamlalok a motor-ECU (0x7E8) diagnozisahoz:
//   eng_ff = hany elso keret (FF) jott a motor-ECU-tol
//   eng_ok = hany 2101 valasz fejezodott be sikeresen
// Ha eng_ff >> eng_ok -> a CF-keretek vesznek el (RX-drop/timing), nem a session.
inline uint32_t eng_ff = 0, eng_ok = 0;

struct PollItem { uint16_t req; uint8_t mode; uint8_t pid; };
inline const PollItem POLL[] = {
  {0x7E0, 0x21, 0x01},
  {0x7E2, 0x01, 0x5B},
  {0x7E2, 0x21, 0x98}, {0x7E2, 0x21, 0x92}, {0x7E2, 0x21, 0x81},
  {0x7E2, 0x21, 0x87}, {0x7E2, 0x21, 0x95}, {0x7E2, 0x21, 0x61},
  {0x7E2, 0x21, 0x62}, {0x7E2, 0x21, 0x67}, {0x7E2, 0x21, 0x68},
  {0x7E2, 0x21, 0x70}, {0x7E2, 0x21, 0x71}, {0x7E2, 0x21, 0x74},
  {0x7E2, 0x21, 0x75}, {0x7E2, 0x21, 0x7D}, {0x7E2, 0x21, 0x01},
  {0x7C4, 0x21, 0x21}, {0x7C4, 0x21, 0x29}, {0x7C4, 0x21, 0x49},
  {0x7C4, 0x21, 0x4B}, {0x7C4, 0x21, 0x24},
  {0x7B0, 0x21, 0x03}, {0x7B0, 0x21, 0x47}, {0x7B0, 0x21, 0x07},
  // 2-es kor:
  {0x7E0, 0x21, 0x49}, {0x7E0, 0x21, 0x3C},          // motornyomatek, injektor
  {0x7E2, 0x21, 0x8E}, {0x7E2, 0x21, 0x9B},          // hutoventilator
  {0x7E2, 0x21, 0xE1}, {0x7E2, 0x21, 0xC1},          // hibakod-szam, model code
  {0x7C4, 0x21, 0x22}, {0x7C4, 0x21, 0x3C}, {0x7C4, 0x21, 0x53},  // klima
  {0x7C0, 0x21, 0x13}, {0x7C0, 0x21, 0x29}, {0x7C0, 0x21, 0x41},  // body
};
inline const int POLL_N = sizeof(POLL) / sizeof(POLL[0]);
inline int poll_i = 0;

inline void parse_payload(uint16_t resp_id, const std::vector<uint8_t> &p) {
  if (p.size() < 2) return;
  uint8_t mode = p[0], pid = p[1];
  const uint8_t *d = p.data() + 2;
  int dn = (int)p.size() - 2;

  if (resp_id == 0x7EA && mode == 0x41 && pid == 0x5B) {
    if (dn >= 1) V.set("soc", d[0] * 20.0f / 51.0f);
    return;
  }
  if (mode != 0x61) return;

  if (resp_id == 0x7E8 && pid == 0x01) {
    eng_ok++;                 // DEBUG: hany 2101 fejezodott be sikeresen
    V.set("eLen", (float)dn); // DEBUG: az utolso valasz hossza
    if (dn < 14) return;
    V.set("load", d[0] * 20.0f / 51.0f);
    V.set("maf", u16(d, 3) / 100.0f);
    V.set("map", (float)d[5]);
    V.set("iat", (float)d[6] - 40.0f);
    V.set("ct", (float)d[8] - 40.0f);
    V.set("rpm", u16(d, 9) / 4.0f);
    V.set("spd", (float)d[11]);
    V.set("run", u16(d, 12));
    return;
  }
  if (resp_id == 0x7E8 && pid == 0x49) {   // tenyleges motornyomatek
    if (dn >= 5) V.set("engNm", u16(d,3) - 32768.0f);
    return;
  }
  if (resp_id == 0x7E8 && pid == 0x3C) {   // injektor: ml (10x) + idotartam us
    if (dn >= 4) {
      V.set("injml", u16(d,0) * 2.047f / 65535.0f);
      V.set("injus", u16(d,2));
    }
    return;
  }

  if (resp_id == 0x7EA) {
    switch (pid) {
      case 0x01:
        if (dn < 21) return;
        V.set("amb", (float)d[3] - 40.0f);
        V.set("thr", d[11] * 20.0f / 51.0f);
        V.set("pedal", d[12] * 20.0f / 51.0f);
        V.set("vbat", u16(d, 19) / 1000.0f);
        return;
      case 0x61: if (dn>=5){ V.set("mg1t",(float)d[0]-40); V.set("mg1r",u16(d,3)-32768);} return;
      case 0x62: if (dn>=5){ V.set("mg2t",(float)d[0]-40); V.set("mg2r",u16(d,3)-32768);} return;
      case 0x67: if (dn>=2) V.set("mg1q", u16(d,0)/8.0f-4096); return;
      case 0x68: if (dn>=2) V.set("mg2q", u16(d,0)/8.0f-4096); return;
      case 0x70: if (dn>=1) V.set("inv1",(float)d[0]-40); return;
      case 0x71: if (dn>=1) V.set("inv2",(float)d[0]-40); return;
      case 0x74:
        if (dn < 9) return;
        V.set("btu",(float)d[0]-40); V.set("btl",(float)d[1]-40);
        V.set("vl", u16(d,5)/2.0f); V.set("vh", u16(d,7)/2.0f);
        return;
      case 0x75:
        if (dn < 4) return;
        V.set("wpRun", (d[0] & 0x10) ? 1 : 0);
        V.set("invwp", u16(d,1)); V.set("invct",(float)d[3]-40);
        return;
      case 0x7D: if (dn>=3) V.set("acw", d[2]*50.0f); return;
      case 0x81:
        if (dn < 28) return;
        for (int i = 0; i < 14; i++) {
          float v = u16(d, 2*i) * 79.99f / 65535.0f;
          char key[8]; snprintf(key, sizeof(key), "b%02d", i+1);
          V.set(key, v);
        }
        return;
      case 0x87:
        if (dn < 8) return;
        V.set("hvAir", u16(d,0)*255.9f/65535.0f-50);
        V.set("tb1", u16(d,2)*255.9f/65535.0f-50);
        V.set("tb2", u16(d,4)*255.9f/65535.0f-50);
        V.set("tb3", u16(d,6)*255.9f/65535.0f-50);
        return;
      case 0x92:
        if (dn < 15) return;
        V.set("bmin", u16(d,0)*79.99f/65535.0f);
        V.set("bmax", u16(d,3)*79.99f/65535.0f);
        V.set("blkD", ((float)((d[3]-d[0])*256 + d[4]-d[1]))*79.99f/65535.0f);
        V.set("weakB", (float)d[2]);
        V.set("tHot", u16(d,13));
        return;
      case 0x95: {
        if (dn < 14) return;
        uint8_t mx = 0;
        for (int i=0;i<14;i++) if (d[i]>mx) mx=d[i];
        V.set("maxR", (float)mx);
        return;
      }
      case 0x98:
        if (dn < 8) return;
        V.set("hvA", u16(d,0)/100.0f-327.68f);
        V.set("hvdis", d[2]/2.0f-64);
        V.set("hvchg", d[3]/2.0f-64);
        return;
      case 0x8E:   // akku-hutoventilator %
        if (dn >= 1) V.set("battFan", d[0]/2.0f);
        return;
      case 0x9B:   // ECU control mode + cooling fan mode
        if (dn >= 2) { V.set("ecuMode", d[0]); V.set("fanMode", d[1]); }
        return;
      case 0xE1:   // hibakodok szama
        if (dn >= 2) { V.set("dtcCur", d[0]); V.set("dtcHist", d[1]); }
        return;
    }
    return;
  }

  if (resp_id == 0x7CC) {
    switch (pid) {
      case 0x21: if (dn>=1) V.set("cabin", d[0]*63.75f/255.0f-6.5f); return;
      case 0x24: if (dn>=1) V.set("solar", (float)d[0]); return;
      case 0x29: if (dn>=1) V.set("setT", d[0]*15.0f/255.0f+17.5f); return;
      case 0x49: if (dn>=2) V.set("comp", u16(d,0)); return;
      case 0x4B: if (dn>=1) V.set("evap", d[0]*89.25f/255.0f-29.7f); return;
      case 0x22: if (dn>=1) V.set("acAmb", d[0]*89.25f/255.0f-23.3f); return;
      case 0x3C: if (dn>=1) V.set("blower", d[0]*31.0f/255.0f); return;
      case 0x53: if (dn>=1) V.set("acPress", d[0]*3.75105f/255.0f-0.45668f); return;
    }
    return;
  }

  if (resp_id == 0x7B8) {
    switch (pid) {
      case 0x03:
        if (dn < 4) return;
        V.set("wFR", d[0]*32.0f/25.0f); V.set("wFL", d[1]*32.0f/25.0f);
        V.set("wRR", d[2]*32.0f/25.0f); V.set("wRL", d[3]*32.0f/25.0f);
        { float fr=d[0]*32.0f/25.0f, fl=d[1]*32.0f/25.0f;
          if (fr+fl>20) V.set("wDif", 200.0f*fabsf(fr-fl)/(fr+fl)); }
        return;
      case 0x47:
        if (dn < 5) return;
        V.set("gLat", d[0]*50.02f/255.0f-25.11f);
        V.set("gFwd", d[1]*50.02f/255.0f-25.11f);
        V.set("steer", u16(d,3)/10.0f-3276.8f);
        // DEBUG: nyers bajtok d[0..7] -> a web UI-n lathato raw0..raw7
        for (int i=0; i<8 && i<dn; i++) {
          char k[8]; snprintf(k,sizeof(k),"raw%d",i);
          V.set(k, (float)d[i]);
        }
        return;
      case 0x07: if (dn>=1) V.set("brkP", d[0]/51.0f); return;
    }
    return;
  }

  if (resp_id == 0x7C8) {   // body ECU
    switch (pid) {
      case 0x13: if (dn>=1) V.set("bodyV", d[0]/10.0f); return;
      case 0x29: if (dn>=1) V.set("fuelIn", d[0]/2.0f); return;
      case 0x41: if (dn>=1) V.set("oilDist", d[0]*2514600.0f/15625.0f); return;
    }
    return;
  }
}

// Visszateres: ha >0, a YAML kuldjon flow control-t a (visszaadott - 8) ID-re.
inline uint16_t on_can_frame(uint16_t resp_id, const std::vector<uint8_t> &x, uint32_t now_ms) {
  if (x.empty()) return 0;
  uint8_t pci = x[0] >> 4;
  if (pci == 0x0) {
    uint8_t len = x[0] & 0x0F;
    if (len == 0 || (int)x.size() < 1+len) return 0;
    std::vector<uint8_t> p(x.begin()+1, x.begin()+1+len);
    parse_payload(resp_id, p);
    // egykeretes valasz (akar negativ 7F is): a keres lezarult
    if (resp_id == await_resp) awaiting = false;
    return 0;
  } else if (pci == 0x1) {
    if (resp_id == 0x7E8) eng_ff++;   // DEBUG: motor-ECU elso keret
    tp_len = ((x[0] & 0x0F) << 8) | x[1];
    tp_buf.assign(x.begin()+2, x.end());
    tp_active = true; tp_src = resp_id;
    tp_started_ms = now_ms;
    return resp_id;
  } else if (pci == 0x2 && tp_active && tp_src == resp_id) {
    tp_buf.insert(tp_buf.end(), x.begin()+1, x.end());
    if ((int)tp_buf.size() >= tp_len) {
      tp_buf.resize(tp_len);
      tp_active = false;
      parse_payload(resp_id, tp_buf);
      // multi-frame valasz teljesult: a keres lezarult
      if (resp_id == await_resp) awaiting = false;
    }
  }
  return 0;
}

// Visszateres: true ha van uj keres (out kitoltve), false ha varunk egy
// folyamatban levo ISO-TP atvitelre (ne kuldjunk kozbe).
inline bool next_request(uint16_t &req_id, std::vector<uint8_t> &out, uint32_t now_ms) {
  // ha epp egy multi-frame valaszt fogadunk, varjunk a befejezesere
  // (de max 120 ms, hogy egy elveszett frame ne akassza meg orokre)
  if (tp_active && (now_ms - tp_started_ms) < 120) {
    return false;
  }
  // teljes sorositas: amig az elozo keresre varunk (FF meg meg sem jott),
  // ne kuldjunk masikat - max 120 ms utan tovabblepunk (nem-valaszolo ECU)
  if (awaiting && (now_ms - await_started) < 120) {
    return false;
  }
  tp_active = false;
  awaiting = false;
  const PollItem &it = POLL[poll_i];
  poll_i = (poll_i + 1) % POLL_N;
  req_id = it.req;
  out = {0x02, it.mode, it.pid, 0, 0, 0, 0, 0};
  awaiting = true;
  await_resp = req_id + 8;     // OBD: a valasz ID = keres ID + 8
  await_started = now_ms;
  return true;
}

inline float ct_hist[13]; inline int ct_n = 0;
inline float dload = 0, drest = 0;

inline void compute_derived() {
  float maf = V.get("maf"), r = V.get("rpm");
  if (!std::isnan(maf))
    V.set("fuel", (r < 100) ? 0.0f : maf/14.7f*3600.0f/745.0f);

  float ct = V.get("ct"), rate = NAN;
  if (!std::isnan(ct)) {
    ct_hist[ct_n % 13] = ct;
    if (ct_n >= 3) {
      int oldest = (ct_n >= 13) ? (ct_n-12) : 0;
      rate = (ct - ct_hist[oldest % 13]) * 60.0f / ((ct_n<13?ct_n:12)*5.0f);
      V.set("ctR", rate);
    }
    ct_n++;
  }
  int wp = 0;
  if (!std::isnan(ct)) {
    if (ct>=105) wp=2; else if (ct>=100) wp=1;
    if (ct>88 && !std::isnan(rate)) { if (rate>=6) wp=2; else if (rate>=3 && wp<1) wp=1; }
    if (V.get("wpRun")>0.5f && ct>95 && !std::isnan(rate) && rate>=4 && wp<2) wp=2;
  }
  V.set("wpW", (float)wp);

  float dlt = V.get("blkD"), amps = V.get("hvA");
  if (!std::isnan(dlt)) {
    bool load = !std::isnan(amps) && fabsf(amps)>15;
    if (load) dload = fmaxf(dload*0.95f, dlt); else drest = fmaxf(drest*0.95f, dlt);
  }
  int cw = 0;
  if (dload>=0.60f || drest>=0.35f) cw=2;
  else if (dload>=0.35f || drest>=0.20f) cw=1;
  float mr = V.get("maxR");
  if (!std::isnan(mr) && mr>=40 && cw<2) cw = (mr>=60)?2:1;
  float t1=V.get("tb1"), t2=V.get("tb2"), t3=V.get("tb3");
  if (!std::isnan(t1)&&!std::isnan(t2)&&!std::isnan(t3)) {
    float tmax=fmaxf(t1,fmaxf(t2,t3)), tmin=fminf(t1,fminf(t2,t3));
    if (tmax>=50) cw=2; else if (tmax-tmin>8 && cw<1) cw=1;
  }
  V.set("cellW", (float)cw);
  V.set("eFF", (float)eng_ff);   // DEBUG: motor-ECU FF szamlalo
  V.set("eOK", (float)eng_ok);   // DEBUG: sikeres 2101 szamlalo
}

inline void emit_json(const char *door_cruise_extra) {
  static const char *KEYS[] = {
    "ct","rpm","spd","soc","load","maf","map","iat","thr","pedal","vbat","amb","run","fuel",
    "mg1t","mg1r","mg1q","mg2t","mg2r","mg2q","inv1","inv2","btu","btl","vl","vh","invct","invwp","acw",
    "hvA","hvdis","hvchg","bmin","bmax","blkD","weakB","maxR","tHot","hvAir","tb1","tb2","tb3",
    "b01","b02","b03","b04","b05","b06","b07","b08","b09","b10","b11","b12","b13","b14",
    "cabin","setT","comp","evap","solar",
    "wFL","wFR","wRL","wRR","wDif","gLat","gFwd","steer","brkP",
    "engNm","injml","injus","battFan","ecuMode","fanMode","dtcCur","dtcHist",
    "acAmb","blower","acPress","bodyV","fuelIn","oilDist",
    "ctR","wpRun","wpW","cellW",
    "eLen","eFF","eOK",               // DEBUG motor-ECU
    nullptr
  };
  char buf[2048]; int off = 0; const int cap = sizeof(buf);
  buf[off++] = '{';
  for (int i=0; KEYS[i]; i++) {
    float v = V.get(KEYS[i]);
    if (std::isnan(v)) off += snprintf(buf+off, cap-off, "\"%s\":null,", KEYS[i]);
    else off += snprintf(buf+off, cap-off, "\"%s\":%.2f,", KEYS[i], v);
  }
  off += snprintf(buf+off, cap-off, "%s}\n", door_cruise_extra ? door_cruise_extra : "");
  fwrite(buf, 1, off, stdout); fflush(stdout);
}

} // namespace prius

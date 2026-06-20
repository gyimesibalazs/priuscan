#pragma once
// IDF serial-OTA receiver running in a DEDICATED FreeRTOS TASK, using the USB-Serial/JTAG
// DRIVER API directly (not the VFS read()/main loop). The earlier loop-based receiver
// failed because read() stopped delivering bytes during the OTA (the esp_ota_begin erase
// + main-loop stalls starved the VFS console). A standalone task with the driver API is
// independent of the ESPHome loop, so the RX is serviced reliably. Transport is base64
// text LINES (binary is mangled by the console). Kept OUT of prius_parse.h.
//
// Protocol:  app "O<size>\n" -> ESP "K\n" (ready) ; per line "<b64>\n" -> "A\n" ; end "D\n"+reboot ; error "E\n"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/usb_serial_jtag.h"

inline volatile bool ota_active = false;   // gates main-loop console reads/emits
inline uint32_t ota_total = 0;
inline char ota_line[1400];
inline uint8_t ota_dbuf[1100];

inline int b64v(char c) {
  if (c >= 'A' && c <= 'Z') return c - 'A';
  if (c >= 'a' && c <= 'z') return c - 'a' + 26;
  if (c >= '0' && c <= '9') return c - '0' + 52;
  if (c == '+') return 62;
  if (c == '/') return 63;
  return -1;
}
inline int b64dec(const char *in, int len, uint8_t *out) {
  int o = 0, bits = 0, val = 0;
  for (int i = 0; i < len; i++) {
    int v = b64v(in[i]); if (v < 0) continue;
    val = (val << 6) | v; bits += 6;
    if (bits >= 8) { bits -= 8; out[o++] = (uint8_t) ((val >> bits) & 0xFF); }
  }
  return o;
}
inline void usj_put(const char *s, int len) {
  usb_serial_jtag_write_bytes((const uint8_t *) s, len, pdMS_TO_TICKS(500));
}

inline void prius_ota_task(void *arg) {
  // make sure the JTAG driver is available (no-op / harmless if ESPHome already installed it)
  usb_serial_jtag_driver_config_t cfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
  usb_serial_jtag_driver_install(&cfg);

  const esp_partition_t *part = esp_ota_get_next_update_partition(nullptr);
  esp_ota_handle_t h = 0;
  if (!part || esp_ota_begin(part, ota_total, &h) != ESP_OK) {
    usj_put("E\n", 2); ota_active = false; vTaskDelete(nullptr); return;
  }
  usj_put("K\n", 2);                            // ready (partition erased)

  uint32_t received = 0, idle = 0; int ln = 0; bool ok = true;
  uint8_t tmp[256];
  while (received < ota_total) {
    int r = usb_serial_jtag_read_bytes(tmp, sizeof(tmp), pdMS_TO_TICKS(100));
    if (r <= 0) { if (++idle > 600) { ok = false; break; } continue; }   // ~60 s overall idle -> abort
    idle = 0;
    for (int i = 0; i < r && received < ota_total; i++) {
      char c = (char) tmp[i];
      if (c == '\n') {
        int m = b64dec(ota_line, ln, ota_dbuf); ln = 0;
        if (m <= 0) continue;
        if (esp_ota_write(h, ota_dbuf, m) != ESP_OK) { ok = false; break; }
        received += (uint32_t) m;
        if (received < ota_total) usj_put("A\n", 2);
      } else if (c != '\r' && ln < (int) sizeof(ota_line) - 1) {
        ota_line[ln++] = c;
      }
    }
    if (!ok) break;
  }
  if (ok && received >= ota_total &&
      esp_ota_end(h) == ESP_OK && esp_ota_set_boot_partition(part) == ESP_OK) {
    usj_put("D\n", 2);
    vTaskDelay(pdMS_TO_TICKS(300));
    esp_restart();
  } else {
    if (h) esp_ota_abort(h);
    usj_put("E\n", 2);
    ota_active = false;
    vTaskDelete(nullptr);
  }
}

// Spawn the OTA task (called once from the YAML on the "O<size>" command).
inline void prius_ota_begin(uint32_t total) {
  if (ota_active) return;
  ota_total = total; ota_active = true;
  xTaskCreate(prius_ota_task, "prius_ota", 8192, nullptr, 5, nullptr);
}

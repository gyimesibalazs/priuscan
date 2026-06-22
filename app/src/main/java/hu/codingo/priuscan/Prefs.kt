package hu.codingo.priuscan

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** App settings. The HA pusher restarts when these change (listener in the service). */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences("priuscan", Context.MODE_PRIVATE)

    var haEnabled: Boolean
        get() = sp.getBoolean("ha_enabled", false)
        set(v) = sp.edit().putBoolean("ha_enabled", v).apply()

    var mqttHost: String
        get() = sp.getString("mqtt_host", "") ?: ""
        set(v) = sp.edit().putString("mqtt_host", v.trim()).apply()

    var mqttPort: Int
        get() = sp.getInt("mqtt_port", 1883)
        set(v) = sp.edit().putInt("mqtt_port", v).apply()

    var mqttUser: String
        get() = sp.getString("mqtt_user", "") ?: ""
        set(v) = sp.edit().putString("mqtt_user", v.trim()).apply()

    var mqttPass: String
        get() = sp.getString("mqtt_pass", "") ?: ""
        set(v) = sp.edit().putString("mqtt_pass", v).apply()

    var topicPrefix: String
        get() = sp.getString("topic_prefix", "priuscan") ?: "priuscan"
        set(v) = sp.edit().putString("topic_prefix", v.trim().trimEnd('/')).apply()

    /** Push interval in seconds. */
    var pushIntervalSec: Int
        get() = sp.getInt("push_interval", 30)
        set(v) = sp.edit().putInt("push_interval", v.coerceIn(2, 3600)).apply()

    /** Which values to show in the status bar (keys: ct, soc, rpm, cons). */
    var statusItems: Set<String>
        get() = sp.getStringSet("status_items", setOf("ct", "soc")) ?: setOf("ct")
        set(v) = sp.edit().putStringSet("status_items", v).apply()

    /** Local data logging (JSONL files) without HA. */
    var logEnabled: Boolean
        get() = sp.getBoolean("log_enabled", true)
        set(v) = sp.edit().putBoolean("log_enabled", v).apply()

    /** Drive dark/light mode from the car's solar (sun-load) sensor instead of the
     *  system setting. When on, also tries to set the system night mode device-wide. */
    var autoDarkCar: Boolean
        get() = sp.getBoolean("auto_dark_car", false)
        set(v) = sp.edit().putBoolean("auto_dark_car", v).apply()

    /** Last applied dark/light state — restored at startup so the right mode shows
     *  immediately (before the first ambient-light reading arrives). */
    var darkLast: Boolean
        get() = sp.getBoolean("dark_last", false)
        set(v) = sp.edit().putBoolean("dark_last", v).apply()

    /** Reference HV pack capacity (Ah) for the SoH/degradation calc. Default ~Prius
     *  6.5 Ah; set to the transplanted Prius-4 cell's rated capacity. */
    var batteryRefAh: Float
        get() = sp.getFloat("battery_ref_ah", 6.5f)
        set(v) = sp.edit().putFloat("battery_ref_ah", v.coerceIn(1f, 20f)).apply()

    /** Learned TPMS sensor IDs per wheel (key = Wheel.name, value = hex id), JSON-encoded. */
    var tpmsIds: Map<String, String>
        get() {
            val raw = sp.getString("tpms_ids", null) ?: return emptyMap()
            return try {
                val o = JSONObject(raw)
                o.keys().asSequence().associateWith { o.getString(it) }
            } catch (_: Exception) { emptyMap() }
        }
        set(v) = sp.edit().putString("tpms_ids", JSONObject(v).toString()).apply()

    /** The status-bar overlay font size (sp). */
    var statusFontSize: Float
        get() = sp.getFloat("status_font_size", 14f)
        set(v) = sp.edit().putFloat("status_font_size", v.coerceIn(8f, 48f)).apply()

    /** The status-bar overlay position (px). -1 = not set yet. */
    var statusX: Int
        get() = sp.getInt("status_x", -1)
        set(v) = sp.edit().putInt("status_x", v).apply()
    var statusY: Int
        get() = sp.getInt("status_y", -1)
        set(v) = sp.edit().putInt("status_y", v).apply()
}

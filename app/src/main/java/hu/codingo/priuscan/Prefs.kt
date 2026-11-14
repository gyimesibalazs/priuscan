package hu.codingo.priuscan

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** App settings. The HA pusher restarts when these change (listener in the service). */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences("priuscan", Context.MODE_PRIVATE)

    /** Frequently-written RUNTIME state lives in its own file. SharedPreferences rewrites the
     *  whole XML on every apply(), and `dark_last` flips right around ignition-off (parking in
     *  a dark garage) — on this head unit the power is CUT abruptly, and a torn write can lose
     *  the entire file. In a separate file the blast radius is one cosmetic flag, not every
     *  setting (the "auto dark switch resets after every restart" bug). */
    private val state: SharedPreferences = ctx.getSharedPreferences("priuscan_state", Context.MODE_PRIVATE)

    /** true = the MAIN settings file existed before but read back EMPTY now: it was lost to a
     *  torn write at a power cut. Settings shows a warning so it isn't a silent mystery. */
    val mainSettingsLost: Boolean

    init {
        val seenBefore = state.getBoolean("main_seen", false)
        mainSettingsLost = seenBefore && sp.all.isEmpty()
        if (sp.all.isNotEmpty() && !seenBefore) state.edit().putBoolean("main_seen", true).apply()
    }

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
    /** WireGuard tunnel name the app keeps up while HA is unreachable (the head-unit ROM
     *  force-stops background apps on every wake-up; the official WireGuard app accepts a
     *  SET_TUNNEL_UP intent when "Allow remote control apps" is on). Empty = off. */
    var wgTunnel: String
        get() = sp.getString("wg_tunnel", "vpn") ?: ""
        set(v) = sp.edit().putString("wg_tunnel", v.trim()).apply()

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
        get() = sp.getBoolean("auto_dark_car", true)   // default ON (and resilient: even a lost
        set(v) = sp.edit().putBoolean("auto_dark_car", v).apply()   // settings file keeps auto dark)

    /** Last applied dark/light state — restored at startup so the right mode shows
     *  immediately (before the first ambient-light reading arrives). */
    /** Last-known dark/light (startup theme seed). Written on EVERY ambient flip -> lives in
     *  the state file (see above); reads fall back to the old main-file key (migration). */
    var darkLast: Boolean
        get() = state.getBoolean("dark_last", sp.getBoolean("dark_last", false))
        set(v) = state.edit().putBoolean("dark_last", v).apply()

    /** Reference HV pack capacity (Ah) for the SoH/degradation calc. Default ~Prius
     *  6.5 Ah; set to the transplanted Prius-4 cell's rated capacity. */
    /** Spirit-level zero: the raw pitch / roll (deg) stored when the user zeroes on level ground. */
    var tiltZeroPitch: Float
        get() = sp.getFloat("tilt_zero_pitch", 0f)
        set(v) = sp.edit().putFloat("tilt_zero_pitch", v).apply()
    var tiltZeroRoll: Float
        get() = sp.getFloat("tilt_zero_roll", 0f)
        set(v) = sp.edit().putFloat("tilt_zero_roll", v).apply()

    var batteryRefAh: Float
        get() = sp.getFloat("battery_ref_ah", 6.5f)
        set(v) = sp.edit().putFloat("battery_ref_ah", v.coerceIn(1f, 20f)).apply()

    /** Oil-change interval for the due indicator (whichever is closer wins): km + months. */
    var oilKm: Int
        get() = sp.getInt("oil_km", 10000)
        set(v) = sp.edit().putInt("oil_km", v.coerceIn(1000, 50000)).apply()
    var oilMonths: Int
        get() = sp.getInt("oil_months", 12)
        set(v) = sp.edit().putInt("oil_months", v.coerceIn(1, 48)).apply()

    /** Home location for the auto "from-home" trip reset (NaN = not set). The app resets the
     *  home slot (R6) when the car departs this spot. Set from the current GPS in Settings. */
    var homeLat: Float
        get() = sp.getFloat("home_lat", Float.NaN)
        set(v) = sp.edit().putFloat("home_lat", v).apply()
    var homeLon: Float
        get() = sp.getFloat("home_lon", Float.NaN)
        set(v) = sp.edit().putFloat("home_lon", v).apply()

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

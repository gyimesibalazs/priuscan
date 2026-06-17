package hu.codingo.priuscan

import android.content.Context
import android.content.SharedPreferences

/** App-beallitasok. A HA pusher ujraindul, ha valtozik (listener a service-ben). */
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

    /** Push idokoz masodpercben. */
    var pushIntervalSec: Int
        get() = sp.getInt("push_interval", 30)
        set(v) = sp.edit().putInt("push_interval", v.coerceIn(2, 3600)).apply()

    /** Melyik ertekek menjenek ki a statuszsorba (kulcsok: ct, soc, rpm, cons). */
    var statusItems: Set<String>
        get() = sp.getStringSet("status_items", setOf("ct", "soc")) ?: setOf("ct")
        set(v) = sp.edit().putStringSet("status_items", v).apply()

    /** A statusz-csik overlay pozicioja (px). -1 = meg nincs beallitva. */
    var statusX: Int
        get() = sp.getInt("status_x", -1)
        set(v) = sp.edit().putInt("status_x", v).apply()
    var statusY: Int
        get() = sp.getInt("status_y", -1)
        set(v) = sp.edit().putInt("status_y", v).apply()
}

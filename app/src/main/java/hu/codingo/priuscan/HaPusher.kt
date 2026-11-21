package hu.codingo.priuscan

import android.content.Context
import android.content.Intent
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Home Assistant integration over MQTT:
 *
 *  - On connect it publishes HA MQTT Discovery configs (retained),
 *    so ALL sensors show up in HA automatically, under the "Prius CAN" device.
 *  - At the configured interval ONE batch goes out: the full state as a single
 *    JSON to the <prefix>/state topic; every HA sensor reads from this with a
 *    value_template.
 *  - If there is no connection (no internet in the car), the batches are buffered
 *    to a file together with their ts; on reconnect the backlog goes out to the
 *    <prefix>/backlog topic in chunks. (The HA state machine can't backdate,
 *    so the backlog is a separate topic - process it with automation/InfluxDB.)
 *  - The head-unit GPS (lat/lon/galt/gspd/gcrs/gacc) and the flattened current-tank trip
 *    slot (tk*) ride in the same state JSON; a device_tracker discovery reads the GPS.
 *  - LWT: <prefix>/status = offline.
 */
class HaPusher(private val ctx: Context, private val prefs: Prefs) {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var client: MqttClient? = null

    private val backlogFile: File get() = File(ctx.filesDir, "ha_backlog.jsonl")
    private val maxBacklogBytes = 2L * 1024 * 1024   // ~2 MB, beyond that we drop the oldest half

    fun start() {
        if (running || !prefs.haEnabled || prefs.mqttHost.isBlank()) return
        running = true
        worker = Thread {
            while (running) {
                try { tick() } catch (_: Exception) {}
                val interval = prefs.pushIntervalSec * 1000L
                try { Thread.sleep(interval) } catch (_: InterruptedException) { break }
            }
            disconnect()
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    fun restart() { stop(); start() }

    // ---------------- main loop ----------------

    private fun tick() {
        val state = CanService.state.value
        if (state.values.isEmpty()) return
        val payload = buildPayload(state)

        if (ensureConnected()) {
            try {
                flushBacklog()
                publish("${prefs.topicPrefix}/state", payload.toString(), retained = true)
            } catch (_: Exception) {
                buffer(payload)
                disconnect()
            }
        } else {
            buffer(payload)
            nudgeVpn()
        }
    }

    private var lastNudge = 0L
    /** HA unreachable -> ask the WireGuard app to bring the tunnel up (at most once a minute).
     *  The head-unit ROM force-stops non-whitelisted background apps on every wake-up and the
     *  always-on VPN restart is cancelled with it; the official app honours this intent when
     *  "Allow remote control apps" is enabled. FLAG_INCLUDE_STOPPED_PACKAGES: a force-stopped
     *  app is in the "stopped" state and would not receive the broadcast otherwise. */
    private fun nudgeVpn() {
        val tunnel = prefs.wgTunnel
        if (tunnel.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastNudge < 60_000) return
        lastNudge = now
        try {
            ctx.sendBroadcast(Intent("com.wireguard.android.action.SET_TUNNEL_UP")
                .setPackage("com.wireguard.android")
                .putExtra("tunnel", tunnel)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        } catch (_: Exception) {}
    }

    private fun buildPayload(s: CanState): JSONObject {
        val o = JSONObject()
        o.put("ts", s.ts / 1000)   // unix seconds
        for ((k, v) in s.values) o.put(k, v)
        // head-unit GPS (same keys as the JSONL log) -> HA device_tracker + attributes
        CanService.gps.value?.let { loc ->
            o.put("lat", loc.latitude).put("lon", loc.longitude).put("galt", loc.altitude)
             .put("gspd", loc.speed * 3.6).put("gcrs", loc.bearing).put("gacc", loc.accuracy)
        }
        // current-tank trip slot (firmware-owned); the "slots" array itself is not numeric, so
        // CanState drops it -> flatten the tank entry into plain keys for the HA sensors
        CanService.tripSlots.value.getOrNull(TANK_SLOT)?.let { t ->
            o.put("tkDist", t.dist).put("tkFuel", t.fuel).put("tkAvg", t.avgCons).put("tkEv", t.ev)
             .put("tkEvKwh", t.evKwh).put("tkEvCons", t.evKwhPer100).put("tkRegen", t.regenKwh)
             .put("tkRegenKm", t.regenKm).put("tkCity", t.cityDist)
        }
        return o
    }

    // ---------------- MQTT ----------------

    private fun ensureConnected(): Boolean {
        if (client?.isConnected == true) return true
        return try {
            // if the host is already a full URI (tcp:// ssl:// ws:// wss://), use it as-is;
            // otherwise tcp://host:port
            val h = prefs.mqttHost
            val uri = if (h.contains("://")) h else "tcp://$h:${prefs.mqttPort}"
            val c = MqttClient(uri, "priuscan-headunit", MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5
                if (prefs.mqttUser.isNotBlank()) {
                    userName = prefs.mqttUser
                    password = prefs.mqttPass.toCharArray()
                }
                setWill("${prefs.topicPrefix}/status", "offline".toByteArray(), 1, true)
            }
            c.connect(opts)
            client = c
            publish("${prefs.topicPrefix}/status", "online", retained = true)
            publishDiscovery()
            true
        } catch (_: Exception) {
            client = null
            false
        }
    }

    private fun disconnect() {
        try { client?.disconnect() } catch (_: Exception) {}
        client = null
    }

    private fun publish(topic: String, payload: String, retained: Boolean = false) {
        client?.publish(topic, payload.toByteArray(), 1, retained)
    }

    // ---------------- HA MQTT Discovery ----------------

    private fun publishDiscovery() {
        val device = JSONObject()
            .put("identifiers", JSONArray().put("priuscan"))
            .put("name", "Prius CAN")
            .put("manufacturer", "Codingo")
            .put("model", "ESP32-C3 OBD bridge")
            .put("sw_version", (CanService.fwRunning.value ?: 0).let { "fw ${it / 100}.${it % 100}" })

        fun sensorConfig(key: String, name: String, unit: String, template: String) {
            val cfg = JSONObject()
                .put("name", name)
                .put("unique_id", "priuscan_$key")
                .put("state_topic", "${prefs.topicPrefix}/state")
                .put("value_template", template)
                .put("availability_topic", "${prefs.topicPrefix}/status")
                .put("device", device)
            if (unit.isNotEmpty()) cfg.put("unit_of_measurement", unit)
            // long-term statistics (hourly min/max/mean kept past the recorder purge) need a
            // state_class; only continuous quantities get one, masks/flags/codes stay plain
            stateClass(key, unit)?.let { cfg.put("state_class", it) }
            deviceClass(key, unit)?.let { cfg.put("device_class", it) }
            publish("homeassistant/sensor/priuscan_$key/config", cfg.toString(), retained = true)
        }

        for ((_, fields) in Fields.groups) {
            for (f in fields) {
                sensorConfig(f.key, ctx.getString(f.labelRes), f.unit, "{{ value_json.${f.key} }}")
            }
        }
        // state-like fields that are not in the Fields list (HA entity identity is the
        // topic/key, so the localized friendly name is safe to change with locale)
        sensorConfig("range", ctx.getString(R.string.f_range), "km", "{{ value_json.range }}")
        sensorConfig("door", ctx.getString(R.string.ha_door), "", "{{ value_json.door }}")
        sensorConfig("cruise", ctx.getString(R.string.ha_cruise), "", "{{ value_json.cruise }}")
        sensorConfig("wpRun", ctx.getString(R.string.ha_wp_running), "", "{{ value_json.wpRun }}")
        sensorConfig("wpW", ctx.getString(R.string.ha_wp_warn), "", "{{ value_json.wpW }}")
        sensorConfig("cellW", ctx.getString(R.string.ha_cell_warn), "", "{{ value_json.cellW }}")
        for (i in 1..14) {
            sensorConfig("b%02d".format(i), ctx.getString(R.string.ha_block_n, i), "V",
                "{{ value_json.b%02d }}".format(i))
        }
        // entities this app used to announce: an empty retained config removes them from HA
        for (key in REMOVED) publish("homeassistant/sensor/priuscan_$key/config", "", retained = true)
        // firmware keys that are not in Fields.groups (dashboard-only / header / status)
        for ((key, nameRes, unit) in EXTRA) sensorConfig(key, ctx.getString(nameRes), unit, "{{ value_json.$key }}")
        // GPS: a device_tracker fed from the state JSON's lat/lon (the state itself is reset to
        // None so HA derives home/away from the coordinates + zones)
        val trk = JSONObject()
            .put("name", ctx.getString(R.string.ha_gps))
            .put("unique_id", "priuscan_gps")
            .put("state_topic", "${prefs.topicPrefix}/state")
            .put("value_template", "None")
            .put("payload_reset", "None")
            .put("source_type", "gps")
            .put("json_attributes_topic", "${prefs.topicPrefix}/state")
            .put("json_attributes_template",
                "{% if value_json.lat is defined %}{{ {'latitude': value_json.lat, 'longitude': value_json.lon, " +
                "'gps_accuracy': value_json.gacc, 'altitude': value_json.galt, 'speed': value_json.gspd, " +
                "'course': value_json.gcrs} | tojson }}{% else %}{}{% endif %}")
            .put("availability_topic", "${prefs.topicPrefix}/status")
            .put("device", device)
        publish("homeassistant/device_tracker/priuscan_gps/config", trk.toString(), retained = true)
    }

    /** HA state_class: "measurement" for continuous readings, "total_increasing" for the odometer,
     *  "total" for the per-tank accumulators (they reset at each refuel), null for discrete values. */
    private fun stateClass(key: String, unit: String): String? = when {
        key == "odo" -> "total_increasing"
        key.startsWith("tk") -> "total"
        key in DISCRETE -> null
        unit.isNotEmpty() || key == "wz" -> "measurement"
        else -> null
    }

    private fun deviceClass(key: String, unit: String): String? = when (unit) {
        "°C" -> "temperature"
        "V" -> "voltage"
        "A" -> "current"
        "kW", "W" -> "power"
        "kWh" -> "energy"
        "km" -> "distance"
        "km/h" -> "speed"
        "kPa", "MPa" -> "pressure"
        "L" -> "volume"
        "s" -> "duration"
        "%" -> if (key == "soc") "battery" else null
        else -> null
    }

    private companion object {
        /** keys whose value is a code / bitmask / flag -> no statistics (would be meaningless) */
        val DISCRETE = setOf("gear", "ev", "city", "turn", "lights", "dtcCur", "dtcHist", "fw",
            "door", "cruise", "wpRun", "wpW", "cellW", "weakB", "tHot", "solar", "blower", "cwL",
            "wblk", "run")
        /** dropped keys ("pedal": the raw accelerator sensor, a squeezed copy of gasB) */
        val REMOVED = listOf("pedal")
        const val TANK_SLOT = 2   // slots[]: 0 boot, 1 lifetime, 2 tank, 3 oil, 4-6 A/B/C, 7 home
        /** key -> HA friendly name -> unit; keys the firmware emits but Fields.groups don't list */
        val EXTRA = listOf(
            Triple("odo", R.string.f_odo, "km"), Triple("fuelIn", R.string.f_fuelIn, "%"),
            Triple("fuelL", R.string.ha_fuelL, "L"), Triple("fuelGm", R.string.ha_fuelGm, "L"),
            Triple("gear", R.string.ha_gear, ""), Triple("ev", R.string.ha_ev, ""),
            Triple("city", R.string.ha_city, ""), Triple("hsi", R.string.ha_hsi, "%"),
            Triple("mg1Pow", R.string.ha_mg1Pow, "kW"), Triple("mg2Pow", R.string.ha_mg2Pow, "kW"),
            Triple("vbat", R.string.f_vbat, "V"), Triple("amb", R.string.f_amb, "°C"),
            Triple("ambL", R.string.f_ambL, ""), Triple("setSpd", R.string.f_setSpd, "km/h"),
            Triple("turn", R.string.f_turn, ""), Triple("lights", R.string.ha_lights, ""),
            Triple("dtcCur", R.string.f_dtcCur, ""), Triple("dtcHist", R.string.f_dtcHist, ""),
            Triple("fw", R.string.ha_fw, ""),
            Triple("tkDist", R.string.ha_tk_dist, "km"), Triple("tkFuel", R.string.ha_tk_fuel, "L"),
            Triple("tkAvg", R.string.ha_tk_avg, "l/100km"), Triple("tkEv", R.string.ha_tk_ev, "km"),
            Triple("tkEvKwh", R.string.ha_tk_evkwh, "kWh"), Triple("tkEvCons", R.string.ha_tk_evcons, "kWh/100km"),
            Triple("tkRegen", R.string.ha_tk_regen, "kWh"), Triple("tkRegenKm", R.string.ha_tk_regenkm, "km"),
            Triple("tkCity", R.string.ha_tk_city, "km"),
        )
    }

    // ---------------- offline buffer ----------------

    @Synchronized
    private fun buffer(payload: JSONObject) {
        try {
            if (backlogFile.length() > maxBacklogBytes) trimBacklog()
            backlogFile.appendText(payload.toString() + "\n")
        } catch (_: Exception) {}
    }

    private fun trimBacklog() {
        // the oldest half gets thrown away
        val lines = backlogFile.readLines()
        backlogFile.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    private fun flushBacklog() {
        if (!backlogFile.exists() || backlogFile.length() == 0L) return
        val lines = backlogFile.readLines().filter { it.isNotBlank() }
        // in chunks of 50, as a JSON array
        var i = 0
        while (i < lines.size) {
            val chunk = JSONArray()
            for (j in i until minOf(i + 50, lines.size)) {
                try { chunk.put(JSONObject(lines[j])) } catch (_: Exception) {}
            }
            publish("${prefs.topicPrefix}/backlog", chunk.toString())
            i += 50
        }
        backlogFile.delete()
    }
}

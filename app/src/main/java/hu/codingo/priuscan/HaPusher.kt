package hu.codingo.priuscan

import android.content.Context
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
        }
    }

    private fun buildPayload(s: CanState): JSONObject {
        val o = JSONObject()
        o.put("ts", s.ts / 1000)   // unix seconds
        for ((k, v) in s.values) o.put(k, v)
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
            .put("model", "ESP32-C6 OBD bridge")

        fun sensorConfig(key: String, name: String, unit: String, template: String) {
            val cfg = JSONObject()
                .put("name", name)
                .put("unique_id", "priuscan_$key")
                .put("state_topic", "${prefs.topicPrefix}/state")
                .put("value_template", template)
                .put("availability_topic", "${prefs.topicPrefix}/status")
                .put("device", device)
            if (unit.isNotEmpty()) cfg.put("unit_of_measurement", unit)
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

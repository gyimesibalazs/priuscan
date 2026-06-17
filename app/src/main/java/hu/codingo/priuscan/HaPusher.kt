package hu.codingo.priuscan

import android.content.Context
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Home Assistant integracio MQTT-n keresztul:
 *
 *  - Csatlakozaskor HA MQTT Discovery configokat publikal (retained),
 *    igy az OSSZES szenzor magatol megjelenik HA-ban, "Prius CAN" device alatt.
 *  - A beallitott idokozonkent EGY batch megy ki: a teljes allapot egyetlen
 *    JSON-kent a <prefix>/state topicra; minden HA szenzor value_template-tel
 *    ebbol olvas.
 *  - Ha nincs kapcsolat (nincs net az autoban), a batch-ek ts-sel egyutt
 *    fajlba pufferelodnak; ujracsatlakozaskor a backlog a <prefix>/backlog
 *    topicra megy ki chunkokban. (A HA state machine nem tud visszadatumozni,
 *    ezert a backlog kulon topic - automatizmussal/InfluxDB-vel dolgozhato fel.)
 *  - LWT: <prefix>/status = offline.
 */
class HaPusher(private val ctx: Context, private val prefs: Prefs) {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var client: MqttClient? = null

    private val backlogFile: File get() = File(ctx.filesDir, "ha_backlog.jsonl")
    private val maxBacklogBytes = 2L * 1024 * 1024   // ~2 MB, utana a legregebbi felet dobjuk

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

    // ---------------- fo ciklus ----------------

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
        o.put("ts", s.ts / 1000)   // unix masodperc
        for ((k, v) in s.values) o.put(k, v)
        return o
    }

    // ---------------- MQTT ----------------

    private fun ensureConnected(): Boolean {
        if (client?.isConnected == true) return true
        return try {
            // ha a host mar teljes URI (tcp:// ssl:// ws:// wss://), ugy hasznaljuk;
            // kulonben tcp://host:port
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
                sensorConfig(f.key, f.label, f.unit, "{{ value_json.${f.key} }}")
            }
        }
        // allapot-jellegu mezok, amik nincsenek a Fields listaban
        sensorConfig("door", "Ajtó maszk", "", "{{ value_json.door }}")
        sensorConfig("cruise", "Tempomat", "", "{{ value_json.cruise }}")
        sensorConfig("wpRun", "Vízpumpa jár", "", "{{ value_json.wpRun }}")
        sensorConfig("wpW", "Vízpumpa warning", "", "{{ value_json.wpW }}")
        sensorConfig("cellW", "HV cella warning", "", "{{ value_json.cellW }}")
        for (i in 1..14) {
            sensorConfig("b%02d".format(i), "HV blokk %02d".format(i), "V",
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
        // a legregebbi fele megy a kukaba
        val lines = backlogFile.readLines()
        backlogFile.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    private fun flushBacklog() {
        if (!backlogFile.exists() || backlogFile.length() == 0L) return
        val lines = backlogFile.readLines().filter { it.isNotBlank() }
        // 50-es chunkokban, JSON tombkent
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

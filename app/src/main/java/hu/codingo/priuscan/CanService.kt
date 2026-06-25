package hu.codingo.priuscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Always-running foreground service:
 *  - USB serial read + reconnect
 *  - JSON parse -> StateFlow (the UI feeds off this)
 *  - status strip: coolant temperature as a dynamic icon
 *  - warning/alert: sound + pop-up overlay
 *  - open door: Prius drawing overlay that slides in from the top
 */
enum class FlashState { IDLE, FLASHING, DONE, ERROR }

class CanService : Service() {

    companion object {
        val state = MutableStateFlow(CanState.EMPTY)
        // connected = true ONLY when real PriusCAN data arrives (opening the port is not enough)
        val connected = MutableStateFlow(false)
        // which USB device we are bound to (product name / VID:PID / device name)
        val deviceInfo = MutableStateFlow<String?>(null)

        // ---- TPMS (a second serial device: CH340 @ 19200, binary 55 AA frames) ----
        val tpms = MutableStateFlow<Map<Wheel, TireReading>>(emptyMap())
        val tpmsConnected = MutableStateFlow(false)
        val tpmsDevice = MutableStateFlow<String?>(null)
        val tpmsIds = MutableStateFlow<Map<Wheel, String>>(emptyMap())
        // pairing target wheel (set by the UI), cleared by the reader on success/stop
        val tpmsPairing = MutableStateFlow<Wheel?>(null)
        private val tpmsCmdQueue = ConcurrentLinkedQueue<ByteArray>()

        // ---- GPS (head-unit location, via LocationManager) ----
        val gps = MutableStateFlow<Location?>(null)
        // ---- belterület geofence: true=built-up area, false=open road, null=unknown/no fix ----
        val city = MutableStateFlow<Boolean?>(null)

        // ---- Dark mode driven by the car's solar (sun-load) sensor ----
        val carDark = MutableStateFlow(false)   // true = dark (low ambient light)

        // ---- Trip slots: firmware-owned, the app only displays. The regular line carries the 8
        // live slots [boot, lifetime, tank, oil, A, B, C, home]; histories fetched on demand.
        val tripSlots = MutableStateFlow<List<TripSlot>>(emptyList())   // 8 live slots
        val refuelHist = MutableStateFlow<List<TripSlot>>(emptyList())  // <- "H"
        val oilHist = MutableStateFlow<List<TripSlot>>(emptyList())     // <- "HO"
        val histCount = MutableStateFlow(0 to 0)                        // (refuel, oil) entry counts
        fun fetchRefuelHistory() = sendCommand("H")
        fun fetchOilHistory() = sendCommand("HO")
        /** Reset a slot: 2=Oil(=oil change) 3=A 4=B 5=C 6=Home. (boot/lifetime/tank not here) */
        fun resetSlot(cmd: Int) { if (cmd in 2..6) sendCommand("R$cmd") }
        /** Copy a live trip INTO a user slot: dst 3=A/4=B/5=C, src 'B'=since-boot 'H'=from-home. */
        fun copySlot(dst: Int, src: Char) { if (dst in 3..5 && (src == 'B' || src == 'H')) sendCommand("C$dst$src") }
        /** Per-block internal resistance (mΩ), fetched on demand ("B"); 14 values, NaN = not ready. */
        val blockR = MutableStateFlow<List<Float>>(emptyList())
        fun fetchBlockR() = sendCommand("B")

        fun mergeLastRefuel() = sendCommand("M")   // merge the last refuel-history entry into the tank

        // ---- Firmware over-the-USB flashing ----
        const val BUNDLED_FW = 343                        // bundled firmware version (3.43)
        /** Format an encoded version (>=100 -> major.minor, else plain). */
        fun fmtFw(v: Int): String = if (v >= 100) "${v / 100}.${v % 100}" else "$v"
        val fwRunning = MutableStateFlow<Int?>(null)      // version reported by the ESP ("fw")
        val flashState = MutableStateFlow(FlashState.IDLE)
        val flashProgress = MutableStateFlow(0)           // 0..100
        val flashMsg = MutableStateFlow("")
        @Volatile private var pendingFlash = false
        /** True when the bundled firmware is newer than what the ESP runs. */
        val updateAvailable get() = fwRunning.value?.let { it < BUNDLED_FW } ?: false
        /** Ask the IO loop to reboot the ESP into download mode and flash it. */
        fun requestFlash() { if (flashState.value == FlashState.IDLE) pendingFlash = true }

        // ---- App-controlled CAN dump (session-only, never auto-starts) ----
        val dumpActive = MutableStateFlow(false)
        val dumpBytes = MutableStateFlow(0L)            // live dump-file size
        val dumpFile = MutableStateFlow<String?>(null)  // path of the finished file (to share)
        private val cmdQueue = ConcurrentLinkedQueue<ByteArray>()   // T<unix> / D1 / D0 to the ESP

        /** Enqueue a text command (newline added); the reader thread writes it to the ESP. */
        fun sendCommand(s: String) { cmdQueue.offer((s + "\n").toByteArray()) }

        /** Toggle the ESP CAN dump. unknown=false -> FULL dump (every frame 0x000-0x7FF + dedup);
         *  unknown=true -> D2 (only frames we don't already decode, for discovery). */
        fun setDump(on: Boolean, unknown: Boolean = false) {
            if (on) {
                dumpBytes.value = 0L; dumpFile.value = null; dumpActive.value = true
                sendCommand(if (unknown) "D2" else "D1 000 7FF")
            } else { sendCommand("D0"); dumpActive.value = false }
        }

        /** Enqueue a raw TPMS command frame; the reader thread sends it. */
        fun sendTpmsCommand(frame: ByteArray) { tpmsCmdQueue.offer(frame) }
        fun startTpmsPairing(w: Wheel) { tpmsPairing.value = w; sendTpmsCommand(Tpms.pair(w.pos)) }
        fun stopTpmsPairing() { sendTpmsCommand(Tpms.PAIR_STOP); tpmsPairing.value = null }

        private const val CHANNEL = "priuscan"
        private const val NOTIF_ID = 1
        // lenient JSON recovery: matches "key":number|null, ignoring malformed segments
        private val FIELD_RE = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?|null)")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CanService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlays: OverlayManager
    private lateinit var notifMgr: NotificationManager
    private lateinit var prefs: Prefs
    private lateinit var haPusher: HaPusher

    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // saving the overlay position (drag) must NOT restart anything, otherwise
            // it snaps back on every release; only a reset (both -1) repositions it
            when {
                // overlay font size/color: just restyle, do not restart the pusher
                key == "status_font_size" -> main.post { overlays.refreshStatusStyle() }
                // saving the position (drag) must NOT restart anything; only a reset (-1/-1) repositions
                key == "status_x" || key == "status_y" -> {
                    if (prefs.statusX < 0 && prefs.statusY < 0) main.post { overlays.resetStatusView() }
                }
                else -> {
                    haPusher.restart()
                    main.post { refreshNotifications() }
                }
            }
        }

    @Volatile private var running = true
    private var ioThread: Thread? = null
    private var tpmsThread: Thread? = null
    private val tb = ArrayList<Int>()   // TPMS byte accumulator (reader thread only)

    @Volatile private var lastLoc: Location? = null   // latest GPS fix (for log merge)
    private var gpsRunning = false
    @Volatile private var inCity: Boolean? = null     // last geofence verdict (to detect flips)
    private var lastHlWarn = 0L                        // last open-road headlight warning (ms)
    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { lastLoc = loc; gps.value = loc; updateGeofence(loc) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // belterület geofence: on each fix, look up the .bgf set; on a city<->road FLIP update the UI
    // flag and tell the firmware ("G1"=belterület / "G0"=országút -> it splits city km / city-EV km).
    private fun updateGeofence(loc: Location) {
        if (!BelteruletGeofence.ready) return
        val nowCity = BelteruletGeofence.cityState(loc.latitude, loc.longitude)  // true/false/null
        if (nowCity != inCity) {
            inCity = nowCity
            city.value = nowCity
            // G1=belterület, G2=országút, G0=unknown (not covered) -> the FW only counts city km on G1
            sendCommand(when (nowCity) { true -> "G1"; false -> "G2"; else -> "G0" })
        }
    }

    private fun startGps() {
        if (gpsRunning) return
        if (!hasLocPermission()) return
        Thread { BelteruletGeofence.init(applicationContext) }.start()   // load the geofence .bgf set once
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, mainLooper)
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLoc = it; gps.value = it }
            }
            gpsRunning = true
        } catch (_: SecurityException) {}
    }

    // Alert gate: a level only fires if it persists for at least HOLD_MS
    // (a momentary spike - e.g. a block delta during acceleration - does not fire), and
    // it does not repeat at the same level. It re-arms once the level drops to 0; for a
    // higher level (1->2) it fires again after the new level is held.
    private class WarnGate(private val holdMs: Long, private val cooldownMs: Long) {
        private var armed = 0
        private var since = 0L
        private var lastFire = 0L
        private var lastLevel = 0
        /** The level to alert on, or 0 if no alert is needed right now. */
        fun update(level: Int, now: Long): Int {
            if (level <= 0) { armed = 0; since = 0L; return 0 }
            if (level <= armed) { since = 0L; return 0 }
            if (since == 0L) { since = now; return 0 }
            if (now - since < holdMs) return 0
            armed = level; since = 0L
            // rate-limit: suppress repeats within the cooldown unless the level ESCALATED
            // (a higher level always alerts) -> stops repeated nuisance alarms while
            // still letting a genuine 1->2 through immediately.
            if (level <= lastLevel && now - lastFire < cooldownMs) return 0
            lastFire = now; lastLevel = level
            return level
        }
    }
    private val wpGate = WarnGate(4000L, 300_000L)    // 4 s hold, 5 min re-alert cooldown
    private val cellGate = WarnGate(4000L, 300_000L)

    private val logger by lazy { DataLogger(this) }
    private val dumpLogger by lazy { DumpLogger(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // apply the last-known dark/light mode immediately at startup (before the first
        // ambient-light reading) so the right theme shows from the very first frame
        carDark.value = prefs.darkLast
        if (prefs.autoDarkCar) applyNightMode(prefs.darkLast)
        overlays = OverlayManager(this, prefs)
        notifMgr = getSystemService(NotificationManager::class.java)
        notifMgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Prius CAN", NotificationManager.IMPORTANCE_LOW)
        )
        goForeground()
        haPusher = HaPusher(this, prefs)
        haPusher.start()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        // restore learned TPMS ids for display
        tpmsIds.value = prefs.tpmsIds.mapNotNull { (k, v) ->
            Wheel.values().firstOrNull { it.name == k }?.let { it to v }
        }.toMap()
        startIoLoop()
        startTpmsLoop()
        startGps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()   // re-assert FGS type (may now include location) + restart GPS
        startGps()       // re-attempt after location permission may have been granted
        return START_STICKY
    }

    private fun hasLocPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** startForeground with ONLY the FGS types we actually hold (avoids the API-34
     *  crash when the manifest declares location but the permission isn't granted). */
    private fun goForeground() {
        val notif = buildNotification(null, null, null, primary = true)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        running = false
        ioThread?.interrupt()
        tpmsThread?.interrupt()
        try { getSystemService(LocationManager::class.java)?.removeUpdates(locListener) } catch (_: Exception) {}
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        haPusher.stop()
        overlays.removeAll()
        super.onDestroy()
    }

    // ---------------- serial read loop ----------------

    private fun startIoLoop() {
        if (ioThread?.isAlive == true) return
        ioThread = Thread {
            val link = SerialLink(this)
            val buf = ByteArray(4096)
            val line = StringBuilder()
            // devices that could be opened but did NOT send PriusCAN data
            // (e.g. another ESP / TPMS) - we skip these in this round
            val rejected = HashSet<Int>()
            while (running) {
                val opened = link.open(rejected)
                if (opened == null) {
                    connected.value = false
                    deviceInfo.value = null
                    rejected.clear()     // new round: give everyone a chance again
                    Thread.sleep(3000)   // retry until the ESP comes up
                    continue
                }
                val port = opened.port
                var valid = false
                val openedAt = System.currentTimeMillis()
                var lastTimeWrite = 0L   // push head-unit wall-clock time to the ESP
                line.setLength(0)
                try {
                    while (running) {
                        val n = port.read(buf, 500)
                        for (i in 0 until n) {
                            val c = buf[i].toInt().toChar()
                            if (c == '\n') {
                                if (handleLine(line.toString()) && !valid) {
                                    // real PriusCAN data -> THIS is the right device
                                    valid = true
                                    connected.value = true
                                    deviceInfo.value = link.describe(opened.device)
                                    // re-arm after a finished update once the ESP is back
                                    if (flashState.value == FlashState.DONE || flashState.value == FlashState.ERROR)
                                        flashState.value = FlashState.IDLE
                                }
                                line.setLength(0)
                            } else if (line.length < 4096) {
                                line.append(c)
                            }
                        }
                        // if no valid PriusCAN line arrived within 4 s, this is not
                        // our device -> reject it and try the next one
                        if (!valid && System.currentTimeMillis() - openedAt > 4000) {
                            rejected.add(opened.device.deviceId)
                            break
                        }
                        // push the head-unit wall-clock time to the ESP every 30 s
                        val nowMs = System.currentTimeMillis()
                        if (valid && nowMs - lastTimeWrite > 30_000L) {
                            lastTimeWrite = nowMs
                            sendCommand("T${nowMs / 1000}")
                        }
                        // drain the command queue (time + dump on/off) to the ESP
                        while (true) {
                            val c = cmdQueue.poll() ?: break
                            try { port.write(c, 200) } catch (_: Exception) {}
                        }
                        // flash requested -> stream the bundled image via serial OTA (same port)
                        if (pendingFlash && valid) {
                            pendingFlash = false
                            flashState.value = FlashState.FLASHING
                            doSerialOta(port)     // streams image; sets DONE/ERROR; ESP reboots on success
                            break                 // reconnect to the rebooted ESP
                        }
                    }
                } catch (_: Exception) {
                    // unplugged USB / error -> reconnect
                } finally {
                    try { port.close() } catch (_: Exception) {}
                    connected.value = false
                    deviceInfo.value = null
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------- TPMS serial read loop (second device, CH340 @ 19200) ----------------

    private fun startTpmsLoop() {
        if (tpmsThread?.isAlive == true) return
        tpmsThread = Thread {
            val link = TpmsLink(this)
            val buf = ByteArray(1024)
            while (running) {
                val opened = link.open()
                if (opened == null) {
                    tpmsConnected.value = false
                    tpmsDevice.value = null
                    Thread.sleep(3000)   // retry until the receiver shows up
                    continue
                }
                val port = opened.port
                tpmsDevice.value = link.describe(opened.device)
                tb.clear()
                try {
                    while (running) {
                        // send any queued commands first (the reader thread owns the port)
                        var cmd = tpmsCmdQueue.poll()
                        while (cmd != null) { try { port.write(cmd, 300) } catch (_: Exception) {}; cmd = tpmsCmdQueue.poll() }
                        val n = port.read(buf, 300)
                        for (i in 0 until n) tpmsFeed(buf[i].toInt() and 0xFF)
                    }
                } catch (_: Exception) {
                    // unplugged USB / error -> reconnect
                } finally {
                    try { port.close() } catch (_: Exception) {}
                    tpmsConnected.value = false
                    tpmsDevice.value = null
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------- Serial OTA (stream the bundled image to the running firmware) ----------------

    /** Streams assets/firmware.bin to the ESP over the SAME serial port via the firmware's
     *  serial-OTA receiver (IDF esp_ota). Flow-controlled per chunk. The ESP reboots on success. */
    private fun doSerialOta(port: com.hoho.android.usbserial.driver.UsbSerialPort) {
        fun lg(m: String) = android.util.Log.i("PCota", m)
        flashProgress.value = 0
        flashMsg.value = getString(R.string.flash_writing)
        try {
            val img = assets.open("firmware.bin").use { it.readBytes() }
            lg("image=${img.size}")
            // wait for a one-char ack line (skips any in-flight JSON/log lines)
            fun expect(want: Char, timeoutMs: Long): Boolean {
                val rb = ByteArray(256); val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val n = try { port.read(rb, 200) } catch (e: Exception) { lg("read err: ${e.message}"); return false }
                    for (i in 0 until n) {
                        val c = rb[i].toInt().toChar()
                        if (c == '\n') {
                            val s = sb.toString(); sb.setLength(0)
                            lg("rx<- ${s.take(60)}")
                            val f = s.firstOrNull()
                            if (f == want) return true
                            if (f == 'E') return false
                        } else if (sb.length < 240) sb.append(c)
                    }
                }
                lg("expect('$want') TIMEOUT")
                return false
            }
            port.write("O${img.size}\n".toByteArray(), 1000)
            if (!expect('K', 6000)) { lg("no READY"); flashFail(getString(R.string.flash_sync_fail)); return }
            // send the image as base64 LINES (binary over the JTAG console is mangled)
            var off = 0; val raw = 600
            while (off < img.size) {
                val end = minOf(off + raw, img.size)
                val b64 = android.util.Base64.encodeToString(img.copyOfRange(off, end), android.util.Base64.NO_WRAP)
                port.write((b64 + "\n").toByteArray(), 3000)
                if (!expect('A', 8000)) { lg("chunk nack @${off}"); flashFail(getString(R.string.flash_err)); return }
                off = end
                flashProgress.value = off * 100 / img.size
            }
            if (!expect('D', 10000)) { lg("no DONE"); flashFail(getString(R.string.flash_err)); return }
            lg("OTA ok -> ESP rebooting")
            flashState.value = FlashState.DONE
            flashMsg.value = getString(R.string.flash_done)
        } catch (e: Exception) {
            lg("exception: ${e.message}")
            flashFail(e.message ?: "error")
        }
    }

    private fun flashFail(msg: String) {
        flashState.value = FlashState.ERROR
        flashMsg.value = msg
    }

    /** Best-effort device-wide night mode (needs a system perm; granted via adb on this
     *  head unit:  pm grant hu.codingo.priuscan android.permission.WRITE_SECURE_SETTINGS).
     *  The in-app theme follows carDark regardless; this also flips the rest of the system. */
    private fun applyNightMode(dark: Boolean) {
        try {
            val uim = getSystemService(android.app.UiModeManager::class.java)
            uim?.setNightMode(if (dark) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO)
        } catch (_: Exception) {}
        try {
            android.provider.Settings.Secure.putInt(contentResolver, "ui_night_mode", if (dark) 2 else 1)
        } catch (_: Exception) {}
    }

    /** Byte-level framer: resync to 55 AA, validate XOR; 0x09 frames are 9 bytes, others 8. */
    private fun tpmsFeed(b: Int) {
        tb.add(b)
        while (tb.size >= 2 && !(tb[0] == 0x55 && tb[1] == 0xAA)) tb.removeAt(0)
        while (tb.size >= 3 && tb[0] == 0x55 && tb[1] == 0xAA) {
            // frame length by type: 0x09 ID = 9, 0x06 command-ack = 6, others (0x08 data) = 8
            val len = when (tb[2]) { 0x09 -> 9; 0x06 -> 6; else -> 8 }
            if (tb.size < len) break
            var x = 0
            for (k in 0 until len - 1) x = x xor tb[k]
            if (x == tb[len - 1]) {
                val f = IntArray(len) { tb[it] }
                repeat(len) { tb.removeAt(0) }
                tpmsHandleFrame(f)
            } else {
                tb.removeAt(0)   // bad XOR -> resync past this 55
                while (tb.size >= 2 && !(tb[0] == 0x55 && tb[1] == 0xAA)) tb.removeAt(0)
            }
        }
    }

    private fun tpmsHandleFrame(f: IntArray) {
        if (!tpmsConnected.value) tpmsConnected.value = true
        when (f[2]) {
            0x08 -> {  // sensor data: 55 AA 08 POS(0x00/01/10/11/05) D0 D1 D2 XOR
                val w = Wheel.fromPos(f[3]) ?: return
                val r = TireReading(Tpms.bar(f[4]), Tpms.tempC(f[5]), f[6], System.currentTimeMillis())
                tpms.value = HashMap(tpms.value).apply { put(w, r) }
            }
            0x09 -> {  // ID response: 55 AA 09 SLOT(1..5) ID0 ID1 ID2 ID3 XOR
                val w = Wheel.fromSlot(f[3])
                if (w == null || f.size < 9) return
                val id = "%02X%02X%02X%02X".format(f[4], f[5], f[6], f[7])
                tpmsIds.value = HashMap(tpmsIds.value).apply { put(w, id) }
                prefs.tpmsIds = tpmsIds.value.mapKeys { it.key.name }
                // pairing success for this wheel -> leave learning mode automatically
                if (tpmsPairing.value == w) { sendTpmsCommand(Tpms.PAIR_STOP); tpmsPairing.value = null }
            }
        }
    }

    /** @return true if this is a valid PriusCAN JSON line (not data from another device). */
    private fun handleLine(raw: String): Boolean {
        val s = raw.trim()
        // CAN dump frames -> separate dump file (never treated as device data)
        if (s.startsWith("#")) { if (dumpActive.value) dumpLogger.write(s); return false }
        if (!dumpActive.value && dumpLogger.isOpen) dumpLogger.stop()   // dump turned off -> close file
        if (!s.startsWith("{")) return false   // drop log/other lines
        // Fast path: strict JSON. Fallback: lenient field-by-field recovery so a single
        // malformed field (e.g. a firmware emit bug) doesn't drop the whole line.
        var recovered = false
        val json = try { JSONObject(s) } catch (_: Exception) {
            recovered = true; lenientJson(s) ?: return false
        }
        // firmware history responses (on demand): "H" -> rhist, "HO" -> ohist
        if (json.has("rhist")) { refuelHist.value = TripSlot.list(json.optJSONArray("rhist")); return false }
        if (json.has("ohist")) { oilHist.value = TripSlot.list(json.optJSONArray("ohist")); return false }
        if (json.has("rblk")) {
            val a = json.optJSONArray("rblk")
            blockR.value = if (a == null) emptyList()
                else (0 until a.length()).map { if (a.isNull(it)) Float.NaN else a.optDouble(it).toFloat() }
            return false
        }
        // PriusCAN signature: every emit_json line contains the door+cruise
        // fields -> this is how we tell it apart from another ESP/TPMS's data
        if (!json.has("door") || !json.has("cruise")) return false
        if (json.has("fw")) fwRunning.value = json.optInt("fw")
        // log a clean, parseable line: the raw line if it was valid, else the recovered JSON
        val logSrc = if (recovered) json.toString() else s
        if (prefs.logEnabled && logSrc.endsWith("}")) {
            // append a timestamp (epoch ms) + the latest GPS fix to the logged line, so
            // the JSONL is usable for time-based offline analysis (capacity, etc.).
            val sb = StringBuilder(logSrc.length + 96)
            sb.append(logSrc, 0, logSrc.length - 1)       // drop the closing '}'
            sb.append(",\"ts\":").append(System.currentTimeMillis())
            lastLoc?.let { loc ->
                sb.append(String.format(
                    java.util.Locale.US,
                    ",\"lat\":%.6f,\"lon\":%.6f,\"galt\":%.0f,\"gspd\":%.1f,\"gcrs\":%.0f",
                    loc.latitude, loc.longitude, loc.altitude, loc.speed * 3.6f, loc.bearing,
                ))
            }
            sb.append("}")
            logger.log(sb.toString())
        } else if (prefs.logEnabled) {
            logger.log(logSrc)
        }
        // trip slots (8) + history counts come on the regular line
        json.optJSONArray("slots")?.let { tripSlots.value = TripSlot.list(it) }
        if (json.has("rhN") || json.has("ohN"))
            histCount.value = json.optInt("rhN", histCount.value.first) to json.optInt("ohN", histCount.value.second)
        val st = CanState(json)
        state.value = st
        main.post { onState(st) }
        return true
    }

    /** Lenient recovery: extract every well-formed "key":number pair, skipping any
     *  malformed segment. So one broken field can't drop the whole line. */
    private fun lenientJson(s: String): JSONObject? {
        val o = JSONObject()
        for (m in FIELD_RE.findAll(s)) {
            val v = m.groupValues[2]
            if (v != "null") o.put(m.groupValues[1], v.toDouble())
        }
        return if (o.length() > 0) o else null
    }

    // ---------------- state reactions (on the main thread) ----------------

    private fun onState(s: CanState) {
        updateNotifications(s)
        updateStatusStrip(s)

        // dark/light: prefer the RAW ambient-light sensor (0x620 b2:b3, ~31 bright .. ~600 dark,
        // confirmed by the lamp test) — fast and independent of the light switch. Fall back to the
        // slow solar sun-load sensor. Hysteresis to avoid flicker.
        // the instrument cluster flips day/night at ambL ~100 -> match it (±10 hysteresis)
        val ambL = s.d("ambL")
        val nightNow = when {
            ambL != null -> if (ambL > 110.0) true else if (ambL < 90.0) false else carDark.value
            else -> s.d("solar")?.let { sol -> if (sol < 6.0) true else if (sol > 20.0) false else carDark.value } ?: carDark.value
        }
        if (nightNow != carDark.value) {
            carDark.value = nightNow
            prefs.darkLast = nightNow                          // remember for the next startup
            if (prefs.autoDarkCar) applyNightMode(nightNow)   // best-effort device-wide
        }

        // warning -> sound + overlay, only at a sustained (HOLD_MS) level, not repeated
        val now = System.currentTimeMillis()
        val wp = wpGate.update(s.wpWarn, now)
        if (wp > 0) {
            val t = s.coolant?.toInt() ?: 0
            alert(wp, if (wp >= 2) getString(R.string.alert_overheat, t) else getString(R.string.alert_cooling, t))
        }
        val cw = cellGate.update(s.cellWarn, now)
        if (cw > 0) {
            val d = s.d("blkD") ?: 0.0
            alert(cw, if (cw >= 2) getString(R.string.alert_hv_fault, d, s.i("weakB"))
                      else getString(R.string.alert_hv_cell, d))
        }

        // belterület geofence: OUTSIDE a built-up area with low-beam OFF while moving -> warn.
        // (HU: dipped headlights are mandatory on the open road.) 0x20 = low-beam lamp bit.
        val lowBeamOff = (s.i("lights") and 0x20) == 0
        if (inCity == false && lowBeamOff && (s.d("spd") ?: 0.0) > 5.0) {
            if (now - lastHlWarn > 60000L) { lastHlWarn = now; alert(1, getString(R.string.alert_headlight)) }
        } else lastHlWarn = 0L   // condition cleared -> re-arm (fire immediately if it returns)

        // doors
        overlays.updateDoors(s.doorMask)
    }

    private fun alert(level: Int, text: String) {
        // the ToneGenerator constructor can throw if the audio resource is busy
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            if (level >= 2) {
                tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2500)
            } else {
                tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
            }
            main.postDelayed({ try { tg.release() } catch (_: Exception) {} }, 3000)
        } catch (_: Exception) {}
        overlays.showAlert(level, text)
    }

    // ---------------- status bar: configurable items ----------------
    // Each enabled item gets a SEPARATE notification with its own drawn icon,
    // so several values are visible at once in the status bar. The first item is
    // the foreground notification (ID=NOTIF_ID), the rest run on 100+index IDs.

    private val itemOrder = listOf("ct", "soc", "rpm", "cons")
    private val lastSlotText = HashMap<Int, String>()
    private var extraCount = 0

    /**
     * Instantaneous consumption: use the SAME firmware-calibrated injector fuel rate (l/h) as the
     * dashboard, not a MAF approximation (which underestimated -> overlay read lower than the dash).
     * While driving l/100km, when stopped/idling l/h.
     */
    private fun consumption(s: CanState): Double? {
        val lph = s.d("fuel") ?: return null
        val spd = s.d("spd") ?: 0.0
        return if (spd > 3) lph / spd * 100.0 else lph
    }

    private fun itemText(key: String, s: CanState): String? = when (key) {
        "ct" -> s.coolant?.toInt()?.toString()
        "soc" -> s.d("soc")?.toInt()?.toString()
        "rpm" -> s.d("rpm")?.toInt()?.toString()
        "cons" -> consumption(s)?.let { "%.1f".format(it) }
        else -> null
    }

    private fun itemLabel(key: String) = when (key) {
        "ct" -> getString(R.string.nlabel_ct); "soc" -> getString(R.string.nlabel_soc); "rpm" -> getString(R.string.nlabel_rpm)
        "cons" -> getString(R.string.nlabel_cons); else -> key
    }

    private fun itemUnit(key: String, s: CanState) = when (key) {
        "ct" -> "°C"; "soc" -> "%"; "rpm" -> "rpm"
        "cons" -> if ((s.d("spd") ?: 0.0) > 3) "l/100km" else "l/h"
        else -> ""
    }

    fun refreshNotifications() {
        lastSlotText.clear()
        updateNotifications(state.value)
        updateStatusStrip(state.value)
    }

    /**
     * The selected values in a single, always-visible overlay strip at the top
     * of the screen. The head-unit ROM's status bar does not show the notification
     * icons, so this is the glance display.
     */
    private fun updateStatusStrip(s: CanState) {
        val enabled = itemOrder.filter { it in prefs.statusItems }.ifEmpty { listOf("ct") }
        val text = enabled.joinToString("    ") { key ->
            val v = itemText(key, s) ?: "–"
            val u = itemUnit(key, s)
            if (u.isEmpty()) "${itemLabel(key)} $v" else "$v $u"
        }
        main.post { overlays.updateStatus(text) }
    }

    private fun updateNotifications(s: CanState) {
        val enabled = itemOrder.filter { it in prefs.statusItems }.ifEmpty { listOf("ct") }

        // primary (foreground) slot
        slot(NOTIF_ID, enabled[0], s, primary = true)

        // additional items
        val extras = enabled.drop(1)
        extras.forEachIndexed { i, key -> slot(100 + i, key, s, primary = false) }
        for (i in extras.size until extraCount) {
            notifMgr.cancel(100 + i)
            lastSlotText.remove(100 + i)
        }
        extraCount = extras.size
    }

    private fun slot(id: Int, key: String, s: CanState, primary: Boolean) {
        val txt = itemText(key, s) ?: "–"
        if (lastSlotText[id] == "$key:$txt") return
        lastSlotText[id] = "$key:$txt"
        notifMgr.notify(id, buildNotification(key, txt, s, primary))
    }

    private fun buildNotification(key: String?, value: String?, s: CanState?, primary: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val content = when {
            s == null || key == null -> getString(R.string.notif_connecting)
            primary -> buildString {
                append("${itemLabel(key)} ${value ?: "–"} ${itemUnit(key, s)}")
                s.coolant?.let { if (key != "ct") append("  •  ${getString(R.string.coolant_caption)} ${it.toInt()}°C") }
                s.d("rpm")?.let { if (key != "rpm") append("  •  ${it.toInt()} rpm") }
                s.d("soc")?.let { if (key != "soc") append("  •  SoC ${it.toInt()}%") }
                if (s.gear in 0..4) append("  •  ${CanState.GEARS[s.gear]}")
            }
            else -> "${itemLabel(key)}: ${value ?: "–"} ${itemUnit(key, s)}"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(statusIcon(key, value, s))
            .setContentTitle(if (key == null) "PriusCAN" else itemLabel(key))
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    // The status bar icons are rendered as an alpha mask (Android tints them),
    // so everything is white, and the "charge level" is a semi-transparent fill.

    private fun statusIcon(key: String?, v: String?, s: CanState?): Icon {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (key == "soc") {
            drawBattery(c, v)
        } else {
            val unit = when (key) {
                "ct" -> "°C"
                "rpm" -> "RPM"
                "cons" -> if (s != null && (s.d("spd") ?: 0.0) > 5) "L/100" else "L/H"
                else -> ""
            }
            drawValueUnit(c, size, v ?: "–", unit)
        }
        return Icon.createWithBitmap(b)
    }

    /** Large value on top, small unit below - e.g. "85" / "°C". */
    private fun drawValueUnit(c: Canvas, size: Int, value: String, unit: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = when (value.length) {
                1, 2 -> 56f
                3 -> 48f
                4 -> 38f
                else -> 32f
            }
        }
        c.drawText(value, size / 2f, size * 0.50f, p)
        if (unit.isNotEmpty()) {
            p.textSize = 26f
            c.drawText(unit, size / 2f, size * 0.88f, p)
        }
    }

    /** Horizontal battery outline with a charge-proportional fill and the % inside. */
    private fun drawBattery(c: Canvas, v: String?) {
        val pct = v?.toFloatOrNull()?.coerceIn(0f, 100f)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f
        }
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        val body = RectF(4f, 26f, 82f, 70f)
        c.drawRoundRect(body, 8f, 8f, outline)
        c.drawRoundRect(RectF(84f, 38f, 93f, 58f), 3f, 3f, solid)   // positive terminal cap
        if (pct != null && pct > 2f) {
            val fill = Paint(solid).apply { alpha = 105 }
            c.drawRoundRect(RectF(9f, 31f, 9f + 68f * pct / 100f, 65f), 5f, 5f, fill)
        }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = if ((v?.length ?: 1) >= 3) 26f else 30f
        }
        c.drawText(
            (v ?: "–") + "%",
            body.centerX(),
            body.centerY() - (tp.ascent() + tp.descent()) / 2f,
            tp
        )
    }
}

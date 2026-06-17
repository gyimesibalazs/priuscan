package hu.codingo.priuscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

/**
 * Mindig futo foreground service:
 *  - USB soros olvasas + ujracsatlakozas
 *  - JSON parse -> StateFlow (a UI ebbol el)
 *  - allapotsor: hutoviz hofok dinamikus ikonkent
 *  - warning/alert: hang + felugro overlay
 *  - nyitott ajto: felulrol becsuszo Prius-rajz overlay
 */
class CanService : Service() {

    companion object {
        val state = MutableStateFlow(CanState.EMPTY)
        // connected = true CSAK ha valodi PriusCAN-adat erkezik (nem eleg a port nyitas)
        val connected = MutableStateFlow(false)
        // melyik USB-eszkozhoz vagyunk kotve (termeknev / VID:PID / eszkoznev)
        val deviceInfo = MutableStateFlow<String?>(null)

        private const val CHANNEL = "priuscan"
        private const val NOTIF_ID = 1

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
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            haPusher.restart()
            main.post { refreshNotifications() }
        }

    @Volatile private var running = true
    private var ioThread: Thread? = null

    private var lastWp = 0
    private var lastCell = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlays = OverlayManager(this, prefs)
        notifMgr = getSystemService(NotificationManager::class.java)
        notifMgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Prius CAN", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, buildNotification(null, null, null, primary = true))
        haPusher = HaPusher(this, prefs)
        haPusher.start()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        startIoLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        ioThread?.interrupt()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        haPusher.stop()
        overlays.removeAll()
        super.onDestroy()
    }

    // ---------------- soros olvaso hurok ----------------

    private fun startIoLoop() {
        if (ioThread?.isAlive == true) return
        ioThread = Thread {
            val link = SerialLink(this)
            val buf = ByteArray(4096)
            val line = StringBuilder()
            // azok az eszkozok, amik megnyithatok voltak, de NEM PriusCAN-adatot
            // kuldtek (pl. masik ESP / TPMS) - ezeket ebben a korben atugorjuk
            val rejected = HashSet<Int>()
            while (running) {
                val opened = link.open(rejected)
                if (opened == null) {
                    connected.value = false
                    deviceInfo.value = null
                    rejected.clear()     // uj kor: adjunk eselyt mindenkinek ujra
                    Thread.sleep(3000)   // ujraprobalkozas, amig az ESP fel nem all
                    continue
                }
                val port = opened.port
                var valid = false
                val openedAt = System.currentTimeMillis()
                line.setLength(0)
                try {
                    while (running) {
                        val n = port.read(buf, 500)
                        for (i in 0 until n) {
                            val c = buf[i].toInt().toChar()
                            if (c == '\n') {
                                if (handleLine(line.toString()) && !valid) {
                                    // valodi PriusCAN-adat -> EZ a jo eszkoz
                                    valid = true
                                    connected.value = true
                                    deviceInfo.value = link.describe(opened.device)
                                }
                                line.setLength(0)
                            } else if (line.length < 4096) {
                                line.append(c)
                            }
                        }
                        // ha 4 mp alatt nem jott ervenyes PriusCAN-sor, ez nem a
                        // mi eszkozunk -> elutasitjuk es a kovetkezot probaljuk
                        if (!valid && System.currentTimeMillis() - openedAt > 4000) {
                            rejected.add(opened.device.deviceId)
                            break
                        }
                    }
                } catch (_: Exception) {
                    // kihuzott USB / hiba -> ujracsatlakozas
                } finally {
                    try { port.close() } catch (_: Exception) {}
                    connected.value = false
                    deviceInfo.value = null
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** @return true, ha ez egy ervenyes PriusCAN JSON-sor (nem mas eszkoz adata). */
    private fun handleLine(raw: String): Boolean {
        val s = raw.trim()
        if (!s.startsWith("{")) return false   // log/dump sorok eldobasa
        val json = try { JSONObject(s) } catch (_: Exception) { return false }
        // PriusCAN-alairas: az emit_json minden sora tartalmazza a door+cruise
        // mezoket -> ezzel kulonbozteti meg egy masik ESP/TPMS adatatol
        if (!json.has("door") || !json.has("cruise")) return false
        val st = CanState(json)
        state.value = st
        main.post { onState(st) }
        return true
    }

    // ---------------- allapot-reakciok (main threaden) ----------------

    private fun onState(s: CanState) {
        updateNotifications(s)
        updateStatusStrip(s)

        // warning -> hang + overlay, csak szintvaltasnal (ne ismeteljen)
        val wp = s.wpWarn
        if (wp > lastWp) {
            val t = s.coolant?.toInt() ?: 0
            alert(wp, if (wp >= 2) "TÚLMELEGEDÉS! ${t}°C" else "Hűtés figyelmeztetés: ${t}°C")
        }
        lastWp = wp

        val cw = s.cellWarn
        if (cw > lastCell) {
            val d = s.d("blkD") ?: 0.0
            alert(cw, if (cw >= 2) "HV AKKU HIBA! ΔU=%.2fV (blokk %d)".format(d, s.i("weakB"))
                      else "HV cella figyelmeztetés: ΔU=%.2fV".format(d))
        }
        lastCell = cw

        // ajtok
        overlays.updateDoors(s.doorMask)
    }

    private fun alert(level: Int, text: String) {
        // a ToneGenerator konstruktor dobhat, ha az audio eroforras foglalt
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

    // ---------------- statuszsor: konfigurálhato elemek ----------------
    // Minden bekapcsolt elem KULON notificationt kap sajat rajzolt ikonnal,
    // igy egyszerre tobb ertek latszik a statuszsorban. Az elso elem a
    // foreground notification (ID=NOTIF_ID), a tobbi 100+index ID-n fut.

    private val itemOrder = listOf("ct", "soc", "rpm", "cons")
    private val lastSlotText = HashMap<Int, String>()
    private var extraCount = 0

    /**
     * Pillanatnyi fogyasztas MAF-bol: uzemanyag g/s = MAF/14.7 (sztöchiometrikus
     * AFR), benzin ~745 g/l. Menetben l/100km, allo/alapjaraton l/h.
     * EV menetnel (ICE all) 0.
     */
    private fun consumption(s: CanState): Double? {
        val maf = s.d("maf") ?: return null
        val rpm = s.d("rpm") ?: 0.0
        if (rpm < 100) return 0.0
        val lph = maf / 14.7 * 3600.0 / 745.0
        val spd = s.d("spd") ?: 0.0
        return if (spd > 5) lph / spd * 100.0 else lph
    }

    private fun itemText(key: String, s: CanState): String? = when (key) {
        "ct" -> s.coolant?.toInt()?.toString()
        "soc" -> s.d("soc")?.toInt()?.toString()
        "rpm" -> s.d("rpm")?.toInt()?.toString()
        "cons" -> consumption(s)?.let { "%.1f".format(it) }
        else -> null
    }

    private fun itemLabel(key: String) = when (key) {
        "ct" -> "Hűtővíz"; "soc" -> "HV akku"; "rpm" -> "Fordulat"
        "cons" -> "Fogyasztás"; else -> key
    }

    private fun itemUnit(key: String, s: CanState) = when (key) {
        "ct" -> "°C"; "soc" -> "%"; "rpm" -> "rpm"
        "cons" -> if ((s.d("spd") ?: 0.0) > 5) "l/100km" else "l/h"
        else -> ""
    }

    fun refreshNotifications() {
        lastSlotText.clear()
        updateNotifications(state.value)
        updateStatusStrip(state.value)
    }

    /**
     * A kivalasztott ertekek egyetlen, mindig lathato overlay-csikban a kepernyo
     * tetejen. A fejegyseg ROM statuszsora nem mutatja a notification-ikonokat,
     * ezert ez a glance-display.
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

        // elsodleges (foreground) slot
        slot(NOTIF_ID, enabled[0], s, primary = true)

        // tovabbi elemek
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
            s == null || key == null -> "Kapcsolódás az ESP32-höz…"
            primary -> buildString {
                append("${itemLabel(key)} ${value ?: "–"} ${itemUnit(key, s)}")
                s.coolant?.let { if (key != "ct") append("  •  hűtővíz ${it.toInt()}°C") }
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

    // A statuszsor-ikonok alfa-maszkkent renderelodnek (az Android szinezi),
    // ezert minden feher, a "toltottseg" pedig felig atlatszo kitoltes.

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

    /** Nagy ertek felul, kis mertekegyseg alatta - pl. "85" / "°C". */
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

    /** Fekvo akkumulator korvonal, toltottseg-aranyos kitoltessel, benne a %. */
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
        c.drawRoundRect(RectF(84f, 38f, 93f, 58f), 3f, 3f, solid)   // pozitiv sapka
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

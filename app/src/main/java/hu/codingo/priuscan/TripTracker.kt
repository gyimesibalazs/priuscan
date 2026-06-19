package hu.codingo.priuscan

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Live "since last refuel" stats. */
data class TripLive(
    val elapsedS: Long,   // wall-clock seconds since the last refuel
    val distKm: Double,
    val avgKmh: Double,   // distance / moving-time (stops <=3 min count as moving)
    val avgCons: Double,  // l/100km
)

/** One finalized refuel period (what the trip looked like between two refuels). */
data class RefuelRecord(
    val epoch: Long,      // unix seconds when this refuel happened
    val durationS: Long,
    val distKm: Double,
    val avgKmh: Double,
    val avgCons: Double,
) {
    fun toJson() = JSONObject().apply {
        put("epoch", epoch); put("dur", durationS); put("dist", distKm)
        put("kmh", avgKmh); put("cons", avgCons)
    }
    companion object {
        fun fromJson(o: JSONObject) = RefuelRecord(
            o.optLong("epoch"), o.optLong("dur"), o.optDouble("dist"),
            o.optDouble("kmh"), o.optDouble("cons"),
        )
    }
}

/**
 * Accumulates trip stats SINCE THE LAST REFUEL from the live JSON stream, using the
 * app's own wall clock. Refuel is detected from a fuelIn jump while parked (mirrors
 * the firmware). On refuel the current period is finalized into a history of up to
 * MAX_HISTORY records. Everything is persisted in app prefs (survives restarts).
 *
 * Moving-time rule (for avg speed): time spent moving + stops up to 3 min count as
 * "moving"; a stop longer than 3 min stops accumulating (parked, not driving).
 */
class TripTracker(
    private val prefs: Prefs,
    private val live: MutableStateFlow<TripLive>,
    private val history: MutableStateFlow<List<RefuelRecord>>,
) {

    private var startMs = 0L        // when the current refuel period began (wall clock)
    private var distKm = 0.0
    private var movingMs = 0L
    private var fuelL = 0.0
    private var stoppedMs = 0L
    private var lastMs = 0L         // previous onState timestamp (for dt)
    private var fuelRef = -1.0      // slow fuel-level baseline for refuel detection
    private var lastPersist = 0L

    init { load() }

    fun onState(s: CanState) {
        val now = System.currentTimeMillis()
        if (startMs == 0L) startMs = now
        if (lastMs == 0L) { lastMs = now; return }
        val dt = now - lastMs
        lastMs = now
        if (dt <= 0 || dt > 60_000L) return   // ignore startup / large gaps (parked/app paused)

        val sp = s.d("spd") ?: 0.0
        if (sp > 2.0) {
            movingMs += dt; stoppedMs = 0
            distKm += sp * (dt / 3_600_000.0)
        } else {
            stoppedMs += dt
            if (stoppedMs <= 180_000L) movingMs += dt   // stop <=3 min still "moving"
        }
        s.d("fuel")?.let { if (it > 0) fuelL += it * (dt / 3_600_000.0) }

        // refuel detection from fuelIn (0x5A4): a jump up while parked
        s.d("fuelIn")?.let { fin ->
            if (fuelRef < 0) fuelRef = fin
            if (sp < 3.0 && fin - fuelRef >= REFUEL_DELTA) { finalize(now); fuelRef = fin }
            else fuelRef += 0.01 * (fin - fuelRef)
        }

        live.value = TripLive((now - startMs) / 1000, distKm, avgKmh(), avgCons())
        if (now - lastPersist > 30_000L) { persist(); lastPersist = now }
    }

    private fun avgKmh(): Double = if (movingMs > 1000) distKm / (movingMs / 3_600_000.0) else 0.0
    private fun avgCons(): Double = if (distKm > 0.1) fuelL / distKm * 100.0 else 0.0

    private fun finalize(now: Long) {
        val rec = RefuelRecord((now / 1000), (now - startMs) / 1000, distKm, avgKmh(), avgCons())
        history.value = (listOf(rec) + history.value).take(MAX_HISTORY)
        startMs = now; distKm = 0.0; movingMs = 0L; fuelL = 0.0; stoppedMs = 0L
        live.value = TripLive(0, 0.0, 0.0, 0.0)
        persist()
    }

    private fun persist() {
        val cur = JSONObject().apply {
            put("start", startMs); put("dist", distKm); put("moving", movingMs)
            put("fuel", fuelL); put("stopped", stoppedMs); put("ref", fuelRef)
        }
        val hist = JSONArray().apply { history.value.forEach { put(it.toJson()) } }
        prefs.sp.edit().putString("trip_cur", cur.toString()).putString("trip_hist", hist.toString()).apply()
    }

    private fun load() {
        try {
            prefs.sp.getString("trip_cur", null)?.let {
                val o = JSONObject(it)
                startMs = o.optLong("start"); distKm = o.optDouble("dist"); movingMs = o.optLong("moving")
                fuelL = o.optDouble("fuel"); stoppedMs = o.optLong("stopped"); fuelRef = o.optDouble("ref", -1.0)
            }
            prefs.sp.getString("trip_hist", null)?.let {
                val a = JSONArray(it)
                history.value = (0 until a.length()).map { i -> RefuelRecord.fromJson(a.getJSONObject(i)) }
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val MAX_HISTORY = 50
        const val REFUEL_DELTA = 5.0
    }
}

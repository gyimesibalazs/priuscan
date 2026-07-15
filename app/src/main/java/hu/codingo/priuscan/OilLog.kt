package hu.codingo.priuscan

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-side oil-change metadata registry. The FIRMWARE owns the measurement (slot[2] km/fuel since
 * the change + the ohist periods, persisted in NVS); this file only stores what the firmware can't
 * know: what oil went in, was the filter changed, a free-text note. One entry per registered
 * change, keyed by the odometer at the change (= the start odo of the period it opened), so the
 * UI can join entries to firmware records with a small tolerance.
 */
object OilLog {
    data class OilEntry(
        val epoch: Long,        // unix s of the registration
        val odo: Long,          // odometer (km) at the change = slot/period start odo
        val oilType: String,
        val filter: Boolean,    // was the oil filter changed too
        val note: String,
    )

    val entries = MutableStateFlow<List<OilEntry>>(emptyList())
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "oil_log.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(file(ctx).readText())
            entries.value = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                OilEntry(o.optLong("e"), o.optLong("o"), o.optString("t"),
                         o.optBoolean("f"), o.optString("n"))
            }
        }
    }

    @Synchronized
    fun add(ctx: Context, e: OilEntry) {
        load(ctx)
        val list = entries.value + e
        entries.value = list
        runCatching {
            val a = JSONArray()
            list.forEach { x ->
                a.put(JSONObject().put("e", x.epoch).put("o", x.odo).put("t", x.oilType)
                    .put("f", x.filter).put("n", x.note))
            }
            file(ctx).writeText(a.toString())
        }
    }

    /** The entry registered at (or within ±tol km of) the given period-start odometer. */
    fun forOdo(odo: Long, tol: Long = 3): OilEntry? =
        entries.value.lastOrNull { kotlin.math.abs(it.odo - odo) <= tol }
}

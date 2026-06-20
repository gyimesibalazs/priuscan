package hu.codingo.priuscan

import androidx.annotation.StringRes
import org.json.JSONObject

/** Descriptor of a single sensor field for display. label is a string resource id. */
data class Field(
    val key: String,
    @StringRes val labelRes: Int,
    val unit: String = "",
    val decimals: Int = 0,
)

/** The display order and grouping - the main screen is built from this.
 *  Group titles and field labels are string resources (localized).
 *  The keys match the output of prius_parse.h emit_json() (v2 config). */
object Fields {
    /** Shown on the dashboard only (not a tab). */
    val trip = listOf(
        Field("odo", R.string.f_odo, "km", 0),
        Field("fuelIn", R.string.f_fuelIn, "%", 0),
        Field("tDist", R.string.f_tDist, "km", 1),
        Field("tEv", R.string.f_tEv, "km", 1),
        Field("tSpd", R.string.f_tSpd, "km/h", 0),
        Field("tFuel", R.string.f_tFuel, "L", 2),
        Field("tAvg", R.string.f_tAvg, "l/100km", 1),
    )

    val groups: List<Pair<Int, List<Field>>> = listOf(
        R.string.grp_engine to listOf(
            Field("ct", R.string.f_ct, "°C"),
            Field("rpm", R.string.f_rpm, "rpm"),
            Field("spd", R.string.f_spd, "km/h"),
            Field("load", R.string.f_load, "%"),
            Field("maf", R.string.f_maf, "g/s", 1),
            Field("map", R.string.f_map, "kPa"),
            Field("iat", R.string.f_iat, "°C"),
            Field("thr", R.string.f_thr, "%"),
            Field("pedal", R.string.f_pedal, "%"),
            Field("fuel", R.string.f_fuel, "l/h", 1),
            Field("engNm", R.string.f_engNm, "Nm"),
            Field("injml", R.string.f_injml, "ml", 3),
            Field("run", R.string.f_run, "s"),
        ),
        R.string.grp_hybrid_drive to listOf(
            Field("mg1t", R.string.f_mg1t, "°C"),
            Field("mg1r", R.string.f_mg1r, "rpm"),
            Field("mg1q", R.string.f_mg1q, "Nm"),
            Field("mg2t", R.string.f_mg2t, "°C"),
            Field("mg2r", R.string.f_mg2r, "rpm"),
            Field("mg2q", R.string.f_mg2q, "Nm"),
            Field("inv1", R.string.f_inv1, "°C"),
            Field("inv2", R.string.f_inv2, "°C"),
            Field("btu", R.string.f_btu, "°C"),
            Field("btl", R.string.f_btl, "°C"),
            Field("vl", R.string.f_vl, "V"),
            Field("vh", R.string.f_vh, "V"),
            Field("invct", R.string.f_invct, "°C"),
            Field("invwp", R.string.f_invwp, "rpm"),
        ),
        R.string.grp_hybrid_batt to listOf(
            Field("soc", R.string.f_soc, "%"),
            Field("hvA", R.string.f_hvA, "A", 1),
            Field("hvdis", R.string.f_hvdis, "kW", 1),
            Field("hvchg", R.string.f_hvchg, "kW", 1),
            Field("bmin", R.string.f_bmin, "V", 2),
            Field("bmax", R.string.f_bmax, "V", 2),
            Field("blkD", R.string.f_blkD, "V", 2),
            Field("weakB", R.string.f_weakB),
            Field("maxR", R.string.f_maxR, "mΩ"),
            Field("hvAir", R.string.f_hvAir, "°C"),
            Field("tb1", R.string.f_tb1, "°C"),
            Field("tb2", R.string.f_tb2, "°C"),
            Field("tb3", R.string.f_tb3, "°C"),
            Field("tHot", R.string.f_tHot),
            Field("battFan", R.string.f_battFan, "%"),
            Field("cellW", R.string.f_cellW),
            Field("cwL", R.string.f_cwL),
            Field("wblk", R.string.f_wblk),
            Field("wz", R.string.f_wz, "", 2),
            Field("capAh", R.string.f_capAh, "Ah", 2),
            Field("capKwh", R.string.f_capKwh, "kWh", 2),
        ),
        R.string.grp_climate to listOf(
            Field("cabin", R.string.f_cabin, "°C"),
            Field("setT", R.string.f_setT, "°C", 1),
            Field("comp", R.string.f_comp, "rpm"),
            Field("evap", R.string.f_evap, "°C"),
            Field("acw", R.string.f_acw, "W"),
            Field("solar", R.string.f_solar),
            Field("acAmb", R.string.f_acAmb, "°C"),
            Field("blower", R.string.f_blower),
            Field("acPress", R.string.f_acPress, "MPa", 2),
        ),
        R.string.grp_drive_brake to listOf(
            Field("wFL", R.string.f_wFL, "km/h", 1),
            Field("wFR", R.string.f_wFR, "km/h", 1),
            Field("wRL", R.string.f_wRL, "km/h", 1),
            Field("wRR", R.string.f_wRR, "km/h", 1),
            Field("wDif", R.string.f_wDif, "%", 2),
            Field("gLat", R.string.f_gLat, "m/s²", 2),
            Field("gFwd", R.string.f_gFwd, "m/s²", 2),
            Field("steer", R.string.f_steer, "°"),
            Field("brkP", R.string.f_brkP, "V", 2),
            Field("yaw", R.string.f_yaw, "", 0),
        ),
    )
}


/** A single received state snapshot. */
class CanState(json: JSONObject?) {

    val values = HashMap<String, Double>()
    val ts = System.currentTimeMillis()

    init {
        if (json != null) {
            for (key in json.keys()) {
                // the firmware sends the HV blocks as keys b01..b14 (not as an array)
                val v = json.optDouble(key)
                if (!v.isNaN()) values[key] = v
            }
        }
    }

    fun d(key: String): Double? = values[key]
    fun i(key: String): Int = values[key]?.toInt() ?: 0

    val coolant: Double? get() = d("ct")
    val wpWarn: Int get() = i("wpW")
    val cellWarn: Int get() = i("cellW")
    val doorMask: Int get() = i("door")
    val gear: Int get() = values["gear"]?.toInt() ?: -1

    companion object {
        val EMPTY = CanState(null)
        val GEARS = arrayOf("P", "R", "N", "D", "B")
    }
}

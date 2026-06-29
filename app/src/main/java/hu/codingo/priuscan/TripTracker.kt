package hu.codingo.priuscan

import org.json.JSONArray
import org.json.JSONObject

/**
 * One trip slot (live or a history record), straight from the firmware. The firmware owns and
 * persists everything; the app only displays. Fields: e=epoch o=odo d=dist v=ev f=fuel
 * m=move_s r=recovered electrical energy (kWh). Derived: avg consumption (l/100km), avg speed (km/h).
 */
data class TripSlot(
    val epoch: Long,
    val odo: Long,
    val dist: Double,
    val ev: Double,
    val fuel: Double,
    val moveS: Double,
    val regenKwh: Double,   // recovered electrical energy into the HV pack (kWh)
    val cityDist: Double = 0.0,   // km driven in a built-up area (belterület)
    val cityEv: Double = 0.0,     // pure-EV km in a built-up area
) {
    val avgCons: Double get() = if (dist > 0.1) fuel / dist * 100.0 else 0.0
    val avgKmh: Double get() = if (moveS > 5.0) dist / (moveS / 3600.0) else 0.0
    val cityEvPct: Int get() = if (cityDist > 0.1) (cityEv / cityDist * 100.0).toInt() else 0
    // recovered energy as equivalent EV distance. 15 km/kWh = this car's measured EV efficiency
    // (227 EV km logged: 15.3 km/kWh distance-weighted; per-drive CoV ~21%, stable in aggregate).
    val regenKm: Double get() = regenKwh * EV_KM_PER_KWH

    companion object {
        const val EV_KM_PER_KWH = 15.0
        val EMPTY = TripSlot(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        fun from(o: JSONObject) = TripSlot(
            o.optLong("e"), o.optLong("o"), o.optDouble("d"), o.optDouble("v"),
            o.optDouble("f"), o.optDouble("m"), o.optDouble("r"),
            o.optDouble("cd", 0.0), o.optDouble("ce", 0.0),   // default 0 (absent on pre-v3.42 firmware -> not NaN)
        )
        /** Parse a JSON array of slot objects (the firmware "slots"/"rhist"/"ohist"). */
        fun list(a: JSONArray?): List<TripSlot> =
            if (a == null) emptyList() else (0 until a.length()).map { from(a.getJSONObject(it)) }
    }
}

package hu.codingo.priuscan

import org.json.JSONArray
import org.json.JSONObject

/**
 * One trip slot (live or a history record), straight from the firmware. The firmware owns and
 * persists everything; the app only displays. Fields: e=epoch o=odo d=dist v=ev f=fuel
 * m=move_s r=recovered electrical energy (kWh) cd/ce=city km / city EV km ew=EV energy drawn (kWh).
 * Derived: avg consumption (l/100km), avg speed (km/h), EV kWh/100km.
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
    val evKwh: Double = 0.0,      // electrical energy drawn from the HV pack while in EV mode (kWh)
) {
    val avgCons: Double get() = if (dist > 0.1) fuel / dist * 100.0 else 0.0
    val avgKmh: Double get() = if (moveS > 5.0) dist / (moveS / 3600.0) else 0.0
    val cityEvPct: Int get() = if (cityDist > 0.1) (cityEv / cityDist * 100.0).toInt() else 0
    /** Measured EV consumption of this slot (kWh/100km); 0 until there is enough EV distance. */
    val evKwhPer100: Double get() = if (ev > 0.5 && evKwh > 0.0) evKwh / ev * 100.0 else 0.0
    /** km per kWh: the slot's OWN measured EV efficiency once it has a meaningful sample
     *  (>= 5 EV km and >= 0.3 kWh), else the fleet-wide 15 km/kWh fallback (pre-3.52 slots have no "ew"). */
    val evKmPerKwh: Double get() = if (ev >= 5.0 && evKwh >= 0.3) ev / evKwh else EV_KM_PER_KWH
    // recovered energy as equivalent EV distance, calibrated with the measured EV consumption.
    // 15 km/kWh fallback = this car's fleet-wide EV efficiency (227 EV km logged: 15.3 km/kWh
    // distance-weighted; per-drive CoV ~21%, stable in aggregate; per-tank 13-17 in the fwsim replay).
    val regenKm: Double get() = regenKwh * evKmPerKwh

    companion object {
        const val EV_KM_PER_KWH = 15.0
        /** First firmware whose slot "r" is recovered kWh; older firmware sent the regen ratio %. */
        const val FW_R_IS_KWH = 347
        val EMPTY = TripSlot(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        fun from(o: JSONObject, rIsKwh: Boolean = true) = TripSlot(
            o.optLong("e"), o.optLong("o"), o.optDouble("d"), o.optDouble("v"),
            o.optDouble("f"), o.optDouble("m"),
            // pre-3.47 firmware sent regen-% in "r" -- displaying that as kWh would show nonsense
            // ("62.00 kWh"); zero it until the firmware is updated
            if (rIsKwh) o.optDouble("r") else 0.0,
            o.optDouble("cd", 0.0), o.optDouble("ce", 0.0),   // default 0 (absent on pre-v3.42 firmware -> not NaN)
            o.optDouble("ew", 0.0),                           // EV kWh (absent on pre-v3.52 firmware)
        )
        /** Parse a JSON array of slot objects (the firmware "slots"/"rhist"/"ohist"). */
        fun list(a: JSONArray?, rIsKwh: Boolean = true): List<TripSlot> =
            if (a == null) emptyList() else (0 until a.length()).map { from(a.getJSONObject(it), rIsKwh) }
    }
}

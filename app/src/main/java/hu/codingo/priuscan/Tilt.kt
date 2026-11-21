package hu.codingo.priuscan

/** Car attitude for the bullseye level on the dashboard: the firmware's `pitch` / `roll` keys
 *  (fw >= 3.57, computed on the ESP from every G-sensor and wheel-speed frame, level zero applied).
 *  [pitch] deg, + = nose up; [roll] deg, + = right side lower. */
data class Tilt(val pitch: Double, val roll: Double, val speedKmh: Double)

fun CanState.tilt(): Tilt? {
    val p = d("pitch"); val r = d("roll")
    return if (p == null || r == null) null else Tilt(p, r, d("spd") ?: 0.0)
}

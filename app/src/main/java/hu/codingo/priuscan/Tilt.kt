package hu.codingo.priuscan

import kotlin.math.abs
import kotlin.math.asin

/** Car attitude from the G sensor, for the bullseye level on the dashboard.
 *  [pitch] deg, + = nose up; [roll] deg, + = right side lower. Zero offsets are NOT applied
 *  here (the UI subtracts the stored ones). */
data class Tilt(val pitch: Double, val roll: Double, val speedKmh: Double)

/** The accelerometers see gravity AND the car's own acceleration; subtracting the latter leaves
 *  the slope: pitch from `gFwd - dv/dt` (wheel speed), roll from `gLat - v * yaw`. Fed with the
 *  ~4 Hz firmware samples (fw >= 3.54: gFwd/gLat in m/s2, yaw in deg/s). Replayed on the raw dumps
 *  this keeps a flat-road drive within +-2..3 deg where the uncompensated reading swings +-7..12.
 *  The display never stops: while accelerating / braking / cornering the compensation is less
 *  exact, so the filter just follows more slowly (gain / (1 + (a/0.7)^2 + (lat/1.0)^2)) -- on the
 *  dumps that is steadier than a hard freeze above a threshold. */
class TiltEstimator {
    private var prevV = Double.NaN      // m/s, signed (negative in reverse)
    private var prevT = 0L
    private var acc = 0.0               // smoothed dv/dt, m/s2
    private var pitch = Double.NaN
    private var roll = Double.NaN

    fun reset() { prevV = Double.NaN; pitch = Double.NaN; roll = Double.NaN; acc = 0.0 }

    fun update(s: CanState, nowMs: Long): Tilt? {
        val gF = s.d("gFwd"); val gL = s.d("gLat"); val yaw = s.d("yaw")
        val wl = s.d("wRL"); val wr = s.d("wRR")
        if (gF == null || gL == null || yaw == null || wl == null || wr == null) { reset(); return null }
        // wheel speeds are unsigned -> sign from the gear (1 = R)
        val v = (wl + wr) / 2.0 / 3.6 * (if (s.gear == 1) -1.0 else 1.0)
        val dt = (nowMs - prevT) / 1000.0
        if (prevV.isNaN() || dt <= 0.0 || dt > 2.0) {          // first sample / gap: no usable dv/dt yet
            prevV = v; prevT = nowMs; acc = 0.0
            return if (pitch.isNaN()) null else Tilt(pitch, roll, abs(v) * 3.6)
        }
        acc += ((v - prevV) / dt - acc) * 0.5
        prevV = v; prevT = nowMs
        val lat = v * Math.toRadians(yaw)                       // centripetal, + = towards the right
        val p = Math.toDegrees(asin(((gF - acc) / G).coerceIn(-1.0, 1.0)))
        val r = Math.toDegrees(asin((-(gL - lat) / G).coerceIn(-1.0, 1.0)))
        if (pitch.isNaN()) { pitch = p; roll = r }
        else {
            val qa = acc / SOFT_ACC; val ql = lat / SOFT_LAT
            val k = (dt / TAU).coerceAtMost(1.0) / (1.0 + qa * qa + ql * ql)
            pitch += (p - pitch) * k; roll += (r - roll) * k
        }
        return Tilt(pitch, roll, abs(v) * 3.6)
    }

    private companion object {
        const val G = 9.81
        const val TAU = 1.5          // s, smoothing time constant
        const val SOFT_ACC = 0.7     // m/s2 longitudinal at which the filter gain halves
        const val SOFT_LAT = 1.0     // m/s2 lateral, likewise
    }
}

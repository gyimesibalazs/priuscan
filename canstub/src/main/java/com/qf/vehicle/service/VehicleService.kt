package com.qf.vehicle.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Minimal stand-in for the head unit's factory CAN service (QF_Canbus.apk, com.qf.vehicle).
 *
 * The factory app only speaks to a CAN-decoder box on a UART, which this car doesn't have, so the
 * reverse-camera app (com.qf.backcar) never gets a steering angle and its trajectory lines stay
 * straight. The clients bind by package name + action only (no signature check), so this app
 * takes over the package and implements the binder interface they use,
 * `com.qf.vehicle.service.ICanBusServiceFeature` (transaction codes read from the factory dex):
 *
 *  - every add/remove...CallBack and forward... call is accepted and ignored,
 *  - EXCEPT add/removeSWCAngleCallBack (7/8): those callbacks receive the steering packet,
 *  - generateNullProtocal / getProtocalStartOffset / getDataType answer null / 0 / 0, which is what
 *    the factory service returns with no car protocol selected.
 *
 * The steering angle arrives from the PriusCAN app as the same broadcast the factory service
 * itself emits ("com.qf.vehicle.action.swa": extra_swa = track image index 0..90, 45 = straight;
 * extra_angle = degrees). It is forwarded to the callbacks as the factory "packed data" frame:
 *   98 06 06 | angle lo, angle hi (bit7 = negative) | rate lo, hi | index lo, hi | FF
 * (QfSdkDataPack.packSwcAngle; the client hands the index to BackCarView.updateSwcAngle).
 */
class VehicleService : Service() {

    private val swcCallbacks = ArrayList<IBinder>()
    @Volatile private var lastFrame: ByteArray? = null

    private val feature = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(FEATURE); return true }
            if (code < FIRST_CALL_TRANSACTION || code > 36) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(FEATURE)
            when (code) {
                7 -> data.readStrongBinder()?.let { cb ->                 // addSWCAngleCallBack
                    synchronized(swcCallbacks) { if (cb !in swcCallbacks) swcCallbacks.add(cb) }
                    Log.d(TAG, "SWC callback added (${swcCallbacks.size})")
                    lastFrame?.let { send(cb, it) }                          // show the current angle at once
                }
                8 -> data.readStrongBinder()?.let { cb ->                 // removeSWCAngleCallBack
                    synchronized(swcCallbacks) { swcCallbacks.remove(cb) }
                }
            }
            reply?.writeNoException()
            when (code) {
                26 -> reply?.writeByteArray(null)      // generateNullProtocal(byte, byte): byte[]
                27 -> reply?.writeInt(0)               // getProtocalStartOffset(): int
                28 -> reply?.writeByte(0)              // getDataType(byte[]): byte
            }
            return true
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra("extra_swa", -1)
            if (level !in 0..90) return
            val frame = packSwcAngle(i.getIntExtra("extra_angle", 0), i.getFloatExtra("extra_rate", 0f), level)
            lastFrame = frame
            val dead = ArrayList<IBinder>()
            val cbs = synchronized(swcCallbacks) { ArrayList(swcCallbacks) }
            for (cb in cbs) if (!send(cb, frame)) dead.add(cb)
            if (dead.isNotEmpty()) synchronized(swcCallbacks) { swcCallbacks.removeAll(dead.toSet()) }
        }
    }

    /** ICanBusServiceCallback.onGetPackedData(byte[]) = transaction 2; oneway so a stuck client
     *  can never block us. Returns false when the client is gone. */
    private fun send(cb: IBinder, frame: ByteArray): Boolean {
        if (!cb.isBinderAlive) return false
        val p = Parcel.obtain()
        return try {
            p.writeInterfaceToken(CALLBACK)
            p.writeByteArray(frame)
            cb.transact(2, p, null, IBinder.FLAG_ONEWAY)
            true
        } catch (_: Exception) { false } finally { p.recycle() }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(receiver, IntentFilter(ACTION_SWA))
        Log.i(TAG, "PriusCAN CAN stub up")
    }

    override fun onDestroy() { try { unregisterReceiver(receiver) } catch (_: Exception) {}; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder = feature
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    companion object {
        const val TAG = "PriusCanStub"
        const val FEATURE = "com.qf.vehicle.service.ICanBusServiceFeature"
        const val CALLBACK = "com.qf.vehicle.service.ICanBusServiceCallback"
        const val ACTION_SWA = "com.qf.vehicle.action.swa"

        fun packSwcAngle(angle: Int, rate: Float, level: Int): ByteArray {
            val f = ByteArray(10)
            f[0] = 0x98.toByte(); f[1] = 6; f[2] = 6; f[9] = 0xFF.toByte()
            val a = kotlin.math.abs(angle)
            f[3] = (a and 0xFF).toByte()
            f[4] = (((a shr 8) and 0xFF) + (if (angle < 0) 0x80 else 0)).toByte()
            val r = rate.toInt()
            f[5] = (r and 0xFF).toByte(); f[6] = ((r shr 8) and 0xFF).toByte()
            f[7] = (level and 0xFF).toByte(); f[8] = ((level shr 8) and 0xFF).toByte()
            return f
        }
    }
}

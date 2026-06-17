package hu.codingo.priuscan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Megkeresi es megnyitja az ESP32-C6-ot (nativ USB Serial/JTAG,
 * VID 0x303A / PID 0x1001 - CDC-ACM kompatibilis), fallbackkent
 * a szokasos hidakat (CP2102/CH340) is ismeri a default proberen at.
 */
class SerialLink(private val ctx: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "hu.codingo.priuscan.USB_PERMISSION"
        const val BAUD = 115200
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    private val prober: UsbSerialProber by lazy {
        val table = ProbeTable()
        table.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)  // C6
        table.addProduct(0x303A, 0x1003, CdcAcmSerialDriver::class.java)  // C3
        UsbSerialProber(table)
    }

    /** Blokkolva probal portot nyitni; null, ha nincs eszkoz / nincs engedely. */
    fun openPort(): UsbSerialPort? {
        val drivers =
            prober.findAllDrivers(usb) + UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        val driver = drivers.firstOrNull() ?: return null
        val device = driver.device

        if (!usb.hasPermission(device) && !requestPermissionBlocking(device)) return null

        val conn = usb.openDevice(device) ?: return null
        val port = driver.ports.firstOrNull() ?: return null
        return try {
            port.open(conn)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            port
        } catch (e: Exception) {
            try { port.close() } catch (_: Exception) {}
            null
        }
    }

    /** Engedelykeres szinkron megvarassal (max 15 s). */
    private fun requestPermissionBlocking(device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(device, pi)
        latch.await(15, TimeUnit.SECONDS)
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        return granted
    }
}

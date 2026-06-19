package hu.codingo.priuscan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opens the 433 MHz TPMS USB receiver: a CH340 serial bridge (VID 0x1A86) at
 * 19200 8N1, binary 55 AA framing (see TPMS_PROTOCOL.md). Separate from
 * SerialLink (the ESP32 reader) so the two serial devices never collide:
 * SerialLink skips VID 0x1A86, this class only accepts it.
 */
class TpmsLink(private val ctx: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "hu.codingo.priuscan.USB_PERMISSION"
        const val BAUD = 19200
        const val CH340_VID = 0x1A86
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    /** An opened port plus its associated device (for identification). */
    data class Opened(val port: UsbSerialPort, val device: UsbDevice)

    /** Opens the CH340 TPMS receiver, blocking. Returns null if absent / no permission. */
    fun open(): Opened? {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
            .firstOrNull { it.device.vendorId == CH340_VID } ?: return null
        val device = driver.device

        if (!usb.hasPermission(device) && !requestPermissionBlocking(device)) return null

        val conn = usb.openDevice(device) ?: return null
        val port = driver.ports.firstOrNull() ?: return null
        return try {
            port.open(conn)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // no DTR/RTS toggling: the receiver does not need it
            Opened(port, device)
        } catch (e: Exception) {
            try { port.close() } catch (_: Exception) {}
            null
        }
    }

    /** Human-readable: product name + VID:PID + device name. */
    fun describe(d: UsbDevice): String {
        val vidpid = "%04X:%04X".format(d.vendorId, d.productId)
        val name = try { d.productName } catch (_: Exception) { null }
        return listOfNotNull(name?.takeIf { it.isNotBlank() }, vidpid, d.deviceName)
            .joinToString("  ·  ")
    }

    /** Permission request with synchronous wait (max 15 s). */
    private fun requestPermissionBlocking(device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
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

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
 * Finds and opens the ESP32-C6 (native USB Serial/JTAG,
 * VID 0x303A / PID 0x1001 - CDC-ACM compatible); as a fallback it
 * also recognizes the usual bridges (CP2102/CH340) via the default prober.
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

    /** An opened port plus its associated device (for identification). */
    data class Opened(val port: UsbSerialPort, val device: UsbDevice)

    /**
     * Tries to open a port, blocking. It skips the device IDs listed in `exclude`
     * - so if there are several serial devices on the bus (e.g. another ESP / TPMS),
     * CanService can iterate over them and pick the right one based on the DATA.
     * Returns null if there is no (further) device / no permission.
     */
    fun open(exclude: Set<Int> = emptySet()): Opened? {
        val drivers =
            prober.findAllDrivers(usb) + UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        // our own prober (303A) comes first -> we prefer it; skip the rejected ones.
        // VID 0x1A86 (CH340) is the TPMS receiver, owned by TpmsLink -> never grab it.
        val driver = drivers.firstOrNull {
            it.device.deviceId !in exclude && it.device.vendorId != TpmsLink.CH340_VID
        } ?: return null
        val device = driver.device

        if (!usb.hasPermission(device) && !requestPermissionBlocking(device)) return null

        val conn = usb.openDevice(device) ?: return null
        val port = driver.ports.firstOrNull() ?: return null
        return try {
            port.open(conn)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DON'T assert DTR/RTS: on the ESP32-C3 native USB Serial/JTAG these map internally to
            // CHIP_PU (reset) and GPIO9 (boot), so asserting them on connect REBOOTS the ESP (and
            // wipes its in-RAM trip state since the last NVS flush). USB-JTAG carries data regardless
            // of the control lines, so keep them deasserted -> the firmware keeps running on connect.
            port.dtr = false
            port.rts = false
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

package com.mcpintelligence.fr3k.integrations.blackwave

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

data class UsbRecoveryLinkStatus(
    val devicePresent: Boolean = false,
    val permissionGranted: Boolean = false,
    val ready: Boolean = false,
    val deviceLabel: String? = null,
    val detail: String = "not probed",
)

/**
 * Android USB-OTG bearer for the bounded BLACKWAVE recovery protocol.
 * Presence is never enough: ready requires a versioned HELLO response.
 */
class BlackwaveUsbRecoveryLink(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val decoder = BlackwaveUsbRecoveryDecoder()
    private val sequence = AtomicInteger(1)

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var activeDriver: UsbSerialDriver? = null
    @Volatile private var last = UsbRecoveryLinkStatus()

    val status: UsbRecoveryLinkStatus get() = last

    @Synchronized
    fun probeReady(): Boolean {
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull()
        if (driver == null) {
            closeLocked()
            last = UsbRecoveryLinkStatus(detail = "no USB serial device")
            return false
        }

        val label = deviceLabel(driver)
        if (!usbManager.hasPermission(driver.device)) {
            closeLocked()
            last = UsbRecoveryLinkStatus(true, false, false, label, "USB permission required")
            return false
        }

        return try {
            ensureOpen(driver)
            val seq = nextSequence()
            val hello = JSONObject()
                .put("protocol", PROTOCOL)
                .put("client", "fr3k-hud")
                .toString()
                .toByteArray(Charsets.UTF_8)
            send(UsbRecoveryFrame(UsbRecoveryType.HELLO, seq, hello))
            val response = awaitResponse(seq, HANDSHAKE_TIMEOUT_MS)
            val ok = response?.let(::isValidHello) == true
            last = UsbRecoveryLinkStatus(
                devicePresent = true,
                permissionGranted = true,
                ready = ok,
                deviceLabel = label,
                detail = if (ok) "authenticated BLACKWAVE USB recovery peer"
                else "serial device did not complete $PROTOCOL HELLO",
            )
            ok
        } catch (t: Throwable) {
            closeLocked()
            last = UsbRecoveryLinkStatus(true, true, false, label, t.message ?: "USB probe failed")
            false
        }
    }

    fun requestPermission(): Boolean {
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull() ?: return false
        if (usbManager.hasPermission(driver.device)) return true
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(driver.device, pending)
        return true
    }

    @Synchronized
    fun requestWirelessReconnect(): Boolean {
        if (!probeReady()) return false
        val seq = nextSequence()
        val payload = JSONObject()
            .put("protocol", PROTOCOL)
            .put("action", "reconnect_wireless")
            .put("reason", "wireless_unreachable")
            .toString()
            .toByteArray(Charsets.UTF_8)
        return try {
            send(UsbRecoveryFrame(UsbRecoveryType.RECONNECT_WIRELESS, seq, payload))
            val response = awaitResponse(seq, COMMAND_TIMEOUT_MS)
            response?.let(::responseOk) == true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun close() = closeLocked()

    private fun ensureOpen(driver: UsbSerialDriver) {
        if (activeDriver?.device?.deviceId == driver.device.deviceId && port?.isOpen == true) return
        closeLocked()
        val openedConnection = usbManager.openDevice(driver.device)
            ?: error("could not open USB device")
        val openedPort = driver.ports.firstOrNull()
            ?: run {
                openedConnection.close()
                error("USB serial driver has no port")
            }
        try {
            openedPort.open(openedConnection)
            openedPort.setParameters(
                BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            runCatching { openedPort.setDTR(true) }
            runCatching { openedPort.setRTS(true) }
        } catch (t: Throwable) {
            runCatching { openedPort.close() }
            openedConnection.close()
            throw t
        }
        connection = openedConnection
        port = openedPort
        activeDriver = driver
        decoder.reset()
    }

    private fun send(frame: UsbRecoveryFrame) {
        val active = port ?: error("USB recovery port not open")
        active.write(BlackwaveUsbRecoveryCodec.encode(frame), WRITE_TIMEOUT_MS)
    }

    private fun awaitResponse(sequence: Int, timeoutMs: Int): UsbRecoveryFrame? {
        val active = port ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buffer = ByteArray(4096)
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).toInt().coerceAtLeast(1)
            val count = active.read(buffer, minOf(READ_SLICE_MS, remaining))
            if (count <= 0) continue
            val frames = decoder.feed(buffer.copyOf(count))
            val match = frames.firstOrNull {
                it.sequence == sequence &&
                    (it.type == UsbRecoveryType.RESPONSE || it.type == UsbRecoveryType.ERROR)
            }
            if (match != null) return match
        }
        return null
    }

    private fun isValidHello(frame: UsbRecoveryFrame): Boolean {
        if (frame.type != UsbRecoveryType.RESPONSE) return false
        return runCatching {
            val body = JSONObject(frame.payload.toString(Charsets.UTF_8))
            body.optBoolean("ok", false) && body.optString("protocol") == PROTOCOL
        }.getOrDefault(false)
    }

    private fun responseOk(frame: UsbRecoveryFrame): Boolean {
        if (frame.type != UsbRecoveryType.RESPONSE) return false
        return runCatching {
            JSONObject(frame.payload.toString(Charsets.UTF_8)).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    private fun nextSequence(): Int = synchronized(sequence) {
        if (sequence.get() > 0xFFFF) sequence.set(1)
        sequence.getAndIncrement()
    }

    private fun deviceLabel(driver: UsbSerialDriver): String {
        val device = driver.device
        val maker = device.manufacturerName?.takeIf { it.isNotBlank() }
        val product = device.productName?.takeIf { it.isNotBlank() }
        return listOfNotNull(maker, product).joinToString(" ").ifBlank {
            "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
        }
    }

    private fun closeLocked() {
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
        activeDriver = null
        decoder.reset()
    }

    companion object {
        const val PROTOCOL = "fr3k-blackwave-usb-recovery/1"
        const val ACTION_USB_PERMISSION = "com.mcpintelligence.fr3k.BLACKWAVE_USB_PERMISSION"
        private const val BAUD_RATE = 115200
        private const val HANDSHAKE_TIMEOUT_MS = 1_200
        private const val COMMAND_TIMEOUT_MS = 1_500
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READ_SLICE_MS = 200
    }
}

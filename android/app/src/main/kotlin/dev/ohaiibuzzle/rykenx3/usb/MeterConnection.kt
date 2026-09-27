package dev.ohaiibuzzle.rykenx3.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import dev.ohaiibuzzle.rykenx3.protocol.DeviceInfo
import dev.ohaiibuzzle.rykenx3.protocol.Ryken
import dev.ohaiibuzzle.rykenx3.protocol.Sample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

class MeterException(val reason: Reason, val busy: Boolean = false) : Exception(reason.name) {
    enum class Reason { NO_DEVICE, PERMISSION, OPEN_FAILED, NO_ANSWER, IN_USE, BAD_SIGNATURE }
}

/**
 * The meter's vendor HID interface driven through the USB host API (Android has no
 * hidraw access for apps): 64-byte reports on the interrupt endpoints, no report ID.
 * Frames are read on a dedicated thread; [onSample] is called there for every
 * measurement with its monotonic receive time in ms.
 */
class MeterConnection private constructor(
    val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint?,
    private val onSample: (Double, Sample) -> Unit,
) {
    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 2000L
        private const val IO_TIMEOUT_MS = 200

        fun isMeter(d: UsbDevice) = d.vendorId == Ryken.VID && d.productId == Ryken.PID

        /** Opens and claims the meter's HID interface; throws [MeterException]. */
        fun open(usb: UsbManager, device: UsbDevice, onSample: (Double, Sample) -> Unit): MeterConnection {
            val conn = usb.openDevice(device) ?: throw MeterException(MeterException.Reason.OPEN_FAILED)
            val candidates = (0 until device.interfaceCount).map(device::getInterface)
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID && inEndpoint(it) != null }
            // Prefer the vendor-defined usage page (0xFF00+ / 0x00FF) if the device exposes several HID interfaces.
            val intf = candidates.firstOrNull { isVendor(conn, it) } ?: candidates.firstOrNull()
            if (intf == null || !conn.claimInterface(intf, true)) {
                conn.close()
                throw MeterException(MeterException.Reason.OPEN_FAILED)
            }
            val out = (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
                it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
            return MeterConnection(device, conn, intf, inEndpoint(intf)!!, out, onSample).also { it.startReader() }
        }

        private fun inEndpoint(intf: UsbInterface): UsbEndpoint? =
            (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }

        private fun isVendor(conn: UsbDeviceConnection, intf: UsbInterface): Boolean {
            val buf = ByteArray(512)
            // GET_DESCRIPTOR (HID report descriptor) to the interface
            val n = conn.controlTransfer(0x81, 0x06, 0x2200, intf.id, buf, buf.size, IO_TIMEOUT_MS)
            var k = 0
            while (k in 0 until n) {
                val prefix = buf[k].toInt() and 0xff
                val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
                if (prefix and 0xfc == 0x04) { // Usage Page
                    var page = 0
                    for (j in size downTo 1) page = (page shl 8) or (buf.getOrElse(k + j) { 0 }.toInt() and 0xff)
                    if (page >= 0xff00 || page == 0x00ff) return true
                }
                k += 1 + size
            }
            return false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    @Volatile private var running = true
    @Volatile private var handshakeWaiter: CompletableDeferred<ByteArray>? = null
    private var reader: Thread? = null
    private var claimed = false
    var info: DeviceInfo? = null
        private set
    @Volatile var badFrames = 0L
        private set

    private fun startReader() {
        reader = Thread({
            val buf = ByteArray(maxOf(epIn.maxPacketSize, Ryken.REPORT_SIZE))
            while (running) {
                val started = SystemClock.elapsedRealtime()
                val n = conn.bulkTransfer(epIn, buf, buf.size, IO_TIMEOUT_MS)
                if (n <= 0) {
                    // an immediate failure (e.g. unplugged) rather than a timeout: don't spin
                    if (SystemClock.elapsedRealtime() - started < 5) Thread.sleep(20)
                    continue
                }
                val t = SystemClock.elapsedRealtimeNanos() / 1e6
                val frame = Ryken.splitFrame(buf, n)
                when {
                    frame == null -> badFrames++
                    frame.cmd == Ryken.Cmd.MEASURE && frame.bytes.size >= 25 ->
                        onSample(t, Ryken.parseMeasurement(frame.bytes))
                    frame.cmd == Ryken.Cmd.HANDSHAKE -> handshakeWaiter?.complete(frame.bytes)
                }
            }
        }, "rk-x3-reader").apply { isDaemon = true; start() }
    }

    fun send(cmd: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val report = ByteArray(Ryken.REPORT_SIZE)
        Ryken.buildFrame(cmd, payload).copyInto(report)
        synchronized(writeLock) {
            val n = if (epOut != null) {
                conn.bulkTransfer(epOut, report, report.size, IO_TIMEOUT_MS)
            } else {
                // SET_REPORT (output, report ID 0) on the control pipe
                conn.controlTransfer(0x21, 0x09, 0x0200, intf.id, report, report.size, IO_TIMEOUT_MS)
            }
            return n == report.size
        }
    }

    /**
     * Handshake, verify the signature, then claim (which starts the ~167 Hz stream).
     * A claim left behind by a crashed host keeps the meter busy for ~5 s (heartbeat
     * timeout); [force] sends a disconnect first so it can be taken over immediately.
     */
    suspend fun connect(force: Boolean = false): DeviceInfo {
        if (force) {
            send(Ryken.Cmd.DISCONNECT, byteArrayOf(1))
            delay(100)
        }
        val ts = Ryken.timestamp(LocalDateTime.now())
        val waiter = CompletableDeferred<ByteArray>()
        handshakeWaiter = waiter
        send(Ryken.Cmd.HANDSHAKE, ts)
        // a still-claimed meter may ignore handshakes
        val f = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { waiter.await() }
            ?: throw MeterException(MeterException.Reason.NO_ANSWER, busy = true)
        handshakeWaiter = null
        val reply = Ryken.parseHandshake(f, ts)
        info = reply.info
        if (reply.busy) throw MeterException(MeterException.Reason.IN_USE, busy = true)
        if (!reply.valid) throw MeterException(MeterException.Reason.BAD_SIGNATURE)
        send(Ryken.Cmd.CLAIM, Ryken.claimPayload(reply.signature))
        claimed = true
        scope.launch {
            while (isActive) {
                delay(Ryken.HEARTBEAT_MS)
                send(Ryken.Cmd.HEARTBEAT, byteArrayOf(1))
            }
        }
        return reply.info
    }

    /** Blocking: stops the stream (if claimed), the reader and releases the device. */
    fun close() {
        scope.cancel()
        if (claimed) runCatching { send(Ryken.Cmd.DISCONNECT, byteArrayOf(1)) }
        claimed = false
        running = false
        reader?.join(1000)
        runCatching { conn.releaseInterface(intf) }
        conn.close()
    }
}

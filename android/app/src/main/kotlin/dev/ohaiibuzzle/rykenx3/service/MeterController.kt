package dev.ohaiibuzzle.rykenx3.service

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import dev.ohaiibuzzle.rykenx3.protocol.Sample
import dev.ohaiibuzzle.rykenx3.session.Session
import dev.ohaiibuzzle.rykenx3.session.stamp
import dev.ohaiibuzzle.rykenx3.usb.MeterConnection
import dev.ohaiibuzzle.rykenx3.usb.MeterException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * Owns the meter connection and the session. Lives as long as the process; the
 * [MeterService] only keeps the process in the foreground while a meter is in use.
 */
class MeterController(private val app: Application) {
    enum class Status { IDLE, CONNECTING, LIVE, UNPLUGGED, DISCONNECTED, ERROR }

    /** Snapshot for the UI, published every [UI_MS] while connected and after every change. */
    data class State(
        val status: Status = Status.IDLE,
        val deviceLabel: String = "",
        val stale: Boolean = false,
        val fps: Int = 0,
        val last: Sample? = null,
        val frames: Long = 0,
        val elapsedS: Double = 0.0,
        val charge: Double = 0.0,
        val energy: Double = 0.0,
        val avgPower: Double = Double.NaN,
        val minV: Double = Double.NaN,
        val maxV: Double = Double.NaN,
        val minI: Double = Double.NaN,
        val maxI: Double = Double.NaN,
        val maxP: Double = Double.NaN,
        val rows: Long = 0,
        val bytes: Long = 0,
        val exported: Boolean = true,
        val recording: Boolean = false,
        val intervalS: Double = DEFAULT_INTERVAL,
        /** Right edge of the chart view, ms since the session start. */
        val viewEnd: Double = 0.0,
    ) {
        val connected get() = status == Status.LIVE
    }

    class Error(val reason: MeterException.Reason, val canTakeOver: Boolean)

    companion object {
        const val UI_MS = 100L
        private const val FLUSH_MS = 2000L
        private const val STALE_MS = 1500.0
        const val DEFAULT_INTERVAL = 0.2
        val INTERVALS = listOf(0.0, 0.1, 0.2, 0.5, 1.0, 5.0)
        private const val ACTION_USB_PERMISSION = "dev.ohaiibuzzle.rykenx3.USB_PERMISSION"

        private fun now() = SystemClock.elapsedRealtimeNanos() / 1e6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val usb = app.getSystemService(UsbManager::class.java)
    private val prefs = app.getSharedPreferences("ryken", Context.MODE_PRIVATE)
    private val logFile = File(app.filesDir, "session.csv")

    /** Guards [session]; frames arrive on the reader thread. */
    private val lock = Any()
    private val session = Session(logFile).apply {
        intervalS = prefs.getFloat("interval", DEFAULT_INTERVAL.toFloat()).toDouble()
    }

    private var meter: MeterConnection? = null
    private var status = Status.IDLE
    private var reconnectPending = false // unplugged mid-session; reconnect when it comes back
    private var ticker: Job? = null
    private var fps = 0
    private var fpsFrames = 0L
    private var fpsT = 0.0

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _errors = MutableSharedFlow<Error>(extraBufferCapacity = 4)
    val errors: SharedFlow<Error> = _errors

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(d)
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (reconnectPending && MeterConnection.isMeter(d)) {
                        scope.launch {
                            delay(500)
                            connect(device = d, auto = true)
                        }
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Runs <T> with exclusive access to the session (chart drawing). */
    fun <T> withSession(block: (Session) -> T): T = synchronized(lock) { block(session) }

    // ---------------------------------------------------------------------------
    // Connection

    /**
     * Connects to [device] (or the first attached meter) and starts a new session;
     * [auto] reconnects continue the current one.
     */
    fun connect(force: Boolean = false, device: UsbDevice? = null, auto: Boolean = false) {
        if (status == Status.CONNECTING || meter != null) return
        status = Status.CONNECTING
        publish()
        scope.launch {
            var m: MeterConnection? = null
            try {
                val d = device ?: usb.deviceList.values.firstOrNull(MeterConnection::isMeter)
                    ?: throw MeterException(MeterException.Reason.NO_DEVICE)
                if (!usb.hasPermission(d) && !requestPermission(d)) {
                    throw MeterException(MeterException.Reason.PERMISSION)
                }
                val info = withContext(Dispatchers.IO) {
                    MeterConnection.open(usb, d, ::onSample).also { m = it }.connect(force)
                }
                meter = m
                reconnectPending = false
                val label = "${d.productName ?: "RK-X3"} · SN ${info.serial} · HW ${info.hardware} · FW ${info.firmware}"
                synchronized(lock) {
                    if (auto) session.device = label else session.reset(now(), label)
                }
                status = Status.LIVE
                fpsFrames = 0
                fpsT = now()
                startTicker()
                ContextCompat.startForegroundService(app, Intent(app, MeterService::class.java))
            } catch (e: MeterException) {
                m?.let { withContext(Dispatchers.IO) { it.close() } }
                status = if (reconnectPending) Status.UNPLUGGED else Status.ERROR
                _errors.tryEmit(Error(e.reason, e.busy))
            }
            publish()
        }
    }

    /** Called when the app is opened for an attached meter (USB device filter). */
    fun onLaunchedForDevice(d: UsbDevice) {
        if (!MeterConnection.isMeter(d) || meter != null) return
        // Don't start over on top of rows the user hasn't saved.
        if (reconnectPending) connect(device = d, auto = true)
        else if (synchronized(lock) { session.exported }) connect(device = d)
    }

    fun disconnect() {
        reconnectPending = false
        scope.launch { closeMeter(Status.DISCONNECTED) }
    }

    private fun onDetached(d: UsbDevice) {
        val m = meter ?: return
        if (m.device.deviceName != d.deviceName) return
        reconnectPending = true
        scope.launch { closeMeter(Status.UNPLUGGED) }
    }

    private suspend fun closeMeter(next: Status) {
        val m = meter
        meter = null
        ticker?.cancel()
        if (m != null) withContext(Dispatchers.IO) { m.close() }
        synchronized(lock) { session.flush() }
        if (m != null || status == Status.UNPLUGGED) status = next
        if (!reconnectPending) app.stopService(Intent(app, MeterService::class.java))
        publish()
    }

    private fun onSample(t: Double, s: Sample) {
        synchronized(lock) { session.onSample(t, s) }
    }

    private suspend fun requestPermission(d: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { app.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
        // Mutable: the system fills in the device and the result.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        usb.requestPermission(d, PendingIntent.getBroadcast(app, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    // ---------------------------------------------------------------------------
    // Session and log

    /** Starts over: a new session (and log file). */
    fun newSession() {
        synchronized(lock) { session.reset(now(), session.device.takeIf { meter != null } ?: "") }
        publish()
    }

    fun setInterval(seconds: Double) {
        synchronized(lock) { session.intervalS = seconds }
        prefs.edit { putFloat("interval", seconds.toFloat()) }
        publish()
    }

    fun setRecording(on: Boolean) {
        synchronized(lock) { session.recording = on }
        publish()
    }

    fun exportFileName(): String = "ryken_${stamp(synchronized(lock) { session.startedAt })}.csv"

    /** Copies the log to [uri] (from a CreateDocument picker) and marks it exported. */
    suspend fun exportTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val out = app.contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            out.use { copyLog(it) }
            true
        }.getOrDefault(false)
        if (ok) synchronized(lock) { session.exported = true }
        withContext(Dispatchers.Main) { publish() }
        ok
    }

    /** A content URI to a snapshot of the log for the share sheet. */
    suspend fun shareUri(): Uri = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, exportFileName())
        f.outputStream().use { copyLog(it) }
        FileProvider.getUriForFile(app, "${app.packageName}.files", f)
    }

    // The log file only grows, so copy up to its length at flush time without holding the lock.
    private fun copyLog(out: OutputStream) {
        val len = synchronized(lock) { session.flush() }
        logFile.inputStream().use { it.copyPrefix(out, len) }
    }

    private fun InputStream.copyPrefix(out: OutputStream, len: Long) {
        val buf = ByteArray(64 * 1024)
        var left = len
        while (left > 0) {
            val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    // ---------------------------------------------------------------------------
    // Publishing

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceFlush = 0L
            while (isActive) {
                publish()
                delay(UI_MS)
                sinceFlush += UI_MS
                if (sinceFlush >= FLUSH_MS) {
                    sinceFlush = 0
                    withContext(Dispatchers.IO) { synchronized(lock) { session.flush() } }
                }
            }
        }
    }

    private fun publish() {
        val now = now()
        _state.value = synchronized(lock) {
            val s = session
            if (now - fpsT >= 1000) {
                fps = ((s.frames - fpsFrames) * 1000 / (now - fpsT)).toInt()
                fpsFrames = s.frames
                fpsT = now
            }
            val connected = meter != null
            val endT = if (connected) now else s.lastT ?: s.start
            val h = s.history
            fun finite(v: Double) = if (v.isFinite()) v else Double.NaN
            State(
                status = status,
                deviceLabel = s.device,
                stale = connected && (s.lastT?.let { now - it > STALE_MS } ?: true),
                fps = if (connected) fps else 0,
                last = s.last,
                frames = s.frames,
                elapsedS = if (s.frames > 0) (endT - s.start) / 1000 else 0.0,
                charge = s.charge,
                energy = s.energy,
                avgPower = s.avgPower,
                minV = finite(s.min.voltage),
                maxV = finite(s.max.voltage),
                minI = finite(s.min.current),
                maxI = finite(s.max.current),
                maxP = finite(s.max.power),
                rows = s.rows,
                bytes = s.bytes,
                exported = s.exported,
                recording = s.recording,
                intervalS = s.intervalS,
                viewEnd = if (connected || h.n == 0) now - s.start else h.t[h.n - 1],
            )
        }
    }
}

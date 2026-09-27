package dev.ohaiibuzzle.rykenx3.session

import dev.ohaiibuzzle.rykenx3.protocol.Sample
import java.io.File
import java.io.Writer
import java.time.LocalDateTime
import java.util.Locale

/**
 * The session is also the log: every measurement feeds the stats and charts, and a
 * CSV row is appended to [logFile] every [intervalS] seconds from the session start.
 * Not thread-safe; the owner serialises access.
 */
class Session(private val logFile: File) {
    companion object {
        /** A longer pause between frames is a gap, not something to integrate over. */
        const val GAP_MS = 1000.0
        const val CSV_HEADER = "time,elapsed_s,voltage_V,current_A,power_W,dplus_V,dminus_V,cc1_V,cc2_V," +
            "temperature_raw,phone_power,charge_Ah,energy_Wh"
    }

    val history = History()

    /** Monotonic ms of the session start; frame times are relative to it. */
    var start = 0.0
        private set
    var startedAt: LocalDateTime = LocalDateTime.now()
        private set
    var device = ""
    var lastT: Double? = null
        private set
    var last: Sample? = null
        private set
    var frames = 0L
        private set
    var charge = 0.0 // Ah
        private set
    var energy = 0.0 // Wh
        private set
    var activeMs = 0.0
        private set
    val min = MinMax(Double.POSITIVE_INFINITY)
    val max = MinMax(Double.NEGATIVE_INFINITY)

    var rows = 0L
        private set
    var bytes = 0L
        private set
    var exported = true // nothing to lose yet

    /** Seconds between logged rows, 0 = every frame. */
    var intervalS = 0.2

    /** When off, frames still feed the stats and charts but no rows are logged. Off in a new session. */
    var recording = false
        set(value) {
            if (value && !field) lastRowT = Double.NEGATIVE_INFINITY // log the next frame right away
            field = value
        }

    private var lastRowT = Double.NEGATIVE_INFINITY
    private var writer: Writer? = null

    val avgPower: Double get() = if (activeMs > 0) energy / (activeMs / 3.6e6) else Double.NaN

    /** Start a fresh session and log file at monotonic time [now]. */
    fun reset(now: Double, device: String) {
        writer?.close()
        start = now
        startedAt = LocalDateTime.now()
        this.device = device
        lastT = null
        last = null
        frames = 0
        charge = 0.0
        energy = 0.0
        activeMs = 0.0
        min.fill(Double.POSITIVE_INFINITY)
        max.fill(Double.NEGATIVE_INFINITY)
        rows = 0
        bytes = 0
        exported = true
        recording = false
        lastRowT = Double.NEGATIVE_INFINITY
        history.clear()
        writer = logFile.bufferedWriter().also { it.write(CSV_HEADER + "\n") }
    }

    /** Runs for every frame (~167/s); [t] is monotonic ms. */
    fun onSample(t: Double, s: Sample) {
        val dt = lastT?.let { t - it } ?: Double.POSITIVE_INFINITY
        if (dt < GAP_MS) {
            val h = dt / 3.6e6
            charge += s.current * h
            energy += s.power * h
            activeMs += dt
        }
        lastT = t
        last = s
        frames++
        min.update(s) { a, b -> a < b }
        max.update(s) { a, b -> a > b }
        history.add(t - start, s)
        logRow(t, s)
    }

    private fun logRow(t: Double, s: Sample) {
        val w = writer ?: return
        if (!recording) return
        if (intervalS > 0 && t - lastRowT < intervalS * 1000) return
        lastRowT = t
        val row = listOf(
            localIso(LocalDateTime.now()), "%.3f".format(Locale.ROOT, (t - start) / 1000),
            g6(s.voltage), g6(s.current), g6(s.power),
            g6(s.dplus), g6(s.dminus), g6(s.cc1), g6(s.cc2),
            s.temperature?.toString() ?: "", g6(s.phonePower), g6(charge), g6(energy),
        ).joinToString(",")
        w.write(row)
        w.write("\n")
        rows++
        bytes += row.length + 1
        exported = false
    }

    /** Writes buffered rows to the log file; returns its length in bytes. */
    fun flush(): Long {
        writer?.flush()
        return if (logFile.exists()) logFile.length() else 0
    }

    fun close() {
        writer?.close()
        writer = null
    }

    class MinMax(initial: Double) {
        var voltage = initial
        var current = initial
        var power = initial

        fun fill(v: Double) {
            voltage = v; current = v; power = v
        }

        inline fun update(s: Sample, better: (Double, Double) -> Boolean) {
            if (better(s.voltage, voltage)) voltage = s.voltage
            if (better(s.current, current)) current = s.current
            if (better(s.power, power)) power = s.power
        }
    }
}

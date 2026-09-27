package dev.ohaiibuzzle.rykenx3.session

import dev.ohaiibuzzle.rykenx3.protocol.Sample
import kotlin.math.floor

/**
 * Chart history: 100 ms averages of V/I/P. When full, neighbouring points are
 * merged (halving resolution) so an entire session always fits.
 */
class History(private val cap: Int = 20000) {
    val t = DoubleArray(cap)
    private val v = FloatArray(cap)
    private val i = FloatArray(cap)
    private val p = FloatArray(cap)
    var n = 0
        private set
    private var bucketMs = 100.0

    private var accB = -1L
    private var accN = 0
    private var accT = 0.0
    private var accV = 0.0
    private var accI = 0.0
    private var accP = 0.0

    fun clear() {
        n = 0
        bucketMs = 100.0
        accN = 0
    }

    fun add(time: Double, s: Sample) {
        val b = floor(time / bucketMs).toLong()
        if (accN > 0 && accB != b) flush()
        if (accN == 0) {
            accB = b
            accT = 0.0; accV = 0.0; accI = 0.0; accP = 0.0
        }
        accT += time; accV += s.voltage; accI += s.current; accP += s.power
        accN++
    }

    /** Push the partially filled bucket. */
    fun flush() {
        if (accN == 0) return
        if (n == cap) compact()
        t[n] = accT / accN
        v[n] = (accV / accN).toFloat()
        i[n] = (accI / accN).toFloat()
        p[n] = (accP / accN).toFloat()
        n++
        accN = 0
    }

    private fun compact() {
        val half = n shr 1
        for (k in 0 until half) {
            t[k] = (t[2 * k] + t[2 * k + 1]) / 2
            v[k] = (v[2 * k] + v[2 * k + 1]) / 2
            i[k] = (i[2 * k] + i[2 * k + 1]) / 2
            p[k] = (p[2 * k] + p[2 * k + 1]) / 2
        }
        n = half
        bucketMs *= 2
    }

    fun series(key: Series): FloatArray = when (key) {
        Series.V -> v
        Series.I -> i
        Series.P -> p
    }

    /** First index with t >= value. */
    fun lowerBound(value: Double): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

enum class Series { V, I, P }

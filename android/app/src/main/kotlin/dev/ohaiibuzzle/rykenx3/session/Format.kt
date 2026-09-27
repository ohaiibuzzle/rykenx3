// Formatting helpers (ports of web/format.js).
package dev.ohaiibuzzle.rykenx3.session

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

fun clock(sec: Double, withTenths: Boolean = false): String {
    val neg = sec < 0
    val a = abs(sec)
    val h = floor(a / 3600).toInt()
    val m = floor((a % 3600) / 60).toInt()
    val s = floor(a % 60).toInt()
    var out = if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
    if (withTenths) out += "." + floor((a % 1) * 10).toInt()
    return (if (neg) "-" else "") + out
}

/** Like Python's "%.6g": up to 6 significant digits, no trailing zeros. */
fun g6(v: Double): String {
    if (!v.isFinite()) return v.toString()
    if (v == 0.0) return "0"
    return BigDecimal(v).round(MathContext(6)).stripTrailingZeros().toPlainString()
}

/** Fixed decimals for display, "–" for missing values. */
fun fix(v: Double?, decimals: Int): String =
    if (v == null || !v.isFinite()) "–" else "%.${decimals}f".format(Locale.ROOT, v)

private val ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

fun localIso(t: LocalDateTime): String = ISO_MS.format(t)

fun stamp(t: LocalDateTime): String = STAMP.format(t)

/** USB-C sink-side CC voltage (vRd) -> current advertised by the source. */
enum class CcState { OPEN, DEFAULT, A1_5, A3_0, UNKNOWN }

fun ccState(v: Double): CcState = when {
    v < 0.2 -> CcState.OPEN
    v <= 0.66 -> CcState.DEFAULT
    v <= 1.23 -> CcState.A1_5
    v <= 2.04 -> CcState.A3_0
    else -> CcState.UNKNOWN
}

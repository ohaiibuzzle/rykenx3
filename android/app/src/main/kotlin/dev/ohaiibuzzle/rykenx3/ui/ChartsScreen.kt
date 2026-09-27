package dev.ohaiibuzzle.rykenx3.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ohaiibuzzle.rykenx3.R
import dev.ohaiibuzzle.rykenx3.service.MeterController
import dev.ohaiibuzzle.rykenx3.session.History
import dev.ohaiibuzzle.rykenx3.session.Series
import dev.ohaiibuzzle.rykenx3.session.clock
import dev.ohaiibuzzle.rykenx3.session.fix
import dev.ohaiibuzzle.rykenx3.ui.theme.LocalSeries
import dev.ohaiibuzzle.rykenx3.ui.theme.Tabular
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val PAD_L = 52.dp
private val PAD_R = 8.dp
private val PAD_T = 22.dp
private val HOVER_SLOP = 24.dp

private class Window(val seconds: Int, @param:StringRes val label: Int)

private val WINDOWS = listOf(
    Window(30, R.string.win_30s),
    Window(120, R.string.win_2m),
    Window(600, R.string.win_10m),
    Window(3600, R.string.win_1h),
    Window(0, R.string.win_all),
)

private class ChartSpec(
    val series: Series,
    @param:StringRes val label: Int,
    val fromZero: Boolean,
    val minSpan: Double,
    val showTime: Boolean,
)

private val CHARTS = listOf(
    ChartSpec(Series.V, R.string.chart_v, fromZero = false, minSpan = 0.1, showTime = false),
    ChartSpec(Series.I, R.string.chart_i, fromZero = true, minSpan = 0.01, showTime = false),
    ChartSpec(Series.P, R.string.chart_p, fromZero = true, minSpan = 0.05, showTime = true),
)

private class Hover(val index: Int, val t: Double, val v: Float, val i: Float, val p: Float)

@Composable
fun ChartsScreen(controller: MeterController, state: MeterController.State, modifier: Modifier = Modifier) {
    val series = LocalSeries.current
    val density = LocalDensity.current
    var windowSec by rememberSaveable { mutableIntStateOf(120) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableIntStateOf(0) }
    var tipWidth by remember { mutableFloatStateOf(0f) }

    // Visible time range, ms since the session start.
    var t1 = state.viewEnd
    val t0 = if (windowSec > 0) {
        t1 - windowSec * 1000.0
    } else {
        controller.withSession { s -> if (s.history.n > 0) minOf(s.history.t[0], t1 - 10000) else t1 - 10000 }
    }
    t1 = max(t1, t0 + 1000)

    val hover = touchX?.let { x ->
        with(density) {
            controller.withSession { s -> findHover(s.history, x, width.toFloat(), t0, t1, PAD_L.toPx(), PAD_R.toPx(), HOVER_SLOP.toPx()) }
        }
    }

    Column(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val windowLabel = stringResource(R.string.charts_window)
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = windowLabel },
        ) {
            WINDOWS.forEachIndexed { i, w ->
                SegmentedButton(
                    selected = windowSec == w.seconds,
                    onClick = { windowSec = w.seconds },
                    shape = SegmentedButtonDefaults.itemShape(i, WINDOWS.size),
                    icon = {},
                ) { Text(stringResource(w.label), maxLines = 1) }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { width = it.width }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touchX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            touchX = change.position.x
                        }
                        touchX = null
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                val colors = listOf(series.volt, series.amp, series.watt)
                CHARTS.forEachIndexed { k, spec ->
                    Chart(
                        controller = controller,
                        spec = spec,
                        color = colors[k],
                        t0 = t0,
                        t1 = t1,
                        hover = hover?.index,
                        modifier = Modifier
                            .weight(if (spec.showTime) 1.1f else 1f)
                            .fillMaxWidth(),
                    )
                }
            }
            val x = touchX
            if (hover != null && x != null) {
                val gap = with(density) { 16.dp.toPx() }
                val left = if (x + gap + tipWidth > width) x - gap - tipWidth else x + gap
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), 0) }
                        .onSizeChanged { tipWidth = it.width.toFloat() },
                ) {
                    val style = MaterialTheme.typography.bodySmall.merge(Tabular)
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(clock(hover.t / 1000, true), style = style.copy(fontWeight = FontWeight.Bold))
                        Text("${fix(hover.v.toDouble(), 3)} V", style = style)
                        Text("${fix(hover.i.toDouble(), 4)} A", style = style)
                        Text("${fix(hover.p.toDouble(), 3)} W", style = style)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.charts_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun findHover(h: History, px: Float, width: Float, t0: Double, t1: Double, padL: Float, padR: Float, slop: Float): Hover? {
    val w = width - padL - padR
    if (h.n == 0 || w <= 0 || px < padL || px > width - padR) return null
    val t = t0 + (px - padL) / w * (t1 - t0)
    var k = h.lowerBound(t)
    if (k >= h.n || (k > 0 && t - h.t[k - 1] < h.t[k] - t)) k--
    if (k < 0 || h.t[k] < t0 || h.t[k] > t1) return null
    val x = padL + (h.t[k] - t0) / (t1 - t0) * w
    if (abs(x - px) > slop) return null
    return Hover(k, h.t[k], h.series(Series.V)[k], h.series(Series.I)[k], h.series(Series.P)[k])
}

@Composable
private fun Chart(
    controller: MeterController,
    spec: ChartSpec,
    color: Color,
    t0: Double,
    t1: Double,
    hover: Int?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = muted.copy(alpha = 0.13f)
    val labelStyle = MaterialTheme.typography.labelSmall.merge(Tabular).copy(color = muted)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            controller.withSession { s ->
                drawSeries(s.history, spec, t0, t1, hover, color, grid, muted, measurer, labelStyle)
            }
        }
        Text(
            stringResource(spec.label),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(start = PAD_L, top = 2.dp),
        )
    }
}

private fun DrawScope.drawSeries(
    h: History,
    spec: ChartSpec,
    t0: Double,
    t1: Double,
    hover: Int?,
    color: Color,
    grid: Color,
    muted: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val padL = PAD_L.toPx()
    val padT = PAD_T.toPx()
    val padB = (if (spec.showTime) 20.dp else 6.dp).toPx()
    val w = size.width - padL - PAD_R.toPx()
    val ht = size.height - padT - padB
    if (w <= 0 || ht <= 0) return
    val arr = h.series(spec.series)
    val ts = h.t

    var a = h.lowerBound(t0)
    val b = h.lowerBound(t1 + 1)
    if (a > 0) a-- // keep the line continuous at the left edge

    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (k in a until b) {
        if (arr[k] < lo) lo = arr[k].toDouble()
        if (arr[k] > hi) hi = arr[k].toDouble()
    }
    if (!lo.isFinite()) {
        lo = 0.0
        hi = spec.minSpan * 4
    }
    if (spec.fromZero && lo >= 0) lo = 0.0
    if (hi - lo < spec.minSpan) {
        val mid = (hi + lo) / 2
        lo = mid - spec.minSpan / 2
        hi = mid + spec.minSpan / 2
        if (spec.fromZero && lo < 0) {
            hi -= lo
            lo = 0.0
        }
    }
    val step = niceStep(hi - lo, if (ht < 110.dp.toPx()) 3 else 4)
    lo = floor(lo / step + 1e-9) * step
    hi = ceil(hi / step - 1e-9) * step
    if (hi <= lo) hi = lo + step
    fun y(v: Double) = (padT + ht - (v - lo) / (hi - lo) * ht).toFloat()
    fun x(t: Double) = (padL + (t - t0) / (t1 - t0) * w).toFloat()

    // y grid + labels
    val dec = decimalsFor(step)
    val labelGap = 8.dp.toPx()
    for (k in 0..((hi - lo) / step).roundToInt()) {
        val v = lo + k * step
        val yy = y(v)
        drawLine(grid, Offset(padL, yy), Offset(padL + w, yy), strokeWidth = 1f)
        val layout = measurer.measure(fix(v, dec), labelStyle)
        drawText(layout, topLeft = Offset(padL - labelGap - layout.size.width, yy - layout.size.height / 2f))
    }

    // time grid (+ labels on the bottom chart)
    val spanS = (t1 - t0) / 1000
    val tStep = timeStep(spanS, max(2, (w / 90.dp.toPx()).toInt()))
    var sec = ceil(t0 / 1000 / tStep) * tStep
    while (sec <= t1 / 1000) {
        val xx = x(sec * 1000)
        drawLine(grid, Offset(xx, padT), Offset(xx, padT + ht), strokeWidth = 1f)
        if (spec.showTime && sec >= 0) {
            val layout = measurer.measure(clock(sec), labelStyle)
            drawText(layout, topLeft = Offset(xx - layout.size.width / 2f, padT + ht + 3.dp.toPx()))
        }
        sec += tStep
    }

    // data: one min/max column per pixel so spikes survive downsampling
    if (b > a) {
        val line = Path()
        var started = false
        var col = Int.MIN_VALUE
        var first = 0.0
        var cMin = 0.0
        var cMax = 0.0
        var last = 0.0
        var firstX = 0f
        var lastX = 0f
        fun emit() {
            val xx = padL + col + 0.5f
            if (!started) {
                line.moveTo(xx, y(first))
                firstX = xx
                started = true
            } else {
                line.lineTo(xx, y(first))
            }
            if (cMin != cMax) {
                line.lineTo(xx, y(cMin))
                line.lineTo(xx, y(cMax))
            }
            line.lineTo(xx, y(last))
            lastX = xx
        }
        for (k in a until b) {
            val c = floor((ts[k] - t0) / (t1 - t0) * w).toInt()
            val v = arr[k].toDouble()
            if (c != col) {
                if (col != Int.MIN_VALUE) emit()
                col = c
                first = v; cMin = v; cMax = v; last = v
            } else {
                if (v < cMin) cMin = v
                if (v > cMax) cMax = v
                last = v
            }
        }
        emit()
        clipRect(padL, padT - 1, padL + w, padT + ht + 1) {
            if (spec.fromZero) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(lastX, y(lo))
                    lineTo(firstX, y(lo))
                    close()
                }
                drawPath(fill, color.copy(alpha = 0.12f))
            }
            drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
        }
    }

    if (hover != null && hover < h.n) {
        val xx = x(ts[hover])
        drawLine(
            muted, Offset(xx, padT), Offset(xx, padT + ht), strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
        drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(xx, y(arr[hover].toDouble())))
    }
}

private fun niceStep(range: Double, count: Int): Double {
    val raw = range / count
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    return (if (n <= 1) 1.0 else if (n <= 2) 2.0 else if (n <= 2.5) 2.5 else if (n <= 5) 5.0 else 10.0) * mag
}

private fun decimalsFor(step: Double): Int {
    val e = floor(log10(step)).toInt()
    val extra = if (abs(step / 10.0.pow(e) - 2.5) < 1e-9) 1 else 0
    return max(0, -e + extra)
}

private val TIME_STEPS = intArrayOf(
    1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400,
)

private fun timeStep(spanS: Double, maxTicks: Int): Double =
    (TIME_STEPS.firstOrNull { spanS / it <= maxTicks } ?: 86400).toDouble()

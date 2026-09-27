package dev.ohaiibuzzle.rykenx3.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.ohaiibuzzle.rykenx3.R
import dev.ohaiibuzzle.rykenx3.service.MeterController
import dev.ohaiibuzzle.rykenx3.session.CcState
import dev.ohaiibuzzle.rykenx3.session.ccState
import dev.ohaiibuzzle.rykenx3.session.clock
import dev.ohaiibuzzle.rykenx3.session.fix
import dev.ohaiibuzzle.rykenx3.ui.theme.LocalSeries
import dev.ohaiibuzzle.rykenx3.ui.theme.Tabular

@Composable
fun LiveScreen(state: MeterController.State, modifier: Modifier = Modifier) {
    val series = LocalSeries.current
    val s = state.last
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Reading(
            label = stringResource(R.string.card_power),
            value = fix(s?.power, 3),
            unit = "W",
            detail = stringResource(R.string.mm_avg_peak, fix(state.avgPower, 3), fix(state.maxP, 3)),
            color = series.watt,
            style = MaterialTheme.typography.displayMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Reading(
                label = stringResource(R.string.card_voltage),
                value = fix(s?.voltage, 3),
                unit = "V",
                detail = stringResource(R.string.mm_min_max, fix(state.minV, 3), fix(state.maxV, 3)),
                color = series.volt,
                modifier = Modifier.weight(1f),
            )
            Reading(
                label = stringResource(R.string.card_current),
                value = fix(s?.current, 4),
                unit = "A",
                detail = stringResource(R.string.mm_min_max, fix(state.minI, 4), fix(state.maxI, 4)),
                color = series.amp,
                modifier = Modifier.weight(1f),
            )
        }
        val mWh = state.energy * 1000
        Reading(
            label = stringResource(R.string.card_energy),
            value = if (mWh >= 10000) fix(state.energy, 3) else fix(mWh, 2),
            unit = if (mWh >= 10000) "Wh" else "mWh",
            detail = "${fix(state.charge * 1000, 2)} mAh · ${clock(state.elapsedS)}",
            color = series.energy,
        )
        DataLines(state)
    }
}

@Composable
private fun Reading(
    label: String,
    value: String,
    unit: String,
    detail: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
) {
    Panel(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = style.merge(Tabular), maxLines = 1)
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun DataLines(state: MeterController.State) {
    val series = LocalSeries.current
    val s = state.last
    Panel(title = stringResource(R.string.lines_title)) {
        LineRow("D+", s?.dplus, series.data, null)
        LineRow("D−", s?.dminus, series.data, null)
        LineRow("CC1", s?.cc1, series.cc, s?.let { ccLabel(ccState(it.cc1)) })
        LineRow("CC2", s?.cc2, series.cc, s?.let { ccLabel(ccState(it.cc2)) })
        Text(
            stringResource(R.string.lines_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ccLabel(state: CcState): String = when (state) {
    CcState.OPEN -> stringResource(R.string.cc_open)
    CcState.DEFAULT -> stringResource(R.string.cc_default)
    CcState.A1_5 -> "1.5 A"
    CcState.A3_0 -> "3.0 A"
    CcState.UNKNOWN -> "?"
}

@Composable
private fun LineRow(name: String, volts: Double?, color: Color, state: String?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(36.dp))
        Text(
            if (volts == null) "–" else "${fix(volts, 3)} V",
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            modifier = Modifier.width(64.dp),
        )
        LinearProgressIndicator(
            progress = { ((volts ?: 0.0) / 5).coerceIn(0.0, 1.0).toFloat() },
            color = color,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
            modifier = Modifier.weight(1f),
        )
        Text(
            state.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
    }
}

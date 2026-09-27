package dev.ohaiibuzzle.rykenx3.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ohaiibuzzle.rykenx3.R
import dev.ohaiibuzzle.rykenx3.service.MeterController
import dev.ohaiibuzzle.rykenx3.session.clock
import dev.ohaiibuzzle.rykenx3.session.fix
import dev.ohaiibuzzle.rykenx3.session.g6

@Composable
fun SessionScreen(
    state: MeterController.State,
    onNewSession: () -> Unit,
    onIntervalChange: (Double) -> Unit,
    onRecordingChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.last
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(
            title = stringResource(R.string.session_title),
            trailing = { TextButton(onClick = onNewSession) { Text(stringResource(R.string.btn_new_session)) } },
        ) {
            Text(
                state.deviceLabel.ifEmpty { stringResource(R.string.device_none) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow(stringResource(R.string.stat_duration), clock(state.elapsedS))
            StatRow(stringResource(R.string.stat_charge), "${fix(state.charge * 1000, 3)} mAh")
            StatRow(stringResource(R.string.stat_energy), "${fix(state.energy * 1000, 3)} mWh")
            StatRow(stringResource(R.string.stat_power), "${fix(state.avgPower, 3)} / ${fix(state.maxP, 3)} W")
            StatRow(stringResource(R.string.stat_vrange), "${fix(state.minV, 3)} – ${fix(state.maxV, 3)} V")
            StatRow(stringResource(R.string.stat_irange), "${fix(state.minI, 4)} – ${fix(state.maxI, 4)} A")
            StatRow(
                stringResource(R.string.stat_temp),
                if (s == null) "–" else s.temperature?.toString() ?: stringResource(R.string.stat_na),
            )
            StatRow(stringResource(R.string.stat_phone), if (s == null) "–" else fix(s.phonePower, 1))
            StatRow(stringResource(R.string.stat_frames), "${fmtInt(state.frames)} · ${state.fps}/s")
        }

        val logState = when {
            state.rows == 0L -> R.string.log_empty
            state.exported -> R.string.log_exported
            else -> R.string.log_unexported
        }
        Panel(
            title = stringResource(R.string.log_title),
            trailing = {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(logState)) },
                    colors = if (logState == R.string.log_unexported) {
                        AssistChipDefaults.assistChipColors(
                            disabledLabelColor = MaterialTheme.colorScheme.tertiary,
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (state.recording) R.string.log_recording else R.string.log_paused),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.recording, onCheckedChange = onRecordingChange)
            }
            IntervalPicker(state.intervalS, onIntervalChange)
            StatRow(stringResource(R.string.log_rows), fmtInt(state.rows))
            StatRow(stringResource(R.string.log_size), fmtSize(state.bytes))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onExport, enabled = state.rows > 0, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_export))
                }
                OutlinedButton(onClick = onShare, enabled = state.rows > 0, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_share))
                }
            }
            Text(
                stringResource(R.string.log_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalPicker(value: Double, onChange: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    @Composable
    fun label(v: Double) =
        if (v == 0.0) stringResource(R.string.log_every_frame) else stringResource(R.string.log_seconds, g6(v))

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label(value),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.log_interval)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeterController.INTERVALS.forEach { v ->
                DropdownMenuItem(
                    text = { Text(label(v)) },
                    onClick = {
                        expanded = false
                        onChange(v)
                    },
                )
            }
        }
    }
}

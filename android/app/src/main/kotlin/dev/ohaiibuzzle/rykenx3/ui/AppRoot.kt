package dev.ohaiibuzzle.rykenx3.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohaiibuzzle.rykenx3.R
import dev.ohaiibuzzle.rykenx3.service.MeterController
import dev.ohaiibuzzle.rykenx3.service.MeterController.Status
import dev.ohaiibuzzle.rykenx3.ui.theme.LocalSeries
import dev.ohaiibuzzle.rykenx3.usb.MeterException.Reason
import kotlinx.coroutines.launch

private enum class Tab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    LIVE(R.string.tab_live, R.drawable.ic_tab_live),
    CHARTS(R.string.tab_charts, R.drawable.ic_tab_charts),
    SESSION(R.string.tab_session, R.drawable.ic_tab_session),
}

private fun Reason.message() = when (this) {
    Reason.NO_DEVICE -> R.string.err_no_device
    Reason.PERMISSION -> R.string.err_permission
    Reason.OPEN_FAILED -> R.string.err_open
    Reason.NO_ANSWER -> R.string.err_no_answer
    Reason.IN_USE -> R.string.err_in_use
    Reason.BAD_SIGNATURE -> R.string.err_signature
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(controller: MeterController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pendingDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(controller) {
        controller.errors.collect { e ->
            val result = snackbar.showSnackbar(
                message = resources.getString(e.reason.message()),
                actionLabel = if (e.canTakeOver) resources.getString(R.string.btn_take_over) else null,
                withDismissAction = true,
                duration = if (e.canTakeOver) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) controller.connect(force = true)
        }
    }

    // Keep the screen on while live data is shown.
    val view = LocalView.current
    DisposableEffect(state.connected) {
        view.keepScreenOn = state.connected
        onDispose { view.keepScreenOn = false }
    }

    // Starting over drops rows that haven't been exported; ask first.
    fun guarded(action: () -> Unit) {
        if (!state.exported && state.rows > 0) pendingDiscard = action else action()
    }

    // Ask for notifications once so the ongoing notification shows live values; connect either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.connect()
    }
    val onConnect = {
        guarded {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                controller.connect()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = controller.exportTo(uri)
                snackbar.showSnackbar(resources.getString(if (ok) R.string.export_done else R.string.export_failed))
            }
        }
    }
    val onShare: () -> Unit = {
        scope.launch {
            val uri = controller.shareUri()
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        StatusLine(state)
                    }
                },
                actions = {
                    ConnectButton(state.status, onConnect = onConnect, onDisconnect = controller::disconnect)
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val content = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
        when (Tab.entries[tab]) {
            Tab.LIVE -> LiveScreen(state, content)
            Tab.CHARTS -> ChartsScreen(controller, state, content)
            Tab.SESSION -> SessionScreen(
                state = state,
                onNewSession = { guarded(controller::newSession) },
                onIntervalChange = controller::setInterval,
                onRecordingChange = controller::setRecording,
                onExport = { exportLauncher.launch(controller.exportFileName()) },
                onShare = onShare,
                modifier = content,
            )
        }
    }

    pendingDiscard?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text(stringResource(R.string.confirm_discard_title)) },
            text = { Text(stringResource(R.string.confirm_discard, fmtInt(state.rows))) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiscard = null
                    action()
                }) { Text(stringResource(R.string.btn_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscard = null }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }
}

@Composable
private fun StatusLine(state: MeterController.State) {
    val series = LocalSeries.current
    val warn = MaterialTheme.colorScheme.tertiary
    val (color, text) = when (state.status) {
        Status.CONNECTING -> warn to stringResource(R.string.status_connecting)
        Status.LIVE -> if (state.stale) {
            warn to stringResource(R.string.status_waiting)
        } else {
            series.energy to stringResource(R.string.status_live, state.fps)
        }
        Status.UNPLUGGED -> MaterialTheme.colorScheme.error to stringResource(R.string.status_unplugged)
        Status.ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.status_not_connected)
        Status.DISCONNECTED -> Color.Unspecified to stringResource(R.string.status_disconnected)
        Status.IDLE -> Color.Unspecified to stringResource(R.string.status_idle)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(color.takeOrElse { MaterialTheme.colorScheme.outline }, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectButton(status: Status, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val padding = PaddingValues(horizontal = 16.dp)
    Box(Modifier.padding(end = 8.dp)) {
        when (status) {
            Status.LIVE, Status.UNPLUGGED -> OutlinedButton(onClick = onDisconnect, contentPadding = padding) {
                Text(stringResource(R.string.btn_disconnect))
            }
            Status.CONNECTING -> Button(onClick = {}, enabled = false, contentPadding = padding) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            else -> Button(onClick = onConnect, contentPadding = padding) {
                Text(stringResource(R.string.btn_connect))
            }
        }
    }
}

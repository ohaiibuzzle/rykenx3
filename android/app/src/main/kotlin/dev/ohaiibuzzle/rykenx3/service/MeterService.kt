package dev.ohaiibuzzle.rykenx3.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ohaiibuzzle.rykenx3.MainActivity
import dev.ohaiibuzzle.rykenx3.R
import dev.ohaiibuzzle.rykenx3.RykenApp
import dev.ohaiibuzzle.rykenx3.session.clock
import dev.ohaiibuzzle.rykenx3.session.fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps logging alive with the app in the background or the screen off: an ongoing
 * notification with live values, and a partial wake lock while a meter is in use.
 */
class MeterService : Service() {
    companion object {
        private const val CHANNEL = "meter"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MS = 60_000L // renewed every update while running
        const val ACTION_DISCONNECT = "dev.ohaiibuzzle.rykenx3.DISCONNECT"
    }

    private val controller by lazy { (application as RykenApp).controller }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notif_channel))
                .setShowBadge(false)
                .build(),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RykenX3:logging")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(), type)
        if (intent?.action == ACTION_DISCONNECT) controller.disconnect()
        if (updater == null) {
            updater = scope.launch {
                while (isActive) {
                    delay(1000)
                    wakeLock?.acquire(WAKE_LOCK_MS)
                    update()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun update() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build())
    }

    private fun build(): Notification {
        val st = controller.state.value
        val s = st.last
        val title = when {
            st.status == MeterController.Status.UNPLUGGED -> getString(R.string.status_unplugged)
            st.stale -> getString(R.string.status_waiting)
            else -> getString(R.string.status_live, st.fps)
        }
        val text = if (s == null) "" else "${fix(s.voltage, 3)} V · ${fix(s.current, 4)} A · ${fix(s.power, 3)} W"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeterService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("${energyText(st.energy)} · ${clock(st.elapsedS)}")
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/** Session energy in mWh, switching to Wh from 10 Wh. */
fun energyText(wh: Double): String {
    val mWh = wh * 1000
    return if (mWh >= 10000) "${fix(wh, 3)} Wh" else "${fix(mWh, 2)} mWh"
}

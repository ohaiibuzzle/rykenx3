package dev.ohaiibuzzle.rykenx3

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import dev.ohaiibuzzle.rykenx3.ui.AppRoot
import dev.ohaiibuzzle.rykenx3.ui.theme.RykenTheme

class MainActivity : ComponentActivity() {
    private val controller get() = (application as RykenApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            RykenTheme {
                AppRoot(controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Opened by the system because the meter was plugged in.
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?.let(controller::onLaunchedForDevice)
    }
}

package dev.ohaiibuzzle.rykenx3.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/** Measurement series colors, fixed so V/I/P read the same as in the web app and exported charts. */
@Immutable
data class SeriesColors(
    val volt: Color,
    val amp: Color,
    val watt: Color,
    val energy: Color,
    val data: Color,
    val cc: Color,
)

private val LightSeries = SeriesColors(
    volt = Color(0xFFB87906),
    amp = Color(0xFF0A7FBD),
    watt = Color(0xFFA8369F),
    energy = Color(0xFF138A58),
    data = Color(0xFF138A58),
    cc = Color(0xFF4254D6),
)

private val DarkSeries = SeriesColors(
    volt = Color(0xFFF5B83D),
    amp = Color(0xFF4CC3FF),
    watt = Color(0xFFE36CF0),
    energy = Color(0xFF3ECF8E),
    data = Color(0xFF3ECF8E),
    cc = Color(0xFF8594FF),
)

val LocalSeries = staticCompositionLocalOf { LightSeries }

/** Tabular figures so live numbers don't jitter. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun RykenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFF5B83D), onPrimary = Color(0xFF1A1204))
        else -> lightColorScheme(primary = Color(0xFF8A5A00))
    }
    CompositionLocalProvider(LocalSeries provides if (dark) DarkSeries else LightSeries) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

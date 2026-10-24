package de.lltntplanet.adhsapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Farben angelehnt an Google Kalender. */
object AppColors {
    val GoogleBlue = Color(0xFF0B57D0)
    val GoogleBlueLight = Color(0xFFD3E3FD)
    val NowRed = Color(0xFFEA4335)
    val Birthday = Color(0xFFC2185B)
    val Task = Color(0xFF188038)
    val Health = Color(0xFF00897B)
    val Sleep = Color(0xFF5C6BC0)
    val Warning = Color(0xFFF9AB00)

    val SleepDeep = Color(0xFF3949AB)
    val SleepLight = Color(0xFF7986CB)
    val SleepRem = Color(0xFF26C6DA)
    val SleepAwake = Color(0xFFFF8A65)
}

private val LightColors = lightColorScheme(
    primary = AppColors.GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = AppColors.GoogleBlueLight,
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    tertiary = AppColors.Task,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F4F9),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    outlineVariant = Color(0xFFC4C7C5),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    tertiary = Color(0xFF6DD58C),
    background = Color(0xFF131314),
    surface = Color(0xFF131314),
    surfaceVariant = Color(0xFF1E1F20),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerLow = Color(0xFF1B1B1C),
    surfaceContainerHigh = Color(0xFF282A2C),
    outlineVariant = Color(0xFF444746),
)

@Composable
fun AdhsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

/** Kalenderfarbe für Text darauf: weiß oder fast schwarz, je nach Helligkeit. */
fun contentColorFor(background: Color): Color {
    val luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
    return if (luminance > 0.62) Color(0xFF1F1F1F) else Color.White
}

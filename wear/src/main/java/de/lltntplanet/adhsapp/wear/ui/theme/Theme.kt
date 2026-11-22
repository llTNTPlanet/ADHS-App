package de.lltntplanet.adhsapp.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Farben wie in der Handy-App (angelehnt an Google Kalender). */
object WearColors {
    val GoogleBlue = Color(0xFF0B57D0)
    val NowRed = Color(0xFFEA4335)
    val Birthday = Color(0xFFC2185B)
    val Task = Color(0xFF188038)
    val Medication = Color(0xFF6D28D9)
    val Routine = Color(0xFFD97706)
    val Mood = Color(0xFFDB2777)
    val Health = Color(0xFF00897B)
    val Sleep = Color(0xFF5C6BC0)
    val Warning = Color(0xFFF9AB00)

    /**
     * Farbe zur Art einer Erinnerung oder Routine, wie am Handy. [kind] ist EVENT, BIRTHDAY, TASK, MEDICATION,
     * ROUTINE oder CHECKUP; alles andere bekommt das Blau der App.
     */
    fun forKind(kind: String): Color = when (kind) {
        "BIRTHDAY" -> Birthday
        "TASK" -> Task
        "MEDICATION" -> Medication
        "ROUTINE" -> Routine
        "CHECKUP" -> Mood
        else -> GoogleBlue
    }

    /**
     * Aufgehellt für den schwarzen Hintergrund der Uhr: Die kräftigen Farben der Handy-App wären als Schrift
     * oder Symbol dort schlecht lesbar.
     */
    fun onDark(color: Color): Color = lerp(color, Color.White, 0.45f)

    /** Schwarz oder Weiß – je nachdem, was auf [background] besser lesbar ist. */
    fun readableOn(background: Color): Color = if (background.luminance() > 0.4f) Color.Black else Color.White
}

/** Die dunklen Farben der Handy-App; alles Übrige (Hintergrund, Flächen, Fehler) bleibt beim Standard der Uhr. */
private val WearScheme = ColorScheme(
    primary = Color(0xFFA8C7FA),
    primaryDim = Color(0xFF7CACF8),
    primaryContainer = Color(0xFF0842A0),
    onPrimary = Color(0xFF062E6F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    secondaryDim = Color(0xFF5AB3E6),
    secondaryContainer = Color(0xFF004A77),
    onSecondary = Color(0xFF003355),
    onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C),
    tertiaryDim = Color(0xFF37BE5F),
    tertiaryContainer = Color(0xFF0F5223),
    onTertiary = Color(0xFF0A3818),
    onTertiaryContainer = Color(0xFFC4EED0),
)

/** Das Aussehen aller Bildschirme der Uhr-App. */
@Composable
fun AdhsWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WearScheme, content = content)
}

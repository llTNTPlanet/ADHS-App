package de.lltntplanet.adhsapp.ui.mood

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.MoodEntryEntity
import de.lltntplanet.adhsapp.data.mood.MoodLogic
import de.lltntplanet.adhsapp.data.mood.SymptomStats
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.abs

/** Zeitraum der Statistik in Tagen. */
enum class StatsRange(val days: Int, val label: String) {
    WEEK(7, "7 Tage"),
    MONTH(30, "30 Tage"),
    QUARTER(90, "90 Tage"),
}

/** Lädt Symptome und Werte (inklusive Vorzeitraum für den Trend) und rechnet die Statistik aus. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodStatsViewModel(private val c: AppContainer) : ViewModel() {
    val range = MutableStateFlow(StatsRange.WEEK)
    private val today = LocalDate.now()

    private val entries: StateFlow<List<MoodEntryEntity>> = range
        .flatMapLatest { r -> c.moods.observeEntries(today.minusDays(2L * r.days - 1), today) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<List<SymptomStats>?> = combine(c.moods.observeSymptoms(), entries, range) { symptoms, e, r ->
        val from = today.minusDays(r.days - 1L)
        // Aktive Symptome immer, entfernte nur, wenn sie im Zeitraum noch Werte haben.
        MoodLogic.stats(symptoms, e, from, today).filter { it.symptom.active || it.count > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val checkups: StateFlow<Int> = combine(entries, range) { e, r ->
        MoodLogic.checkupCount(e, today.minusDays(r.days - 1L), today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/** Statistik der Gefühlslage: je Symptom Durchschnitt, Trend und Verlauf als Linie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodStatsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = appViewModel { MoodStatsViewModel(it) }
    val range by vm.range.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val checkups by vm.checkups.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Gefühlslage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    StatsRange.entries.forEach { r ->
                        FilterChip(selected = range == r, onClick = { vm.range.value = r }, label = { Text(r.label) })
                    }
                }
            }
            item {
                Text(
                    when (checkups) {
                        0 -> "Noch kein Checkup in diesem Zeitraum."
                        1 -> "1 Checkup in den letzten ${range.days} Tagen."
                        else -> "$checkups Checkups in den letzten ${range.days} Tagen."
                    } + " Niedrige Werte sind gut – 1 heißt „gar nicht“, 10 „sehr stark“.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            val list = stats
            if (list != null && list.isEmpty()) {
                item {
                    InfoCard(
                        icon = Icons.Filled.Mood,
                        title = "Noch keine Symptome",
                        text = "Lege unter „Routinen“ einen Gefühls-Checkup an. Sobald du ihn ausfüllst, erscheint hier der Verlauf jedes Symptoms.",
                        buttonText = "Zu den Routinen",
                        onClick = onBack,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            items(list.orEmpty(), key = { it.symptom.id }) { s -> SymptomCard(s) }
        }
    }
}

@Composable
private fun SymptomCard(s: SymptomStats) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("symptom-card"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.symptom.name + if (!s.symptom.active) " (entfernt)" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TrendLine(s)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        s.average?.let { "%.1f".format(TimeFormat.locale, it) } ?: "–",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = s.average?.let { averageColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Ø", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.count == 0) {
                Text(
                    "Keine Werte in diesem Zeitraum.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Spacer(Modifier.height(12.dp))
                LineChart(s.days.map { it.average }, color = AppColors.Mood)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(TimeFormat.dayMonth(s.days.first().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Min ${s.min} · Max ${s.max} · Zuletzt ${s.last}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** „▼ 0,8 weniger als davor“ – Vergleich mit dem gleich langen Zeitraum zuvor. */
@Composable
private fun TrendLine(s: SymptomStats) {
    val change = s.change
    val (icon, text, color) = when {
        change == null -> Triple(Icons.AutoMirrored.Filled.TrendingFlat, "Kein Vergleich zum Vorzeitraum", MaterialTheme.colorScheme.onSurfaceVariant)
        abs(change) < 0.3f -> Triple(Icons.AutoMirrored.Filled.TrendingFlat, "Unverändert zum Vorzeitraum", MaterialTheme.colorScheme.onSurfaceVariant)
        change < 0 -> Triple(Icons.AutoMirrored.Filled.TrendingDown, "%.1f weniger als davor".format(TimeFormat.locale, -change), AppColors.Task)
        else -> Triple(Icons.AutoMirrored.Filled.TrendingUp, "%.1f stärker als davor".format(TimeFormat.locale, change), MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.width(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun averageColor(avg: Float): Color = when {
    avg <= 3.5f -> AppColors.Task
    avg <= 6.5f -> AppColors.Warning
    else -> MaterialTheme.colorScheme.error
}

/**
 * Verlauf als Linie über den Zeitraum (Skala 1–10). Tage ohne Checkup lassen eine Lücke;
 * einzelne Tage werden als Punkt gezeichnet.
 */
@Composable
fun LineChart(values: List<Float?>, color: Color, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        if (values.isEmpty()) return@Canvas
        val min = MoodLogic.MIN_VALUE.toFloat()
        val max = MoodLogic.MAX_VALUE.toFloat()
        val inset = 6f
        fun y(v: Float) = inset + (1f - (v - min) / (max - min)) * (size.height - 2 * inset)
        fun x(i: Int) = if (values.size == 1) size.width / 2 else inset + i * (size.width - 2 * inset) / (values.size - 1)
        // Hilfslinien bei 1, 5 und 10
        listOf(1f, 5f, 10f).forEach { v ->
            drawLine(grid, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        val path = Path()
        var open = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                open = false
                return@forEachIndexed
            }
            val p = Offset(x(i), y(v))
            if (open) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
            open = true
            val lonely = (i == 0 || values[i - 1] == null) && (i == values.lastIndex || values[i + 1] == null)
            if (lonely || values.size <= 14) drawCircle(color, radius = 7f, center = p)
        }
        drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

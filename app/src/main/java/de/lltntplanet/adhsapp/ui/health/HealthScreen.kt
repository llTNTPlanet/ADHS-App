package de.lltntplanet.adhsapp.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.google.GoogleFeature
import de.lltntplanet.adhsapp.data.google.NotAuthorizedException
import de.lltntplanet.adhsapp.data.google.TokenResult
import de.lltntplanet.adhsapp.data.health.HealthDay
import de.lltntplanet.adhsapp.data.health.HealthSnapshot
import de.lltntplanet.adhsapp.data.health.SleepNight
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.components.rememberGoogleConnector
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime

class HealthViewModel(private val c: AppContainer) : ViewModel() {
    val snapshot: StateFlow<HealthSnapshot?> = c.health.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _needsReconnect = MutableStateFlow(false)
    val needsReconnect: StateFlow<Boolean> = _needsReconnect.asStateFlow()

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            runCatching { c.health.refresh() }
                .onSuccess { _needsReconnect.value = false }
                .onFailure { e ->
                    if (e is NotAuthorizedException) _needsReconnect.value = true
                    _messages.tryEmit(e.message ?: "Gesundheitsdaten konnten nicht geladen werden")
                }
            _loading.value = false
        }
    }

    fun refreshIfStale() {
        val last = snapshot.value?.fetchedAt ?: 0L
        if (settings.value.healthConnected && System.currentTimeMillis() - last > 15 * 60_000L) refresh()
    }

    fun onConnectResult(result: TokenResult) {
        when (result) {
            is TokenResult.Token -> viewModelScope.launch {
                c.settings.update { it.copy(healthConnected = true) }
                _needsReconnect.value = false
                refresh()
            }
            is TokenResult.Failed -> _messages.tryEmit(result.message)
            is TokenResult.NeedsConsent -> Unit
        }
    }

    /** Freigabe kappen und neu verbinden – der Google-Zustimmungsbildschirm erscheint erneut. */
    fun reconnect(connect: (GoogleFeature) -> Unit) {
        viewModelScope.launch {
            c.auth.reset(GoogleFeature.HEALTH)
            connect(GoogleFeature.HEALTH)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            c.auth.reset(GoogleFeature.HEALTH)
            c.health.clear()
            c.settings.update { it.copy(healthConnected = false) }
        }
    }
}

private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(TimeFormat.locale)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { HealthViewModel(it) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val needsReconnect by vm.needsReconnect.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val connect = rememberGoogleConnector { _, result -> vm.onConnectResult(result) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(settings.healthConnected) { vm.refreshIfStale() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Gesundheit") },
                actions = {
                    if (settings.healthConnected) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Aktualisieren") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!settings.healthConnected || needsReconnect) {
                InfoCard(
                    icon = Icons.Filled.HealthAndSafety,
                    title = if (needsReconnect) "Google Health neu verbinden" else "Google Health verbinden",
                    text = "Zeigt Schritte, Schlaf, Ruhepuls und Aktivität aus deinem Google-Health-Konto (Fitbit, Pixel Watch …). " +
                        "Die Daten werden nur gelesen und bleiben auf deinem Handy.",
                    buttonText = "Mit Google verbinden",
                    onClick = { connect(GoogleFeature.HEALTH) },
                )
                Text(
                    "Einmalig nötig: Google-Cloud-Projekt mit aktivierter „Google Health API“ und Android-OAuth-Client – " +
                        "die Schritt-für-Schritt-Anleitung steht in der README des Projekts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val s = snapshot
            if (s != null && settings.healthConnected) {
                HealthContent(s, settings.stepGoal)
                Text(
                    "Zuletzt aktualisiert: ${TimeFormat.relativeDay(de.lltntplanet.adhsapp.util.Dates.toLocalDate(s.fetchedAt))}, " +
                        TimeFormat.time(s.fetchedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = { vm.reconnect(connect) }) { Text("Neu verbinden") }
                    TextButton(onClick = vm::disconnect) { Text("Google Health trennen") }
                }
            } else if (settings.healthConnected && loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HealthContent(s: HealthSnapshot, stepGoal: Int) {
    val today = LocalDate.now()
    val todayData = s.day(today)
    val sleep = s.lastSleep

    adhdHint(todayData, sleep, stepGoal)?.let { hint ->
        InfoCard(
            icon = Icons.Filled.Lightbulb,
            title = "Tipp für heute",
            text = hint,
            container = MaterialTheme.colorScheme.primaryContainer,
        )
    }

    HealthCard(Icons.AutoMirrored.Filled.DirectionsWalk, "Schritte", AppColors.Health) {
        val steps = todayData?.steps ?: 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
                CircularProgressIndicator(
                    progress = { (steps.toFloat() / stepGoal).coerceIn(0f, 1f) },
                    modifier = Modifier.size(112.dp),
                    strokeWidth = 10.dp,
                    color = AppColors.Health,
                    trackColor = AppColors.Health.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(numberFormat.format(steps), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("heute", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ziel: ${numberFormat.format(stepGoal)}", style = MaterialTheme.typography.bodyMedium)
                todayData?.distanceMeters?.let { Stat("Strecke", "%.1f km".format(TimeFormat.locale, it / 1000)) }
                todayData?.activeZoneMinutes?.let { Stat("Aktivzonenminuten", "$it Min.") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Letzte 7 Tage", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        BarChart(
            values = s.days.map { it.steps?.toFloat() ?: 0f },
            labels = s.days.map { TimeFormat.weekdayNarrow(it.date.dayOfWeek) },
            goal = stepGoal.toFloat(),
            color = AppColors.Health,
        )
    }

    HealthCard(Icons.Filled.Bedtime, "Schlaf", AppColors.Sleep) {
        if (sleep == null) {
            Text(s.errors["Schlaf"] ?: "Keine Schlafdaten in den letzten 7 Tagen.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                TimeFormat.duration(sleep.minutesAsleep),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${TimeFormat.relativeDay(sleep.date, today)} · ${TimeFormat.time(sleep.start)} – ${TimeFormat.time(sleep.end)}" +
                    if (sleep.minutesAwake > 0) " · ${sleep.minutesAwake} Min. wach" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SleepStages(sleep)
            Spacer(Modifier.height(12.dp))
            Text("Schlafdauer der letzten Nächte", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            val nights = (0..6).map { today.minusDays((6 - it).toLong()) }
            BarChart(
                values = nights.map { d -> s.sleepNights.find { it.date == d }?.minutesAsleep?.toFloat()?.div(60f) ?: 0f },
                labels = nights.map { TimeFormat.weekdayNarrow(it.dayOfWeek) },
                goal = 7.5f,
                color = AppColors.Sleep,
            )
        }
    }

    HealthCard(Icons.Filled.Favorite, "Ruhepuls", AppColors.NowRed) {
        val latest = s.latestRestingHeartRate
        if (latest == null) {
            Text(s.errors["Ruhepuls"] ?: "Keine Ruhepuls-Daten.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${latest.bpm}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("Schläge/Min.", Modifier.padding(bottom = 4.dp))
            }
            val avg = s.restingHeartRate.map { it.bpm }.average()
            Text(
                "${TimeFormat.relativeDay(latest.date, today)} · Ø 7 Tage: ${avg.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    todayData?.kcal?.let { kcal ->
        HealthCard(Icons.Filled.LocalFireDepartment, "Kalorien heute", AppColors.Warning) {
            Text("${numberFormat.format(kcal.toLong())} kcal", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }

    val otherErrors = s.errors.filterKeys { it !in setOf("Schlaf", "Ruhepuls") }
    if (otherErrors.isNotEmpty()) {
        Text(
            "Nicht geladen: " + otherErrors.entries.joinToString("; ") { "${it.key}: ${it.value}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Freundliche, ADHS-orientierte Hinweise aus den Daten. */
private fun adhdHint(today: HealthDay?, sleep: SleepNight?, stepGoal: Int): String? {
    val now = LocalTime.now()
    val lastNight = sleep?.takeIf { it.date == LocalDate.now() }
    return when {
        lastNight != null && lastNight.minutesAsleep < 6 * 60 ->
            "Kurze Nacht (${TimeFormat.duration(lastNight.minutesAsleep)}). Plane heute bewusst Pausen ein und " +
                "erledige Wichtiges lieber früh. Sei nachsichtig mit dir."
        now.hour >= 15 && (today?.steps ?: 0L) < stepGoal * 0.3 ->
            "Ein 10-Minuten-Spaziergang kann Fokus und Stimmung spürbar verbessern."
        lastNight != null && lastNight.minutesAsleep >= 7 * 60 ->
            "Gut geschlafen – ein guter Tag, um eine aufgeschobene Aufgabe anzugehen."
        else -> null
    }
}

@Composable
private fun HealthCard(icon: ImageVector, title: String, color: Color, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SleepStages(sleep: SleepNight) {
    val stages = listOfNotNull(
        sleep.deep?.let { Triple("Tief", it, AppColors.SleepDeep) },
        sleep.light?.let { Triple("Leicht", it, AppColors.SleepLight) },
        sleep.rem?.let { Triple("REM", it, AppColors.SleepRem) },
        sleep.minutesAwake.takeIf { it > 0 }?.let { Triple("Wach", it, AppColors.SleepAwake) },
    )
    val total = stages.sumOf { it.second }.coerceAtLeast(1)
    if (stages.size < 2) return
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(14.dp)) {
        stages.forEach { (_, minutes, color) ->
            Box(Modifier.weight(minutes.toFloat().coerceAtLeast(1f)).fillMaxSize().background(color))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stages.forEach { (label, minutes, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text("$label ${minutes * 100 / total}%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Einfaches Balkendiagramm mit gestrichelter Ziellinie. */
@Composable
private fun BarChart(values: List<Float>, labels: List<String>, goal: Float, color: Color) {
    val max = maxOf(values.maxOrNull() ?: 0f, goal) * 1.1f
    val track = MaterialTheme.colorScheme.outlineVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            if (values.isEmpty() || max <= 0f) return@Canvas
            val slot = size.width / values.size
            val barWidth = slot * 0.55f
            values.forEachIndexed { i, v ->
                val h = (v / max) * size.height
                drawRoundRect(
                    color = if (v >= goal) color else color.copy(alpha = 0.55f),
                    topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
            }
            val gy = size.height - (goal / max) * size.height
            drawLine(
                track,
                Offset(0f, gy),
                Offset(size.width, gy),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

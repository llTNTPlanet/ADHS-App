package de.lltntplanet.adhsapp.ui.nutrition

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DinnerDining
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.nutrition.CalorieBalance
import de.lltntplanet.adhsapp.data.nutrition.FoodRecognition
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionProvider
import de.lltntplanet.adhsapp.data.nutrition.PhotoScaler
import de.lltntplanet.adhsapp.data.nutrition.RecognizedFood
import de.lltntplanet.adhsapp.data.nutrition.NutritionMath
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.settings.Sex
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.io.File

/** Alles, was die Seite für einen Tag braucht. */
data class NutritionDay(
    val date: LocalDate,
    val entries: List<FoodEntryEntity>,
    val balance: CalorieBalance,
    /** Grundumsatz stammt aus dem Profil (sonst Standardwert). */
    val profileComplete: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NutritionViewModel(private val c: AppContainer) : ViewModel() {
    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val refreshing: StateFlow<Boolean> = c.refresher.refreshing

    val day: StateFlow<NutritionDay?> = combine(
        _date.flatMapLatest { d -> c.nutrition.observeDay(d).combine(MutableStateFlow(d)) { e, dd -> dd to e } },
        settings,
        c.health.snapshot,
    ) { (d, entries), s, health ->
        val basal = NutritionMath.basalMetabolicRate(s, d)
        val burned = if (s.healthConnected) health?.day(d)?.kcal else null
        NutritionDay(
            date = d,
            entries = entries,
            balance = NutritionMath.balance(basal ?: NutritionMath.DEFAULT_BASAL, burned, s.calorieAdjustment, entries),
            profileComplete = basal != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previousDay() {
        _date.value = _date.value.minusDays(1)
    }

    fun nextDay() {
        if (_date.value.isBefore(LocalDate.now())) _date.value = _date.value.plusDays(1)
    }

    fun today() {
        _date.value = LocalDate.now()
    }

    fun refreshAll() = c.refresher.refreshLater(userInitiated = true)

    suspend fun recent(): List<FoodEntryEntity> = c.nutrition.recent()

    fun save(entry: FoodEntryEntity) {
        viewModelScope.launch { c.nutrition.save(entry) }
    }

    fun delete(entry: FoodEntryEntity) {
        viewModelScope.launch { c.nutrition.delete(entry) }
    }

    /** Welcher Erkenner ist eingestellt und hat er einen Schlüssel? */
    val visionProvider: StateFlow<FoodVisionProvider> = settings.map { it.foodVisionProvider }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FoodVisionProvider.GEMINI)
    val visionConfigured: StateFlow<Boolean> = combine(
        settings.map { it.foodVisionProvider },
        c.secrets.geminiApiKey,
        c.secrets.anthropicApiKey,
    ) { p, gemini, claude -> !(if (p == FoodVisionProvider.CLAUDE) claude else gemini).isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.foodVision.configured)

    private val _analysis = MutableStateFlow<PhotoAnalysis>(PhotoAnalysis.Idle)
    val analysis: StateFlow<PhotoAnalysis> = _analysis.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun setApiKey(provider: FoodVisionProvider, key: String) = when (provider) {
        FoodVisionProvider.GEMINI -> c.secrets.setGeminiApiKey(key)
        FoodVisionProvider.CLAUDE -> c.secrets.setAnthropicApiKey(key)
    }

    /** Foto verkleinern, an die Erkennung schicken und das Ergebnis zur Prüfung anzeigen. */
    fun analyzePhoto(uri: Uri, deleteAfter: Boolean) {
        if (_analysis.value is PhotoAnalysis.Running) return
        _analysis.value = PhotoAnalysis.Running
        viewModelScope.launch {
            val result = runCatching {
                val jpeg = PhotoScaler.toJpeg(c.app.contentResolver, uri)
                c.foodVision.analyze(jpeg)
            }
            if (deleteAfter) runCatching { c.app.contentResolver.delete(uri, null, null) }
            _analysis.value = result.fold(
                onSuccess = { PhotoAnalysis.Done(it) },
                onFailure = { PhotoAnalysis.Failed(it.message ?: "Erkennung fehlgeschlagen") },
            )
        }
    }

    fun dismissAnalysis() {
        _analysis.value = PhotoAnalysis.Idle
    }

    /** Erkannte Lebensmittel als einzelne Einträge speichern. */
    fun saveRecognized(items: List<RecognizedFood>, meal: MealType) {
        val epochDay = _date.value.toEpochDay()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            items.forEachIndexed { i, item ->
                c.nutrition.save(FoodEntryEntity(epochDay = epochDay, meal = meal, name = item.name.trim(), kcal = item.kcal, amount = item.amount.trim(), createdAt = now + i))
            }
            _messages.tryEmit("${items.size} ${if (items.size == 1) "Eintrag" else "Einträge"} aus dem Foto eingetragen")
        }
        _analysis.value = PhotoAnalysis.Idle
    }

    fun saveProfile(sex: Sex?, birthYear: Int?, heightCm: Int?, weightKg: Double?, adjustment: Int) {
        viewModelScope.launch {
            c.settings.update {
                it.copy(sex = sex, birthYear = birthYear, heightCm = heightCm, weightKg = weightKg, calorieAdjustment = adjustment)
            }
        }
    }
}

private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(TimeFormat.locale)
private fun kcal(value: Int): String = numberFormat.format(value)

object NutritionColors {
    val Basal = Color(0xFF5C6BC0)
    val Activity = Color(0xFF26A69A)
    val Eaten = Color(0xFFFF8F00)
    val Remaining = Color(0xFF43A047)
    val Over = Color(0xFFE53935)
}

private val MealType.icon: ImageVector
    get() = when (this) {
        MealType.BREAKFAST -> Icons.Filled.FreeBreakfast
        MealType.LUNCH -> Icons.Filled.LunchDining
        MealType.DINNER -> Icons.Filled.DinnerDining
        MealType.SNACK -> Icons.Filled.Cookie
    }

private val MealType.color: Color
    get() = when (this) {
        MealType.BREAKFAST -> Color(0xFFF9A825)
        MealType.LUNCH -> Color(0xFFEF6C00)
        MealType.DINNER -> Color(0xFF8E24AA)
        MealType.SNACK -> Color(0xFF00ACC1)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { NutritionViewModel(it) }
    val day by vm.day.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<MealType?>(null) }
    var editing by remember { mutableStateOf<FoodEntryEntity?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var showPhotoSource by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    val visionConfigured by vm.visionConfigured.collectAsStateWithLifecycle()
    val visionProvider by vm.visionProvider.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val today = LocalDate.now()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Kamera: Foto landet im Cache der App und wird nach der Erkennung gelöscht.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.analyzePhoto(uri, deleteAfter = true) else uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        cameraUri = null
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.analyzePhoto(uri, deleteAfter = false)
    }
    fun startCamera() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "essen-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri
        runCatching { takePicture.launch(uri) }.onFailure { cameraUri = null }
    }
    fun startPhoto() {
        if (visionConfigured) showPhotoSource = true else showApiKey = true
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Ernährung") },
                actions = {
                    IconButton(onClick = { showProfile = true }) { Icon(Icons.Filled.Person, "Profil für den Grundumsatz") }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { startPhoto() },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    if (analysis is PhotoAnalysis.Running) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.PhotoCamera, "Essen fotografieren")
                    }
                }
                FloatingActionButton(
                    onClick = { adding = NutritionMath.mealFor(LocalTime.now()) },
                    shape = RoundedCornerShape(16.dp),
                ) { Icon(Icons.Filled.Add, "Essen eintragen") }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DayNavigator(
                    date = date,
                    today = today,
                    onPrevious = vm::previousDay,
                    onNext = vm::nextDay,
                    onToday = vm::today,
                )
                val d = day
                if (d != null) {
                    BalanceCard(d.balance, healthConnected = settings.healthConnected)
                    if (!d.profileComplete) {
                        InfoCard(
                            icon = Icons.Filled.Person,
                            title = "Grundumsatz berechnen",
                            text = "Bis du dein Profil (Geschlecht, Geburtsjahr, Größe, Gewicht) einträgst, rechnet die App mit " +
                                "${kcal(NutritionMath.DEFAULT_BASAL)} kcal Grundumsatz.",
                            buttonText = "Profil eintragen",
                            onClick = { showProfile = true },
                        )
                    }
                    if (analysis is PhotoAnalysis.Running) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Foto wird erkannt … das dauert ein paar Sekunden.")
                            }
                        }
                    }
                    MealType.entries.forEach { meal ->
                        MealCard(
                            meal = meal,
                            entries = d.entries.filter { it.meal == meal },
                            onAdd = { adding = meal },
                            onEdit = { editing = it },
                            onDelete = vm::delete,
                        )
                    }
                }
                Spacer(Modifier.height(88.dp))
            }
        }
    }

    adding?.let { meal ->
        FoodEntryDialog(
            initial = FoodEntryEntity(epochDay = date.toEpochDay(), meal = meal, name = "", kcal = 0),
            loadRecent = vm::recent,
            onDismiss = { adding = null },
            onSave = {
                vm.save(it)
                adding = null
            },
        )
    }
    editing?.let { entry ->
        FoodEntryDialog(
            initial = entry,
            loadRecent = vm::recent,
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
        )
    }
    if (showPhotoSource) {
        PhotoSourceDialog(
            onDismiss = { showPhotoSource = false },
            onCamera = { showPhotoSource = false; startCamera() },
            onGallery = {
                showPhotoSource = false
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
    if (showApiKey) {
        ApiKeyDialog(
            provider = visionProvider,
            onDismiss = { showApiKey = false },
            onSave = { key ->
                vm.setApiKey(visionProvider, key)
                showApiKey = false
                showPhotoSource = true
            },
        )
    }
    when (val a = analysis) {
        is PhotoAnalysis.Done -> RecognitionDialog(
            result = a.result,
            defaultMeal = NutritionMath.mealFor(LocalTime.now()),
            onDismiss = vm::dismissAnalysis,
            onSave = vm::saveRecognized,
        )
        is PhotoAnalysis.Failed -> AlertDialog(
            onDismissRequest = vm::dismissAnalysis,
            title = { Text("Erkennung fehlgeschlagen") },
            text = { Text(a.message) },
            confirmButton = { TextButton(onClick = vm::dismissAnalysis) { Text("OK") } },
            dismissButton = if (!visionConfigured || a.message.contains("Schlüssel")) {
                { TextButton(onClick = { vm.dismissAnalysis(); showApiKey = true }) { Text("Schlüssel eintragen") } }
            } else null,
        )
        else -> Unit
    }
    if (showProfile) {
        ProfileDialog(
            settings = settings,
            onDismiss = { showProfile = false },
            onSave = { sex, year, height, weight, adjustment ->
                vm.saveProfile(sex, year, height, weight, adjustment)
                showProfile = false
            },
        )
    }
}

@Composable
private fun DayNavigator(date: LocalDate, today: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorheriger Tag") }
        Column(Modifier.weight(1f).clickable(onClick = onToday), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(TimeFormat.relativeDay(date, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                TimeFormat.fullDate(date, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = date.isBefore(today)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Tag") }
    }
}

/** Die bunte Übersicht: Ring (gegessen / übrig) und die vier Zahlen. */
@Composable
private fun BalanceCard(b: CalorieBalance, healthConnected: Boolean) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Restaurant, null, tint = NutritionColors.Eaten)
                Spacer(Modifier.width(8.dp))
                Text("Kalorien-Bilanz", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalorieRing(b, Modifier.size(150.dp))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Legend(NutritionColors.Basal, "Grundumsatz", "${kcal(b.basal)} kcal")
                    Legend(
                        NutritionColors.Activity,
                        "Bewegung & Training",
                        if (b.activityKnown) "+${kcal(b.activity)} kcal" else "–",
                    )
                    if (b.adjustment != 0) {
                        Legend(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            if (b.adjustment < 0) "Defizit" else "Zuschlag",
                            "${if (b.adjustment > 0) "+" else ""}${kcal(b.adjustment)} kcal",
                        )
                    }
                    Legend(NutritionColors.Eaten, "Gegessen", "${kcal(b.eaten)} kcal")
                    Legend(
                        if (b.over) NutritionColors.Over else NutritionColors.Remaining,
                        if (b.over) "Drüber" else "Noch frei",
                        "${kcal(kotlin.math.abs(b.remaining))} kcal",
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            BudgetBar(b)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !healthConnected -> "Verbinde Google Health, dann zählt deine Bewegung automatisch mit."
                    !b.activityKnown -> "Für diesen Tag hat Google Health noch keinen Verbrauch gemeldet."
                    else -> "Bewegung laut Google Health – für heute noch unvollständig, der Wert wächst über den Tag."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Ring: Budget = ganzer Kreis, gegessen orange, Rest grün; drüber = roter Überlauf. */
@Composable
private fun CalorieRing(b: CalorieBalance, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val eatenColor = if (b.over) NutritionColors.Over else NutritionColors.Eaten
    val remainingColor = NutritionColors.Remaining
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val eatenSweep = (b.eatenFraction.coerceIn(0f, 1f) * 360f)
            if (!b.over && eatenSweep < 360f) {
                drawArc(remainingColor, -90f + eatenSweep, 360f - eatenSweep, false, topLeft, arcSize, style = Stroke(stroke))
            }
            if (eatenSweep > 0f) {
                drawArc(eatenColor, -90f, eatenSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                kcal(kotlin.math.abs(b.remaining)),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (b.over) NutritionColors.Over else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (b.over) "kcal drüber" else "kcal frei",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Balken: Grundumsatz + Bewegung = Budget, darunter der gegessene Anteil. */
@Composable
private fun BudgetBar(b: CalorieBalance) {
    val total = maxOf(b.budget, b.eaten, 1)
    Column {
        Row(Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))) {
            val basalShare = (b.basal.coerceAtMost(b.budget).toFloat() / total).coerceIn(0f, 1f)
            val activityShare = ((b.budget - b.basal.coerceAtMost(b.budget)).toFloat() / total).coerceIn(0f, 1f)
            if (basalShare > 0f) Box(Modifier.fillMaxHeight().weight(basalShare).background(NutritionColors.Basal, RoundedCornerShape(7.dp)))
            if (activityShare > 0f) Box(Modifier.fillMaxHeight().weight(activityShare).background(NutritionColors.Activity, RoundedCornerShape(7.dp)))
            val rest = 1f - basalShare - activityShare
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))) {
            val eatenShare = (b.eaten.toFloat() / total).coerceIn(0f, 1f)
            if (eatenShare > 0f) {
                Box(
                    Modifier.fillMaxHeight().weight(eatenShare)
                        .background(if (b.over) NutritionColors.Over else NutritionColors.Eaten, RoundedCornerShape(7.dp)),
                )
            }
            if (eatenShare < 0.999f) Spacer(Modifier.weight(1f - eatenShare))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Budget ${kcal(b.budget)} kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Gegessen ${kcal(b.eaten)} kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MealCard(
    meal: MealType,
    entries: List<FoodEntryEntity>,
    onAdd: () -> Unit,
    onEdit: (FoodEntryEntity) -> Unit,
    onDelete: (FoodEntryEntity) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(meal.color.copy(alpha = 0.18f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(meal.icon, null, tint = meal.color)
                }
                Spacer(Modifier.width(12.dp))
                Text(meal.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${kcal(entries.sumOf { it.kcal })} kcal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = meal.color,
                )
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Zu ${meal.label} hinzufügen") }
            }
            if (entries.isEmpty()) {
                Text(
                    "Noch nichts eingetragen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
            }
            entries.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEdit(e) }.padding(start = 48.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        val detail = listOfNotNull(e.amount.takeIf { it.isNotBlank() }, TimeFormat.time(e.createdAt)).joinToString(" · ")
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${kcal(e.kcal)} kcal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { onDelete(e) }) {
                        Icon(Icons.Filled.Delete, "Löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodEntryDialog(
    initial: FoodEntryEntity,
    loadRecent: suspend () -> List<FoodEntryEntity>,
    onDismiss: () -> Unit,
    onSave: (FoodEntryEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var kcalText by remember { mutableStateOf(if (initial.kcal > 0) initial.kcal.toString() else "") }
    var amount by remember { mutableStateOf(initial.amount) }
    var meal by remember { mutableStateOf(initial.meal) }
    var recent by remember { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }
    LaunchedEffect(Unit) { recent = runCatching { loadRecent() }.getOrDefault(emptyList()) }
    val kcalValue = kcalText.trim().toIntOrNull()
    val valid = name.isNotBlank() && kcalValue != null && kcalValue >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Essen eintragen" else "Eintrag bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Was hast du gegessen?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = kcalText,
                        onValueChange = { v -> kcalText = v.filter { it.isDigit() } },
                        label = { Text("kcal") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Menge") },
                        placeholder = { Text("z. B. 200 g") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealType.entries.forEach { m ->
                        FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.label) })
                    }
                }
                val suggestions = recent.filter { it.name.isNotBlank() && (name.isBlank() || it.name.contains(name, ignoreCase = true)) }.take(6)
                if (suggestions.isNotEmpty()) {
                    Text("Zuletzt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { r ->
                            AssistChip(
                                onClick = {
                                    name = r.name
                                    kcalText = r.kcal.toString()
                                    amount = r.amount
                                },
                                label = { Text("${r.name} · ${kcal(r.kcal)}") },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(initial.copy(name = name.trim(), kcal = kcalValue ?: 0, amount = amount.trim(), meal = meal))
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Profil für den Grundumsatz; wird auch aus den Einstellungen geöffnet. */
@Composable
fun ProfileDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (sex: Sex?, birthYear: Int?, heightCm: Int?, weightKg: Double?, adjustment: Int) -> Unit,
) {
    var sex by remember { mutableStateOf(settings.sex) }
    var year by remember { mutableStateOf(settings.birthYear?.toString().orEmpty()) }
    var height by remember { mutableStateOf(settings.heightCm?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(settings.weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    var adjustment by remember { mutableStateOf(settings.calorieAdjustment) }
    val yearValue = year.toIntOrNull()?.takeIf { it in 1900..LocalDate.now().year }
    val heightValue = height.toIntOrNull()?.takeIf { it in 100..250 }
    val weightValue = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
    val bmr = NutritionMath.basalMetabolicRate(sex, yearValue, heightValue, weightValue)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profil für den Grundumsatz") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sex.entries.forEach { s ->
                        FilterChip(selected = sex == s, onClick = { sex = s }, label = { Text(s.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { v -> year = v.filter { it.isDigit() }.take(4) },
                        label = { Text("Geburtsjahr") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { v -> height = v.filter { it.isDigit() }.take(3) },
                        label = { Text("Größe (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = weight,
                    onValueChange = { v -> weight = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                    label = { Text("Gewicht (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Tägliche Anpassung", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -300, 0, 300).forEach { a ->
                        FilterChip(
                            selected = adjustment == a,
                            onClick = { adjustment = a },
                            label = { Text(if (a == 0) "Halten" else "${if (a > 0) "+" else ""}$a kcal") },
                        )
                    }
                }
                Text(
                    if (bmr != null) "Grundumsatz: ${kcal(bmr)} kcal pro Tag (Mifflin-St Jeor)"
                    else "Alle vier Angaben nötig – sonst rechnet die App mit ${kcal(NutritionMath.DEFAULT_BASAL)} kcal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bmr != null) NutritionColors.Basal else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Diese Angaben bleiben auf deinem Handy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sex, yearValue, heightValue, weightValue, adjustment) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Zustand der Foto-Erkennung. */
sealed interface PhotoAnalysis {
    data object Idle : PhotoAnalysis
    data object Running : PhotoAnalysis
    data class Done(val result: FoodRecognition) : PhotoAnalysis
    data class Failed(val message: String) : PhotoAnalysis
}

/** Dialog: Foto aufnehmen oder aus der Galerie wählen. */
@Composable
private fun PhotoSourceDialog(onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Essen fotografieren") },
        text = {
            Text(
                "Fotografiere deinen Teller oder die Verpackung (gern die Nährwerttabelle). " +
                    "Die App erkennt die Lebensmittel und schätzt die Kalorien – du kannst alles vor dem Eintragen anpassen.",
            )
        },
        confirmButton = { TextButton(onClick = onCamera) { Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Kamera") } },
        dismissButton = { TextButton(onClick = onGallery) { Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Galerie") } },
    )
}

/** Erkannte Lebensmittel prüfen, anpassen und eintragen. */
@Composable
private fun RecognitionDialog(
    result: FoodRecognition,
    defaultMeal: MealType,
    onDismiss: () -> Unit,
    onSave: (items: List<RecognizedFood>, meal: MealType) -> Unit,
) {
    var items by remember { mutableStateOf(result.items) }
    var selected by remember { mutableStateOf(result.items.indices.toSet()) }
    var meal by remember { mutableStateOf(result.mealGuess ?: defaultMeal) }
    val total = items.filterIndexed { i, _ -> i in selected }.sumOf { it.kcal }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.isEmpty()) "Nichts erkannt" else "Erkannt: ${kcal(total)} kcal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.note.isNotBlank()) {
                    Text(result.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (items.isEmpty()) {
                    Text("Auf dem Foto war kein Essen zu erkennen. Versuch es mit mehr Licht oder näher dran.")
                }
                items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = i in selected,
                            onCheckedChange = { selected = if (it) selected + i else selected - i },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = item.name,
                                onValueChange = { v -> items = items.toMutableList().also { l -> l[i] = item.copy(name = v) } },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = if (item.kcal > 0) item.kcal.toString() else "",
                                    onValueChange = { v ->
                                        items = items.toMutableList().also { l -> l[i] = item.copy(kcal = v.filter { it.isDigit() }.toIntOrNull() ?: 0) }
                                    },
                                    label = { Text("kcal") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = item.amount,
                                    onValueChange = { v -> items = items.toMutableList().also { l -> l[i] = item.copy(amount = v) } },
                                    label = { Text("Menge") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (item.confidence == "niedrig") {
                                Text("Unsichere Schätzung – bitte prüfen", style = MaterialTheme.typography.labelSmall, color = NutritionColors.Eaten)
                            }
                        }
                    }
                }
                if (items.isNotEmpty()) {
                    Text("Mahlzeit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MealType.entries.forEach { m ->
                            FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.label) })
                        }
                    }
                    Text(
                        "Schätzungen können deutlich abweichen – besonders bei Soßen, Öl und Portionsgrößen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (items.isNotEmpty()) {
                TextButton(
                    enabled = selected.isNotEmpty() && items.filterIndexed { i, _ -> i in selected }.all { it.name.isNotBlank() },
                    onClick = { onSave(items.filterIndexed { i, _ -> i in selected }, meal) },
                ) { Text("Eintragen") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (items.isEmpty()) "Schließen" else "Abbrechen") } },
    )
}

/** Einmalig den API-Schlüssel für die Foto-Erkennung eintragen. */
@Composable
fun ApiKeyDialog(provider: FoodVisionProvider, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Foto-Erkennung einrichten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (provider) {
                        FoodVisionProvider.GEMINI ->
                            "Die Erkennung läuft kostenlos über die Gemini-API von Google. Du brauchst einmalig einen eigenen " +
                                "API-Schlüssel aus Google AI Studio – ein Google-Konto genügt, Zahlungsdaten sind nicht nötig."
                        FoodVisionProvider.CLAUDE ->
                            "Die Erkennung läuft über die Claude-API von Anthropic. Dafür brauchst du einmalig einen eigenen API-Schlüssel " +
                                "(mit etwas Guthaben; ein Foto kostet ungefähr einen Cent)."
                    },
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text(provider.keyPlaceholder) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, provider.keyUrl.toUri())) } }) {
                    Text(if (provider == FoodVisionProvider.GEMINI) "Kostenlosen Schlüssel bei Google erstellen" else "Schlüssel bei Anthropic erstellen")
                }
                Text(
                    "Der Schlüssel bleibt auf deinem Handy und wird nicht gesichert. Fotos werden nur für die Erkennung übertragen und nicht gespeichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = key.isNotBlank(), onClick = { onSave(key) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}


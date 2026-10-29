package de.lltntplanet.adhsapp.reminders

import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import de.lltntplanet.adhsapp.AdhsApp
import de.lltntplanet.adhsapp.MainActivity
import de.lltntplanet.adhsapp.data.google.GoogleJson
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.ui.theme.AdhsTheme
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.LocalDate

/**
 * Die Vollbild-Erinnerung. Erscheint auch auf dem Sperrbildschirm und schaltet das Display ein.
 * Mehrere gleichzeitige Erinnerungen werden nacheinander gezeigt.
 */
class ReminderActivity : ComponentActivity() {

    private val reminders = mutableStateListOf<Reminder>()
    private lateinit var player: AlertPlayer
    private val container get() = (application as AdhsApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        player = AlertPlayer(this)
        addReminders(intent)
        if (reminders.isEmpty()) {
            finish()
            return
        }
        startAlert()
        setContent {
            AdhsTheme(darkTheme = true) {
                val current = reminders.firstOrNull()
                if (current != null) {
                    AlertScreen(
                        reminder = current,
                        remaining = reminders.size - 1,
                        onDismiss = { handled(current) },
                        onSnooze = { minutes -> snooze(current, minutes) },
                        onDone = { done(current) },
                        onMute = { mute(current) },
                        onOpen = { open(current) },
                    )
                }
            }
        }
        closeWhenDoneElsewhere()
    }

    /** Hakt der Medikamentenspender (oder die App an anderer Stelle) eine Einnahme ab, verschwindet ihre Erinnerung. */
    private fun closeWhenDoneElsewhere() {
        val today = LocalDate.now()
        lifecycleScope.launch {
            container.routines.observeLog(today.minusDays(1), today.plusDays(1)).collect { log ->
                val done = RoutineLogic.doneKeys(log)
                reminders.filter { it.kind.isRoutine && (it.originKey ?: it.key) in done }.forEach(::handled)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val before = reminders.size
        addReminders(intent)
        if (reminders.size > before) startAlert()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) player.stop()
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun addReminders(intent: Intent?) {
        val json = intent?.getStringExtra(EXTRA_REMINDERS) ?: return
        val parsed = runCatching { GoogleJson.decodeFromString<List<Reminder>>(json) }.getOrDefault(emptyList())
        parsed.filter { new -> reminders.none { it.key == new.key } }.forEach { reminders.add(it) }
    }

    private fun startAlert() {
        lifecycleScope.launch {
            val s = container.settings.current()
            player.start(sound = s.alarmSound, vibrate = s.vibrate, seconds = s.ringSeconds)
        }
    }

    private fun handled(r: Reminder) {
        player.stop()
        container.notifier.cancel(r)
        reminders.remove(r)
        if (reminders.isEmpty()) finish()
    }

    private fun snooze(r: Reminder, minutes: Int) {
        container.scope.launch { container.reminderEngine.snooze(r, minutes) }
        handled(r)
    }

    /** Keine weiteren Erinnerungen zu diesem Termin an diesem Tag. */
    private fun mute(r: Reminder) {
        container.scope.launch { container.reminderEngine.mute(r) }
        handled(r)
    }

    private fun done(r: Reminder) {
        val id = r.refId
        if (r.kind == ReminderKind.TASK && id != null) {
            container.scope.launch { container.tasks.setDone(id, true) }
        } else if (r.kind.isRoutine && id != null) {
            val at = r.targetTime ?: r.triggerAt
            container.scope.launch { container.routines.setDoneAt(id, at) }
        }
        handled(r)
    }

    private fun open(r: Reminder) {
        val target = if (r.kind == ReminderKind.EVENT && r.refId != null) {
            Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, r.refId)).apply {
                r.targetTime?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                r.targetEnd?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            }
        } else if (r.kind.isCheckup && r.refId != null) {
            checkupLaunchIntent(this, r)
        } else {
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, r.kind.name)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        player.stop()
        val launch = {
            runCatching { startActivity(target) }.onFailure {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            handled(r)
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        launch()
                    }
                },
            )
        } else {
            launch()
        }
    }

    companion object {
        private const val EXTRA_REMINDERS = "reminders"

        fun intent(context: Context, reminders: List<Reminder>): Intent =
            Intent(context, ReminderActivity::class.java)
                .putExtra(EXTRA_REMINDERS, GoogleJson.encodeToString(reminders))
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

private fun kindColor(kind: ReminderKind): Color = when (kind) {
    ReminderKind.EVENT -> AppColors.GoogleBlue
    ReminderKind.BIRTHDAY -> AppColors.Birthday
    ReminderKind.TASK -> AppColors.Task
    ReminderKind.MEDICATION -> AppColors.Medication
    ReminderKind.ROUTINE -> AppColors.Routine
    ReminderKind.MEAL -> AppColors.Routine
    ReminderKind.CHECKUP -> AppColors.Mood
}

@Composable
private fun AlertScreen(
    reminder: Reminder,
    remaining: Int,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit,
    onDone: () -> Unit,
    onMute: () -> Unit,
    onOpen: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val background = kindColor(reminder.kind)
    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding(),
    ) {
        AnimatedContent(targetState = reminder, label = "reminder") { r ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Kopfzeile: Art + aktuelle Uhrzeit
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (r.kind) {
                            ReminderKind.EVENT -> Icons.Filled.Event
                            ReminderKind.BIRTHDAY -> Icons.Filled.Cake
                            ReminderKind.TASK -> Icons.Filled.TaskAlt
                            ReminderKind.MEDICATION -> Icons.Filled.Medication
                            ReminderKind.ROUTINE -> Icons.Filled.Repeat
                            ReminderKind.MEAL -> Icons.Filled.Restaurant
                            ReminderKind.CHECKUP -> Icons.Filled.Mood
                        },
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        r.kind.label.uppercase(),
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    if (r.color != null && r.kind == ReminderKind.EVENT) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(12.dp).background(Color(r.color), CircleShape))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(TimeFormat.time(now), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }

                // Inhalt
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ReminderText.bigCountdown(r, now)?.let {
                        Text(
                            it,
                            color = Color.White,
                            fontSize = 56.sp,
                            lineHeight = 60.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        ReminderText.headline(r),
                        color = Color.White,
                        fontSize = 40.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ReminderText.subtitle(r, now),
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                    )
                    r.detail?.let { detail ->
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            if (r.kind == ReminderKind.EVENT) {
                                Icon(Icons.Filled.LocationOn, null, tint = Color.White.copy(alpha = 0.85f))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(detail, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
                        }
                    }
                    if (remaining > 0) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "+ $remaining weitere Erinnerung${if (remaining > 1) "en" else ""}",
                            color = Color.White,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                // Aktionen – groß und eindeutig
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val primaryColors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = background)
                    if (r.kind.isCheckup && r.refId != null) {
                        Button(
                            onClick = onOpen,
                            colors = primaryColors,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Icon(Icons.Filled.Mood, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Jetzt ausfüllen", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if ((r.kind == ReminderKind.TASK || r.kind.isRoutine) && r.refId != null) {
                        Button(
                            onClick = onDone,
                            colors = primaryColors,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Icon(Icons.Filled.CheckCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (r.kind == ReminderKind.MEDICATION) "Genommen" else "Erledigt",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            colors = primaryColors,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Text("Verstanden", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Später erinnern",
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(5 to "5 Min", 10 to "10 Min", 30 to "30 Min", 60 to "1 Std").forEach { (minutes, label) ->
                            OutlinedButton(
                                onClick = { onSnooze(minutes) },
                                modifier = Modifier.weight(1f).height(52.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) { Text(label, maxLines = 1, softWrap = false) }
                        }
                    }
                    if (r.canMute) {
                        OutlinedButton(
                            onClick = onMute,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                        ) {
                            Icon(Icons.Filled.NotificationsOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Keine weiteren Erinnerungen zu diesem Termin", maxLines = 1)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onOpen) { Text("Öffnen", color = Color.White) }
                        if ((r.kind == ReminderKind.TASK || r.kind.isRoutine || r.kind.isCheckup) && r.refId != null) {
                            TextButton(onClick = onDismiss) { Text("Nur schließen", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

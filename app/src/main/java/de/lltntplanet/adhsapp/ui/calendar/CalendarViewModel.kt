package de.lltntplanet.adhsapp.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.calendar.CalendarInfo
import de.lltntplanet.adhsapp.data.calendar.EventDraft
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.settings.CalendarViewMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.calendar

    val settings: StateFlow<AppSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val refreshTick = MutableStateFlow(0)
    private val changes = combine(repo.changes(), refreshTick) { _, _ -> }

    /** Zählt Änderungen im Kalender-Speicher; Ansichten laden bei jeder Änderung neu. */
    val version: StateFlow<Int> = changes
        .runningFold(0) { acc, _ -> acc + 1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val calendars: StateFlow<List<CalendarInfo>> = changes
        .mapLatest { repo.calendars() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _anchor = MutableStateFlow(LocalDate.now())
    /** Der Tag, um den sich die aktuelle Ansicht dreht (für Titel und "Neuer Termin"). */
    val anchor: StateFlow<LocalDate> = _anchor.asStateFlow()

    fun setAnchor(date: LocalDate) {
        _anchor.value = date
    }

    fun hasPermission() = repo.hasReadPermission()

    /** Neu laden, z. B. nachdem die Kalenderberechtigung erteilt wurde. */
    fun refresh() {
        refreshTick.value++
    }

    suspend fun load(from: LocalDate, toExclusive: LocalDate): List<EventInstance> {
        val hidden = settings.value.hiddenCalendars
        return repo.instances(from, toExclusive).filter { it.calendarId !in hidden && !it.canceled }
    }

    fun setView(mode: CalendarViewMode) {
        viewModelScope.launch { container.settings.update { it.copy(calendarView = mode) } }
    }

    fun toggleCalendar(id: Long) {
        viewModelScope.launch {
            container.settings.update {
                it.copy(hiddenCalendars = if (id in it.hiddenCalendars) it.hiddenCalendars - id else it.hiddenCalendars + id)
            }
        }
    }

    suspend fun reminders(eventId: Long): List<Int> = repo.eventReminders(listOf(eventId))[eventId].orEmpty().sorted()

    suspend fun loadDraft(eventId: Long): EventDraft? = repo.loadDraft(eventId)

    /** Beschreibbare Kalender direkt laden (ohne auf den Flow zu warten). */
    suspend fun loadWritableCalendars(): List<CalendarInfo> =
        repo.calendars().filter { it.canWrite && !it.isBirthdayCalendar }

    suspend fun save(draft: EventDraft): Result<Long> = runCatching { repo.save(draft) }.also {
        if (it.isSuccess) {
            container.settings.update { s -> s.copy(defaultCalendarId = draft.calendarId) }
            container.requestReschedule()
        }
    }

    fun delete(eventId: Long) {
        viewModelScope.launch {
            runCatching { repo.delete(eventId) }
            container.requestReschedule()
        }
    }

    fun writableCalendars(): List<CalendarInfo> = calendars.value.filter { it.canWrite && !it.isBirthdayCalendar }

    fun defaultCalendar(): CalendarInfo? {
        val writable = writableCalendars()
        return writable.find { it.id == settings.value.defaultCalendarId }
            ?: writable.find { it.isPrimary && it.isGoogle }
            ?: writable.find { it.isGoogle }
            ?: writable.firstOrNull()
    }
}

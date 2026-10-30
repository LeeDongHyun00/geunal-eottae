package app.geunal.feature.calendar.screen

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.Change
import app.geunal.core.CoreInterface
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.agenda.AgendaModel
import app.geunal.feature.calendar.month.MonthModel
import app.geunal.feature.calendar.quickentry.EventActions
import app.geunal.feature.calendar.quickentry.EventTexts
import app.geunal.feature.calendar.quickentry.QuickEntryModel
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.week.WeekModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** The three views of the calendar tab (m1.md §2.2). */
enum class CalendarViewKind { MONTH, WEEK, LIST }

/** Where the calendar keeps the view last shown, so the next launch opens on it (m1.md §2.2). */
interface ViewKindStore {
    fun load(): CalendarViewKind

    fun save(kind: CalendarViewKind)
}

/**
 * A view preference is neither a secret nor the person's data, so plain preferences hold it. The app's backup rules
 * name this file (`calendar.xml`); a renamed file would quietly drop out of backups.
 */
class PreferencesViewKindStore(
    context: Context,
) : ViewKindStore {
    private val preferences = context.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    override fun load(): CalendarViewKind =
        preferences.getString(KEY, null)?.let { saved -> CalendarViewKind.entries.firstOrNull { it.name == saved } }
            ?: CalendarViewKind.MONTH

    override fun save(kind: CalendarViewKind) {
        preferences.edit { putString(KEY, kind.name) }
    }

    private companion object {
        const val KEY = "view"
    }
}

/**
 * The calendar tab's state (m1.md screens 4–8): the three views' models, the view on screen, and the actions after an
 * add, which run in this scope so an undo outlives the sheet and the tab.
 */
class CalendarViewModel(
    private val core: CoreInterface,
    toasts: ToastCenter,
    texts: EventTexts,
    changes: Flow<Change>,
    private val store: ViewKindStore,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val month = MonthModel(core, now, io = io)
    val week = WeekModel(core, now, io = io)
    val agenda = AgendaModel(core, now, io)
    val actions = EventActions(core, toasts, texts, viewModelScope, io)

    private val _kind = MutableStateFlow(store.load())
    val kind: StateFlow<CalendarViewKind> = _kind.asStateFlow()

    /** The Korean day the views were read for; an app left open overnight reads them again for the new today. */
    private var shownDay = KoreanCalendar.day(now())

    init {
        viewModelScope.launch { agenda.reload() }
        viewModelScope.launch { changes.collect { if (it == Change.Calendar) reload() } }
    }

    /** The segment: the new view opens where the old one was. */
    fun select(kind: CalendarViewKind) {
        val previous = _kind.value
        if (previous == kind) return
        keepPlace(previous, kind)
        _kind.value = kind
        store.save(kind)
    }

    /** The header's title: back to today. */
    fun showToday() {
        when (_kind.value) {
            CalendarViewKind.MONTH -> month.showToday()
            CalendarViewKind.WEEK -> week.showToday()
            CalendarViewKind.LIST -> Unit
        }
    }

    /** On coming back to the app and at midnight. */
    fun refreshIfNewDay() {
        val today = KoreanCalendar.day(now())
        if (today == shownDay) return
        shownDay = today
        viewModelScope.launch { reload() }
    }

    /** The quick entry for [day], with what the line under its title says. */
    fun quickEntry(
        day: String,
        notes: List<String>,
        range: IntRange? = null,
    ): QuickEntryModel = QuickEntryModel(day, notes, range, core, now, io)

    val today: String get() = KoreanCalendar.day(now())

    suspend fun reload() {
        month.reload()
        week.reload()
        agenda.reload()
    }

    /** The week of the month on screen (today's when it is this month), or the month of the week on screen. */
    private fun keepPlace(
        from: CalendarViewKind,
        to: CalendarViewKind,
    ) {
        when {
            from == CalendarViewKind.MONTH && to == CalendarViewKind.WEEK -> {
                val state = month.state.value
                week.show(if (state.visibleMonth == state.currentMonth) state.today else "${state.visibleMonth}-01")
            }
            from == CalendarViewKind.WEEK && to == CalendarViewKind.MONTH -> {
                val middle = KoreanCalendar.addDays(week.state.value.visibleWeek, 3) ?: return
                month.show(KoreanCalendar.month(middle))
            }
            else -> Unit
        }
    }
}

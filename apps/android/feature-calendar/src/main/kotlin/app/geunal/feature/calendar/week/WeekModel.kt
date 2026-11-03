package app.geunal.feature.calendar.week

import app.geunal.core.CoreInterface
import app.geunal.core.WeekDay
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** The week view (m1.md screen 7): one page per week from Sunday, swiped sideways. */
class WeekModel(
    private val core: CoreInterface,
    private val now: () -> Instant = Instant::now,
    years: Int = 10,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val today: String,
        /** The Sunday of the week on screen. */
        val visibleWeek: String,
        val pages: Map<String, List<WeekDay>> = emptyMap(),
    ) {
        val currentWeek: String get() = KoreanCalendar.weekStart(today) ?: today
    }

    /** The pager's weeks by their Sunday, [years] either side of this week. */
    val weeks: List<String>

    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>

    init {
        val today = KoreanCalendar.day(now())
        val current = KoreanCalendar.weekStart(today) ?: today
        val count = 53L * years
        weeks = (-count..count).mapNotNull { KoreanCalendar.addDays(current, 7 * it) }
        _state = MutableStateFlow(State(today, current))
        state = _state.asStateFlow()
    }

    fun index(of: String): Int = weeks.indexOf(of).coerceAtLeast(0)

    suspend fun load(week: String) {
        if (_state.value.pages.containsKey(week)) return
        read(week)
    }

    suspend fun reload() {
        val today = KoreanCalendar.day(now())
        val visible = _state.value.visibleWeek
        val keep = listOf(-7L, 0L, 7L).mapNotNull { KoreanCalendar.addDays(visible, it) }.toSet()
        _state.update { current -> current.copy(today = today, pages = current.pages.filterKeys { it in keep }) }
        for (week in keep.sorted()) read(week)
    }

    fun showToday() {
        val today = KoreanCalendar.day(now())
        _state.update { it.copy(today = today, visibleWeek = KoreanCalendar.weekStart(today) ?: today) }
    }

    /** The week [day] falls in, for switching views without losing the place. */
    fun show(day: String) {
        val week = KoreanCalendar.weekStart(day) ?: return
        _state.update { it.copy(visibleWeek = week) }
    }

    /** Minutes since midnight now, for the red line, while today is on the page of [week]. */
    fun nowMinute(week: String): Int? {
        val instant = now()
        val day = KoreanCalendar.day(instant)
        val offset = KoreanCalendar.days(from = week, to = day) ?: return null
        if (offset !in 0..6) return null
        val midnight = KoreanCalendar.startOf(day) ?: return null
        return Duration.between(midnight, instant).toMinutes().toInt()
    }

    private suspend fun read(week: String) {
        val days =
            withContext(io) { coreRead { core.calendarWeek(week) } } ?: return
        _state.update { it.copy(pages = it.pages + (week to days)) }
    }
}

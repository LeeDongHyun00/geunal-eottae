package app.geunal.feature.calendar.month

import app.geunal.core.CoreInterface
import app.geunal.core.DayCell
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The month view (m1.md screen 4): one page per month, swiped sideways. A page reads the core when it comes into view
 * and stays until the calendar changes.
 */
class MonthModel(
    private val core: CoreInterface,
    private val now: () -> Instant = Instant::now,
    years: Int = 10,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val today: String,
        /** The month on screen, "YYYY-MM". */
        val visibleMonth: String,
        val pages: Map<String, List<DayCell>> = emptyMap(),
    ) {
        val currentMonth: String get() = KoreanCalendar.month(today)

        /** The weeks of a loaded page, seven cells each. */
        fun weeks(month: String): List<List<DayCell>> = pages[month].orEmpty().chunked(7)

        /** The first-run hint under today's cell (m1.md screen 4): this month is loaded and has nothing on it yet. */
        val showsFirstRunHint: Boolean
            get() = visibleMonth == currentMonth && pages[currentMonth]?.all { it.events.isEmpty() } == true
    }

    /** The pager's months, "YYYY-MM", [years] either side of this month. */
    val months: List<String>

    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>

    init {
        val today = KoreanCalendar.day(now())
        val current = KoreanCalendar.month(today)
        months = (-12L * years..12L * years).mapNotNull { KoreanCalendar.addMonths(current, it) }
        _state = MutableStateFlow(State(today, current))
        state = _state.asStateFlow()
    }

    fun index(of: String): Int = months.indexOf(of).coerceAtLeast(0)

    suspend fun load(month: String) {
        if (_state.value.pages.containsKey(month)) return
        read(month)
    }

    /** After a change: the pages around the visible month again, the rest when they come back into view. */
    suspend fun reload() {
        val today = KoreanCalendar.day(now())
        val visible = _state.value.visibleMonth
        val keep = listOf(-1L, 0L, 1L).mapNotNull { KoreanCalendar.addMonths(visible, it) }.toSet()
        _state.update { current -> current.copy(today = today, pages = current.pages.filterKeys { it in keep }) }
        for (month in keep.sorted()) read(month)
    }

    /** The header's month title: back to this month. */
    fun showToday() {
        val today = KoreanCalendar.day(now())
        _state.update { it.copy(today = today, visibleMonth = KoreanCalendar.month(today)) }
    }

    fun show(month: String) {
        _state.update { it.copy(visibleMonth = month) }
    }

    private suspend fun read(month: String) {
        val cells =
            withContext(io) { coreRead { core.calendarMonth(month) } } ?: return
        _state.update { it.copy(pages = it.pages + (month to cells)) }
    }
}

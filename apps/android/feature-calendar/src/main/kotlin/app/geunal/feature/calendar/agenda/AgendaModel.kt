package app.geunal.feature.calendar.agenda

import app.geunal.core.AgendaDay
import app.geunal.core.CoreInterface
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
 * The list view (m1.md screen 8): the days with something on them, today first, four weeks a page. Scrolling up
 * brings the four weeks before, greyed out.
 */
class AgendaModel(
    private val core: CoreInterface,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val today: String,
        val days: List<AgendaDay> = emptyList(),
        /** Nothing from today on in the first page. */
        val isEmpty: Boolean = false,
        val hasLoaded: Boolean = false,
        val canLoadEarlier: Boolean = true,
        val canLoadMore: Boolean = true,
    ) {
        fun isPast(day: AgendaDay): Boolean = (KoreanCalendar.days(from = today, to = day.day) ?: 0) < 0

        val past: List<AgendaDay> get() = days.filter { isPast(it) }
        val upcoming: List<AgendaDay> get() = days.filterNot { isPast(it) }
    }

    private val _state = MutableStateFlow(State(KoreanCalendar.day(now())))
    val state: StateFlow<State> = _state.asStateFlow()
    private var firstDay = _state.value.today
    private var nextDay = _state.value.today
    private var loading = false

    /** The first page from today, and the page before it so scrolling up has somewhere to go. */
    suspend fun reload() {
        val today = KoreanCalendar.day(now())
        val start = KoreanCalendar.addDays(today, -PAGE_DAYS) ?: today
        val end = KoreanCalendar.addDays(today, PAGE_DAYS) ?: today
        val found = read(start, 2 * PAGE_DAYS) ?: return
        firstDay = start
        nextDay = end
        val probe = State(today, found)
        // A holiday alone is not an event: the empty state still says there is nothing planned.
        val empty = probe.upcoming.none { it.events.isNotEmpty() || it.birthdays.isNotEmpty() }
        _state.value = State(today, found, isEmpty = empty, hasLoaded = true)
    }

    /** The next four weeks, when the list reaches its end. */
    suspend fun loadMore() {
        val current = _state.value
        if (!current.hasLoaded || !current.canLoadMore || loading) return
        val start = nextDay
        val ahead = KoreanCalendar.days(from = current.today, to = start) ?: return
        if (ahead >= LATEST_DAYS) {
            _state.update { it.copy(canLoadMore = false) }
            return
        }
        loading = true
        try {
            val found = read(start, PAGE_DAYS) ?: return
            nextDay = KoreanCalendar.addDays(start, PAGE_DAYS) ?: start
            _state.update { it.copy(days = it.days + found) }
        } finally {
            loading = false
        }
    }

    /** The four weeks before, when the list reaches its top. */
    suspend fun loadEarlier() {
        val current = _state.value
        if (!current.hasLoaded || !current.canLoadEarlier || loading) return
        val start = KoreanCalendar.addDays(firstDay, -PAGE_DAYS) ?: return
        val back = KoreanCalendar.days(from = start, to = current.today) ?: return
        if (back > EARLIEST_DAYS) {
            _state.update { it.copy(canLoadEarlier = false) }
            return
        }
        loading = true
        try {
            val found = read(start, PAGE_DAYS) ?: return
            firstDay = start
            _state.update { it.copy(days = found + it.days) }
        } finally {
            loading = false
        }
    }

    private suspend fun read(
        from: String,
        days: Long,
    ): List<AgendaDay>? = withContext(io) { coreRead { core.calendarAgenda(from, days.toInt().toUShort()) } }

    companion object {
        /** Days per page; an empty first page is the empty state ("앞으로 4주 동안 일정이 없어요"). */
        const val PAGE_DAYS = 28L

        /** How far back scrolling up and forward scrolling down may go. */
        const val EARLIEST_DAYS = 366L
        const val LATEST_DAYS = 3 * 366L
    }
}

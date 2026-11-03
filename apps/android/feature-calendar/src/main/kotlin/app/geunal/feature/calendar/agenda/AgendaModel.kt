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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * One read at a time: a page that finished after a reload would be spliced onto the new list, leaving a gap or
     * a day twice (and the list's keys must be unique).
     */
    private val mutex = Mutex()

    /** The first page from today, and the page before it so scrolling up has somewhere to go. */
    suspend fun reload() {
        mutex.withLock {
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
    }

    /**
     * The next four weeks with something on them, when the list reaches its end. Weeks with nothing on them add no
     * rows, so the list's end would stay put and never ask again: they are passed over here.
     */
    suspend fun loadMore() {
        mutex.withLock {
            while (true) {
                val current = _state.value
                if (!current.hasLoaded || !current.canLoadMore) return
                val start = nextDay
                val ahead = KoreanCalendar.days(from = current.today, to = start) ?: return
                if (ahead >= LATEST_DAYS) {
                    _state.update { it.copy(canLoadMore = false) }
                    return
                }
                val found = read(start, PAGE_DAYS) ?: return
                nextDay = KoreanCalendar.addDays(start, PAGE_DAYS) ?: return
                if (found.isNotEmpty()) {
                    _state.update { it.copy(days = it.days + found) }
                    return
                }
            }
        }
    }

    /** The four weeks before with something on them, when the list reaches its top; empty weeks as in [loadMore]. */
    suspend fun loadEarlier() {
        mutex.withLock {
            while (true) {
                val current = _state.value
                if (!current.hasLoaded || !current.canLoadEarlier) return
                val start = KoreanCalendar.addDays(firstDay, -PAGE_DAYS) ?: return
                val back = KoreanCalendar.days(from = start, to = current.today) ?: return
                if (back > EARLIEST_DAYS) {
                    _state.update { it.copy(canLoadEarlier = false) }
                    return
                }
                val found = read(start, PAGE_DAYS) ?: return
                firstDay = start
                if (found.isNotEmpty()) {
                    _state.update { it.copy(days = found + it.days) }
                    return
                }
            }
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

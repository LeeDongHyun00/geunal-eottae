package app.geunal.feature.calendar.nextup

import app.geunal.core.CoreInterface
import app.geunal.core.Palette
import app.geunal.core.UpNext
import app.geunal.feature.calendar.support.DayLabel
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.Until
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The next-up bar above the tab bar (docs/design/README.md §5): the nearest things from now, stepped through with ‹ ›.
 * M1 has own events and birthdays; group appointments join at M2.
 */
class NextUpModel(
    private val core: CoreInterface,
    private val count: Int = 12,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** What one item says; the bar turns it into text. */
    sealed interface Title {
        data class Event(
            val text: String,
        ) : Title

        data class Birthday(
            val name: String,
        ) : Title
    }

    sealed interface Detail {
        data class AllDay(
            val day: DayLabel,
        ) : Detail

        /** `until` is `null` once the event has begun ("지금"). */
        data class Timed(
            val day: DayLabel,
            val start: Int,
            val end: Int,
            val until: Until?,
        ) : Detail

        data class Birthday(
            val day: DayLabel,
        ) : Detail
    }

    data class Item(
        val title: Title,
        val detail: Detail,
        val color: Palette?,
        val source: UpNext,
    ) {
        val isBirthday: Boolean get() = source is UpNext.Birthday
    }

    data class State(
        val items: List<Item> = emptyList(),
        val index: Int = 0,
    ) {
        val current: Item? get() = items.getOrNull(index)
        val canGoBack: Boolean get() = index > 0
        val canGoForward: Boolean get() = index + 1 < items.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Reads the core again, keeping the bar on the same item when it is still there. */
    suspend fun refresh() {
        val upcoming = withContext(io) { runCatching { core.nextUp(count.toUShort()) }.getOrDefault(emptyList()) }
        val at = now()
        val items = upcoming.map { item(it, at) }
        val previous = _state.value.current?.source
        val index = previous?.let { source -> items.indexOfFirst { it.source == source }.takeIf { it >= 0 } } ?: 0
        _state.value = State(items, index)
    }

    /**
     * Reads the core again at every minute while the bar is on screen, until the calling coroutine is cancelled.
     * Nothing else refreshes it while the app stays in front without edits, and "5분 뒤" would still be on the bar after
     * the event began, ended, or the day turned.
     */
    suspend fun keepCurrent() {
        while (true) {
            val intoMinute = now().toEpochMilli() % 60_000
            sleep(60_000 - intoMinute)
            refresh()
        }
    }

    fun forward() {
        _state.value.let { if (it.canGoForward) _state.value = it.copy(index = it.index + 1) }
    }

    fun back() {
        _state.value.let { if (it.canGoBack) _state.value = it.copy(index = it.index - 1) }
    }

    companion object {
        fun item(
            upNext: UpNext,
            now: Instant,
        ): Item {
            val today = KoreanCalendar.day(now)
            return when (upNext) {
                is UpNext.Event -> {
                    val event = upNext.item
                    val day = DayLabel.of(event.start.day, today)
                    val detail =
                        if (event.allDay) {
                            Detail.AllDay(day)
                        } else {
                            Detail.Timed(day, event.start.minute.toInt(), endMinute(upNext), Until.of(upNext.startsAt, now))
                        }
                    Item(Title.Event(event.title), detail, event.color, upNext)
                }
                is UpNext.Birthday ->
                    Item(
                        Title.Birthday(upNext.item.name),
                        Detail.Birthday(DayLabel.of(upNext.item.day, today)),
                        color = null,
                        source = upNext,
                    )
            }
        }

        /** An event ending at midnight ends at minute 0 of the next day (rust-core.md §3); it shows as 24:00. */
        private fun endMinute(upNext: UpNext.Event): Int {
            val event = upNext.item
            return if (event.end.day != event.start.day && event.end.minute.toInt() == 0) 1440 else event.end.minute.toInt()
        }
    }
}

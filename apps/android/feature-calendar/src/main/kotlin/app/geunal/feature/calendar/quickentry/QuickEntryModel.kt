package app.geunal.feature.calendar.quickentry

import app.geunal.core.AlertRule
import app.geunal.core.CalendarKind
import app.geunal.core.CalendarView
import app.geunal.core.CoreInterface
import app.geunal.core.DayTime
import app.geunal.core.EventInput
import app.geunal.core.Frequency
import app.geunal.core.Palette
import app.geunal.core.Recurrence
import app.geunal.core.Suggestion
import app.geunal.feature.calendar.support.DayTitle
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.RepeatText
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * The quick entry sheet (m1.md screen 5): five suggestion chips that save on a tap, or a title with a time range
 * dragged on the 0–24 strip. Saving goes through [EventActions], which shows the toast.
 */
class QuickEntryModel(
    val day: String,
    /** Birthdays and holidays of the day, for the line under the title ("민수 생일 · 개천절"). */
    val notes: List<String> = emptyList(),
    range: IntRange? = null,
    private val core: CoreInterface,
    now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

    data class State(
        val suggestions: List<Suggestion> = emptyList(),
        /** The calendars an event can go in: own ones, visible or not. Device calendars are read-only. */
        val calendars: List<CalendarView> = emptyList(),
        val title: String = "",
        /** Half hours from midnight, the end exclusive; `null` saves an all-day event. */
        val range: IntRange? = null,
        val calendarId: String? = null,
        val repeat: Repeat = Repeat.NONE,
        val hasLoaded: Boolean = false,
    ) {
        val selectedCalendar: CalendarView? get() = calendars.firstOrNull { it.id == calendarId } ?: calendars.firstOrNull()
        val trimmedTitle: String get() = title.trim()
        val canSave: Boolean get() = trimmedTitle.isNotEmpty() && selectedCalendar != null

        /** The strip has a range but the title is empty: the button says what is missing (tone.md "오류와 한계"). */
        val needsTitle: Boolean get() = trimmedTitle.isEmpty() && range != null
    }

    val today: String = KoreanCalendar.day(now())
    val heading: DayTitle? = DayTitle.of(day, today)

    private val _state = MutableStateFlow(State(range = range))
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun load() {
        val (calendars, suggestions) =
            coroutineScope {
                val calendars = async(io) { coreRead { core.calendars() }.orEmpty() }
                val suggestions = async(io) { coreRead { core.suggestions(day) }.orEmpty() }
                calendars.await() to suggestions.await()
            }
        val own = calendars.filter { it.kind == CalendarKind.OWN }
        _state.update {
            it.copy(
                calendars = own,
                suggestions = suggestions,
                calendarId = it.calendarId ?: own.firstOrNull()?.id,
                hasLoaded = true,
            )
        }
    }

    fun setTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    fun setRange(range: IntRange?) {
        _state.update { it.copy(range = range) }
    }

    fun setCalendar(id: String) {
        _state.update { it.copy(calendarId = id) }
    }

    fun setRepeat(repeat: Repeat) {
        _state.update { it.copy(repeat = repeat) }
    }

    /** The calendar color a chip's dot shows. */
    fun color(of: Suggestion): Palette? {
        val current = _state.value
        return current.calendars.firstOrNull { it.id == of.event.calendarId }?.color ?: current.selectedCalendar?.color
    }

    /** What "저장" saves, or `null` while the title is empty. */
    fun typedEvent(): EventInput? = if (_state.value.canSave) draft() else null

    /**
     * What "자세히" hands to the editor: the typed title (or none yet), the range or all day, the calendar and the
     * repeat rule. An empty calendar id leaves the choice to the editor, which takes the first own calendar.
     */
    fun draft(): EventInput {
        val current = _state.value
        val range = current.range
        val (start, end) =
            if (range != null) {
                time(range.first, day) to time(range.last + 1, day)
            } else {
                DayTime(day, 0u) to DayTime(day, 0u)
            }
        return EventInput(
            calendarId = current.selectedCalendar?.id.orEmpty(),
            title = current.trimmedTitle,
            allDay = range == null,
            start = start,
            end = end,
            recurrence = recurrence(current.repeat),
            alert = AlertRule.Inherit,
            note = null,
        )
    }

    /** The repeat chip's label for a choice: `null` is "반복 안 함". */
    fun label(of: Repeat): RepeatText? = recurrence(of)?.let { RepeatText.of(it, day) }

    companion object {
        /** Half hours in a day: the strip's steps (m1.md screen 5 "30분 단위"). */
        const val SLOTS = 48

        /** A half-hour step as a day and minute; step 48 is midnight at the start of the next day (rust-core.md §3). */
        fun time(
            slot: Int,
            day: String,
        ): DayTime {
            val minutes = slot * 30
            if (minutes >= 1440) return DayTime(KoreanCalendar.addDays(day, 1) ?: day, (minutes - 1440).toUShort())
            return DayTime(day, minutes.toUShort())
        }

        /** The repeat chip's choices; a weekly or monthly rule without days falls on the first occurrence's. */
        fun recurrence(choice: Repeat): Recurrence? {
            val frequency =
                when (choice) {
                    Repeat.NONE -> return null
                    Repeat.DAILY -> Frequency.DAILY
                    Repeat.WEEKLY -> Frequency.WEEKLY
                    Repeat.MONTHLY -> Frequency.MONTHLY
                    Repeat.YEARLY -> Frequency.YEARLY
                }
            return Recurrence(frequency = frequency, interval = 1u, weekdays = emptyList(), until = null, count = null)
        }
    }
}

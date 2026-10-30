package app.geunal.feature.calendar.editor

import app.geunal.core.AlertRule
import app.geunal.core.CalendarKind
import app.geunal.core.CalendarView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.DayTime
import app.geunal.core.EditScope
import app.geunal.core.EventInput
import app.geunal.core.EventSource
import app.geunal.core.Frequency
import app.geunal.core.InternalException
import app.geunal.core.Recurrence
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor opens. */
sealed interface EditorTarget {
    /** A new event on [day], filled from [input] when the quick entry's "자세히" or the leave mark hands one over. */
    data class New(
        val day: String,
        val input: EventInput? = null,
    ) : EditorTarget

    /** [occurrence] names one occurrence of a repeating event, which may then change alone or with what follows. */
    data class Existing(
        val eventId: String,
        val occurrence: String?,
    ) : EditorTarget
}

/**
 * The event editor (m1.md screen 9): a new event, or one opened from the views. Times are Korean days and minutes as
 * the core keeps them; moving the start moves the end with it so the length stays.
 *
 * The work runs in [scope], which outlives the screen: a rotation must not lose a load half done, and a save tapped
 * just before leaving still lands.
 */
class EditorModel(
    val target: EditorTarget,
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Problem { NOT_FOUND, SAVE_FAILED, DELETE_FAILED }

    data class State(
        val title: String = "",
        val calendarId: String? = null,
        val allDay: Boolean = false,
        val startDay: String,
        val startMinute: Int = 540,
        val endDay: String,
        /** Up to 1440: an end at midnight reads as 24:00 of its day rather than 00:00 of the next. */
        val endMinute: Int = 600,
        val recurrence: Recurrence? = null,
        val alert: AlertRule = AlertRule.Inherit,
        /** The alert that applies after inheritance, from the core, for the "캘린더 기본 · 1시간 전" label. */
        val effectiveAlert: Int? = null,
        val calendars: List<CalendarView> = emptyList(),
        val readOnly: Boolean = false,
        val source: EventSource = EventSource.Own,
        /** The event repeated when it was opened: saving or deleting an occurrence asks "이 일정만 · 이후 모두". */
        val wasRepeating: Boolean = false,
        val hasLoaded: Boolean = false,
        val problem: Problem? = null,
        val isWorking: Boolean = false,
        val note: String? = null,
    ) {
        /** Own calendars to pick from; a device calendar's event shows its own and cannot move. */
        val ownCalendars: List<CalendarView> get() = calendars.filter { it.kind == CalendarKind.OWN }

        val selectedCalendar: CalendarView? get() = calendars.firstOrNull { it.id == calendarId } ?: ownCalendars.firstOrNull()

        val trimmedTitle: String get() = title.trim()

        /** The end comes after the start: a timed event by at least a minute, an all-day one on the same day or later. */
        val hasValidRange: Boolean
            get() {
                val start = absolute(startDay, startMinute) ?: return false
                val end = absolute(endDay, endMinute) ?: return false
                return if (allDay) Math.floorDiv(end, DAY) >= Math.floorDiv(start, DAY) else end > start
            }

        val canSave: Boolean
            get() =
                hasLoaded &&
                    !readOnly &&
                    !isWorking &&
                    problem != Problem.NOT_FOUND &&
                    trimmedTitle.isNotEmpty() &&
                    hasValidRange &&
                    selectedCalendar?.kind == CalendarKind.OWN
    }

    private val _state =
        MutableStateFlow(
            when (target) {
                is EditorTarget.New -> State(startDay = target.day, endDay = target.day).filled(target.input)
                is EditorTarget.Existing -> State(startDay = "", endDay = "")
            },
        )
    val state: StateFlow<State> = _state.asStateFlow()
    private var started = false

    /** The rule the event had when it was opened or handed over. */
    private var loadedRecurrence: Recurrence? = (target as? EditorTarget.New)?.input?.recurrence

    val isNew: Boolean get() = target is EditorTarget.New

    /** Saving or deleting asks which occurrences it is for. */
    val asksScope: Boolean
        get() = target is EditorTarget.Existing && target.occurrence != null && _state.value.wasRepeating

    /** Reads the calendars and the event once; a screen that comes back after a rotation finds them read. */
    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val calendars = withContext(io) { coreRead { core.calendars() } }.orEmpty()
        if (target is EditorTarget.Existing) {
            val detail = withContext(io) { coreRead { core.event(target.eventId, target.occurrence) } }
            if (detail == null) {
                _state.update { it.copy(calendars = calendars, problem = Problem.NOT_FOUND, hasLoaded = true) }
                return
            }
            _state.update {
                it
                    .copy(
                        title = detail.title,
                        calendarId = detail.calendarId,
                        recurrence = detail.recurrence,
                        alert = detail.alert,
                        effectiveAlert = detail.effectiveAlert,
                        note = detail.note,
                        source = detail.source,
                        readOnly = detail.readOnly,
                        wasRepeating = detail.recurrence != null,
                    ).timed(detail.allDay, detail.start, detail.end)
            }
            loadedRecurrence = detail.recurrence
        }
        _state.update { current ->
            val known = calendars.any { it.id == current.calendarId }
            current.copy(
                calendars = calendars,
                calendarId = if (known) current.calendarId else calendars.firstOrNull { it.kind == CalendarKind.OWN }?.id,
                hasLoaded = true,
            )
        }
    }

    fun setTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    fun setCalendar(id: String) {
        _state.update { it.copy(calendarId = id) }
    }

    fun setRecurrence(recurrence: Recurrence?) {
        _state.update { it.copy(recurrence = recurrence) }
    }

    fun setAlert(alert: AlertRule) {
        _state.update { it.copy(alert = alert) }
    }

    /** Moves the start; the end follows so the event keeps its length (m1.md screen 9 "동작"). */
    fun setStart(
        day: String,
        minute: Int,
    ) {
        _state.update { current ->
            val length = current.duration ?: HOUR
            val moved = current.copy(startDay = day, startMinute = minute)
            val start = absolute(day, minute) ?: return@update moved
            val (endDay, endMinute) = dayAndMinute(start + length) ?: return@update moved
            moved.copy(endDay = endDay, endMinute = endMinute)
        }
    }

    fun setEnd(
        day: String,
        minute: Int,
    ) {
        _state.update { it.copy(endDay = day, endMinute = minute) }
    }

    /** Turning "종일" off puts the event at 09:00–10:00 of its first day. */
    fun setAllDay(on: Boolean) {
        _state.update { current ->
            when {
                on == current.allDay -> current
                on -> current.copy(allDay = true)
                else -> current.copy(allDay = false, startMinute = 540, endDay = current.startDay, endMinute = 600)
            }
        }
    }

    /**
     * The repeat chips of m1.md screen 9: none, weekly on the start's weekday, daily, monthly, yearly, and the event's
     * own rule when it is none of those. The rule the event was opened with keeps its chip after another is picked, so
     * a stray tap on "안 함" can be taken back.
     */
    fun repeatChoices(): List<Recurrence?> {
        val choices = mutableListOf<Recurrence?>(null)
        listOf(Frequency.WEEKLY, Frequency.DAILY, Frequency.MONTHLY, Frequency.YEARLY).forEach { choices += rule(it) }
        val own = listOfNotNull(loadedRecurrence, _state.value.recurrence).distinct().filter { it !in choices }
        choices.addAll(2, own)
        return choices
    }

    /** What "저장" sends, or `null` while the times cannot be saved. */
    fun input(): EventInput? {
        val current = _state.value
        val calendar = current.selectedCalendar ?: return null
        if (!current.hasValidRange) return null
        val start = DayTime(current.startDay, if (current.allDay) 0u else current.startMinute.toUShort())
        val end =
            when {
                current.allDay -> DayTime(current.endDay, 0u)
                // 24:00 is midnight at the start of the next day (rust-core.md §3).
                current.endMinute >= DAY -> DayTime(KoreanCalendar.addDays(current.endDay, 1) ?: current.endDay, 0u)
                else -> DayTime(current.endDay, current.endMinute.toUShort())
            }
        return EventInput(
            calendarId = calendar.id,
            title = current.trimmedTitle,
            allDay = current.allDay,
            start = start,
            end = end,
            recurrence = current.recurrence,
            alert = current.alert,
            note = current.note,
        )
    }

    /** Saves a new event or the edit; [editScope] is the answer to "이 일정만 · 이후 모두" when [asksScope]. */
    suspend fun save(editScope: EditScope = EditScope.THIS_AND_FOLLOWING): Boolean {
        if (!_state.value.canSave) return false
        val input = input() ?: return false
        return work(Problem.SAVE_FAILED) {
            when (target) {
                is EditorTarget.New -> core.addEvent(input, fromSuggestion = false)
                is EditorTarget.Existing -> core.updateEvent(target.eventId, target.occurrence, editScope, input)
            }
        }
    }

    suspend fun delete(editScope: EditScope = EditScope.THIS_AND_FOLLOWING): Boolean {
        val current = _state.value
        if (target !is EditorTarget.Existing || current.readOnly || current.isWorking || !current.hasLoaded) return false
        return work(Problem.DELETE_FAILED) { core.deleteEvent(target.eventId, target.occurrence, editScope) }
    }

    /** [save] in [scope], so the answer comes back even when the screen went away meanwhile. */
    fun launchSave(
        editScope: EditScope,
        then: (Boolean) -> Unit,
    ) {
        scope.launch { then(save(editScope)) }
    }

    fun launchDelete(
        editScope: EditScope,
        then: (Boolean) -> Unit,
    ) {
        scope.launch { then(delete(editScope)) }
    }

    private suspend fun work(
        failure: Problem,
        block: () -> Unit,
    ): Boolean {
        _state.update { it.copy(isWorking = true) }
        val done =
            try {
                withContext(io) { block() }
                true
            } catch (e: CoreException) {
                false
            } catch (e: InternalException) {
                false
            }
        _state.update { it.copy(isWorking = false, problem = if (done) null else failure) }
        return done
    }

    private fun State.filled(input: EventInput?): State {
        if (input == null) return this
        return copy(
            title = input.title,
            calendarId = input.calendarId,
            recurrence = input.recurrence,
            alert = input.alert,
            note = input.note,
        ).timed(input.allDay, input.start, input.end)
    }

    private fun State.timed(
        allDay: Boolean,
        start: DayTime,
        end: DayTime,
    ): State {
        val startMinute = if (allDay) 540 else start.minute.toInt()
        return when {
            allDay -> copy(allDay = true, startDay = start.day, startMinute = startMinute, endDay = end.day, endMinute = 600)
            // Midnight at the end of the day reads as 24:00 of the start day.
            end.minute.toInt() == 0 && KoreanCalendar.addDays(start.day, 1) == end.day ->
                copy(allDay = false, startDay = start.day, startMinute = startMinute, endDay = start.day, endMinute = DAY)
            else -> copy(allDay = false, startDay = start.day, startMinute = startMinute, endDay = end.day, endMinute = end.minute.toInt())
        }
    }

    private companion object {
        const val DAY = 1440
        const val HOUR = 60

        /** Days count from here, so lengths across midnight and month ends are plain subtraction. */
        const val EPOCH = "2000-01-01"

        fun rule(frequency: Frequency) =
            Recurrence(frequency = frequency, interval = 1u, weekdays = emptyList(), until = null, count = null)

        fun absolute(
            day: String,
            minute: Int,
        ): Int? = KoreanCalendar.days(from = EPOCH, to = day)?.let { (it * DAY + minute).toInt() }

        fun dayAndMinute(absolute: Int): Pair<String, Int>? {
            val days = Math.floorDiv(absolute, DAY)
            val day = KoreanCalendar.addDays(EPOCH, days.toLong()) ?: return null
            return day to absolute - days * DAY
        }

        val State.duration: Int?
            get() {
                val start = absolute(startDay, startMinute) ?: return null
                val end = absolute(endDay, endMinute) ?: return null
                return end - start
            }
    }
}

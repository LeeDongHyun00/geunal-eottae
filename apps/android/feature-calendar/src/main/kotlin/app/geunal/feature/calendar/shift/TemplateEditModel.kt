package app.geunal.feature.calendar.shift

import app.geunal.core.AlertRule
import app.geunal.core.CalendarKind
import app.geunal.core.CalendarView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.Palette
import app.geunal.core.ShiftTemplate
import app.geunal.core.ShiftTemplateInput
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

/**
 * The shift template sheet (m1.md screen 18): a name, start and end (an end at or before the start runs to the next
 * day), the calendar the shifts go in (the core's '알바' when none is picked) and the alert.
 */
class TemplateEditModel(
    template: ShiftTemplate?,
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val name: String = "",
        val startMinute: Int = 540,
        val endMinute: Int = 1080,
        /** `null` until one is picked: the core then puts the shifts in '알바', making it the first time. */
        val calendarId: String? = null,
        val alert: AlertRule = AlertRule.Inherit,
        val calendars: List<CalendarView> = emptyList(),
        /** The template's own color and tint, for the preview card of a template that exists. */
        val color: Palette? = null,
        val tint: Int = 3,
        val isWorking: Boolean = false,
        val failed: Boolean = false,
    ) {
        /** 22:00–02:00 is one shift into the next day (m1.md screen 18 "상태"). */
        val crossesMidnight: Boolean get() = endMinute <= startMinute

        val lengthMinutes: Int get() = if (crossesMidnight) endMinute + DAY - startMinute else endMinute - startMinute

        val trimmedName: String get() = name.trim()

        val canSave: Boolean get() = trimmedName.isNotEmpty() && startMinute != endMinute && !isWorking

        val ownCalendars: List<CalendarView> get() = calendars.filter { it.kind == CalendarKind.OWN }

        /** The calendar picked, or `null` for the '알바' the core uses. */
        val selectedCalendar: CalendarView? get() = ownCalendars.firstOrNull { it.id == calendarId }

        /** The preview bar's color: the picked calendar's, else the template's. */
        val previewColor: Palette? get() = selectedCalendar?.color ?: color
    }

    val templateId: String? = template?.id
    val isNew: Boolean get() = templateId == null

    private val _state =
        MutableStateFlow(
            template?.let {
                State(
                    name = it.name,
                    startMinute = it.startMinute.toInt(),
                    endMinute = it.endMinute.toInt(),
                    calendarId = it.calendarId,
                    alert = it.alert,
                    color = it.color,
                    tint = it.tint.toInt(),
                )
            } ?: State(),
        )
    val state: StateFlow<State> = _state.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val calendars = withContext(io) { coreRead { core.calendars() } }.orEmpty()
        _state.update { it.copy(calendars = calendars) }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    fun setStart(minute: Int) {
        _state.update { it.copy(startMinute = minute) }
    }

    fun setEnd(minute: Int) {
        _state.update { it.copy(endMinute = minute) }
    }

    fun setCalendar(id: String?) {
        _state.update { it.copy(calendarId = id) }
    }

    fun setAlert(alert: AlertRule) {
        _state.update { it.copy(alert = alert) }
    }

    /** The template's id, or `null` when the core refused. */
    suspend fun save(): String? {
        val current = _state.value
        if (!current.canSave) return null
        val input =
            ShiftTemplateInput(
                id = templateId,
                name = current.trimmedName,
                startMinute = (current.startMinute % DAY).toUShort(),
                endMinute = (current.endMinute % DAY).toUShort(),
                calendarId = current.calendarId,
                alert = current.alert,
            )
        _state.update { it.copy(isWorking = true) }
        val id =
            try {
                withContext(io) { core.saveShiftTemplate(input) }
            } catch (e: CoreException) {
                null
            } catch (e: InternalException) {
                null
            }
        _state.update { it.copy(isWorking = false, failed = id == null) }
        return id
    }

    suspend fun delete(): Boolean {
        val id = templateId ?: return false
        if (_state.value.isWorking) return false
        _state.update { it.copy(isWorking = true) }
        val done =
            try {
                withContext(io) { core.deleteShiftTemplate(id) }
                true
            } catch (e: CoreException) {
                false
            } catch (e: InternalException) {
                false
            }
        _state.update { it.copy(isWorking = false, failed = !done) }
        return done
    }

    fun launchSave(then: (String) -> Unit) {
        scope.launch { save()?.let(then) }
    }

    fun launchDelete(then: (String) -> Unit) {
        val id = templateId ?: return
        scope.launch { if (delete()) then(id) }
    }

    private companion object {
        const val DAY = 1440
    }
}

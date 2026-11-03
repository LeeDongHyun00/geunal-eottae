package app.geunal.feature.calendar.calendars

import app.geunal.core.CalendarKind
import app.geunal.core.CalendarView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
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
 * 캘린더 관리 (m1.md screen 15): this app's calendars with their show switch, and the calendar sheet (screen 16). The
 * device calendars' group comes with the device mirror. The sheet's model lives here, so a rotation keeps what was typed.
 */
class CalendarsModel(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        /** This app's calendars, in the core's order. */
        val calendars: List<CalendarView> = emptyList(),
        val hasLoaded: Boolean = false,
        /** The last show switch the core refused, put back as it was. */
        val visibilityFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _editing = MutableStateFlow<CalendarEditModel?>(null)
    val editing: StateFlow<CalendarEditModel?> = _editing.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val all = withContext(io) { coreRead { core.calendars() } }
        _state.update { state ->
            state.copy(calendars = all?.filter { it.kind == CalendarKind.OWN } ?: state.calendars, hasLoaded = true)
        }
    }

    /** "표시": off hides the calendar's events from the month, week and list; groups see them as before. */
    fun setVisible(
        calendarId: String,
        visible: Boolean,
    ) {
        // The switch moves at once; a refusal puts it back when the list is read again.
        _state.update { state ->
            state.copy(
                calendars = state.calendars.map { if (it.id == calendarId) it.copy(visible = visible) else it },
                visibilityFailed = false,
            )
        }
        scope.launch {
            val done =
                try {
                    withContext(io) { core.setCalendarVisible(calendarId, visible) }
                    true
                } catch (e: CoreException) {
                    false
                } catch (e: InternalException) {
                    false
                }
            if (!done) _state.update { it.copy(visibilityFailed = true) }
            load()
        }
    }

    /** A row with its calendar, or "＋ 새 캘린더" with none. */
    fun edit(calendar: CalendarView?) {
        val calendars = _state.value.calendars
        lateinit var editor: CalendarEditModel
        editor =
            CalendarEditModel(
                calendar = calendar,
                isLast = calendar != null && calendars.size <= 1,
                used = calendars.mapNotNull { it.color }.toSet(),
                core = core,
                scope = scope,
                io = io,
                onDone = { edited(editor) },
            )
        _editing.value = editor
    }

    fun closeEditor() {
        _editing.value = null
    }

    /**
     * After a sheet saved or deleted: the list again, and that sheet closed if it is still up. Its work outlives the
     * sheet, which may have been closed and given way to another calendar's before the core answered.
     */
    private fun edited(editor: CalendarEditModel) {
        _editing.compareAndSet(editor, null)
        scope.launch { load() }
    }
}

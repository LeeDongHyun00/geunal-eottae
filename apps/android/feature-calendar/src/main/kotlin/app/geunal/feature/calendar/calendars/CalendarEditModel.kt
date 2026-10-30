package app.geunal.feature.calendar.calendars

import app.geunal.core.AlertRule
import app.geunal.core.CalendarInput
import app.geunal.core.CalendarView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.Palette
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
 * The calendar sheet (m1.md screen 16): the name, one of the twelve colors, soft or not, and the default alert of its
 * events. A new calendar takes the first color no other calendar has; the last calendar left cannot be deleted.
 */
class CalendarEditModel(
    val calendar: CalendarView?,
    /** The only calendar there is: "캘린더는 하나 이상 있어야 해요". */
    val isLast: Boolean,
    /** The colors the other calendars have, so a new one starts on a free one. */
    used: Set<Palette> = emptySet(),
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onDone: () -> Unit = {},
) {
    enum class Problem { SAVE_FAILED, DELETE_FAILED }

    data class State(
        val name: String = "",
        val color: Palette = Palette.entries.first(),
        val soft: Boolean = false,
        val defaultAlert: AlertRule = AlertRule.Inherit,
        /** What an inheriting calendar's events ring at, from the notification settings: minutes before, or off. */
        val settingsAlert: Int? = null,
        val isWorking: Boolean = false,
        val problem: Problem? = null,
    ) {
        val trimmedName: String get() = name.trim()

        val canSave: Boolean get() = trimmedName.isNotEmpty() && !isWorking
    }

    val isNew: Boolean get() = calendar == null

    val canDelete: Boolean get() = !isNew && !isLast

    private val _state =
        MutableStateFlow(
            calendar?.let { State(it.name, it.color ?: Palette.entries.first(), it.soft, it.defaultAlert) }
                ?: State(color = Palette.entries.firstOrNull { it !in used } ?: Palette.entries.first()),
        )
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false

    /** Reads what "알림 설정을 따름" comes to, for the alert row. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val settings = withContext(io) { coreRead { core.notificationSettings() } } ?: return@launch
            _state.update { it.copy(settingsAlert = settings.eventAlert) }
        }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    fun setColor(color: Palette) {
        _state.update { it.copy(color = color) }
    }

    fun setSoft(soft: Boolean) {
        _state.update { it.copy(soft = soft) }
    }

    fun setDefaultAlert(alert: AlertRule) {
        _state.update { it.copy(defaultAlert = alert) }
    }

    suspend fun save(): Boolean {
        val current = _state.value
        if (!current.canSave) return false
        val input = CalendarInput(calendar?.id, current.trimmedName, current.color, current.soft, current.defaultAlert)
        return work(Problem.SAVE_FAILED) { core.saveCalendar(input) }
    }

    /** The calendar and its events; the core keeps at least one calendar. */
    suspend fun delete(): Boolean {
        val id = calendar?.id ?: return false
        if (!canDelete || _state.value.isWorking) return false
        return work(Problem.DELETE_FAILED) { core.deleteCalendar(id) }
    }

    fun launchSave() {
        scope.launch { if (save()) onDone() }
    }

    fun launchDelete() {
        scope.launch { if (delete()) onDone() }
    }

    private suspend fun work(
        problem: Problem,
        block: () -> Unit,
    ): Boolean {
        _state.update { it.copy(isWorking = true, problem = null) }
        val done =
            try {
                withContext(io) { block() }
                true
            } catch (e: CoreException) {
                false
            } catch (e: InternalException) {
                false
            }
        _state.update { it.copy(isWorking = false, problem = if (done) null else problem) }
        return done
    }
}

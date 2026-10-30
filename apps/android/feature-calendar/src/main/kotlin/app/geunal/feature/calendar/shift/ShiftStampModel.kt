package app.geunal.feature.calendar.shift

import app.geunal.core.CalendarView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.ShiftDay
import app.geunal.core.ShiftTemplate
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

/**
 * The shift stamp screen (m1.md screen 10): pick a template, tap days. The taps stay a draft until "완료" hands the
 * changed days to the core in one call (`setShiftDays`), so "취소" changes nothing (#29 decision 6).
 *
 * The work runs in [scope], which outlives the screen, and the template sheet's model lives here too, so a rotation
 * keeps both the taps and a half-typed template.
 */
class ShiftStampModel(
    month: String,
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val month: String,
        val templates: List<ShiftTemplate> = emptyList(),
        val calendars: List<CalendarView> = emptyList(),
        val selectedTemplateId: String? = null,
        /** The template ids of each day as the core had them, and as the screen has them now. */
        val saved: Map<String, List<String>> = emptyMap(),
        val draft: Map<String, List<String>> = emptyMap(),
        val hasLoaded: Boolean = false,
        val isSaving: Boolean = false,
        val failed: Boolean = false,
    ) {
        val selectedTemplate: ShiftTemplate? get() = templates.firstOrNull { it.id == selectedTemplateId }

        /** The days whose templates differ from the core's; "완료" is on only when there are some. */
        val changedDays: List<ShiftDay>
            get() =
                (saved.keys + draft.keys).sorted().mapNotNull { day ->
                    val now = draft[day].orEmpty()
                    if (now == saved[day].orEmpty()) null else ShiftDay(day, now)
                }

        val hasChanges: Boolean get() = changedDays.isNotEmpty()

        /** The month's grid, whole weeks from Sunday like the month view. */
        val weeks: List<List<String>>
            get() {
                var day = KoreanCalendar.weekStart("$month-01") ?: return emptyList()
                val weeks = mutableListOf<List<String>>()
                do {
                    val start = day
                    weeks += (0L..6L).mapNotNull { KoreanCalendar.addDays(start, it) }
                    day = KoreanCalendar.addDays(start, 7) ?: break
                } while (KoreanCalendar.month(day) == month)
                return weeks
            }

        fun templates(on: String): List<ShiftTemplate> = draft[on].orEmpty().mapNotNull { id -> templates.firstOrNull { it.id == id } }

        /** "11월 9번": the days of the month with a shift. */
        val countThisMonth: Int get() = draft.count { (day, ids) -> KoreanCalendar.month(day) == month && ids.isNotEmpty() }

        /** The calendar a template stamps into, for "찍은 근무는 '알바' 캘린더의 일반 일정이 돼요". */
        fun calendarName(of: ShiftTemplate): String? = calendars.firstOrNull { it.id == of.calendarId }?.name
    }

    private val _state = MutableStateFlow(State(month))
    val state: StateFlow<State> = _state.asStateFlow()

    /** The template sheet, while it is up: `null` closed, a model for a new or an existing template. */
    private val _editing = MutableStateFlow<TemplateEditModel?>(null)
    val editing: StateFlow<TemplateEditModel?> = _editing.asStateFlow()

    private val loadedMonths = mutableSetOf<String>()
    private var started = false

    /** Reads once; a screen that comes back after a rotation finds everything read. */
    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val calendars = withContext(io) { coreRead { core.calendars() } }.orEmpty()
        val templates = withContext(io) { coreRead { core.shiftTemplates() } }.orEmpty()
        _state.update { current ->
            val kept = current.selectedTemplateId?.takeIf { id -> templates.any { it.id == id } }
            current.copy(calendars = calendars, templates = templates, selectedTemplateId = kept ?: templates.firstOrNull()?.id)
        }
        loadGrid()
        _state.update { it.copy(hasLoaded = true) }
    }

    suspend fun show(month: String) {
        _state.update { it.copy(month = month) }
        loadGrid()
    }

    /** ‹ and › above the grid. */
    fun step(months: Long) {
        val next = KoreanCalendar.addMonths(_state.value.month, months) ?: return
        scope.launch { show(next) }
    }

    fun select(templateId: String) {
        _state.update { it.copy(selectedTemplateId = templateId) }
    }

    /**
     * A tap with the chosen template: the same template again takes it off, another one is replaced by it. A day whose
     * month has not been read takes no tap, so a tap never stamps over a shift the screen did not show.
     */
    fun tap(day: String) {
        val template = _state.value.selectedTemplateId ?: return
        if (KoreanCalendar.month(day) !in loadedMonths) return
        _state.update { current ->
            val now = current.draft[day].orEmpty()
            current.copy(draft = current.draft + (day to if (now == listOf(template)) emptyList() else listOf(template)))
        }
    }

    /** "새 템플릿", or a long press on a template. */
    fun editTemplate(template: ShiftTemplate?) {
        _editing.value = TemplateEditModel(template, core, scope, io)
    }

    fun closeTemplate() {
        _editing.value = null
    }

    /** After the template sheet saved one: the list again, with the new or edited template chosen. */
    suspend fun templateSaved(id: String) {
        _editing.value = null
        // A first template may have made the '알바' calendar.
        val calendars = withContext(io) { coreRead { core.calendars() } }
        val templates = withContext(io) { coreRead { core.shiftTemplates() } }
        _state.update {
            it.copy(
                calendars = calendars ?: it.calendars,
                templates = templates ?: it.templates,
                selectedTemplateId = id,
            )
        }
    }

    /** After the template sheet deleted one: its stamped shifts stay as ordinary events (rust-core.md §3). */
    suspend fun templateDeleted(id: String) {
        _editing.value = null
        val templates = withContext(io) { coreRead { core.shiftTemplates() } }
        _state.update { current ->
            val left = templates ?: current.templates.filterNot { it.id == id }
            current.copy(
                templates = left,
                selectedTemplateId = if (current.selectedTemplateId == id) left.firstOrNull()?.id else current.selectedTemplateId,
                draft = current.draft.mapValues { (_, ids) -> ids - id },
                saved = current.saved.mapValues { (_, ids) -> ids - id },
            )
        }
    }

    fun launchTemplateSaved(id: String) {
        scope.launch { templateSaved(id) }
    }

    fun launchTemplateDeleted(id: String) {
        scope.launch { templateDeleted(id) }
    }

    /** "완료": the changed days in one call. */
    suspend fun commit(): Boolean {
        val days = _state.value.changedDays
        if (days.isEmpty()) return true
        _state.update { it.copy(isSaving = true) }
        val done =
            try {
                withContext(io) { core.setShiftDays(days) }
                true
            } catch (e: CoreException) {
                false
            } catch (e: InternalException) {
                false
            }
        _state.update { current ->
            current.copy(
                isSaving = false,
                failed = !done,
                saved = if (done) current.saved + days.associate { it.day to it.templateIds } else current.saved,
            )
        }
        return done
    }

    fun launchCommit(then: (Boolean) -> Unit) {
        scope.launch { then(commit()) }
    }

    /**
     * The grid shows the ends of the months on either side, and those days take taps too, so their months are read as
     * well.
     */
    private suspend fun loadGrid() {
        _state.value.weeks
            .flatten()
            .map { KoreanCalendar.month(it) }
            .distinct()
            .sorted()
            .forEach { loadMonth(it) }
    }

    private suspend fun loadMonth(month: String) {
        if (month in loadedMonths) return
        val days = withContext(io) { coreRead { core.shiftDays(month) } } ?: return
        loadedMonths += month
        _state.update { current ->
            // A day tapped before its month arrived is impossible (taps wait for it), but a second read of the same
            // month must not undo taps made since the first.
            val fresh = days.filter { it.day !in current.draft }
            current.copy(
                saved = current.saved + fresh.associate { it.day to it.templateIds },
                draft = current.draft + fresh.associate { it.day to it.templateIds },
            )
        }
    }
}

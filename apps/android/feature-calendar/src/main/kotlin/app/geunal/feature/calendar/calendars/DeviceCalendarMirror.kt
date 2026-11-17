package app.geunal.feature.calendar.calendars

import app.geunal.core.CalendarKind
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.DayTime
import app.geunal.core.DeviceCalendarInput
import app.geunal.core.DeviceEventInput
import app.geunal.core.InternalException
import app.geunal.feature.calendar.support.Coalescer
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
import java.time.ZoneOffset

enum class DeviceCalendarAccess { NOT_ASKED, GRANTED, DENIED }

/** One of the phone's calendars as 캘린더 관리 lists it: what the checkbox shows before anything is mirrored. */
data class DeviceCalendarSummary(
    /** CalendarContract's `Calendars._ID`, the mirror's source id. */
    val id: String,
    val name: String,
    val eventCount: Int,
)

/** The phone's calendars behind a seam, so the mirror is tested without CalendarContract. */
interface DeviceCalendarSource {
    /** Whether the app may read the phone's calendars now. */
    fun granted(): Boolean

    fun calendars(): List<DeviceCalendarSummary>

    /** The calendars with these ids and their events in the mirrored window, each repeat as its own occurrence. */
    fun read(ids: List<String>): List<DeviceCalendarInput>

    fun openSettings()

    /** Calls [action] whenever the phone's calendars change, from any app; the app answers with a refresh. */
    fun observeChanges(action: () -> Unit)
}

/**
 * The device calendar mirror (m1.md screen 15's second group, decision 5): read only, titles stay on this phone. The
 * core keeps which calendars are mirrored; the app reads them again whenever the phone's calendars may have changed (a
 * change notice from the calendar provider, the app's start, coming to the front).
 *
 * Its functions are called on the main thread, where the state lives; the core and the provider are read on [io].
 */
class DeviceCalendarMirror(
    private val core: CoreInterface,
    private val source: DeviceCalendarSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val access: DeviceCalendarAccess = DeviceCalendarAccess.NOT_ASKED,
        val calendars: List<DeviceCalendarSummary> = emptyList(),
        /** The ticked calendars. While the switch is off they keep the last ticks, which the dimmed list still shows. */
        val selected: Set<String> = emptySet(),
        val isOn: Boolean = false,
        val loaded: Boolean = false,
        val failed: Boolean = false,
        val isWorking: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val coalescer = Coalescer()

    /**
     * What the core holds since this model last wrote it: coming to the front reads the phone again, and an unchanged
     * phone writes nothing, so the views and the alerts are not read again for nothing.
     */
    private var written: List<DeviceCalendarInput>? = null

    /**
     * Counts the person's changes (the switch, a checkbox, onboarding's import). A refresh that read the phone before
     * one of them writes nothing, or it would mirror again what was just switched off.
     */
    private var choices = 0

    /** One write at a time, in the order they were made. */
    private val writes = Mutex()

    /** The source ids the core mirrors now. */
    private suspend fun mirrored(): Set<String> =
        withContext(io) { coreRead { core.calendars() } }
            .orEmpty()
            .filter { it.kind == CalendarKind.DEVICE && it.id.startsWith(ID_PREFIX) }
            .map { it.id.removePrefix(ID_PREFIX) }
            .toSet()

    /** Android tells a refusal only in the answer to the request, so a refusal is remembered until access is there. */
    private fun access(): DeviceCalendarAccess =
        when {
            source.granted() -> DeviceCalendarAccess.GRANTED
            _state.value.access == DeviceCalendarAccess.DENIED -> DeviceCalendarAccess.DENIED
            else -> DeviceCalendarAccess.NOT_ASKED
        }

    /** 캘린더 관리 opens: the phone's calendars and which of them the core mirrors. */
    suspend fun load() {
        val access = access()
        val mirrored = mirrored()
        val calendars = if (access == DeviceCalendarAccess.GRANTED) summaries() else emptyList()
        _state.update {
            val isOn = mirrored.isNotEmpty()
            it.copy(
                access = access,
                calendars = calendars,
                isOn = isOn,
                selected = if (isOn || it.selected.isEmpty()) mirrored else it.selected,
                loaded = true,
            )
        }
    }

    /**
     * The switch. On ticks every calendar, or the ticks the switch last turned off; off empties the mirror and keeps
     * the ticks for the dimmed list. Returns true when the screen must ask the system for access first and report the
     * answer to [accessAnswered].
     */
    suspend fun setOn(on: Boolean): Boolean {
        val state = _state.value
        if (on == state.isOn || state.isWorking) return false
        choices++
        if (!on) {
            if (mirror(emptyList())) _state.update { it.copy(isOn = false) }
            return false
        }
        val access = access()
        _state.update { it.copy(access = access) }
        if (access == DeviceCalendarAccess.NOT_ASKED) return true
        if (access == DeviceCalendarAccess.GRANTED) turnOn()
        return false
    }

    /** The system's answer to the request [setOn] asked for. */
    suspend fun accessAnswered(granted: Boolean) {
        _state.update { it.copy(access = if (granted) DeviceCalendarAccess.GRANTED else DeviceCalendarAccess.DENIED) }
        if (!granted) return
        choices++
        turnOn()
    }

    private suspend fun turnOn() {
        val calendars = summaries()
        _state.update { it.copy(calendars = calendars) }
        val present = calendars.map { it.id }
        // The remembered ticks may name calendars deleted or replaced on the phone while the switch was off. With none
        // of them left the switch would stay off, and the checkboxes are disabled while it is off, so every calendar
        // is ticked as on the first turn.
        val remembered = present.filter { it in _state.value.selected }
        val ids = remembered.ifEmpty { present }
        if (ids.isEmpty()) return
        if (mirror(ids)) _state.update { it.copy(selected = ids.toSet(), isOn = true) }
    }

    /** A checkbox. Unticking the last calendar turns the mirror off, as the core does with an empty list. */
    suspend fun toggle(id: String) {
        val state = _state.value
        if (!state.isOn || state.isWorking) return
        choices++
        val next = if (id in state.selected) state.selected - id else state.selected + id
        val ids = state.calendars.map { it.id }.filter { it in next }
        if (mirror(ids)) _state.update { it.copy(selected = next, isOn = ids.isNotEmpty()) }
    }

    /**
     * Onboarding's "가져오기" after the system said yes: every calendar on the phone. The count is what the card says,
     * "가져왔어요 · 캘린더 3개"; `null` when there is no access or the core refused.
     */
    suspend fun importAll(): Int? {
        choices++
        val access = access()
        _state.update { it.copy(access = access) }
        if (access != DeviceCalendarAccess.GRANTED) return null
        val calendars = summaries()
        _state.update { it.copy(calendars = calendars) }
        val ids = calendars.map { it.id }
        if (!mirror(ids)) return null
        _state.update { it.copy(selected = ids.toSet(), isOn = ids.isNotEmpty()) }
        return ids.size
    }

    /**
     * The phone's calendars may have changed: read the mirrored ones again. A calendar deleted on the phone drops out
     * of the mirror, and nothing is written while the mirror is off.
     *
     * Access taken back in the system settings empties the mirror. iOS keeps what it had, but there the copy can still
     * be switched off; here it would stay in the calendar, and in the free time groups see, with no way to read it
     * again or to see what it holds. Turning the switch on again after giving access brings it back.
     */
    suspend fun refresh() {
        coalescer.run {
            val started = choices
            val access = access()
            val mirrored = mirrored()
            if (access != DeviceCalendarAccess.GRANTED) {
                _state.update { it.copy(access = access, calendars = emptyList()) }
                if (mirrored.isNotEmpty() && mirror(emptyList(), unlessChangedSince = started)) {
                    _state.update { it.copy(isOn = false) }
                }
                return@run
            }
            val present = summaries()
            _state.update { it.copy(access = access, calendars = present) }
            if (mirrored.isEmpty()) return@run
            mirror(present.map { it.id }.filter { it in mirrored }, unlessChangedSince = started)
        }
    }

    fun openSettings() {
        source.openSettings()
    }

    /** Access can be taken back between the check and the read; that reads as no calendars. */
    private suspend fun summaries(): List<DeviceCalendarSummary> =
        withContext(io) {
            try {
                source.calendars()
            } catch (e: SecurityException) {
                emptyList()
            }
        }

    /**
     * [unlessChangedSince] is the count of the person's changes a refresh started from: when it moved meanwhile, the
     * refresh's reading is stale and nothing is written.
     */
    private suspend fun mirror(
        ids: List<String>,
        unlessChangedSince: Int? = null,
    ): Boolean {
        _state.update { it.copy(isWorking = true) }
        try {
            val inputs =
                if (ids.isEmpty()) {
                    emptyList()
                } else {
                    withContext(io) {
                        try {
                            source.read(ids)
                        } catch (e: SecurityException) {
                            null
                        }
                    }
                }
            if (inputs == null) {
                _state.update { it.copy(failed = true) }
                return false
            }
            return writes.withLock {
                if (unlessChangedSince != null && unlessChangedSince != choices) return@withLock false
                if (inputs == written) {
                    _state.update { it.copy(failed = false) }
                    return@withLock true
                }
                val done =
                    withContext(io) {
                        try {
                            core.mirrorDeviceCalendars(inputs)
                            true
                        } catch (e: CoreException) {
                            false
                        } catch (e: InternalException) {
                            false
                        }
                    }
                if (done) written = inputs
                _state.update { it.copy(failed = !done) }
                done
            }
        } finally {
            _state.update { it.copy(isWorking = false) }
        }
    }

    companion object {
        /** The core names a mirrored calendar `device:` and its source id (core/geunal-ffi `mirror_device_calendars`). */
        const val ID_PREFIX = "device:"
    }
}

/** How an instance of the phone's calendars becomes the core's input, in Korean days and minutes (rust-core.md §3). */
object DeviceEventDates {
    /**
     * The occurrence's id: a repeating event shares one event id across its repeats, and the core keys each occurrence
     * by its own id. The provider's own instance ids are not used: it numbers the instances again when it expands them.
     */
    fun occurrenceId(
        eventId: Long,
        begin: Instant,
    ): String = "$eventId@${begin.epochSecond}"

    /**
     * A timed event keeps its instants, read in Korean time. An all-day event floats: CalendarContract stores it from
     * UTC midnight of its first day to UTC midnight after its last, whatever the phone's time zone is.
     */
    fun input(
        id: String,
        title: String,
        allDay: Boolean,
        begin: Instant,
        end: Instant,
    ): DeviceEventInput {
        if (!allDay) return DeviceEventInput(id, title, allDay = false, start = korean(begin), end = korean(end))
        val first = begin.atOffset(ZoneOffset.UTC).toLocalDate()
        val last = end.minusMillis(1).atOffset(ZoneOffset.UTC).toLocalDate()
        return DeviceEventInput(
            id,
            title,
            allDay = true,
            start = DayTime(first.toString(), 0u),
            // An all-day event some app stored with no length still takes its one day.
            end = DayTime(maxOf(first, last).toString(), 0u),
        )
    }

    /** "계정 · 캘린더": phones often hold calendars of the same name in two accounts. A local calendar has no account. */
    fun name(
        account: String?,
        calendar: String,
        join: (String, String) -> String,
    ): String = if (account.isNullOrBlank() || account == calendar) calendar else join(account, calendar)

    /** A year back and two ahead, as iOS mirrors: the reach of the month view's scrolling in practice. */
    fun window(now: Instant): Pair<Instant, Instant> {
        val today = now.atZone(KoreanCalendar.zone)
        return today.minusYears(1).toInstant() to today.plusYears(2).toInstant()
    }

    private fun korean(instant: Instant): DayTime {
        val time = instant.atZone(KoreanCalendar.zone)
        return DayTime(time.toLocalDate().toString(), (time.hour * 60 + time.minute).toUShort())
    }
}

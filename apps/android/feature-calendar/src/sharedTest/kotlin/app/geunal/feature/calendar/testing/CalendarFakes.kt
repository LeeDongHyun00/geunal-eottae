package app.geunal.feature.calendar.testing

import app.geunal.core.AddedEvent
import app.geunal.core.AgendaDay
import app.geunal.core.AlertRule
import app.geunal.core.BirthdayItem
import app.geunal.core.CalendarKind
import app.geunal.core.CalendarView
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.DayCell
import app.geunal.core.DayTime
import app.geunal.core.EditScope
import app.geunal.core.EventInput
import app.geunal.core.EventItem
import app.geunal.core.EventSource
import app.geunal.core.Holiday
import app.geunal.core.NoHandle
import app.geunal.core.Palette
import app.geunal.core.Suggestion
import app.geunal.core.SuggestionSource
import app.geunal.core.TimedBlock
import app.geunal.core.UpNext
import app.geunal.core.WeekDay
import app.geunal.core.Weekday
import app.geunal.feature.calendar.support.KoreanCalendar
import java.time.DayOfWeek
import java.util.Collections

/** The calendars and events the calendar tests work with, after the boards (docs/design/boards/Month.dc.html). */
object Samples {
    val personal = calendar("c1", "개인", Palette.TANGERINE)
    val work = calendar("c2", "알바", Palette.MINT)
    val device = calendar("d1", "구글", null, kind = CalendarKind.DEVICE)

    fun calendar(
        id: String,
        name: String,
        color: Palette?,
        kind: CalendarKind = CalendarKind.OWN,
    ) = CalendarView(id, name, color, kind, soft = false, visible = true, defaultAlert = AlertRule.Inherit, eventCount = 0u, shiftTemplateCount = 0u)

    fun event(
        id: String,
        title: String,
        day: String,
        start: Int? = null,
        end: Int? = null,
        calendar: CalendarView = personal,
    ) = EventItem(
        eventId = id,
        occurrence = null,
        calendarId = calendar.id,
        calendarName = calendar.name,
        color = calendar.color,
        title = title,
        allDay = start == null,
        start = DayTime(day, (start ?: 0).toUShort()),
        end = DayTime(day, (end ?: 0).toUShort()),
        soft = false,
        source = if (calendar.kind == CalendarKind.DEVICE) EventSource.Device else EventSource.Own,
        recurrence = null,
    )

    fun input(
        title: String,
        day: String,
        start: Int,
        end: Int,
        calendar: CalendarView = personal,
    ) = EventInput(
        calendarId = calendar.id,
        title = title,
        allDay = false,
        start = DayTime(day, start.toUShort()),
        end = DayTime(day, end.toUShort()),
        recurrence = null,
        alert = AlertRule.Inherit,
        note = null,
    )

    fun suggestion(
        title: String,
        day: String,
        start: Int,
        end: Int,
        calendar: CalendarView = personal,
    ) = Suggestion(input(title, day, start, end, calendar), SuggestionSource.History)

    fun holiday(
        day: String,
        name: String,
    ) = Holiday(day, name, substitute = false)

    fun birthday(
        name: String,
        day: String,
    ) = BirthdayItem(personId = "p-$name", name = name, day = day, lunar = false)

    fun weekday(day: String): Weekday =
        when (KoreanCalendar.weekday(day)) {
            DayOfWeek.SUNDAY -> Weekday.SUNDAY
            DayOfWeek.MONDAY -> Weekday.MONDAY
            DayOfWeek.TUESDAY -> Weekday.TUESDAY
            DayOfWeek.WEDNESDAY -> Weekday.WEDNESDAY
            DayOfWeek.THURSDAY -> Weekday.THURSDAY
            DayOfWeek.FRIDAY -> Weekday.FRIDAY
            DayOfWeek.SATURDAY, null -> Weekday.SATURDAY
        }
}

/**
 * The calendar side of the core, without a Rust handle, the way UniFFI provides for fakes. The views read from the
 * maps the test fills; the writes are recorded in [calls]. Any method it does not override fails, so a screen that
 * starts calling something new shows up in its tests.
 */
open class CalendarFakeCore : Core(NoHandle) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var events: Map<String, List<EventItem>> = emptyMap()

    @Volatile var holidays: Map<String, List<Holiday>> = emptyMap()

    @Volatile var birthdays: Map<String, List<BirthdayItem>> = emptyMap()

    @Volatile var calendarList: List<CalendarView> = listOf(Samples.personal, Samples.work, Samples.device)

    @Volatile var suggestionList: List<Suggestion> = emptyList()

    @Volatile var added: Result<AddedEvent> = Result.success(AddedEvent("e9", null))

    @Volatile var deleteError: CoreException? = null

    @Volatile var updateError: CoreException? = null

    @Volatile var readError: CoreException? = null

    /** The month grid from the Sunday on or before the 1st to the Saturday on or after the last day. */
    override fun calendarMonth(month: String): List<DayCell> {
        calls += "month $month"
        readError?.let { throw it }
        val first = KoreanCalendar.parse("$month-01") ?: return emptyList()
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val last = first.plusMonths(1).minusDays(1)
        val end = last.plusDays((6 - last.dayOfWeek.value % 7).toLong())
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { date ->
            val day = date.toString()
            DayCell(
                day = day,
                weekday = Samples.weekday(day),
                inMonth = date.monthValue == first.monthValue,
                holidays = holidays[day].orEmpty(),
                events = events[day].orEmpty(),
                birthdays = birthdays[day].orEmpty(),
                leaveBridge = null,
            )
        }.toList()
    }

    /** Seven days from [startDay]; timed events become blocks side by side when they overlap, as the core lays them out. */
    override fun calendarWeek(startDay: String): List<WeekDay> {
        calls += "week $startDay"
        readError?.let { throw it }
        return (0L..6L).mapNotNull { KoreanCalendar.addDays(startDay, it) }.map { day ->
            val all = events[day].orEmpty()
            val timed = all.filterNot { it.allDay }
            WeekDay(
                day = day,
                weekday = Samples.weekday(day),
                holidays = holidays[day].orEmpty(),
                allDay = all.filter { it.allDay },
                birthdays = birthdays[day].orEmpty(),
                timed =
                    timed.map { event ->
                        val overlapping = timed.filter { it.start.minute < event.end.minute && event.start.minute < it.end.minute }
                        TimedBlock(
                            event = event,
                            startMinute = event.start.minute,
                            endMinute = event.end.minute,
                            column = overlapping.indexOf(event).coerceAtLeast(0).toUByte(),
                            columns = overlapping.size.coerceAtLeast(1).toUByte(),
                        )
                    },
                hidden = 0u,
            )
        }
    }

    /** The days with something on them in [days] days from [fromDay]. */
    override fun calendarAgenda(
        fromDay: String,
        days: UShort,
    ): List<AgendaDay> {
        calls += "agenda $fromDay $days"
        readError?.let { throw it }
        return (0L until days.toLong()).mapNotNull { KoreanCalendar.addDays(fromDay, it) }.mapNotNull { day ->
            val dayEvents = events[day].orEmpty()
            val dayHolidays = holidays[day].orEmpty()
            val dayBirthdays = birthdays[day].orEmpty()
            if (dayEvents.isEmpty() && dayHolidays.isEmpty() && dayBirthdays.isEmpty()) {
                null
            } else {
                AgendaDay(day, Samples.weekday(day), dayHolidays, dayEvents, dayBirthdays)
            }
        }
    }

    override fun calendars(): List<CalendarView> = calendarList

    override fun suggestions(day: String): List<Suggestion> {
        calls += "suggestions $day"
        return suggestionList
    }

    override fun addEvent(
        input: EventInput,
        fromSuggestion: Boolean,
    ): AddedEvent {
        calls += "add ${input.title} ${input.start.day} ${input.start.minute}-${input.end.minute}" + if (fromSuggestion) " suggested" else ""
        return added.getOrThrow()
    }

    override fun deleteEvent(
        eventId: String,
        occurrence: String?,
        scope: EditScope,
    ) {
        calls += "delete $eventId $scope"
        deleteError?.let { throw it }
    }

    override fun updateEvent(
        eventId: String,
        occurrence: String?,
        scope: EditScope,
        input: EventInput,
    ): String {
        calls += "update $eventId ${input.recurrence?.frequency}"
        updateError?.let { throw it }
        return eventId
    }

    override fun nextUp(count: UShort): List<UpNext> = emptyList()
}

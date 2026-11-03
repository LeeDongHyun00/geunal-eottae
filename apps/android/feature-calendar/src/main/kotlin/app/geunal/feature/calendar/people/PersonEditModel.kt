package app.geunal.feature.calendar.people

import app.geunal.core.AlertRule
import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.Person
import app.geunal.core.PersonInput
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
import java.time.Instant

/**
 * The person sheet (m1.md screen 17): a name, a solar or lunar birthday with an optional year, the alert and whether
 * the birthday may suggest a meetup. A lunar birthday says which solar day it falls on this year.
 */
class PersonEditModel(
    val person: Person?,
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onDone: () -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    data class State(
        val name: String = "",
        val lunar: Boolean = false,
        val leapMonth: Boolean = false,
        /** `null` until a birthday is picked; a person may have none. */
        val month: Int? = null,
        val day: Int? = null,
        /** The year of birth, which nobody has to give; ages are never shown. */
        val year: Int? = null,
        /** `Inherit` follows the birthday alerts of the notification settings, 당일 09:00 by default (rust-core.md). */
        val alert: AlertRule = AlertRule.Inherit,
        val suggest: Boolean = true,
        /** This year's solar day of a lunar birthday, as `YYYY-MM-DD`, for "올해는 11월 11일 수요일이에요". */
        val solarThisYear: String? = null,
        val isWorking: Boolean = false,
        val failed: Boolean = false,
    ) {
        val trimmedName: String get() = name.trim()

        val birthday: Birthday?
            get() {
                val month = month ?: return null
                val day = day ?: return null
                val calendar = if (lunar) BirthdayCalendar.Lunar(leapMonth) else BirthdayCalendar.Solar
                return Birthday(month.toUByte(), day.toUByte(), year, calendar)
            }

        val canSave: Boolean get() = trimmedName.isNotEmpty() && !isWorking
    }

    val isNew: Boolean get() = person == null

    /** A birthday a group shared keeps the name and day the group gave it; only the alert is this device's (M2). */
    val readOnly: Boolean get() = person?.readOnly == true

    private val _state = MutableStateFlow(person?.let(::stateOf) ?: State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { refreshSolar() }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    /**
     * 양력·음력: the day stays as typed, read in the other calendar, and comes down to that calendar's last day when it
     * has none so late (solar 31 → lunar 30, lunar 30 February → solar 29): the core refuses a day that does not exist.
     */
    fun setLunar(lunar: Boolean) {
        _state.update {
            val day = it.month?.let { month -> it.day?.coerceAtMost(lastDay(month, lunar)) } ?: it.day
            it.copy(lunar = lunar, leapMonth = lunar && it.leapMonth, day = day, solarThisYear = null)
        }
        scope.launch { refreshSolar() }
    }

    fun setBirthday(
        month: Int,
        day: Int,
        leapMonth: Boolean = false,
    ) {
        _state.update { it.copy(month = month, day = day, leapMonth = it.lunar && leapMonth, solarThisYear = null) }
        scope.launch { refreshSolar() }
    }

    fun setYear(year: Int?) {
        _state.update { it.copy(year = year) }
    }

    fun setAlert(alert: AlertRule) {
        _state.update { it.copy(alert = alert) }
    }

    fun setSuggest(suggest: Boolean) {
        _state.update { it.copy(suggest = suggest) }
    }

    /** Asks the core where a lunar birthday falls this year; the core keeps the table and its leap-month rules. */
    suspend fun refreshSolar() {
        val current = _state.value
        val birthday = current.birthday
        if (!current.lunar || birthday == null) return
        val year = KoreanCalendar.date(now()).year
        val solar = withContext(io) { coreRead { core.birthdayInYear(birthday, year) } }
        // Only if the birthday did not change while the core was asked.
        _state.update { if (it.birthday == birthday) it.copy(solarThisYear = solar) else it }
    }

    suspend fun save(): Boolean {
        val current = _state.value
        if (!current.canSave) return false
        val input =
            PersonInput(
                id = person?.id,
                name = current.trimmedName,
                birthday = current.birthday,
                alert = current.alert,
                suggest = current.suggest,
            )
        return work { core.savePerson(input) }
    }

    suspend fun delete(): Boolean {
        val id = person?.id ?: return false
        if (readOnly || _state.value.isWorking) return false
        return work { core.deletePerson(id) }
    }

    fun launchSave() {
        scope.launch { if (save()) onDone() }
    }

    fun launchDelete() {
        scope.launch { if (delete()) onDone() }
    }

    private suspend fun work(block: () -> Unit): Boolean {
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
        _state.update { it.copy(isWorking = false, failed = !done) }
        return done
    }

    companion object {
        /**
         * The last day a birthday can be on in [month]: 30 in any lunar month, and the solar month's own length with 29
         * in February, since a 2월 29일 birthday exists (rust-core.md §6, core `valid_day`).
         */
        fun lastDay(
            month: Int,
            lunar: Boolean,
        ): Int = if (lunar) LUNAR_DAYS else SOLAR_DAYS[month - 1]

        private const val LUNAR_DAYS = 30
        private val SOLAR_DAYS = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        private fun stateOf(person: Person): State {
            val birthday = person.birthday
            val lunar = birthday?.calendar as? BirthdayCalendar.Lunar
            return State(
                name = person.name,
                lunar = lunar != null,
                leapMonth = lunar?.leapMonth == true,
                month = birthday?.month?.toInt(),
                day = birthday?.day?.toInt(),
                year = birthday?.year,
                alert = person.alert,
                suggest = person.suggest,
            )
        }
    }
}

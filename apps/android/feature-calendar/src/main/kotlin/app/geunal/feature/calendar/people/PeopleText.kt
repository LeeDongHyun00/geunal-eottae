package app.geunal.feature.calendar.people

import android.content.res.Resources
import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.ContactImport
import app.geunal.core.Person
import app.geunal.core.PersonSource
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.DayTitle
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.text
import app.geunal.feature.calendar.support.weekdayLong

/** How people and birthdays read on 사람·생일 (m1.md screen 11, boards/People). */
object PeopleText {
    /** A birthday as it was given: "3월 14일", "음력 1월 3일", "음력 윤4월 3일"; "생일 없음" without one. */
    fun birthday(
        birthday: Birthday?,
        resources: Resources,
    ): String {
        if (birthday == null) return resources.getString(R.string.people_no_birthday)
        val month = birthday.month.toInt()
        val day = birthday.day.toInt()
        return when (val calendar = birthday.calendar) {
            BirthdayCalendar.Solar -> resources.getString(R.string.people_day, month, day)
            is BirthdayCalendar.Lunar ->
                if (calendar.leapMonth) {
                    resources.getString(R.string.people_lunar_leap_day, month, day)
                } else {
                    resources.getString(R.string.people_lunar_day, month, day)
                }
        }
    }

    /**
     * The line under a coming birthday: "10월 30일 금요일", or for a lunar one the day as given and where it falls
     * next, "음력 10월 3일 · 올해는 11월 11일 수요일" ("내년은 …" when that is next year).
     */
    fun upcoming(
        person: Person,
        today: String,
        resources: Resources,
    ): String {
        val next = person.nextBirthday ?: return birthday(person.birthday, resources)
        if (person.birthday?.calendar !is BirthdayCalendar.Lunar) return DayTitle.of(next, today)?.text(resources) ?: next
        return resources.getString(R.string.people_lunar_next, birthday(person.birthday, resources), solarDay(next, today, resources))
    }

    /** Where a lunar birthday falls: "올해는 11월 11일 수요일", or "내년은 1월 9일 토요일". */
    fun solarDay(
        day: String,
        today: String,
        resources: Resources,
    ): String {
        val date = KoreanCalendar.parse(day) ?: return day
        val sameYear = KoreanCalendar.parse(today)?.year?.let { it == date.year } ?: true
        return resources.getString(
            if (sameYear) R.string.people_this_year else R.string.people_next_year,
            date.monthValue,
            date.dayOfMonth,
            weekdayLong(resources, date.dayOfWeek),
        )
    }

    /** The person sheet's line under a lunar birthday, with this year's solar day once the core has said it. */
    fun lunarNote(
        solarThisYear: String?,
        resources: Resources,
    ): String {
        val date = solarThisYear?.let(KoreanCalendar::parse) ?: return resources.getString(R.string.person_lunar_note_plain)
        return resources.getString(R.string.person_lunar_note, date.monthValue, date.dayOfMonth, weekdayLong(resources, date.dayOfWeek))
    }

    /** How an import went, as its toast says it. */
    fun imported(
        result: ContactImport,
        resources: Resources,
    ): String {
        val added = result.added.toInt()
        val updated = result.updated.toInt()
        return when {
            added > 0 && updated > 0 -> resources.getString(R.string.people_imported_updated, added, updated)
            added > 0 -> resources.getString(R.string.people_imported, added)
            updated > 0 -> resources.getString(R.string.people_updated, updated)
            else -> resources.getString(R.string.people_imported_none)
        }
    }

    /** The pill on a coming birthday: "오늘" or "D-5". */
    fun dDay(
        days: Int,
        resources: Resources,
    ): String = if (days <= 0) resources.getString(R.string.people_today) else resources.getString(R.string.people_d_day, days)

    /** Where a birthday came from: "직접 등록", "연락처에서 가져옴", or a group that shared it (M2). */
    fun source(
        source: PersonSource,
        resources: Resources,
    ): String =
        resources.getString(
            when (source) {
                PersonSource.Manual -> R.string.people_source_manual
                PersonSource.Contacts -> R.string.people_source_contacts
                is PersonSource.Group -> R.string.people_source_group
            },
        )
}

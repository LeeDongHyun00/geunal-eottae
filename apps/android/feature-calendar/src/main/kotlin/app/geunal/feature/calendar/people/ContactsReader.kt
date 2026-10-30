package app.geunal.feature.calendar.people

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.core.content.ContextCompat
import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.ContactInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The contacts with a birthday to pick from, and how many were left out for having none. */
data class ContactsFound(
    val withBirthday: List<ContactInput>,
    val withoutBirthday: Int,
)

/** The device's contacts, for "연락처에서 생일 가져오기" (m1.md §2.4). */
interface ContactsReader {
    fun canRead(): Boolean

    /** By name; nothing without the permission. Names and birthdays stay on this device until the core stores them. */
    suspend fun birthdays(): ContactsFound
}

/**
 * Reads the name and the birthday of each contact and nothing else (docs/security/app-review.md §2 "연락처"): the
 * birthday events, then the structured names of only those contacts. The aggregate's display name is not used, since
 * a contact without a name falls back to its phone number or email there. A contact has no standard field saying a
 * birthday is lunar, so every one comes in as solar and the person sheet can change it (docs/parts/android.md §4).
 */
class DeviceContactsReader(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContactsReader {
    override fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override suspend fun birthdays(): ContactsFound {
        if (!canRead()) return ContactsFound(emptyList(), 0)
        return withContext(io) {
            val birthdays = birthdayEvents()
            val names = names(birthdays.keys)
            val found =
                birthdays.mapNotNull { (id, birthday) ->
                    names[id]?.let { ContactInput(id.toString(), it, birthday) }
                }
            ContactsFound(found.sortedBy { it.name }, withoutBirthday = (contactCount() - birthdays.size).coerceAtLeast(0))
        }
    }

    /** The first readable birthday of each contact. */
    private fun birthdayEvents(): Map<Long, Birthday> {
        val found = mutableMapOf<Long, Birthday>()
        context.contentResolver
            .query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${Event.TYPE} = ?",
                arrayOf(Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val birthday = ContactBirthday.parse(cursor.getString(1)) ?: continue
                    found.putIfAbsent(cursor.getLong(0), birthday)
                }
            }
        return found
    }

    /** The structured name of each of [ids], asked in chunks under SQLite's limit on bound arguments. */
    private fun names(ids: Set<Long>): Map<Long, String> {
        val names = mutableMapOf<Long, String>()
        ids.chunked(NAME_CHUNK).forEach { chunk ->
            context.contentResolver
                .query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.CONTACT_ID, StructuredName.DISPLAY_NAME),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.CONTACT_ID} IN " +
                        chunk.joinToString(",", "(", ")") { "?" },
                    arrayOf(StructuredName.CONTENT_ITEM_TYPE) + chunk.map(Long::toString),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)?.trim().orEmpty()
                        if (name.isNotEmpty()) names.putIfAbsent(cursor.getLong(0), name)
                    }
                }
        }
        return names
    }

    /** Only the count, for "생일이 없는 연락처 N명은 빼고 보여 줘요". */
    private fun contactCount(): Int =
        context.contentResolver
            .query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)
            ?.use { it.count } ?: 0

    private companion object {
        const val NAME_CHUNK = 500
    }
}

/** The birthday text contacts apps write: `YYYY-MM-DD`, or `--MM-DD` without a year. */
object ContactBirthday {
    private val full = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val noYear = Regex("""^--(\d{1,2})-(\d{1,2})""")

    fun parse(text: String?): Birthday? {
        val value = text?.trim() ?: return null
        full.find(value)?.let { match ->
            val (year, month, day) = match.destructured
            return birthday(month.toInt(), day.toInt(), year.toInt().takeIf { it in 1800..2200 })
        }
        noYear.find(value)?.let { match ->
            val (month, day) = match.destructured
            return birthday(month.toInt(), day.toInt(), null)
        }
        return null
    }

    private fun birthday(
        month: Int,
        day: Int,
        year: Int?,
    ): Birthday? {
        if (month !in 1..12 || day !in 1..31) return null
        return Birthday(month.toUByte(), day.toUByte(), year, BirthdayCalendar.Solar)
    }
}

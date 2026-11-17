package app.geunal.feature.calendar.calendars

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.geunal.core.DeviceCalendarInput
import app.geunal.core.DeviceEventInput
import app.geunal.feature.calendar.R
import java.time.Instant

/**
 * The phone's calendars through CalendarContract, read only (docs/parts/android.md §4): the app never writes to them.
 * Repeats come expanded from the provider's Instances table, so each occurrence is its own row.
 */
class DeviceCalendars(
    context: Context,
    private val now: () -> Instant = Instant::now,
) : DeviceCalendarSource {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private var onChange: (() -> Unit)? = null
    private var watching = false

    /** Change notices arrive on the main thread, where the mirror's state lives. */
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange?.invoke()
            }
        }

    override fun granted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    override fun observeChanges(action: () -> Unit) {
        onChange = action
        watch()
    }

    /**
     * The provider takes an observer only from an app that may read it, so this is tried again at each read until it
     * holds: access given later, in onboarding or the settings, starts the notices without a restart.
     */
    @Synchronized
    private fun watch() {
        if (watching || onChange == null || !granted()) return
        try {
            resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            watching = true
        } catch (e: SecurityException) {
            // Access was taken back since the check; the next read tries again.
        }
    }

    override fun calendars(): List<DeviceCalendarSummary> {
        watch()
        val calendars = allCalendars()
        val events = mutableMapOf<String, MutableSet<Long>>()
        // "일정 N개" counts events, not their repeats.
        instances(calendars.map { it.id }.toSet()) { row -> events.getOrPut(row.calendarId) { mutableSetOf() } += row.eventId }
        return calendars.map { DeviceCalendarSummary(it.id, it.name, events[it.id]?.size ?: 0) }
    }

    override fun read(ids: List<String>): List<DeviceCalendarInput> {
        watch()
        val wanted = ids.toSet()
        val calendars = allCalendars().filter { it.id in wanted }
        val events = mutableMapOf<String, MutableList<DeviceEventInput>>()
        instances(calendars.map { it.id }.toSet()) { row ->
            val begin = Instant.ofEpochMilli(row.begin)
            events.getOrPut(row.calendarId) { mutableListOf() } +=
                DeviceEventDates.input(
                    id = DeviceEventDates.occurrenceId(row.eventId, begin),
                    title = row.title,
                    allDay = row.allDay,
                    begin = begin,
                    end = Instant.ofEpochMilli(row.end),
                )
        }
        return calendars.map { DeviceCalendarInput(it.id, it.name, events[it.id].orEmpty()) }
    }

    override fun openSettings() {
        app.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private class Calendar(
        val id: String,
        val name: String,
    )

    private class Instance(
        val calendarId: String,
        val eventId: Long,
        val title: String,
        val allDay: Boolean,
        val begin: Long,
        val end: Long,
    )

    /** Every calendar on the phone, in the provider's order. Without access there are none. */
    private fun allCalendars(): List<Calendar> {
        if (!granted()) return emptyList()
        val found = mutableListOf<Calendar>()
        resolver
            .query(CalendarContract.Calendars.CONTENT_URI, CALENDAR_COLUMNS, null, null, CalendarContract.Calendars._ID)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty()
                    // A calendar kept on the phone itself belongs to no account worth naming.
                    val account = cursor.getString(2).takeUnless { cursor.getString(3) == CalendarContract.ACCOUNT_TYPE_LOCAL }
                    found +=
                        Calendar(
                            id = cursor.getLong(0).toString(),
                            name = DeviceEventDates.name(account, name) { a, c -> app.getString(R.string.calendars_device_name, a, c) },
                        )
                }
            }
        return found
    }

    /** The occurrences of [calendars] in the mirrored window, without the ones their organizer cancelled. */
    private fun instances(
        calendars: Set<String>,
        each: (Instance) -> Unit,
    ) {
        if (calendars.isEmpty() || !granted()) return
        val (from, to) = DeviceEventDates.window(now())
        val uri =
            CalendarContract.Instances.CONTENT_URI
                .buildUpon()
                .also {
                    ContentUris.appendId(it, from.toEpochMilli())
                    ContentUris.appendId(it, to.toEpochMilli())
                }.build()
        resolver.query(uri, INSTANCE_COLUMNS, null, null, CalendarContract.Instances.BEGIN)?.use { cursor ->
            while (cursor.moveToNext()) {
                val calendarId = cursor.getLong(0).toString()
                if (calendarId !in calendars) continue
                if (cursor.getInt(6) == CalendarContract.Instances.STATUS_CANCELED) continue
                each(
                    Instance(
                        calendarId = calendarId,
                        eventId = cursor.getLong(1),
                        title = cursor.getString(2).orEmpty(),
                        allDay = cursor.getInt(3) != 0,
                        begin = cursor.getLong(4),
                        end = cursor.getLong(5),
                    ),
                )
            }
        }
    }

    private companion object {
        val CALENDAR_COLUMNS =
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            )
        val INSTANCE_COLUMNS =
            arrayOf(
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.STATUS,
            )
    }
}

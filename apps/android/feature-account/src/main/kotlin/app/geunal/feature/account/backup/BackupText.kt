package app.geunal.feature.account.backup

import android.content.res.Resources
import app.geunal.core.BackupView
import app.geunal.feature.account.R
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** How backups read (boards/Backup): "오늘 03:12", "어제 03:10", "10월 23일 03:11", "일정 128개 · 사람 12명", "2.4MB". */
object BackupText {
    private val seoul = ZoneId.of("Asia/Seoul")

    /** A backup's time in Korea, against [now]. */
    fun time(
        at: Instant,
        now: Instant,
        resources: Resources,
    ): String {
        val moment = at.atZone(seoul)
        val day = moment.toLocalDate()
        val today = now.atZone(seoul).toLocalDate()
        val clock = String.format(Locale.ROOT, "%02d:%02d", moment.hour, moment.minute)
        return when {
            day == today -> resources.getString(R.string.backup_today, clock)
            day == today.minusDays(1) -> resources.getString(R.string.backup_yesterday, clock)
            day.year == today.year -> resources.getString(R.string.backup_day, day.monthValue, day.dayOfMonth, clock)
            // The year as text: a localized integer would read "2,025".
            else -> resources.getString(R.string.backup_day_other_year, day.year.toString(), day.monthValue, day.dayOfMonth, clock)
        }
    }

    /** "830KB" under a megabyte, else "2.4MB" with one decimal. */
    fun size(
        bytes: ULong,
        resources: Resources,
    ): String {
        val value = bytes.toLong()
        if (value <
            MEGABYTE
        ) {
            return resources.getString(R.string.backup_size_kb, ((value + KILOBYTE - 1) / KILOBYTE).coerceAtLeast(1).toInt())
        }
        return resources.getString(R.string.backup_size_mb, String.format(Locale.ROOT, "%.1f", value / MEGABYTE.toDouble()))
    }

    /** "일정 128개 · 사람 12명", or nothing for a backup another device made, which this one knows only the size of. */
    fun counts(
        backup: BackupView,
        resources: Resources,
    ): String? {
        val events = backup.events ?: return null
        val people = backup.people ?: return null
        return resources.getString(R.string.backup_counts, events.toInt(), people.toInt())
    }

    /** A recent backup's second line: "자동 · 일정 128개 · 사람 12명", "직접", or only the counts. */
    fun detail(
        backup: BackupView,
        resources: Resources,
    ): String =
        listOfNotNull(
            backup.automatic?.let { resources.getString(if (it) R.string.backup_kind_auto else R.string.backup_kind_manual) },
            counts(backup, resources),
        ).joinToString(" · ")

    private const val KILOBYTE = 1_000L
    private const val MEGABYTE = 1_000_000L
}

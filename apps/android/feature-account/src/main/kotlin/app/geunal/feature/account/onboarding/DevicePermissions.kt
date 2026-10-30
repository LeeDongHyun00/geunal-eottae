package app.geunal.feature.account.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** [SystemPermissions] on the real device. */
class DevicePermissions(
    private val context: Context,
) : SystemPermissions {
    override fun calendarGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The device's calendars, for "가져왔어요 · 캘린더 3개". Which ones to mirror is chosen under 캘린더 관리. */
    override fun deviceCalendarCount(): Int {
        if (!calendarGranted()) return 0
        return try {
            context.contentResolver
                .query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)
                ?.use { it.count } ?: 0
        } catch (e: SecurityException) {
            0
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    override fun notificationsNeedAsking(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

package app.geunal.feature.calendar.alerts

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import app.geunal.feature.calendar.R
import java.io.File
import java.time.Instant
import app.geunal.designsystem.R as DesignR

/**
 * The alerts on the real device (docs/parts/android.md §4 "로컬 알림"): an AlarmManager alarm per alert, which
 * [AlertReceiver] turns into a notification from the stored plan, without the core or any screen.
 */
class SystemAlertCenter(
    context: Context,
) : AlertCenter {
    private val app = context.applicationContext
    private val alarms = AlertAlarms(AlertPlanStore(File(app.noBackupFilesDir, PLAN_FILE)), SystemAlarmSetter(app))
    private val notifications = app.getSystemService(NotificationManager::class.java)

    init {
        // Made before the first alert rings, so the person finds them in the system settings from the start.
        // Making a channel that exists changes nothing the person set.
        AlertKind.entries.forEach { kind ->
            notifications.createNotificationChannel(NotificationChannel(channel(kind), app.getString(publicTitle(kind)), importance(kind)))
        }
    }

    override fun allowsAlerts(): Boolean = notifications.areNotificationsEnabled()

    override fun scheduled(): List<PlannedAlert> = alarms.scheduled()

    override fun schedule(alert: PlannedAlert) = alarms.schedule(alert)

    override fun cancel(ids: List<String>) = alarms.cancel(ids)

    override fun rearm() = alarms.rearm()

    override fun removeAll() {
        alarms.clear()
        // Only the alerts: a push notification of a later milestone is not this class's to take away.
        notifications.activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { notifications.cancel(it.tag, it.id) }
    }

    /** The alarm of [id] rang: shows its notification, unless it was cancelled or notifications were turned off since. */
    fun fired(id: String) {
        val alert = alarms.take(id) ?: return
        if (!allowsAlerts()) return
        notifications.notify(TAG_PREFIX + alert.id, NOTIFICATION_ID, notification(alert))
    }

    private fun notification(alert: PlannedAlert) =
        NotificationCompat
            .Builder(app, channel(alert.kind))
            .setSmallIcon(if (alert.kind == AlertKind.BIRTHDAY) DesignR.drawable.ds_ic_gift else DesignR.drawable.ds_ic_calendar)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setWhen(alert.fireAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(if (alert.kind == AlertKind.EVENT) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open(alert))
            // With "민감한 내용 숨기기" on, a locked screen shows the public version: what kind of alert it is and
            // nothing of the title, the name or the date (tone.md "잠긴 화면(안드로이드)").
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat
                    .Builder(app, channel(alert.kind))
                    .setSmallIcon(DesignR.drawable.ds_ic_calendar)
                    .setContentTitle(app.getString(publicTitle(alert.kind)))
                    .build(),
            ).build()

    /** Tapping the notification opens the app on the alert's route and nothing else. */
    private fun open(alert: PlannedAlert): PendingIntent? {
        val intent = AlertIntents.open(app, alert) ?: return null
        return PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun importance(kind: AlertKind): Int =
        // A suggestion weeks ahead does not interrupt; an event or a birthday shows over what is on screen.
        if (kind == AlertKind.LEAVE) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH

    private fun channel(kind: AlertKind): String =
        when (kind) {
            AlertKind.EVENT -> "alert.event"
            AlertKind.BIRTHDAY -> "alert.birthday"
            AlertKind.LEAVE -> "alert.leave"
        }

    /** The channel's name, which is also all a locked screen may say (tone.md). */
    private fun publicTitle(kind: AlertKind): Int =
        when (kind) {
            AlertKind.EVENT -> R.string.alert_public_event
            AlertKind.BIRTHDAY -> R.string.alert_public_birthday
            AlertKind.LEAVE -> R.string.alert_public_leave
        }

    private companion object {
        const val PLAN_FILE = "alerts/plan"

        /** Alerts are told apart by their tag, the alert's id; the number is the same for all. */
        const val NOTIFICATION_ID = 1
        const val TAG_PREFIX = "alert."
    }
}

/** The intents of an alert: the alarm's broadcast and the notification's tap. Each is told apart by its data URI. */
object AlertIntents {
    internal const val ACTION_FIRE = "app.geunal.alert.FIRE"
    private const val ACTION_OPEN = "app.geunal.alert.OPEN"
    private const val SCHEME = "geunal-alert"
    private const val EXTRA_PREFIX = "app.geunal.alert."
    private val ROUTE_KEYS = listOf("route", "event", "occurrence", "person", "day")

    internal fun fire(
        context: Context,
        id: String,
    ): Intent = Intent(context, AlertReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.fromParts(SCHEME, id, null))

    internal fun firedId(intent: Intent): String? = intent.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart

    /** The app's own launcher activity, explicitly: only this app can be opened by it. */
    internal fun open(
        context: Context,
        alert: PlannedAlert,
    ): Intent? {
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        return Intent(ACTION_OPEN)
            .setComponent(launcher)
            .setData(Uri.fromParts(SCHEME, alert.id, null))
            // The activity that is already there takes it in onNewIntent rather than a second one opening over it.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { alert.route.values().forEach { (key, value) -> putExtra(EXTRA_PREFIX + key, value) } }
    }

    /** The route of a tapped alert, when [intent] is one. */
    fun route(intent: Intent?): AlertRoute? {
        if (intent?.action != ACTION_OPEN) return null
        return AlertRoute.of(ROUTE_KEYS.mapNotNull { key -> intent.getStringExtra(EXTRA_PREFIX + key)?.let { key to it } }.toMap())
    }
}

/**
 * AlarmManager's side of [AlertAlarms].
 *
 * Exact alarms are what a calendar's reminders are for, so the manifest declares `USE_EXACT_ALARM` (Android 13 and
 * later, granted at install to calendar and alarm apps) and `SCHEDULE_EXACT_ALARM` for Android 12, where the person can
 * take the access back. Whenever the system says no, the alarm is set inexactly instead of not at all.
 */
internal class SystemAlarmSetter(
    private val context: Context,
) : AlarmSetter {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun exact(
        id: String,
        at: Instant,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) return false
        return try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation(id))
            true
        } catch (e: SecurityException) {
            // The access was taken back between the check and the call.
            false
        }
    }

    override fun inexact(
        id: String,
        at: Instant,
    ) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation(id))
    }

    override fun cancel(id: String) {
        // Asked for without making one: an alarm that was never set has nothing to cancel.
        val set =
            PendingIntent.getBroadcast(context, 0, AlertIntents.fire(context, id), PendingIntent.FLAG_NO_CREATE or IMMUTABLE) ?: return
        alarms.cancel(set)
        set.cancel()
    }

    private fun operation(id: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, AlertIntents.fire(context, id), PendingIntent.FLAG_UPDATE_CURRENT or IMMUTABLE)

    private companion object {
        /** Nothing may fill in or change the broadcast on its way back to the app. */
        const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE
    }
}

package app.geunal.feature.calendar.alerts

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Shows an alert when its alarm rings, and sets the alarms again when the system has dropped or shifted them: after a
 * reboot, an update of the app, a change of the clock or the time zone, or of the exact alarm access. It works from the
 * stored plan alone, so it needs neither the core nor a screen, and it is not exported: only the system and this app
 * reach it.
 */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // `null` sets the alarms again; an id is the alert whose alarm rang.
        val fired: String? =
            when (intent.action) {
                AlertIntents.ACTION_FIRE -> AlertIntents.firedId(intent) ?: return
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                -> null
                else -> return
            }
        // The plan is a file: read off the main thread, with the broadcast held open until it is done.
        val pending = goAsync()
        thread(name = "alert-receiver") {
            try {
                val center = SystemAlertCenter(context)
                if (fired != null) center.fired(fired) else center.rearm()
            } finally {
                pending.finish()
            }
        }
    }
}

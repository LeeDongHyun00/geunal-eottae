package app.geunal.feature.calendar.alerts

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.NotificationSettings
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the system lets this app post notifications at all. */
fun interface NotificationAccess {
    fun enabled(): Boolean
}

class DeviceNotificationAccess(
    private val context: Context,
) : NotificationAccess {
    override fun enabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/**
 * 알림 설정 (m1.md screen 19): the default alerts of timed and all-day events, the next-up bar, birthday alerts and
 * the long-weekend suggestions, all rung by this device. The core keeps the values and says what fires when; the
 * scheduling stays with the app. Each change shows at once and is written in order; a refusal puts the screen back to
 * what the core last took.
 */
class AlertSettingsModel(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val access: NotificationAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val settings: NotificationSettings? = null,
        /** Off in the system settings: a card says so and the switches below are dimmed. */
        val systemEnabled: Boolean = true,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State(systemEnabled = access.enabled()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false

    /** One write at a time: two in flight could land out of order and leave an older choice stored. */
    private val writes = Mutex()

    /** What the core holds, as read or last written; a refusal goes back to it. */
    private var confirmed: NotificationSettings? = null

    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val settings = withContext(io) { coreRead { core.notificationSettings() } }
        if (settings != null) confirmed = settings
        _state.update { it.copy(settings = settings ?: it.settings, systemEnabled = access.enabled()) }
    }

    /** Back from the system settings: whether notifications were turned on there. */
    fun resumed() {
        _state.update { it.copy(systemEnabled = access.enabled()) }
    }

    /** Minutes before a timed event; `null` is none. */
    fun setEventAlert(minutes: Int?) = write { it.copy(eventAlert = minutes) }

    /** Minutes before 00:00 of an all-day event's day; `null` is none. */
    fun setAllDayAlert(minutes: Int?) = write { it.copy(allDayAlert = minutes) }

    fun setNextUpBar(on: Boolean) = write { it.copy(nextUpBar = on) }

    fun setBirthdayAlerts(on: Boolean) = write { it.copy(birthdayAlerts = on) }

    fun setLeaveSuggestions(on: Boolean) = write { it.copy(leaveSuggestions = on) }

    private fun write(change: (NotificationSettings) -> NotificationSettings) {
        val shown = _state.value.settings ?: return
        _state.update { it.copy(settings = change(shown), failed = false) }
        scope.launch {
            writes.withLock {
                // The screen as it is now, which holds every change made so far; a later change's write finds it
                // already stored and has nothing left to do.
                val wanted = _state.value.settings ?: return@withLock
                if (wanted == confirmed) return@withLock
                val done =
                    try {
                        withContext(io) { core.setNotificationSettings(wanted) }
                        true
                    } catch (e: CoreException) {
                        false
                    } catch (e: InternalException) {
                        false
                    }
                if (done) {
                    confirmed = wanted
                } else {
                    _state.update { it.copy(settings = confirmed ?: it.settings, failed = true) }
                }
            }
        }
    }

    companion object {
        /** Timed events: none, at the start, or before it (the editor's choices, m1.md screen 9). */
        val EVENT_CHOICES: List<Int?> = listOf(null, 0, 10, 30, 60, 1440)

        /** All-day events, counted from 00:00 of the day: none, 당일 09:00, 전날 18:00. */
        val ALL_DAY_CHOICES: List<Int?> = listOf(null, -540, 360)
    }
}

package app.geunal.feature.account.onboarding

import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.BuildChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PermissionState { NOT_ASKED, GRANTED, DENIED }

/**
 * The OS side of the permissions screen, so the model is tested without system dialogs. Asking happens in the screen,
 * which owns the permission launchers, and reports the answer back to the model.
 */
interface SystemPermissions {
    fun calendarGranted(): Boolean

    /** Notifications need asking from Android 13; before that they are on unless the person turned them off. */
    fun notificationsNeedAsking(): Boolean

    fun notificationsEnabled(): Boolean

    fun openAppSettings()

    fun openNotificationSettings()
}

/** Where the import of the device's calendars stands once access is given. */
sealed interface CalendarImport {
    data object Idle : CalendarImport

    data object Working : CalendarImport

    /** "가져왔어요 · 캘린더 3개": the calendars the mirror took. */
    data class Done(
        val calendars: Int,
    ) : CalendarImport

    /** The mirror did not take them; the button offers to try again. */
    data object Failed : CalendarImport
}

/** What the screen does after a card's button. */
sealed interface PermissionAction {
    data object None : PermissionAction

    data class Ask(
        val permission: String,
    ) : PermissionAction

    data object OpenAppSettings : PermissionAction

    data object OpenNotificationSettings : PermissionAction
}

/**
 * The permissions screen (m1.md screen 3): device calendars and notifications, plus the test statistics consent in alpha
 * builds. Every card can be refused; refusing only turns its button into "설정에서 켜기".
 */
class PermissionsModel(
    private val core: CoreInterface,
    private val permissions: SystemPermissions,
    channel: BuildChannel,
    private val notify: (AccountNotice) -> Unit,
    /** Where the import runs; it outlives the screen, so leaving for the calendar does not cut it short. */
    private val scope: CoroutineScope,
    /** Mirrors every calendar of the phone and says how many; `null` when it could not. */
    private val importCalendars: suspend () -> Int?,
) {
    data class State(
        val calendar: PermissionState = PermissionState.NOT_ASKED,
        val calendarImport: CalendarImport = CalendarImport.Idle,
        val notifications: PermissionState = PermissionState.NOT_ASKED,
        val alphaStats: Boolean = false,
        val showsAlphaStats: Boolean = false,
    ) {
        /** "캘린더 열기" waits until both system permissions are answered either way; "나중에 할게요" never waits. */
        val canOpenCalendar: Boolean
            get() = calendar != PermissionState.NOT_ASKED && notifications != PermissionState.NOT_ASKED
    }

    private val _state = MutableStateFlow(State(showsAlphaStats = channel == BuildChannel.ALPHA))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Reads what the OS says now: on the first show and whenever the person comes back from the settings. */
    fun refresh() {
        val before = _state.value.calendar
        _state.update { current ->
            val calendar =
                when {
                    permissions.calendarGranted() -> PermissionState.GRANTED
                    current.calendar == PermissionState.DENIED -> PermissionState.DENIED
                    else -> PermissionState.NOT_ASKED
                }
            current.copy(
                calendar = calendar,
                calendarImport = if (calendar == PermissionState.GRANTED) current.calendarImport else CalendarImport.Idle,
                notifications = notificationState(current.notifications),
                alphaStats =
                    current.showsAlphaStats &&
                        runCatching { core.alphaStatsEnabled() }.getOrDefault(false),
            )
        }
        // Also after the settings app: access given there imports the calendars, as a yes to the system's question does.
        if (before != PermissionState.GRANTED && _state.value.calendar == PermissionState.GRANTED) import()
    }

    fun calendarTapped(): PermissionAction =
        when (_state.value.calendar) {
            PermissionState.NOT_ASKED -> PermissionAction.Ask(CALENDAR)
            PermissionState.DENIED -> PermissionAction.OpenAppSettings
            PermissionState.GRANTED -> {
                // "다시 가져오기" after an import that did not go through.
                if (_state.value.calendarImport == CalendarImport.Failed) import()
                PermissionAction.None
            }
        }

    fun calendarAnswered(granted: Boolean) {
        val before = _state.value.calendar
        _state.update {
            it.copy(
                calendar = if (granted) PermissionState.GRANTED else PermissionState.DENIED,
                calendarImport = if (granted) it.calendarImport else CalendarImport.Idle,
            )
        }
        if (granted && before != PermissionState.GRANTED) import()
    }

    /** The card says "가져왔어요" only once the calendars are in the app's calendar (m1.md screen 3). */
    private fun import() {
        if (_state.value.calendarImport == CalendarImport.Working) return
        _state.update { it.copy(calendarImport = CalendarImport.Working) }
        scope.launch {
            val count = importCalendars()
            _state.update { it.copy(calendarImport = count?.let(CalendarImport::Done) ?: CalendarImport.Failed) }
        }
    }

    fun notificationsTapped(): PermissionAction =
        when (_state.value.notifications) {
            PermissionState.NOT_ASKED -> PermissionAction.Ask(NOTIFICATIONS)
            PermissionState.DENIED ->
                if (permissions.notificationsNeedAsking()) {
                    PermissionAction.OpenAppSettings
                } else {
                    PermissionAction.OpenNotificationSettings
                }
            PermissionState.GRANTED -> PermissionAction.None
        }

    fun notificationsAnswered(granted: Boolean) {
        _state.update { it.copy(notifications = if (granted) PermissionState.GRANTED else PermissionState.DENIED) }
    }

    /** Off by default and only on a tap (protocol §14). Turning it off again lives under 나. */
    fun alphaStatsTapped() {
        val current = _state.value
        if (!current.showsAlphaStats || current.alphaStats) return
        try {
            core.setAlphaStats(true)
            _state.update { it.copy(alphaStats = true) }
        } catch (e: CoreException) {
            notify(AccountNotice.SETTING_FAILED)
        }
    }

    private fun notificationState(previous: PermissionState): PermissionState =
        when {
            permissions.notificationsEnabled() -> PermissionState.GRANTED
            !permissions.notificationsNeedAsking() -> PermissionState.DENIED
            previous == PermissionState.DENIED -> PermissionState.DENIED
            else -> PermissionState.NOT_ASKED
        }

    companion object {
        const val CALENDAR = "android.permission.READ_CALENDAR"
        const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}

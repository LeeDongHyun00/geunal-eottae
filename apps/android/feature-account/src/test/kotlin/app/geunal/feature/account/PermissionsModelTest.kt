package app.geunal.feature.account

import app.geunal.core.CoreException
import app.geunal.feature.account.onboarding.PermissionAction
import app.geunal.feature.account.onboarding.PermissionState
import app.geunal.feature.account.onboarding.PermissionsModel
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsModelTest {
    private val core = AccountFakeCore()
    private val permissions = FakePermissions()
    private val notices = mutableListOf<AccountNotice>()

    private fun model(channel: BuildChannel = BuildChannel.ALPHA) =
        PermissionsModel(core, permissions, channel, notify = { notices += it }).apply { refresh() }

    @Test
    fun countsTheDeviceCalendarsOnceAllowed() {
        val model = model()
        assertEquals(PermissionAction.Ask(PermissionsModel.CALENDAR), model.calendarTapped())

        model.calendarAnswered(granted = true)

        assertEquals(PermissionState.GRANTED, model.state.value.calendar)
        assertEquals(3, model.state.value.calendarCount)
    }

    @Test
    fun opensTheCalendarOnlyOnceBothAreAnswered() {
        val model = model()
        assertFalse(model.state.value.canOpenCalendar)

        model.calendarAnswered(granted = true)
        assertFalse(model.state.value.canOpenCalendar)
        model.notificationsAnswered(granted = false)

        assertTrue(model.state.value.canOpenCalendar)
    }

    @Test
    fun sendsARefusalToTheSettings() {
        val model = model()
        model.calendarAnswered(granted = false)
        model.notificationsAnswered(granted = false)

        assertEquals(PermissionAction.OpenAppSettings, model.calendarTapped())
        assertEquals(PermissionAction.OpenAppSettings, model.notificationsTapped())
    }

    @Test
    fun opensTheNotificationSettingsWhereTheSystemNeverAsks() {
        // Before Android 13 notifications are on unless turned off in the settings, and there is nothing to ask.
        permissions.notificationsNeedAsking = false
        val model = model()

        assertEquals(PermissionState.DENIED, model.state.value.notifications)
        assertEquals(PermissionAction.OpenNotificationSettings, model.notificationsTapped())
    }

    @Test
    fun showsWhatThePersonTurnedOnInTheSettings() {
        val model = model()
        model.calendarAnswered(granted = false)

        permissions.calendar = true
        permissions.notifications = true
        model.refresh()

        assertEquals(PermissionState.GRANTED, model.state.value.calendar)
        assertEquals(PermissionState.GRANTED, model.state.value.notifications)
        assertEquals(PermissionAction.None, model.calendarTapped())
    }

    @Test
    fun turnsTheStatisticsOnOnlyWhenAsked() {
        val model = model()
        assertFalse(model.state.value.alphaStats)
        assertFalse(core.statsOn)

        model.alphaStatsTapped()

        assertTrue(core.statsOn)
        assertTrue(model.state.value.alphaStats)
    }

    @Test
    fun hasNoStatisticsCardInAPublicBuild() {
        val model = model(BuildChannel.PUBLIC)

        model.alphaStatsTapped()

        assertFalse(model.state.value.showsAlphaStats)
        assertFalse(core.statsOn)
    }

    @Test
    fun saysWhenTheStatisticsCouldNotBeTurnedOn() {
        core.alphaStatsError = CoreException.NotImplemented("set_alpha_stats")
        val model = model()

        model.alphaStatsTapped()

        assertFalse(model.state.value.alphaStats)
        assertEquals(listOf(AccountNotice.SETTING_FAILED), notices)
    }
}

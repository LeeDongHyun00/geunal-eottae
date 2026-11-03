package app.geunal.feature.calendar

import app.geunal.core.CoreException
import app.geunal.feature.calendar.alerts.AlertSettingsModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSettingsModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private var systemOn = true

    private fun TestScope.model() = AlertSettingsModel(core, scope = this, access = { systemOn }, io = dispatcher)

    @Test
    fun writesEachChangeAtOnce() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.setEventAlert(30)
            model.setAllDayAlert(null)
            model.setLeaveSuggestions(false)
            advanceUntilIdle()

            assertEquals("setNotificationSettings 30 null true true false", core.calls.last())
            assertEquals(
                30,
                model.state.value.settings
                    ?.eventAlert,
            )
        }

    @Test
    fun storesTheLastOfQuickChangesAndGoesBackToWhatTheCoreHoldsWhenRefused() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            val loaded = model.state.value.settings

            // Two changes before the first write ran: what is stored is what the screen shows.
            model.setEventAlert(30)
            model.setNextUpBar(false)
            advanceUntilIdle()
            assertEquals(model.state.value.settings, core.settings)
            assertEquals(30, core.settings.eventAlert)
            assertFalse(core.settings.nextUpBar)
            val stored = core.settings

            // Both refused: the screen goes back to what the core still holds, not to a half-way choice.
            core.writeError = CoreException.Store("busy")
            model.setEventAlert(60)
            model.setBirthdayAlerts(false)
            advanceUntilIdle()
            assertEquals(stored, model.state.value.settings)
            assertTrue(model.state.value.failed)
            assertTrue(loaded != stored)
        }

    @Test
    fun putsAChangeBackWhenTheCoreRefuses() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            core.writeError = CoreException.Store("busy")

            model.setBirthdayAlerts(false)
            assertFalse(
                "the switch moves at once",
                model.state.value.settings!!
                    .birthdayAlerts,
            )
            advanceUntilIdle()

            assertTrue(
                model.state.value.settings!!
                    .birthdayAlerts,
            )
            assertTrue(model.state.value.failed)
        }

    @Test
    fun followsTheSystemSwitchWhenBackInFront() =
        runTest(dispatcher) {
            systemOn = false
            val model = model()
            assertFalse(model.state.value.systemEnabled)

            systemOn = true
            model.resumed()

            assertTrue(model.state.value.systemEnabled)
        }
}

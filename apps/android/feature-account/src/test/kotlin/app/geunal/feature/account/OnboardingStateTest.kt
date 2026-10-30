package app.geunal.feature.account

import app.geunal.feature.account.support.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class OnboardingStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun remembersAFinishedOnboardingAcrossLaunches() {
        val dir = folder.newFolder("onboarding")
        OnboardingState(dir).isFinished = true

        assertTrue(OnboardingState(dir).isFinished)
    }

    @Test
    fun keepsAnOfflineGuestsTickTimeUntilCleared() {
        val dir = folder.newFolder("onboarding")
        val ticked = Instant.parse("2026-10-26T01:02:03Z")
        OnboardingState(dir).pendingGuestAgeConfirmedAt = ticked

        val state = OnboardingState(dir)
        assertEquals(ticked, state.pendingGuestAgeConfirmedAt)
        state.pendingGuestAgeConfirmedAt = null
        assertNull(OnboardingState(dir).pendingGuestAgeConfirmedAt)
    }

    @Test
    fun startsOverWithoutItsFiles() {
        // A new phone gets neither the store nor this directory, so it starts at the welcome screen.
        val state = OnboardingState(File(folder.root, "missing"))

        assertFalse(state.isFinished)
        assertNull(state.pendingGuestAgeConfirmedAt)
    }

    @Test
    fun readsAGarbledTimeAsNone() {
        val dir = folder.newFolder("onboarding")
        File(dir, "pending-guest-age-confirmed-at").writeText("not a time")

        assertNull(OnboardingState(dir).pendingGuestAgeConfirmedAt)
    }

    @Test
    fun resetsEverything() {
        val dir = folder.newFolder("onboarding")
        val state = OnboardingState(dir)
        state.isFinished = true
        state.pendingGuestAgeConfirmedAt = Instant.EPOCH

        state.reset()

        assertFalse(state.isFinished)
        assertNull(state.pendingGuestAgeConfirmedAt)
    }
}

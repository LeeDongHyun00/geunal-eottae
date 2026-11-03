package app.geunal.feature.account

import app.geunal.core.CoreException
import app.geunal.core.Provider
import app.geunal.feature.account.onboarding.SignInModel
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.support.StepTiming
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakeProviders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StepTimingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val lines = mutableListOf<String>()
    private var clock = 0L

    private fun timing() = StepTiming(log = { lines += it }, wallMs = { clock }, cpuMs = { clock / 2 }, nativeHeapKb = { 100 })

    @Test
    fun logsTheStepWithItsTimesAndOutcome() =
        runTest {
            val result =
                timing().measure("signIn.kakao", "core") {
                    clock += 40
                    7
                }

            assertEquals(7, result)
            assertEquals(listOf("signIn.kakao/core ok wall=40ms cpu=20ms nativeHeap=100KB→100KB"), lines)
        }

    @Test
    fun aFailingStepIsLoggedByKindOnlyAndStillThrows() =
        runTest {
            try {
                timing().measure("signIn.kakao", "core") { throw CoreException.Network("secret detail") }
                fail("the error must reach the caller")
            } catch (e: CoreException.Network) {
                // Expected.
            }

            assertEquals(1, lines.size)
            assertTrue(lines[0].startsWith("signIn.kakao/core Network "))
            assertFalse("the message stays out of the log", lines[0].contains("secret"))
        }

    @Test
    fun offMeasuresNothing() =
        runTest {
            assertEquals(3, StepTiming.OFF.measure("f", "s") { 3 })
        }

    @Test
    fun aKakaoSignInLogsEachStepWithoutTheToken() =
        runTest {
            val model =
                SignInModel(
                    AccountFakeCore(),
                    FakeProviders(),
                    OnboardingState(folder.newFolder()),
                    notify = {},
                    timing = timing(),
                )
            model.setAgeConfirmed(true)

            model.signIn(Provider.KAKAO, isOnline = true)

            assertEquals(
                listOf("nonce", "provider", "core", "total"),
                lines.map { it.substringBefore(' ').substringAfter('/') },
            )
            assertTrue(lines.none { "token" in it || "하린" in it })
        }
}

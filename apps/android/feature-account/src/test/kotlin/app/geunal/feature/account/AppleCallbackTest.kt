package app.geunal.feature.account

import app.geunal.feature.account.providers.AppleCallback
import app.geunal.feature.account.providers.AppleCallbacks
import app.geunal.feature.account.providers.ProviderCredential
import app.geunal.feature.account.providers.ProviderSignInException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleCallbackTest {
    @After
    fun tearDown() {
        AppleCallbacks.clear("s1")
    }

    @Test
    fun readsTheFragmentTheServerSends() {
        val callback =
            AppleCallback.parse(
                "code=c%2B1&id_token=eyJ.a.b&state=s1&user=%7B%22name%22%3A%7B%22firstName%22%3A%22%ED%95%98%EB%A6%B0%22%7D%7D",
            )

        assertEquals("c+1", callback.code)
        assertEquals("eyJ.a.b", callback.idToken)
        assertEquals("s1", callback.state)
        assertEquals("""{"name":{"firstName":"하린"}}""", callback.user)
        assertNull(callback.error)
    }

    @Test
    fun handsTheTokenToTheSignInThatAskedForIt() =
        runTest {
            val pending = AppleCallbacks.expect("s1")

            AppleCallbacks.deliver("code=c1&id_token=t1&state=s1")

            assertEquals(ProviderCredential(idToken = "t1", authorizationCode = "c1"), pending.await())
        }

    @Test
    fun dropsAResultOfAnotherAttempt() {
        val pending = AppleCallbacks.expect("s1")

        AppleCallbacks.deliver("code=c1&id_token=t1&state=other")

        assertFalse(pending.isCompleted)
    }

    @Test
    fun treatsAClosedTabAsCancelled() =
        runTest {
            val pending = AppleCallbacks.expect("s1")
            AppleCallbacks.launched()

            AppleCallbacks.onHostResumed()

            assertTrue(runCatching { pending.await() }.exceptionOrNull() is ProviderSignInException.Cancelled)
        }

    @Test
    fun putsAKoreanNameFamilyNameFirst() {
        assertEquals("이하린", AppleCallbacks.displayName("""{"name":{"firstName":"하린","lastName":"이"}}"""))
        assertEquals("Harin Lee", AppleCallbacks.displayName("""{"name":{"firstName":"Harin","lastName":"Lee"}}"""))
        assertNull(AppleCallbacks.displayName("""{"email":"x"}"""))
        assertNull(AppleCallbacks.displayName("not json"))
    }
}

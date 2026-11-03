package app.geunal.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class MissingKeyTest {
    @Test
    fun keystore2SaysTheKeyIsGone() {
        assertEquals(MissingKey.LOST, missingKey(sdk = 31, reading = true, sealedValues = true))
        // Every sealed value is lost already, so a write starts over; the core deletes or rewrites the rest.
        assertEquals(MissingKey.MAKE, missingKey(sdk = 36, reading = false, sealedValues = true))
    }

    @Test
    fun theOlderKeystoreNeverReplacesAKeyThatSealedSomething() {
        // API 26-30 also return no key when the keystore daemon does not answer, so a value cannot be called lost and
        // a new key would replace the real one for good.
        assertEquals(MissingKey.UNAVAILABLE, missingKey(sdk = 30, reading = true, sealedValues = true))
        assertEquals(MissingKey.UNAVAILABLE, missingKey(sdk = 26, reading = false, sealedValues = true))
    }

    @Test
    fun theFirstWriteMakesTheKeyOnEveryLevel() {
        assertEquals(MissingKey.MAKE, missingKey(sdk = 26, reading = false, sealedValues = false))
        assertEquals(MissingKey.MAKE, missingKey(sdk = 31, reading = false, sealedValues = false))
    }
}

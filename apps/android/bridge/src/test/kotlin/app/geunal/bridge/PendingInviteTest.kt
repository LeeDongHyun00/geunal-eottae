package app.geunal.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingInviteTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theCodeOutlivesTheProcessUntilCleared() {
        val dir = folder.newFolder("invite")
        PendingInvite(dir).offer("ABCDEFGH23")
        val reopened = PendingInvite(dir)
        assertEquals("ABCDEFGH23", reopened.code.value)
        reopened.offer("ZYXWVUTS76")
        assertEquals("ZYXWVUTS76", reopened.code.value)
        reopened.clear()
        assertNull(PendingInvite(dir).code.value)
    }

    @Test
    fun theReferrerIsReadOncePerInstall() {
        val dir = folder.newFolder("invite")
        assertFalse(PendingInvite(dir).referrerRead)
        PendingInvite(dir).markReferrerRead()
        assertTrue(PendingInvite(dir).referrerRead)
    }
}

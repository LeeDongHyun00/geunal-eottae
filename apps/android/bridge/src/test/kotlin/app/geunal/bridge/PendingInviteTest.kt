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
        PendingInvite(dir).offer("GN7KQ4XZM2")
        val reopened = PendingInvite(dir)
        assertEquals("GN7KQ4XZM2", reopened.invite.value)
        reopened.offer("GN7KQ4XZM3")
        assertEquals("GN7KQ4XZM3", reopened.invite.value)
        reopened.clear()
        assertNull(PendingInvite(dir).invite.value)
    }

    @Test
    fun theReferrerIsReadOncePerInstall() {
        val dir = folder.newFolder("invite")
        assertFalse(PendingInvite(dir).referrerRead)
        PendingInvite(dir).markReferrerRead()
        assertTrue(PendingInvite(dir).referrerRead)
    }
}

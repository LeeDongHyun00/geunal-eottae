package app.geunal.feature.account

import app.geunal.feature.account.security.SensitiveClipboard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveClipboardTest {
    @Test
    fun clearsWhenTheClipboardCannotBeReadFromTheBackground() {
        // Android 10 and later hide the clipboard from an app that is not in front; the code must still go.
        assertTrue(SensitiveClipboard.mayClear(readable = false, label = null))
    }

    @Test
    fun clearsOurClipButLeavesWhatWasCopiedSince() {
        assertTrue(SensitiveClipboard.mayClear(readable = true, label = "geunal"))
        assertFalse(SensitiveClipboard.mayClear(readable = true, label = "메모"))
        assertFalse(SensitiveClipboard.mayClear(readable = true, label = null))
    }
}

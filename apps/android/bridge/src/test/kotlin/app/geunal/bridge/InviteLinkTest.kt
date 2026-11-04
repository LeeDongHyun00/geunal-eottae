package app.geunal.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val host = "geunaleottae.com"

    @Test
    fun readsTheCodeFromAnInviteLink() {
        assertEquals("ABCDEFGH23", InviteLink.fromLink("https://geunaleottae.com/i/ABCDEFGH23", host))
        assertEquals("ABCDEFGH23", InviteLink.fromLink("https://GeunalEottae.com/i/ABCDEFGH23/", host))
        // The fragment carries the group's name and color for the card, not the code.
        assertEquals("ABCDEFGH23", InviteLink.fromLink("https://geunaleottae.com/i/ABCDEFGH23#%EC%A3%BC%EB%A7%90", host))
    }

    @Test
    fun ignoresLinksThatAreNotInvitesOnTheInviteHost() {
        assertNull(InviteLink.fromLink("http://geunaleottae.com/i/ABCDEFGH23", host))
        assertNull(InviteLink.fromLink("https://evil.example/i/ABCDEFGH23", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com.evil.example/i/ABCDEFGH23", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com:8443/i/ABCDEFGH23", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/auth/apple", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/ABCDEFGH23/more", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/short", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/ABCDEFGH23", ""))
        assertNull(InviteLink.fromLink("not a link", host))
    }

    @Test
    fun readsTheCodeFromThePlayReferrer() {
        assertEquals("ABCDEFGH23", InviteLink.fromReferrer("invite=ABCDEFGH23"))
        assertEquals("ABCDEFGH23", InviteLink.fromReferrer("utm_source=kakao&invite=ABCDEFGH23"))
        assertNull(InviteLink.fromReferrer("utm_source=google-play&utm_medium=organic"))
        assertNull(InviteLink.fromReferrer("invite=ABC%2FDEF%2F123"))
        assertNull(InviteLink.fromReferrer(""))
    }
}

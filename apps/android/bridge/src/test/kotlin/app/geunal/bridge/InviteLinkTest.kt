package app.geunal.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val host = "geunaleottae.com"

    @Test
    fun keepsAnInviteLinkWholeForTheCore() {
        val plain = "https://geunaleottae.com/i/GN7KQ-4XZM2"
        assertEquals(plain, InviteLink.fromLink(plain, host))
        // The fragment carries the group's name and color and who invited; parse_invite reads them.
        val full = "https://geunaleottae.com/i/GN7KQ4XZM2#n=%EC%A3%BC%EB%A7%90&c=mint&i=%ED%95%98%EB%A6%B0"
        assertEquals(full, InviteLink.fromLink(full, host))
        assertEquals("https://GeunalEottae.com/i/gn7kq4xzm2/", InviteLink.fromLink("https://GeunalEottae.com/i/gn7kq4xzm2/", host))
    }

    @Test
    fun ignoresLinksThatAreNotInvitesOnTheInviteHost() {
        assertNull(InviteLink.fromLink("http://geunaleottae.com/i/GN7KQ4XZM2", host))
        assertNull(InviteLink.fromLink("https://evil.example/i/GN7KQ4XZM2", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com.evil.example/i/GN7KQ4XZM2", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com:8443/i/GN7KQ4XZM2", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/auth/apple", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/GN7KQ4XZM2/more", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/short", host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/GN7KQ4XZM2#" + "n".repeat(2000), host))
        assertNull(InviteLink.fromLink("https://geunaleottae.com/i/GN7KQ4XZM2", ""))
        assertNull(InviteLink.fromLink("not a link", host))
    }

    @Test
    fun readsTheCodeFromThePlayReferrer() {
        assertEquals("GN7KQ4XZM2", InviteLink.fromReferrer("invite=GN7KQ4XZM2"))
        assertEquals("GN7KQ-4XZM2", InviteLink.fromReferrer("utm_source=kakao&invite=GN7KQ-4XZM2"))
        assertNull(InviteLink.fromReferrer("utm_source=google-play&utm_medium=organic"))
        assertNull(InviteLink.fromReferrer("invite=ABC%2FDEF%2F123"))
        assertNull(InviteLink.fromReferrer(""))
    }
}

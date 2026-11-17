package app.geunal.api.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.Base64
import java.util.UUID

/** The token a refresh mints is a function of the session it rotates from and the server's key (api-server.md §4). */
class RefreshTokensTests {
    private fun key(id: String): ECKey = ECKeyGenerator(Curve.P_256).keyID(id).generate()

    private fun tokens(vararg keys: ECKey) =
        RefreshTokens(JwtKeys(AuthProperties(jwt = AuthProperties.Jwt(JWKSet(keys.toList()).toString(false))), MockEnvironment()))

    private val parent = UUID.randomUUID()
    private val hash = AuthService.sha256("spent")

    @Test
    fun `the same session and key give the same token, as long as a random one`() {
        val old = key("old")
        val token = tokens(old).rotated(parent, hash)

        // A second process with the same key, as after a restart or on another node.
        assertThat(tokens(old).rotated(parent, hash)).isEqualTo(token)
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32)
        assertThat(token).hasSameSizeAs(tokens(old).random())
    }

    @Test
    fun `another session, another token hash or another key gives another token`() {
        val old = key("old")
        val token = tokens(old).rotated(parent, hash)

        assertThat(tokens(old).rotated(UUID.randomUUID(), hash)).isNotEqualTo(token)
        assertThat(tokens(old).rotated(parent, AuthService.sha256("other"))).isNotEqualTo(token)
        assertThat(tokens(key("new")).rotated(parent, hash)).isNotEqualTo(token)
    }

    @Test
    fun `a rotated signing key still finds the token while the old private key stays in the set`() {
        val old = key("old")
        val new = key("new")
        val token = tokens(old).rotated(parent, hash)

        val rotated = tokens(new, old)
        assertThat(rotated.rotated(parent, hash)).isNotEqualTo(token)
        assertThat(rotated.rotatedCandidates(parent, hash)).containsExactly(rotated.rotated(parent, hash), token)
        // Kept for verifying access tokens only, or dropped: the token cannot be computed again.
        assertThat(tokens(new, old.toPublicJWK()).rotatedCandidates(parent, hash)).doesNotContain(token)
        assertThat(tokens(new).rotatedCandidates(parent, hash)).doesNotContain(token)
    }
}

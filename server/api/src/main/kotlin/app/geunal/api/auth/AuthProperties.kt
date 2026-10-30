package app.geunal.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `geunal.auth.*` in application.yml; the environment overrides them as `GEUNAL_AUTH_*`. */
@ConfigurationProperties("geunal.auth")
data class AuthProperties(
    val stub: Boolean = false,
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(30),
    val nonceTtl: Duration = Duration.ofMinutes(5),
    val ticketTtl: Duration = Duration.ofMinutes(30),
    /** "새로 시작" without the recovery code needs a provider sign-in this recent, or a fresh provider proof (protocol §10). */
    val providerWindow: Duration = Duration.ofMinutes(30),
    val jwt: Jwt = Jwt(),
    val kakao: Kakao = Kakao(),
    val apple: Apple = Apple(),
) {
    data class Jwt(
        val keys: String = "",
    )

    /**
     * Kakao Login over OpenID Connect. Sign-in is offered only with both keys: the app key is the ID token's `aud`, and
     * the admin key unlinks a deleted account from Kakao (protocol §13), so an account is never created that the server
     * could not take apart again.
     */
    data class Kakao(
        val appKey: String = "",
        val adminKey: String = "",
        val issuer: String = "https://kauth.kakao.com",
        val jwksUri: String = "https://kauth.kakao.com/.well-known/jwks.json",
        val unlinkUri: String = "https://kapi.kakao.com/v1/user/unlink",
    ) {
        val enabled: Boolean get() = appKey.isNotBlank() && adminKey.isNotBlank()
    }

    /**
     * Sign in with Apple. The identity token's `aud` is the iOS bundle ID or, for Android's web sign-in, the services
     * ID. Sign-in is offered only with the client secret material (team ID, key ID, the .p8 key) and the callback
     * address, because account deletion exchanges a fresh authorization code and revokes the tokens (protocol §13),
     * and an Android code exchanges only with the redirect URI it was issued for.
     */
    data class Apple(
        val teamId: String = "",
        val keyId: String = "",
        val privateKey: String = "",
        val redirectUri: String = "",
        val bundleId: String = "app.geunal.ios",
        val serviceId: String = "app.geunal.signin",
        val appLink: String = "/auth/apple",
        val issuer: String = "https://appleid.apple.com",
        val jwksUri: String = "https://appleid.apple.com/auth/keys",
        val tokenUri: String = "https://appleid.apple.com/auth/token",
        val revokeUri: String = "https://appleid.apple.com/auth/revoke",
    ) {
        val enabled: Boolean
            get() = teamId.isNotBlank() && keyId.isNotBlank() && privateKey.isNotBlank() && redirectUri.isNotBlank()
    }
}

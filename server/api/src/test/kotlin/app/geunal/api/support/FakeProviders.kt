package app.geunal.api.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Kakao and Apple as far as the API reaches them: each publishes a JWKS on a local HTTP server, and tests mint ID
 * tokens with the matching private keys. Started once and shared like [TestStack].
 */
object FakeProviders {
    const val KAKAO_ISSUER = "https://kauth.kakao.com"
    const val KAKAO_APP_KEY = "test-kakao-native-app-key"
    const val APPLE_ISSUER = "https://appleid.apple.com"
    const val APPLE_BUNDLE_ID = "app.geunal.ios"
    const val APPLE_SERVICE_ID = "app.geunal.signin"

    val kakaoKey: RSAKey = RSAKeyGenerator(2048).keyID("kakao-test").generate()
    val appleKey: RSAKey = RSAKeyGenerator(2048).keyID("apple-test").generate()

    /** Signs like a provider but is published nowhere. */
    val strangerKey: RSAKey = RSAKeyGenerator(2048).keyID("kakao-test").generate()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/kakao/jwks") { it.json(JWKSet(kakaoKey.toPublicJWK()).toString()) }
            createContext("/apple/keys") { it.json(JWKSet(appleKey.toPublicJWK()).toString()) }
            start()
        }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    /** A Sign in with Apple key in the .p8 file's PEM form, with the line breaks an environment value would carry. */
    val applePrivateKeyPem: String =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
            .private
            .encoded
            .let {
                "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it) +
                    "\n-----END PRIVATE KEY-----"
            }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("geunal.auth.kakao.app-key") { KAKAO_APP_KEY }
        registry.add("geunal.auth.kakao.admin-key") { "test-kakao-admin-key" }
        registry.add("geunal.auth.kakao.jwks-uri") { "$base/kakao/jwks" }
        registry.add("geunal.auth.apple.team-id") { "TEAM123456" }
        registry.add("geunal.auth.apple.key-id") { "KEY1234567" }
        registry.add("geunal.auth.apple.private-key") { applePrivateKeyPem }
        registry.add("geunal.auth.apple.redirect-uri") { "https://api.geunal.test/auth/apple/callback" }
        registry.add("geunal.auth.apple.jwks-uri") { "$base/apple/keys" }
    }

    fun kakaoToken(
        sub: String,
        nonce: String?,
        audience: String = KAKAO_APP_KEY,
        issuer: String = KAKAO_ISSUER,
        expiresIn: Duration = Duration.ofHours(1),
        key: RSAKey = kakaoKey,
    ): String = token(key, issuer, audience, sub, nonce, expiresIn)

    fun appleToken(
        sub: String,
        nonce: String?,
        audience: String = APPLE_BUNDLE_ID,
        issuer: String = APPLE_ISSUER,
        expiresIn: Duration = Duration.ofMinutes(10),
        key: RSAKey = appleKey,
    ): String = token(key, issuer, audience, sub, nonce, expiresIn)

    private fun token(
        key: RSAKey,
        issuer: String,
        audience: String,
        sub: String,
        nonce: String?,
        expiresIn: Duration,
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(expiresIn)))
                .apply { if (nonce != null) claim("nonce", nonce) }
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(key.keyID)
                .type(JOSEObjectType.JWT)
                .build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
    }

    private fun HttpExchange.json(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

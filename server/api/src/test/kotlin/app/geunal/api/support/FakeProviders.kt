package app.geunal.api.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
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
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Kakao and Apple as far as the API reaches them: each publishes a JWKS on a local HTTP server, and tests mint ID
 * tokens with the matching private keys. Apple's token and revoke endpoints and Kakao's unlink answer as configured and
 * record what they were sent. Started once and shared like [TestStack].
 */
object FakeProviders {
    const val KAKAO_ISSUER = "https://kauth.kakao.com"
    const val KAKAO_APP_KEY = "test-kakao-native-app-key"
    const val KAKAO_ADMIN_KEY = "test-kakao-admin-key"
    const val APPLE_ISSUER = "https://appleid.apple.com"
    const val APPLE_BUNDLE_ID = "app.geunal.ios"
    const val APPLE_SERVICE_ID = "app.geunal.signin"
    const val APPLE_TEAM_ID = "TEAM123456"
    const val APPLE_KEY_ID = "KEY1234567"
    const val APPLE_REDIRECT_URI = "https://api.geunal.test/auth/apple/callback"

    val kakaoKey: RSAKey = RSAKeyGenerator(2048).keyID("kakao-test").generate()
    val appleKey: RSAKey = RSAKeyGenerator(2048).keyID("apple-test").generate()

    /** Signs like a provider but is published nowhere. */
    val strangerKey: RSAKey = RSAKeyGenerator(2048).keyID("kakao-test").generate()

    /** A request one of the fake endpoints received: its path, form fields and `Authorization` header. */
    data class Call(
        val path: String,
        val form: Map<String, String>,
        val authorization: String?,
    )

    val calls = ConcurrentLinkedQueue<Call>()

    @Volatile var kakaoUnlinkStatus = 200

    @Volatile var appleRevokeStatus = 200

    /** Authorization codes the token endpoint exchanges, each once: code → (sub, the client it was issued to). */
    private val appleCodes = ConcurrentHashMap<String, Pair<String, String>>()

    /** The Sign in with Apple key (.p8); the token endpoint checks client secrets with its public half. */
    private val appleClientKey: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/kakao/jwks") { it.json(JWKSet(kakaoKey.toPublicJWK()).toString()) }
            createContext("/apple/keys") { it.json(JWKSet(appleKey.toPublicJWK()).toString()) }
            createContext("/apple/token") { it.appleToken() }
            createContext("/apple/revoke") {
                calls += Call("/apple/revoke", it.form(), null)
                it.json("", appleRevokeStatus)
            }
            createContext("/kakao/unlink") {
                val form = it.form()
                calls += Call("/kakao/unlink", form, it.requestHeaders.getFirst("Authorization"))
                it.json("{\"id\":${form["target_id"]?.toLongOrNull() ?: 0}}", kakaoUnlinkStatus)
            }
            start()
        }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    /** The Sign in with Apple key in the .p8 file's PEM form. */
    val applePrivateKeyPem: String =
        appleClientKey.private.encoded.let {
            "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it) +
                "\n-----END PRIVATE KEY-----"
        }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("geunal.auth.kakao.app-key") { KAKAO_APP_KEY }
        registry.add("geunal.auth.kakao.admin-key") { KAKAO_ADMIN_KEY }
        registry.add("geunal.auth.kakao.jwks-uri") { "$base/kakao/jwks" }
        registry.add("geunal.auth.kakao.unlink-uri") { "$base/kakao/unlink" }
        registry.add("geunal.auth.apple.team-id") { APPLE_TEAM_ID }
        registry.add("geunal.auth.apple.key-id") { APPLE_KEY_ID }
        registry.add("geunal.auth.apple.private-key") { applePrivateKeyPem }
        registry.add("geunal.auth.apple.redirect-uri") { APPLE_REDIRECT_URI }
        registry.add("geunal.auth.apple.jwks-uri") { "$base/apple/keys" }
        registry.add("geunal.auth.apple.token-uri") { "$base/apple/token" }
        registry.add("geunal.auth.apple.revoke-uri") { "$base/apple/revoke" }
    }

    /** An authorization code Apple would hand a device of [platform] signed in as [sub]. */
    fun appleCode(
        sub: String,
        platform: String = "ios",
    ): String {
        val code = "c" + UUID.randomUUID().toString().replace("-", "")
        appleCodes[code] = sub to (if (platform == "android") APPLE_SERVICE_ID else APPLE_BUNDLE_ID)
        return code
    }

    fun callsTo(path: String): List<Call> = calls.filter { it.path == path }

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

    /**
     * Apple's `/auth/token` for an authorization code: the client secret must be an ES256 JWT by our key for the client
     * the code was issued to, and an Android code also needs the redirect URI. The identity token names the code's `sub`.
     */
    private fun HttpExchange.appleToken() {
        val form = form()
        calls += Call("/apple/token", form, null)
        val (sub, clientId) = appleCodes.remove(form["code"]) ?: return json("{\"error\":\"invalid_grant\"}", 400)
        val redirectOk = clientId != APPLE_SERVICE_ID || form["redirect_uri"] == APPLE_REDIRECT_URI
        if (form["client_id"] != clientId || !redirectOk || !clientSecretValid(form["client_secret"], clientId)) {
            return json("{\"error\":\"invalid_client\"}", 400)
        }
        val idToken = appleToken(sub, null, audience = clientId)
        json(
            "{\"access_token\":\"a-$sub\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"r-$sub\",\"id_token\":\"$idToken\"}",
        )
    }

    private fun clientSecretValid(
        secret: String?,
        clientId: String,
    ): Boolean {
        val jwt = runCatching { SignedJWT.parse(secret) }.getOrNull() ?: return false
        val claims = jwt.jwtClaimsSet
        return jwt.header.algorithm == JWSAlgorithm.ES256 &&
            jwt.header.keyID == APPLE_KEY_ID &&
            jwt.verify(ECDSAVerifier(appleClientKey.public as ECPublicKey)) &&
            claims.issuer == APPLE_TEAM_ID &&
            claims.subject == clientId &&
            claims.audience == listOf(APPLE_ISSUER) &&
            claims.expirationTime.after(Date())
    }

    private fun HttpExchange.form(): Map<String, String> =
        requestBody
            .use { String(it.readAllBytes()) }
            .split('&')
            .filter { it.contains('=') }
            .associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }

    private fun HttpExchange.json(
        body: String,
        status: Int = 200,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

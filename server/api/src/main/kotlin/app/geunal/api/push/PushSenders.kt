package app.geunal.api.push

import app.geunal.api.auth.ProviderTokens
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

enum class Delivery { SENT, DEAD, FAILED }

interface PushSender {
    val platform: String
    val enabled: Boolean

    fun send(
        target: PushTarget,
        payload: PushPayload,
    ): Delivery
}

private val http: HttpClient =
    HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

private fun post(
    uri: String,
    body: String,
    headers: Map<String, String>,
): HttpResponse<String>? =
    try {
        val request =
            HttpRequest
                .newBuilder(URI(uri))
                .timeout(Duration.ofSeconds(10))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (_: IOException) {
        null
    }

/**
 * APNs over HTTP/2 with token auth: an ES256 JWT from the .p8, reused for 50 minutes (Apple refuses one older than an
 * hour and throttles minting more than one every 20 minutes). Gone (410) and a bad device token are dead tokens.
 */
@Component
class ApnsSender(
    properties: PushProperties,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : PushSender {
    private val apns = properties.apns
    override val platform = "ios"
    override val enabled = apns.enabled
    private val key = if (enabled) ProviderTokens.applePrivateKey(apns.privateKey) else null

    @Volatile private var token: Pair<String, Instant>? = null

    private fun bearer(): String {
        val now = clock.instant()
        token?.let { (jwt, at) -> if (now.isBefore(at.plus(TOKEN_LIFETIME))) return jwt }
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apns.keyId).build(),
                JWTClaimsSet
                    .Builder()
                    .issuer(apns.teamId)
                    .issueTime(Date.from(now))
                    .build(),
            ).apply { sign(ECDSASigner(checkNotNull(key))) }.serialize()
        token = jwt to now
        return jwt
    }

    override fun send(
        target: PushTarget,
        payload: PushPayload,
    ): Delivery {
        val response =
            post(
                "${apns.endpoint}/3/device/${target.token}",
                mapper.writeValueAsString(payload.apns()),
                mapOf(
                    "authorization" to "bearer ${bearer()}",
                    "apns-topic" to apns.topic,
                    "apns-push-type" to "alert",
                    "apns-priority" to "10",
                ),
            ) ?: return Delivery.FAILED
        if (response.statusCode() == 200) return Delivery.SENT
        if (response.statusCode() == 403) token = null
        val reason = runCatching { mapper.readTree(response.body()).path("reason").stringValue() }.getOrNull()
        return if (response.statusCode() == 410 || reason in DEAD_REASONS) Delivery.DEAD else Delivery.FAILED
    }

    private companion object {
        val TOKEN_LIFETIME: Duration = Duration.ofMinutes(50)
        val DEAD_REASONS = setOf("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic")
    }
}

/**
 * FCM HTTP v1 with a service account: an RS256 assertion traded for an OAuth access token, kept until five minutes
 * before it expires. NOT_FOUND and UNREGISTERED are dead tokens.
 */
@Component
class FcmSender(
    properties: PushProperties,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : PushSender {
    private val fcm = properties.fcm
    override val platform = "android"
    override val enabled = fcm.enabled
    private val account = if (enabled) ServiceAccount.parse(fcm.serviceAccount) else null

    @Volatile private var token: Pair<String, Instant>? = null

    private fun bearer(): String? {
        val account = checkNotNull(account)
        val now = clock.instant()
        token?.let { (value, until) -> if (now.isBefore(until)) return value }
        val assertion =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID(account.keyId).build(),
                JWTClaimsSet
                    .Builder()
                    .issuer(account.clientEmail)
                    .audience(account.tokenUri)
                    .claim("scope", SCOPE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(3600)))
                    .build(),
            ).apply { sign(RSASSASigner(account.privateKey)) }.serialize()
        val form =
            "grant_type=${URLEncoder.encode(GRANT, Charsets.UTF_8)}&assertion=$assertion"
        val response =
            post(account.tokenUri, form, mapOf("Content-Type" to "application/x-www-form-urlencoded"))
                ?.takeIf { it.statusCode() == 200 } ?: return null
        val body = mapper.readTree(response.body())
        val value = body.path("access_token").stringValue() ?: return null
        val lifetime = body.path("expires_in").asLong(3600)
        token = value to now.plusSeconds((lifetime - 300).coerceAtLeast(60))
        return value
    }

    override fun send(
        target: PushTarget,
        payload: PushPayload,
    ): Delivery {
        val account = checkNotNull(account)
        val bearer = bearer() ?: return Delivery.FAILED
        val response =
            post(
                "${fcm.endpoint}/v1/projects/${account.projectId}/messages:send",
                mapper.writeValueAsString(payload.fcm(target.token)),
                mapOf("Authorization" to "Bearer $bearer", "Content-Type" to "application/json"),
            ) ?: return Delivery.FAILED
        if (response.statusCode() == 200) return Delivery.SENT
        if (response.statusCode() == 401) token = null
        val codes =
            runCatching {
                mapper
                    .readTree(response.body())
                    .path("error")
                    .path("details")
                    .values()
                    .mapNotNull { it.path("errorCode").stringValue() }
            }.getOrDefault(emptyList())
        return if (response.statusCode() == 404 || "UNREGISTERED" in codes) Delivery.DEAD else Delivery.FAILED
    }

    /**
     * The fields of a service account key file that FCM needs. The alpha server's secret store hands multi-line values
     * over with every line break written as the two characters `\n` (infra/alpha fetch-secrets.py), which also turns
     * the JSON's own line breaks into `\n` next to the escapes inside `private_key`. Turning them all back into line
     * breaks and reading control characters leniently gives the same fields as the file itself.
     */
    data class ServiceAccount(
        val projectId: String,
        val clientEmail: String,
        val keyId: String?,
        val tokenUri: String,
        val privateKey: RSAPrivateKey,
    ) {
        override fun toString(): String = "ServiceAccount(projectId=$projectId, clientEmail=$clientEmail)"

        companion object {
            private val lenient: JsonMapper =
                JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build()

            fun parse(text: String): ServiceAccount {
                val json: JsonNode =
                    try {
                        lenient.readTree(text.replace("\\n", "\n"))
                    } catch (e: RuntimeException) {
                        throw IllegalStateException("geunal.push.fcm.service-account is not a service account key file", e)
                    }

                fun field(name: String) =
                    json.path(name).stringValue()?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("geunal.push.fcm.service-account lacks $name")
                return ServiceAccount(
                    projectId = field("project_id"),
                    clientEmail = field("client_email"),
                    keyId = json.path("private_key_id").stringValue(),
                    tokenUri = json.path("token_uri").stringValue() ?: "https://oauth2.googleapis.com/token",
                    privateKey = rsaKey(field("private_key")),
                )
            }

            private fun rsaKey(pem: String): RSAPrivateKey {
                val body =
                    pem
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .filterNot { it.isWhitespace() }
                return try {
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))) as RSAPrivateKey
                } catch (e: Exception) {
                    throw IllegalStateException("geunal.push.fcm.service-account has no readable private_key", e)
                }
            }
        }
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
    }
}

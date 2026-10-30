package app.geunal.api.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Date

/** How a call that ends a provider link went: done, worth another try later, or refused for good. */
enum class Outcome { DONE, RETRY, GIVE_UP }

/** What a Sign in with Apple code exchanged for: the account it names, and the tokens to revoke on deletion. */
data class AppleTokens(
    val sub: String,
    val clientId: String,
    val token: String?,
    val tokenType: String,
) {
    override fun toString(): String = "AppleTokens(…)"
}

/** Form posts to the providers' endpoints with short timeouts. Nothing of a request or answer reaches a log. */
abstract class ProviderClient {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    protected fun post(
        uri: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String>? {
        val body = form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(URI(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: IOException) {
            null
        }
    }

    /** 2xx is done; 429, 5xx, an auth refusal (a key that may be fixed) and no answer are worth retrying. */
    protected fun outcome(status: Int?): Outcome =
        when {
            status == null -> Outcome.RETRY
            status in 200..299 -> Outcome.DONE
            status == 401 || status == 403 || status == 429 || status >= 500 -> Outcome.RETRY
            else -> Outcome.GIVE_UP
        }
}

/**
 * Apple's token endpoints for account deletion (protocol §13, Apple TN3194): a fresh authorization code is exchanged
 * with a client secret signed by the Sign in with Apple key, and the tokens it yields are revoked. Codes from iOS belong
 * to the bundle ID; Android's web sign-in codes belong to the services ID and its redirect URI.
 */
@Component
class AppleClient(
    private val properties: AuthProperties,
    private val providers: ProviderTokens,
    private val mapper: JsonMapper,
) : ProviderClient() {
    private val apple = properties.apple
    private val key = apple.takeIf { it.enabled }?.let { ProviderTokens.applePrivateKey(it.privateKey) }

    /** The account a code names, or null when Apple refuses it or the identity token does not verify. */
    fun exchange(
        code: String,
        platform: String,
    ): AppleTokens? {
        if (key == null || code.isEmpty() || code.length > MAX_CODE) return null
        val clientId = if (platform == "android") apple.serviceId else apple.bundleId
        val form =
            mutableMapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret(clientId),
                "code" to code,
                "grant_type" to "authorization_code",
            )
        if (platform == "android") form["redirect_uri"] = apple.redirectUri
        val response = post(apple.tokenUri, form)?.takeIf { it.statusCode() == 200 } ?: return null
        val body = runCatching { mapper.readTree(response.body()) }.getOrNull() ?: return null
        val sub = providers.appleSubject(body.path("id_token").asString(""), clientId) ?: return null
        val refresh = body.path("refresh_token").asString("").ifEmpty { null }
        val token = refresh ?: body.path("access_token").asString("").ifEmpty { null }
        return AppleTokens(sub, clientId, token, if (refresh != null) "refresh_token" else "access_token")
    }

    fun revoke(
        clientId: String,
        token: String,
        tokenType: String,
    ): Outcome {
        if (key == null) return Outcome.RETRY
        val form =
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret(clientId),
                "token" to token,
                "token_type_hint" to tokenType,
            )
        return outcome(post(apple.revokeUri, form)?.statusCode())
    }

    /** ES256, issued by the team, for one client, valid five minutes: Apple accepts up to six months, but nothing needs more. */
    private fun clientSecret(clientId: String): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(apple.teamId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .audience(apple.issuer)
                .subject(clientId)
                .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.keyId).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
    }

    private companion object {
        const val MAX_CODE = 1024
    }
}

/** Kakao's unlink with the app's admin key (protocol §13 "계정 삭제"): the deleted account leaves the Kakao app too. */
@Component
class KakaoClient(
    properties: AuthProperties,
) : ProviderClient() {
    private val kakao = properties.kakao

    fun unlink(sub: String): Outcome {
        if (!kakao.enabled) return Outcome.RETRY
        val response =
            post(
                kakao.unlinkUri,
                mapOf("target_id_type" to "user_id", "target_id" to sub),
                mapOf("Authorization" to "KakaoAK ${kakao.adminKey}"),
            )
        val outcome = outcome(response?.statusCode())
        if (outcome == Outcome.GIVE_UP) log.warn("Kakao refused an unlink with status {}", response?.statusCode())
        return outcome
    }

    private companion object {
        val log = LoggerFactory.getLogger(KakaoClient::class.java)
    }
}

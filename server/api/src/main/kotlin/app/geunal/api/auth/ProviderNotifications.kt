package app.geunal.api.auth

import app.geunal.api.common.ApiException
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/** Apple's server-to-server notification body: one signed JWT. */
data class AppleNotification(
    val payload: String,
)

/**
 * The providers telling us a user cut the link from their side (protocol §13 "제공자 쪽에서 끊길 때"): Kakao's unlink
 * webhook and Apple's consent-revoked and account-delete notifications. Each marks the link cut and keeps it, so the
 * account opens with its recovery code until the same provider account signs in again. A notification that does not
 * prove its sender changes nothing, or anyone could detach an account from its provider (threat model 3.6).
 */
@RestController
class ProviderNotifications(
    private val accounts: AccountRepository,
    private val providers: ProviderTokens,
    private val properties: AuthProperties,
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) {
    /**
     * Kakao's unlink webhook, GET or POST as the console sets it, with the primary admin key as `KakaoAK` (Kakao's
     * documented check). Kakao wants 200 within 3 seconds whatever the outcome; only a request without our admin key
     * gets 401.
     */
    @RequestMapping("/auth/kakao/unlinked", method = [RequestMethod.GET, RequestMethod.POST])
    fun kakaoUnlinked(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam("user_id", required = false) userId: String?,
    ) {
        if (!properties.kakao.enabled) throw ProviderTokens.disabled(Provider.KAKAO)
        val expected = "KakaoAK ${properties.kakao.adminKey}".toByteArray()
        if (authorization == null || !MessageDigest.isEqual(authorization.toByteArray(), expected)) throw unverified()
        if (userId.isNullOrBlank() || userId.length > MAX_SUB) return
        if (accounts.cut(Provider.KAKAO, userId)) log.info("kakao cut a link")
    }

    /**
     * Apple's server-to-server notifications. `consent-revoked` (the user stopped using Sign in with Apple with us)
     * and `account-delete` (the Apple account is gone) cut the link; the email events concern nothing we keep. A
     * notification counts once and only for a day after Apple issued it, so a captured one cannot cut a link that was
     * restored later.
     */
    @PostMapping("/auth/apple/notifications")
    fun appleNotification(
        @RequestBody body: AppleNotification,
    ) {
        if (!properties.apple.enabled) throw ProviderTokens.disabled(Provider.APPLE)
        val event = providers.appleEvent(body.payload) ?: throw unverified()
        val age = Duration.between(event.issuedAt, clock.instant())
        if (age > MAX_AGE || age < MAX_SKEW.negated()) throw unverified()
        val seen = "apple-notification:${event.id}"
        if (redis.hasKey(seen)) return
        if (event.type in CUTTING && accounts.cut(Provider.APPLE, event.sub)) log.info("apple cut a link: {}", event.type)
        // Marked only once the cut is done: a delivery that failed on the way stays retryable. Two copies racing
        // here both cut, which is the same as one.
        redis.opsForValue().set(seen, "1", MAX_AGE)
    }

    private fun unverified() = ApiException(HttpStatus.UNAUTHORIZED, "provider_token_invalid", "the sender could not be verified")

    private companion object {
        val CUTTING = setOf("consent-revoked", "account-delete")
        val MAX_AGE: Duration = Duration.ofDays(1)
        val MAX_SKEW: Duration = Duration.ofMinutes(5)
        const val MAX_SUB = 200
        val log = LoggerFactory.getLogger(ProviderNotifications::class.java)
    }
}

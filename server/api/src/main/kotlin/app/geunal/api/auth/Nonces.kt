package app.geunal.api.auth

import app.geunal.api.common.ApiException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * `GET /auth/nonce`: one-time values a device signs (sign-in, recovery and deletion proofs) or binds into a provider
 * token. Each lives five minutes and is spent by the first request that names it.
 */
@Component
class Nonces(
    private val redis: StringRedisTemplate,
    private val properties: AuthProperties,
) {
    private val random = SecureRandom()

    fun issue(): String {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        redis.opsForValue().set(key(nonce), "1", properties.nonceTtl)
        return nonce
    }

    /** Spends [nonce] and says whether it was live. Deleting the key is the atomic single use; a replay finds nothing. */
    fun spend(nonce: String?): Boolean =
        nonce != null && nonce.isNotEmpty() && nonce.length <= MAX_NONCE && redis.delete(key(nonce)) == true

    /** [spend], answering 400 `invalid_request` for a nonce that is not live: the shape of a sign-in's device proof. */
    fun consume(nonce: String) {
        if (!spend(nonce)) throw ApiException.invalid("nonce is unknown or was already used")
    }

    private fun key(nonce: String) = "nonce:$nonce"

    private companion object {
        const val MAX_NONCE = 128
    }
}

package app.geunal.api.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Refresh token values (api-server.md §4). A sign-in gets a random one. The token a refresh mints is instead a
 * function of the session it rotates from: `HMAC-SHA256(key, "geunal refresh v1" ‖ session id ‖ refresh hash)`. A
 * retry of a refresh whose answer was lost can then be handed the very token the first attempt minted, without the
 * server having stored it, so a spent token never starts a second line of tokens that reuse detection would not see.
 *
 * The HMAC key comes from the JWT signing key rather than a secret of its own to deploy and rotate. Whoever holds that
 * key can already mint access tokens for anyone; without it, a spent token and even a database dump (ids and hashes)
 * do not give the next token. The private scalar is not used raw: the key is its HMAC under a label of its own, so
 * nothing computed here is a value ES256 signing also computes.
 *
 * Every P-256 private key of the configured set gets a key, the signing one first. A JWT key rotation between a
 * refresh and its retry therefore still finds the token as long as the old key stays in the set as a private key.
 * Once the old private key is gone (or only its public half was kept, or the process restarted where the key is
 * per-process), the retry matches nothing and is answered as reuse: the device signs in again. Failing closed there
 * costs one sign-in for a retry that straddled a key change; failing open would be a fresh rotation, the fork this
 * design exists to prevent.
 */
@Component
class RefreshTokens(
    keys: JwtKeys,
) {
    private val random = SecureRandom()

    private val macKeys: List<SecretKeySpec> =
        (
            listOf(keys.signing) +
                keys.set.keys
                    .filterIsInstance<ECKey>()
                    .filter { it.isPrivate && it.curve == Curve.P_256 }
        ).distinctBy { it.keyID }
            .map { SecretKeySpec(hmac(SecretKeySpec(scalar(it), ALGORITHM), KEY_LABEL), ALGORITHM) }

    /** A token for a sign-in: nothing to derive it from, and nothing ever asks for it a second time. */
    fun random(): String = encode(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    /** The token the refresh of session [parentId], whose token hashes to [parentHash], mints. */
    fun rotated(
        parentId: UUID,
        parentHash: ByteArray,
    ): String = derive(macKeys.first(), parentId, parentHash)

    /** [rotated] under every key a recent refresh may have minted with, the current one first. */
    fun rotatedCandidates(
        parentId: UUID,
        parentHash: ByteArray,
    ): List<String> = macKeys.map { derive(it, parentId, parentHash) }

    // Both inputs have a fixed length (16 and 32 bytes), so the concatenation reads only one way.
    private fun derive(
        key: SecretKeySpec,
        parentId: UUID,
        parentHash: ByteArray,
    ): String {
        val id =
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(parentId.mostSignificantBits)
                .putLong(parentId.leastSignificantBits)
                .array()
        return encode(hmac(key, TOKEN_LABEL + id + parentHash))
    }

    private fun hmac(
        key: SecretKeySpec,
        message: ByteArray,
    ): ByteArray = Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(message)

    /** The private scalar as 32 big-endian bytes, whatever leading zeros the JWK's encoding kept or dropped. */
    private fun scalar(key: ECKey): ByteArray {
        val bytes = key.toECPrivateKey().s.toByteArray()
        val out = ByteArray(SCALAR_BYTES)
        val length = minOf(bytes.size, SCALAR_BYTES)
        bytes.copyInto(out, SCALAR_BYTES - length, bytes.size - length, bytes.size)
        return out
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val TOKEN_BYTES = 32
        const val UUID_BYTES = 16
        const val SCALAR_BYTES = 32
        val KEY_LABEL = "geunal refresh key v1".toByteArray(Charsets.US_ASCII)
        val TOKEN_LABEL = "geunal refresh v1".toByteArray(Charsets.US_ASCII)
    }
}

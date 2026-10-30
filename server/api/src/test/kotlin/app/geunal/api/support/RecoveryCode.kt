package app.geunal.api.support

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature

/**
 * What the core derives from a recovery code (protocol §10), as far as the server sees it: the recovery ID, the public
 * half of the recovery signing key, and the wrapped AMK. Tests sign with the private half as the core would.
 */
class RecoveryCode {
    val id: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKey: ByteArray = keyPair.public.encoded.copyOfRange(12, 44)
    val amkWrapped: ByteArray = ByteArray(72).also(SecureRandom()::nextBytes)

    /** The body of a first `PUT /me/recovery`; a later one adds a proof. */
    fun upload(): Map<String, Any> = mapOf("amk_wrapped" to amkWrapped, "recovery_id" to id, "recovery_public_key" to publicKey)

    /** Ed25519 over the ASCII of [label] followed by [nonce], as every recovery signature is. */
    fun sign(
        label: String,
        nonce: String,
    ): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update((label + nonce).toByteArray(Charsets.US_ASCII))
            sign()
        }
}

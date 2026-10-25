package app.geunal.api.auth

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec

/** Checks a device's proof of possession of its MLS signature key: Ed25519 over the nonce's ASCII bytes. */
object Ed25519 {
    const val PUBLIC_KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64

    // SubjectPublicKeyInfo for id-Ed25519 (RFC 8410) is this fixed prefix followed by the 32 raw key bytes.
    private val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    fun verify(
        rawPublicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (rawPublicKey.size != PUBLIC_KEY_LENGTH || signature.size != SIGNATURE_LENGTH) return false
        val key = publicKey(rawPublicKey) ?: return false
        return try {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: SignatureException) {
            false
        }
    }

    private fun publicKey(raw: ByteArray): PublicKey? =
        try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiPrefix + raw))
        } catch (_: InvalidKeySpecException) {
            null
        }
}

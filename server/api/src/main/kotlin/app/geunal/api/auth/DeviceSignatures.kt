package app.geunal.api.auth

/**
 * What a device signs with its registered key for the requests a stolen session must not make alone (protocol §10,
 * §13), checked against core/vectors/device_signatures.json. Each act has its own words, so a signature for one is
 * no signature for another; the recovery uploads also cover the material, so it cannot be swapped under them.
 */
object DeviceSignatures {
    private val DELETE = "geunal delete v1".toByteArray(Charsets.US_ASCII)
    private val UPLOAD = "geunal recovery upload v1".toByteArray(Charsets.US_ASCII)
    private val NEW_KEY = "geunal new key v1".toByteArray(Charsets.US_ASCII)

    /** `DELETE /me`: "geunal delete v1" ‖ nonce. */
    fun delete(nonce: String): ByteArray = DELETE + nonce.toByteArray(Charsets.US_ASCII)

    /**
     * `PUT /me/recovery`: "geunal new key v1" (with `new_key`) or "geunal recovery upload v1", then nonce ‖
     * recovery_id ‖ recovery_public_key ‖ amk_wrapped.
     */
    fun recoveryUpload(
        newKey: Boolean,
        nonce: String,
        recoveryId: ByteArray,
        recoveryPublicKey: ByteArray,
        amkWrapped: ByteArray,
    ): ByteArray = (if (newKey) NEW_KEY else UPLOAD) + nonce.toByteArray(Charsets.US_ASCII) + recoveryId + recoveryPublicKey + amkWrapped
}

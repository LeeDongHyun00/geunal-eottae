package app.geunal.api.support

import org.springframework.http.HttpMethod
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.UUID

/** A device: an Ed25519 key, the account it signed in as, and the tokens it holds. */
class Client(
    private val api: Api,
    val subject: String,
) {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** The raw 32-byte public key: the X.509 encoding minus its fixed 12-byte prefix. */
    val signatureKey: ByteArray = keyPair.public.encoded.copyOfRange(SPKI_PREFIX, SPKI_PREFIX + 32)

    lateinit var userId: UUID
    lateinit var deviceId: UUID
    lateinit var token: String
    lateinit var refreshToken: String

    fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }

    fun loginRequest(
        nonce: String,
        platform: String = "ios",
        signature: ByteArray = sign(nonce.toByteArray(Charsets.US_ASCII)),
    ) = mapOf(
        "subject" to subject,
        "display_name" to subject.take(40),
        "platform" to platform,
        "signature_key" to signatureKey,
        "nonce" to nonce,
        "signature" to signature,
    )

    fun login(platform: String = "ios"): Reply {
        val nonce = api.send(HttpMethod.GET, "/auth/nonce").expect(200).string("nonce")
        return api.send(HttpMethod.POST, "/auth/stub", body = loginRequest(nonce, platform)).expect(201).also(::adopt)
    }

    fun adopt(tokens: Reply) {
        userId = tokens.uuid("user_id")
        deviceId = tokens.uuid("device_id")
        token = tokens.string("access_token")
        refreshToken = tokens.string("refresh_token")
    }

    fun get(path: String) = api.send(HttpMethod.GET, path, token)

    fun post(
        path: String,
        body: Any? = null,
    ) = api.send(HttpMethod.POST, path, token, body)

    fun put(
        path: String,
        body: Any?,
    ) = api.send(HttpMethod.PUT, path, token, body)

    fun delete(path: String) = api.send(HttpMethod.DELETE, path, token)

    /** An Add commit for [epoch] that brings [member]'s device in, with the Welcome addressed to it. */
    fun addMember(
        groupId: UUID,
        epoch: Long,
        member: Client,
    ): Reply =
        post(
            "/groups/$groupId/commits",
            mapOf(
                "epoch" to epoch,
                "commit" to Mls.commit(groupId, epoch),
                "group_info" to Mls.groupInfo(groupId, epoch + 1),
                "welcome" to Mls.welcome(),
                "added" to listOf(member.deviceId),
            ),
        ).expect(201)

    /** A plain (self-update) commit for [epoch]. */
    fun commit(
        groupId: UUID,
        epoch: Long,
    ): Reply =
        post(
            "/groups/$groupId/commits",
            mapOf("epoch" to epoch, "commit" to Mls.commit(groupId, epoch), "group_info" to Mls.groupInfo(groupId, epoch + 1)),
        )

    fun message(
        groupId: UUID,
        epoch: Long,
        size: Int = 64,
        alert: Any = "all",
        cat: String? = "chat",
    ): Reply =
        post(
            "/groups/$groupId/messages",
            mapOf("epoch" to epoch, "message" to Mls.privateMessage(groupId, epoch, size), "alert" to alert, "cat" to cat),
        )

    private companion object {
        const val SPKI_PREFIX = 12
    }
}

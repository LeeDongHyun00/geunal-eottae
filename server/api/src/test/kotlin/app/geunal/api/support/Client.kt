package app.geunal.api.support

import app.geunal.api.auth.ProviderTokens
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

    fun login(platform: String = "ios"): Reply = stubSignIn(platform).expect(201).also(::adopt)

    /** A stub sign-in as it answers: 201 registered, or 200 replace_required when another device is active. */
    fun stubSignIn(platform: String = "ios"): Reply = api.send(HttpMethod.POST, "/auth/stub", body = loginRequest(nonce(), platform))

    fun nonce(): String = api.send(HttpMethod.GET, "/auth/nonce").expect(200).string("nonce")

    /** The device proof every M1 sign-in carries, over a fresh nonce unless one is given. */
    fun proof(
        nonce: String = nonce(),
        platform: String = "ios",
    ): Map<String, Any> =
        mapOf(
            "platform" to platform,
            "signature_key" to signatureKey,
            "nonce" to nonce,
            "signature" to sign(nonce.toByteArray(Charsets.US_ASCII)),
        )

    /** What this device hands Kakao or Apple as the nonce for a sign-in over [nonce]. */
    fun binding(nonce: String): String = ProviderTokens.binding(nonce, signatureKey)

    /**
     * `POST /auth/kakao` or `/auth/apple` with a token [token] makes for the nonce binding; [account] holds the fields
     * a new account reads. Adopts the tokens of a 201.
     */
    fun providerSignIn(
        provider: String,
        account: Map<String, Any?> = newAccount(),
        platform: String = "ios",
        token: (binding: String) -> String,
    ): Reply {
        val nonce = nonce()
        val body = proof(nonce, platform) + account + ("id_token" to token(binding(nonce)))
        return api.send(HttpMethod.POST, "/auth/$provider", body = body).also { if (it.status == 201) adopt(it) }
    }

    fun newAccount(): Map<String, Any?> = mapOf("age_confirmed_at" to "2026-10-31T09:00:00+09:00", "display_name" to subject.take(40))

    /** `POST /auth/replace` with this device's signature over the ticket. Adopts the tokens of a 201. */
    fun replace(
        ticket: String,
        signature: ByteArray = sign("geunal replace v1".toByteArray() + ticket.toByteArray()),
    ): Reply =
        api
            .send(HttpMethod.POST, "/auth/replace", body = mapOf("ticket" to ticket, "signature" to signature))
            .also { if (it.status == 201) adopt(it) }

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

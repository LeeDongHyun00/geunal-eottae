package app.geunal.api.auth

import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRepository
import app.geunal.api.groups.GroupRepository
import app.geunal.api.realtime.MembershipEvent
import app.geunal.api.realtime.RevokeEvent
import app.geunal.api.realtime.Signals
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Sign-in, device registration and token rotation (api-server.md §4). */
@Service
class AuthService(
    private val jdbc: JdbcClient,
    private val redis: StringRedisTemplate,
    private val devices: DeviceRepository,
    private val groups: GroupRepository,
    private val tokens: AccessTokens,
    private val signals: Signals,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /** A one-time value the device signs with its Ed25519 key to prove it holds the key it registers. */
    fun nonce(): String {
        val nonce = randomToken()
        redis.opsForValue().set("nonce:$nonce", "1", properties.nonceTtl)
        return nonce
    }

    @Transactional
    fun stubLogin(request: StubLoginRequest): TokenResponse {
        val displayName = request.displayName.trim()
        if (request.subject.isBlank() || request.subject.length > MAX_SUBJECT) throw ApiException.invalid("subject is missing or too long")
        if (displayName.isEmpty() ||
            displayName.length > MAX_DISPLAY_NAME
        ) {
            throw ApiException.invalid("display_name is missing or too long")
        }
        if (request.platform !in PLATFORMS) throw ApiException.invalid("platform must be ios or android")
        if (request.signatureKey.size != Ed25519.PUBLIC_KEY_LENGTH) throw ApiException.invalid("signature_key must be 32 bytes")
        // Deleting the key is the atomic single use; a replayed nonce finds nothing to delete.
        if (redis.delete("nonce:${request.nonce}") != true) throw ApiException.invalid("nonce is unknown or was already used")
        if (!Ed25519.verify(request.signatureKey, request.nonce.toByteArray(Charsets.US_ASCII), request.signature)) {
            throw ApiException.invalid("signature does not verify over the nonce")
        }
        val userId =
            jdbc
                .sql(
                    """
                    INSERT INTO users (kakao_sub, display_name, age_confirmed_at) VALUES (:sub, :name, now())
                    ON CONFLICT (kakao_sub) DO UPDATE SET display_name = EXCLUDED.display_name
                    RETURNING id
                    """,
                ).param("sub", "stub:${request.subject}")
                .param("name", displayName)
                .query(UUID::class.java)
                .single()
        val deviceId = registerDevice(userId, request.platform, request.signatureKey)
        return issue(Caller(userId, deviceId))
    }

    @Transactional
    fun refresh(refreshToken: String): TokenResponse {
        val deviceId =
            jdbc
                .sql("DELETE FROM sessions WHERE refresh_hash = :hash AND expires_at > now() RETURNING device_id")
                .param("hash", sha256(refreshToken))
                .query(UUID::class.java)
                .optional()
                .orElseThrow { ApiException(HttpStatus.UNAUTHORIZED, "token_expired", "refresh token is unknown or expired") }
        val device =
            devices.current(deviceId) ?: throw ApiException(HttpStatus.FORBIDDEN, "device_revoked", "this device was replaced; sign out")
        return issue(Caller(device.userId, deviceId))
    }

    /**
     * Registers the device and retires the account's previous one (MVP: one active device). The old device gets
     * `revoke`; the other members of the account's groups get `membership`, so a device of theirs removes the stale leaf
     * if the new device does not resync first (protocol §2).
     */
    private fun registerDevice(
        userId: UUID,
        platform: String,
        signatureKey: ByteArray,
    ): UUID {
        val revoked = devices.revokeActive(userId)
        if (revoked != null) {
            jdbc.sql("DELETE FROM sessions WHERE device_id = :id").param("id", revoked).update()
            signals.send(listOf(revoked), RevokeEvent)
            for (groupId in groups.activeGroupIdsOf(userId)) {
                signals.send(groups.activeDeviceIds(groupId, except = revoked), MembershipEvent(groupId))
            }
        }
        return devices.insert(userId, platform, signatureKey)
    }

    private fun issue(caller: Caller): TokenResponse {
        val refreshToken = randomToken()
        jdbc
            .sql("INSERT INTO sessions (device_id, refresh_hash, expires_at) VALUES (:device, :hash, :expires)")
            .param("device", caller.deviceId)
            .param("hash", sha256(refreshToken))
            .param("expires", clock.instant().plus(properties.refreshTtl).atOffset(java.time.ZoneOffset.UTC))
            .update()
        return TokenResponse(
            userId = caller.userId,
            deviceId = caller.deviceId,
            accessToken = tokens.issue(caller),
            expiresIn = tokens.ttl.toSeconds(),
            refreshToken = refreshToken,
        )
    }

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))

    private companion object {
        const val MAX_SUBJECT = 200
        const val MAX_DISPLAY_NAME = 40
        val PLATFORMS = setOf("ios", "android")
    }
}

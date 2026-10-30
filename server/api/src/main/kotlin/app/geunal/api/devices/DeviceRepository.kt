package app.geunal.api.devices

import app.geunal.api.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class DeviceRow(
    val id: UUID,
    val userId: UUID,
    val platform: String,
    val signatureKey: ByteArray,
    val createdAt: Instant,
)

@Repository
class DeviceRepository(
    private val jdbc: JdbcClient,
) {
    /** True while the device is the account's current one. Revocation makes every token it holds useless at once. */
    fun isCurrent(
        deviceId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc
            .sql("SELECT 1 FROM devices WHERE id = :id AND user_id = :user AND revoked_at IS NULL")
            .param("id", deviceId)
            .param("user", userId)
            .query(Int::class.java)
            .optional()
            .isPresent

    fun current(deviceId: UUID): DeviceRow? =
        jdbc
            .sql("SELECT id, user_id, platform, signature_key, created_at FROM devices WHERE id = :id AND revoked_at IS NULL")
            .param("id", deviceId)
            .query(::row)
            .optional()
            .orElse(null)

    /** The account's active device, if any (MVP: at most one, api-server.md §6). */
    fun activeOf(userId: UUID): DeviceRow? =
        jdbc
            .sql("SELECT id, user_id, platform, signature_key, created_at FROM devices WHERE user_id = :user AND revoked_at IS NULL")
            .param("user", userId)
            .query(::row)
            .optional()
            .orElse(null)

    /**
     * Revokes the account's active device and returns its id, so the caller can send it `revoke`. [via] is how the
     * replacing sign-in proved itself (`provider` or `recovery`), which the old device reads in its 403.
     */
    fun revokeActive(
        userId: UUID,
        via: String? = null,
    ): UUID? =
        jdbc
            .sql("UPDATE devices SET revoked_at = now(), replaced_via = :via WHERE user_id = :user AND revoked_at IS NULL RETURNING id")
            .param("user", userId)
            .param("via", via)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    /** [providerAuth]: a Kakao, Apple or stub sign-in gave this device its session, which starts the 30-minute window. */
    fun insert(
        userId: UUID,
        platform: String,
        signatureKey: ByteArray,
        providerAuth: Boolean = false,
    ): UUID =
        jdbc
            .sql(
                """
                INSERT INTO devices (user_id, platform, signature_key, provider_auth_at)
                VALUES (:user, :platform, :key, CASE WHEN :provider::boolean THEN now() END) RETURNING id
                """,
            ).param("user", userId)
            .param("platform", platform)
            .param("key", signatureKey)
            .param("provider", providerAuth)
            .query(UUID::class.java)
            .single()

    /** The same device signed in with its provider again: "새로 시작" without the code counts from now. */
    fun markProviderAuth(deviceId: UUID) {
        jdbc.sql("UPDATE devices SET provider_auth_at = now() WHERE id = :id").param("id", deviceId).update()
    }

    /**
     * 403 `device_revoked` for a device that is no longer current. A device a replacement retired also hears when,
     * where and how (protocol §13): `replaced_at`, the account's active platform now, and `provider` or `recovery`.
     */
    fun revokedError(deviceId: UUID): ApiException {
        val details =
            jdbc
                .sql(
                    """
                    SELECT d.revoked_at, d.replaced_via, a.platform AS active_platform
                    FROM devices d JOIN devices a ON a.user_id = d.user_id AND a.revoked_at IS NULL
                    WHERE d.id = :id AND d.replaced_via IS NOT NULL
                    """,
                ).param("id", deviceId)
                .query { rs, _ ->
                    mapOf(
                        "replaced_at" to rs.getObject("revoked_at", OffsetDateTime::class.java).toInstant().toString(),
                        "replaced_by" to rs.getString("active_platform"),
                        "replaced_via" to rs.getString("replaced_via"),
                    )
                }.optional()
                .orElse(emptyMap())
        return ApiException(HttpStatus.FORBIDDEN, "device_revoked", "this device was replaced; sign out", details)
    }

    fun setPushToken(
        deviceId: UUID,
        token: String?,
    ) {
        jdbc
            .sql("UPDATE devices SET push_token = :token WHERE id = :id")
            .param("id", deviceId)
            .param("token", token)
            .update()
    }

    fun countKeyPackages(deviceId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM key_packages WHERE device_id = :id")
            .param("id", deviceId)
            .query(Int::class.java)
            .single()

    fun addKeyPackages(
        deviceId: UUID,
        packages: List<ByteArray>,
    ) {
        for (data in packages) {
            jdbc
                .sql("INSERT INTO key_packages (device_id, data) VALUES (:id, :data)")
                .param("id", deviceId)
                .param("data", data)
                .update()
        }
    }

    /** Takes the oldest key package of the device, so two adders never get the same one (SKIP LOCKED). */
    fun claimKeyPackage(deviceId: UUID): ByteArray? =
        jdbc
            .sql(
                """
                DELETE FROM key_packages WHERE id = (
                  SELECT id FROM key_packages WHERE device_id = :id ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                ) RETURNING data
                """,
            ).param("id", deviceId)
            .query(ByteArray::class.java)
            .optional()
            .orElse(null)

    private fun row(
        rs: java.sql.ResultSet,
        @Suppress("unused") index: Int,
    ) = DeviceRow(
        id = rs.getObject("id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        platform = rs.getString("platform"),
        signatureKey = rs.getBytes("signature_key"),
        createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
    )
}

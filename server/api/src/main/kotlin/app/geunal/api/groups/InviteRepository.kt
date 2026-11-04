package app.geunal.api.groups

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class InviteRow(
    val code: String,
    val groupId: UUID,
    val createdBy: UUID?,
    val expiresAt: Instant,
    val maxUses: Int,
    val uses: Int,
)

@Repository
class InviteRepository(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun create(
        groupId: UUID,
        creatorId: UUID,
    ): InviteRow {
        val expiresAt = clock.instant().plus(LIFETIME)
        // 50 random bits: a collision is not expected in the lifetime of the table, but costs only a retry.
        while (true) {
            val code = InviteCodes.random(random)
            val inserted =
                jdbc
                    .sql(
                        """
                        INSERT INTO group_invites (code, group_id, created_by, expires_at, max_uses)
                        VALUES (:code, :group, :user, :expires, :max) ON CONFLICT (code) DO NOTHING
                        """,
                    ).param("code", code)
                    .param("group", groupId)
                    .param("user", creatorId)
                    .param("expires", Timestamp.from(expiresAt))
                    .param("max", MAX_USES)
                    .update()
            if (inserted == 1) return InviteRow(code, groupId, creatorId, expiresAt, MAX_USES, 0)
        }
    }

    fun find(code: String): InviteRow? =
        jdbc
            .sql("SELECT code, group_id, created_by, expires_at, max_uses, uses FROM group_invites WHERE code = :code")
            .param("code", code)
            .query(::inviteRow)
            .optional()
            .orElse(null)

    /** The inviting member's account name, or null once they have deleted their account. */
    fun inviterName(invite: InviteRow): String? =
        invite.createdBy?.let { user ->
            jdbc
                .sql("SELECT display_name FROM users WHERE id = :user")
                .param("user", user)
                .query(String::class.java)
                .optional()
                .orElse(null)
        }

    fun delete(code: String) {
        jdbc.sql("DELETE FROM group_invites WHERE code = :code").param("code", code).update()
    }

    fun spend(code: String) {
        jdbc.sql("UPDATE group_invites SET uses = uses + 1 WHERE code = :code").param("code", code).update()
    }

    private fun inviteRow(
        rs: ResultSet,
        @Suppress("unused") index: Int,
    ) = InviteRow(
        code = rs.getString("code"),
        groupId = rs.getObject("group_id", UUID::class.java),
        createdBy = rs.getObject("created_by", UUID::class.java),
        expiresAt = rs.instant("expires_at"),
        maxUses = rs.getInt("max_uses"),
        uses = rs.getInt("uses"),
    )

    companion object {
        /** m2.md §5 decision 5. */
        val LIFETIME: Duration = Duration.ofDays(7)
        const val MAX_USES = 20
    }
}

/**
 * Codes are 10 characters of Crockford's base32 (protocol §15). People type them, so reading one forgives case, spaces,
 * dashes and the letters Crockford reads as digits; the core reads them by the same rule and the same vectors.
 */
object InviteCodes {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 10

    fun random(random: SecureRandom): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** The stored form of what someone typed, or null when it cannot be a code. */
    fun canonical(typed: String): String? {
        if (typed.length > 2 * LENGTH) return null
        val code =
            typed
                .filterNot { it == '-' || it.isWhitespace() }
                .uppercase()
                .map {
                    when (it) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> it
                    }
                }.joinToString("")
        return code.takeIf { it.length == LENGTH && it.all(ALPHABET::contains) }
    }
}

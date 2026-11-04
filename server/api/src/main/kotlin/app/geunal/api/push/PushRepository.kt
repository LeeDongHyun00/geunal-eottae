package app.geunal.api.push

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class PushTarget(
    val deviceId: UUID,
    val platform: String,
    val token: String,
) {
    override fun toString(): String = "PushTarget(deviceId=$deviceId, platform=$platform)"
}

@Repository
class PushRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * Who hears a message (api-server.md §5): the active members [alert] names, other than the sender, whose group is not
     * muted and whose switch for [cat] is on, through their current device's token. [users] null means everyone.
     */
    fun targets(
        groupId: UUID,
        senderId: UUID,
        cat: String,
        users: List<UUID>?,
    ): List<PushTarget> {
        if (users != null && users.isEmpty()) return emptyList()
        val named = if (users == null) "" else "AND m.user_id IN (:users)"
        return jdbc
            .sql(
                """
                SELECT d.id, d.platform, d.push_token
                FROM group_members m JOIN devices d ON d.user_id = m.user_id AND d.revoked_at IS NULL AND d.push_token IS NOT NULL
                WHERE m.group_id = :group AND m.status = 'active' AND m.user_id <> :sender
                  AND (m.muted_until IS NULL OR m.muted_until <= now())
                  AND CASE :cat WHEN 'chat' THEN m.notify_chat WHEN 'plan' THEN m.notify_plan ELSE false END
                  $named
                """,
            ).param("group", groupId)
            .param("sender", senderId)
            .param("cat", cat)
            .apply { if (users != null) param("users", users) }
            .query { rs, _ -> PushTarget(rs.getObject("id", UUID::class.java), rs.getString("platform"), rs.getString("push_token")) }
            .list()
    }

    /** Drops a token the platform said is dead, unless the device has registered another since. */
    fun forget(target: PushTarget) {
        jdbc
            .sql("UPDATE devices SET push_token = NULL WHERE id = :id AND push_token = :token")
            .param("id", target.deviceId)
            .param("token", target.token)
            .update()
    }
}

package app.geunal.api.groups

import app.geunal.api.common.ApiException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class GroupRow(
    val id: UUID,
    val kind: String,
    val epoch: Long,
    val lastSeq: Long,
)

data class MemberRow(
    val userId: UUID,
    val displayName: String,
    val deviceId: UUID?,
    val role: String,
    val status: String,
    val joinedAt: Instant,
)

@Repository
class GroupRepository(
    private val jdbc: JdbcClient,
) {
    fun create(creatorId: UUID): GroupRow {
        val id =
            jdbc
                .sql("INSERT INTO groups (created_by) VALUES (:user) RETURNING id")
                .param("user", creatorId)
                .query(UUID::class.java)
                .single()
        jdbc
            .sql("INSERT INTO group_members (group_id, user_id, role, status) VALUES (:group, :user, 'owner', 'active')")
            .param("group", id)
            .param("user", creatorId)
            .update()
        return GroupRow(id, "circle", 0, 0)
    }

    fun find(groupId: UUID): GroupRow? =
        jdbc
            .sql("SELECT id, kind::text AS kind, epoch, next_seq - 1 AS last_seq FROM groups WHERE id = :id")
            .param("id", groupId)
            .query(::groupRow)
            .optional()
            .orElse(null)

    /** The caller's group, or 404 for a group that does not exist and 403 `not_member` for one they are not active in. */
    fun requireActive(
        groupId: UUID,
        userId: UUID,
    ): GroupRow {
        val group = find(groupId) ?: throw ApiException.notFound("group")
        if (memberStatus(groupId, userId) != "active") throw ApiException.notMember()
        return group
    }

    fun memberStatus(
        groupId: UUID,
        userId: UUID,
    ): String? =
        jdbc
            .sql("SELECT status::text FROM group_members WHERE group_id = :group AND user_id = :user")
            .param("group", groupId)
            .param("user", userId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun members(groupId: UUID): List<MemberRow> =
        jdbc
            .sql(
                """
                SELECT m.user_id, u.display_name, d.id AS device_id, m.role::text AS role, m.status::text AS status, m.joined_at
                FROM group_members m
                JOIN users u ON u.id = m.user_id
                LEFT JOIN devices d ON d.user_id = m.user_id AND d.revoked_at IS NULL
                WHERE m.group_id = :group
                ORDER BY m.joined_at, m.user_id
                """,
            ).param("group", groupId)
            .query(::memberRow)
            .list()

    /** Groups the user is in and not removed from, with the cursor data a device syncs by. */
    fun groupsOf(userId: UUID): List<Membership> =
        jdbc
            .sql(
                """
                SELECT g.id, g.kind::text AS kind, g.epoch, g.next_seq - 1 AS last_seq, m.role::text AS role, m.status::text AS status, m.joined_at
                FROM group_members m JOIN groups g ON g.id = m.group_id
                WHERE m.user_id = :user AND m.status <> 'removed'
                ORDER BY m.joined_at, g.id
                """,
            ).param("user", userId)
            .query { rs, i -> Membership(groupRow(rs, i), rs.getString("role"), rs.getString("status"), rs.instant("joined_at")) }
            .list()

    fun activeGroupIdsOf(userId: UUID): List<UUID> =
        jdbc
            .sql("SELECT group_id FROM group_members WHERE user_id = :user AND status = 'active'")
            .param("user", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** The active devices of the group's active members: who gets a signal. */
    fun activeDeviceIds(
        groupId: UUID,
        except: UUID? = null,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT d.id FROM group_members m JOIN devices d ON d.user_id = m.user_id AND d.revoked_at IS NULL
                WHERE m.group_id = :group AND m.status = 'active' AND (:except::uuid IS NULL OR d.id <> :except::uuid)
                """,
            ).param("group", groupId)
            .param("except", except)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    fun deviceOf(userId: UUID): UUID? =
        jdbc
            .sql("SELECT id FROM devices WHERE user_id = :user AND revoked_at IS NULL")
            .param("user", userId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    /**
     * Makes the user an active member: a first add, a pending joiner, or someone removed earlier and added again. An
     * account that is already active (its replacement device being added) keeps its role and join time, so an Add
     * commit can never cost the group its owner.
     */
    fun activate(
        groupId: UUID,
        userId: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO group_members (group_id, user_id, status) VALUES (:group, :user, 'active')
                ON CONFLICT (group_id, user_id) DO UPDATE SET
                  status = 'active',
                  role = CASE WHEN group_members.status = 'active' THEN group_members.role ELSE 'member' END,
                  joined_at = CASE WHEN group_members.status = 'active' THEN group_members.joined_at ELSE now() END
                """,
            ).param("group", groupId)
            .param("user", userId)
            .update()
    }

    /** Marks the member removed; returns false when there was no such active or pending member. */
    fun remove(
        groupId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc
            .sql("UPDATE group_members SET status = 'removed' WHERE group_id = :group AND user_id = :user AND status <> 'removed'")
            .param("group", groupId)
            .param("user", userId)
            .update() == 1

    /** When no active owner is left, the longest-standing active member takes over (api-server.md §8). */
    fun succeedOwner(groupId: UUID) {
        jdbc
            .sql(
                """
                UPDATE group_members SET role = 'owner'
                WHERE group_id = :group AND status = 'active'
                  AND NOT EXISTS (SELECT 1 FROM group_members WHERE group_id = :group AND role = 'owner' AND status = 'active')
                  AND user_id = (
                    SELECT user_id FROM group_members WHERE group_id = :group AND status = 'active' ORDER BY joined_at, user_id LIMIT 1
                  )
                """,
            ).param("group", groupId)
            .update()
    }

    data class Membership(
        val group: GroupRow,
        val role: String,
        val status: String,
        val joinedAt: Instant,
    )

    private fun groupRow(
        rs: ResultSet,
        @Suppress("unused") index: Int,
    ) = GroupRow(rs.getObject("id", UUID::class.java), rs.getString("kind"), rs.getLong("epoch"), rs.getLong("last_seq"))

    private fun memberRow(
        rs: ResultSet,
        @Suppress("unused") index: Int,
    ) = MemberRow(
        userId = rs.getObject("user_id", UUID::class.java),
        displayName = rs.getString("display_name"),
        deviceId = rs.getObject("device_id", UUID::class.java),
        role = rs.getString("role"),
        status = rs.getString("status"),
        joinedAt = rs.instant("joined_at"),
    )
}

internal fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

package app.geunal.api.delivery

import app.geunal.api.groups.instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class LogEntry(
    val seq: Long,
    val epoch: Long,
    val kind: String,
    val authorId: UUID,
    val ciphertext: ByteArray,
    val receivedAt: Instant,
)

data class Accepted(
    val seq: Long,
    val epoch: Long,
)

data class StateRow(
    val kind: String,
    val ownerId: UUID,
    val epoch: Long,
    val version: Long,
    val ciphertext: ByteArray,
    val updatedAt: Instant,
)

data class WelcomeRow(
    val groupId: UUID,
    val data: ByteArray,
)

/** The SQL from api-server.md §5: one statement each for the races that matter. */
@Repository
class DeliveryRepository(
    private val jdbc: JdbcClient,
) {
    /** Takes the epoch, assigns the next seq and replaces the GroupInfo; no row back means another commit won the epoch. */
    fun acceptCommit(
        groupId: UUID,
        epoch: Long,
        authorId: UUID,
        ciphertext: ByteArray,
        groupInfo: ByteArray,
    ): Accepted? =
        jdbc
            .sql(
                """
                WITH moved AS (
                  UPDATE groups SET epoch = epoch + 1, next_seq = next_seq + 1, group_info = :group_info
                  WHERE id = :group_id AND epoch = :epoch
                  RETURNING next_seq - 1 AS seq, epoch - 1 AS epoch
                )
                INSERT INTO group_log (group_id, seq, epoch, kind, author_id, ciphertext)
                SELECT :group_id, seq, epoch, 'commit', :author_id, :ciphertext FROM moved
                RETURNING seq, epoch
                """,
            ).param("group_id", groupId)
            .param("epoch", epoch)
            .param("author_id", authorId)
            .param("ciphertext", ciphertext)
            .param("group_info", groupInfo)
            .query { rs, _ -> Accepted(rs.getLong("seq"), rs.getLong("epoch")) }
            .optional()
            .orElse(null)

    /** Assigns the next seq while the group is still in this epoch; no row back means the message is for a past epoch. */
    fun appendMessage(
        groupId: UUID,
        epoch: Long,
        authorId: UUID,
        ciphertext: ByteArray,
    ): Long? =
        jdbc
            .sql(
                """
                WITH s AS (
                  UPDATE groups SET next_seq = next_seq + 1
                  WHERE id = :group_id AND epoch = :epoch
                  RETURNING next_seq - 1 AS seq
                )
                INSERT INTO group_log (group_id, seq, epoch, kind, author_id, ciphertext)
                SELECT :group_id, seq, :epoch, 'app', :author_id, :ciphertext FROM s
                RETURNING seq
                """,
            ).param("group_id", groupId)
            .param("epoch", epoch)
            .param("author_id", authorId)
            .param("ciphertext", ciphertext)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    fun log(
        groupId: UUID,
        after: Long,
        limit: Int,
    ): List<LogEntry> =
        jdbc
            .sql(
                """
                SELECT seq, epoch, kind::text AS kind, author_id, ciphertext, received_at FROM group_log
                WHERE group_id = :group AND seq > :after ORDER BY seq LIMIT :limit
                """,
            ).param("group", groupId)
            .param("after", after)
            .param("limit", limit)
            .query { rs, _ ->
                LogEntry(
                    rs.getLong("seq"),
                    rs.getLong("epoch"),
                    rs.getString("kind"),
                    rs.uuid("author_id"),
                    rs.getBytes("ciphertext"),
                    rs.instant("received_at"),
                )
            }.list()

    fun groupInfo(groupId: UUID): ByteArray? =
        jdbc
            .sql("SELECT group_info FROM groups WHERE id = :id")
            .param("id", groupId)
            .query { rs, _ -> rs.getBytes("group_info") }
            .optional()
            .orElse(null)

    /** Replaces the GroupInfo while the group is still in [epoch]; false when a commit moved it on meanwhile. */
    fun putGroupInfo(
        groupId: UUID,
        epoch: Long,
        groupInfo: ByteArray,
    ): Boolean =
        jdbc
            .sql("UPDATE groups SET group_info = :info WHERE id = :id AND epoch = :epoch")
            .param("id", groupId)
            .param("epoch", epoch)
            .param("info", groupInfo)
            .update() == 1

    fun addWelcome(
        deviceId: UUID,
        groupId: UUID,
        data: ByteArray,
    ) {
        jdbc
            .sql("INSERT INTO welcomes (device_id, group_id, data) VALUES (:device, :group, :data)")
            .param("device", deviceId)
            .param("group", groupId)
            .param("data", data)
            .update()
    }

    /** Hands over and deletes the device's Welcomes: a Welcome is read once (protocol §3). */
    fun takeWelcomes(deviceId: UUID): List<WelcomeRow> =
        jdbc
            .sql("DELETE FROM welcomes WHERE device_id = :device RETURNING group_id, data")
            .param("device", deviceId)
            .query { rs, _ -> WelcomeRow(rs.uuid("group_id"), rs.getBytes("data")) }
            .list()

    /** Overwrites the owner's blob unless it is sealed for an older epoch; returns the new version or null. */
    fun putState(
        groupId: UUID,
        kind: String,
        ownerId: UUID,
        epoch: Long,
        ciphertext: ByteArray,
    ): Long? =
        jdbc
            .sql(
                """
                INSERT INTO group_state (group_id, kind, owner_id, epoch, ciphertext)
                VALUES (:group_id, CAST(:kind AS state_kind), :owner_id, :epoch, :ciphertext)
                ON CONFLICT (group_id, kind, owner_id) DO UPDATE
                  SET epoch = EXCLUDED.epoch, ciphertext = EXCLUDED.ciphertext, version = group_state.version + 1, updated_at = now()
                  WHERE group_state.epoch <= EXCLUDED.epoch
                RETURNING version
                """,
            ).param("group_id", groupId)
            .param("kind", kind)
            .param("owner_id", ownerId)
            .param("epoch", epoch)
            .param("ciphertext", ciphertext)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /**
     * The one shared `group_meta` slot: inserted only from `base_version` 0, overwritten only from the version the writer
     * read and never with an older epoch. Null means the writer must re-read (api-server.md §5).
     */
    fun putGroupMeta(
        groupId: UUID,
        writerId: UUID,
        epoch: Long,
        ciphertext: ByteArray,
        baseVersion: Long,
    ): Long? =
        jdbc
            .sql(
                """
                INSERT INTO group_state (group_id, kind, owner_id, epoch, ciphertext)
                SELECT :group_id, 'group_meta', :writer_id, :epoch, :ciphertext
                WHERE :base_version = 0 OR EXISTS (SELECT 1 FROM group_state WHERE group_id = :group_id AND kind = 'group_meta')
                ON CONFLICT (group_id) WHERE kind = 'group_meta' DO UPDATE
                  SET owner_id = EXCLUDED.owner_id, epoch = EXCLUDED.epoch, ciphertext = EXCLUDED.ciphertext,
                      version = group_state.version + 1, updated_at = now()
                  WHERE group_state.version = :base_version AND group_state.epoch <= EXCLUDED.epoch
                RETURNING version
                """,
            ).param("group_id", groupId)
            .param("writer_id", writerId)
            .param("epoch", epoch)
            .param("ciphertext", ciphertext)
            .param("base_version", baseVersion)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    fun states(groupId: UUID): List<StateRow> =
        jdbc
            .sql(
                """
                SELECT kind::text AS kind, owner_id, epoch, version, ciphertext, updated_at FROM group_state
                WHERE group_id = :group ORDER BY kind, owner_id
                """,
            ).param("group", groupId)
            .query(::stateRow)
            .list()

    fun state(
        groupId: UUID,
        kind: String,
        ownerId: UUID?,
    ): StateRow? =
        jdbc
            .sql(
                """
                SELECT kind::text AS kind, owner_id, epoch, version, ciphertext, updated_at FROM group_state
                WHERE group_id = :group AND kind = CAST(:kind AS state_kind) AND (:owner::uuid IS NULL OR owner_id = :owner::uuid)
                """,
            ).param("group", groupId)
            .param("kind", kind)
            .param("owner", ownerId)
            .query(::stateRow)
            .optional()
            .orElse(null)

    private fun stateRow(
        rs: ResultSet,
        @Suppress("unused") index: Int,
    ) = StateRow(
        rs.getString("kind"),
        rs.uuid("owner_id"),
        rs.getLong("epoch"),
        rs.getLong("version"),
        rs.getBytes("ciphertext"),
        rs.instant("updated_at"),
    )

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
}

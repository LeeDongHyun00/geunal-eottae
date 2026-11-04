package app.geunal.api.groups

import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import app.geunal.api.realtime.MembershipEvent
import app.geunal.api.realtime.Signals
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.Optional
import java.util.UUID

data class GroupResponse(
    val id: UUID,
    val kind: String,
    val epoch: Long,
    val lastSeq: Long,
)

data class MyGroup(
    val id: UUID,
    val kind: String,
    val epoch: Long,
    val lastSeq: Long,
    val role: String,
    val status: String,
    val joinedAt: Instant,
    val mutedUntil: Instant?,
    val notifyChat: Boolean,
    val notifyPlan: Boolean,
)

data class MyGroupsResponse(
    val groups: List<MyGroup>,
)

data class MemberView(
    val userId: UUID,
    val displayName: String,
    val deviceId: UUID?,
    val role: String,
    val status: String,
    val joinedAt: Instant,
)

data class MembersResponse(
    val members: List<MemberView>,
)

/** Groups and their member lists. The MLS side of membership moves through the delivery endpoints. */
@RestController
class GroupController(
    private val groups: GroupRepository,
    private val signals: Signals,
) {
    /** The API mints the group ID; the creator's device then creates the MLS group with these 16 bytes (protocol §2). */
    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(
        @AuthenticationPrincipal caller: Caller,
    ): GroupResponse = groups.create(caller.userId).let { GroupResponse(it.id, it.kind, it.epoch, it.lastSeq) }

    @GetMapping("/me/groups")
    fun mine(
        @AuthenticationPrincipal caller: Caller,
    ): MyGroupsResponse =
        MyGroupsResponse(
            groups.groupsOf(caller.userId).map {
                MyGroup(
                    it.group.id,
                    it.group.kind,
                    it.group.epoch,
                    it.group.lastSeq,
                    it.role,
                    it.status,
                    it.joinedAt,
                    it.settings.mutedUntil,
                    it.settings.notifyChat,
                    it.settings.notifyPlan,
                )
            },
        )

    @GetMapping("/groups/{id}/members")
    fun members(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
    ): MembersResponse {
        groups.requireActive(id, caller.userId)
        return MembersResponse(
            groups.members(id).map { MemberView(it.userId, it.displayName, it.deviceId, it.role, it.status, it.joinedAt) },
        )
    }

    /**
     * The caller's push settings for the group (protocol §8): `muted_until` (null unmutes), `notify_chat`, `notify_plan`.
     * A field left out stays as it is.
     */
    @PatchMapping("/groups/{id}/members/me")
    @Transactional
    fun settings(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @RequestBody body: JsonNode,
    ): MemberSettings {
        groups.requireActive(id, caller.userId)
        if (!body.isObject ||
            body.propertyNames().any { it !in SETTINGS }
        ) {
            throw ApiException.invalid("only muted_until, notify_chat and notify_plan")
        }
        val muted =
            body.get("muted_until")?.let { node ->
                when {
                    node.isNull -> Optional.empty()
                    node.isString ->
                        Optional.of(
                            runCatching {
                                Instant.parse(node.stringValue())
                            }.getOrElse { throw ApiException.invalid("muted_until is not an RFC 3339 time") },
                        )
                    else -> throw ApiException.invalid("muted_until is a time or null")
                }
            }

        fun flag(name: String): Boolean? =
            body.get(name)?.let { if (it.isBoolean) it.booleanValue() else throw ApiException.invalid("$name is true or false") }
        return groups.updateSettings(id, caller.userId, muted, flag("notify_chat"), flag("notify_plan"))
    }

    /**
     * Leaving (own id) or kicking (the owner, anyone else). The server only changes the member's status and signals
     * `membership`; the first active device to see it uploads the Remove commit (protocol §2).
     */
    @DeleteMapping("/groups/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun remove(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
    ) {
        groups.requireActive(id, caller.userId)
        if (userId != caller.userId) {
            val role = groups.members(id).first { it.userId == caller.userId }.role
            if (role != "owner") throw ApiException(HttpStatus.FORBIDDEN, "not_owner", "only the owner removes other members")
        }
        if (!groups.remove(id, userId)) throw ApiException.notFound("member")
        groups.succeedOwner(id)
        val recipients = groups.activeDeviceIds(id) + listOfNotNull(groups.deviceOf(userId))
        signals.send(recipients.distinct(), MembershipEvent(id))
    }
}

private val SETTINGS = setOf("muted_until", "notify_chat", "notify_plan")

package app.geunal.api.groups

import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import app.geunal.api.common.HourlyLimit
import app.geunal.api.common.RateLimitProperties
import app.geunal.api.realtime.MembershipEvent
import app.geunal.api.realtime.Signals
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class InviteResponse(
    val code: String,
    val expiresAt: Instant,
)

data class InvitePreview(
    val groupId: UUID,
    val inviterName: String?,
    val memberCount: Int,
    val expiresAt: Instant,
    val full: Boolean,
)

data class JoinResponse(
    val groupId: UUID,
    val status: String,
)

/**
 * Invite codes (protocol §2): any active member makes one, and whoever holds it becomes pending in the group, which an
 * online member's device then adds. Holding a code is the inviting member's approval (kickoff decisions), so the code
 * is all the join asks for; the per-address limit keeps codes from being guessed.
 * Shapes and errors follow protocol §15.
 */
@RestController
class InviteController(
    private val groups: GroupRepository,
    private val invites: InviteRepository,
    private val signals: Signals,
    private val limits: HourlyLimit,
    private val rates: RateLimitProperties,
    private val clock: Clock,
) {
    @PostMapping("/groups/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
    ): InviteResponse {
        val group = groups.requireActive(id, caller.userId)
        if (group.kind != "circle") throw ApiException.invalid("a 1:1 takes no invitations")
        limits.check("invite", caller.userId.toString(), rates.invitesPerHour)
        if (groups.seatCount(id) >= GroupLimits.MEMBERS_PER_GROUP) throw GroupLimits.full()
        val invite = invites.create(id, caller.userId)
        return InviteResponse(invite.code, invite.expiresAt)
    }

    /** What the join screen shows before joining: no member names, since the holder is not a member yet (m2.md §5). */
    @GetMapping("/invites/{code}")
    fun preview(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable code: String,
        request: HttpServletRequest,
    ): InvitePreview {
        limits.check("invite lookup", request.remoteAddr, rates.inviteUsesPerHour)
        val invite = InviteCodes.canonical(code)?.let(invites::find)?.takeIf(::usable) ?: throw invalid()
        return InvitePreview(
            groupId = invite.groupId,
            inviterName = invites.inviterName(invite),
            memberCount = groups.members(invite.groupId).count { it.status == "active" },
            expiresAt = invite.expiresAt,
            full = groups.seatCount(invite.groupId) >= GroupLimits.MEMBERS_PER_GROUP,
        )
    }

    /**
     * Makes the caller pending and tells the group's active devices, the first of which adds them (protocol §2). Asking
     * again changes nothing and spends no use, so a device may retry a join whose answer it lost.
     */
    @PostMapping("/invites/{code}/join")
    @Transactional
    fun join(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable code: String,
        request: HttpServletRequest,
    ): JoinResponse {
        limits.check("invite join", request.remoteAddr, rates.inviteUsesPerHour)
        val canonical = InviteCodes.canonical(code) ?: throw invalid()
        groups.lockAccount(caller.userId)
        val found = invites.find(canonical) ?: throw invalid()
        groups.lockGroup(found.groupId)
        // Read again under the group's lock: a join that waited on it sees the uses the one before it spent.
        val invite = invites.find(canonical)?.takeIf(::usable) ?: throw invalid()
        when (val status = groups.memberStatus(invite.groupId, caller.userId)) {
            "pending", "active" -> return JoinResponse(invite.groupId, status)
        }
        if (groups.seatCount(invite.groupId) >= GroupLimits.MEMBERS_PER_GROUP) throw GroupLimits.full()
        if (groups.circleCount(caller.userId) >= GroupLimits.GROUPS_PER_ACCOUNT) throw GroupLimits.tooManyGroups()
        groups.invite(invite.groupId, caller.userId)
        invites.spend(canonical)
        signals.send(groups.activeDeviceIds(invite.groupId), MembershipEvent(invite.groupId))
        return JoinResponse(invite.groupId, "pending")
    }

    private fun usable(invite: InviteRow): Boolean = invite.expiresAt > clock.instant() && invite.uses < invite.maxUses

    /** Unknown, ended and used-up codes answer alike (protocol §15), so guessing learns nothing from the difference. */
    private fun invalid() = ApiException(HttpStatus.NOT_FOUND, "invite_invalid", "no usable invite code")
}

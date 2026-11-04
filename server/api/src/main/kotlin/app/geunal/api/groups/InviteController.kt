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
    val maxUses: Int,
)

data class InvitePreview(
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
        val invite = invites.create(id, caller.userId)
        return InviteResponse(InviteCodes.display(invite.code), invite.expiresAt, invite.maxUses)
    }

    /** What the join screen shows before joining: no member names, since the holder is not a member yet (m2.md §5). */
    @GetMapping("/invites/{code}")
    fun preview(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable code: String,
        request: HttpServletRequest,
    ): InvitePreview {
        limits.check("invite lookup", request.remoteAddr, rates.inviteUsesPerHour)
        val invite = InviteCodes.canonical(code)?.let(invites::find) ?: throw ApiException.notFound("invite")
        val seats = groups.seatCount(invite.groupId)
        return InvitePreview(
            inviterName = invites.inviterName(invite),
            memberCount = groups.members(invite.groupId).count { it.status == "active" },
            expiresAt = invite.expiresAt,
            full = invite.uses >= invite.maxUses || seats >= GroupLimits.MEMBERS_PER_GROUP,
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
        val canonical = InviteCodes.canonical(code) ?: throw ApiException.notFound("invite")
        groups.lockAccount(caller.userId)
        val found = invites.find(canonical) ?: throw ApiException.notFound("invite")
        groups.lockGroup(found.groupId)
        // Read again under the group's lock: a join that waited on it sees the uses the one before it spent.
        val invite = invites.find(canonical) ?: throw ApiException.notFound("invite")
        when (val status = groups.memberStatus(invite.groupId, caller.userId)) {
            "pending", "active" -> return JoinResponse(invite.groupId, status)
        }
        if (invite.expiresAt <= clock.instant() || invite.uses >= invite.maxUses) {
            throw ApiException(HttpStatus.GONE, "invite_expired", "the invite code has expired or been used up")
        }
        if (groups.seatCount(invite.groupId) >= GroupLimits.MEMBERS_PER_GROUP) throw GroupLimits.full()
        if (groups.circleCount(caller.userId) >= GroupLimits.GROUPS_PER_ACCOUNT) throw GroupLimits.tooManyGroups()
        groups.invite(invite.groupId, caller.userId)
        invites.spend(canonical)
        signals.send(groups.activeDeviceIds(invite.groupId), MembershipEvent(invite.groupId))
        return JoinResponse(invite.groupId, "pending")
    }
}

package app.geunal.api.delivery

import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRepository
import app.geunal.api.groups.GroupRepository
import app.geunal.api.mls.MlsHeader
import app.geunal.api.realtime.EntryEvent
import app.geunal.api.realtime.Signals
import app.geunal.api.realtime.StateEvent
import app.geunal.api.realtime.WelcomeEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The delivery service rules of api-server.md §5: accept, order, store, then signal. */
@Service
class DeliveryService(
    private val groups: GroupRepository,
    private val devices: DeviceRepository,
    private val repository: DeliveryRepository,
    private val signals: Signals,
) {
    @Transactional
    fun commit(
        caller: Caller,
        groupId: UUID,
        request: CommitRequest,
    ): Accepted {
        groups.requireActive(groupId, caller.userId)
        checkSize(request.commit, "commit", MAX_ENTRY)
        checkSize(request.groupInfo, "group_info", MAX_ENTRY)
        val header = MlsHeader.parse(request.commit).expectWireFormat(MlsHeader.PUBLIC_MESSAGE).expectGroup(groupId, request.epoch)
        if (header.contentType != MlsHeader.CONTENT_COMMIT) throw ApiException.invalid("the public message is not a commit")
        // An external commit's author is already an active member (checked above); the device holding the token is by
        // construction their current one, so a stale device cannot rejoin a group it was replaced out of.
        MlsHeader.parse(request.groupInfo).expectWireFormat(MlsHeader.GROUP_INFO).expectGroup(groupId, request.epoch + 1)
        val added = request.added.distinct()
        if (added.isNotEmpty() && request.welcome == null) throw ApiException.invalid("added devices need a welcome")
        if (added.isEmpty() && request.welcome != null) throw ApiException.invalid("a welcome needs added devices")
        request.welcome?.let {
            checkSize(it, "welcome", MAX_ENTRY)
            MlsHeader.parse(it).expectWireFormat(MlsHeader.WELCOME)
        }
        val addedDevices = added.map { devices.current(it) ?: throw ApiException.notFound("added device $it") }

        val accepted =
            repository.acceptCommit(groupId, request.epoch, caller.userId, request.commit, request.groupInfo)
                ?: throw ApiException(
                    HttpStatus.CONFLICT,
                    "epoch_conflict",
                    "another commit already moved the group past epoch ${request.epoch}",
                )

        for (device in addedDevices) {
            groups.activate(groupId, device.userId)
            repository.addWelcome(device.id, groupId, accepted.seq, request.welcome!!)
        }
        val addedIds = addedDevices.map { it.id }.toSet()
        val others = groups.activeDeviceIds(groupId, except = caller.deviceId).filterNot { it in addedIds }
        signals.send(others, EntryEvent.of(groupId, accepted.seq, "commit", accepted.receivedAt, request.commit))
        signals.send(addedIds, WelcomeEvent)
        return accepted
    }

    @Transactional
    fun message(
        caller: Caller,
        groupId: UUID,
        request: MessageRequest,
    ): Appended {
        groups.requireActive(groupId, caller.userId)
        checkSize(request.message, "message", MAX_ENTRY)
        MlsHeader.parse(request.message).expectWireFormat(MlsHeader.PRIVATE_MESSAGE).expectGroup(groupId, request.epoch)
        request.validateAlert()
        val appended =
            repository.appendMessage(groupId, request.epoch, caller.userId, request.message)
                ?: throw ApiException(HttpStatus.CONFLICT, "stale_epoch", "the group is no longer in epoch ${request.epoch}")
        val event = EntryEvent.of(groupId, appended.seq, "app", appended.receivedAt, request.message)
        signals.send(groups.activeDeviceIds(groupId, except = caller.deviceId), event)
        return appended
    }

    fun log(
        caller: Caller,
        groupId: UUID,
        after: Long,
        limit: Int,
    ): LogPage {
        val group = groups.requireActive(groupId, caller.userId)
        if (after < 0) throw ApiException.invalid("after must be 0 or more")
        val entries = repository.log(groupId, after, limit.coerceIn(1, MAX_LOG_PAGE))
        // The entry right after the cursor is gone when the first one back is later, or when nothing came back although
        // the group has moved on: the mailbox dropped it with a 30-day partition (api-server.md §5).
        val expired = entries.firstOrNull()?.let { it.seq != after + 1 } ?: (after < group.lastSeq)
        if (expired) throw ApiException(HttpStatus.GONE, "cursor_expired", "entries after $after are no longer kept")
        return LogPage(entries, group.lastSeq)
    }

    fun groupInfo(
        caller: Caller,
        groupId: UUID,
    ): GroupInfoResponse {
        groups.requireActive(groupId, caller.userId)
        val info = repository.groupInfo(groupId) ?: throw ApiException.notFound("group info")
        return GroupInfoResponse(info.epoch, info.groupInfo)
    }

    /** The creator's device publishes the epoch-0 GroupInfo here, since the group ID exists only after `POST /groups`. */
    @Transactional
    fun putGroupInfo(
        caller: Caller,
        groupId: UUID,
        request: GroupInfoRequest,
    ) {
        groups.requireActive(groupId, caller.userId)
        checkSize(request.groupInfo, "group_info", MAX_ENTRY)
        MlsHeader.parse(request.groupInfo).expectWireFormat(MlsHeader.GROUP_INFO).expectGroup(groupId, request.epoch)
        if (!repository.putGroupInfo(groupId, request.epoch, request.groupInfo)) {
            throw ApiException(HttpStatus.CONFLICT, "stale_epoch", "the group is no longer in epoch ${request.epoch}")
        }
    }

    @Transactional
    fun putState(
        caller: Caller,
        groupId: UUID,
        kind: String,
        request: StateRequest,
    ): Long {
        val group = groups.requireActive(groupId, caller.userId)
        if (kind !in STATE_KINDS) throw ApiException.notFound("state kind $kind")
        checkSize(request.ciphertext, "state", MAX_STATE)
        if (request.epoch < 0 ||
            request.epoch > group.epoch
        ) {
            throw ApiException.invalid("epoch ${request.epoch} is ahead of the group's ${group.epoch}")
        }
        val version =
            if (kind == GROUP_META) {
                val base = request.baseVersion ?: throw ApiException.invalid("group_meta needs base_version (0 when there is none yet)")
                repository.putGroupMeta(groupId, caller.userId, request.epoch, request.ciphertext, base)
                    ?: throw conflict(groupId, kind, null, base, request.epoch)
            } else {
                repository.putState(groupId, kind, caller.userId, request.epoch, request.ciphertext)
                    ?: throw conflict(groupId, kind, caller.userId, null, request.epoch)
            }
        signals.send(groups.activeDeviceIds(groupId, except = caller.deviceId), StateEvent(groupId, kind, caller.userId, request.epoch))
        return version
    }

    fun states(
        caller: Caller,
        groupId: UUID,
    ): List<StateRow> {
        groups.requireActive(groupId, caller.userId)
        return repository.states(groupId)
    }

    @Transactional
    fun takeWelcomes(caller: Caller): List<WelcomeRow> = repository.takeWelcomes(caller.deviceId)

    /** Why a state write was refused: another writer's version for group_meta, else a blob sealed for a newer epoch. */
    private fun conflict(
        groupId: UUID,
        kind: String,
        ownerId: UUID?,
        baseVersion: Long?,
        epoch: Long,
    ): ApiException {
        val current = repository.state(groupId, kind, ownerId)
        return if (baseVersion != null && current?.version != baseVersion) {
            ApiException(HttpStatus.CONFLICT, "state_conflict", "group_meta is at version ${current?.version ?: 0}, not $baseVersion")
        } else {
            ApiException(HttpStatus.CONFLICT, "stale_epoch", "the stored blob is sealed for epoch ${current?.epoch}, newer than $epoch")
        }
    }

    private fun checkSize(
        bytes: ByteArray,
        what: String,
        max: Int,
    ) {
        if (bytes.isEmpty()) throw ApiException.invalid("$what is empty")
        if (bytes.size > max) throw ApiException.tooLarge(what, max)
    }

    companion object {
        const val MAX_ENTRY = 65536
        const val MAX_STATE = 16384
        const val MAX_LOG_PAGE = 500
        const val GROUP_META = "group_meta"
        val STATE_KINDS = setOf("availability", GROUP_META, "member_profile", "newcomer_bundle")
    }
}

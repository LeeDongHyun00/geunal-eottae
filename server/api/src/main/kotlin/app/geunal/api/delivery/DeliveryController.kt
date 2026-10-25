package app.geunal.api.delivery

import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** A commit for [epoch] with the GroupInfo of the epoch it creates; Add commits carry one Welcome for the [added] devices. */
data class CommitRequest(
    val epoch: Long,
    val commit: ByteArray,
    val groupInfo: ByteArray,
    val welcome: ByteArray? = null,
    val added: List<UUID> = emptyList(),
)

data class CommitResponse(
    val seq: Long,
    val epoch: Long,
)

/** An application message. [alert] is `"all"`, `"none"` or a list of user IDs; [cat] is `chat` or `plan` unless alert is none (protocol §8). */
data class MessageRequest(
    val epoch: Long,
    val message: ByteArray,
    val alert: JsonNode,
    val cat: String? = null,
) {
    fun validateAlert() {
        val ok =
            when {
                alert.isString -> alert.stringValue() in setOf("all", "none")
                alert.isArray -> alert.all { it.isString && runCatching { UUID.fromString(it.stringValue()) }.isSuccess }
                else -> false
            }
        if (!ok) throw ApiException.invalid("alert must be \"all\", \"none\" or a list of user IDs")
        val none = alert.isString && alert.stringValue() == "none"
        if (!none && cat !in setOf("chat", "plan")) throw ApiException.invalid("cat must be chat or plan when alert is not none")
    }
}

data class MessageResponse(
    val seq: Long,
)

data class LogEntryView(
    val seq: Long,
    val epoch: Long,
    val kind: String,
    val authorId: UUID,
    val ciphertext: ByteArray,
    val receivedAt: Instant,
)

data class LogPage(
    val entries: List<LogEntry>,
    val lastSeq: Long,
)

data class LogResponse(
    val entries: List<LogEntryView>,
    val lastSeq: Long,
)

data class GroupInfoRequest(
    val epoch: Long,
    val groupInfo: ByteArray,
)

data class GroupInfoResponse(
    val epoch: Long,
    val groupInfo: ByteArray,
)

data class StateRequest(
    val epoch: Long,
    val ciphertext: ByteArray,
    val baseVersion: Long? = null,
)

data class StateResponse(
    val version: Long,
)

data class StateView(
    val kind: String,
    val ownerId: UUID,
    val epoch: Long,
    val version: Long,
    val ciphertext: ByteArray,
    val updatedAt: Instant,
)

data class StatesResponse(
    val states: List<StateView>,
)

data class WelcomeView(
    val groupId: UUID,
    val data: ByteArray,
)

data class WelcomesResponse(
    val welcomes: List<WelcomeView>,
)

@RestController
class DeliveryController(
    private val delivery: DeliveryService,
) {
    @PostMapping("/groups/{id}/commits")
    @ResponseStatus(HttpStatus.CREATED)
    fun commit(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @RequestBody body: CommitRequest,
    ): CommitResponse = delivery.commit(caller, id, body).let { CommitResponse(it.seq, it.epoch) }

    @PostMapping("/groups/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun message(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @RequestBody body: MessageRequest,
    ): MessageResponse = MessageResponse(delivery.message(caller, id, body))

    @GetMapping("/groups/{id}/log")
    fun log(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") after: Long,
        @RequestParam(defaultValue = "500") limit: Int,
    ): LogResponse {
        val page = delivery.log(caller, id, after, limit)
        return LogResponse(
            page.entries.map { LogEntryView(it.seq, it.epoch, it.kind, it.authorId, it.ciphertext, it.receivedAt) },
            page.lastSeq,
        )
    }

    @GetMapping("/groups/{id}/group-info")
    fun groupInfo(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
    ): GroupInfoResponse = delivery.groupInfo(caller, id)

    @PutMapping("/groups/{id}/group-info")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun putGroupInfo(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @RequestBody body: GroupInfoRequest,
    ) = delivery.putGroupInfo(caller, id, body)

    @PutMapping("/groups/{id}/state/{kind}")
    fun putState(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
        @PathVariable kind: String,
        @RequestBody body: StateRequest,
    ): StateResponse = StateResponse(delivery.putState(caller, id, kind, body))

    @GetMapping("/groups/{id}/state")
    fun states(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: UUID,
    ): StatesResponse =
        StatesResponse(
            delivery.states(caller, id).map {
                StateView(it.kind, it.ownerId, it.epoch, it.version, it.ciphertext, it.updatedAt)
            },
        )

    @GetMapping("/me/welcomes")
    fun welcomes(
        @AuthenticationPrincipal caller: Caller,
    ): WelcomesResponse = WelcomesResponse(delivery.takeWelcomes(caller).map { WelcomeView(it.groupId, it.data) })
}

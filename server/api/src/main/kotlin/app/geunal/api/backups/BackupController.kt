package app.geunal.api.backups

import app.geunal.api.auth.AccountRepository
import app.geunal.api.auth.AuthProperties
import app.geunal.api.auth.Caller
import app.geunal.api.auth.Ed25519
import app.geunal.api.auth.Nonces
import app.geunal.api.auth.ProviderProofs
import app.geunal.api.auth.Reader
import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** `PUT /me/recovery`. The last four fields prove the right to overwrite material that is already stored. */
data class RecoveryUploadRequest(
    val amkWrapped: ByteArray,
    val recoveryId: ByteArray,
    val recoveryPublicKey: ByteArray,
    val newKey: Boolean = false,
    val nonce: String? = null,
    val proof: ByteArray? = null,
    val kakaoIdToken: String? = null,
    val appleAuthorizationCode: String? = null,
)

data class RecoveryResponse(
    val amkWrapped: ByteArray,
    val updatedAt: Instant,
)

data class BackupStartRequest(
    val size: Long,
)

data class BackupStartResponse(
    val id: Long,
    val uploadUrl: String,
    val expiresIn: Long,
)

/** A completed backup as the server knows it: id, size and time only (protocol §10 "목록"). */
data class BackupView(
    val id: Long,
    val size: Long,
    val createdAt: Instant,
)

data class BackupList(
    val backups: List<BackupView>,
)

data class BackupDownload(
    val id: Long,
    val size: Long,
    val createdAt: Instant,
    val downloadUrl: String,
    val expiresIn: Long,
)

/**
 * Recovery material and backups (protocol §10). The three reads also answer a replacement ticket, so a device can
 * restore before it takes over the account; everything that writes needs the account's active device.
 */
@RestController
class BackupController(
    private val backups: BackupRepository,
    private val store: ObjectStore,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val nonces: Nonces,
    private val proofs: ProviderProofs,
    private val redis: StringRedisTemplate,
    private val auth: AuthProperties,
    private val clock: Clock,
) {
    /**
     * Stores the recovery material. Overwriting needs the current code's signature over a nonce, except "새로 시작"
     * (`new_key`) from a provider account: a provider sign-in on this device under 30 minutes ago, or a fresh provider
     * proof. Starting fresh deletes every backup, which nobody can open without the old key any more.
     */
    @PutMapping("/me/recovery")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun putRecovery(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody body: RecoveryUploadRequest,
    ) {
        if (body.amkWrapped.size != WRAPPED_AMK_LENGTH ||
            body.recoveryId.size != RECOVERY_ID_LENGTH ||
            body.recoveryPublicKey.size != PUBLIC_KEY_LENGTH
        ) {
            throw ApiException.invalid("amk_wrapped, recovery_id and recovery_public_key must be 72, 16 and 32 bytes")
        }
        accounts.lock(caller.userId)
        // Checked again under the account lock: a guest's recovery may just have replaced this device.
        val device = devices.current(caller.deviceId) ?: throw devices.revokedError(caller.deviceId)
        val owner = backups.recoveryOwner(body.recoveryId)
        if (owner != null && owner != caller.userId) {
            throw ApiException(HttpStatus.CONFLICT, "recovery_conflict", "another account uses this recovery_id; make a new code")
        }
        val current = backups.recovery(caller.userId)
        if (current != null && body.newKey) {
            val since = devices.providerAuthAt(caller.deviceId)
            val recent = since != null && since.isAfter(clock.instant().minus(auth.providerWindow))
            if (!recent &&
                proofs.prove(accounts.links(caller.userId), device, body.kakaoIdToken, body.nonce, body.appleAuthorizationCode) == null
            ) {
                throw ApiException(
                    HttpStatus.FORBIDDEN,
                    "reauth_required",
                    "starting fresh needs a provider sign-in on this device or a fresh provider proof",
                )
            }
            backups.deleteAll(caller.userId)
        } else if (current != null) {
            val message = ROTATE_LABEL + body.nonce.orEmpty().toByteArray(Charsets.US_ASCII)
            val proven = body.proof != null && nonces.spend(body.nonce) && Ed25519.verify(current.publicKey, message, body.proof)
            if (!proven) {
                throw ApiException(
                    HttpStatus.FORBIDDEN,
                    "recovery_proof_required",
                    "overwriting needs the current code's signature",
                )
            }
        }
        backups.putRecovery(caller.userId, body.recoveryId, body.recoveryPublicKey, body.amkWrapped)
    }

    @GetMapping("/me/recovery")
    fun recovery(
        @AuthenticationPrincipal reader: Reader,
    ): RecoveryResponse {
        val row = backups.recovery(reader.userId) ?: throw ApiException.notFound("recovery material")
        return RecoveryResponse(row.amkWrapped, row.updatedAt)
    }

    /** Where to put a sealed backup of [BackupStartRequest.size] bytes: a URL signed for that length, 15 minutes. */
    @PostMapping("/me/backups")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun start(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody body: BackupStartRequest,
    ): BackupStartResponse {
        if (body.size < 1) throw ApiException.invalid("size must be at least 1 byte")
        if (body.size > MAX_BACKUP) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "a backup is at most $MAX_BACKUP bytes")
        if (!accounts.hasRecovery(
                caller.userId,
            )
        ) {
            throw ApiException(HttpStatus.CONFLICT, "recovery_missing", "upload the recovery material first")
        }
        limit(caller.userId)
        val key = UUID.randomUUID().toString()
        val id = backups.start(caller.userId, key, body.size)
        return BackupStartResponse(id, store.uploadUrl(bucket(), key, body.size, URL_TTL), URL_TTL.toSeconds())
    }

    /** The upload is in: the object must be there at the declared size. Then only the newest three backups stay. */
    @PostMapping("/me/backups/{id}/complete")
    @Transactional
    fun complete(
        @AuthenticationPrincipal caller: Caller,
        @PathVariable id: Long,
    ): BackupView {
        val row = backups.lockOf(caller.userId, id) ?: throw ApiException.notFound("backup")
        row.completedAt?.let { return BackupView(row.id, row.size, it) }
        if (store.size(bucket(), row.objectKey) != row.size) {
            throw ApiException(HttpStatus.CONFLICT, "upload_incomplete", "the object is missing or not the declared size")
        }
        val completedAt = backups.complete(row.id)
        backups.prune(caller.userId)
        return BackupView(row.id, row.size, completedAt)
    }

    @GetMapping("/me/backups")
    fun list(
        @AuthenticationPrincipal reader: Reader,
    ): BackupList = BackupList(backups.completed(reader.userId).map { BackupView(it.id, it.size, it.completedAt!!) })

    @GetMapping("/me/backups/latest")
    fun latest(
        @AuthenticationPrincipal reader: Reader,
    ): BackupDownload {
        val row = backups.completed(reader.userId).firstOrNull() ?: throw ApiException.notFound("completed backup")
        return BackupDownload(row.id, row.size, row.completedAt!!, store.downloadUrl(bucket(), row.objectKey, URL_TTL), URL_TTL.toSeconds())
    }

    private fun bucket(): String = store.buckets.getValue(ObjectStore.BACKUPS)

    /** Ten backups a day per account (api-server.md §9), counted per UTC day. */
    private fun limit(userId: UUID) {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val key = "backups:$userId:$today"
        val count = redis.opsForValue().increment(key) ?: 0
        if (count == 1L) redis.expire(key, Duration.ofDays(2))
        if (count > BACKUPS_PER_DAY) {
            val retryAfter = Duration.between(clock.instant(), today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "at most $BACKUPS_PER_DAY backups a day",
                headers = mapOf(HttpHeaders.RETRY_AFTER to retryAfter.toSeconds().coerceAtLeast(1).toString()),
            )
        }
    }

    private companion object {
        const val WRAPPED_AMK_LENGTH = 72
        const val RECOVERY_ID_LENGTH = 16
        const val PUBLIC_KEY_LENGTH = 32
        const val MAX_BACKUP = 64L * 1024 * 1024
        const val BACKUPS_PER_DAY = 10
        val URL_TTL: Duration = Duration.ofMinutes(15)
        val ROTATE_LABEL = "geunal recovery rotate v1".toByteArray(Charsets.US_ASCII)
    }
}

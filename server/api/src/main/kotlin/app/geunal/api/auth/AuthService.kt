package app.geunal.api.auth

import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRepository
import app.geunal.api.devices.DeviceRow
import app.geunal.api.groups.GroupRepository
import app.geunal.api.realtime.MembershipEvent
import app.geunal.api.realtime.RevokeEvent
import app.geunal.api.realtime.Signals
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * Sign-in, device registration and token rotation (api-server.md §4, protocol §13). An account has one active device:
 * a sign-in from another device gets a replacement ticket instead of tokens, a guest coming back with the recovery
 * code replaces the device at once, and the same key signing in again is the same device.
 */
@Service
class AuthService(
    private val jdbc: JdbcClient,
    private val redis: StringRedisTemplate,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val groups: GroupRepository,
    private val tokens: AccessTokens,
    private val providers: ProviderTokens,
    private val signals: Signals,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /** A one-time value the device signs with its Ed25519 key to prove it holds the key it registers. */
    fun nonce(): String {
        val nonce = randomToken()
        redis.opsForValue().set("nonce:$nonce", "1", properties.nonceTtl)
        return nonce
    }

    /** `POST /auth/kakao` and `POST /auth/apple`. */
    @Transactional
    fun providerLogin(
        provider: Provider,
        request: ProviderLoginRequest,
    ): SignInResult {
        if (!providers.enabled(provider)) throw ProviderTokens.disabled(provider)
        val proof = request.proof()
        checkProof(proof)
        val sub = providers.subject(provider, request.idToken, proof.nonce, proof.signatureKey)
        return signIn(provider, sub, proof) { newAccount(request.ageConfirmedAt, request.displayName) }
    }

    /** `POST /auth/stub`: Kakao's rules with [StubLoginRequest.subject] for the identity the token would assert. */
    @Transactional
    fun stubLogin(request: StubLoginRequest): SignInResult {
        if (request.subject.isBlank() || request.subject.length > MAX_SUBJECT) throw ApiException.invalid("subject is missing or too long")
        val proof = request.proof()
        checkProof(proof)
        return signIn(Provider.STUB, request.subject, proof) { newAccount(OffsetDateTime.now(clock), request.displayName) }
    }

    /** `POST /auth/guest`: always a new account, with no provider to come back by but the recovery code. */
    @Transactional
    fun guestLogin(request: GuestLoginRequest): SignInResult {
        val proof = request.proof()
        val (displayName, ageConfirmedAt) = newAccount(request.ageConfirmedAt, request.displayName)
        checkProof(proof)
        val userId = accounts.create(displayName, ageConfirmedAt)
        val deviceId = register(userId, proof.platform, proof.signatureKey, via = null)
        return registered(Caller(userId, deviceId), newAccount = true)
    }

    /**
     * `POST /auth/recover`: a guest on another device with the recovery code. The recovery signature over the same
     * nonce as the device proof is the whole proof, so the device is registered at once and the previous one revoked.
     * An account linked to Kakao or Apple cannot come back this way, so a code someone saw does not hand over the account.
     */
    @Transactional
    fun recover(request: RecoverRequest): SignInResult {
        if (request.recoveryId.size != RECOVERY_ID_LENGTH) throw ApiException.invalid("recovery_id must be $RECOVERY_ID_LENGTH bytes")
        val proof = request.proof()
        checkProof(proof)
        val key = accounts.recoveryKey(request.recoveryId) ?: throw recoveryInvalid()
        val message = RECOVER_LABEL + proof.nonce.toByteArray(Charsets.US_ASCII)
        if (!Ed25519.verify(key.publicKey, message, request.recoverySignature)) throw recoveryInvalid()
        accounts.lock(key.userId)
        if (accounts.providersOf(key.userId).isNotEmpty()) throw recoveryInvalid()
        val active = devices.activeOf(key.userId)
        val deviceId =
            if (active.holds(proof.signatureKey)) {
                active!!.id
            } else {
                register(key.userId, proof.platform, proof.signatureKey, via = VIA_RECOVERY)
            }
        return registered(Caller(key.userId, deviceId), newAccount = false)
    }

    /**
     * `POST /auth/replace`: the ticket of a `replace_required` sign-in, signed by the key that sign-in proved, makes
     * that key the account's device. A leaked ticket alone gets no tokens; a bad signature leaves the ticket unspent.
     */
    @Transactional
    fun replace(request: ReplaceRequest): SignInResult {
        val hash = sha256(request.ticket)
        val ticket =
            accounts.ticket(hash) ?: throw ApiException(HttpStatus.UNAUTHORIZED, "token_expired", "the ticket is unknown, spent or expired")
        val message = REPLACE_LABEL + request.ticket.toByteArray(Charsets.US_ASCII)
        if (!Ed25519.verify(ticket.signatureKey, message, request.signature)) {
            throw ApiException.invalid("signature does not verify with the ticket's key")
        }
        accounts.spendTicket(hash)
        accounts.lock(ticket.userId)
        val active = devices.activeOf(ticket.userId)
        val deviceId =
            if (active.holds(ticket.signatureKey)) {
                active!!.id.also(devices::markProviderAuth)
            } else {
                register(ticket.userId, ticket.platform, ticket.signatureKey, via = VIA_PROVIDER)
            }
        return registered(Caller(ticket.userId, deviceId), newAccount = false)
    }

    /**
     * `POST /auth/link`: a guest takes on a Kakao or Apple account, bound like a sign-in to the session device's
     * registered key. Linking ends the way back by recovery code alone; recovery material and backups stay.
     */
    @Transactional
    fun link(
        caller: Caller,
        request: LinkRequest,
    ): LinkResponse {
        val provider = Provider.linkable(request.provider) ?: throw ApiException.invalid("provider must be kakao or apple")
        if (!providers.enabled(provider)) throw ProviderTokens.disabled(provider)
        consumeNonce(request.nonce)
        val device = devices.current(caller.deviceId) ?: throw ApiException.notFound("device")
        val sub = providers.subject(provider, request.idToken, request.nonce, device.signatureKey)
        accounts.lockIdentity(provider, sub)
        accounts.lock(caller.userId)
        // A guest's recovery may have replaced this device while the token was checked; it must still be the device.
        if (!devices.isCurrent(caller.deviceId, caller.userId)) throw devices.revokedError(caller.deviceId)
        val existing = accounts.link(provider, sub)
        val signsInWithProvider = accounts.providersOf(caller.userId).isNotEmpty()
        when {
            existing != null && existing.userId != caller.userId -> throw conflict(
                "account_exists",
                "that account belongs to another account",
            )
            existing?.active == true || signsInWithProvider -> throw conflict(
                "already_linked",
                "this account already signs in with a provider",
            )
            // The same provider account after the provider cut the link: linking it again restores it.
            existing != null -> accounts.restore(provider, sub)
            else -> accounts.addLink(caller.userId, provider, sub)
        }
        return LinkResponse(accounts.providersOf(caller.userId))
    }

    /**
     * Trades a refresh token for new tokens. The token is kept as spent until it would have expired; presenting it a
     * second time within that time means two holders, so every session of the device ends (reuse detection,
     * api-server.md §4). The refreshes and reuse checks of one device take turns on the device row, so a replay cannot
     * miss a token that a concurrent refresh is minting.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun refresh(refreshToken: String): TokenResponse {
        val hash = sha256(refreshToken)
        val expired = ApiException(HttpStatus.UNAUTHORIZED, "token_expired", "refresh token is unknown or expired")
        val deviceId =
            jdbc
                .sql("SELECT device_id FROM sessions WHERE refresh_hash = :hash AND expires_at > now()")
                .param("hash", hash)
                .query(UUID::class.java)
                .optional()
                .orElse(null) ?: throw expired
        devices.lock(deviceId)
        val rotated =
            jdbc
                .sql(
                    """
                    UPDATE sessions SET spent_at = now()
                    WHERE refresh_hash = :hash AND spent_at IS NULL AND expires_at > now()
                    RETURNING device_id
                    """,
                ).param("hash", hash)
                .query(UUID::class.java)
                .optional()
        if (rotated.isEmpty) {
            jdbc.sql("DELETE FROM sessions WHERE device_id = :device").param("device", deviceId).update()
            throw expired
        }
        val device = devices.current(deviceId) ?: throw devices.revokedError(deviceId)
        return issue(Caller(device.userId, deviceId))
    }

    /**
     * The account for a provider sign-in, created on first sight, and then this device: registered when the account has
     * none or this is it, otherwise a ticket for [replace]. A cut provider link comes back on the same account.
     */
    private fun signIn(
        provider: Provider,
        sub: String,
        proof: DeviceProof,
        newAccount: () -> Pair<String, OffsetDateTime>,
    ): SignInResult {
        accounts.lockIdentity(provider, sub)
        val link = accounts.link(provider, sub)
        val userId =
            if (link != null) {
                if (!link.active) accounts.restore(provider, sub)
                link.userId
            } else {
                val (displayName, ageConfirmedAt) = newAccount()
                accounts.create(displayName, ageConfirmedAt).also { accounts.addLink(it, provider, sub) }
            }
        accounts.lock(userId)
        val active = devices.activeOf(userId)
        if (active != null && !active.holds(proof.signatureKey)) return replaceRequired(userId, proof, active)
        val deviceId =
            active?.id?.also(devices::markProviderAuth)
                ?: register(userId, proof.platform, proof.signatureKey, via = null, providerAuth = true)
        return registered(Caller(userId, deviceId), newAccount = link == null)
    }

    private fun replaceRequired(
        userId: UUID,
        proof: DeviceProof,
        active: DeviceRow,
    ): ReplaceRequired {
        val ticket = randomToken()
        accounts.addTicket(sha256(ticket), userId, proof.platform, proof.signatureKey, clock.instant().plus(properties.ticketTtl))
        return ReplaceRequired(
            userId = userId,
            ticket = ticket,
            expiresIn = properties.ticketTtl.toSeconds(),
            activeDevice = ActiveDevice(active.platform, active.createdAt),
            recovery = accounts.hasRecovery(userId),
        )
    }

    /**
     * Registers the device and retires the account's previous one. The old device gets `revoke` and, through
     * [via], learns how it was replaced; the other members of the account's groups get `membership`, so a device of
     * theirs removes the stale leaf if the new device does not resync first (protocol §2).
     */
    private fun register(
        userId: UUID,
        platform: String,
        signatureKey: ByteArray,
        via: String?,
        providerAuth: Boolean = via == VIA_PROVIDER,
    ): UUID {
        val revoked = devices.revokeActive(userId, via)
        if (revoked != null) {
            jdbc.sql("DELETE FROM sessions WHERE device_id = :id").param("id", revoked).update()
            signals.send(listOf(revoked), RevokeEvent)
            for (groupId in groups.activeGroupIdsOf(userId)) {
                signals.send(groups.activeDeviceIds(groupId, except = revoked), MembershipEvent(groupId))
            }
        }
        return devices.insert(userId, platform, signatureKey, providerAuth)
    }

    private fun registered(
        caller: Caller,
        newAccount: Boolean,
    ): Registered {
        val tokens = issue(caller)
        return Registered(
            userId = tokens.userId,
            deviceId = tokens.deviceId,
            accessToken = tokens.accessToken,
            expiresIn = tokens.expiresIn,
            refreshToken = tokens.refreshToken,
            newAccount = newAccount,
            recovery = accounts.hasRecovery(caller.userId),
        )
    }

    private fun issue(caller: Caller): TokenResponse {
        val refreshToken = randomToken()
        jdbc
            .sql("INSERT INTO sessions (device_id, refresh_hash, expires_at) VALUES (:device, :hash, :expires)")
            .param("device", caller.deviceId)
            .param("hash", sha256(refreshToken))
            .param("expires", clock.instant().plus(properties.refreshTtl).atOffset(ZoneOffset.UTC))
            .update()
        return TokenResponse(
            userId = caller.userId,
            deviceId = caller.deviceId,
            accessToken = tokens.issue(caller),
            expiresIn = tokens.ttl.toSeconds(),
            refreshToken = refreshToken,
        )
    }

    /** The device proof every sign-in carries: a single-use nonce signed by the key being registered. */
    private fun checkProof(proof: DeviceProof) {
        if (proof.platform !in PLATFORMS) throw ApiException.invalid("platform must be ios or android")
        if (proof.signatureKey.size != Ed25519.PUBLIC_KEY_LENGTH) throw ApiException.invalid("signature_key must be 32 bytes")
        consumeNonce(proof.nonce)
        if (!Ed25519.verify(proof.signatureKey, proof.nonce.toByteArray(Charsets.US_ASCII), proof.signature)) {
            throw ApiException.invalid("signature does not verify over the nonce")
        }
    }

    /** Deleting the key is the atomic single use; a replayed nonce finds nothing to delete. */
    private fun consumeNonce(nonce: String) {
        if (nonce.isEmpty() || nonce.length > MAX_NONCE || redis.delete("nonce:$nonce") != true) {
            throw ApiException.invalid("nonce is unknown or was already used")
        }
    }

    /** The two values only a new account reads (protocol §13), required then. */
    private fun newAccount(
        ageConfirmedAt: OffsetDateTime?,
        displayName: String?,
    ): Pair<String, OffsetDateTime> {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_DISPLAY_NAME) {
            throw ApiException.invalid("a new account needs a display_name of 1 to $MAX_DISPLAY_NAME characters")
        }
        if (ageConfirmedAt == null) throw ApiException.invalid("a new account needs age_confirmed_at")
        return name to ageConfirmedAt
    }

    /** The same key signing in again is the same device: it keeps its id, and with it its MLS credential. */
    private fun DeviceRow?.holds(key: ByteArray): Boolean = this != null && signatureKey.contentEquals(key)

    private fun recoveryInvalid() =
        ApiException(HttpStatus.UNAUTHORIZED, "recovery_invalid", "the recovery code does not open a guest account")

    private fun conflict(
        code: String,
        message: String,
    ) = ApiException(HttpStatus.CONFLICT, code, message)

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    companion object {
        const val VIA_PROVIDER = "provider"
        const val VIA_RECOVERY = "recovery"
        const val RECOVERY_ID_LENGTH = 16
        private const val MAX_SUBJECT = 200
        private const val MAX_DISPLAY_NAME = 40
        private const val MAX_NONCE = 128
        private val PLATFORMS = setOf("ios", "android")
        val RECOVER_LABEL = "geunal recover v1".toByteArray(Charsets.US_ASCII)
        val REPLACE_LABEL = "geunal replace v1".toByteArray(Charsets.US_ASCII)

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
    }
}

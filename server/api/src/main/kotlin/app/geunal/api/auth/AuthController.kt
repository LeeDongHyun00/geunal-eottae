package app.geunal.api.auth

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class NonceResponse(
    val nonce: String,
)

/** What every sign-in proves (protocol §13): the device holds the Ed25519 key it registers. */
data class DeviceProof(
    val platform: String,
    val signatureKey: ByteArray,
    val nonce: String,
    val signature: ByteArray,
)

/**
 * Signs in without Kakao or Apple (`GEUNAL_AUTH_STUB`). [subject] stands for the identity a provider would assert; the
 * device proof is the real one, so the core exercises the same registration path it will use against Kakao and Apple.
 */
data class StubLoginRequest(
    val subject: String,
    val displayName: String,
    val platform: String,
    val signatureKey: ByteArray,
    val nonce: String,
    val signature: ByteArray,
) {
    fun proof() = DeviceProof(platform, signatureKey, nonce, signature)
}

/** `POST /auth/kakao` and `POST /auth/apple`; [ageConfirmedAt] and [displayName] are read only for a new account. */
data class ProviderLoginRequest(
    val platform: String,
    val signatureKey: ByteArray,
    val nonce: String,
    val signature: ByteArray,
    val idToken: String,
    val ageConfirmedAt: OffsetDateTime? = null,
    val displayName: String? = null,
) {
    fun proof() = DeviceProof(platform, signatureKey, nonce, signature)
}

data class GuestLoginRequest(
    val platform: String,
    val signatureKey: ByteArray,
    val nonce: String,
    val signature: ByteArray,
    val ageConfirmedAt: OffsetDateTime,
    val displayName: String,
) {
    fun proof() = DeviceProof(platform, signatureKey, nonce, signature)
}

data class RecoverRequest(
    val platform: String,
    val signatureKey: ByteArray,
    val nonce: String,
    val signature: ByteArray,
    val recoveryId: ByteArray,
    val recoverySignature: ByteArray,
) {
    fun proof() = DeviceProof(platform, signatureKey, nonce, signature)
}

data class ReplaceRequest(
    val ticket: String,
    val signature: ByteArray,
)

data class LinkRequest(
    val provider: String,
    val idToken: String,
    val nonce: String,
)

data class LinkResponse(
    val providers: List<String>,
)

data class RefreshRequest(
    val refreshToken: String,
)

data class TokenResponse(
    val userId: UUID,
    val deviceId: UUID,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
)

/** What a sign-in answers (protocol §13 "로그인 결과"): 201 [Registered] or 200 [ReplaceRequired]. */
sealed interface SignInResult

/** This device is the account's active device now. [recovery]: the server holds the account's recovery material. */
@JsonPropertyOrder("result")
data class Registered(
    val userId: UUID,
    val deviceId: UUID,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val newAccount: Boolean,
    val recovery: Boolean,
) : SignInResult {
    val result: String get() = "registered"
}

/** Another device is active and nothing was registered; [ticket] goes to `POST /auth/replace`. */
@JsonPropertyOrder("result")
data class ReplaceRequired(
    val userId: UUID,
    val ticket: String,
    val expiresIn: Long,
    val activeDevice: ActiveDevice,
    val recovery: Boolean,
) : SignInResult {
    val result: String get() = "replace_required"
}

data class ActiveDevice(
    val platform: String,
    val createdAt: Instant,
)

@RestController
class AuthController(
    private val auth: AuthService,
) {
    @GetMapping("/auth/nonce")
    fun nonce(): NonceResponse = NonceResponse(auth.nonce())

    @PostMapping("/auth/kakao")
    fun kakao(
        @RequestBody body: ProviderLoginRequest,
    ): ResponseEntity<Any> = answer(auth.providerLogin(Provider.KAKAO, body))

    @PostMapping("/auth/apple")
    fun apple(
        @RequestBody body: ProviderLoginRequest,
    ): ResponseEntity<Any> = answer(auth.providerLogin(Provider.APPLE, body))

    @PostMapping("/auth/guest")
    fun guest(
        @RequestBody body: GuestLoginRequest,
    ): ResponseEntity<Any> = answer(auth.guestLogin(body))

    @PostMapping("/auth/recover")
    fun recover(
        @RequestBody body: RecoverRequest,
    ): ResponseEntity<Any> = answer(auth.recover(body))

    @PostMapping("/auth/replace")
    fun replace(
        @RequestBody body: ReplaceRequest,
    ): ResponseEntity<Any> = answer(auth.replace(body))

    /** The one `/auth` call made with a session: a guest links its account to Kakao or Apple. */
    @PostMapping("/auth/link")
    fun link(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody body: LinkRequest,
    ): LinkResponse = auth.link(caller, body)

    @PostMapping("/auth/refresh")
    fun refresh(
        @RequestBody body: RefreshRequest,
    ): TokenResponse = auth.refresh(body.refreshToken)

    companion object {
        /** Typed as Any so Jackson writes the concrete result, not the interface's (empty) set of properties. */
        fun answer(result: SignInResult): ResponseEntity<Any> =
            when (result) {
                is Registered -> ResponseEntity.status(HttpStatus.CREATED).body(result)
                is ReplaceRequired -> ResponseEntity.ok(result)
            }
    }
}

/** Exists only with `geunal.auth.stub=true`; otherwise the path is a plain 404, whatever the body. */
@RestController
@ConditionalOnProperty("geunal.auth.stub", havingValue = "true")
class StubAuthController(
    private val auth: AuthService,
) {
    @PostMapping("/auth/stub")
    fun stub(
        @RequestBody body: StubLoginRequest,
    ): ResponseEntity<Any> = AuthController.answer(auth.stubLogin(body))
}

package app.geunal.api.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class NonceResponse(
    val nonce: String,
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

@RestController
class AuthController(
    private val auth: AuthService,
) {
    @GetMapping("/auth/nonce")
    fun nonce(): NonceResponse = NonceResponse(auth.nonce())

    @PostMapping("/auth/refresh")
    fun refresh(
        @RequestBody body: RefreshRequest,
    ): TokenResponse = auth.refresh(body.refreshToken)
}

/** Exists only with `geunal.auth.stub=true`; otherwise the path is a plain 404, whatever the body. */
@RestController
@ConditionalOnProperty("geunal.auth.stub", havingValue = "true")
class StubAuthController(
    private val auth: AuthService,
) {
    @PostMapping("/auth/stub")
    @ResponseStatus(HttpStatus.CREATED)
    fun stub(
        @RequestBody body: StubLoginRequest,
    ): TokenResponse = auth.stubLogin(body)
}

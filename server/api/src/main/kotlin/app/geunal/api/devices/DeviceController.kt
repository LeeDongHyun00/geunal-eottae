package app.geunal.api.devices

import app.geunal.api.auth.AccountRepository
import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import app.geunal.api.mls.MlsHeader
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** [providers]: `kakao`, `apple` the account signs in with, empty for a guest; [recovery]: recovery material is stored. */
data class MeResponse(
    val userId: UUID,
    val displayName: String,
    val device: DeviceView,
    val keyPackages: Int,
    val providers: List<String>,
    val recovery: Boolean,
)

data class DeviceView(
    val id: UUID,
    val platform: String,
    val createdAt: Instant,
)

data class PushTokenRequest(
    val token: String?,
)

data class KeyPackagesRequest(
    val keyPackages: List<ByteArray>,
)

data class KeyPackagesResponse(
    val count: Int,
)

data class UserDevicesResponse(
    val devices: List<UserDevice>,
)

data class UserDevice(
    val id: UUID,
    val platform: String,
    val signatureKey: ByteArray,
)

data class ClaimResponse(
    val keyPackage: ByteArray,
)

/** The account's own device and other accounts' devices: what a member checks before adding one (protocol §2). */
@RestController
class DeviceController(
    private val devices: DeviceRepository,
    private val accounts: AccountRepository,
    private val jdbc: JdbcClient,
) {
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal caller: Caller,
    ): MeResponse {
        val device = devices.current(caller.deviceId) ?: throw ApiException.notFound("device")
        val displayName =
            jdbc
                .sql("SELECT display_name FROM users WHERE id = :id")
                .param("id", caller.userId)
                .query(String::class.java)
                .single()
        return MeResponse(
            userId = caller.userId,
            displayName = displayName,
            device = DeviceView(device.id, device.platform, device.createdAt),
            keyPackages = devices.countKeyPackages(caller.deviceId),
            providers = accounts.providersOf(caller.userId),
            recovery = accounts.hasRecovery(caller.userId),
        )
    }

    @PutMapping("/me/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pushToken(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody body: PushTokenRequest,
    ) {
        val token = body.token?.takeIf { it.isNotBlank() }
        if (token != null && token.length > MAX_PUSH_TOKEN) throw ApiException.invalid("push token is too long")
        devices.setPushToken(caller.deviceId, token)
    }

    @PostMapping("/me/key-packages")
    @Transactional
    fun addKeyPackages(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody body: KeyPackagesRequest,
    ): KeyPackagesResponse {
        if (body.keyPackages.isEmpty() || body.keyPackages.size > MAX_KEY_PACKAGES_PER_UPLOAD) {
            throw ApiException.invalid("send between 1 and $MAX_KEY_PACKAGES_PER_UPLOAD key packages")
        }
        for (data in body.keyPackages) {
            if (data.size > MAX_KEY_PACKAGE_BYTES) throw ApiException.tooLarge("a key package", MAX_KEY_PACKAGE_BYTES)
            MlsHeader.parse(data).expectWireFormat(MlsHeader.KEY_PACKAGE)
        }
        if (devices.countKeyPackages(caller.deviceId) + body.keyPackages.size > MAX_KEY_PACKAGES_STORED) {
            throw ApiException.invalid("at most $MAX_KEY_PACKAGES_STORED key packages are kept per device")
        }
        devices.addKeyPackages(caller.deviceId, body.keyPackages)
        return KeyPackagesResponse(devices.countKeyPackages(caller.deviceId))
    }

    @GetMapping("/users/{id}/devices")
    fun userDevices(
        @PathVariable id: UUID,
    ): UserDevicesResponse {
        val active = devices.activeOf(id)
        return UserDevicesResponse(listOfNotNull(active).map { UserDevice(it.id, it.platform, it.signatureKey) })
    }

    @PostMapping("/devices/{id}/key-packages/claim")
    @Transactional
    fun claim(
        @PathVariable id: UUID,
    ): ClaimResponse {
        devices.current(id) ?: throw ApiException.notFound("device")
        val data = devices.claimKeyPackage(id) ?: throw ApiException.notFound("an unused key package of this device")
        return ClaimResponse(data)
    }

    private companion object {
        const val MAX_PUSH_TOKEN = 4096
        const val MAX_KEY_PACKAGE_BYTES = 1024
        const val MAX_KEY_PACKAGES_PER_UPLOAD = 100
        const val MAX_KEY_PACKAGES_STORED = 200
    }
}

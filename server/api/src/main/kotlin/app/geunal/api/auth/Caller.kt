package app.geunal.api.auth

import org.springframework.security.authentication.AbstractAuthenticationToken
import java.util.UUID

/** Whoever may read an account's recovery material and backups: its signed-in device, or a replacement ticket. */
sealed interface Reader {
    val userId: UUID
}

/** The signed-in account and the device that holds the token: the `sub` and `did` claims of the access JWT. */
data class Caller(
    override val userId: UUID,
    val deviceId: UUID,
) : Reader

/**
 * The bearer of a replacement ticket (protocol §13): an account still choosing between restoring and starting fresh on
 * a device that is not registered yet. [DeviceAuthFilter] lets it reach only [PATHS], and only to read.
 */
data class TicketBearer(
    override val userId: UUID,
) : Reader {
    companion object {
        val PATHS = setOf("/me/recovery", "/me/backups", "/me/backups/latest")
    }
}

class CallerAuthentication(
    private val principal: Reader,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): Reader = principal
}

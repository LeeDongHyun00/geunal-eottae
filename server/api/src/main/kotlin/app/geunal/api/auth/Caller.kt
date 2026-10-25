package app.geunal.api.auth

import org.springframework.security.authentication.AbstractAuthenticationToken
import java.util.UUID

/** The signed-in account and the device that holds the token: the `sub` and `did` claims of the access JWT. */
data class Caller(
    val userId: UUID,
    val deviceId: UUID,
)

class CallerAuthentication(
    private val caller: Caller,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): Caller = caller
}

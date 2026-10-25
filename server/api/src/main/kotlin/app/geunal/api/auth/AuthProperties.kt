package app.geunal.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `geunal.auth.*` in application.yml; the environment overrides them as `GEUNAL_AUTH_*`. */
@ConfigurationProperties("geunal.auth")
data class AuthProperties(
    val stub: Boolean = false,
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(30),
    val nonceTtl: Duration = Duration.ofMinutes(5),
    val jwt: Jwt = Jwt(),
) {
    data class Jwt(
        val keys: String = "",
    )
}

package app.geunal.api.auth

import app.geunal.api.devices.DeviceRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Health, the JWKS, the App Links file and the sign-in endpoints are public; everything else needs a signed-in device,
 * carried as an access JWT that [DeviceAuthFilter] checks (or, for three reads, a replacement ticket). A missing or
 * stale token gets 401 in the protocol §11 body that tells a device to refresh. `POST /auth/link` is the one `/auth`
 * call that needs the session.
 */
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        tokens: AccessTokens,
        devices: DeviceRepository,
        accounts: AccountRepository,
        mapper: JsonMapper,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/.well-known/jwks.json", permitAll)
                authorize("/.well-known/assetlinks.json", permitAll)
                authorize("/auth/link", authenticated)
                authorize("/auth/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling { authenticationEntryPoint = tokenExpired }
            addFilterBefore<AnonymousAuthenticationFilter>(DeviceAuthFilter(tokens, devices, accounts, mapper))
        }
        return http.build()
    }

    private val tokenExpired =
        AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"error":"token_expired","message":"sign in or refresh the access token"}""")
        }
}

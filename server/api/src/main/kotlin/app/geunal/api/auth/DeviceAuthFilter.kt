package app.geunal.api.auth

import app.geunal.api.common.ErrorBody
import app.geunal.api.devices.DeviceRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Turns a `Bearer` access JWT into the [Caller]. A token that does not verify leaves the request anonymous, and the
 * entry point answers 401 `token_expired`. A verified token for a device that has since been revoked gets 403
 * `device_revoked` here, so a replaced phone learns to sign out on its very next request (protocol §11).
 */
class DeviceAuthFilter(
    private val tokens: AccessTokens,
    private val devices: DeviceRepository,
    private val mapper: JsonMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith(BEARER, ignoreCase = true)) return chain.doFilter(request, response)
        val caller = tokens.verify(header.substring(BEARER.length).trim()) ?: return chain.doFilter(request, response)
        if (!devices.isCurrent(caller.deviceId, caller.userId)) {
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(mapper.writeValueAsString(ErrorBody("device_revoked", "this device was replaced; sign out")))
            return
        }
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = CallerAuthentication(caller) }
        SecurityContextHolder.setContext(context)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

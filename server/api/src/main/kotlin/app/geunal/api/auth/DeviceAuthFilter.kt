package app.geunal.api.auth

import app.geunal.api.common.ApiException
import app.geunal.api.common.ErrorHandler
import app.geunal.api.devices.DeviceRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Turns a `Bearer` access JWT into the [Caller]. A token that does not verify leaves the request anonymous, and the
 * entry point answers 401 `token_expired`. A verified token for a device that has since been revoked gets 403
 * `device_revoked` here, so a replaced phone learns to sign out on its very next request (protocol §11).
 *
 * A live replacement ticket in the same header is a [TicketBearer]: it may read the account's recovery material and
 * backups and gets 403 `replace_required` everywhere else (protocol §13). A ticket that is spent or expired is just an
 * unknown token.
 */
class DeviceAuthFilter(
    private val tokens: AccessTokens,
    private val devices: DeviceRepository,
    private val accounts: AccountRepository,
    private val mapper: JsonMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith(BEARER, ignoreCase = true)) return chain.doFilter(request, response)
        val token = header.substring(BEARER.length).trim()
        val principal =
            tokens.verify(token)?.let { caller ->
                if (!devices.isCurrent(caller.deviceId, caller.userId)) return reject(response, devices.revokedError(caller.deviceId))
                caller
            } ?: ticketBearer(token)?.let { bearer ->
                if (request.method != HttpMethod.GET.name() || request.requestURI !in TicketBearer.PATHS) {
                    return reject(response, ApiException(HttpStatus.FORBIDDEN, "replace_required", "finish the device replacement first"))
                }
                bearer
            } ?: return chain.doFilter(request, response)
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = CallerAuthentication(principal) }
        SecurityContextHolder.setContext(context)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    /** Tickets are 43 base64url characters; anything else is not worth a database lookup. */
    private fun ticketBearer(token: String): TicketBearer? {
        if (token.length != TICKET_LENGTH ||
            !token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        ) {
            return null
        }
        return accounts.ticketHolder(AuthService.sha256(token))?.let(::TicketBearer)
    }

    private fun reject(
        response: HttpServletResponse,
        error: ApiException,
    ) {
        response.status = error.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(mapper.writeValueAsString(ErrorHandler.body(error)))
    }

    private companion object {
        const val BEARER = "Bearer "
        const val TICKET_LENGTH = 43
    }
}

package app.geunal.api.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.ServletRequestPathUtils

/**
 * One line per request: method, the matched route pattern, status and milliseconds (api-server.md §10). The pattern,
 * not the path, so no ID, nonce or token in a path or query reaches the log; no address, header or body either.
 * Health checks are left out, as they come every few seconds.
 *
 * It runs ahead of Spring Security so that a request the security chain turns away (an expired session, a revoked
 * device, a replacement ticket) is logged too, and so that the time includes authentication.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
class RequestLog(
    @param:Qualifier("requestMappingHandlerMapping") private val mappings: ObjectProvider<RequestMappingHandlerMapping>,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI.startsWith("/actuator")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            val millis = (System.nanoTime() - started) / 1_000_000
            log.info("{} {} {} {}ms", request.method, pattern(request), response.status, millis)
        }
    }

    /** The route the dispatcher matched, or, for a request turned away before it got there, the route it would have. */
    private fun pattern(request: HttpServletRequest): String {
        (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)?.let { return it }
        return try {
            if (!ServletRequestPathUtils.hasParsedRequestPath(request)) ServletRequestPathUtils.parseAndCache(request)
            mappings.ifAvailable?.getHandler(request)
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        } catch (_: Exception) {
            null
        } ?: UNMATCHED
    }

    private companion object {
        const val UNMATCHED = "(unmatched)"
        val log = LoggerFactory.getLogger(RequestLog::class.java)
    }
}

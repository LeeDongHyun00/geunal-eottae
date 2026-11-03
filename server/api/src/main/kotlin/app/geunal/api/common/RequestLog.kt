package app.geunal.api.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

/**
 * One line per request: method, the matched route pattern, status and milliseconds (api-server.md §10). The pattern,
 * not the path, so no ID, nonce or token in a path or query reaches the log; no address, header or body either.
 * Health checks are left out, as they come every few seconds.
 */
@Component
class RequestLog : OncePerRequestFilter() {
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
            val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "(unmatched)"
            val millis = (System.nanoTime() - started) / 1_000_000
            log.info("{} {} {} {}ms", request.method, pattern, response.status, millis)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RequestLog::class.java)
    }
}

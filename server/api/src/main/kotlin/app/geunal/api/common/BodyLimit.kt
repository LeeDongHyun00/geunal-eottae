package app.geunal.api.common

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.core.StreamReadConstraints
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Caps every request body before anything reads it: a body larger than its route needs is 413 `too_large`. A declared
 * `Content-Length` over the cap is refused unread; a body without one (chunked) is read only up to the cap. JSON bodies
 * are then handed on from that bounded copy, so no single request can hold more memory than its cap. The caps sit a
 * little above the largest valid body of each route (base64 makes bytes a third larger).
 *
 * Form posts (Apple's web sign-in callback) are left to Tomcat, which parses them from its own stream and caps them
 * with `server.tomcat.max-http-form-post-size`; the declared length is still checked here. Spring's form-content
 * filter, which would read a form PUT, PATCH or DELETE whole, is off (`spring.mvc.formcontent.filter.enabled`).
 *
 * It runs first of all filters, before anything else could read the body; only [RequestLog] wraps it, to log the 413.
 */
@Component
@Order(BodyLimit.ORDER)
class BodyLimit(
    private val mapper: JsonMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val cap = capOf(request)
        if (request.contentLengthLong > cap) return refuse(response, cap)
        val form = request.contentType?.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE) == true
        if (form || !hasBody(request)) return chain.doFilter(request, response)
        val body = request.inputStream.readNBytes(cap + 1)
        if (body.size > cap) return refuse(response, cap)
        chain.doFilter(Buffered(request, body), response)
    }

    private fun hasBody(request: HttpServletRequest): Boolean =
        request.contentLengthLong > 0 || request.getHeader("Transfer-Encoding") != null

    private fun refuse(
        response: HttpServletResponse,
        cap: Int,
    ) {
        val error = ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "the request body is larger than $cap bytes")
        response.status = error.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        // The rest of an oversized body is not worth reading; the connection goes with it.
        response.setHeader("Connection", "close")
        response.writer.write(mapper.writeValueAsString(ErrorHandler.body(error)))
    }

    /** The request with its body read already: the same bytes, from memory. */
    private class Buffered(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()

        override fun getInputStream(): ServletInputStream {
            val bytes = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = bytes.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = bytes.read(b, off, len)

                override fun isFinished(): Boolean = bytes.available() == 0

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
            }
        }

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    }

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 1

        const val KIB = 1024

        /** Most bodies are a few fields and at most a 16 KiB state blob. */
        const val DEFAULT = 32 * KIB

        /** Routes whose valid bodies are larger, by path pattern (POST and PUT). */
        val CAPS =
            listOf(
                // commit, group_info and welcome, up to 64 KiB each.
                Regex("/groups/[^/]+/commits") to 320 * KIB,
                // A 64 KiB message or GroupInfo.
                Regex("/groups/[^/]+/(messages|group-info)") to 96 * KIB,
                // 100 key packages of up to 1 KiB.
                Regex("/me/key-packages") to 160 * KIB,
            )

        /** The largest cap of all: what the JSON parser accepts as one string. */
        val LARGEST = CAPS.maxOf { it.second }

        fun capOf(request: HttpServletRequest): Int {
            val path = request.requestURI.removePrefix(request.contextPath)
            return CAPS.firstOrNull { (route, _) -> route.matches(path) }?.second ?: DEFAULT
        }
    }
}

/** No JSON string longer than the largest body could be; Jackson's own default allows 20 million characters. */
@Configuration
class JsonLimits {
    @Bean
    fun jsonStringLimit() =
        JsonFactoryBuilderCustomizer { builder ->
            builder.streamReadConstraints(StreamReadConstraints.builder().maxStringLength(BodyLimit.LARGEST).build())
        }
}

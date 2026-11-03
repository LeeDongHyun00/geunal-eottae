package app.geunal.api.auth

import app.geunal.api.common.HourlyLimit
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 30 sign-ins and nonces an hour per client address (api-server.md §9): what someone guessing recovery codes or
 * spraying provider tokens can try. Refreshing, linking and Apple's callback are not counted; they need a session or
 * come from Apple. The address is the device's as Caddy forwards it.
 */
@Configuration
class SignInLimit(
    private val limits: HourlyLimit,
    private val properties: AuthProperties,
) : WebMvcConfigurer,
    HandlerInterceptor {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns(NONCE, *SIGN_INS.toTypedArray())
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        // The registered patterns match the decoded path, so a percent-encoded spelling is counted like the plain one;
        // the raw URI is never compared. The one handler under these paths that is no sign-in is the App Link page.
        val page = handler is HandlerMethod && handler.beanType == AppLinksController::class.java
        if (!page) limits.check("sign-in", request.remoteAddr, properties.signInsPerHour)
        return true
    }

    private companion object {
        const val NONCE = "/auth/nonce"
        val SIGN_INS = setOf("/auth/kakao", "/auth/apple", "/auth/guest", "/auth/recover", "/auth/replace", "/auth/stub")
    }
}

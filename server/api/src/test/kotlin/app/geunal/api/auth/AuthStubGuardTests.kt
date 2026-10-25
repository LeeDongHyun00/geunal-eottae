package app.geunal.api.auth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class AuthStubGuardTests {
    private fun environment(vararg profiles: String) = MockEnvironment().apply { setActiveProfiles(*profiles) }

    @Test
    fun `refuses to start with the auth stub under the prod profile`() {
        assertThrows<IllegalStateException> { AuthStubGuard(environment("prod"), stub = true) }
    }

    @Test
    fun `starts with the auth stub outside prod and without it in prod`() {
        assertDoesNotThrow { AuthStubGuard(environment("local"), stub = true) }
        assertDoesNotThrow { AuthStubGuard(environment("prod"), stub = false) }
    }
}

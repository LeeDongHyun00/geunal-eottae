package app.geunal.api.common

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import java.util.Base64
import java.util.UUID

/** api-server.md §10: no request body, ciphertext, token or personal information reaches a log, at any level. */
@ExtendWith(OutputCaptureExtension::class)
class LogHygieneTests : ApiTest() {
    private val logging = LoggingSystem.get(javaClass.classLoader)

    // The root level is the one an operator would turn down; the libraries pinned in application.yml keep theirs.
    // Logging is global to the JVM, so the level goes back before the next test class.
    @BeforeEach
    fun traceEverything() = logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE)

    @AfterEach
    fun back() = logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO)

    @Test
    fun `logs no request body, ciphertext or token at any level`(output: CapturedOutput) {
        val name = "로그-${UUID.randomUUID().toString().take(8)}"
        val owner = Client(api, name).also { it.login() }
        val groupId = groupWith(owner)
        val ciphertext = Mls.privateMessage(groupId, 0, size = 0) + "secret-chat-${UUID.randomUUID()}".toByteArray()
        val state = "secret-state-${UUID.randomUUID()}".toByteArray()

        owner.post("/groups/$groupId/messages", mapOf("epoch" to 0, "message" to ciphertext, "alert" to "none")).expect(201)
        owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 0, "ciphertext" to state)).expect(200)
        owner.get("/groups/$groupId/log").expect(200)
        val oldRefresh = owner.refreshToken
        val refreshed = api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to oldRefresh)).expect(200)

        val secrets =
            listOf(
                owner.token,
                oldRefresh,
                refreshed.string("access_token"),
                refreshed.string("refresh_token"),
                name,
                Base64.getEncoder().encodeToString(ciphertext),
                String(ciphertext.copyOfRange(ciphertext.size - 20, ciphertext.size)),
                Base64.getEncoder().encodeToString(state),
                String(state),
            )
        // The test's own HTTP client logs what it sends from the test thread; only the server's lines count.
        val server =
            output.out
                .lines()
                .filterNot { it.contains("\"name\":\"Test worker\"") }
                .joinToString("\n")
        assertThat(server).describedAs("the TRACE output ran").contains("\"level\":\"TRACE\"")
        for (secret in secrets) assertThat(server).doesNotContain(secret)
    }
}

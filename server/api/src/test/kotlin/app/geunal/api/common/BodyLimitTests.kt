package app.geunal.api.common

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.Socket
import java.time.Duration

/** Every request body has a cap that holds before the body is read (api-server.md §9). */
class BodyLimitTests : ApiTest() {
    /** Sends [head] and then [body] as is on a fresh connection, and returns the response's status line and body. */
    private fun raw(
        head: String,
        body: ByteArray = ByteArray(0),
    ): Pair<String, String> =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = Duration.ofSeconds(10).toMillis().toInt()
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            // Read the head and as much body as it declares, not to the end: the server may hold the connection open
            // while it drains what the client said it would send.
            val input = socket.getInputStream().buffered()
            val head = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            val length =
                head.firstNotNullOfOrNull {
                    Regex("(?i)content-length: *(\\d+)")
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }
            val content = if (length != null) String(input.readNBytes(length), Charsets.UTF_8) else readChunked(input)
            head.first() to content
        }

    private fun readLine(input: java.io.InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    private fun readChunked(input: java.io.InputStream): String {
        val body = StringBuilder()
        while (true) {
            val size = readLine(input).trim().toInt(16)
            if (size == 0) return body.toString()
            body.append(String(input.readNBytes(size), Charsets.UTF_8))
            readLine(input)
        }
    }

    @Test
    fun `rejects a body over the limit before reading it`() {
        // Declares 50 MB and sends none of it: an answer can only come from the declared length.
        val (status, body) =
            raw(
                "POST /auth/refresh HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                    "Content-Length: 50000000\r\nConnection: close\r\n\r\n",
            )

        assertThat(status).contains(" 413 ")
        assertThat(body).contains("\"error\":\"too_large\"")
    }

    @Test
    fun `rejects a form body over the limit before anything reads it`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val (status, body) =
                raw(
                    "$method /me/push-token HTTP/1.1\r\nHost: localhost\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 50000000\r\nConnection: close\r\n\r\n",
                )

            assertThat(status).describedAs(method).contains(" 413 ")
            assertThat(body).contains("\"error\":\"too_large\"")
        }
    }

    @Test
    fun `rejects a chunked body once it passes the limit`() {
        val chunk = ByteArray(16 * 1024) { 'a'.code.toByte() }
        val chunks =
            (1..4).fold(ByteArray(0)) { all, _ -> all + "4000\r\n".toByteArray() + chunk + "\r\n".toByteArray() } +
                "0\r\n\r\n".toByteArray()

        val (status, body) =
            raw(
                "POST /auth/refresh HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
                chunks,
            )

        assertThat(status).contains(" 413 ")
        assertThat(body).contains("\"error\":\"too_large\"")
    }

    @Test
    fun `a chunked body under the limit reaches the endpoint`() {
        val json = """{"refresh_token":"not-a-token"}""".toByteArray()
        val chunked = "%x\r\n".format(json.size).toByteArray() + json + "\r\n0\r\n\r\n".toByteArray()

        val (status, body) =
            raw(
                "POST /auth/refresh HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
                chunked,
            )

        assertThat(status).contains(" 401 ")
        assertThat(body).doesNotContain("too_large")
    }

    @Test
    fun `the largest valid commit still fits its route`() {
        val owner = signIn()
        val groupId = groupWith(owner)

        owner
            .post(
                "/groups/$groupId/commits",
                mapOf(
                    "epoch" to 0,
                    "commit" to Mls.commit(groupId, 0, size = 65536),
                    "group_info" to Mls.groupInfo(groupId, 1, size = 65536),
                ),
            ).expect(201)
    }

    @Test
    fun `a route without large bodies keeps the default cap`() {
        val owner = signIn()

        owner.post("/me/alpha-stats", mapOf("stats" to emptyList<Any>(), "padding" to "a".repeat(40 * 1024))).expectError(413, "too_large")
    }
}

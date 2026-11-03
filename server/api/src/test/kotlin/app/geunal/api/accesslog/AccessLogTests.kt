package app.geunal.api.accesslog

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.RecoveryCode
import app.geunal.api.support.TestStack
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.GZIPInputStream

/** The access log of api-server.md §10: every sign-in and refresh with the client's address, shipped to its bucket. */
class AccessLogTests : ApiTest() {
    @Autowired
    lateinit var accessLog: AccessLog

    private fun waiting(deviceId: UUID): List<Pair<String, String>> =
        jdbc
            .sql("SELECT event, ip FROM access_log_outbox WHERE device_id = :id ORDER BY id")
            .param("id", deviceId)
            .query { rs, _ -> rs.getString("event") to rs.getString("ip") }
            .list()

    private fun forwardedFor(address: String) = mapOf("X-Forwarded-For" to address)

    /** The lines of the access-logs object [key]. */
    private fun lines(key: String): List<JsonNode> {
        val gzipped = TestStack.s3Client.getObjectAsBytes { it.bucket(TestStack.ACCESS_LOGS_BUCKET).key(key) }.asByteArray()
        return GZIPInputStream(gzipped.inputStream())
            .bufferedReader()
            .readLines()
            .map { api.mapper.readTree(it) }
    }

    /** Every line of every object in the access-logs bucket. */
    private fun shipped(): List<JsonNode> =
        TestStack.s3Client
            .listObjectsV2Paginator { it.bucket(TestStack.ACCESS_LOGS_BUCKET) }
            .contents()
            .flatMap { lines(it.key()) }

    @Test
    fun `a sign-in and a refresh are logged with the address the proxy forwarded`() {
        val device = Client(api, "access-${UUID.randomUUID()}")
        val body = device.loginRequest(device.nonce())
        device.adopt(api.send(HttpMethod.POST, "/auth/stub", body = body, headers = forwardedFor("203.0.113.7")).expect(201))
        api
            .send(
                HttpMethod.POST,
                "/auth/refresh",
                body = mapOf("refresh_token" to device.refreshToken),
                headers = forwardedFor("2001:db8::1"),
            ).expect(200)

        assertThat(waiting(device.deviceId)).containsExactly("sign_in" to "203.0.113.7", "refresh" to "2001:db8::1")
    }

    @Test
    fun `a sign-in told to replace the active device gets no tokens and leaves no entry`() {
        val subject = "access-replace-${UUID.randomUUID()}"
        val first = signIn(subject)

        Client(api, subject).stubSignIn().expect(200)

        val entries =
            jdbc
                .sql("SELECT count(*) FROM access_log_outbox WHERE user_id = :id")
                .param("id", first.userId)
                .query(Int::class.java)
                .single()
        assertThat(entries).isEqualTo(1)
    }

    @Test
    fun `shipping writes gzipped JSON lines to the bucket and empties the outbox`() {
        val device = Client(api, "access-ship-${UUID.randomUUID()}")
        device.adopt(
            api
                .send(
                    HttpMethod.POST,
                    "/auth/stub",
                    body = device.loginRequest(device.nonce()),
                    headers = forwardedFor("198.51.100.4"),
                ).expect(201),
        )

        accessLog.ship()

        assertThat(waiting(device.deviceId)).isEmpty()
        val line = shipped().single { it.path("device_id").stringValue() == device.deviceId.toString() }
        assertThat(line.path("event").stringValue()).isEqualTo("sign_in")
        assertThat(line.path("user_id").stringValue()).isEqualTo(device.userId.toString())
        assertThat(line.path("ip").stringValue()).isEqualTo("198.51.100.4")
        assertThat(OffsetDateTime.parse(line.path("at").stringValue())).isAfter(OffsetDateTime.now().minusMinutes(5))
    }

    @Test
    fun `a batch cut before a failed round goes again with the same rows under the same name`() {
        val earlier = signIn()
        // As if an earlier round had cut this batch and then failed to upload it or to delete its rows.
        val cut = "2026/11/03/cut-${UUID.randomUUID()}.jsonl.gz"
        jdbc
            .sql("UPDATE access_log_outbox SET batch = :batch WHERE device_id = :id")
            .param("batch", cut)
            .param("id", earlier.deviceId)
            .update()
        val later = signIn()

        accessLog.ship()

        assertThat(lines(cut).map { it.path("device_id").stringValue() }).containsExactly(earlier.deviceId.toString())
        assertThat(shipped().count { it.path("device_id").stringValue() == earlier.deviceId.toString() }).isEqualTo(1)
        assertThat(shipped().count { it.path("device_id").stringValue() == later.deviceId.toString() }).isEqualTo(1)
        assertThat(waiting(earlier.deviceId) + waiting(later.deviceId)).isEmpty()
    }

    @Test
    fun `deleting the account leaves its access log`() {
        val device = Client(api, "access-delete-${UUID.randomUUID()}")
        val guest = device.proof() + mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "기록")
        device.adopt(api.send(HttpMethod.POST, "/auth/guest", body = guest, headers = forwardedFor("192.0.2.10")).expect(201))
        val code = RecoveryCode().also { device.put("/me/recovery", it.upload()).expect(204) }
        val nonce = device.nonce()

        api
            .send(
                HttpMethod.DELETE,
                "/me",
                device.token,
                mapOf(
                    "nonce" to nonce,
                    "recovery_signature" to code.sign("geunal delete v1", nonce),
                ),
            ).expect(204)

        assertThat(waiting(device.deviceId)).containsExactly("sign_in" to "192.0.2.10")
    }

    @Test
    fun `entries that never reached the bucket go after three months`() {
        val device = signIn()
        jdbc.sql("UPDATE access_log_outbox SET at = now() - interval '93 days' WHERE device_id = :id").param("id", device.deviceId).update()

        accessLog.expire()

        assertThat(waiting(device.deviceId)).isEmpty()
    }
}

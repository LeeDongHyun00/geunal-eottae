package app.geunal.api.common

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** api-server.md §10: what `/actuator/prometheus` hands the scraper, and that no ID rides along in a label. */
class MetricsTests : ApiTest() {
    /** The scrape as Prometheus makes it: no token. The body is text, which [api] would not parse. */
    private fun scrape(): String {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/actuator/prometheus")).GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        return response.body()
    }

    @Test
    fun `labels HTTP timings with the route pattern and never the path`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        val stranger = UUID.randomUUID()
        owner.get("/groups/$groupId/members").expect(200)
        // Turned away before any handler: no token, and no such route.
        api.send(HttpMethod.GET, "/groups/$stranger/members").expectError(401, "token_expired")
        owner.get("/nowhere/$stranger").expectError(404, "not_found")

        val metrics = scrape()

        assertThat(metrics).contains("http_server_requests_seconds_count")
        assertThat(metrics).contains("uri=\"/groups/{id}/members\"")
        assertThat(metrics).contains("http_server_requests_seconds_bucket")
        for (id in listOf(groupId, stranger, owner.userId, owner.deviceId)) assertThat(metrics).doesNotContain(id.toString())
    }

    @Test
    fun `counts 409 answers by their code`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        owner.commit(groupId, 0).expect(201)

        owner.commit(groupId, 0).expectError(409, "epoch_conflict")

        val line = scrape().lines().first { it.startsWith("geunal_api_conflicts_total") && it.contains("code=\"epoch_conflict\"") }
        assertThat(line).contains("status=\"409\"")
        assertThat(line.substringAfterLast(' ').toDouble()).isGreaterThanOrEqualTo(1.0)
    }
}

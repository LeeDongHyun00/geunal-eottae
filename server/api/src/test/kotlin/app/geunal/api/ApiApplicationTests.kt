package app.geunal.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiApplicationTests(
    @param:Autowired private val jdbc: JdbcClient,
    @param:Value("\${local.server.port}") private val port: Int,
) {
    companion object {
        // The images infra/docker-compose.yml runs.
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16.15")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = GenericContainer<Nothing>("redis:7.4.11").apply { withExposedPorts(6379) }
    }

    private fun get(path: String): HttpResponse<String> =
        HttpClient
            .newHttpClient()
            .send(HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `applies the V1 schema`() {
        val tables =
            jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'public'")
                .query(String::class.java)
                .list()

        assertThat(tables).contains("users", "devices", "groups", "group_log", "group_state", "welcomes", "media_objects")
    }

    @Test
    fun `reports UP once PostgreSQL and Redis answer`() {
        val response = get("/actuator/health")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }

    @Test
    fun `answers a request without a token in the protocol error body`() {
        val response = get("/me")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("\"error\":\"token_expired\"")
    }
}

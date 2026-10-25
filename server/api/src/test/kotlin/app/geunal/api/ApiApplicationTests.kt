package app.geunal.api

import app.geunal.api.support.Api
import app.geunal.api.support.TestStack
import app.geunal.api.support.register
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper

/** The API as a deployment would run it: no auth stub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiApplicationTests(
    @param:Autowired private val jdbc: JdbcClient,
    @param:Autowired private val mapper: JsonMapper,
    @param:Value("\${local.server.port}") private val port: Int,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun stack(registry: DynamicPropertyRegistry) = TestStack.register(registry)
    }

    private val api by lazy { Api(port, mapper) }

    @Test
    fun `applies the V1 schema and creates today's log partition`() {
        val tables =
            jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'public'")
                .query(String::class.java)
                .list()

        assertThat(tables).contains("users", "devices", "groups", "group_log", "group_state", "welcomes", "media_objects")
        assertThat(tables.filterNotNull().filter { it.startsWith("group_log_") }).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `reports UP once PostgreSQL and Redis answer`() {
        val response = api.send(HttpMethod.GET, "/actuator/health")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.string("status")).isEqualTo("UP")
    }

    @Test
    fun `answers a request without a token in the protocol error body`() {
        api.send(HttpMethod.GET, "/me").expectError(401, "token_expired")
    }

    @Test
    fun `serves the JWKS without a token`() {
        val jwks = api.send(HttpMethod.GET, "/.well-known/jwks.json").expect(200)

        assertThat(jwks["keys"].size()).isEqualTo(1)
        assertThat(jwks["keys"][0].path("kty").stringValue()).isEqualTo("EC")
        assertThat(jwks["keys"][0].has("d")).describedAs("private part stays private").isFalse()
    }

    @Test
    fun `hides the auth stub when it is off`() {
        api.send(HttpMethod.POST, "/auth/stub", body = emptyMap<String, Any>()).expectError(404, "not_found")
    }
}

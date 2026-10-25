package app.geunal.api.support

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Plain HTTP against the running API; never throws on a 4xx or 5xx, since the tests assert on them. */
class Api(
    port: Int,
    val mapper: JsonMapper,
) {
    private val client =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .build()

    fun send(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: Any? = null,
    ): Reply {
        val request = client.method(method).uri(path)
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body))
        val response = request.retrieve().toEntity(String::class.java)
        val json = response.body?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
        return Reply(response.statusCode.value(), json)
    }
}

data class Reply(
    val status: Int,
    val json: JsonNode?,
) {
    fun expect(status: Int): Reply {
        assertThat(this.status).describedAs("status of %s", this).isEqualTo(status)
        return this
    }

    /** Asserts the protocol §11 shape: the status and the `error` code. */
    fun expectError(
        status: Int,
        code: String,
    ): Reply {
        expect(status)
        assertThat(string("error")).isEqualTo(code)
        assertThat(json!!.has("message")).isTrue()
        return this
    }

    operator fun get(field: String): JsonNode = json!!.path(field)

    fun string(field: String): String = this[field].stringValue()

    fun long(field: String): Long = this[field].longValue()

    fun int(field: String): Int = this[field].intValue()

    fun uuid(field: String): UUID = UUID.fromString(string(field))

    fun bytes(field: String): ByteArray = this[field].binaryValue()
}

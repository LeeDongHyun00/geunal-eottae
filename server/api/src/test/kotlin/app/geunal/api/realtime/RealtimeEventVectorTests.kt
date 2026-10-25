package app.geunal.api.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The realtime event JSON is a contract the gateway and the core read (protocol §7, §12). This test pins it to
 * core/vectors/realtime_events.json in the shape the gateway's channel tests consume: for each case the exact string the
 * API publishes (`redis_message`), the event name the gateway derives from `t` and the payload it pushes, or `dropped`
 * for strings the gateway must ignore. Run with `GEUNAL_WRITE_VECTORS=1` to regenerate the file after a deliberate change.
 */
class RealtimeEventVectorTests {
    private val file = Path.of("..", "..", "core", "vectors", "realtime_events.json")
    private val group = UUID.fromString("6d1f5c44-cfcd-4fbf-a7e4-ddba702b6f27")
    private val owner = UUID.fromString("a3b2c1d0-1111-4222-8333-444455556666")
    private val device = UUID.fromString("0f3b1c9e-5a2d-4e7f-9b1a-2c3d4e5f6a7b")
    private val at = Instant.parse("2026-10-25T08:25:07.763555Z")

    private data class Case(
        val name: String,
        val event: RealtimeEvent,
        val ciphertext: ByteArray? = null,
    )

    private val published =
        listOf(
            Case("application message small enough to carry its ciphertext", EntryEvent.of(group, 42, "app", at, bytes(16)), bytes(16)),
            Case("commit small enough to carry its ciphertext", EntryEvent.of(group, 43, "commit", at, bytes(254)), bytes(254)),
            Case("commit too large for the signal, fetched from the log", EntryEvent.of(group, 44, "commit", at, ByteArray(4097))),
            Case("sequence numbers past 32 bits stay integers", EntryEvent.of(group, 4294967297, "app", at, ByteArray(4097))),
            Case("state blob rewritten", StateEvent(group, "availability", owner, 7)),
            Case("group meta rewritten", StateEvent(group, "group_meta", owner, 7)),
            Case("membership changed", MembershipEvent(group)),
            Case("welcome waiting", WelcomeEvent),
            Case("device revoked; the gateway also closes the connection", RevokeEvent),
        )

    /** Never published by the API; the gateway must drop them rather than push something a device cannot read. */
    private val dropped =
        mapOf(
            "unknown event type" to """{"t":"typing","g":"$group"}""",
            "no event type" to """{"g":"$group","s":44}""",
            "not JSON" to "entry 44",
        )

    @Test
    fun `publishes the events the vector file records`() {
        val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
        runner.withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE").run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val generated = generate(mapper)

            if (System.getenv("GEUNAL_WRITE_VECTORS") == "1") {
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generated) + "\n")
            }
            val recorded = mapper.readTree(Files.readString(file))
            assertThat(recorded.path("version").intValue()).isEqualTo(1)
            assertThat(recorded.path("device_id").stringValue()).isEqualTo(device.toString())
            val recordedCases = recorded.path("cases").values().associateBy { it.path("name").stringValue() }
            val generatedCases = generated.path("cases").values().associateBy { it.path("name").stringValue() }
            assertThat(recordedCases.keys).containsExactlyInAnyOrderElementsOf(generatedCases.keys)
            for ((name, expected) in generatedCases) {
                val actual = recordedCases.getValue(name)
                // Field order is not part of the contract, so the published string is compared as parsed JSON.
                assertThat(
                    parse(mapper, actual.path("redis_message")),
                ).describedAs(name).isEqualTo(parse(mapper, expected.path("redis_message")))
                assertThat(actual.path("event")).describedAs("%s event", name).isEqualTo(expected.path("event"))
                assertThat(actual.path("payload")).describedAs("%s payload", name).isEqualTo(expected.path("payload"))
                assertThat(actual.path("dropped")).describedAs("%s dropped", name).isEqualTo(expected.path("dropped"))
            }
        }
    }

    @Test
    fun `puts the ciphertext inline only up to 4 KB`() {
        assertThat(EntryEvent.of(group, 1, "app", at, ByteArray(4096)).c).isNotNull()
        assertThat(EntryEvent.of(group, 1, "app", at, ByteArray(4097)).c).isNull()
    }

    private fun generate(mapper: JsonMapper): ObjectNode {
        val signals = Signals(redis = StringRedisTemplate(), mapper = mapper)
        val root = mapper.createObjectNode()
        root.put("version", 1)
        root.put("generated_by", "server/api: GEUNAL_WRITE_VECTORS=1 make test-api (RealtimeEventVectorTests)")
        root.put(
            "description",
            "What the API publishes on Redis channel d:{device_id} (`redis_message`, the exact UTF-8 string) and what the gateway " +
                "then pushes on the device's Phoenix channel (`event`, `payload`), or `dropped` when the gateway forwards nothing. " +
                "`c` is the MLS ciphertext in base64, as in the push payload; `ciphertext_hex` is the same bytes for checking " +
                "the decode. `at` is the server's received_at of the entry (RFC 3339 UTC).",
        )
        root.put("device_id", device.toString())
        root.put("channel", signals.channel(device))
        val cases = root.putArray("cases")
        for (case in published) {
            val message = signals.encode(case.event)
            val payload = (mapper.readTree(message) as ObjectNode).also { it.remove("t") }
            val node = cases.addObject()
            node.put("name", case.name)
            node.put("redis_message", message)
            node.put("event", case.event.t)
            node.set("payload", payload)
            case.ciphertext?.let { node.put("ciphertext_hex", it.joinToString("") { b -> "%02x".format(b) }) }
        }
        for ((name, message) in dropped) {
            val node = cases.addObject()
            node.put("name", name)
            node.put("redis_message", message)
            node.put("dropped", true)
        }
        return root
    }

    /** The published string as JSON, or the string itself when it is not JSON (a `dropped` case). */
    private fun parse(
        mapper: JsonMapper,
        node: JsonNode,
    ): JsonNode = runCatching { mapper.readTree(node.stringValue()) }.getOrElse { node }

    private fun bytes(size: Int) = ByteArray(size) { (it + 1).toByte() }
}

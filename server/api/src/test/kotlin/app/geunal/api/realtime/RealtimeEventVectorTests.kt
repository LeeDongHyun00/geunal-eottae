package app.geunal.api.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The realtime event JSON is a contract the gateway and the core read (protocol §7, §12). This test pins it to
 * core/vectors/realtime_events.json; run with `GEUNAL_WRITE_VECTORS=1` to regenerate the file after a deliberate change.
 */
class RealtimeEventVectorTests {
    private val file = Path.of("..", "..", "core", "vectors", "realtime_events.json")
    private val group = UUID.fromString("0190a2b4-7c3e-7d11-8a0b-5f2e9c1d3e4f")
    private val owner = UUID.fromString("c56a4180-65aa-42ec-a945-5fd21dec0538")
    private val device = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2")

    private data class Case(
        val name: String,
        val event: RealtimeEvent,
        val ciphertext: ByteArray? = null,
    )

    private val cases =
        listOf(
            Case("entry_commit_inline", EntryEvent.of(group, 1, "commit", ByteArray(48) { it.toByte() }), ByteArray(48) { it.toByte() }),
            Case(
                "entry_app_inline",
                EntryEvent.of(group, 2, "app", ByteArray(254) { (it * 7).toByte() }),
                ByteArray(254) { (it * 7).toByte() },
            ),
            Case("entry_app_too_large_for_inline", EntryEvent.of(group, 3, "app", ByteArray(4097))),
            Case("state_availability", StateEvent(group, "availability", owner, 5)),
            Case("state_group_meta", StateEvent(group, "group_meta", owner, 5)),
            Case("membership", MembershipEvent(group)),
            Case("welcome", WelcomeEvent),
            Case("revoke", RevokeEvent),
        )

    @Test
    fun `publishes the events the vector file records`() {
        val contextRunner =
            ApplicationContextRunner().withConfiguration(
                org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(JacksonAutoConfiguration::class.java),
            )
        contextRunner.withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE").run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val signals = Signals(redis = StringRedisTemplate(), mapper = mapper)
            val generated = mapper.createObjectNode()
            generated.put("version", 1)
            generated.put("generated_by", "server/api: GEUNAL_WRITE_VECTORS=1 make test-api (RealtimeEventVectorTests)")
            val events = generated.putArray("events")
            for (case in cases) {
                val node = events.addObject()
                node.put("name", case.name)
                node.put("channel", signals.channel(device))
                node.put("payload", signals.encode(case.event))
                case.ciphertext?.let { node.put("ciphertext_hex", it.joinToString("") { b -> "%02x".format(b) }) }
            }

            if (System.getenv("GEUNAL_WRITE_VECTORS") == "1") {
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generated) + "\n")
            }
            val recorded = mapper.readTree(Files.readString(file))
            assertThat(recorded.path("version").intValue()).isEqualTo(1)
            val recordedEvents = recorded.path("events").associateBy { it.path("name").stringValue() }
            assertThat(recordedEvents.keys).containsExactlyInAnyOrderElementsOf(cases.map { it.name })
            for (event in events) {
                val name = event.path("name").stringValue()
                val expected = recordedEvents.getValue(name)
                assertThat(event.path("channel").stringValue()).describedAs(name).isEqualTo(expected.path("channel").stringValue())
                // Field order is not part of the contract, so compare parsed JSON.
                assertThat(
                    mapper.readTree(event.path("payload").stringValue()),
                ).describedAs(name).isEqualTo(mapper.readTree(expected.path("payload").stringValue()))
            }
        }
    }

    @Test
    fun `puts the ciphertext inline only up to 4 KB`() {
        assertThat(EntryEvent.of(group, 1, "app", ByteArray(4096)).c).isNotNull()
        assertThat(EntryEvent.of(group, 1, "app", ByteArray(4097)).c).isNull()
    }
}

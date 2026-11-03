package app.geunal.api.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * The sign-in vectors of protocol §12: the server computes the provider nonce binding and checks recovery signatures
 * exactly as the core produces them (core/vectors/login_nonce.json, recovery.json), and pins the sign-in result bodies
 * the core parses (core/vectors/auth_results.json). Run with `GEUNAL_WRITE_VECTORS=1` to rewrite auth_results.json
 * after a deliberate change.
 */
class AuthVectorTests {
    private val vectors = Path.of("..", "..", "core", "vectors")
    private val mapper = JsonMapper.builder().build()
    private val hex = HexFormat.of()

    private fun read(name: String): JsonNode = mapper.readTree(Files.readString(vectors.resolve(name)))

    @Test
    fun `binds the provider nonce as the core does`() {
        val cases = read("login_nonce.json").path("cases").values()

        assertThat(cases).isNotEmpty()
        for (case in cases) {
            val key = hex.parseHex(case.path("signature_key").stringValue())
            assertThat(ProviderTokens.binding(case.path("nonce").stringValue(), key))
                .describedAs(case.path("nonce").stringValue())
                .isEqualTo(case.path("provider_nonce").stringValue())
        }
    }

    @Test
    fun `checks the core's recovery signatures`() {
        val codes = read("recovery.json").path("codes").values()

        assertThat(codes).isNotEmpty()
        for (code in codes) {
            val key = hex.parseHex(code.path("recovery_public_key").stringValue())
            val nonce = code.path("nonce").stringValue().toByteArray(Charsets.US_ASCII)
            val recover = hex.parseHex(code.path("recover_signature").stringValue())
            assertThat(hex.parseHex(code.path("recovery_id").stringValue())).hasSize(AuthService.RECOVERY_ID_LENGTH)
            assertThat(Ed25519.verify(key, AuthService.RECOVER_LABEL + nonce, recover)).isTrue()
            // A signature for one purpose never passes for another.
            assertThat(Ed25519.verify(key, "geunal delete v1".toByteArray() + nonce, recover)).isFalse()
            assertThat(Ed25519.verify(key, AuthService.RECOVER_LABEL + nonce + byteArrayOf(0), recover)).isFalse()
        }
    }

    @Test
    fun `answers sign-ins in the shapes the vector file records`() {
        val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
        runner.withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE").run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            // Through text and back, so numbers compare as the file reads them (900 is an int there, a long in Registered).
            val generated = mapper.readTree(mapper.writeValueAsString(generate(mapper)))
            val file = vectors.resolve("auth_results.json")

            if (System.getenv("GEUNAL_WRITE_VECTORS") == "1") {
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generated) + "\n")
            }
            val recorded = mapper.readTree(Files.readString(file))
            assertThat(recorded.path("version").intValue()).isEqualTo(1)
            val byName = { node: JsonNode -> node.path("cases").values().associateBy { it.path("name").stringValue() } }
            val recordedCases = byName(recorded)
            val generatedCases = byName(generated)
            assertThat(recordedCases.keys).containsExactlyInAnyOrderElementsOf(generatedCases.keys)
            for ((name, expected) in generatedCases) {
                val actual = recordedCases.getValue(name)
                assertThat(actual.path("status")).describedAs("%s status", name).isEqualTo(expected.path("status"))
                assertThat(actual.path("body")).describedAs("%s body", name).isEqualTo(expected.path("body"))
            }
        }
    }

    private fun generate(mapper: JsonMapper): ObjectNode {
        val user = UUID.fromString("a3b2c1d0-1111-4222-8333-444455556666")
        val device = UUID.fromString("0f3b1c9e-5a2d-4e7f-9b1a-2c3d4e5f6a7b")
        val results =
            listOf(
                "registered: this call created the account" to
                    Registered(user, device, ACCESS, 900, REFRESH, newAccount = true, recovery = false),
                "registered: an existing account with recovery material, so the app offers a restore" to
                    Registered(user, device, ACCESS, 900, REFRESH, newAccount = false, recovery = true),
                "replace_required: another device is active and nothing was registered" to
                    ReplaceRequired(
                        userId = user,
                        ticket = "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c",
                        expiresIn = 1800,
                        activeDevice = ActiveDevice("android", Instant.parse("2026-10-30T12:34:56.789Z")),
                        recovery = true,
                    ),
            )
        val root = mapper.createObjectNode()
        root.put("version", 1)
        root.put("generated_by", "server/api: GEUNAL_WRITE_VECTORS=1 make test-api (AuthVectorTests)")
        root.put(
            "description",
            "Bodies of the sign-in endpoints (POST /auth/kakao, /auth/apple, /auth/guest, /auth/recover, /auth/replace, " +
                "/auth/stub; protocol §13 \"로그인 결과\"). `status` is the HTTP status: 201 registers this device, 200 means " +
                "replace_required and registers nothing. Field order is not part of the contract.",
        )
        val cases = root.putArray("cases")
        for ((name, result) in results) {
            val case = cases.addObject()
            case.put("name", name)
            case.put("status", AuthController.answer(result).statusCode.value())
            case.set("body", mapper.valueToTree(result))
        }
        return root
    }

    private companion object {
        const val ACCESS = "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsxIiwidHlwIjoiSldUIn0.eyJzdWIiOiJ1In0.c2ln"
        const val REFRESH = "Zm9yLXRoZS12ZWN0b3Itb25seS1ub3QtYS1yZWFsLXRva2Vu"
    }
}

package app.geunal.api.groups

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** The server reads typed codes by the core's rule (protocol §15, core/vectors/invites.json). */
class InviteCodeVectorTests {
    private val vectors = JsonMapper().readTree(Files.readString(Path.of("..", "..", "core", "vectors", "invites.json")))

    @Test
    fun `reads codes as the core does`() {
        val cases = vectors.path("codes").toList()
        assertThat(cases).isNotEmpty()
        cases.forEach { case ->
            val expected = case.path("code").takeUnless { it.isNull }?.stringValue()
            assertThat(InviteCodes.canonical(case.path("input").stringValue())).`as`(case.toString()).isEqualTo(expected)
        }
    }
}

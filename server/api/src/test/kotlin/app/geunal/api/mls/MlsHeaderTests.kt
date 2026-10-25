package app.geunal.api.mls

import app.geunal.api.common.ApiException
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class MlsHeaderTests {
    private val groupId = UUID.fromString("0190a2b4-7c3e-7d11-8a0b-5f2e9c1d3e4f")

    @Test
    fun `reads group id and epoch from public and private messages`() {
        val public = MlsHeader.parse(Mls.commit(groupId, 7))
        assertThat(public.wireFormat).isEqualTo(MlsHeader.PUBLIC_MESSAGE)
        assertThat(public.groupId).isEqualTo(MlsHeader.bytesOf(groupId))
        assertThat(public.epoch).isEqualTo(7)
        assertThat(public.senderType).isEqualTo(MlsHeader.SENDER_MEMBER)
        assertThat(public.contentType).isEqualTo(MlsHeader.CONTENT_COMMIT)
        assertThat(public.isExternalCommit).isFalse()

        val private = MlsHeader.parse(Mls.privateMessage(groupId, 1L shl 40))
        assertThat(private.wireFormat).isEqualTo(MlsHeader.PRIVATE_MESSAGE)
        assertThat(private.groupId).isEqualTo(MlsHeader.bytesOf(groupId))
        assertThat(private.epoch).isEqualTo(1L shl 40)
        assertThat(private.senderType).isNull()
    }

    @Test
    fun `tells an external commit by its sender type`() {
        val external = MlsHeader.parse(Mls.externalCommit(groupId, 3))
        assertThat(external.isExternalCommit).isTrue()
        assertThat(external.contentType).isEqualTo(MlsHeader.CONTENT_COMMIT)
    }

    @Test
    fun `reads the group context of a GroupInfo and only the format of Welcome and KeyPackage`() {
        val info = MlsHeader.parse(Mls.groupInfo(groupId, 2))
        assertThat(info.wireFormat).isEqualTo(MlsHeader.GROUP_INFO)
        assertThat(info.groupId).isEqualTo(MlsHeader.bytesOf(groupId))
        assertThat(info.epoch).isEqualTo(2)

        assertThat(MlsHeader.parse(Mls.welcome()).wireFormat).isEqualTo(MlsHeader.WELCOME)
        assertThat(MlsHeader.parse(Mls.keyPackage()).groupId).isNull()
    }

    @Test
    fun `reads every length prefix width`() {
        val longId = ByteArray(300) { it.toByte() }
        val bytes = byteArrayOf(0, 1, 0, 2, 0x41, 0x2c) + longId + ByteArray(8) { if (it == 7) 9 else 0 }
        val header = MlsHeader.parse(bytes)
        assertThat(header.groupId).isEqualTo(longId)
        assertThat(header.epoch).isEqualTo(9)
    }

    @Test
    fun `rejects bytes that are not an MLS version 1 message`() {
        fun invalid(bytes: ByteArray) {
            val e = assertThrows<ApiException> { MlsHeader.parse(bytes) }
            assertThat(e.code).isEqualTo("invalid_request")
        }
        invalid(byteArrayOf())
        invalid(byteArrayOf(0, 2, 0, 1))
        invalid(byteArrayOf(0, 1, 0, 9))
        invalid(Mls.commit(groupId, 1).copyOf(10))
        invalid(byteArrayOf(0, 1, 0, 1, 0xC0.toByte()))
        invalid(Mls.publicMessage(groupId, 1, senderType = 9, contentType = 3, size = 64))
        invalid(Mls.publicMessage(groupId, 1, senderType = 1, contentType = 0, size = 64))
    }

    /**
     * The vectors the Rust core generates from real mls-rs messages (protocol §12, core/vectors/README.md). Each entry
     * carries the message bytes and what the server must read from them; the test is skipped until the file lands.
     */
    @Test
    fun `agrees with the core's vectors`() {
        val file = Path.of("..", "..", "core", "vectors", "mls_headers.json")
        org.junit.jupiter.api.Assumptions
            .assumeTrue(Files.exists(file), "core/vectors/mls_headers.json is not published yet")
        val vectors = JsonMapper.builder().build().readTree(Files.readString(file))

        assertThat(vectors.path("version").intValue()).isEqualTo(1)
        for (vector in vectors.path("vectors")) {
            val header = MlsHeader.parse(hex(vector.path("message").stringValue()))
            val name = vector.path("name").stringValue()
            assertThat(header.wireFormat).describedAs("%s wire_format", name).isEqualTo(vector.path("wire_format").intValue())
            if (vector.has(
                    "group_id",
                )
            ) {
                assertThat(header.groupId).describedAs("%s group_id", name).isEqualTo(hex(vector.path("group_id").stringValue()))
            }
            if (vector.has("epoch")) assertThat(header.epoch).describedAs("%s epoch", name).isEqualTo(vector.path("epoch").longValue())
            if (vector.has(
                    "content_type",
                )
            ) {
                assertThat(header.contentType).describedAs("%s content_type", name).isEqualTo(vector.path("content_type").intValue())
            }
            if (vector.has(
                    "sender_type",
                )
            ) {
                assertThat(header.senderType).describedAs("%s sender_type", name).isEqualTo(vector.path("sender_type").intValue())
            }
        }
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

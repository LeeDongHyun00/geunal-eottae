package app.geunal.api.mls

import app.geunal.api.common.ApiException
import java.util.UUID

/**
 * The public prefix of an MLSMessage (RFC 9420 §6): protocol version, wire format and, where the format has one, the
 * group ID and epoch the message belongs to. Nothing further is parsed; the server checks these against the request
 * so a message cannot land in another group's log or claim another epoch (api-server.md §5). Verified in CI against the
 * vectors the Rust core generates (`core/vectors/mls_headers.json`).
 */
data class MlsHeader(
    val version: Int,
    val wireFormat: Int,
    val groupId: ByteArray?,
    val epoch: Long?,
    /** Public messages only: `member`, `external`, `new_member_proposal` or `new_member_commit` (1 to 4). */
    val senderType: Int?,
    /** Public messages only: `application`, `proposal` or `commit` (1 to 3). */
    val contentType: Int?,
) {
    val isExternalCommit: Boolean get() = wireFormat == PUBLIC_MESSAGE && senderType == SENDER_NEW_MEMBER_COMMIT

    fun expectWireFormat(expected: Int): MlsHeader {
        if (wireFormat != expected) throw ApiException.invalid("expected MLS wire format $expected, got $wireFormat")
        return this
    }

    fun expectGroup(
        groupId: UUID,
        epoch: Long,
    ): MlsHeader {
        if (this.groupId == null ||
            !this.groupId.contentEquals(bytesOf(groupId))
        ) {
            throw ApiException.invalid("the MLS group ID is not this group")
        }
        if (this.epoch != epoch) throw ApiException.invalid("the MLS epoch is ${this.epoch}, the request says $epoch")
        return this
    }

    companion object {
        const val PUBLIC_MESSAGE = 1
        const val PRIVATE_MESSAGE = 2
        const val WELCOME = 3
        const val GROUP_INFO = 4
        const val KEY_PACKAGE = 5

        const val SENDER_MEMBER = 1
        const val SENDER_EXTERNAL = 2
        const val SENDER_NEW_MEMBER_PROPOSAL = 3
        const val SENDER_NEW_MEMBER_COMMIT = 4

        const val CONTENT_APPLICATION = 1
        const val CONTENT_PROPOSAL = 2
        const val CONTENT_COMMIT = 3

        private const val MLS10 = 1

        /** Parses the header or throws 400 `invalid_request` when the bytes are not an MLS 1.0 message. */
        fun parse(bytes: ByteArray): MlsHeader =
            try {
                read(Reader(bytes))
            } catch (e: MlsFormatException) {
                throw ApiException.invalid("not an MLS message: ${e.message}")
            }

        private fun read(r: Reader): MlsHeader {
            val version = r.u16()
            if (version != MLS10) throw MlsFormatException("protocol version $version")
            val wireFormat = r.u16()
            return when (wireFormat) {
                PUBLIC_MESSAGE -> {
                    val groupId = r.vector()
                    val epoch = r.u64()
                    val senderType = r.u8()
                    when (senderType) {
                        SENDER_MEMBER, SENDER_EXTERNAL -> r.u32()
                        SENDER_NEW_MEMBER_PROPOSAL, SENDER_NEW_MEMBER_COMMIT -> {}
                        else -> throw MlsFormatException("sender type $senderType")
                    }
                    r.vector() // authenticated_data
                    val contentType = r.u8()
                    if (contentType !in CONTENT_APPLICATION..CONTENT_COMMIT) throw MlsFormatException("content type $contentType")
                    MlsHeader(version, wireFormat, groupId, epoch, senderType, contentType)
                }
                PRIVATE_MESSAGE -> MlsHeader(version, wireFormat, r.vector(), r.u64(), null, null)
                GROUP_INFO -> {
                    // GroupInfo starts with its GroupContext: version, cipher suite, group ID, epoch.
                    r.u16()
                    r.u16()
                    MlsHeader(version, wireFormat, r.vector(), r.u64(), null, null)
                }
                WELCOME, KEY_PACKAGE -> MlsHeader(version, wireFormat, null, null, null, null)
                else -> throw MlsFormatException("wire format $wireFormat")
            }
        }

        /** A UUID as the 16 bytes the core uses for the MLS group ID (protocol §1). */
        fun bytesOf(id: UUID): ByteArray =
            java.nio.ByteBuffer
                .allocate(16)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .array()
    }

    private class MlsFormatException(
        message: String,
    ) : RuntimeException(message)

    /** Big-endian reads over the TLS presentation language, with the QUIC-style variable-length vector prefix (RFC 9420 §2.1.2). */
    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var at = 0

        fun u8(): Int = next().toInt() and 0xff

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun u64(): Long = (u32() shl 32) or u32()

        fun vector(): ByteArray {
            val first = u8()
            val length =
                when (first shr 6) {
                    0 -> first and 0x3f
                    1 -> ((first and 0x3f) shl 8) or u8()
                    2 -> ((first and 0x3f) shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
                    else -> throw MlsFormatException("invalid vector length prefix")
                }
            if (at + length > bytes.size) throw MlsFormatException("truncated vector")
            return bytes.copyOfRange(at, at + length).also { at += length }
        }

        private fun next(): Byte {
            if (at >= bytes.size) throw MlsFormatException("truncated header")
            return bytes[at++]
        }
    }
}

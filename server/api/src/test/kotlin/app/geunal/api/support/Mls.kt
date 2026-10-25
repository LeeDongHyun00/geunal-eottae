package app.geunal.api.support

import app.geunal.api.mls.MlsHeader
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Byte strings shaped like MLS 1.0 messages as far as the server reads them (RFC 9420 §6): the header the parser checks
 * followed by filler. They are not valid MLS beyond that; the core's vectors cover real messages.
 */
object Mls {
    fun commit(
        groupId: UUID,
        epoch: Long,
        senderType: Int = MlsHeader.SENDER_MEMBER,
        contentType: Int = MlsHeader.CONTENT_COMMIT,
        size: Int = 96,
    ): ByteArray = publicMessage(groupId, epoch, senderType, contentType, size)

    fun externalCommit(
        groupId: UUID,
        epoch: Long,
    ): ByteArray = publicMessage(groupId, epoch, MlsHeader.SENDER_NEW_MEMBER_COMMIT, MlsHeader.CONTENT_COMMIT, 96)

    fun publicMessage(
        groupId: UUID,
        epoch: Long,
        senderType: Int,
        contentType: Int,
        size: Int,
    ): ByteArray =
        build(size) {
            header(MlsHeader.PUBLIC_MESSAGE)
            vector(MlsHeader.bytesOf(groupId))
            u64(epoch)
            u8(senderType)
            if (senderType == MlsHeader.SENDER_MEMBER || senderType == MlsHeader.SENDER_EXTERNAL) u32(0)
            vector(ByteArray(0))
            u8(contentType)
        }

    fun privateMessage(
        groupId: UUID,
        epoch: Long,
        size: Int = 64,
    ): ByteArray =
        build(size) {
            header(MlsHeader.PRIVATE_MESSAGE)
            vector(MlsHeader.bytesOf(groupId))
            u64(epoch)
            u8(MlsHeader.CONTENT_APPLICATION)
        }

    fun groupInfo(
        groupId: UUID,
        epoch: Long,
        size: Int = 80,
    ): ByteArray =
        build(size) {
            header(MlsHeader.GROUP_INFO)
            u16(1)
            u16(3)
            vector(MlsHeader.bytesOf(groupId))
            u64(epoch)
        }

    fun welcome(size: Int = 120): ByteArray =
        build(size) {
            header(MlsHeader.WELCOME)
            u16(3)
        }

    fun keyPackage(size: Int = 200): ByteArray =
        build(size) {
            header(MlsHeader.KEY_PACKAGE)
            u16(1)
            u16(3)
        }

    private fun build(
        size: Int,
        fill: Writer.() -> Unit,
    ): ByteArray {
        val writer = Writer().apply(fill)
        val head = writer.bytes()
        // Deterministic filler so equal inputs give equal bytes.
        return head + ByteArray((size - head.size).coerceAtLeast(0)) { (it * 31 + 7).toByte() }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun header(wireFormat: Int) {
            u16(1)
            u16(wireFormat)
        }

        fun u8(value: Int) = out.write(value and 0xff)

        fun u16(value: Int) {
            u8(value shr 8)
            u8(value)
        }

        fun u32(value: Long) {
            u16((value shr 16).toInt())
            u16(value.toInt())
        }

        fun u64(value: Long) {
            u32(value ushr 32)
            u32(value and 0xffffffffL)
        }

        fun vector(bytes: ByteArray) {
            when {
                bytes.size < 0x40 -> u8(bytes.size)
                bytes.size < 0x4000 -> u16(0x4000 or bytes.size)
                else -> u32(0x80000000L or bytes.size.toLong())
            }
            out.write(bytes)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}

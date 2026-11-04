package app.geunal.api.delivery

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/** The mailbox keeps what a device sent as it is: the server reads the MLS header and nothing after it (protocol §3). */
class CiphertextTests : ApiTest() {
    @Test
    fun `stores a message's ciphertext byte for byte and never parses past the MLS header`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        val header = Mls.privateMessage(groupId, 0, size = 0)
        // Every byte value, in no structure MLS would accept: only the header is ever looked at.
        val rest = ByteArray(4096).also { SecureRandom().nextBytes(it) } + ByteArray(256) { it.toByte() }
        val sent = header + rest

        val seq = owner.post("/groups/$groupId/messages", mapOf("epoch" to 0, "message" to sent, "alert" to "none")).expect(201).long("seq")

        val logged = owner.get("/groups/$groupId/log?after=${seq - 1}&limit=1").expect(200)["entries"][0]
        assertThat(logged.path("ciphertext").binaryValue()).isEqualTo(sent)
        val stored =
            jdbc
                .sql("SELECT ciphertext FROM group_log WHERE group_id = :group AND seq = :seq")
                .param("group", groupId)
                .param("seq", seq)
                .query(ByteArray::class.java)
                .single()
        assertThat(stored).isEqualTo(sent)
    }
}

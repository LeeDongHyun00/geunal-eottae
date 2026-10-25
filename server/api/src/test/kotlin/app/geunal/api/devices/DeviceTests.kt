package app.geunal.api.devices

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DeviceTests : ApiTest() {
    @Test
    fun `lists an account's active device with its signature key`() {
        val client = signIn()

        val devices = client.get("/users/${client.userId}/devices").expect(200)["devices"]
        assertThat(devices.size()).isEqualTo(1)
        assertThat(devices[0].path("id").stringValue()).isEqualTo(client.deviceId.toString())
        assertThat(devices[0].path("signature_key").binaryValue()).isEqualTo(client.signatureKey)
        assertThat(client.get("/users/${UUID.randomUUID()}/devices").expect(200)["devices"].size()).isZero()
    }

    @Test
    fun `stores key packages and hands them out one at a time`() {
        val owner = signIn()
        val adder = signIn()
        val packages = (1..3).map { Mls.keyPackage(100 + it) }

        assertThat(owner.post("/me/key-packages", mapOf("key_packages" to packages)).expect(200).int("count")).isEqualTo(3)
        assertThat(owner.get("/me").expect(200).int("key_packages")).isEqualTo(3)

        val claimed = adder.post("/devices/${owner.deviceId}/key-packages/claim").expect(200).bytes("key_package")
        assertThat(claimed).isEqualTo(packages[0])
        assertThat(owner.get("/me").expect(200).int("key_packages")).isEqualTo(2)
        adder.post("/devices/${owner.deviceId}/key-packages/claim").expect(200)
        adder.post("/devices/${owner.deviceId}/key-packages/claim").expect(200)
        adder.post("/devices/${owner.deviceId}/key-packages/claim").expectError(404, "not_found")
        adder.post("/devices/${UUID.randomUUID()}/key-packages/claim").expectError(404, "not_found")
    }

    @Test
    fun `refuses key packages that are too large, not key packages, or too many`() {
        val client = signIn()

        client.post("/me/key-packages", mapOf("key_packages" to listOf(Mls.keyPackage(1025)))).expectError(413, "too_large")
        client.post("/me/key-packages", mapOf("key_packages" to listOf(Mls.welcome()))).expectError(400, "invalid_request")
        client.post("/me/key-packages", mapOf("key_packages" to emptyList<ByteArray>())).expectError(400, "invalid_request")
        client.post("/me/key-packages", mapOf("key_packages" to List(101) { Mls.keyPackage() })).expectError(400, "invalid_request")
        assertThat(client.get("/me").expect(200).int("key_packages")).isZero()
    }

    @Test
    fun `keeps a push token for the current device`() {
        val client = signIn()

        client.put("/me/push-token", mapOf("token" to "apns-token")).expect(204)
        val stored =
            jdbc
                .sql("SELECT push_token FROM devices WHERE id = :id")
                .param("id", client.deviceId)
                .query(String::class.java)
                .single()
        assertThat(stored).isEqualTo("apns-token")
        client.put("/me/push-token", mapOf("token" to null)).expect(204)
    }
}

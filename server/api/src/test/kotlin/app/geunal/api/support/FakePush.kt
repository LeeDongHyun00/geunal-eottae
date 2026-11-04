package app.geunal.api.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * APNs, FCM and Google's OAuth token endpoint on one local HTTP server. Each push is recorded with the device token it
 * was sent to; a token put in [dead] is answered the way each platform answers for an uninstalled app.
 */
object FakePush {
    data class Sent(
        val platform: String,
        val token: String,
        val headers: Map<String, String>,
        val body: String,
    )

    val sent = ConcurrentLinkedQueue<Sent>()
    val dead: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val mapper = JsonMapper.builder().build()

    private fun pem(encoded: ByteArray) =
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----\n"

    val apnsKeyPem: String =
        pem(
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
                .private.encoded,
        )

    private val fcmKeyPem: String =
        pem(
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private.encoded,
        )

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/3/device/") { exchange ->
                val token = exchange.requestURI.path.removePrefix("/3/device/")
                sent += Sent("ios", token, exchange.headers(), exchange.requestBody.readAllBytes().decodeToString())
                if (token in dead) exchange.reply(410, """{"reason":"Unregistered"}""") else exchange.reply(200, "")
            }
            createContext("/oauth/token") { it.reply(200, """{"access_token":"fcm-access","expires_in":3600,"token_type":"Bearer"}""") }
            createContext("/v1/projects/") { exchange ->
                val body = exchange.requestBody.readAllBytes().decodeToString()
                val token =
                    mapper
                        .readTree(body)
                        .path("message")
                        .path("token")
                        .stringValue()
                sent += Sent("android", token, exchange.headers(), body)
                if (token in dead) {
                    exchange.reply(404, """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""")
                } else {
                    exchange.reply(200, """{"name":"projects/geunal-test/messages/1"}""")
                }
            }
            start()
        }

    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    /** A service account key file as Google hands it out, with the token endpoint pointed here. */
    val serviceAccountJson: String
        get() =
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                mapOf(
                    "type" to "service_account",
                    "project_id" to "geunal-test",
                    "private_key_id" to "abc123",
                    "private_key" to fcmKeyPem,
                    "client_email" to "geunal-push@geunal-test.iam.gserviceaccount.com",
                    "token_uri" to "$base/oauth/token",
                ),
            )

    /**
     * The service account as the alpha server's env file carries it: infra/alpha fetch-secrets.py writes every line
     * break of a value as the two characters `\n`.
     */
    val serviceAccountAsFetched: String get() = serviceAccountJson.replace("\n", "\\n")

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("geunal.push.apns.key-id") { "APNSKEY123" }
        registry.add("geunal.push.apns.team-id") { "TEAM123456" }
        registry.add("geunal.push.apns.private-key") { apnsKeyPem.replace("\n", "\\n") }
        registry.add("geunal.push.apns.endpoint") { base }
        registry.add("geunal.push.fcm.service-account") { serviceAccountAsFetched }
        registry.add("geunal.push.fcm.endpoint") { base }
    }

    fun to(token: String): List<Sent> = sent.filter { it.token == token }

    private fun HttpExchange.headers(): Map<String, String> = requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
    }
}

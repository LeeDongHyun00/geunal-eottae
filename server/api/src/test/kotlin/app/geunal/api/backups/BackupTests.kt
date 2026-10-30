package app.geunal.api.backups

import app.geunal.api.ops.Cleanup
import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.RecoveryCode
import app.geunal.api.support.TestStack
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Backups against a real S3 API (protocol §10 "백업"): signed uploads, completion, the newest three, downloads. */
class BackupTests : ApiTest() {
    @Autowired
    lateinit var cleanup: Cleanup

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun account(subject: String = "backup-${UUID.randomUUID()}"): Client =
        signIn(subject).also { it.put("/me/recovery", RecoveryCode().upload()).expect(204) }

    private fun put(
        url: String,
        body: ByteArray,
    ): Int =
        http
            .send(
                HttpRequest.newBuilder(URI(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    private fun get(url: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    /** A backup of [body] uploaded and completed; its id. */
    private fun backUp(
        account: Client,
        body: ByteArray,
    ): Long {
        val start = account.post("/me/backups", mapOf("size" to body.size)).expect(201)
        assertThat(put(start.string("upload_url"), body)).isEqualTo(200)
        return account.post("/me/backups/${start.long("id")}/complete").expect(200).long("id")
    }

    private fun objectKey(id: Long): String =
        jdbc
            .sql("SELECT object_key FROM backups WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    private fun stored(key: String): Boolean =
        try {
            TestStack.s3Client.headObject { it.bucket(TestStack.BACKUPS_BUCKET).key(key) }
            true
        } catch (_: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw e
        }

    @Test
    fun `uploads, completes, lists and downloads a backup`() {
        val account = account()
        val body = Random.nextBytes(4096)

        val start = account.post("/me/backups", mapOf("size" to body.size)).expect(201)
        assertThat(start.long("expires_in")).isEqualTo(900)
        assertThat(start.string("upload_url")).contains(TestStack.BACKUPS_BUCKET).contains("X-Amz-Signature")
        assertThat(account.get("/me/backups").expect(200)["backups"].size()).isZero()
        assertThat(put(start.string("upload_url"), body)).isEqualTo(200)

        val done = account.post("/me/backups/${start.long("id")}/complete").expect(200)
        assertThat(done.long("id")).isEqualTo(start.long("id"))
        assertThat(done.long("size")).isEqualTo(4096)
        val completedAt = Instant.parse(done.string("created_at"))
        // Completing again answers the same backup.
        assertThat(
            account.post("/me/backups/${start.long("id")}/complete").expect(200).string("created_at"),
        ).isEqualTo(done.string("created_at"))

        val list = account.get("/me/backups").expect(200)["backups"]
        assertThat(list.size()).isEqualTo(1)
        assertThat(list[0].path("id").longValue()).isEqualTo(start.long("id"))
        assertThat(Instant.parse(list[0].path("created_at").stringValue())).isEqualTo(completedAt)
        val latest = account.get("/me/backups/latest").expect(200)
        assertThat(latest.long("id")).isEqualTo(start.long("id"))
        assertThat(latest.long("expires_in")).isEqualTo(900)
        val download = get(latest.string("download_url"))
        assertThat(download.statusCode()).isEqualTo(200)
        assertThat(download.body()).isEqualTo(body)
    }

    @Test
    fun `the upload URL takes exactly the declared size`() {
        val account = account()
        val start = account.post("/me/backups", mapOf("size" to 1000)).expect(201)
        val url = start.string("upload_url")

        assertThat(put(url, ByteArray(1001))).isEqualTo(403)
        assertThat(put(url, ByteArray(999))).isEqualTo(403)
        account.post("/me/backups/${start.long("id")}/complete").expectError(409, "upload_incomplete")
        assertThat(account.get("/me/backups").expect(200)["backups"].size()).isZero()

        assertThat(put(url, ByteArray(1000))).isEqualTo(200)
        account.post("/me/backups/${start.long("id")}/complete").expect(200)
    }

    @Test
    fun `completing needs the object, and only the account's own upload`() {
        val account = account()
        val start = account.post("/me/backups", mapOf("size" to 10)).expect(201)

        account.post("/me/backups/${start.long("id")}/complete").expectError(409, "upload_incomplete")
        account().post("/me/backups/${start.long("id")}/complete").expectError(404, "not_found")
        account.post("/me/backups/999999999/complete").expectError(404, "not_found")
    }

    @Test
    fun `keeps the newest three and deletes the rest from storage`() {
        val account = account()
        val first = backUp(account, Random.nextBytes(64))
        val oldest = objectKey(first)
        val ids = listOf(first) + List(3) { backUp(account, Random.nextBytes(64)) }

        val listed =
            account
                .get("/me/backups")
                .expect(200)["backups"]
                .values()
                .map { it.path("id").longValue() }
        assertThat(listed).containsExactly(ids[3], ids[2], ids[1])
        assertThat(account.get("/me/backups/latest").expect(200).long("id")).isEqualTo(ids[3])
        assertThat(stored(oldest)).isTrue()

        cleanup.deleteObjects()

        assertThat(stored(oldest)).isFalse()
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM object_deletions WHERE object_key = :key")
                .param("key", oldest)
                .query(Int::class.java)
                .single(),
        ).isZero()
        assertThat(stored(objectKey(ids[1]))).isTrue()
    }

    @Test
    fun `needs recovery material and a size within 64 MiB`() {
        val bare = signIn()
        bare.post("/me/backups", mapOf("size" to 10)).expectError(409, "recovery_missing")
        bare.get("/me/backups/latest").expectError(404, "not_found")

        val account = account()
        account.post("/me/backups", mapOf("size" to 0)).expectError(400, "invalid_request")
        account.post("/me/backups", mapOf("size" to 64L * 1024 * 1024 + 1)).expectError(413, "too_large")
        account.post("/me/backups", mapOf("size" to 64L * 1024 * 1024)).expect(201)
    }

    @Test
    fun `limits an account to ten backups a day`() {
        val account = account()
        repeat(10) { account.post("/me/backups", mapOf("size" to 10)).expect(201) }

        val refused = account.post("/me/backups", mapOf("size" to 10)).expectError(429, "rate_limited")

        assertThat(refused.headers.getFirst("Retry-After")?.toLong()).isBetween(1L, 86_400L)
        account().post("/me/backups", mapOf("size" to 10)).expect(201)
    }

    @Test
    fun `a replacement ticket lists and downloads, but cannot upload`() {
        val subject = "backup-ticket-${UUID.randomUUID()}"
        val account = account(subject)
        val body = Random.nextBytes(128)
        backUp(account, body)
        val ticket = Client(api, subject).stubSignIn().expect(200).string("ticket")

        assertThat(api.send(HttpMethod.GET, "/me/backups", token = ticket).expect(200)["backups"].size()).isEqualTo(1)
        val latest = api.send(HttpMethod.GET, "/me/backups/latest", token = ticket).expect(200)
        assertThat(get(latest.string("download_url")).body()).isEqualTo(body)
        api.send(HttpMethod.POST, "/me/backups", token = ticket, body = mapOf("size" to 10)).expectError(403, "replace_required")
    }

    @Test
    fun `drops uploads nobody completed after a day`() {
        val account = account()
        val stale = account.post("/me/backups", mapOf("size" to 10)).expect(201).long("id")
        val recent = account.post("/me/backups", mapOf("size" to 10)).expect(201).long("id")
        val key = objectKey(stale)
        jdbc.sql("UPDATE backups SET created_at = now() - interval '25 hours' WHERE id = :id").param("id", stale).update()

        cleanup.daily()

        assertThat(
            jdbc
                .sql("SELECT count(*) FROM backups WHERE id IN (:ids)")
                .param("ids", listOf(stale, recent))
                .query(Int::class.java)
                .single(),
        ).isEqualTo(1)
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM object_deletions WHERE object_key = :key")
                .param("key", key)
                .query(Int::class.java)
                .single(),
        ).isEqualTo(1)
    }
}

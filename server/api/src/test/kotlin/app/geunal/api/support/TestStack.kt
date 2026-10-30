package app.geunal.api.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration
import java.time.Instant

/** The images infra/docker-compose.yml runs, started once for every test class and reused across Spring contexts. */
object TestStack {
    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16.15").also { it.start() }

    val redis: GenericContainer<*> = GenericContainer("redis:7.4.11").withExposedPorts(REDIS_PORT).also { it.start() }

    const val REDIS_PORT = 6379

    val redisHost: String get() = redis.host
    val redisPort: Int get() = redis.getMappedPort(REDIS_PORT)

    const val S3_PORT = 8333
    const val S3_ACCESS_KEY = "geunal-test"
    const val S3_SECRET_KEY = "geunal-test-secret"
    const val BACKUPS_BUCKET = "geunal-test-backups"

    /**
     * SeaweedFS's S3 API with one identity, so it checks signatures the way NCP Object Storage does: a presigned URL
     * works only for the request it was signed for.
     */
    val s3: GenericContainer<*> =
        GenericContainer("chrislusf/seaweedfs:4.48")
            .withCommand("server", "-s3", "-dir=/data", "-s3.config=/etc/seaweedfs/s3.json")
            .withCopyToContainer(
                Transferable.of(
                    """
                    {"identities": [{"name": "geunal", "credentials": [{"accessKey": "$S3_ACCESS_KEY", "secretKey": "$S3_SECRET_KEY"}],
                      "actions": ["Admin", "Read", "Write", "List", "Tagging"]}]}
                    """.trimIndent(),
                ),
                "/etc/seaweedfs/s3.json",
            ).withExposedPorts(S3_PORT)
            .also {
                it.start()
                createBucket("http://${it.host}:${it.getMappedPort(S3_PORT)}")
            }

    val s3Endpoint: String get() = "http://${s3.host}:${s3.getMappedPort(S3_PORT)}"

    /** A client of the test store, as infra's bucket module would be: tests use it to look at what the API stored. */
    val s3Client: S3Client by lazy { client(s3Endpoint) }

    private fun client(endpoint: String): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
            .forcePathStyle(true)
            .build()

    /** SeaweedFS answers on its S3 port a little before its filer can take a bucket, so this tries for a while. */
    private fun createBucket(endpoint: String) {
        val client = client(endpoint)
        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        while (true) {
            try {
                client.createBucket { it.bucket(BACKUPS_BUCKET) }
                return
            } catch (e: SdkException) {
                if (Instant.now().isAfter(deadline)) throw e
                Thread.sleep(500)
            }
        }
    }
}

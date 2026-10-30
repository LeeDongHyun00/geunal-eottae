package app.geunal.api.backups

import app.geunal.api.common.ApiException
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

/**
 * `geunal.s3.*`: the S3-compatible object storage (NCP Object Storage, SeaweedFS locally). [publicEndpoint] is the
 * address devices reach, which presigned URLs are signed for; it defaults to [endpoint], where the API itself reaches
 * the store. Bucket names are the full names infra gives them (`<prefix>-backups`).
 */
@ConfigurationProperties("geunal.s3")
data class StorageProperties(
    val endpoint: String = "",
    val publicEndpoint: String = "",
    val region: String = "kr-standard",
    val accessKey: String = "",
    val secretKey: String = "",
    val backupsBucket: String = "backups",
)

/**
 * Presigned URLs and the few calls the API makes itself (a size check, deletes). Requests carry no checksum the
 * signature does not need, since S3-compatible stores differ in which checksum headers they understand.
 */
@Component
class ObjectStore(
    private val properties: StorageProperties,
    environment: Environment,
) : DisposableBean {
    init {
        check(properties.endpoint.isNotBlank() || !environment.matchesProfiles("prod")) {
            "geunal.s3.endpoint (GEUNAL_S3_ENDPOINT) and its keys must be set when the prod profile is active"
        }
    }

    /** The short bucket names of infra.md §1 that rows refer to, with the names this deployment gives them. */
    val buckets: Map<String, String> = mapOf(BACKUPS to properties.backupsBucket)

    private val credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey.ifBlank { "local" }, properties.secretKey.ifBlank { "local" }),
        )

    private val client: S3Client? =
        properties.endpoint.takeIf { it.isNotBlank() }?.let {
            S3Client
                .builder()
                .endpointOverride(URI(it))
                .region(Region.of(properties.region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(TIMEOUT).socketTimeout(TIMEOUT))
                .build()
        }

    /** False on a local or test stack without storage: backup calls then answer 500, and nothing is deleted. */
    val configured: Boolean get() = client != null

    private val presigner: S3Presigner? =
        properties.endpoint.takeIf { it.isNotBlank() }?.let {
            S3Presigner
                .builder()
                .endpointOverride(URI(properties.publicEndpoint.ifBlank { it }))
                .region(Region.of(properties.region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()
        }

    /** A PUT of exactly [size] bytes: `Content-Length` is a signed header, so a longer or shorter body is refused. */
    fun uploadUrl(
        bucket: String,
        key: String,
        size: Long,
        ttl: Duration,
    ): String {
        val put =
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .contentLength(size)
                .build()
        return presigner()
            .presignPutObject(
                PutObjectPresignRequest
                    .builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(put)
                    .build(),
            ).url()
            .toString()
    }

    fun downloadUrl(
        bucket: String,
        key: String,
        ttl: Duration,
    ): String {
        val get =
            GetObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build()
        return presigner()
            .presignGetObject(
                GetObjectPresignRequest
                    .builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(get)
                    .build(),
            ).url()
            .toString()
    }

    /** The stored object's size, or null when there is none. */
    fun size(
        bucket: String,
        key: String,
    ): Long? =
        try {
            client()
                .headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                ).contentLength()
        } catch (_: NoSuchKeyException) {
            null
        } catch (e: S3Exception) {
            if (e.statusCode() == HttpStatus.NOT_FOUND.value()) null else throw e
        }

    /** Deleting a key that is already gone succeeds, so a retried deletion is harmless. */
    fun delete(
        bucket: String,
        key: String,
    ) {
        client().deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
    }

    override fun destroy() {
        client?.close()
        presigner?.close()
    }

    private fun client(): S3Client = client ?: throw notConfigured()

    private fun presigner(): S3Presigner = presigner ?: throw notConfigured()

    private fun notConfigured() = ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "object storage is not configured")

    companion object {
        const val BACKUPS = "backups"
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

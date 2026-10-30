package app.geunal.api.auth

import app.geunal.api.common.ApiException
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.text.ParseException
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** The provider accounts a sign-in can name. [STUB] stands in for them on local and CI stacks (`GEUNAL_AUTH_STUB`). */
enum class Provider(
    val id: String,
) {
    KAKAO("kakao"),
    APPLE("apple"),
    STUB("stub"),
    ;

    companion object {
        /** `kakao` or `apple`: what a device may ask to sign in or link with. */
        fun linkable(id: String): Provider? = entries.firstOrNull { it.id == id && it != STUB }
    }
}

/**
 * Checks Kakao and Apple ID tokens (protocol §13): the provider's signature by its published JWKS, `iss`, `aud` and
 * expiry, then the `nonce` claim that binds the token to the device key of the request. A provider without its
 * configuration (AuthProperties) is off: its sign-in answers 404 and nothing here reaches out to it.
 */
@Component
class ProviderTokens(
    properties: AuthProperties,
) {
    private val kakao =
        properties.kakao.takeIf { it.enabled }?.let { Verifier(it.issuer, it.jwksUri, setOf(it.appKey)) }

    private val apple =
        properties.apple.takeIf { it.enabled }?.let {
            // A key that does not parse is a broken deployment, not a provider to switch off quietly.
            applePrivateKey(it.privateKey)
            Verifier(it.issuer, it.jwksUri, setOf(it.bundleId, it.serviceId))
        }

    fun enabled(provider: Provider): Boolean = verifier(provider) != null

    /**
     * The provider's `sub` for a sign-in or link token bound to [nonce] and [signatureKey], or 401
     * `provider_token_invalid`. A provider that is off answers 404.
     */
    fun subject(
        provider: Provider,
        idToken: String,
        nonce: String,
        signatureKey: ByteArray,
    ): String {
        val verifier = verifier(provider) ?: throw disabled(provider)
        val claims = verifier.verify(idToken) ?: throw invalidToken()
        if (claims.claims["nonce"] != binding(nonce, signatureKey)) throw invalidToken()
        return claims.subject
    }

    private fun verifier(provider: Provider): Verifier? =
        when (provider) {
            Provider.KAKAO -> kakao
            Provider.APPLE -> apple
            Provider.STUB -> null
        }

    /**
     * One provider's JWKS-backed check. The keys are cached for an hour and fetched again early only for a `kid` the
     * cache lacks, at most every 30 seconds: Kakao asks clients to cache its keys and may block frequent fetches.
     */
    private class Verifier(
        issuer: String,
        jwksUri: String,
        audiences: Set<String>,
    ) {
        private val processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                val retriever = DefaultResourceRetriever(TIMEOUT_MS, TIMEOUT_MS, JWKS_SIZE_LIMIT)
                val keys =
                    JWKSourceBuilder
                        .create<SecurityContext>(URI(jwksUri).toURL(), retriever)
                        .cache(TimeUnit.HOURS.toMillis(1), TIMEOUT_MS.toLong())
                        .rateLimited(TimeUnit.SECONDS.toMillis(30))
                        .retrying(true)
                        .build()
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier(
                        audiences,
                        JWTClaimsSet.Builder().issuer(issuer).build(),
                        setOf("sub", "iat", "exp"),
                        null,
                    )
            }

        /** The claims of a token that verifies, or null. Nothing about the token reaches a log (api-server.md §10). */
        fun verify(token: String): JWTClaimsSet? {
            if (token.length > MAX_TOKEN) return null
            return try {
                processor.process(token, null)
            } catch (_: ParseException) {
                null
            } catch (_: BadJOSEException) {
                null
            } catch (_: JOSEException) {
                null
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5000
        private const val JWKS_SIZE_LIMIT = 64 * 1024
        private const val MAX_TOKEN = 8192
        private val LOGIN_LABEL = "geunal login v1".toByteArray(Charsets.US_ASCII)

        /**
         * The value a device hands Kakao or Apple as the sign-in nonce: lowercase hex of SHA-256("geunal login v1" ‖
         * nonce ‖ signature_key), checked against core/vectors/login_nonce.json. A leaked token or nonce is useless with
         * any other device key.
         */
        fun binding(
            nonce: String,
            signatureKey: ByteArray,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(LOGIN_LABEL)
            digest.update(nonce.toByteArray(Charsets.US_ASCII))
            digest.update(signatureKey)
            return HexFormat.of().formatHex(digest.digest())
        }

        /** The Sign in with Apple key (.p8: PEM PKCS#8, P-256). An environment value may carry `\n` for its line breaks. */
        fun applePrivateKey(pem: String): ECPrivateKey {
            val body =
                pem
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot { it.isWhitespace() }
            val key =
                try {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("geunal.auth.apple.private-key is not a PEM PKCS#8 key", e)
                } catch (e: InvalidKeySpecException) {
                    throw IllegalStateException("geunal.auth.apple.private-key is not an EC private key", e)
                }
            return key as? ECPrivateKey ?: throw IllegalStateException("geunal.auth.apple.private-key is not an EC private key")
        }

        fun disabled(provider: Provider) =
            ApiException(HttpStatus.NOT_FOUND, "not_found", "${provider.id} sign-in is not configured on this server")

        fun invalidToken() =
            ApiException(HttpStatus.UNAUTHORIZED, "provider_token_invalid", "the provider token does not verify for this app and device")
    }
}

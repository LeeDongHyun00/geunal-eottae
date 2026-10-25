package app.geunal.api.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * The ES256 keys behind the access JWT. `geunal.auth.jwt.keys` holds a JWK Set; its first P-256 private key signs and
 * every key is published, so rotation means putting the new key first and keeping the old one until its tokens expire
 * (api-server.md §4). Without a configured set a key is generated for this process, which only a non-prod profile may do.
 */
@Component
class JwtKeys(
    properties: AuthProperties,
    environment: Environment,
) {
    init {
        check(properties.jwt.keys.isNotBlank() || !environment.matchesProfiles("prod")) {
            "geunal.auth.jwt.keys (GEUNAL_AUTH_JWT_KEYS) must hold a JWK Set when the prod profile is active"
        }
    }

    val set: JWKSet =
        if (properties.jwt.keys.isNotBlank()) {
            JWKSet(JWKSet.parse(properties.jwt.keys).keys.map(::withKeyId))
        } else {
            log.warn("no JWT key configured; signing with a key generated for this process")
            JWKSet(ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint(true).generate())
        }

    val signing: ECKey =
        set.keys.firstOrNull { it is ECKey && it.isPrivate && it.curve == Curve.P_256 }?.toECKey()
            ?: throw IllegalStateException("geunal.auth.jwt.keys holds no P-256 private key")

    /** What `/.well-known/jwks.json` serves: public parts only. */
    val public: JWKSet get() = set.toPublicJWKSet()

    /** The verifying key for a token header, or null for a `kid` this process does not know. */
    fun verifying(keyId: String?): ECKey? = keyId?.let { set.getKeyByKeyId(it) as? ECKey }

    private fun withKeyId(key: JWK): JWK = if (key.keyID != null || key !is ECKey) key else ECKey.Builder(key).keyIDFromThumbprint().build()

    private companion object {
        val log = LoggerFactory.getLogger(JwtKeys::class.java)
    }
}

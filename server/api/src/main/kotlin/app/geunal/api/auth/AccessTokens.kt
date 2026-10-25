package app.geunal.api.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

/** Issues and checks the access JWT: ES256, 15 minutes, `sub` = user_id and `did` = device_id (api-server.md §4). */
@Component
class AccessTokens(
    private val keys: JwtKeys,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    val ttl: Duration get() = properties.accessTtl

    fun issue(
        caller: Caller,
        ttl: Duration = properties.accessTtl,
    ): String {
        val now = clock.instant()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(caller.userId.toString())
                .claim(DEVICE_CLAIM, caller.deviceId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.ES256)
                .keyID(keys.signing.keyID)
                .type(JOSEObjectType.JWT)
                .build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(keys.signing)) }.serialize()
    }

    /** The caller a token names, or null when it is not one of ours any more: bad signature, unknown key, expired. */
    fun verify(token: String): Caller? {
        val jwt =
            try {
                SignedJWT.parse(token)
            } catch (_: ParseException) {
                return null
            }
        if (jwt.header.algorithm != JWSAlgorithm.ES256) return null
        val key = keys.verifying(jwt.header.keyID) ?: return null
        val valid =
            try {
                jwt.verify(ECDSAVerifier(key.toECPublicKey()))
            } catch (_: JOSEException) {
                false
            }
        if (!valid) return null
        val claims = jwt.jwtClaimsSet
        val expires = claims.expirationTime ?: return null
        if (!expires.toInstant().isAfter(clock.instant())) return null
        return try {
            Caller(UUID.fromString(claims.subject), UUID.fromString(claims.getStringClaim(DEVICE_CLAIM)))
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val DEVICE_CLAIM = "did"
    }
}

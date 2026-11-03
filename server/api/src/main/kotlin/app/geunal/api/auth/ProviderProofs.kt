package app.geunal.api.auth

import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRow
import org.springframework.stereotype.Component

/** A provider account proven again: which one, and for Apple the tokens its code was exchanged for. */
data class ProviderProof(
    val provider: Provider,
    val sub: String,
    val apple: AppleTokens? = null,
)

/**
 * A fresh proof that the caller still holds the account's Kakao or Apple account, for the two acts a stolen session
 * must not do: deleting the account and "새로 시작" past the 30 minutes (protocol §10, §13). Kakao: an ID token from a
 * sign-in made for this request, bound to [nonce] and the device's registered key. Apple: an authorization code whose
 * exchanged identity token names the account.
 */
@Component
class ProviderProofs(
    private val providers: ProviderTokens,
    private val apple: AppleClient,
    private val nonces: Nonces,
) {
    /** The proof, or null when it is missing, does not verify, or names another provider account than [links]. */
    fun prove(
        links: Map<Provider, String>,
        device: DeviceRow,
        kakaoIdToken: String?,
        nonce: String?,
        appleAuthorizationCode: String?,
    ): ProviderProof? =
        when {
            kakaoIdToken != null -> {
                val sub =
                    try {
                        if (nonces.spend(nonce)) providers.subject(Provider.KAKAO, kakaoIdToken, nonce!!, device.signatureKey) else null
                    } catch (_: ApiException) {
                        null
                    }
                sub?.takeIf { it == links[Provider.KAKAO] }?.let { ProviderProof(Provider.KAKAO, it) }
            }
            appleAuthorizationCode != null && links.containsKey(Provider.APPLE) -> {
                val tokens = apple.exchange(appleAuthorizationCode, device.platform)
                tokens?.takeIf { it.sub == links[Provider.APPLE] }?.let { ProviderProof(Provider.APPLE, it.sub, it) }
            }
            else -> null
        }
}

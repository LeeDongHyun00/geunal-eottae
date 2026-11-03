package app.geunal.feature.account.providers

import app.geunal.core.CoreInterface
import app.geunal.core.DeletionProof
import app.geunal.core.Provider

/**
 * A fresh sign-in with the account's own provider, which the server checks before it deletes the account or its
 * backups (protocol §13): Kakao's ID token with the core's nonce, or Apple's one-time authorization code. Signing in
 * this way changes nothing on the device; the session stays as it was.
 */
object ProviderProof {
    /** Kakao first when both are linked, as on iOS; `null` for a guest, whose proof the core signs itself. */
    fun providerOf(linked: List<Provider>): Provider? =
        when {
            Provider.KAKAO in linked -> Provider.KAKAO
            Provider.APPLE in linked -> Provider.APPLE
            else -> null
        }

    suspend fun make(
        provider: Provider,
        core: CoreInterface,
        providers: ProviderSignIn,
    ): DeletionProof {
        val nonce = core.signInNonce()
        return when (provider) {
            Provider.KAKAO -> DeletionProof.Kakao(nonce = nonce.nonce, idToken = providers.kakao(nonce.providerNonce).idToken)
            Provider.APPLE -> {
                val code =
                    providers.apple(nonce.providerNonce).authorizationCode
                        ?: throw ProviderSignInException.Unavailable("apple authorization code")
                DeletionProof.Apple(authorizationCode = code)
            }
        }
    }
}

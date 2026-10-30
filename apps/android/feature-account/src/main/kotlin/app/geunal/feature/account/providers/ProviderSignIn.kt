package app.geunal.feature.account.providers

/**
 * What a Kakao or Apple sign-in gives the app: the OIDC token the core sends to the server, and the name the account is
 * made with when it is new.
 */
data class ProviderCredential(
    val idToken: String,
    /** The provider's name for the person, when it has one; the core fills in a default otherwise. */
    val displayName: String? = null,
    /**
     * Apple only: the one-time code the web callback also carries. Sign-in drops it; only account deletion sends it, to
     * let the server revoke Apple's tokens (protocol §13). It is never stored or logged.
     */
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "ProviderCredential(…)"
}

sealed class ProviderSignInException(
    message: String,
) : Exception(message) {
    /** The person closed the provider's screen. Not an error to show. */
    class Cancelled : ProviderSignInException("cancelled")

    /** The provider is not set up in this build (no Kakao key, no Apple service ID or domain), or gave no ID token. */
    class Unavailable(
        what: String,
    ) : ProviderSignInException(what)

    /** The provider answered with an error; only its kind is kept, never a token or a body. */
    class Failed(
        kind: String,
    ) : ProviderSignInException(kind)
}

/**
 * The two providers behind one seam, so the sign-in model is tested without them. Both take the core's
 * `providerNonce` as the OIDC nonce, which binds the token to this device's key (protocol §13 "nonce 묶기").
 */
interface ProviderSignIn {
    suspend fun kakao(providerNonce: String): ProviderCredential

    suspend fun apple(providerNonce: String): ProviderCredential
}

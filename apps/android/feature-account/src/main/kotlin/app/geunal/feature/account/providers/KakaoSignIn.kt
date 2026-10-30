package app.geunal.feature.account.providers

import android.content.Context
import com.kakao.sdk.auth.TokenManageable
import com.kakao.sdk.auth.TokenManagerProvider
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Kakao Login through the Kakao SDK: KakaoTalk when it is installed, the Kakao account web login otherwise. The SDK
 * sends the authorization code with PKCE (S256, a SHA-512 verifier) on both paths. The app needs only the OIDC ID token
 * and the nickname; right after reading them it logs out of the SDK, which expires Kakao's access and refresh tokens on
 * Kakao's server and drops them from memory, the only place [configure] lets the SDK keep them.
 */
class KakaoSignIn(
    private val appKey: String,
    private val activity: () -> android.app.Activity? = ForegroundActivity::get,
) {
    val isConfigured: Boolean get() = appKey.isNotEmpty()

    suspend fun signIn(providerNonce: String): ProviderCredential {
        if (!isConfigured) throw ProviderSignInException.Unavailable("kakao app key")
        val context = activity() ?: throw ProviderSignInException.Unavailable("no activity")
        val token = login(context, providerNonce)
        try {
            // Without OpenID Connect turned on for the app, Kakao answers with no ID token.
            val idToken = token.idToken ?: throw ProviderSignInException.Unavailable("kakao id token")
            return ProviderCredential(idToken = idToken, displayName = nickname())
        } finally {
            // Every path after the login expires Kakao's tokens, a missing ID token and a cancelled sign-in included.
            withContext(NonCancellable) { logout() }
        }
    }

    private suspend fun login(
        context: Context,
        nonce: String,
    ): OAuthToken {
        val client = UserApiClient.instance
        if (client.isKakaoTalkLoginAvailable(context)) {
            try {
                return suspendCancellableCoroutine { continuation ->
                    client.loginWithKakaoTalk(context, nonce = nonce) { token, error -> continuation.complete(token, error) }
                }
            } catch (e: ProviderSignInException.Cancelled) {
                throw e
            } catch (e: ProviderSignInException) {
                // KakaoTalk without a signed-in account, or an outdated KakaoTalk: the account login still works.
            }
        }
        return suspendCancellableCoroutine { continuation ->
            client.loginWithKakaoAccount(context, nonce = nonce) { token, error -> continuation.complete(token, error) }
        }
    }

    private suspend fun nickname(): String? =
        suspendCancellableCoroutine { continuation ->
            UserApiClient.instance.me { user, _ -> continuation.resume(user?.kakaoAccount?.profile?.nickname) }
        }

    private suspend fun logout() =
        suspendCancellableCoroutine { continuation ->
            // The SDK deletes its stored tokens whatever the server answers.
            UserApiClient.instance.logout { continuation.resume(Unit) }
        }

    private fun kotlinx.coroutines.CancellableContinuation<OAuthToken>.complete(
        token: OAuthToken?,
        error: Throwable?,
    ) {
        when {
            error is ClientError && error.reason == ClientErrorCause.Cancelled ->
                resumeWithException(ProviderSignInException.Cancelled())
            error != null -> resumeWithException(ProviderSignInException.Failed(error.javaClass.simpleName))
            token != null -> resume(token)
            else -> resumeWithException(ProviderSignInException.Failed("empty"))
        }
    }

    companion object {
        /**
         * The SDK's app-wide setup, with its logging off so no token or verifier reaches logcat. The SDK would keep its
         * tokens in SharedPreferences, which backups and device transfers copy; every place it reads or writes them
         * asks the provider each time, so swapping the manager right after init keeps them in memory only.
         */
        fun configure(
            context: Context,
            appKey: String,
        ) {
            if (appKey.isEmpty()) return
            KakaoSdk.init(context, appKey, loggingEnabled = false)
            TokenManagerProvider.instance.manager = MemoryTokenManager()
        }
    }
}

/** Kakao's tokens for as long as the process lives: the app uses them only for `me` and `logout` right after the login. */
internal class MemoryTokenManager : TokenManageable {
    @Volatile private var token: OAuthToken? = null

    override fun getToken(): OAuthToken? = token

    override fun setToken(token: OAuthToken) {
        this.token = token
    }

    override fun clear() {
        token = null
    }
}

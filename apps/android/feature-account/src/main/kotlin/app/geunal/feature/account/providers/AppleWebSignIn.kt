package app.geunal.feature.account.providers

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Sign in with Apple on Android, which has no Apple SDK: the web authorization in a Custom Tab (protocol §13
 * "안드로이드의 Apple 로그인").
 *
 * Apple posts the result to the API server's `/auth/apple/callback`, which sends the browser on to the App Link
 * `https://<host>/auth/apple#code=…&id_token=…&state=…&user=…`. [AppleSignInCallbackActivity] receives that link and
 * hands it here; the `state` minted for this attempt must come back, or the result is ignored. The fragment never
 * reaches a server, and the code is kept only for account deletion.
 */
class AppleWebSignIn(
    private val serviceId: String,
    private val apiBase: String,
    private val appLinkHost: String,
    private val activity: () -> Activity? = ForegroundActivity::get,
) {
    val isConfigured: Boolean
        get() = serviceId.isNotEmpty() && appLinkHost.isNotEmpty() && apiBase.startsWith("https://")

    suspend fun signIn(providerNonce: String): ProviderCredential {
        if (!isConfigured) throw ProviderSignInException.Unavailable("apple service id or domain")
        val host = activity() ?: throw ProviderSignInException.Unavailable("no activity")
        val state = newState()
        val pending = AppleCallbacks.expect(state)
        try {
            CustomTabsIntent
                .Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(host, authorizeUri(providerNonce, state))
            AppleCallbacks.launched()
            return pending.await()
        } finally {
            AppleCallbacks.clear(state)
        }
    }

    internal fun authorizeUri(
        nonce: String,
        state: String,
    ): Uri =
        "https://appleid.apple.com/auth/authorize"
            .toUri()
            .buildUpon()
            .appendQueryParameter("client_id", serviceId)
            .appendQueryParameter("redirect_uri", apiBase.trimEnd('/') + "/auth/apple/callback")
            .appendQueryParameter("response_type", "code id_token")
            // The name is only for a new account's display name; the email is not asked for at all.
            .appendQueryParameter("scope", "name")
            .appendQueryParameter("response_mode", "form_post")
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .build()

    private fun newState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** The callback parameters of the App Link fragment. */
internal data class AppleCallback(
    val state: String?,
    val code: String?,
    val idToken: String?,
    val user: String?,
    val error: String?,
) {
    override fun toString(): String = "AppleCallback(…)"

    companion object {
        /** `code=…&id_token=…&state=…&user=…`, each value percent-encoded by the server. */
        fun parse(fragment: String?): AppleCallback {
            val values =
                fragment
                    .orEmpty()
                    .split('&')
                    .mapNotNull { part ->
                        val equals = part.indexOf('=')
                        if (equals <= 0) return@mapNotNull null
                        part.substring(0, equals) to URLDecoder.decode(part.substring(equals + 1), "UTF-8")
                    }.toMap()
            return AppleCallback(values["state"], values["code"], values["id_token"], values["user"], values["error"])
        }
    }
}

/**
 * The one Apple sign-in in flight, between the Custom Tab and the activity that receives its result. A result whose
 * state does not match is dropped: it was not started by this attempt.
 */
internal object AppleCallbacks {
    private class Pending(
        val state: String,
        val result: CompletableDeferred<ProviderCredential>,
        var launched: Boolean = false,
    )

    private var pending: Pending? = null

    private val resumed: (Activity) -> Unit = { onHostResumed() }

    @Synchronized
    fun expect(state: String): CompletableDeferred<ProviderCredential> {
        pending?.result?.completeExceptionally(ProviderSignInException.Cancelled())
        val result = CompletableDeferred<ProviderCredential>()
        pending = Pending(state, result)
        ForegroundActivity.addResumeListener(resumed)
        return result
    }

    @Synchronized
    fun launched() {
        pending?.launched = true
    }

    @Synchronized
    fun clear(state: String) {
        if (pending?.state == state) {
            pending = null
            ForegroundActivity.removeResumeListener(resumed)
        }
    }

    /** From [AppleSignInCallbackActivity], before it brings the app back to the front. */
    @Synchronized
    fun deliver(fragment: String?) {
        val current = pending ?: return
        val callback = AppleCallback.parse(fragment)
        if (callback.state != current.state) return
        val idToken = callback.idToken
        if (callback.error != null || idToken.isNullOrEmpty()) {
            val cancelled = callback.error == "user_cancelled_authorize"
            current.result.completeExceptionally(
                if (cancelled) ProviderSignInException.Cancelled() else ProviderSignInException.Failed("apple"),
            )
        } else {
            current.result.complete(
                ProviderCredential(idToken = idToken, displayName = displayName(callback.user), authorizationCode = callback.code),
            )
        }
    }

    /**
     * The app came back to the front. With a launched attempt still open, the person closed the Custom Tab without
     * finishing: the callback activity always delivers before the app resumes.
     */
    @Synchronized
    fun onHostResumed() {
        val current = pending ?: return
        if (current.launched && !current.result.isCompleted) {
            current.result.completeExceptionally(ProviderSignInException.Cancelled())
        }
    }

    /**
     * Apple sends the name on the first authorization only, as `{"name":{"firstName":…,"lastName":…}}`. A Korean name
     * reads family name first and without a space.
     */
    fun displayName(user: String?): String? {
        if (user.isNullOrBlank()) return null
        val name =
            runCatching { Json.parseToJsonElement(user).jsonObject["name"] as? JsonObject }.getOrNull() ?: return null
        val first =
            name["firstName"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()
        val last =
            name["lastName"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()
        val hangul = Regex("\\p{IsHangul}")
        return when {
            first.isEmpty() && last.isEmpty() -> null
            hangul.containsMatchIn(first + last) -> last + first
            else -> listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}

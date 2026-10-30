package app.geunal.feature.account.support

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.geunal.feature.account.R
import app.geunal.feature.account.providers.ForegroundActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Why the OS asks; the prompt shows it under "본인 확인" (tone.md "본인 확인"). */
enum class DeviceAuthPurpose(
    @param:StringRes val reason: Int,
) {
    WIPE_BACKUPS(R.string.device_auth_wipe_backups),
}

/**
 * The OS's check that the person holding the phone is its owner, before the few actions that a stranger with an
 * unlocked phone must not take (m1.md §4 "기기 인증", decision 26). The app draws nothing for it.
 */
interface DeviceAuth {
    /** False when the phone has no screen lock. Then nothing is asked and the screen suggests turning one on. */
    fun isDeviceSecure(): Boolean

    /** True when the owner passed, or when there is no screen lock to ask for; false when they cancelled or failed. */
    suspend fun confirm(purpose: DeviceAuthPurpose): Boolean
}

/** [DeviceAuth] through androidx.biometric: a biometric or the screen lock (PIN, pattern, password), whichever they use. */
class SystemDeviceAuth(
    private val context: Context,
    private val activity: () -> android.app.Activity? = ForegroundActivity::get,
) : DeviceAuth {
    override fun isDeviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun confirm(purpose: DeviceAuthPurpose): Boolean {
        if (!isDeviceSecure()) return true
        val host = activity() as? FragmentActivity ?: return false
        return suspendCancellableCoroutine { continuation ->
            val callback =
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        // The lock was removed while the prompt came up: the same as a phone without one.
                        if (continuation.isActive) continuation.resume(errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)
                    }

                    // A rejected fingerprint keeps the prompt up for another try; only the error above ends it.
                }
            val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), callback)
            val info =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(host.getString(R.string.device_auth_title))
                    .setSubtitle(host.getString(purpose.reason))
                    .setAllowedAuthenticators(authenticators())
                    .build()
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info)
        }
    }

    companion object {
        /**
         * A strong biometric or the screen lock. androidx.biometric refuses that pair on Android 9 and 10 only
         * (`AuthenticatorUtils.isSupportedCombination`), where a weak biometric or the screen lock is the closest.
         */
        fun authenticators(sdk: Int = Build.VERSION.SDK_INT): Int =
            if (sdk == Build.VERSION_CODES.P || sdk == Build.VERSION_CODES.Q) {
                BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            } else {
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            }
    }
}

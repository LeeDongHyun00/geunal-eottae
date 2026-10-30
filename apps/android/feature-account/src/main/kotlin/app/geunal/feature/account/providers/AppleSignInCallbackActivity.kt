package app.geunal.feature.account.providers

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the App Link `https://<host>/auth/apple#…` that ends the web sign-in, hands its fragment to the sign-in in
 * flight and goes back to the app, which closes the Custom Tab above it. It shows nothing itself.
 */
class AppleSignInCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppleCallbacks.deliver(intent?.data?.encodedFragment)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }
        finish()
    }
}

package app.geunal

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.geunal.bridge.InviteLink
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.account.security.SensitiveClipboard
import app.geunal.ui.root.RootScreen

/** A FragmentActivity because the OS's owner check (androidx.biometric's BiometricPrompt) runs in one. */
class MainActivity : FragmentActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus first: from Android 10 an app reads the clipboard only while its window has it.
        if (hasFocus && !leftoverChecked) {
            leftoverChecked = true
            SensitiveClipboard(this).clearLeftover()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GeunalApplication).container
        // Only a fresh start: a recreated activity gets the same intent again, and the invite was kept the first time.
        if (savedInstanceState == null) takeInvite(intent)
        setContent {
            GeunalTheme {
                RootScreen(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeInvite(intent)
    }

    /** Keeps the invite link an App Link brought in; the group screens take it once the account is ready. */
    private fun takeInvite(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = InviteLink.fromLink(intent.dataString ?: return, BuildConfig.INVITE_HOST) ?: return
        (application as GeunalApplication).container.invites.offer(link)
    }

    private companion object {
        /**
         * Once per process: a code copied in this process is cleared by its own minute, and the check is for the one
         * an earlier process left behind.
         */
        var leftoverChecked = false
    }
}

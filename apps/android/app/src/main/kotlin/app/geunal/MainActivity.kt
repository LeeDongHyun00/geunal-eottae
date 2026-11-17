package app.geunal

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.account.security.SensitiveClipboard
import app.geunal.feature.calendar.alerts.AlertIntents
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
        // Only a fresh launch: a rotation or a task restored from the recents list hands the same intent over again.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) openAlert(intent)
        setContent {
            GeunalTheme {
                RootScreen(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openAlert(intent)
    }

    /** A tapped reminder: the root takes its route once the tabs are there (m1.md §2.6). */
    private fun openAlert(intent: Intent) {
        val route = AlertIntents.route(intent) ?: return
        val container = (application as GeunalApplication).container
        // The view model the root uses, from the same store under the same key.
        ViewModelProvider(this, AppViewModel.factory(container))[AppViewModel::class.java].open(route)
    }

    private companion object {
        /**
         * Once per process: a code copied in this process is cleared by its own minute, and the check is for the one
         * an earlier process left behind.
         */
        var leftoverChecked = false
    }
}

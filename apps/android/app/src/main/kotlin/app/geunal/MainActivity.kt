package app.geunal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.geunal.designsystem.GeunalTheme
import app.geunal.ui.root.RootScreen

/** A FragmentActivity because the OS's owner check (androidx.biometric's BiometricPrompt) runs in one. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GeunalApplication).container
        setContent {
            GeunalTheme {
                RootScreen(container)
            }
        }
    }
}

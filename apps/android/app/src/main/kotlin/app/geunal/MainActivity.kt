package app.geunal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.geunal.designsystem.GeunalTheme
import app.geunal.ui.corestatus.CoreStatusScreen
import app.geunal.ui.corestatus.CoreStatusViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GeunalApplication).container
        setContent {
            GeunalTheme {
                val viewModel: CoreStatusViewModel =
                    viewModel(factory = CoreStatusViewModel.factory(container::openCore))
                val status by viewModel.status.collectAsStateWithLifecycle()
                CoreStatusScreen(status = status, apiBase = container.apiBase)
            }
        }
    }
}

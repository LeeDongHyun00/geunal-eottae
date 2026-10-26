package app.geunal.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.geunal.BuildConfig
import app.geunal.core.Change
import app.geunal.core.CoreInterface
import app.geunal.ui.corestatus.CoreStatusScreen
import app.geunal.ui.corestatus.CoreStatusViewModel
import kotlinx.coroutines.flow.Flow

/**
 * The calendar tab. Until the month, week and list views land (docs/design/m1.md screens 4, 7, 8), it keeps the M0
 * check that the core runs and syncs on the device.
 */
@Composable
fun CalendarTab(
    core: CoreInterface,
    changes: Flow<Change>,
    modifier: Modifier = Modifier,
) {
    val model = viewModel { CoreStatusViewModel(core, changes) }
    val status by model.status.collectAsStateWithLifecycle()
    CoreStatusScreen(status = status, apiBase = BuildConfig.API_BASE, modifier = modifier)
}

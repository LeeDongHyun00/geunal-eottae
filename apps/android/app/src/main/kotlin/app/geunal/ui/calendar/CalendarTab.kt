package app.geunal.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.geunal.core.Change
import app.geunal.core.CoreInterface
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.quickentry.ResourceEventTexts
import app.geunal.feature.calendar.screen.CalendarCovers
import app.geunal.feature.calendar.screen.CalendarScreen
import app.geunal.feature.calendar.screen.CalendarViewModel
import app.geunal.feature.calendar.screen.PreferencesViewKindStore
import kotlinx.coroutines.flow.Flow

/**
 * The calendar tab: the month, week and list views with the quick entry (docs/design/m1.md screens 4–8). The editor and
 * the shift stamp screen open as [covers] over the tabs.
 */
@Composable
fun CalendarTab(
    core: CoreInterface,
    changes: Flow<Change>,
    toasts: ToastCenter,
    covers: CalendarCovers,
    modifier: Modifier = Modifier,
) {
    // The application's, not the activity's: the view model outlives a rotation.
    val context = LocalContext.current.applicationContext
    val model =
        viewModel {
            CalendarViewModel(
                core = core,
                toasts = toasts,
                texts = ResourceEventTexts(context.resources),
                changes = changes,
                store = PreferencesViewKindStore(context),
            )
        }
    CalendarScreen(model = model, onEdit = covers::edit, onStamp = covers::stamp, modifier = modifier)
}

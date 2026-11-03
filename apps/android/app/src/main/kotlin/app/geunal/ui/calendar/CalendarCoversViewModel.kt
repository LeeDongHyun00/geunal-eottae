package app.geunal.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.CoreInterface
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.calendar.alerts.NotificationAccess
import app.geunal.feature.calendar.people.ContactsReader
import app.geunal.feature.calendar.screen.CalendarCovers
import app.geunal.ui.me.MeCovers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The full-screen covers over the tabs: the calendar's (the editor, the shift stamp screen and 나's calendar screens)
 * and 나's account screens. A view model keeps them, and the work their models started, across a rotation.
 */
class CalendarCoversViewModel(
    core: CoreInterface,
    contacts: ContactsReader,
    notifications: NotificationAccess,
    deviceAuth: DeviceAuth,
    providers: ProviderSignIn,
    accountEnded: Flow<Unit>,
    onAccountDeleted: () -> Unit,
) : ViewModel() {
    val covers = CalendarCovers(core, viewModelScope, contacts, notifications)

    val me = MeCovers(core, viewModelScope, deviceAuth, providers, onAccountDeleted)

    init {
        // The view model outlives onboarding in the activity's store. Whatever ended the account (this screen or a sync
        // that found it deleted), the covers go now, so none comes back over the tabs holding the old account's words.
        viewModelScope.launch {
            accountEnded.collect {
                covers.stack.clear()
                me.clear()
            }
        }
    }
}

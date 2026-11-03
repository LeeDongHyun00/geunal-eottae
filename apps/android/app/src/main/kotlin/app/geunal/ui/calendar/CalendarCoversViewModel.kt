package app.geunal.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.CoreInterface
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.calendar.alerts.NotificationAccess
import app.geunal.feature.calendar.people.ContactsReader
import app.geunal.feature.calendar.screen.CalendarCovers
import app.geunal.ui.me.MeCovers

/**
 * The full-screen covers over the tabs: the calendar's (the editor, the shift stamp screen and 나's calendar screens)
 * and 나's account screens. A view model keeps them, and the work their models started, across a rotation.
 */
class CalendarCoversViewModel(
    core: CoreInterface,
    contacts: ContactsReader,
    notifications: NotificationAccess,
    deviceAuth: DeviceAuth,
) : ViewModel() {
    val covers = CalendarCovers(core, viewModelScope, contacts, notifications)

    val me = MeCovers(core, viewModelScope, deviceAuth)
}

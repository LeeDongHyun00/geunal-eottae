package app.geunal.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.CoreInterface
import app.geunal.feature.calendar.screen.CalendarCovers

/**
 * The calendar's full-screen covers over the tabs (the editor and the shift stamp screen). A view model keeps them, and
 * the work their models started, across a rotation.
 */
class CalendarCoversViewModel(
    core: CoreInterface,
) : ViewModel() {
    val covers = CalendarCovers(core, viewModelScope)
}

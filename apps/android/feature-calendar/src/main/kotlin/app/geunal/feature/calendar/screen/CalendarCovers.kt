package app.geunal.feature.calendar.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import app.geunal.core.CoreInterface
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.editor.EditorModel
import app.geunal.feature.calendar.editor.EditorScreen
import app.geunal.feature.calendar.editor.EditorTarget
import app.geunal.feature.calendar.people.ContactsReader
import app.geunal.feature.calendar.people.PeopleModel
import app.geunal.feature.calendar.people.PeopleScreen
import app.geunal.feature.calendar.shift.ShiftStampModel
import app.geunal.feature.calendar.shift.ShiftStampScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** A screen the calendar opens over the tabs: the editor and the shift stamp screen (m1.md 9, 10), and 사람·생일 (11). */
sealed interface CalendarCover {
    class Editor(
        val model: EditorModel,
    ) : CalendarCover

    class Shifts(
        val model: ShiftStampModel,
    ) : CalendarCover

    class People(
        val model: PeopleModel,
    ) : CalendarCover
}

/**
 * The covers on screen, the last one on top. The app draws them over the tabs and turns its back gesture into [back];
 * the models work in [scope], which belongs to the app and outlives a rotation.
 */
class CalendarCovers(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    /** The device's contacts, for 사람·생일's import. */
    private val contacts: ContactsReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val stack: SnapshotStateList<CalendarCover> = mutableStateListOf()

    fun edit(target: EditorTarget) {
        stack += CalendarCover.Editor(EditorModel(target, core, scope, io))
    }

    fun stamp(month: String) {
        stack += CalendarCover.Shifts(ShiftStampModel(month, core, scope, io))
    }

    /** 사람·생일, opening [focus]'s sheet on arrival when a birthday led here (m1.md §2.6). */
    fun people(focus: String? = null) {
        stack += CalendarCover.People(PeopleModel(core, scope, contacts, io, focus))
    }

    /** Takes [cover] away wherever it is: a save that lands after back was pressed must not close what is on top now. */
    fun close(cover: CalendarCover) {
        stack.remove(cover)
    }

    fun back() {
        stack.removeLastOrNull()
    }
}

/** A cover's screen; [toasts] is the app's, for a cover that says something after it worked (사람·생일's import). */
@Composable
fun CalendarCoverContent(
    cover: CalendarCover,
    covers: CalendarCovers,
    toasts: ToastCenter,
    modifier: Modifier = Modifier,
) {
    when (cover) {
        is CalendarCover.Editor -> EditorScreen(model = cover.model, onClose = { covers.close(cover) }, modifier = modifier)
        is CalendarCover.Shifts -> ShiftStampScreen(model = cover.model, onClose = { covers.close(cover) }, modifier = modifier)
        is CalendarCover.People ->
            PeopleScreen(model = cover.model, toasts = toasts, onClose = { covers.close(cover) }, modifier = modifier)
    }
}

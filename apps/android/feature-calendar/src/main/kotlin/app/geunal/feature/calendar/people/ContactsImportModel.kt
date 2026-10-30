package app.geunal.feature.calendar.people

import app.geunal.core.ContactImport
import app.geunal.core.ContactInput
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "연락처에서 생일 가져오기" (m1.md §2.4): the permission, then the contacts with a birthday, none of them chosen to
 * start with (docs/security/app-review.md §2 "연락처": only the contacts the person picks), and one `importContacts`
 * for the chosen. It lives with the people screen's model, so a rotation keeps the choices and a pending question.
 */
class ContactsImportModel(
    private val reader: ContactsReader,
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onFinished: (ContactImport) -> Unit = {},
) {
    enum class Phase { CHECKING, DENIED, PICKING, IMPORTING }

    data class State(
        val phase: Phase = Phase.CHECKING,
        val candidates: List<ContactInput> = emptyList(),
        val withoutBirthday: Int = 0,
        val selected: Set<String> = emptySet(),
        val failed: Boolean = false,
    ) {
        val allSelected: Boolean get() = candidates.isNotEmpty() && selected.size == candidates.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The system's question is up; another start (a rotation) must not ask twice. */
    private var asking = false

    /** Reads at once with the permission; without it, [ask] puts the system's question up. */
    fun start(ask: () -> Unit) {
        val phase = _state.value.phase
        if (phase != Phase.CHECKING || asking) return
        if (reader.canRead()) {
            launchRead()
        } else {
            asking = true
            ask()
        }
    }

    fun answered(granted: Boolean) {
        asking = false
        if (granted) launchRead() else _state.update { it.copy(phase = Phase.DENIED) }
    }

    /** Back from "설정에서 켜기" with the permission on: the contacts at once. */
    fun resumed() {
        if (_state.value.phase == Phase.DENIED && reader.canRead()) launchRead()
    }

    fun toggle(contactId: String) {
        _state.update { state ->
            if (state.phase != Phase.PICKING || state.candidates.none { it.contactId == contactId }) return@update state
            state.copy(selected = if (contactId in state.selected) state.selected - contactId else state.selected + contactId)
        }
    }

    fun toggleAll() {
        _state.update { state ->
            if (state.phase != Phase.PICKING) return@update state
            state.copy(selected = if (state.allSelected) emptySet() else state.candidates.map { it.contactId }.toSet())
        }
    }

    /** The chosen contacts to the core at once; a refusal keeps the choices for another try. */
    suspend fun importChosen() {
        val current = _state.value
        if (current.phase != Phase.PICKING || current.selected.isEmpty()) return
        _state.update { it.copy(phase = Phase.IMPORTING, failed = false) }
        val chosen = current.candidates.filter { it.contactId in current.selected }
        val result =
            try {
                withContext(io) { core.importContacts(chosen) }
            } catch (e: CoreException) {
                null
            } catch (e: InternalException) {
                null
            }
        if (result == null) {
            _state.update { it.copy(phase = Phase.PICKING, failed = true) }
        } else {
            onFinished(result)
        }
    }

    fun launchImport() {
        scope.launch { importChosen() }
    }

    private fun launchRead() {
        scope.launch {
            val found = reader.birthdays()
            _state.value = State(phase = Phase.PICKING, candidates = found.withBirthday, withoutBirthday = found.withoutBirthday)
        }
    }
}

package app.geunal.feature.calendar.people

import app.geunal.core.ContactImport
import app.geunal.core.CoreInterface
import app.geunal.core.Person
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 사람·생일 (m1.md screen 11): the birthdays of the next 30 days, everyone with a search over names, and the import
 * from contacts. The person sheet's and the import's models live here too, so a rotation keeps what was typed or
 * chosen. [focus] opens a person on arrival, for the next-up bar's birthday and the birthday notification (§2.6).
 */
class PeopleModel(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val contacts: ContactsReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val focus: String? = null,
    private val now: () -> Instant = Instant::now,
) {
    data class State(
        val people: List<Person> = emptyList(),
        /** The birthdays in the next 30 days, soonest first. */
        val upcoming: List<Person> = emptyList(),
        val query: String = "",
        /** The Korean day the lists were read on; the D-days and "올해는" are as of it. */
        val today: String = "",
        val hasLoaded: Boolean = false,
    ) {
        private val needle: String get() = query.trim()

        /** Everyone whose name holds the search, in the core's order. */
        val shownPeople: List<Person>
            get() = if (needle.isEmpty()) people else people.filter { it.name.contains(needle, ignoreCase = true) }

        /** The search narrows the coming birthdays too. */
        val shownUpcoming: List<Person>
            get() = if (needle.isEmpty()) upcoming else upcoming.filter { it.name.contains(needle, ignoreCase = true) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _editing = MutableStateFlow<PersonEditModel?>(null)
    val editing: StateFlow<PersonEditModel?> = _editing.asStateFlow()

    private val _importing = MutableStateFlow<ContactsImportModel?>(null)
    val importing: StateFlow<ContactsImportModel?> = _importing.asStateFlow()

    /** How the last import went, until the screen has said so. */
    private val _imported = MutableStateFlow<ContactImport?>(null)
    val imported: StateFlow<ContactImport?> = _imported.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            load()
            focus?.let { id ->
                _state.value.people
                    .firstOrNull { it.id == id }
                    ?.let(::edit)
            }
        }
    }

    suspend fun load() {
        val today = KoreanCalendar.day(now())
        val people = withContext(io) { coreRead { core.people() } }
        val upcoming = withContext(io) { coreRead { core.upcomingBirthdays(UPCOMING_DAYS) } }
        _state.update {
            it.copy(
                people = people ?: it.people,
                upcoming = upcoming ?: it.upcoming,
                today = today,
                hasLoaded = true,
            )
        }
    }

    /** Back in front: the lists again if the day turned while away, so the D-days move at midnight. */
    fun resumed() {
        if (!_state.value.hasLoaded || _state.value.today == KoreanCalendar.day(now())) return
        scope.launch { load() }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** "＋" with no one, or a row with its person. */
    fun edit(person: Person?) {
        _editing.value = PersonEditModel(person, core, scope, io, onDone = ::edited, now = now)
    }

    fun closeEditor() {
        _editing.value = null
    }

    /** "연락처에서 생일 가져오기", also from the empty list and the new person's sheet (m1.md §2.4). */
    fun openImport() {
        _editing.value = null
        _importing.value = ContactsImportModel(contacts, core, scope, io, onFinished = ::finishedImport)
    }

    fun closeImport() {
        _importing.value = null
    }

    /** "직접 추가" from the import: the new person's sheet in its place. */
    fun addByHand() {
        _importing.value = null
        edit(null)
    }

    fun importShown() {
        _imported.value = null
    }

    private fun finishedImport(result: ContactImport) {
        _importing.value = null
        _imported.value = result
        scope.launch { load() }
    }

    /** After the sheet saved or deleted: the lists again, the sheet closed. */
    private fun edited() {
        _editing.value = null
        scope.launch { load() }
    }

    companion object {
        /** "다가오는 생일" looks a month ahead (m1.md screen 11 `people.upcoming(days: 30)`). */
        val UPCOMING_DAYS: UShort = 30u
    }
}

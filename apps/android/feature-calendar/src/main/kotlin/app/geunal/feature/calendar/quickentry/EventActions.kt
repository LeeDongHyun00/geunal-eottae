package app.geunal.feature.calendar.quickentry

import android.content.res.Resources
import app.geunal.core.AddedEvent
import app.geunal.core.CoreInterface
import app.geunal.core.EditScope
import app.geunal.core.EventInput
import app.geunal.core.Frequency
import app.geunal.core.Recurrence
import app.geunal.designsystem.components.Proposal
import app.geunal.designsystem.components.ToastAction
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.Josa
import app.geunal.feature.calendar.support.coreRead
import app.geunal.feature.calendar.support.dayOfWeek
import app.geunal.feature.calendar.support.weekdayLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

/** What the toasts after an add say; the app's strings, or plain text in tests. */
interface EventTexts {
    fun added(title: String): String

    val undo: String
    val undone: String
    val undoFailed: String
    val saveFailed: String

    fun repeatProposal(weekday: DayOfWeek): Proposal

    fun repeatDone(weekday: DayOfWeek): String

    val repeatFailed: String
}

/** [EventTexts] from the string resources; the particle after the title follows its last sound. */
class ResourceEventTexts(
    private val resources: Resources,
) : EventTexts {
    override fun added(title: String): String =
        resources.getString(
            if (Josa.endsInConsonant(title)) R.string.calendar_added_after_consonant else R.string.calendar_added_after_vowel,
            title,
        )

    override val undo: String get() = resources.getString(R.string.calendar_undo)
    override val undone: String get() = resources.getString(R.string.calendar_undone)
    override val undoFailed: String get() = resources.getString(R.string.calendar_undo_failed)
    override val saveFailed: String get() = resources.getString(R.string.calendar_save_failed)

    override fun repeatProposal(weekday: DayOfWeek): Proposal =
        Proposal(
            message = resources.getString(R.string.calendar_repeat_proposal, weekdayLong(resources, weekday)),
            declineLabel = resources.getString(R.string.calendar_repeat_decline),
            acceptLabel = resources.getString(R.string.calendar_repeat_accept),
        )

    override fun repeatDone(weekday: DayOfWeek): String =
        resources.getString(R.string.calendar_repeat_done, weekdayLong(resources, weekday))

    override val repeatFailed: String get() = resources.getString(R.string.calendar_repeat_failed)
}

/**
 * Adding an event and what follows it (m1.md screen 6): the toast "카페 마감을 추가했어요 · 되돌리기" for five seconds,
 * and on the third same title on the same weekday the question whether to make it weekly.
 */
class EventActions(
    private val core: CoreInterface,
    private val toasts: ToastCenter,
    private val texts: EventTexts,
    /** Where the undo and the weekly change run; they outlive the sheet that saved the event. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Saves [input]; `false` when the core refused, after saying so. */
    suspend fun add(
        input: EventInput,
        fromSuggestion: Boolean,
    ): Boolean {
        val added: AddedEvent =
            withContext(io) { coreRead { core.addEvent(input, fromSuggestion) } } ?: run {
                toasts.show(texts.saveFailed)
                return false
            }
        val undo = ToastAction(texts.undo) { scope.launch { undo(added.eventId) } }
        val hint = added.repeatHint
        if (hint == null) {
            toasts.show(texts.added(input.title), undo)
            return true
        }
        val weekday = hint.weekday.dayOfWeek
        toasts.show(texts.added(input.title), undo, texts.repeatProposal(weekday)) {
            scope.launch { makeWeekly(added.eventId, input, weekday) }
        }
        return true
    }

    /**
     * [add] in the actions' own scope, so it finishes after the sheet that asked for it has closed; [then] gets
     * whether it was saved.
     */
    fun launchAdd(
        input: EventInput,
        fromSuggestion: Boolean,
        then: (Boolean) -> Unit = {},
    ) {
        scope.launch { then(add(input, fromSuggestion)) }
    }

    /** "되돌리기": the event goes, and the core takes back what the add counted (rust-core.md §6 "입력 추천"). */
    suspend fun undo(eventId: String) {
        val done = withContext(io) { coreRead { core.deleteEvent(eventId, null, EditScope.THIS_AND_FOLLOWING) } }
        toasts.show(if (done != null) texts.undone else texts.undoFailed)
    }

    /** "반복으로 바꾸기": the same event repeats weekly on its own weekday from now on. */
    suspend fun makeWeekly(
        eventId: String,
        input: EventInput,
        weekday: DayOfWeek,
    ) {
        val weekly = input.copy(recurrence = Recurrence(Frequency.WEEKLY, 1u, emptyList(), null, null))
        val done = withContext(io) { coreRead { core.updateEvent(eventId, null, EditScope.THIS_AND_FOLLOWING, weekly) } }
        toasts.show(if (done != null) texts.repeatDone(weekday) else texts.repeatFailed)
    }
}

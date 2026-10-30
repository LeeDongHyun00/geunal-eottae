package app.geunal.feature.account.restore

import app.geunal.core.BackupView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.core.ProgressListener
import app.geunal.core.RecoveryCodeCheck
import app.geunal.core.RestoreSummary
import app.geunal.feature.account.onboarding.SignInModel
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.DeviceAuthPurpose
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** The three ways a device reaches the recovery code (m1.md screen 21). */
enum class RestoreMode {
    /** A second device of a Kakao or Apple account, with a replacement ticket. */
    DEVICE_CHANGE,

    /** An account whose backup the server holds, signed in on a device with no other device active. */
    OFFER,

    /** A guest coming back on a new device, from the sign-in screen. */
    GUEST_RETURN,
}

/**
 * Entering the twelve-word recovery code and restoring with it. The words stay in memory only: never in saved state,
 * so neither process death nor a backup can write them anywhere.
 */
class RestoreModel(
    val mode: RestoreMode,
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    /** The sign-in screen's tick. A new guest account needs it; without it, onboarding goes back to tick the box. */
    private val ageConfirmedAt: Instant?,
    private val notify: (AccountNotice) -> Unit,
    private val deviceAuth: DeviceAuth,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Problem {
        /** The word in this cell (0-based) is not one of the 2048. Judged on the device. */
        data class UnknownWord(
            val index: Int,
        ) : Problem

        /** All twelve words are in the list but their checksum does not match: a word differs or two swapped places. */
        data object Checksum : Problem

        /**
         * The code did not open this account's key, or found no guest account. One wrong code in sixteen passes the
         * checksum, so only this tells whose code it is.
         */
        data object WrongCode : Problem

        data object Offline : Problem

        data object Failed : Problem
    }

    sealed interface Phase {
        data object Entering : Phase

        /** Another account's events are on this device; ask before restoring (m1.md screen 24). */
        data class AskingLocalData(
            val local: LocalData,
        ) : Phase

        data class Restoring(
            val done: Long,
            val total: Long,
        ) : Phase

        data class Finished(
            val summary: RestoreSummary,
        ) : Phase

        /** "백업 없이 새로 시작" or a new guest account: onboarding goes on to the permissions without a restore. */
        data object StartedFresh : Phase

        /** "나중에 넣을게요" of the offer: the calendar first, the code later (m1.md decision 24). */
        data object Later : Phase

        /** A guest without the age tick chose a new guest account: back to the sign-in screen to tick it. */
        data object AgeCheckNeeded : Phase

        /** The replacement ticket ran out (30 minutes); the person signs in again. */
        data object TicketExpired : Phase
    }

    /** The "복구 코드가 없어요" sheet and its second step, which asks before deleting the backups. */
    sealed interface NoCode {
        data object Explaining : NoCode

        /**
         * `backups` is `null` when they could not be read; the sheet then asks without the number. Without a screen lock
         * the OS cannot ask who is holding the phone, so the sheet suggests one instead (m1.md §4 "기기 인증").
         */
        data class ConfirmingWipe(
            val backups: Int?,
            val lastAt: Instant?,
            val screenLockMissing: Boolean = false,
        ) : NoCode
    }

    data class State(
        val cells: List<String> = List(WORD_COUNT) { "" },
        /**
         * Counts the times the model itself rewrote each cell (a taken hint, a paste). The field keeps its own text while
         * the person types and takes the model's only when this moves.
         */
        val rewrites: List<Int> = List(WORD_COUNT) { 0 },
        val accepted: List<String?> = List(WORD_COUNT) { null },
        /** The one word a cell's prefix leads to, shown as a hint and taken when the person leaves the cell. */
        val completions: List<String?> = List(WORD_COUNT) { null },
        /** The cell with the keyboard; a cell turns red only once the person leaves it. */
        val focused: Int? = 0,
        val problem: Problem? = null,
        val phase: Phase = Phase.Entering,
        val noCode: NoCode? = null,
        val isWorking: Boolean = false,
    ) {
        val acceptedCount: Int get() = accepted.count { it != null }
        val isComplete: Boolean get() = acceptedCount == WORD_COUNT

        fun isUnknown(index: Int): Boolean = index != focused && cells[index].isNotBlank() && accepted[index] == null

        /** The first cell the person left with a word that is not in the list. */
        val firstUnknownIndex: Int? get() = (0 until WORD_COUNT).firstOrNull { isUnknown(it) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The person typed in a cell. */
    fun changed(
        index: Int,
        text: String,
    ) {
        _state.update { current ->
            val cells = current.cells.replaced(index, text)
            // Spaces around a word change nothing the core said about it.
            if (current.cells[index].trim() == text.trim()) return@update current.copy(cells = cells)
            // What the core said about the old text no longer holds. Until it answers for the new text, neither the
            // main button nor leaving the cell may use the old word or hint.
            current.copy(
                cells = cells,
                accepted = current.accepted.replaced(index, null),
                completions = current.completions.replaced(index, null),
                problem = current.problem.takeUnless { it == Problem.Checksum },
            )
        }
        scope.launch { check(index) }
    }

    fun focused(index: Int) {
        _state.update { it.copy(focused = index) }
    }

    /** The cell lost the keyboard, to another cell or because the keyboard went away: take its single completion. */
    fun left(index: Int) {
        if (_state.value.focused == index) _state.update { it.copy(focused = null) }
        scope.launch { commit(index) }
    }

    /** The keyboard's "다음": leave the cell, then go on to the next empty one. */
    fun submitted(index: Int) {
        scope.launch {
            commit(index)
            val focused = _state.value.focused
            if (focused == null || focused == index) moveFocus(after = index)
        }
    }

    /**
     * "붙여넣기": a whole code in one go, numbering and line breaks dropped by the core. The words are looked up before
     * the cells change, so they fill in at once with their verdicts and no cell shows red on the way.
     */
    fun paste(text: String) {
        scope.launch {
            val looked =
                withContext(io) {
                    runCatching { core.splitRecoveryCode(text) }
                        .getOrDefault(emptyList())
                        .take(WORD_COUNT)
                        .map { word -> word to lookUp(word.trim()) }
                }
            if (looked.isEmpty()) return@launch
            _state.update { current ->
                var cells = current.cells
                var rewrites = current.rewrites
                var accepted = current.accepted
                var completions = current.completions
                looked.forEachIndexed { index, (word, verdict) ->
                    cells = cells.replaced(index, word)
                    rewrites = rewrites.replaced(index, rewrites[index] + 1)
                    accepted = accepted.replaced(index, verdict.word)
                    completions = completions.replaced(index, verdict.completion)
                }
                current.copy(
                    cells = cells,
                    rewrites = rewrites,
                    accepted = accepted,
                    completions = completions,
                    focused = cells.indexOfFirst { it.isBlank() }.takeIf { it >= 0 },
                    // As with a typed word: new words take the old code's verdict away.
                    problem =
                        current.problem.takeUnless {
                            it == Problem.Checksum || (it == Problem.WrongCode && looked.any { (_, verdict) -> verdict.word != null })
                        },
                )
            }
            refreshProblem()
        }
    }

    /** The main button: asks about another account's events first when there are any, then restores. */
    fun start() {
        val current = _state.value
        if (!current.isComplete || current.isWorking || current.problem == Problem.Checksum) return
        _state.update { it.copy(isWorking = true) }
        scope.launch {
            val local = withContext(io) { runCatching { core.localDataOfOtherAccount() }.getOrNull() }
            if (local != null) {
                _state.update { it.copy(phase = Phase.AskingLocalData(local), isWorking = false) }
            } else {
                restore(LocalDataChoice.KEEP)
            }
        }
    }

    /** The answer on screen 24, taken once. */
    fun choose(choice: LocalDataChoice) {
        val current = _state.value
        if (current.phase !is Phase.AskingLocalData || current.isWorking) return
        _state.update { it.copy(isWorking = true) }
        scope.launch { restore(choice) }
    }

    fun openNoCode() {
        _state.update { it.copy(noCode = NoCode.Explaining) }
    }

    fun closeNoCode() {
        if (_state.value.isWorking) return
        _state.update { it.copy(noCode = null) }
    }

    /** "지우지 않을게요": back from the confirmation to the explanation. */
    fun keepBackups() {
        _state.update { it.copy(noCode = NoCode.Explaining) }
    }

    /** "나중에 넣을게요 · 캘린더부터 쓰기", offered only when the server holds a backup and no ticket runs out. */
    fun later() {
        if (mode != RestoreMode.OFFER) return
        _state.update { it.copy(noCode = null, phase = Phase.Later) }
    }

    /**
     * The red button of the sheet. Starting fresh deletes the account's backups, so the first press only turns the sheet
     * into a confirmation with their number and last time (m1.md decision 24); a guest's new account needs no such step.
     */
    fun startOver() {
        if (_state.value.isWorking) return
        when (mode) {
            RestoreMode.DEVICE_CHANGE, RestoreMode.OFFER -> {
                _state.update { it.copy(isWorking = true) }
                scope.launch {
                    val backups: List<BackupView>? = withContext(io) { runCatching { core.backups() }.getOrNull() }
                    _state.update {
                        it.copy(
                            noCode =
                                NoCode.ConfirmingWipe(
                                    backups = backups?.size,
                                    lastAt = backups?.maxOfOrNull { backup -> backup.createdAt },
                                    screenLockMissing = !deviceAuth.isDeviceSecure(),
                                ),
                            isWorking = false,
                        )
                    }
                }
            }
            RestoreMode.GUEST_RETURN -> newGuest()
        }
    }

    /**
     * "백업 N개 지우고 새로 시작". The OS asks who holds the phone first (decision 26); a cancel leaves the sheet as it was
     * and deletes nothing.
     */
    fun confirmWipe() {
        val current = _state.value
        if (current.noCode !is NoCode.ConfirmingWipe || current.isWorking) return
        _state.update { it.copy(isWorking = true) }
        scope.launch {
            try {
                if (!deviceAuth.confirm(DeviceAuthPurpose.WIPE_BACKUPS)) return@launch
                // Without a proof: within 30 minutes of the provider sign-in the server needs none. A later start of the
                // offer gets `ReauthRequired`, and the provider check it asks for comes with account deletion's.
                core.startFresh(null)
                _state.update { it.copy(phase = Phase.StartedFresh, noCode = null) }
            } catch (e: CoreException.Network) {
                _state.update { it.copy(problem = Problem.Offline, noCode = null) }
            } catch (e: CoreException.SignInAgain) {
                expireTicket()
            } catch (e: CoreException) {
                notify(AccountNotice.SOMETHING_FAILED)
            } catch (e: InternalException) {
                notify(AccountNotice.SOMETHING_FAILED)
            } finally {
                _state.update { it.copy(isWorking = false) }
            }
        }
    }

    private fun newGuest() {
        val ageConfirmedAt = ageConfirmedAt
        if (ageConfirmedAt == null) {
            notify(AccountNotice.AGE_CHECK_NEEDED)
            _state.update { it.copy(noCode = null, phase = Phase.AgeCheckNeeded) }
            return
        }
        _state.update { it.copy(isWorking = true) }
        scope.launch {
            try {
                core.signInGuest(SignInModel.account(null, ageConfirmedAt))
                _state.update { it.copy(phase = Phase.StartedFresh, noCode = null) }
            } catch (e: CoreException.Network) {
                _state.update { it.copy(problem = Problem.Offline, noCode = null) }
            } catch (e: CoreException) {
                notify(AccountNotice.SOMETHING_FAILED)
            } catch (e: InternalException) {
                notify(AccountNotice.SOMETHING_FAILED)
            } finally {
                _state.update { it.copy(isWorking = false) }
            }
        }
    }

    private suspend fun restore(choice: LocalDataChoice) {
        _state.update { it.copy(problem = null, phase = Phase.Restoring(0, 0)) }
        val words = _state.value.accepted.filterNotNull()
        val relay =
            object : ProgressListener {
                override fun onProgress(
                    done: ULong,
                    total: ULong,
                ) {
                    _state.update { current ->
                        if (current.phase is Phase.Restoring) {
                            current.copy(phase = Phase.Restoring(done.toLong(), total.toLong()))
                        } else {
                            current
                        }
                    }
                }
            }
        try {
            val summary = core.restoreFromCode(words, choice, relay)
            _state.update { it.copy(phase = Phase.Finished(summary)) }
        } catch (e: CoreException.WrongRecoveryCode) {
            _state.update { it.copy(problem = Problem.WrongCode, phase = Phase.Entering) }
        } catch (e: CoreException.Network) {
            _state.update { it.copy(problem = Problem.Offline, phase = Phase.Entering) }
        } catch (e: CoreException.SignInAgain) {
            expireTicket()
        } catch (e: CoreException) {
            _state.update { it.copy(problem = Problem.Failed, phase = Phase.Entering) }
        } catch (e: InternalException) {
            _state.update { it.copy(problem = Problem.Failed, phase = Phase.Entering) }
        } finally {
            _state.update { it.copy(isWorking = false) }
        }
    }

    private fun expireTicket() {
        notify(AccountNotice.TICKET_EXPIRED)
        _state.update { it.copy(phase = Phase.TicketExpired, noCode = null) }
    }

    /**
     * Checks a cell against the word list. A full word moves the keyboard on at once, if the cell still has it. A shorter
     * prefix that leads to one word only becomes a hint: Hangul composition passes through syllables that are prefixes
     * of other words ("가이" on the way to "가입" leads to "가이드"), so replacing the text while the person types would
     * pick the wrong word (m1.md decision 19).
     */
    private suspend fun check(index: Int) {
        val text = _state.value.cells[index].trim()
        val verdict = withContext(io) { lookUp(text) }
        // Typing may have gone on while the core looked; then the newer text has its own check on the way, and this
        // verdict must not land on it.
        val now =
            _state.updateAndGet { current ->
                if (current.cells[index].trim() != text) return@updateAndGet current
                current.copy(
                    accepted = current.accepted.replaced(index, verdict.word),
                    completions = current.completions.replaced(index, verdict.completion),
                    problem = if (verdict.word != null && current.problem == Problem.WrongCode) null else current.problem,
                )
            }
        if (now.cells[index].trim() != text) return
        if (verdict.word != null && now.focused == index) moveFocus(after = index)
        refreshProblem()
    }

    /** Takes the cell's hint. The hint is always the current text's: an edit drops it, and a late verdict is not kept. */
    private suspend fun commit(index: Int) {
        val current = _state.value
        val completion = current.completions[index]
        if (current.accepted[index] == null && completion != null) {
            _state.update {
                it.copy(cells = it.cells.replaced(index, completion), rewrites = it.rewrites.replaced(index, it.rewrites[index] + 1))
            }
            check(index)
        }
        refreshProblem()
    }

    /** What the core says of one cell's text: the word it is, or else the one word it leads to. */
    private fun lookUp(text: String): Verdict {
        if (text.isEmpty()) return Verdict(null, null)
        val word = runCatching { core.checkRecoveryWord(text) }.getOrNull()
        if (word != null) return Verdict(word, null)
        return Verdict(null, runCatching { core.recoveryCompletions(text) }.getOrDefault(emptyList()).singleOrNull())
    }

    private data class Verdict(
        val word: String?,
        val completion: String?,
    )

    private fun moveFocus(after: Int) {
        _state.update { current ->
            val next =
                ((after + 1) until WORD_COUNT).firstOrNull { current.accepted[it] == null }
                    ?: (0 until after).firstOrNull { current.accepted[it] == null }
            current.copy(focused = next)
        }
    }

    /**
     * The cells' problem in the order of m1.md screen 21: a word outside the list, then the checksum of the twelve. Both
     * are judged on the device, so a mistyped code never reaches Argon2id or the server.
     */
    private suspend fun refreshProblem() {
        val words = _state.value.accepted
        val checksumFails =
            words.all { it != null } &&
                withContext(io) { runCatching { core.checkRecoveryCode(words.filterNotNull()) }.getOrNull() } is RecoveryCodeCheck.Checksum
        _state.update { current ->
            val unknown = current.firstUnknownIndex
            when {
                unknown != null -> current.copy(problem = Problem.UnknownWord(unknown))
                // Only for the words judged; an edit since then brings its own judgement.
                checksumFails && current.accepted == words -> current.copy(problem = Problem.Checksum)
                current.problem is Problem.UnknownWord || current.problem == Problem.Checksum -> current.copy(problem = null)
                else -> current
            }
        }
    }

    companion object {
        const val WORD_COUNT = 12
    }
}

private fun <T> List<T>.replaced(
    index: Int,
    value: T,
): List<T> = toMutableList().also { it[index] = value }

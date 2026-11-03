package app.geunal.feature.account.delete

import app.geunal.core.AccountView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.DeletionProof
import app.geunal.core.InternalException
import app.geunal.core.Provider
import app.geunal.feature.account.providers.ProviderProof
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.providers.ProviderSignInException
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.DeviceAuthPurpose
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
 * 계정 삭제 (m1.md screen 22): what the server deletes and what stays on this device, then the sheet that asks for
 * "삭제" typed out. The OS prompt comes first (decision 26). Then the server wants the account proven at that moment
 * (protocol §13): a guest's proof comes from the recovery key on the device, a Kakao or Apple account's from a fresh
 * sign-in with that provider.
 *
 * Everything runs on the main thread of [scope]; only the core calls move to [io].
 */
class DeleteAccountModel(
    private val core: CoreInterface,
    private val providers: ProviderSignIn,
    private val deviceAuth: DeviceAuth,
    account: AccountView?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The account is gone. Called by the model, not the screen: a deletion still waiting when the person left the
     * screen must end the app's account all the same.
     */
    private val onDeleted: () -> Unit = {},
) {
    enum class Problem {
        OFFLINE,

        /** 403 `reauth_required`: the provider signed in another account. Nothing was deleted; the session is good. */
        REAUTH_REQUIRED,

        /** A guest's recovery material is still not on the server after the core uploaded it and tried once more. */
        RECOVERY_MISSING,

        /**
         * The connection broke before the answer came (decision 36): the server may already have deleted the account.
         * The sheet asks the core once there is a connection.
         */
        ANSWER_LOST,

        /** The check found the account still there: pressing again sends the first proof once more. */
        NOT_DELETED,
        FAILED,
    }

    data class State(
        val typed: String = "",
        val sheetOpen: Boolean = false,
        val isWorking: Boolean = false,
        val problem: Problem? = null,
        /** "확인하는 중…": a sync is asking whether a deletion whose answer was lost went through. */
        val checking: Boolean = false,
        /** Deleted by a press or found deleted by the check: the app ends as after a 204. */
        val deleted: Boolean = false,
    ) {
        val canConfirm: Boolean get() = typed.trim() == WORD && !isWorking && problem != Problem.ANSWER_LOST
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** "하린" for "하린님의 계정을 삭제할까요?". */
    val displayName: String = account?.displayName.orEmpty()

    /** The provider the sheet re-checks with; `null` for a guest or an account whose link was cut. */
    val provider: Provider? = ProviderProof.providerOf(account?.providers.orEmpty())

    /** False on a phone without a screen lock: nothing is asked, and the sheet suggests one. */
    val screenLocked: Boolean = deviceAuth.isDeviceSecure()

    /**
     * The sheet asks the OS once; "카카오로 다시 확인" after a failed check does not ask again (m1.md §4 "기기 인증").
     * Only within one opening of the sheet: whoever holds the phone after it closed is asked again.
     */
    private var ownerConfirmed = false

    /**
     * The proof of a deletion whose answer was lost, sent again before anything is asked anew (decision 36). In memory
     * only, and only while the sheet is open (docs/security/app-review.md §4).
     */
    private var sentProof: DeletionProof? = null

    /** Counts the sheet's closings, so a press still waiting when its sheet closed keeps nothing for the next one. */
    private var presentation = 0

    fun openSheet() {
        _state.update { it.copy(sheetOpen = true) }
    }

    fun type(text: String) {
        _state.update { it.copy(typed = text) }
    }

    /**
     * The sheet closed: its proof and its owner check go, so reopening asks the OS again. A deletion left without its
     * answer stays unknown until a call reaches the server; the next press proves the account afresh.
     */
    fun closeSheet() {
        presentation++
        ownerConfirmed = false
        sentProof = null
        _state.update {
            it.copy(
                sheetOpen = false,
                problem = it.problem.takeUnless { p -> p == Problem.ANSWER_LOST || p == Problem.NOT_DELETED },
            )
        }
    }

    fun confirm(online: Boolean) {
        scope.launch { delete(online) }
    }

    /**
     * Asks the OS, proves the account, deletes it on the server; the core then forgets the secrets and the calendar
     * stays. Cancelling the OS prompt or the provider's sign-in leaves the sheet as it was, "삭제" still typed.
     */
    suspend fun delete(online: Boolean) {
        if (!_state.value.canConfirm) return
        if (!online) {
            _state.update { it.copy(problem = Problem.OFFLINE) }
            return
        }
        _state.update { it.copy(isWorking = true) }
        try {
            run()
        } finally {
            _state.update { it.copy(isWorking = false) }
        }
    }

    private suspend fun run() {
        // A press that outlives its sheet must not hand the next sheet a proof or a passed OS prompt.
        val attempt = presentation
        val again = sentProof
        if (again != null) {
            // The account was still there after a lost answer: the first proof goes again before anything is asked
            // anew. A proof the server already took answers reauth_required, and only then is the OS asked again.
            sentProof = null
            _state.update { it.copy(problem = null) }
            try {
                send(again)
                finish()
                return
            } catch (e: AnswerLost) {
                if (presentation != attempt) return
                sentProof = again
                _state.update { it.copy(problem = Problem.ANSWER_LOST) }
                return
            } catch (e: CoreException.ReauthRequired) {
                ownerConfirmed = false
            } catch (e: CoreException) {
                if (presentation == attempt) _state.update { it.copy(problem = Problem.FAILED) }
                return
            } catch (e: InternalException) {
                if (presentation == attempt) _state.update { it.copy(problem = Problem.FAILED) }
                return
            }
            if (presentation != attempt) return
        }
        if (!ownerConfirmed) {
            if (!deviceAuth.confirm(DeviceAuthPurpose.DELETE_ACCOUNT) || presentation != attempt) return
            ownerConfirmed = true
        }
        _state.update { it.copy(problem = null) }
        var proof: DeletionProof? = null
        val problem =
            try {
                val made = proof()
                // The provider's sign-in can outlast the sheet; a closed sheet sends nothing.
                if (presentation != attempt) return
                proof = made
                send(made)
                finish()
                return
            } catch (e: AnswerLost) {
                sentProof = proof
                Problem.ANSWER_LOST
            } catch (e: ProviderSignInException.Cancelled) {
                // Nothing was deleted and nothing needs saying.
                null
            } catch (e: CoreException.ReauthRequired) {
                if (provider == null) Problem.FAILED else Problem.REAUTH_REQUIRED
            } catch (e: CoreException.RecoveryMissing) {
                Problem.RECOVERY_MISSING
            } catch (e: CoreException.Network) {
                // Before anything was sent (the nonce): plainly offline.
                Problem.OFFLINE
            } catch (e: ProviderSignInException) {
                Problem.FAILED
            } catch (e: CoreException) {
                Problem.FAILED
            } catch (e: InternalException) {
                Problem.FAILED
            }
        // What this press learned stays with it once the sheet closed.
        if (presentation != attempt) {
            sentProof = null
            return
        }
        _state.update { it.copy(problem = problem) }
    }

    /**
     * After a lost answer, once there is a connection: a sync with this session always reaches the server, so it either
     * finds the account gone (401 `account_deleted`, and the core has finished as after a 204) or still there. Any other
     * failure leaves the question to the next connection (m1.md screen 22, decision 36).
     */
    suspend fun checkLostAnswer() {
        val current = _state.value
        if (current.problem != Problem.ANSWER_LOST || current.checking) return
        _state.update { it.copy(checking = true) }
        try {
            withContext(io) { core.sync() }
            _state.update { if (it.problem == Problem.ANSWER_LOST) it.copy(problem = Problem.NOT_DELETED) else it }
        } catch (e: CoreException) {
            if (e.isAccountDeleted) finish()
            // Otherwise still unknown.
        } catch (e: InternalException) {
            // Still unknown.
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    private fun finish() {
        if (_state.value.deleted) return
        _state.update { it.copy(deleted = true) }
        onDeleted()
    }

    /** The deletion went out, and the connection broke before its answer. */
    private class AnswerLost : Exception()

    /**
     * 401 `account_deleted` means the account is gone, deleted by this call or by one whose answer was lost, and the core
     * has finished as after a 204 (core #71). The core itself uploads a guest's missing recovery material and tries once
     * more before it answers `RecoveryMissing`.
     */
    private suspend fun send(proof: DeletionProof) {
        try {
            withContext(io) { core.deleteAccount(proof) }
        } catch (e: CoreException.Network) {
            throw AnswerLost()
        } catch (e: CoreException.Api) {
            if (!e.isAccountDeleted) throw e
        }
    }

    private suspend fun proof(): DeletionProof {
        val provider = provider ?: return DeletionProof.Guest
        return ProviderProof.make(provider, core, providers)
    }

    companion object {
        /** The word the sheet asks for. */
        const val WORD = "삭제"
    }
}

/**
 * 401 `account_deleted` (protocol §13 "답을 잃은 삭제"): the account is gone, and the core has already finished as after
 * a 204 (core #71). Any call that reaches the server can be the one that learns it.
 */
val CoreException.isAccountDeleted: Boolean
    get() = this is CoreException.Api && status.toInt() == 401 && code == "account_deleted"

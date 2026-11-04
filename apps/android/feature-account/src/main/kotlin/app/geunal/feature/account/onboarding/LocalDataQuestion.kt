package app.geunal.feature.account.onboarding

import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.feature.account.support.AccountNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * "이 기기에 다른 계정의 일정이 남아 있어요" (m1.md screen 24) after an account is decided without a restore: a new sign-in,
 * starting fresh, "나중에 넣을게요" or a new guest account. The core keeps the other account's events apart and backs
 * nothing up until it has the answer (`settleLocalData`); a restore takes the answer itself (`RestoreModel.choose`).
 *
 * Called from the main thread only; the core calls move to [io].
 */
class LocalDataQuestion(
    private val core: CoreInterface,
    private val notify: (AccountNotice) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _asking = MutableStateFlow<LocalData?>(null)

    /** What the sheet counts; `null` while there is nothing to ask. */
    val asking: StateFlow<LocalData?> = _asking.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private var next: (() -> Unit)? = null

    /**
     * Asks when another account's events are on the device, and goes on to [then] at once otherwise. The sheet closes
     * only with an answer, so [then] waits for it. Without a session there is nothing to ask yet: the core keeps the
     * store's owner after a deletion or a sign-out but takes an answer only for a signed-in account, so the question
     * waits until the next account is registered.
     */
    suspend fun ask(then: () -> Unit) {
        if (_asking.value != null || _isWorking.value) return
        _isWorking.value = true
        val local =
            try {
                withContext(io) { if (core.session() == null) null else core.localDataOfOtherAccount() }
            } catch (e: CoreException) {
                null
            } catch (e: InternalException) {
                null
            } finally {
                _isWorking.value = false
            }
        if (local == null) {
            then()
            return
        }
        next = then
        _asking.value = local
    }

    /** The person's answer. A failure keeps the sheet, since the core still waits for an answer. */
    suspend fun answer(choice: LocalDataChoice) {
        if (_asking.value == null || _isWorking.value) return
        _isWorking.value = true
        try {
            withContext(io) { core.settleLocalData(choice) }
        } catch (e: CoreException) {
            notify(AccountNotice.SOMETHING_FAILED)
            return
        } catch (e: InternalException) {
            notify(AccountNotice.SOMETHING_FAILED)
            return
        } finally {
            _isWorking.value = false
        }
        _asking.value = null
        val then = next
        next = null
        then?.invoke()
    }
}

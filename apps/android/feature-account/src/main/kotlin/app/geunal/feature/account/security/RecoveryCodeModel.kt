package app.geunal.feature.account.security

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
import java.time.Instant

/**
 * 복구 코드 (m1.md screen 13): the twelve words, the line about the backup, "복사" and "종이에 적었어요". The screen is
 * opened only after the device owner check; the words live in this model's memory and nowhere else, never in a log,
 * preferences or a file (docs/security/app-review.md §1 and §3).
 */
class RecoveryCodeModel(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val words: List<String> = emptyList(),
        /** The last backup's time for the line under the warning, `null` before the first. */
        val lastBackupAt: Instant? = null,
        val hasLoaded: Boolean = false,
        /** The core could not give the words: no account yet, or the store failed. */
        val failed: Boolean = false,
        val isSaving: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val words = read { core.recoveryWords() }
        val last = read { core.backupStatus() }?.last?.createdAt
        _state.update {
            it.copy(words = words.orEmpty(), lastBackupAt = last, hasLoaded = true, failed = words.isNullOrEmpty())
        }
    }

    /** "종이에 적었어요": recorded on this device only (protocol §10), and the banner asking for it goes. */
    suspend fun markSaved(): Boolean {
        _state.update { it.copy(isSaving = true) }
        val done = read { core.markRecoverySaved() } != null
        _state.update { it.copy(isSaving = false) }
        return done
    }

    fun launchMarkSaved(then: () -> Unit) {
        scope.launch { if (markSaved()) then() }
    }

    private suspend fun <T> read(block: () -> T): T? =
        try {
            withContext(io) { block() }
        } catch (e: CoreException) {
            null
        } catch (e: InternalException) {
            null
        }
}

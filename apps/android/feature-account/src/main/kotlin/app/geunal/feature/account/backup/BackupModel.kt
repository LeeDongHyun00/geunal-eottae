package app.geunal.feature.account.backup

import app.geunal.core.BackupStatus
import app.geunal.core.BackupView
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.ProgressListener
import app.geunal.core.RecoveryStatus
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
 * 암호화 백업 (m1.md screen 20): the last backup, "지금 백업" with its progress, the automatic backup switch and the
 * backups the server keeps. A backup is sealed on the device and opens only with the recovery code; until the code's
 * material is on the server (a code put off at screen 21), nothing can be backed up.
 */
class BackupModel(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val status: BackupStatus? = null,
        val recovery: RecoveryStatus? = null,
        /** Newest first, as the server keeps them: three at most. */
        val backups: List<BackupView> = emptyList(),
        /** 0 to 1 while a backup is sealed and sent. */
        val progress: Float? = null,
        /** The backup just made, for "방금". */
        val justMade: Boolean = false,
        /** The last "지금 백업" did not get through: "연결되면 다시 올릴게요". */
        val failed: Boolean = false,
        val hasLoaded: Boolean = false,
    ) {
        val isBackingUp: Boolean get() = progress != null

        /**
         * The code was put off (screen 21 "나중에"): this device has none, and backups wait until it is entered. A code
         * whose material only waits to be uploaded is not this: "지금 백업" uploads it first (`backup_now`), so
         * `status.ready` alone, false in both cases, would block the one way to retry.
         */
        val waitsForCode: Boolean get() = recovery?.let { !it.created } ?: (status?.ready == false)

        /** The code was never written down, and a backup opens with nothing else. */
        val codeNotSaved: Boolean get() = recovery?.saved == false && !waitsForCode

        val canBackUp: Boolean get() = hasLoaded && !isBackingUp && !waitsForCode
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    suspend fun load() {
        val status = read { core.backupStatus() }
        val recovery = read { core.recoveryStatus() }
        // The list asks the server; without a connection the screen keeps the last backup it knows.
        val backups = read { core.backups() }
        _state.update {
            it.copy(
                status = status ?: it.status,
                recovery = recovery ?: it.recovery,
                backups = backups?.sortedByDescending { backup -> backup.createdAt } ?: it.backups,
                hasLoaded = true,
            )
        }
    }

    /** "지금 백업": sealed here, then sent; the card shows how far it is. */
    suspend fun backUpNow() {
        if (!_state.value.canBackUp) return
        _state.update { it.copy(progress = 0f, justMade = false, failed = false) }
        // Called on a core thread; a state flow takes updates from any thread.
        val listener =
            object : ProgressListener {
                override fun onProgress(
                    done: ULong,
                    total: ULong,
                ) {
                    if (total == 0uL) return
                    _state.update { it.copy(progress = (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)) }
                }
            }
        val made = read { core.backupNow(listener) }
        _state.update { it.copy(progress = null, justMade = made != null, failed = made == null) }
        if (made != null) load()
    }

    fun launchBackUpNow() {
        scope.launch { backUpNow() }
    }

    /** "자동 백업": once a day on Wi-Fi, skipped on a day without changes. */
    fun setAuto(on: Boolean) {
        scope.launch {
            read { core.setAutoBackup(on) }
            load()
        }
    }

    private suspend fun <T> read(block: suspend () -> T): T? =
        try {
            withContext(io) { block() }
        } catch (e: CoreException) {
            null
        } catch (e: InternalException) {
            null
        }
}

package app.geunal.ui.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import app.geunal.core.CoreInterface
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.backup.BackupModel
import app.geunal.feature.account.backup.BackupScreen
import app.geunal.feature.account.security.RecoveryCodeModel
import app.geunal.feature.account.security.RecoveryCodeScreen
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.DeviceAuthPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A screen of 나's security and backup group, over the tabs: 복구 코드 and 암호화 백업 (m1.md screens 13, 20). */
sealed interface MeCover {
    class RecoveryCode(
        val model: RecoveryCodeModel,
        /** False without a screen lock: the screen suggests one. */
        val deviceSecure: Boolean,
    ) : MeCover

    class Backup(
        val model: BackupModel,
    ) : MeCover
}

/**
 * The account screens over the tabs, beside the calendar's covers. Both kinds are opened only from the tabs, never one
 * over the other, so the app draws this stack after the calendar's. The models run in [scope], which outlives a
 * rotation.
 */
class MeCovers(
    private val core: CoreInterface,
    private val scope: CoroutineScope,
    private val deviceAuth: DeviceAuth,
) {
    val stack: SnapshotStateList<MeCover> = mutableStateListOf()

    /** The OS's check is up: a second tap must not ask again. */
    private var asking = false

    /**
     * 복구 코드, once the OS's check passed (m1.md §4 "기기 인증"). Cancelled, nothing opens and the screen behind stays;
     * a phone without a screen lock opens it at once.
     */
    fun recoveryCode() {
        if (asking) return
        asking = true
        scope.launch {
            try {
                if (deviceAuth.confirm(DeviceAuthPurpose.SHOW_RECOVERY_CODE)) {
                    stack += MeCover.RecoveryCode(RecoveryCodeModel(core, scope), deviceAuth.isDeviceSecure())
                }
            } finally {
                asking = false
            }
        }
    }

    fun backup() {
        stack += MeCover.Backup(BackupModel(core, scope))
    }

    fun close(cover: MeCover) {
        stack.remove(cover)
    }

    /** Takes the top screen away; false when there is none here. */
    fun back(): Boolean = stack.removeLastOrNull() != null
}

@Composable
fun MeCoverContent(
    cover: MeCover,
    covers: MeCovers,
    toasts: ToastCenter,
    modifier: Modifier = Modifier,
) {
    when (cover) {
        is MeCover.RecoveryCode ->
            RecoveryCodeScreen(
                model = cover.model,
                deviceSecure = cover.deviceSecure,
                toasts = toasts,
                onClose = { covers.close(cover) },
                modifier = modifier,
            )
        is MeCover.Backup ->
            BackupScreen(
                model = cover.model,
                onRecoveryCode = covers::recoveryCode,
                onClose = { covers.close(cover) },
                modifier = modifier,
            )
    }
}

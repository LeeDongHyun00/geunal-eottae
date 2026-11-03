package app.geunal.ui.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import app.geunal.core.AccountView
import app.geunal.core.CoreInterface
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.backup.BackupModel
import app.geunal.feature.account.backup.BackupScreen
import app.geunal.feature.account.delete.DeleteAccountModel
import app.geunal.feature.account.delete.DeleteAccountScreen
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.security.RecoveryCodeModel
import app.geunal.feature.account.security.RecoveryCodeScreen
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.DeviceAuthPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A screen of 나 over the tabs: 복구 코드, 암호화 백업 and 계정 삭제 (m1.md screens 13, 20, 22). */
sealed interface MeCover {
    class RecoveryCode(
        val model: RecoveryCodeModel,
        /** False without a screen lock: the screen suggests one. */
        val deviceSecure: Boolean,
    ) : MeCover

    class Backup(
        val model: BackupModel,
    ) : MeCover

    class DeleteAccount(
        val model: DeleteAccountModel,
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
    private val providers: ProviderSignIn,
    private val onAccountDeleted: () -> Unit,
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

    fun deleteAccount(account: AccountView) {
        stack +=
            MeCover.DeleteAccount(
                DeleteAccountModel(core, providers, deviceAuth, account, scope, onDeleted = onAccountDeleted),
            )
    }

    /** The account is gone: every screen of it goes too, with what their models hold (m1.md screen 22). */
    fun clear() {
        stack.clear()
    }

    fun close(cover: MeCover) {
        if (stack.remove(cover)) closed(cover)
    }

    /** Takes the top screen away; false when there is none here. */
    fun back(): Boolean = stack.removeLastOrNull()?.also(::closed) != null

    /**
     * 복구 코드 opened from 암호화 백업's reminder may have marked the code saved: the backup screen under it reads
     * again, or it would keep asking for a code just written down.
     */
    private fun closed(cover: MeCover) {
        if (cover !is MeCover.RecoveryCode) return
        stack.filterIsInstance<MeCover.Backup>().forEach { scope.launch { it.model.load() } }
    }
}

@Composable
fun MeCoverContent(
    cover: MeCover,
    covers: MeCovers,
    toasts: ToastCenter,
    online: Boolean,
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
        is MeCover.DeleteAccount ->
            DeleteAccountScreen(
                model = cover.model,
                online = online,
                onClose = { covers.close(cover) },
                modifier = modifier,
            )
    }
}

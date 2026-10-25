package app.geunal

import android.content.Context
import app.geunal.bridge.AppStorage
import app.geunal.bridge.KeystoreSecretStore
import app.geunal.core.Core
import app.geunal.core.CoreInterface
import app.geunal.core.CoreListener

/**
 * The app-wide dependencies, wired by hand: with this few modules a DI framework has nothing to earn
 * (docs/parts/android.md §1).
 */
class AppContainer(
    context: Context,
) {
    private val storage = AppStorage(context)
    private val secrets = KeystoreSecretStore(storage.secretsDir)

    val apiBase: String = BuildConfig.API_BASE

    /** Opens the core. Blocking (it opens the SQLCipher database), so callers move it off the main thread. */
    fun openCore(listener: CoreListener): CoreInterface = Core.open(storage.storeDir.path, apiBase, listener, secrets)
}

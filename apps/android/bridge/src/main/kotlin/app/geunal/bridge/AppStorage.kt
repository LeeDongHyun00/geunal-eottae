package app.geunal.bridge

import android.content.Context
import java.io.File

/**
 * Where the core's files live. Everything sits under `noBackupFilesDir`: the database is unreadable without the key
 * the Keystore holds on this device only, so backing either up would restore an account that cannot be opened
 * (docs/parts/android.md §2).
 */
class AppStorage(
    context: Context,
) {
    private val root: File = context.noBackupFilesDir

    /** The directory `Core.open` gets; the core keeps its SQLCipher database in it. */
    val storeDir: File = File(root, "store").apply { mkdirs() }

    /** The sealed secrets of [KeystoreSecretStore]. */
    val secretsDir: File = File(root, "secrets").apply { mkdirs() }

    /**
     * What onboarding remembers. Next to the store on purpose: a copy of it on a new phone without the store would skip
     * onboarding there with nothing to open (docs/security/app-review.md §1 "기기 이전").
     */
    val onboardingDir: File = File(root, "onboarding").apply { mkdirs() }
}

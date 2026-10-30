package app.geunal.bridge

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.geunal.core.SecretStore
import app.geunal.core.SecretStoreException
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The core's secrets (device signing key, account master key, database key) behind the Android Keystore.
 *
 * The Keystore cannot hold arbitrary bytes, so each secret is sealed with an AES-256-GCM key that never leaves the
 * Keystore and written to its own file under [dir] (docs/parts/android.md §2). The caller puts [dir] under
 * `noBackupFilesDir`, which auto backup and device-to-device transfer skip: a backup of these files would be useless
 * without this device's key anyway, and a transferred one would silently lock the account out.
 *
 * The key asks for neither an unlocked device nor user authentication, because the core must open the store from a
 * push handler while the phone is locked (docs/parts/android.md §5).
 */
class KeystoreSecretStore(
    private val dir: File,
    private val alias: String = DEFAULT_ALIAS,
) : SecretStore {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * `null` only when nothing is kept under [name]. A value that will never open, its Keystore key gone or its tag
     * failed, throws [SecretStoreException.Lost]; any other failure, a Keystore error say, throws for the core to take
     * as one it may get past later.
     */
    @Synchronized
    override fun get(name: String): ByteArray? {
        // Read through AtomicFile rather than testing for the file: on API 26-29 a write in progress has already
        // renamed the previous value to a backup, which AtomicFile restores, and only it knows when nothing is there.
        val sealed =
            try {
                AtomicFile(fileFor(name)).readFully()
            } catch (e: FileNotFoundException) {
                return null
            }
        // Reading never makes a key: one made under the alias would replace the key that sealed this for good.
        val key =
            storedKey() ?: when (missingKey(Build.VERSION.SDK_INT, reading = true, sealedValues = true)) {
                MissingKey.LOST -> throw SecretStoreException.Lost("the Keystore key that sealed $name is gone")
                else -> throw SecretStoreException.Unavailable("the Keystore returned no key for $name")
            }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        // The name is authenticated with the value, so a file moved under another secret's name fails to open.
        cipher.updateAAD(name.toByteArray())
        return try {
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            // Sealed under another key, or damaged: it will never open. The core sets the store aside for a lost
            // database key rather than failing every launch.
            throw SecretStoreException.Lost("$name fails its tag")
        }
    }

    @Synchronized
    override fun put(
        name: String,
        value: ByteArray,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks a fresh random IV for every encryption; a caller-supplied IV is refused by default.
        cipher.init(Cipher.ENCRYPT_MODE, keyForWriting())
        cipher.updateAAD(name.toByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val sealed = iv + cipher.doFinal(value)
        dir.mkdirs()
        // AtomicFile writes to a sibling and renames, so a crash mid-write leaves the previous value, not a torn one.
        val atomic = AtomicFile(fileFor(name))
        val out = atomic.startWrite()
        try {
            out.write(sealed)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    @Synchronized
    override fun delete(name: String) {
        AtomicFile(fileFor(name)).delete()
    }

    // Names come from the core (db_key, ...); the encoding keeps any of them a safe file name.
    private fun fileFor(name: String): File {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray())
        return File(dir, encoded)
    }

    private fun storedKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    /**
     * The key to seal with. A new one orphans every value sealed under the old, so [missingKey] decides when to make it.
     */
    private fun keyForWriting(): SecretKey {
        storedKey()?.let { return it }
        val sealedValues = dir.listFiles().orEmpty().isNotEmpty()
        if (missingKey(Build.VERSION.SDK_INT, reading = false, sealedValues) != MissingKey.MAKE) {
            throw SecretStoreException.Unavailable("the Keystore returned no key, and values are sealed under one")
        }
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "app.geunal.secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}

/** What it means that the Keystore returned no key, as [KeystoreSecretStore] acts on it. */
internal enum class MissingKey {
    /** Make one: nothing is sealed yet, or keystore2 has said the key is gone. */
    MAKE,

    /** Keystore2 has said the key is gone, so a value sealed under it will never open. */
    LOST,

    /** The keystore before API 31 may only have failed to answer, and a new key would replace the real one for good. */
    UNAVAILABLE,
}

/**
 * Keystore2 (API 31) returns no key only for one that is not there and throws on any other failure. The keystore before
 * it also returns none when its daemon does not answer (`KeyStore.contains` on API 26-30), which cannot be told from a
 * key that is gone. There a key is made only before anything is sealed, and a sealed value reads as unavailable.
 */
internal fun missingKey(
    sdk: Int,
    reading: Boolean,
    sealedValues: Boolean,
): MissingKey {
    val gone = sdk >= Build.VERSION_CODES.S
    return when {
        reading -> if (gone) MissingKey.LOST else MissingKey.UNAVAILABLE
        gone || !sealedValues -> MissingKey.MAKE
        else -> MissingKey.UNAVAILABLE
    }
}

package app.geunal.bridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.geunal.core.SecretStore
import java.io.File
import java.security.KeyStore
import java.util.Base64
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

    @Synchronized
    override fun get(name: String): ByteArray? {
        val file = fileFor(name)
        if (!file.exists()) return null
        val sealed = AtomicFile(file).readFully()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        // The name is authenticated with the value, so a file moved under another secret's name fails to open.
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    @Synchronized
    override fun put(
        name: String,
        value: ByteArray,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks a fresh random IV for every encryption; a caller-supplied IV is refused by default.
        cipher.init(Cipher.ENCRYPT_MODE, key())
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

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
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

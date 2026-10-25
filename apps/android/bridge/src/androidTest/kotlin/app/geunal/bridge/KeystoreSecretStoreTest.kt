package app.geunal.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private lateinit var dir: File
    private lateinit var store: KeystoreSecretStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.noBackupFilesDir, "secrets-test-${System.nanoTime()}")
        store = KeystoreSecretStore(dir, TEST_ALIAS)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
    }

    @Test
    fun missingSecretIsNull() {
        assertNull(store.get("db_key"))
    }

    @Test
    fun roundTripsAndOverwrites() {
        val first = ByteArray(32) { it.toByte() }
        store.put("db_key", first)
        assertArrayEquals(first, store.get("db_key"))

        val second = ByteArray(32) { (255 - it).toByte() }
        store.put("db_key", second)
        assertArrayEquals(second, store.get("db_key"))
    }

    @Test
    fun secretsSurviveANewInstance() {
        val value = byteArrayOf(1, 2, 3)
        store.put("amk", value)

        assertArrayEquals(value, KeystoreSecretStore(dir, TEST_ALIAS).get("amk"))
    }

    @Test
    fun deleteForgetsTheSecret() {
        store.put("db_key", byteArrayOf(9))
        store.delete("db_key")
        assertNull(store.get("db_key"))
        // Deleting what is already gone is fine.
        store.delete("db_key")
    }

    @Test
    fun aFileMovedUnderAnotherNameDoesNotOpen() {
        store.put("db_key", byteArrayOf(7, 7, 7))
        val files = dir.listFiles().orEmpty()
        check(files.size == 1)
        store.put("amk", byteArrayOf(1))
        val amk = dir.listFiles().orEmpty().first { it != files[0] }
        files[0].copyTo(amk, overwrite = true)

        assertThrows(GeneralSecurityException::class.java) { store.get("amk") }
    }

    private companion object {
        const val TEST_ALIAS = "app.geunal.secrets.test"
    }
}

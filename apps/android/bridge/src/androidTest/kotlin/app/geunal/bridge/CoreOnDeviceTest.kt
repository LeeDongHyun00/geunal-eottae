package app.geunal.bridge

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.geunal.core.AlertRule
import app.geunal.core.CalendarKind
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.DayTime
import app.geunal.core.EventInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Calls into libgeunal_ffi.so on the device, which no other test does: the unit tests run against fakes and the
 * Keystore test never touches the core. So this is where CI learns that the library loads on Android (its packed
 * relocations, its JNA glue) and that SQLCipher, on the OpenSSL the core links, writes, reads back and really encrypts
 * with the key the Keystore keeps.
 */
@RunWith(AndroidJUnit4::class)
class CoreOnDeviceTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.noBackupFilesDir, "core-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
    }

    @Test
    fun opensTheStoreAndSyncsWithoutASession() =
        runBlocking {
            open().use { core ->
                assertTrue(core.cipherVersion().isNotBlank())
                // Signed out, sync has nothing to fetch, so it returns without reaching the API base.
                core.sync()
            }
        }

    @Test
    fun keepsAnEventAcrossAReopen() {
        val eventId =
            open().use { core ->
                val calendar = core.calendars().first { it.kind == CalendarKind.OWN }
                core.addEvent(dinner(calendar.id), fromSuggestion = false).eventId
            }

        // A fresh secret store reads the database key back from its sealed file through the Keystore, and the event
        // written before the close comes back out of the encrypted file.
        open().use { core ->
            assertTrue(core.cipherVersion().isNotBlank())
            assertEquals(TITLE, core.event(eventId, null).title)
        }
    }

    @Test
    fun runsSqlCipherOnTheOpenSslTheCoreBuilds() {
        open().use { core ->
            val provider = core.cipherProvider()
            assertEquals("openssl", provider.name)
            // The trimmed libcrypto the core builds, not a stock one: SQLCipher answers with the linked library's text.
            assertTrue(provider.version, provider.version.startsWith("OpenSSL $OPENSSL_VERSION "))
        }
    }

    @Test
    fun setsTheStoreAsideOnceItsKeyIsGone() {
        val eventId =
            open().use { core ->
                assertNull(core.storeReset())
                val calendar = core.calendars().first { it.kind == CalendarKind.OWN }
                core.addEvent(dinner(calendar.id), fromSuggestion = false).eventId
            }
        File(root, "secrets").deleteRecursively()

        // Nothing can open the old file without its key, so the core starts an empty store instead of failing.
        open().use { core ->
            assertNotNull(core.storeReset())
            assertThrows(CoreException.NotFound::class.java) { core.event(eventId, null) }
        }
        // The file kept aside is still ciphertext: SQLCipher encrypts the header too, so it is not a plain SQLite file.
        val aside = File(root, "store/unreadable").walk().first { it.name == "geunal.db" }
        assertFalse(aside.readBytes().decodeToString(0, SQLITE_HEADER.length) == SQLITE_HEADER)
    }

    @Test
    fun setsTheStoreAsideOnceKeystore2SaysItsKeyIsGone() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        open().close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)

        // The database key reads as lost, so the store goes aside and the secrets sealed with it go too.
        open().use { core ->
            assertNotNull(core.storeReset())
            assertNull(core.session())
        }
        // The new key opens the new store.
        open().use { core -> assertNotNull(core.storeReset()) }
    }

    @Test
    fun keepsTheStoreWhenTheOlderKeystoreReturnsNoKey() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        open().close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)

        // Before API 31 that is also what a keystore daemon that did not answer looks like, so nothing goes aside.
        assertThrows(CoreException.SecretStore::class.java) { open().close() }
        assertFalse(File(root, "store/unreadable").exists())
    }

    private fun open(): Core =
        Core.open(
            File(root, "store").apply { mkdirs() }.path,
            UNREACHABLE_API,
            ChangeListener(),
            KeystoreSecretStore(File(root, "secrets"), TEST_ALIAS),
        )

    private fun dinner(calendarId: String) =
        EventInput(
            calendarId = calendarId,
            // Hangul, so the round trip also covers UTF-8 across the FFI.
            title = TITLE,
            allDay = false,
            start = DayTime(day = "2026-11-03", minute = (19 * 60).toUShort()),
            end = DayTime(day = "2026-11-03", minute = (21 * 60).toUShort()),
            recurrence = null,
            alert = AlertRule.Inherit,
            note = null,
        )

    private companion object {
        const val TEST_ALIAS = "app.geunal.secrets.core-test"
        const val TITLE = "저녁 약속"
        const val SQLITE_HEADER = "SQLite format 3"

        // What core/scripts/build-openssl.sh pins; moves with it (docs/security/dependencies.md).
        const val OPENSSL_VERSION = "3.5.9"

        // Nothing here signs in, so the core never sends a request; the discard port makes any slip fail fast.
        const val UNREACHABLE_API = "http://127.0.0.1:9"
    }
}

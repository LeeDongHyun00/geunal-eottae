package app.geunal.feature.account

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.feature.account.security.SensitiveClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The code an earlier process left on the clipboard goes on the next start; anything else stays. */
@RunWith(AndroidJUnit4::class)
class ClipboardLeftoverTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun clipboard() = compose.activity.getSystemService(ClipboardManager::class.java)

    @Test
    fun ourCodeLeftBehindIsCleared() {
        compose.setContent {}
        compose.waitForIdle()
        compose.runOnUiThread {
            SensitiveClipboard(compose.activity).copy("바람 연필")
            SensitiveClipboard(compose.activity).clearLeftover()
            assertNull(
                clipboard()
                    .primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                    ?.takeIf { it.isNotEmpty() },
            )
        }
    }

    @Test
    fun somethingTheyCopiedStays() {
        compose.setContent {}
        compose.waitForIdle()
        compose.runOnUiThread {
            clipboard().setPrimaryClip(ClipData.newPlainText("메모", "장보기"))
            SensitiveClipboard(compose.activity).clearLeftover()
            assertEquals(
                "장보기",
                clipboard()
                    .primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
    }
}

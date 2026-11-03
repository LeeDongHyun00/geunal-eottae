package app.geunal

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.test.services.storage.TestStorage

/** As feature-account's helper: `screenshots/<name>.png` through the test storage service, for review only. */
fun ComposeTestRule.screenshot(name: String) {
    waitForIdle()
    val captured =
        runCatching {
            val bitmap = onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    if (captured.isFailure) Log.w("Screenshots", "no screenshot for $name")
}

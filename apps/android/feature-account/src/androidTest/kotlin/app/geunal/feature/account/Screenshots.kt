package app.geunal.feature.account

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.test.services.storage.TestStorage

/**
 * Saves what is on screen as `screenshots/<name>.png` through the test storage service, which AGP copies off the device
 * (connected_android_test_additional_output) and the android workflow keeps as the android-screenshots artifact. Names
 * follow docs/design/screens/m1. Without [inside] the last window is taken. A sheet lives in a window of its own whose
 * place among the roots is not fixed (the activity's empty window can come last), so a sheet's picture names a tag
 * inside it and the window holding that tag is taken.
 */
fun ComposeTestRule.screenshot(
    name: String,
    inside: String? = null,
) {
    waitForIdle()
    val captured =
        runCatching {
            val roots = if (inside == null) isRoot() else isRoot() and hasAnyDescendant(hasTestTag(inside))
            val bitmap = onAllNodes(roots).onLast().captureToImage().asAndroidBitmap()
            TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    // A picture is for review only; a device that cannot take one must not fail the behaviour the test checks.
    if (captured.isFailure) Log.w("Screenshots", "no screenshot for $name")
}

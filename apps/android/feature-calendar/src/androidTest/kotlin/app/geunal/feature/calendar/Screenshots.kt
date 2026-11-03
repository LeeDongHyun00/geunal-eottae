package app.geunal.feature.calendar

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage

/**
 * Saves what is on screen as `screenshots/<name>.png` through the test storage service, which AGP copies off the device
 * (connected_android_test_additional_output) and the android workflow keeps as the android-screenshots artifact. Names
 * follow docs/design/screens/m1. The whole screen is taken through UiAutomation, so an open sheet, which is a window of
 * its own, is in the picture with what lies under it; the last Compose root is the fallback where that is not allowed.
 */
fun ComposeTestRule.screenshot(name: String) {
    waitForIdle()
    val captured =
        runCatching {
            val bitmap =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?: onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    // A picture is for review only; a device that cannot take one must not fail the behaviour the test checks.
    if (captured.isFailure) Log.w("Screenshots", "no screenshot for $name")
}

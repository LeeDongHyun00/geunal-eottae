package app.geunal.feature.calendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.services.storage.TestStorage

/**
 * Saves what is on screen as `screenshots/<name>.png` through the test storage service, which AGP copies off the device
 * (connected_android_test_additional_output) and the android workflow keeps as the android-screenshots artifact. Names
 * follow docs/design/screens/m1.
 *
 * An open sheet is a window of its own, so each Compose root is captured and they are stacked: the opaque one (the
 * activity) at the bottom, the others (a sheet with its scrim) drawn over it. Neither the last root alone, which was
 * the window under the sheet, nor UiAutomation's screenshot, which comes back black on the CI emulator, shows a sheet.
 */
fun ComposeTestRule.screenshot(name: String) {
    waitForIdle()
    val captured =
        runCatching {
            val roots = onAllNodes(isRoot())
            val bitmaps = List(roots.fetchSemanticsNodes().size) { roots[it].captureToImage().asAndroidBitmap() }
            val base = bitmaps.firstOrNull(::isOpaque) ?: bitmaps.first()
            val picture = base.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(picture)
            // Windows above the activity are anchored to the bottom of the screen, as a sheet is.
            bitmaps.filter { it !== base }.forEach { canvas.drawBitmap(it, 0f, (picture.height - it.height).toFloat(), null) }
            TestStorage().openOutputFile("screenshots/$name.png").use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    // A picture is for review only; a device that cannot take one must not fail the behaviour the test checks.
    if (captured.isFailure) Log.w("Screenshots", "no screenshot for $name")
}

/** The activity's window paints every pixel; a sheet's window is see-through around the sheet. */
private fun isOpaque(bitmap: Bitmap): Boolean =
    listOf(0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1, bitmap.width - 1 to bitmap.height - 1)
        .all { (x, y) -> bitmap.getPixel(x, y) ushr 24 == 0xFF }

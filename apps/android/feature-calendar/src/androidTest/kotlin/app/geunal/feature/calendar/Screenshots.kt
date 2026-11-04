package app.geunal.feature.calendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.services.storage.TestStorage
import org.hamcrest.Matcher

/**
 * Saves what is on screen as `screenshots/<name>.png` through the test storage service, which AGP copies off the device
 * (connected_android_test_additional_output) and the android workflow keeps as the android-screenshots artifact. Names
 * follow docs/design/screens/m1.
 *
 * Compose's capture only ever reads the activity's window. A sheet is a dialog window of its own, so with [sheet] that
 * window's root view is drawn in software and laid over the activity's picture, its scrim included. UiAutomation's
 * screenshot, which would see both, comes back black on the CI emulator.
 */
fun ComposeTestRule.screenshot(
    name: String,
    sheet: Boolean = false,
) {
    waitForIdle()
    val captured =
        runCatching {
            val picture =
                onAllNodes(isRoot())
                    .onFirst()
                    .captureToImage()
                    .asAndroidBitmap()
                    .copy(Bitmap.Config.ARGB_8888, true)
            if (sheet) {
                val window = drawDialogWindow()
                // The sheet's window is anchored to the bottom of the screen.
                Canvas(picture).drawBitmap(window, 0f, (picture.height - window.height).toFloat(), null)
            }
            TestStorage().openOutputFile("screenshots/$name.png").use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    // A picture is for review only; a device that cannot take one must not fail the behaviour the test checks.
    if (captured.isFailure) Log.w("Screenshots", "no screenshot for $name", captured.exceptionOrNull())
}

/** Draws the topmost dialog window's root view into a bitmap on the main thread. */
private fun drawDialogWindow(): Bitmap {
    var drawn: Bitmap? = null
    onView(ViewMatchers.isRoot()).inRoot(isDialog()).perform(
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = ViewMatchers.isRoot()

            override fun getDescription(): String = "draw the dialog window"

            override fun perform(
                uiController: UiController,
                view: View,
            ) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                drawn = bitmap
            }
        },
    )
    return checkNotNull(drawn)
}

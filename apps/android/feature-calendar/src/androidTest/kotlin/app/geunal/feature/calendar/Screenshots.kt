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
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.services.storage.TestStorage
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not

/**
 * Saves what is on screen as `screenshots/<name>.png` through the test storage service, which AGP copies off the device
 * (connected_android_test_additional_output) and the android workflow keeps as the android-screenshots artifact. Names
 * follow docs/design/screens/m1.
 *
 * Compose's capture only ever reads the activity's window. A sheet is a dialog window of its own, so with [sheet] both
 * windows' root views are drawn in software and the sheet's is laid over the activity's, its scrim included. UiAutomation's
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
                if (sheet) {
                    // Which Compose root comes first is not fixed once a dialog is up, so both windows are drawn the
                    // same way: the activity's, then the sheet's over it, anchored to the bottom of the screen.
                    val screen = drawWindow(not(isDialog()))
                    val window = drawWindow(isDialog())
                    Canvas(screen).drawBitmap(window, 0f, (screen.height - window.height).toFloat(), null)
                    screen
                } else {
                    onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
                }
            TestStorage().openOutputFile("screenshots/$name.png").use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    // A picture is for review only; a device that cannot take one must not fail the behaviour the test checks.
    if (captured.isFailure) Log.w("Screenshots", "no screenshot for $name", captured.exceptionOrNull())
}

/** Draws the root view of the window [root] picks into a bitmap, in software, on the main thread. */
private fun drawWindow(root: Matcher<Root>): Bitmap {
    var drawn: Bitmap? = null
    onView(ViewMatchers.isRoot()).inRoot(root).perform(
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = ViewMatchers.isRoot()

            override fun getDescription(): String = "draw the window"

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

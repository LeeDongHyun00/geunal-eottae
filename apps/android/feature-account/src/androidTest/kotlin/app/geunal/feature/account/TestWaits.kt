package app.geunal.feature.account

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule

private const val TIMEOUT_MS = 10_000L

/** Waits until a node matching [matcher] is in any window, the topmost sheet included. */
fun ComposeTestRule.waitFor(matcher: SemanticsMatcher) {
    waitUntil(TIMEOUT_MS) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.waitForTag(tag: String) = waitFor(hasTestTag(tag))

fun ComposeTestRule.waitUntilEnabled(tag: String) = waitFor(hasTestTag(tag) and isEnabled())

fun ComposeTestRule.waitForText(text: String) = waitFor(hasText(text))

package app.geunal.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.EditScope
import app.geunal.core.Frequency
import app.geunal.core.Recurrence
import app.geunal.core.Weekday
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.calendar.editor.EditorModel
import app.geunal.feature.calendar.editor.EditorScreen
import app.geunal.feature.calendar.editor.EditorTarget
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The event editor (m1.md screen 9) on a fake core, as it opens over the tabs. */
@RunWith(AndroidJUnit4::class)
class EditorScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = CalendarFakeCore()
    private var closed = false

    private fun show(target: EditorTarget) {
        val model = EditorModel(target, core, MainScope())
        compose.setContent { GeunalTheme { EditorScreen(model = model, onClose = { closed = true }) } }
    }

    @Test
    fun editsTheTitleOfAnEventAndSavesIt() {
        val dinner = Samples.event("e2", "가족 · 저녁", "2026-10-27", 1140, 1260)
        core.details = mapOf("e2" to Samples.detail(dinner))
        show(EditorTarget.Existing("e2", null))
        compose.waitForText("가족 · 저녁")
        // The calendar's default reads as what it comes to.
        compose.onNodeWithTag("editor.alert").assertTextContains("캘린더 기본 · 10분 전", substring = true)
        compose.onNodeWithTag("editor.start.time").assertTextContains("19:00", substring = true)
        compose.screenshot("09-editor")

        compose.onNodeWithTag("editor.title").performTextReplacement("가족 · 저녁 2")
        compose.onNodeWithTag("editor.save").performClick()
        compose.waitUntil(5_000) { closed }

        val (scope, input) = core.inputs.single()
        assertEquals(EditScope.THIS_AND_FOLLOWING, scope)
        assertEquals("가족 · 저녁 2", input.title)
    }

    @Test
    fun asksWhichOccurrencesBeforeSavingARepeatingEvent() {
        val weekly = Recurrence(Frequency.WEEKLY, 1u, emptyList(), null, null)
        val lecture = Samples.event("e1", "전공 수업", "2026-11-02", 600, 720).copy(occurrence = "2026-11-02")
        core.details = mapOf("e1" to Samples.detail(lecture, recurrence = weekly))
        show(EditorTarget.Existing("e1", "2026-11-02"))
        compose.waitForText("전공 수업")

        compose.onNodeWithTag("editor.title").performTextReplacement("휴강")
        compose.onNodeWithTag("editor.save").performClick()
        compose.waitForTag("scope.thisOnly")
        compose.screenshot("09-editor--scope")
        compose.onNodeWithTag("scope.thisOnly").performClick()
        compose.waitUntil(5_000) { closed }

        assertEquals(EditScope.THIS_ONLY, core.inputs.single().first)
    }

    @Test
    fun deletesAfterAskingInASheet() {
        val dinner = Samples.event("e2", "가족 · 저녁", "2026-10-27", 1140, 1260)
        core.details = mapOf("e2" to Samples.detail(dinner))
        show(EditorTarget.Existing("e2", null))
        compose.waitForText("가족 · 저녁")

        compose.onNodeWithTag("editor.delete").performScrollTo().performClick()
        compose.waitForTag("delete.confirm")
        compose.onNodeWithTag("delete.confirm").performClick()
        compose.waitUntil(5_000) { closed }

        assertEquals("delete e2 THIS_AND_FOLLOWING", core.calls.last())
    }

    @Test
    fun showsADeviceCalendarsEventReadOnly() {
        val dentist = Samples.event("d9", "치과", "2026-10-29", 600, 660, Samples.device)
        core.details = mapOf("d9" to Samples.detail(dentist))
        show(EditorTarget.Existing("d9", null))

        compose.waitForText("이 일정은 기기 캘린더에서 왔어요. 그 앱에서 고칠 수 있어요")
        compose.onNodeWithTag("editor.title").assertIsNotEnabled()
        compose.onNodeWithTag("editor.save").assertDoesNotExist()
        compose.onNodeWithTag("editor.delete").assertDoesNotExist()
        compose.onNodeWithTag("editor.cancel").assertTextContains("닫기")
    }

    @Test
    fun makesAWeeklyRuleOnChosenDays() {
        show(EditorTarget.New("2026-10-30"))
        compose.waitUntilEnabled("editor.title")
        compose.onNodeWithTag("editor.title").performTextReplacement("수업")

        compose.onNodeWithTag("editor.customRepeat").performScrollTo().performClick()
        compose.waitForTag("customRepeat.done")
        // A Friday event starts with Friday; Tuesday joins it, every second week.
        compose.onNodeWithTag("customRepeat.tuesday").performClick()
        compose.onNodeWithTag("customRepeat.more").performClick()
        compose.onNodeWithTag("customRepeat.summary").assertTextContains("2주마다 화·금")
        compose.onNodeWithTag("customRepeat.done").performClick()
        compose.waitForText("2주마다 화·금")

        compose.onNodeWithTag("editor.save").performClick()
        compose.waitUntil(5_000) { closed }
        val rule =
            core.inputs
                .single()
                .second.recurrence
        assertEquals(Recurrence(Frequency.WEEKLY, 2u, listOf(Weekday.TUESDAY, Weekday.FRIDAY), null, null), rule)
    }
}

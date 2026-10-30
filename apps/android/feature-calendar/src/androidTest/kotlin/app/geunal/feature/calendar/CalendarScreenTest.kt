package app.geunal.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.AddedEvent
import app.geunal.core.AlertRule
import app.geunal.core.DayTime
import app.geunal.core.EventInput
import app.geunal.core.RepeatHint
import app.geunal.core.Suggestion
import app.geunal.core.SuggestionSource
import app.geunal.core.Weekday
import app.geunal.designsystem.GeunalTheme
import app.geunal.designsystem.components.ProposalHost
import app.geunal.designsystem.components.ToastCenter
import app.geunal.designsystem.components.ToastHost
import app.geunal.designsystem.components.ToastStyle
import app.geunal.feature.calendar.quickentry.ResourceEventTexts
import app.geunal.feature.calendar.screen.CalendarScreen
import app.geunal.feature.calendar.screen.CalendarViewKind
import app.geunal.feature.calendar.screen.CalendarViewModel
import app.geunal.feature.calendar.screen.ViewKindStore
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The calendar tab on a fake core, in the October of the boards (docs/design/screens/m1). */
@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core =
        CalendarFakeCore().apply {
            holidays =
                mapOf(
                    "2026-10-03" to listOf(Samples.holiday("2026-10-03", "개천절")),
                    "2026-10-09" to listOf(Samples.holiday("2026-10-09", "한글날")),
                )
            birthdays = mapOf("2026-10-30" to listOf(Samples.birthday("민수", "2026-10-30")))
            events =
                mapOf(
                    "2026-10-23" to listOf(Samples.event("e0", "카페 마감", "2026-10-23", 1020, 1320, Samples.work)),
                    "2026-10-26" to listOf(Samples.event("e1", "알바", "2026-10-26", 1020, 1320, Samples.work)),
                    "2026-10-27" to
                        listOf(Samples.event("e2", "운동", "2026-10-27", 1140, 1200), Samples.event("e3", "스터디", "2026-10-27", 1140, 1260)),
                    "2026-10-28" to listOf(Samples.event("e4", "과제", "2026-10-28", 840, 1020)),
                    "2026-10-29" to listOf(Samples.event("e5", "치과", "2026-10-29", 600, 660)),
                    "2026-10-31" to listOf(Samples.event("e6", "여행", "2026-10-31")),
                )
            suggestionList =
                listOf(
                    Samples.suggestion("민수 생일 약속", "2026-10-30", 1140, 1260),
                    Samples.suggestion("카페 마감", "2026-10-30", 1020, 1320, Samples.work),
                    Samples.suggestion("과제", "2026-10-30", 840, 1020),
                    Samples.suggestion("운동", "2026-10-30", 1140, 1200),
                    Suggestion(
                        EventInput(
                            "c1",
                            "약속",
                            allDay = true,
                            DayTime("2026-10-30", 0u),
                            DayTime("2026-10-30", 0u),
                            null,
                            AlertRule.Inherit,
                            null,
                        ),
                        SuggestionSource.History,
                    ),
                )
            // The third Friday "카페 마감": the core asks whether to make it weekly.
            added = Result.success(AddedEvent("e9", RepeatHint(Weekday.FRIDAY, 3u)))
        }

    private val toasts = ToastCenter(MainScope())

    private class MemoryStore : ViewKindStore {
        var saved = CalendarViewKind.MONTH

        override fun load() = saved

        override fun save(kind: CalendarViewKind) {
            saved = kind
        }
    }

    private fun show(): CalendarViewModel {
        // Monday 26 October 2026, 10:00 in Seoul, whatever the emulator's clock says.
        val now = Instant.parse("2026-10-26T01:00:00Z")
        val model =
            CalendarViewModel(
                core = core,
                toasts = toasts,
                texts = ResourceEventTexts(compose.activity.resources),
                changes = emptyFlow(),
                store = MemoryStore(),
                now = { now },
            )
        compose.setContent {
            GeunalTheme {
                Box(Modifier.fillMaxSize()) {
                    CalendarScreen(model = model)
                    // Where the tabs put them: the card 8 above the toast, the toast in the next-up bar's place.
                    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
                        ProposalHost(center = toasts, modifier = Modifier.padding(bottom = 8.dp))
                        ToastHost(center = toasts, style = ToastStyle.Slot)
                    }
                }
            }
        }
        return model
    }

    @Test
    fun addsFromASuggestionAndMakesItWeeklyThenShowsTheWeekAndTheList() {
        show()
        compose.waitForTag("month.day.2026-10-30")
        compose.screenshot("04-month")

        compose.onNodeWithTag("month.day.2026-10-30").performClick()
        compose.waitForTag("quickEntry.chip.1")
        compose.screenshot("05-quick-entry")

        compose.onNodeWithTag("quickEntry.chip.1").performClick()
        compose.waitForTag("proposal.accept")
        // The toast's row does not merge its text, so the message is a node of its own inside it.
        compose.onNode(hasText("카페 마감을 추가했어요") and hasAnyAncestor(hasTestTag("toast")), useUnmergedTree = true).assertExists()
        compose.screenshot("06-added")

        compose.onNodeWithTag("proposal.accept").performClick()
        compose.waitForText("매주 금요일로 바꿨어요")
        compose.screenshot("06-added--applied")
        assertTrue(core.calls.toString(), "add 카페 마감 2026-10-30 1020-1320 suggested" in core.calls)
        assertTrue(core.calls.toString(), "update e9 WEEKLY" in core.calls)

        segment("주").performClick()
        compose.waitForTag("week.day.2026-10-27")
        compose.screenshot("07-week")

        segment("목록").performClick()
        compose.waitForTag("agenda.day.2026-10-30")
        compose.screenshot("08-list")
    }

    @Test
    fun savesATypedTitleOverAnHourPickedOnTheStrip() {
        core.added = Result.success(AddedEvent("e10", null))
        show()
        compose.waitForTag("calendar.add")

        compose.onNodeWithTag("calendar.add").performClick()
        compose.waitForTag("quickEntry.strip")
        // The strip before the title: the keyboard the title brings up shrinks the sheet, which can scroll the strip out
        // of view. A tap at 39% of the strip lands in the 09:30 slot and takes the hour from 09:00.
        compose.onNodeWithTag("quickEntry.strip").performScrollTo().performTouchInput { click(percentOffset(0.39f, 0.2f)) }
        compose.onNodeWithTag("quickEntry.title").performTextInput("스터디")
        compose.onNodeWithTag("quickEntry.save").performClick()
        compose.waitForText("스터디를 추가했어요")

        assertTrue(core.calls.toString(), "add 스터디 2026-10-26 540-600" in core.calls)
    }

    private fun segment(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("calendar.segment")))
}

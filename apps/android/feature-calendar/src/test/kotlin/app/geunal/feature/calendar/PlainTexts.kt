package app.geunal.feature.calendar

import app.geunal.designsystem.components.Proposal
import app.geunal.feature.calendar.quickentry.EventTexts
import java.time.DayOfWeek

/** Plain texts, so the tests read what the person would see. */
internal object PlainTexts : EventTexts {
    override fun added(title: String) = "${title}을(를) 추가했어요"

    override val undo = "되돌리기"
    override val undone = "되돌렸어요"
    override val undoFailed = "되돌리지 못했어요"
    override val saveFailed = "저장하지 못했어요"

    override fun repeatProposal(weekday: DayOfWeek) = Proposal("매주 $weekday 반복으로 바꿀까요?", "괜찮아요", "반복으로 바꾸기")

    override fun repeatDone(weekday: DayOfWeek) = "매주 ${weekday}로 바꿨어요"

    override val repeatFailed = "바꾸지 못했어요"
}

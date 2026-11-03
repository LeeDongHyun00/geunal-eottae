# Resume notes (project on hold, issue #82)

## State when paused
- Remote: claude/compassionate-rubin-aae4or = d502d7c, PR #73 open (Android PR 3).
- Local only: pr4-local 6b1d94c (/home/user/geunal-pr4), pr5-local 32c378f (5a) + 24eebae (5b) (/home/user/geunal-pr5).
- Trigger trig_01B1A61YVeFHkEm3auEmthvL disabled by the coordination session (not deleted).
- PR bodies drafted: pr4-body.md, pr5a-body.md, pr5b-body.md in this scratchpad (fill __LOCAL__).

## #73 red on d502d7c: android / instrumented
- Failing test: CalendarScreenTest.opensTheEditorAndTheShiftStampFromEachView (CalendarScreenTest.kt:216).
- Error: two nodes with tag `month.day.2026-10-02`. One is off screen (l=-94): the month pager also composes
  September, whose grid ends on Saturday 3 October, so 2 October is a cell of both pages.
- Not caused by d502d7c; the 9b7993b run of this job was cancelled, so the test never ran before.
- Planned fix (MonthView MonthCell): tag `month.day.<day>` only for days of the page's own month, and give the
  leading and trailing days of the neighbouring months `month.outside.<day>`. Check that no test taps an outside
  cell by `month.day`. Then compile, push, and say in one PR comment what failed and why.
- Also apply decision 37 (start failure screen copy, design #70, main 698fdac) in the account flow PR.

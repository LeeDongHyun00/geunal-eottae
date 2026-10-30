import DesignSystem
import FeatureCalendar
import GeunalCore
import SwiftUI

/// The calendar tab: month, week and list views with the quick entry (docs/design/m1.md screens 4–8).
struct CalendarTab: View {
    let model: AppModel
    let core: any CoreProtocol
    @Binding var openEvent: EventItem?
    /// The recovery code banner: "저장" while the code is not written down, "넣기" while the backup waits for it.
    let recoveryBanner: CalendarScreen.RecoveryBanner?

    var body: some View {
        NavigationStack {
            CalendarScreen(
                core: core, toasts: model.toasts, changes: model.calendarChanges, openEvent: $openEvent,
                recoveryBanner: recoveryBanner
            )
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

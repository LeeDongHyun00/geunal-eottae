import DesignSystem
import FeatureCalendar
import GeunalCore
import SwiftUI

/// The calendar tab: month, week and list views with the quick entry (docs/design/m1.md screens 4–8).
struct CalendarTab: View {
    let model: AppModel
    let core: any CoreProtocol
    @Binding var openEvent: EventItem?
    /// The recovery code banner's "저장", while the code is not written down.
    let onRecoveryBanner: (() -> Void)?

    var body: some View {
        NavigationStack {
            CalendarScreen(
                core: core, toasts: model.toasts, changes: model.calendarChanges, openEvent: $openEvent,
                onRecoveryBanner: onRecoveryBanner
            )
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

import DesignSystem
import FeatureCalendar
import GeunalCore
import SwiftUI

/// The calendar tab: month, week and list views with the quick entry (docs/design/m1.md screens 4–8).
struct CalendarTab: View {
    let model: AppModel
    let core: any CoreProtocol

    var body: some View {
        NavigationStack {
            CalendarScreen(core: core, toasts: model.toasts, changes: model.calendarChanges)
                .toolbar(.hidden, for: .navigationBar)
        }
    }
}

import SwiftUI

/// The calendar tab. Until the month, week and list views land (docs/design/m1.md screens 4, 7, 8), it keeps the M0
/// check that the core opens and syncs on the device.
struct CalendarTab: View {
    @State private var status = CoreStatusModel(openCore: AppCore.open)

    var body: some View {
        NavigationStack {
            CoreStatusView(model: status)
        }
    }
}

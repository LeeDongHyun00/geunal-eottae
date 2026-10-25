import SwiftUI

@main
struct AppMain: App {
    @State private var model = CoreStatusModel(openCore: AppCore.open)

    var body: some Scene {
        WindowGroup {
            CoreStatusView(model: model)
        }
    }
}

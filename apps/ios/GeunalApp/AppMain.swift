import FeatureAccount
import HomeWidget
import SwiftUI

@main
struct AppMain: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        KakaoSetup.configure()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .onOpenURL { url in
                    if KakaoSetup.handle(url) {
                        return
                    }
                    if let link = WidgetLink(url: url) {
                        AppModel.shared.open(link)
                    }
                }
        }
    }
}

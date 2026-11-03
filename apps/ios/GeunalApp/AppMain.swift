import FeatureAccount
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
                    _ = KakaoSetup.handle(url)
                }
        }
    }
}

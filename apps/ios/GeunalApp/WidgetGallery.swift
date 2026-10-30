import DesignSystem
import FeatureCalendar
import GeunalCore
import HomeWidget
import SwiftUI

extension View {
    /// Debug builds draw the widgets over the tabs for the UI test's screenshot (`-GeunalWidgetGallery`); the home
    /// screen itself is out of a UI test's reach.
    @ViewBuilder
    func widgetGalleryIfAsked(core: any CoreProtocol) -> some View {
        #if DEBUG
            if LaunchOptions.showsWidgetGallery {
                overlay { WidgetGallery(core: core) }
            } else {
                self
            }
        #else
            self
        #endif
    }
}

#if DEBUG
    /// The small and medium widgets in both appearances from the snapshot the app writes, laid out like
    /// boards/Widgets.
    private struct WidgetGallery: View {
        let core: any CoreProtocol
        @State private var content: WidgetContent?

        var body: some View {
            ScrollView {
                VStack(spacing: 18) {
                    if let content {
                        HStack(spacing: 18) {
                            tile(SmallWidgetView(content: content), width: 170).environment(\.colorScheme, .light)
                            tile(SmallWidgetView(content: content), width: 170).environment(\.colorScheme, .dark)
                        }
                        tile(MediumWidgetView(content: content), width: 358).environment(\.colorScheme, .light)
                        tile(MediumWidgetView(content: content), width: 358).environment(\.colorScheme, .dark)
                    }
                }
                .padding(.top, 60)
                .frame(maxWidth: .infinity)
            }
            .background(Color(light: 0xe4e4ea, dark: 0xe4e4ea).ignoresSafeArea())
            .accessibilityIdentifier("widgetGallery")
            .task {
                let core = self.core
                let json = try? await Task.detached { try core.widgetSnapshot() }.value
                content = WidgetContent.make(
                    WidgetSnapshot.read(json.map { Data($0.utf8) }), day: KoreanCalendar.day(of: Date()))
            }
        }

        private func tile(_ widget: some View, width: CGFloat) -> some View {
            widget
                .padding(16)
                .frame(width: width, height: 170)
                .background(WidgetColors.background, in: RoundedRectangle(cornerRadius: 24))
        }
    }
#endif

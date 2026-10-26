import SwiftUI

extension View {
    /// The sheet chrome of components.md §6 for content shown in `.sheet`: a fixed height (or the content's own when
    /// `nil` lets the system size it as large), the grabber, radius 20 and the plain background. `dismissible: false`
    /// is for the questions that must be answered (m1.md screen 24).
    public func sheetChrome(height: CGFloat?, dismissible: Bool = true) -> some View {
        modifier(SheetChrome(height: height, dismissible: dismissible))
    }
}

private struct SheetChrome: ViewModifier {
    let height: CGFloat?
    let dismissible: Bool

    private var detents: Set<PresentationDetent> {
        if let height {
            return [.height(height)]
        }
        return [.large]
    }

    func body(content: Content) -> some View {
        content
            .presentationDetents(detents)
            .presentationDragIndicator(dismissible ? .visible : .hidden)
            .presentationCornerRadius(Metrics.Sheet.topRadius)
            .presentationBackground(GeunalColor.background)
            .interactiveDismissDisabled(!dismissible)
    }
}

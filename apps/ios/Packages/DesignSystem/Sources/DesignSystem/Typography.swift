import SwiftUI

extension View {
    /// Applies a step of the type scale: size and weight from the tokens, scaled with the user's text size, with the
    /// letter spacing the tokens give as a fraction of the size.
    public func typeStyle(_ style: TypeStyle) -> some View {
        modifier(TypeStyleModifier(style: style))
    }
}

private struct TypeStyleModifier: ViewModifier {
    let style: TypeStyle
    @ScaledMetric private var size: CGFloat

    init(style: TypeStyle) {
        self.style = style
        _size = ScaledMetric(wrappedValue: style.size, relativeTo: style.relativeTo.textStyle)
    }

    func body(content: Content) -> some View {
        content
            .font(.system(size: size, weight: style.weight.font))
            .tracking(size * style.tracking)
    }
}

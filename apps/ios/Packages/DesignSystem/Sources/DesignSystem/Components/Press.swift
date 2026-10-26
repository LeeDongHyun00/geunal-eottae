import SwiftUI

/// A plain button that only shrinks while pressed (components.md §1), for cards and rows that are buttons as a whole.
public struct PressableStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        PressableBody(configuration: configuration)
    }
}

extension ButtonStyle where Self == PressableStyle {
    public static var pressable: PressableStyle { PressableStyle() }
}

private struct PressableBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? Motion.Press.scale : 1)
            .animation(.easeOut(duration: Motion.Press.duration), value: configuration.isPressed)
    }
}

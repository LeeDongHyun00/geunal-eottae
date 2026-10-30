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
            // The whole frame takes the tap: a row leaves empty space between its title and its time, and a tap there
            // must open it too (the list view's rows did nothing when tapped in the middle).
            .contentShape(Rectangle())
            .scaleEffect(configuration.isPressed && !reduceMotion ? Motion.Press.scale : 1)
            .animation(.easeOut(duration: Motion.Press.duration), value: configuration.isPressed)
    }
}

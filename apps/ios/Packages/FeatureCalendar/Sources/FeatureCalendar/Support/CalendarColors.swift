import DesignSystem
import GeunalCore
import SwiftUI

extension Palette {
    /// The design system's group color of the same name (docs/design/tokens.json `color.group`).
    public var groupColor: GroupColor {
        switch self {
        case .tomato: .tomato
        case .tangerine: .tangerine
        case .mustard: .mustard
        case .olive: .olive
        case .forest: .forest
        case .mint: .mint
        case .sky: .sky
        case .sea: .sea
        case .grape: .grape
        case .plum: .plum
        case .blossom: .blossom
        case .cocoa: .cocoa
        }
    }
}

public enum CalendarColors {
    /// Events from device calendars all share one color, cocoa (m1.md decision 17); the core leaves their color empty.
    public static let deviceCalendar = GroupColor.cocoa

    /// The strong color an event's marker uses: the calendar's tint 5, or the device calendar color.
    public static func marker(_ palette: Palette?) -> Color {
        palette.map { $0.groupColor.representative } ?? deviceCalendar.tint(2)
    }

    /// An event bar or block (components.md §8): tint 2 with ink text in light mode. In dark mode the calendar colors
    /// keep their light values, so the bar turns to tint 5 with white text (m1.md §4 "어두운 모드").
    public static func bar(_ palette: Palette?, scheme: ColorScheme) -> (fill: Color, text: Color) {
        let color = palette?.groupColor ?? deviceCalendar
        switch scheme {
        case .dark: return (color.representative, .white)
        default: return (color.tint(2), GeunalColor.ink)
        }
    }
}

/// The fill of an event bar or block. A soft calendar's events are striped like the boards (135° stripes over the
/// lightest tint), so they read as "mine only, free to groups" without a legend.
public struct EventFill: View {
    private let palette: Palette?
    private let soft: Bool
    @Environment(\.colorScheme) private var scheme

    public init(_ palette: Palette?, soft: Bool) {
        self.palette = palette
        self.soft = soft
    }

    public var body: some View {
        if soft {
            let color = palette?.groupColor ?? CalendarColors.deviceCalendar
            let dark = scheme == .dark
            let base = dark ? color.representative : color.tint(1)
            let stripe = dark ? Color.black.opacity(0.2) : Color.white.opacity(0.35)
            Canvas { context, size in
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(base))
                var stripes = Path()
                // 4 pt stripes every 8 pt across the stripes, as diagonals long enough to cross the rectangle.
                let step = 8 * 2.squareRoot()
                var offset = -size.height
                while offset < size.width {
                    stripes.move(to: CGPoint(x: offset, y: size.height))
                    stripes.addLine(to: CGPoint(x: offset + size.height, y: 0))
                    offset += step
                }
                context.stroke(stripes, with: .color(stripe), lineWidth: 4)
            }
        } else {
            Rectangle().fill(CalendarColors.bar(palette, scheme: scheme).fill)
        }
    }
}

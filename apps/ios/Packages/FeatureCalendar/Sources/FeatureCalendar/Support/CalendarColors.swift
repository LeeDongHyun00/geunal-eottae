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
    /// Events from device calendars all share one color, cocoa tint 2 (m1.md decision 17); the core leaves their color
    /// empty.
    public static let deviceCalendar = GroupColor.cocoa

    /// The strong color an event's marker uses: the calendar's tint 5, or the device calendar color.
    public static func marker(_ palette: Palette?) -> Color {
        palette.map { $0.groupColor.representative } ?? deviceCalendar.tint(2)
    }
}

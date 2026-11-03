// Linux has no SwiftUI, so the design system's color tokens are not here; the names are all the models need.
import GeunalCore

public enum GroupColor: String, CaseIterable, Sendable {
    case tomato, tangerine, mustard, olive, forest, mint, sky, sea, grape, plum, blossom, cocoa

    public var name: String {
        switch self {
        case .tomato: "토마토"
        case .tangerine: "귤"
        case .mustard: "겨자"
        case .olive: "올리브"
        case .forest: "숲"
        case .mint: "민트"
        case .sky: "하늘"
        case .sea: "바다"
        case .grape: "포도"
        case .plum: "자두"
        case .blossom: "벚꽃"
        case .cocoa: "코코아"
        }
    }
}

extension Palette {
    public var groupColor: GroupColor { GroupColor(rawValue: "\(self)")! }
}

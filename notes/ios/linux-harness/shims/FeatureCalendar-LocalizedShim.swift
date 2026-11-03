// Linux has neither String(localized:) nor SwiftPM's generated Bundle.module; these stand in for the local type check only.
import Foundation
extension String {
    struct LocalizationValue: ExpressibleByStringInterpolation {
        let value: String
        init(stringLiteral value: String) { self.value = value }
        init(stringInterpolation: DefaultStringInterpolation) { value = stringInterpolation.description }
    }
    init(localized value: LocalizationValue, bundle: Bundle? = nil, comment: StaticString? = nil) { self = value.value }
}
extension Bundle {
    static var module: Bundle { .main }
}

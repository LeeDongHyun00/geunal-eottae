import Foundation

/// Messages the models hand to the toast. Views use `Text("…", bundle: .module)` directly; these are the strings that
/// leave the view layer as plain text.
enum Strings {
    static var offline: String {
        String(localized: "연결이 없어요. 게스트로 시작하고 나중에 연결할 수 있어요", bundle: .module)
    }
    static var signInFailed: String { String(localized: "로그인이 안 됐어요. 다시 시도해 주세요", bundle: .module) }
    static var settingFailed: String { String(localized: "설정을 바꾸지 못했어요. 다시 시도해 주세요", bundle: .module) }
    static var somethingFailed: String { String(localized: "지금은 할 수 없어요. 잠시 뒤에 다시 시도해 주세요", bundle: .module) }
    static var ticketExpired: String { String(localized: "시간이 지나 처음부터 다시 로그인해 주세요", bundle: .module) }
}

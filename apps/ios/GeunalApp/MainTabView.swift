import DesignSystem
import FeatureAccount
import FeatureCalendar
import GeunalCore
import SwiftUI

/// The tab bar: 캘린더 · 나 in M1 (m1.md decision 1), with the next-up bar above it. iOS 26 draws the tab bar in
/// Liquid Glass and takes the bar as its bottom accessory; iOS 17 and 18 keep the system tab bar with the same colors
/// and put the bar right above it (docs/design/README.md §5).
struct MainTabView: View {
    private enum Tab: Hashable {
        case calendar
        case me
    }

    let model: AppModel
    let core: any CoreProtocol
    @State private var tab: Tab = .calendar
    /// The event the next-up bar asked to open; the calendar tab takes it.
    @State private var openEvent: EventItem?
    /// The person whose birthday the next-up bar asked to open; the 나 tab takes it.
    @State private var openPerson: String?
    /// A screen of 나 to open from elsewhere: the recovery code, from the month view's banner.
    @State private var openRoute: MeTab.Route?

    var body: some View {
        TabView(selection: $tab) {
            CalendarTab(
                model: model, core: core, openEvent: $openEvent,
                onRecoveryBanner: model.recoveryUnsaved ? { openRecoveryCode() } : nil
            )
            .toastProposal(model.toasts)
            .nextUpInset(isEnabled: slotIsEnabled) { slot }
            .tabItem {
                Label {
                    Text("캘린더")
                } icon: {
                    Image(systemName: "calendar")
                }
            }
            .tag(Tab.calendar)
            MeTab(core: core, app: model, openPerson: $openPerson, openRoute: $openRoute)
                .toastProposal(model.toasts)
                .nextUpInset(isEnabled: slotIsEnabled) { slot }
                .tabItem {
                    Label {
                        Text("나")
                    } icon: {
                        Image(systemName: "person.fill")
                    }
                }
                .tag(Tab.me)
        }
        .tint(GeunalColor.tabText)
        .nextUpAccessory(isEnabled: slotIsEnabled) { slot }
    }

    /// The slot stays while the bar is on, and otherwise only while a toast takes its place.
    private var slotIsEnabled: Bool {
        model.nextUp != nil && (model.showsNextUpBar || model.toasts.current != nil)
    }

    /// The bar, or the toast in its place (m1.md screen 6).
    @ViewBuilder
    private var slot: some View {
        if let nextUp = model.nextUp {
            Group {
                if model.showsNextUpBar {
                    NextUpBar(model: nextUp, chrome: Self.drawsOwnChrome) { item in
                        // An event opens in the editor, a birthday the person in 사람·생일 (m1.md §2.6).
                        switch item {
                        case .event(let event, _, _):
                            tab = .calendar
                            openEvent = event
                        case .birthday(let birthday):
                            tab = .me
                            openPerson = birthday.personId
                        }
                    }
                } else {
                    Color.clear.frame(height: Metrics.NextUpBar.height)
                }
            }
            .toastSlot(model.toasts, chrome: Self.drawsOwnChrome)
        }
    }

    /// The banner's "저장": the OS prompt comes on the calendar, and only a pass opens 나 › 복구 코드 (decision 26).
    private func openRecoveryCode() {
        Task {
            guard await model.owner.confirm(.recoveryCode) else { return }
            tab = .me
            openRoute = .recovery
        }
    }

    private static var drawsOwnChrome: Bool {
        if #available(iOS 26.0, *) {
            return false
        }
        return true
    }
}

extension View {
    /// iOS 26: the next-up bar as the tab view's bottom accessory. From 26.1 the accessory only hides through
    /// `isEnabled`; on 26.0 an empty accessory hides itself.
    @ViewBuilder
    fileprivate func nextUpAccessory<Accessory: View>(
        isEnabled: Bool, @ViewBuilder _ accessory: () -> Accessory
    ) -> some View {
        if #available(iOS 26.1, *) {
            tabViewBottomAccessory(isEnabled: isEnabled) { accessory() }
        } else if #available(iOS 26.0, *) {
            tabViewBottomAccessory {
                if isEnabled {
                    accessory()
                }
            }
        } else {
            self
        }
    }

    /// iOS 17 and 18: the bar floats above the system tab bar inside each tab.
    @ViewBuilder
    fileprivate func nextUpInset<Bar: View>(isEnabled: Bool, @ViewBuilder _ bar: () -> Bar) -> some View {
        if #available(iOS 26.0, *) {
            self
        } else {
            safeAreaInset(edge: .bottom) {
                if isEnabled {
                    bar()
                        .padding(.horizontal, Metrics.TabBar.sideInset)
                        .padding(.bottom, Metrics.NextUpBar.gapAboveTabBar)
                }
            }
        }
    }
}

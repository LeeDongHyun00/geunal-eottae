import DesignSystem
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

    var body: some View {
        TabView(selection: $tab) {
            CalendarTab(model: model, core: core, openEvent: $openEvent)
                .toastProposal(model.toasts)
                .nextUpInset { slot }
                .tabItem {
                    Label {
                        Text("캘린더")
                    } icon: {
                        Image(systemName: "calendar")
                    }
                }
                .tag(Tab.calendar)
            MeTab(core: core)
                .toastProposal(model.toasts)
                .nextUpInset { slot }
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
        .nextUpAccessory { slot }
    }

    /// The bar, or the toast in its place (m1.md screen 6).
    @ViewBuilder
    private var slot: some View {
        if let nextUp = model.nextUp {
            NextUpBar(model: nextUp, chrome: Self.drawsOwnChrome) { item in
                // An event opens in the editor (m1.md §2.6); a birthday leads to the calendar until 사람·생일 exists.
                tab = .calendar
                if case .event(let event, _, _) = item {
                    openEvent = event
                }
            }
            .toastSlot(model.toasts, chrome: Self.drawsOwnChrome)
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
    /// iOS 26: the next-up bar as the tab view's bottom accessory.
    @ViewBuilder
    fileprivate func nextUpAccessory<Accessory: View>(@ViewBuilder _ accessory: () -> Accessory) -> some View {
        if #available(iOS 26.0, *) {
            tabViewBottomAccessory { accessory() }
        } else {
            self
        }
    }

    /// iOS 17 and 18: the bar floats above the system tab bar inside each tab.
    @ViewBuilder
    fileprivate func nextUpInset<Bar: View>(@ViewBuilder _ bar: () -> Bar) -> some View {
        if #available(iOS 26.0, *) {
            self
        } else {
            safeAreaInset(edge: .bottom) {
                bar()
                    .padding(.horizontal, Metrics.TabBar.sideInset)
                    .padding(.bottom, Metrics.NextUpBar.gapAboveTabBar)
            }
        }
    }
}

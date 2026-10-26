import CoreBridge
import DesignSystem
import FeatureAccount
import FeatureCalendar
import GeunalCore
import SwiftUI

/// 나 (m1.md screen 12, boards/Me): the account card, "캘린더" (calendars, shift templates, people, device
/// calendars), "보안과 백업" (recovery code, backup, device change) and "계정" (alerts, what the server knows, the
/// alpha statistics, deletion). Without an account the security rows read dim; the calendar rows always work.
struct MeTab: View {
    enum Route: Hashable {
        case people(focus: PersonFocus?)
        case calendars
        case notifications
        case recovery
        case backup
        case deviceChange
        case serverKnows
        case deleteAccount
        /// The M0 status screen; only debug builds link to it.
        case coreStatus
    }

    let core: any CoreProtocol
    let app: AppModel
    /// A person to open in 사람·생일, from a birthday on the next-up bar.
    @Binding var openPerson: String?
    /// A screen to open, from the recovery code banner on the month view.
    @Binding var openRoute: Route?
    @State private var model: MeModel
    @State private var link: LinkModel
    @State private var path: [Route] = []
    @State private var stamping = false
    @State private var choosingProvider = false

    init(core: any CoreProtocol, app: AppModel, openPerson: Binding<String?>, openRoute: Binding<Route?>) {
        self.core = core
        self.app = app
        _openPerson = openPerson
        _openRoute = openRoute
        _model = State(initialValue: MeModel(core: core))
        let toasts = app.toasts
        _link = State(
            initialValue: LinkModel(core: core, providers: AppCore.providers(), notify: { toasts.show($0) }))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.l) {
                    Text("나")
                        .typeStyle(.largeTitle)
                        .foregroundStyle(GeunalColor.ink)
                        .padding(.top, Space.xl)
                        .accessibilityAddTraits(.isHeader)
                    profile
                    calendarRows
                    securityRows
                    accountRows
                    #if DEBUG
                        // The M0 check that the core opens and syncs on the device, kept for the UI test.
                        NavigationLink(value: Route.coreStatus) {
                            SettingsRow(Text("코어 상태"))
                                .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("me.coreStatus")
                    #endif
                }
                .padding(.horizontal, Space.m)
                .padding(.bottom, Space.xxl)
            }
            .background(GeunalColor.groupedBackground)
            .navigationDestination(for: Route.self) { route in
                destination(route)
            }
            .task(id: app.accountChanges) { await model.load() }
            .onChange(of: path) { _, path in
                // Back on the list: the counts and states may have changed under it, the code on paper among them.
                if path.isEmpty {
                    Task {
                        await model.load()
                        await app.refreshAccount()
                    }
                }
            }
        }
        .onChange(of: openPerson, initial: true) { _, id in
            guard let id else { return }
            path = [.people(focus: PersonFocus(personId: id))]
            openPerson = nil
        }
        // The opener asked the OS already when the route is the recovery code (MainTabView.openRecoveryCode).
        .onChange(of: openRoute, initial: true) { _, route in
            guard let route else { return }
            path = [route]
            openRoute = nil
        }
        .fullScreenCover(isPresented: $stamping) {
            ShiftStampScreen(core: core) {
                stamping = false
                Task { await model.load() }
            }
        }
        .confirmationDialog(
            Text("어느 계정을 연결할까요?"), isPresented: $choosingProvider, titleVisibility: .visible
        ) {
            Button {
                Task { await linkProvider(.kakao) }
            } label: {
                Text("카카오")
            }
            Button {
                Task { await linkProvider(.apple) }
            } label: {
                Text("Apple")
            }
        } message: {
            Text("연결하면 기기를 잃어도 그 계정으로 로그인해 돌아올 수 있어요.")
        }
    }

    // MARK: Rows

    private var profile: some View {
        HStack(spacing: Space.m) {
            Text(verbatim: initial)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .frame(width: 48, height: 48)
                .background(GroupColor.grape.tint(2), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                profileTitle
                    .typeStyle(.bodyStrong)
                    .foregroundStyle(GeunalColor.ink)
                profileDetail
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            profileAction
        }
        .padding(Space.m)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("me.profile")
    }

    private var initial: String {
        switch model.account {
        case .guest(let view), .provider(let view): String(view.displayName.prefix(1))
        default: ""
        }
    }

    private var profileTitle: Text {
        switch model.account {
        case .loading: Text(verbatim: " ")
        case .none: Text("계정 없이 쓰는 중이에요")
        case .guest(let view), .provider(let view): Text(verbatim: view.displayName)
        case .signedOut: Text("로그아웃됨")
        }
    }

    private var profileDetail: Text {
        switch model.account {
        case .loading: Text(verbatim: " ")
        case .none: Text("이 기기에서만 일정을 쓰고 있어요")
        case .guest: Text("게스트 · 기기를 잃으면 복구 코드로만 돌아올 수 있어요")
        case .provider(let view): view.providers.contains(.kakao) ? Text("카카오 계정") : Text("Apple 계정")
        case .signedOut: Text("캘린더만 쓸 수 있어요")
        }
    }

    @ViewBuilder private var profileAction: some View {
        switch model.account {
        case .guest:
            Button {
                choosingProvider = true
            } label: {
                Text("카카오·Apple 연결")
            }
            .buttonStyle(.geunal(.prominentPill))
            .disabled(link.isWorking)
            .accessibilityIdentifier("me.link")
        case .signedOut:
            Button {
                app.signInAgain()
            } label: {
                Text("로그인")
            }
            .buttonStyle(.geunal(.prominentPill))
            .accessibilityIdentifier("me.signIn")
        default:
            EmptyView()
        }
    }

    private var calendarRows: some View {
        RowGroup(header: Text("캘린더")) {
            NavigationLink(value: Route.calendars) {
                SettingsRow(Text("캘린더 관리"), value: count(model.summary?.calendars, unit: .calendar))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.calendars")
            separator
            Button {
                stamping = true
            } label: {
                SettingsRow(Text("교대 템플릿"), value: count(model.summary?.shiftTemplates, unit: .calendar))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.shiftTemplates")
            separator
            NavigationLink(value: Route.people(focus: nil)) {
                SettingsRow(Text("사람·생일"), value: count(model.summary?.people, unit: .person))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.people")
            separator
            // The mirror and its own screen land with the device calendars (screen 15's second group).
            SettingsRow(
                Text("기기 캘린더 가져오기"),
                value: (model.summary?.deviceCalendars ?? 0) > 0 ? Text("읽기만 · 켜짐") : Text("읽기만 · 꺼짐"),
                chevron: false)
        }
    }

    private var securityRows: some View {
        RowGroup(header: Text("보안과 백업")) {
            Button {
                openRecoveryCode()
            } label: {
                SettingsRow(
                    Text("복구 코드"), value: recoveryValue,
                    valueColor: model.summary?.recoverySaved == false ? GeunalColor.sunday : GeunalColor.secondaryText)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.recovery")
            separator
            NavigationLink(value: Route.backup) {
                SettingsRow(Text("암호화 백업"), value: backupValue)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.backup")
            separator
            NavigationLink(value: Route.deviceChange) {
                SettingsRow(Text("기기 바꾸기"))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.deviceChange")
        }
        .disabled(!model.hasAccount)
        .opacity(model.hasAccount ? 1 : 0.4)
    }

    private var accountRows: some View {
        RowGroup(header: Text("계정")) {
            NavigationLink(value: Route.notifications) {
                SettingsRow(Text("알림"))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.notifications")
            separator
            NavigationLink(value: Route.serverKnows) {
                SettingsRow(Text("서버가 아는 것 · 보안 안내"))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("me.serverKnows")
            if model.showsAlphaStats {
                separator
                GeunalToggle(
                    isOn: Binding(
                        get: { model.alphaStats }, set: { on in Task { await model.setAlphaStats(on) } }),
                    title: Text("테스트 통계 보내기"), detail: Text("알파 빌드 · 하루 한 번 숫자 네 개만")
                )
                .accessibilityIdentifier("me.alphaStats")
            }
            if model.hasAccount {
                separator
                NavigationLink(value: Route.deleteAccount) {
                    // SettingsRow keeps its title ink; this one is the red row of the board.
                    HStack {
                        Text("계정 삭제")
                            .typeStyle(.body)
                            .foregroundStyle(GeunalColor.sunday)
                        Spacer(minLength: Space.s)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(GeunalColor.tertiaryText)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, Space.m)
                    .frame(minHeight: Metrics.Row.setting)
                    .contentShape(Rectangle())
                    .accessibilityElement(children: .combine)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("me.delete")
            }
        }
    }

    private var separator: some View {
        Rectangle().fill(GeunalColor.fill).frame(height: 1)
    }

    private var recoveryValue: Text {
        guard let summary = model.summary, model.hasAccount else { return Text(verbatim: "") }
        return summary.recoverySaved ? Text("✓ 저장함") : Text("저장 안 함")
    }

    private var backupValue: Text {
        guard let last = model.summary?.lastBackupAt else { return Text("아직 없어요") }
        return Text(verbatim: MeText.time(last))
    }

    private enum Unit {
        case calendar
        case person
    }

    private func count(_ value: UInt32?, unit: Unit) -> Text? {
        guard let value else { return nil }
        switch unit {
        case .calendar: return Text("\(Int(value))개")
        case .person: return Text("\(Int(value))명")
        }
    }

    // MARK: Destinations

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .people(let focus):
            PeopleScreen(core: core, contacts: DeviceContacts(), toasts: app.toasts, focus: focus)
        case .calendars:
            CalendarsScreen(core: core)
        case .notifications:
            NotificationSettingsScreen(
                model: NotificationSettingsModel(core: core, permission: MeText.alertPermission),
                openSettings: { MeText.openSettings() })
        case .recovery:
            RecoveryCodeScreen(
                model: RecoveryCodeModel(core: core), screenLocked: app.owner.hasScreenLock,
                backupLine: { MeText.backupLine($0) }, notify: { app.toasts.show($0) })
        case .backup:
            BackupScreen(
                model: BackupModel(core: core), online: app.connectivity.isOnline, timeText: MeText.time,
                onRecoveryCode: { openRecoveryCode() })
        case .deviceChange:
            DeviceChangeGuideScreen(onRecoveryCode: model.hasAccount ? { openRecoveryCode() } : nil)
        case .serverKnows:
            ServerKnowsScreen()
        case .deleteAccount:
            DeleteAccountScreen(
                model: DeleteAccountModel(
                    core: core, providers: AppCore.providers(), owner: app.owner, account: accountView),
                online: app.connectivity.isOnline,
                onDeleted: {
                    path = []
                    app.accountDeleted()
                })
        case .coreStatus:
            coreStatus
        }
    }

    @ViewBuilder private var coreStatus: some View {
        #if DEBUG
            CoreStatusScreen()
        #endif
    }

    private var accountView: AccountView? {
        switch model.account {
        case .guest(let view), .provider(let view): view
        default: nil
        }
    }

    /// Every way to the twelve words passes the OS prompt first; cancelling stays where the person was (decision 26).
    private func openRecoveryCode() {
        Task {
            guard await app.owner.confirm(.recoveryCode) else { return }
            path.append(.recovery)
        }
    }

    private func linkProvider(_ provider: Provider) async {
        if await link.link(provider, online: app.connectivity.isOnline) {
            await model.load()
        }
    }
}

#if DEBUG
    /// Owns the status model, so redrawing the 나 tab does not start the check over.
    private struct CoreStatusScreen: View {
        @State private var model = CoreStatusModel(openCore: AppCore.open)

        var body: some View {
            CoreStatusView(model: model)
        }
    }
#endif

import CoreBridge
import DesignSystem
import FeatureAccount
import GeunalCore
import SwiftUI

/// Onboarding until the person reaches the calendar once, the tabs afterwards. Over the tabs come the "기기
/// 로그아웃됨" sheet and the sign-in that follows it.
struct RootView: View {
    @State private var model = AppModel.shared
    /// "이 기기에서 다시 로그인" waits for the sheet to go: SwiftUI drops a cover asked for while a sheet is closing.
    @State private var signInAfterSheet = false
    /// The restore over the tabs sent a returning guest to the sign-in; it opens once the restore is gone.
    @State private var signInAfterRestore = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.phase {
            case .starting:
                GeunalColor.background.ignoresSafeArea()
            case .failed(let failure):
                StartFailureView(failure: failure, onRetry: { await model.retryStart() })
            case .onboarding:
                if let core = model.core {
                    OnboardingFlow(
                        dependencies: dependencies(core), resumesOnPermissions: model.onboardingResumes,
                        onFinish: { model.finishOnboarding() })
                }
            case .main:
                if let core = model.core {
                    MainTabView(model: model, core: core)
                        .sheet(item: shownRevocation, onDismiss: signInIfAsked) { item in
                            RevokedSheet(
                                revocation: item.revocation, when: MeText.time(item.revocation.at),
                                onSignIn: {
                                    signInAfterSheet = true
                                    model.revocationShown()
                                },
                                onCalendarOnly: { model.revocationShown() })
                        }
                        .fullScreenCover(isPresented: $model.signingIn) {
                            OnboardingFlow(
                                dependencies: dependencies(core), onClose: { model.signingIn = false },
                                onFinish: { model.signedInAgain() })
                        }
                        .fullScreenCover(
                            isPresented: $model.enteringRecoveryCode, onDismiss: signInAfterRestoreIfAsked
                        ) {
                            RestoreCover(
                                dependencies: dependencies(core), mode: model.returningAsGuest ? .guestReturn : .offer,
                                onClose: { model.recoveryCodeClosed() },
                                onBackToSignIn: {
                                    // A returning guest's "새 게스트 계정으로 시작" needs the sign-in screen's age tick.
                                    signInAfterRestore = true
                                    model.recoveryCodeClosed()
                                })
                        }
                        .sheet(isPresented: asksLocalData) {
                            if let question = model.localData, let local = question.asking {
                                KeepLocalSheet(local: local) { choice in
                                    Task { await question.answer(choice) }
                                }
                            }
                        }
                }
            }
        }
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.appBecameActive()
            case .background: Task { await BackgroundWork.schedule() }
            default: break
            }
        }
    }

    private func dependencies(_ core: any CoreProtocol) -> OnboardingDependencies {
        OnboardingDependencies(
            core: core, providers: AppCore.providers(), permissions: DevicePermissions(), owner: model.owner,
            connectivity: model.connectivity, toasts: model.toasts, state: model.onboardingState,
            timeText: { MeText.time($0) }, importDeviceCalendars: { await model.mirror?.importAll() })
    }

    /// The sheet only closes with an answer.
    private var asksLocalData: Binding<Bool> {
        Binding(get: { model.localData?.asking != nil }, set: { _ in })
    }

    /// An item rather than a flag, so the sheet keeps its words while it slides away.
    private var shownRevocation: Binding<RevocationItem?> {
        Binding(
            get: { model.revocation.map(RevocationItem.init) },
            set: { item in
                if item == nil {
                    model.revocationShown()
                }
            })
    }

    private func signInAfterRestoreIfAsked() {
        guard signInAfterRestore else { return }
        signInAfterRestore = false
        model.signInAgain()
    }

    private func signInIfAsked() {
        guard signInAfterSheet else { return }
        signInAfterSheet = false
        model.signInAgain()
    }
}

private struct RevocationItem: Identifiable {
    let revocation: Revocation
    var id: Date { revocation.at }
}

/// The core could not open its store (m1.md §4 "시작 오류", decision 37). Nothing was deleted, so the screen says that
/// first and walks through retrying, restarting the phone and updating the app. It never suggests reinstalling, which
/// would delete a calendar a restart or an update could open, and names only the kind of failure, not the core's words.
private struct StartFailureView: View {
    let failure: StartFailure
    let onRetry: () async -> Void
    @State private var opening = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.m) {
                    Text("그날어때를 열 수 없어요")
                        .typeStyle(.title2)
                        .foregroundStyle(GeunalColor.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("이 기기의 캘린더를 지금 열지 못했어요. 지운 것은 없으니 아래 순서대로 해 보세요.")
                        .typeStyle(.body)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(alignment: .leading, spacing: Space.s) {
                        step(1, Text("'다시 시도'를 눌러 주세요."))
                        step(2, Text("그래도 안 되면 휴대폰을 껐다 켠 뒤 다시 열어 주세요."))
                        step(3, Text("계속 안 되면 앱 업데이트가 있는지 확인해 주세요."))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cardBackground()
                    reason
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.tertiaryText)
                        .accessibilityIdentifier("start.failure")
                }
                .padding(.horizontal, Space.xl)
                .padding(.top, Space.xxxl)
                .padding(.bottom, Space.l)
            }
            Button {
                opening = true
                Task {
                    await onRetry()
                    opening = false
                }
            } label: {
                if opening {
                    Text("여는 중…")
                } else {
                    Text("다시 시도")
                }
            }
            .buttonStyle(.geunal(.primary))
            .disabled(opening)
            .accessibilityIdentifier("start.retry")
            .padding(.horizontal, Space.xl)
            .padding(.bottom, Space.s)
        }
        .background(GeunalColor.background)
    }

    private var reason: Text {
        switch failure {
        case .key: Text("보관한 열쇠를 읽지 못했어요")
        case .store: Text("저장소를 열지 못했어요")
        case .unknown: Text("원인을 알 수 없어요")
        }
    }

    /// One numbered step, read as one line with its number.
    private func step(_ number: Int, _ text: Text) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.xs) {
            Text(verbatim: "\(number).")
                .typeStyle(.body)
                .monospacedDigit()
                .foregroundStyle(GeunalColor.secondaryText)
            text
                .typeStyle(.body)
                .foregroundStyle(GeunalColor.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

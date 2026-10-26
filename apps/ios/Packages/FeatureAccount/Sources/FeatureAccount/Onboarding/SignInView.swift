import CoreBridge
import DesignSystem
import GeunalCore
import SwiftUI

/// The sign-in screen (m1.md screen 2, boards/SignIn.dc.html). The age box gates the three ways that make an account;
/// "복구 코드로 돌아오기" stays on because it returns to an account that already exists (decision 7).
public struct SignInView: View {
    @Bindable private var model: SignInModel
    private let connectivity: ConnectivityMonitor
    private let onOutcome: (SignInModel.Outcome) -> Void
    private let onGuestReturn: () -> Void

    public init(
        model: SignInModel, connectivity: ConnectivityMonitor,
        onOutcome: @escaping (SignInModel.Outcome) -> Void, onGuestReturn: @escaping () -> Void
    ) {
        self.model = model
        self.connectivity = connectivity
        self.onOutcome = onOutcome
        self.onGuestReturn = onGuestReturn
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.s) {
                    Text("그날어때 시작하기", bundle: .module)
                        .font(.system(size: 30, weight: .semibold))
                        .tracking(-0.9)
                        .foregroundStyle(GeunalColor.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("계정은 로그인과 멤버 목록에만 써요. 일정과 대화 내용은 운영자도 볼 수 없어요.", bundle: .module)
                        .typeStyle(.body)
                        .lineSpacing(5)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 24)
                .padding(.top, Space.l)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            VStack(spacing: Space.s) {
                CheckboxRow(
                    isOn: $model.isAgeConfirmed,
                    title: Text("만 14세 이상이에요", bundle: .module),
                    detail: Text("확인한 시각만 저장해요", bundle: .module)
                )
                .accessibilityIdentifier("signIn.age")
                VStack(spacing: 10) {
                    providerButton(.kakao)
                    providerButton(.apple)
                    HStack(spacing: 4) {
                        Button {
                            Task {
                                if let outcome = await model.continueAsGuest(isOnline: connectivity.isOnline) {
                                    onOutcome(outcome)
                                }
                            }
                        } label: {
                            if model.busy == .guest {
                                Text("시작하는 중", bundle: .module)
                            } else {
                                Text("게스트로 둘러보기", bundle: .module)
                            }
                        }
                        .buttonStyle(.geunal(.text, height: 48))
                        .disabled(!model.isAgeConfirmed || model.busy != nil)
                        .accessibilityIdentifier("signIn.guest")
                        Text(verbatim: "·")
                            .foregroundStyle(GeunalColor.tertiaryText)
                            .accessibilityHidden(true)
                        Button(action: onGuestReturn) {
                            Text("복구 코드로 돌아오기", bundle: .module)
                        }
                        .buttonStyle(.geunal(.text, height: 48))
                        .disabled(model.busy != nil)
                        .accessibilityIdentifier("signIn.guestReturn")
                    }
                }
                TermsLine()
                    .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, Space.s)
        }
        .background(GeunalColor.background)
        .navigationTitle(Text("로그인", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // The title names the back button of the next screen ("‹ 로그인"); this screen shows none (the board).
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        .toolbarRole(.editor)
    }

    private func providerButton(_ provider: Provider) -> some View {
        let enabled = model.isAgeConfirmed && model.busy == nil && connectivity.isOnline
        let kind: SignInModel.Busy = provider == .kakao ? .kakao : .apple
        let busy = model.busy == kind
        return Button {
            Task {
                if let outcome = await model.signIn(with: provider, isOnline: connectivity.isOnline) {
                    onOutcome(outcome)
                }
            }
        } label: {
            ProviderButtonLabel(provider: provider, busy: busy)
        }
        .buttonStyle(.pressable)
        .disabled(!enabled)
        // Brand buttons keep their colors when off (Kakao and Apple guidelines), so they fade like the board.
        .opacity(enabled ? 1 : 0.4)
        .accessibilityIdentifier(provider == .kakao ? "signIn.kakao" : "signIn.apple")
    }
}

/// Kakao yellow with its speech bubble, and Apple black with its logo, at the same size (App Store guideline 4.8).
private struct ProviderButtonLabel: View {
    let provider: Provider
    let busy: Bool

    var body: some View {
        HStack(spacing: Space.xs) {
            if busy {
                ProgressView()
                    .tint(foreground)
            } else {
                Image(systemName: provider == .kakao ? "message.fill" : "apple.logo")
                    .font(.system(size: 18, weight: .semibold))
                    .accessibilityHidden(true)
            }
            Group {
                if provider == .kakao {
                    Text("카카오로 시작하기", bundle: .module)
                } else {
                    Text("Apple로 계속하기", bundle: .module)
                }
            }
            .typeStyle(.bodyStrong)
        }
        .foregroundStyle(foreground)
        .frame(maxWidth: .infinity, minHeight: 54)
        .background(background, in: Capsule())
        .contentShape(Capsule())
    }

    private var foreground: Color {
        provider == .kakao ? Color.black.opacity(0.85) : .white
    }

    private var background: Color {
        provider == .kakao ? Color(light: 0xfee500, dark: 0xfee500) : .black
    }
}

/// "시작하면 이용약관과 개인정보 처리방침에 동의하게 돼요." The two documents open when their addresses are set
/// (`GeunalTermsURL`, `GeunalPrivacyURL` in Info.plist); until the domain exists they are underlined text.
private struct TermsLine: View {
    var body: some View {
        Text(attributed)
            .font(.system(size: 13))
            .lineSpacing(4)
            .foregroundStyle(GeunalColor.secondaryText)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    /// One string for the whole sentence so a translation can reorder it; the two links are markers the build's
    /// addresses replace.
    private var attributed: AttributedString {
        let source = String(
            localized: "시작하면 [이용약관](geunal-terms:)과 [개인정보 처리방침](geunal-privacy:)에 동의하게 돼요.", bundle: .module)
        var text = (try? AttributedString(markdown: source)) ?? AttributedString(source)
        for run in text.runs {
            guard let link = run.link else { continue }
            text[run.range].underlineStyle = Text.LineStyle.single
            text[run.range].link = Self.url(link.scheme == "geunal-terms" ? "GeunalTermsURL" : "GeunalPrivacyURL")
        }
        return text
    }

    private static func url(_ key: String) -> URL? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : URL(string: $0) }
    }
}

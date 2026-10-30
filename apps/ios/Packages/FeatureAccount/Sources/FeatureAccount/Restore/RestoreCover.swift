import DesignSystem
import GeunalCore
import SwiftUI

/// "백업 가져오기" over the tabs, after "나중에 넣을게요": the month view's banner and 나 › 복구 코드 open it (m1.md
/// screens 4, 12, 21). Onboarding is over, so "닫기" takes the place of the way back to the sign-in, and every way out
/// (restored, started fresh, "나중에" once more) closes it.
public struct RestoreCover: View {
    @State private var model: RestoreModel
    private let timeText: (Date) -> String
    private let onClose: () -> Void

    public init(dependencies: OnboardingDependencies, onClose: @escaping () -> Void) {
        let toasts = dependencies.toasts
        _model = State(
            initialValue: RestoreModel(
                mode: .offer, core: dependencies.core, owner: dependencies.owner, providers: dependencies.providers,
                lastSignIn: dependencies.state.lastProviderSignIn, notify: { toasts.show($0) }))
        timeText = dependencies.timeText
        self.onClose = onClose
    }

    public var body: some View {
        NavigationStack {
            RestoreView(
                model: model, timeText: timeText, onDone: onClose, onStartedFresh: onClose, onLater: onClose,
                onBackToSignIn: onClose
            )
            .toolbar {
                // A restore under way, and "돌아왔어요" with its own button, keep the screen (decision 30).
                if !model.holdsTheScreen {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(action: onClose) {
                            Text("닫기", bundle: .module)
                        }
                        .accessibilityIdentifier("restore.close")
                    }
                }
            }
        }
        .tint(GeunalColor.ink)
    }
}

import DesignSystem
import GeunalCore
import SwiftUI

/// "기기 바꾸기" and "복구 코드로 돌아오기" (m1.md screen 21, boards/Restore.dc.html): twelve cells, paste, the error
/// states in the order the design judges them, then the restore and "돌아왔어요".
public struct RestoreView: View {
    @Bindable private var model: RestoreModel
    @FocusState private var focus: Int?
    @Environment(\.scenePhase) private var scenePhase
    @State private var showsNoCode = false
    private let onDone: () -> Void
    private let onStartedFresh: () -> Void
    private let onBackToSignIn: () -> Void

    public init(
        model: RestoreModel, onDone: @escaping () -> Void, onStartedFresh: @escaping () -> Void,
        onBackToSignIn: @escaping () -> Void
    ) {
        self.model = model
        self.onDone = onDone
        self.onStartedFresh = onStartedFresh
        self.onBackToSignIn = onBackToSignIn
    }

    public var body: some View {
        Group {
            switch model.phase {
            case .finished(let summary):
                RestoreDoneView(mode: model.mode, summary: summary, onOpen: onDone)
            case .restoring(let done, let total):
                RestoringView(done: done, total: total)
            case .entering, .askingLocalData, .startedFresh, .ticketExpired:
                entry
            }
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        .navigationBarBackButtonHidden(isBusyPhase)
        .sheet(isPresented: $showsNoCode) {
            NoCodeSheet(mode: model.mode) {
                Task {
                    if await model.startWithoutCode() {
                        showsNoCode = false
                    } else if model.mode == .guestReturn {
                        showsNoCode = false
                        onBackToSignIn()
                    }
                }
            }
        }
        .sheet(isPresented: askingLocalData) {
            if case .askingLocalData(let local) = model.phase {
                KeepLocalSheet(local: local) { choice in
                    Task { await model.choose(choice) }
                }
            }
        }
        .onChange(of: model.phase) { _, phase in
            switch phase {
            case .startedFresh: onStartedFresh()
            case .ticketExpired: onBackToSignIn()
            default: break
            }
        }
        .onChange(of: model.focused) { _, index in
            focus = index
        }
        .onChange(of: focus) { previous, index in
            if let previous, previous != index {
                Task { await model.committed(previous) }
            }
            model.focused = index
        }
        .onAppear { focus = model.focused }
    }

    private var isBusyPhase: Bool {
        switch model.phase {
        case .restoring, .finished: true
        default: false
        }
    }

    private var askingLocalData: Binding<Bool> {
        Binding {
            if case .askingLocalData = model.phase { return true }
            return false
        } set: { _ in
        }
    }

    private var title: Text {
        switch model.mode {
        case .guestReturn: Text("복구 코드로 돌아오기", bundle: .module)
        case .deviceChange, .offer: Text("기기 바꾸기", bundle: .module)
        }
    }

    private var entry: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: Space.xs) {
                        title
                            .font(.system(size: 30, weight: .semibold))
                            .tracking(-0.9)
                            .foregroundStyle(GeunalColor.ink)
                            .accessibilityAddTraits(.isHeader)
                        intro
                            .font(.system(size: 15))
                            .lineSpacing(4)
                            .foregroundStyle(GeunalColor.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack {
                        progress
                            .font(.system(size: 13, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(GeunalColor.secondaryText)
                        Spacer()
                        PasteButton(payloadType: String.self) { strings in
                            guard let text = strings.first else { return }
                            Task { await model.paste(text) }
                        }
                        .labelStyle(.titleOnly)
                        .buttonBorderShape(.capsule)
                        .tint(GeunalColor.ink)
                        .accessibilityIdentifier("restore.paste")
                    }
                    .padding(.top, Space.xxl)
                    WordGrid(model: model, focus: $focus)
                        .padding(.top, 14)
                    notice
                        .padding(.top, 14)
                }
                .padding(.horizontal, Space.l)
                .padding(.top, Space.xs)
                .padding(.bottom, Space.l)
            }
            .scrollDismissesKeyboard(.interactively)
            VStack(spacing: Space.xs) {
                Button {
                    focus = nil
                    Task { await model.start() }
                } label: {
                    actionTitle
                }
                .buttonStyle(.geunal(.primary))
                .disabled(!model.isComplete || model.isWorking)
                .accessibilityIdentifier("restore.start")
                Button {
                    showsNoCode = true
                } label: {
                    Text("복구 코드가 없어요", bundle: .module)
                }
                .buttonStyle(.geunal(.text))
                .accessibilityIdentifier("restore.noCode")
            }
            .padding(.horizontal, Space.l)
            .padding(.bottom, Space.s)
        }
        // iOS keeps a picture of the screen for the app switcher once the app leaves the front; for a guest the twelve
        // words are the account itself (docs/security/threat-model.md 3.6).
        .overlay {
            if scenePhase != .active {
                GeunalColor.groupedBackground.ignoresSafeArea()
            }
        }
    }

    private var intro: Text {
        switch model.mode {
        case .deviceChange:
            Text("이 계정은 다른 기기에서 쓰고 있어요. 복구 코드 12단어를 넣으면 암호화 백업을 이 기기로 가져오고, 이전 기기는 로그아웃돼요.", bundle: .module)
        case .offer:
            Text("이 계정에는 암호화 백업이 있어요. 복구 코드 12단어를 넣으면 백업을 이 기기로 가져와요.", bundle: .module)
        case .guestReturn:
            Text("게스트 계정은 복구 코드가 곧 열쇠예요. 12단어를 넣으면 계정을 찾고 암호화 백업을 이 기기로 가져와요. 이전 기기는 로그아웃돼요.", bundle: .module)
        }
    }

    private var progress: Text {
        if model.isComplete {
            return Text("12단어 모두 넣었어요", bundle: .module)
        }
        return Text("\(model.acceptedCount) / 12", bundle: .module)
    }

    private var actionTitle: Text {
        if case .unknownWord(let index) = model.problem {
            return Text("\(index + 1)번째 단어를 고쳐 주세요", bundle: .module)
        }
        if !model.isComplete {
            return Text("단어 \(RestoreModel.wordCount - model.acceptedCount)개 더", bundle: .module)
        }
        if model.isWorking {
            return Text("가져오는 중", bundle: .module)
        }
        switch model.problem {
        case .wrongCode, .offline, .failed:
            return Text("다시 시도", bundle: .module)
        case .unknownWord, .none:
            return model.mode == .guestReturn
                ? Text("계정 찾기", bundle: .module) : Text("백업 가져오기", bundle: .module)
        }
    }

    @ViewBuilder
    private var notice: some View {
        switch model.problem {
        case .unknownWord(let index):
            NoticeCard(
                systemImage: "exclamationmark.triangle",
                text: Text("\(index + 1)번째 단어가 목록에 없어요. 종이에 적은 글자를 다시 봐 주세요.", bundle: .module),
                tone: .problem)
        case .wrongCode:
            NoticeCard(systemImage: "exclamationmark.triangle", text: wrongCodeText, tone: .problem)
        case .offline:
            NoticeCard(
                systemImage: "wifi.slash", text: Text("연결이 없어요. 연결되면 다시 시도할게요.", bundle: .module),
                tone: .offline)
        case .failed:
            NoticeCard(
                systemImage: "exclamationmark.triangle",
                text: Text("지금은 가져올 수 없어요. 잠시 뒤에 다시 시도해 주세요.", bundle: .module), tone: .problem)
        case .none:
            NoticeCard(
                systemImage: "lock",
                text: Text("단어는 이 기기에서만 쓰여요. 두 글자를 넣으면 단어가 정해지고, 목록에 없는 단어는 바로 빨갛게 표시돼요. 횟수 제한은 없어요.", bundle: .module),
                tone: .calm)
        }
    }

    private var wrongCodeText: Text {
        if model.mode == .guestReturn {
            return Text("이 코드로 계정을 찾지 못했어요. 게스트 계정의 코드인지, 카카오·Apple에 연결한 계정은 아닌지 확인해 주세요.", bundle: .module)
        }
        return Text("이 코드로는 백업이 열리지 않아요. 다른 계정의 코드이거나 단어 하나가 다를 수 있어요.", bundle: .module)
    }
}

/// Three columns of 48 pt cells: filled cells blend into the page, the current one has an ink ring, an unknown word is
/// red (boards/Restore.dc.html).
private struct WordGrid: View {
    @Bindable var model: RestoreModel
    var focus: FocusState<Int?>.Binding

    private let columns = Array(repeating: GridItem(.flexible(), spacing: Space.xs), count: 3)

    var body: some View {
        LazyVGrid(columns: columns, spacing: Space.xs) {
            ForEach(0..<RestoreModel.wordCount, id: \.self) { index in
                cell(index)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("복구 코드 12단어 입력", bundle: .module))
    }

    private func cell(_ index: Int) -> some View {
        let unknown = model.isUnknown(index)
        let current = focus.wrappedValue == index
        let filled = !model.cells[index].trimmed.isEmpty
        return HStack(spacing: Space.xs) {
            Text(verbatim: "\(index + 1)")
                .font(.system(size: 12))
                .monospacedDigit()
                .foregroundStyle(GeunalColor.tertiaryText)
                .accessibilityHidden(true)
            ZStack(alignment: .leading) {
                if current, let completion = model.completions[index], completion.hasPrefix(model.cells[index]) {
                    // The rest of the one word this prefix leads to, taken when the cell is left.
                    Text(verbatim: completion)
                        .foregroundStyle(GeunalColor.tertiaryText)
                        .accessibilityHidden(true)
                }
                TextField(text: $model.cells[index]) {
                    Text("\(index + 1)번째 단어", bundle: .module)
                }
                .foregroundStyle(unknown ? GeunalColor.sunday : GeunalColor.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(nil)
                .submitLabel(index == RestoreModel.wordCount - 1 ? .done : .next)
                .focused(focus, equals: index)
                .onSubmit { Task { await model.submitted(index) } }
                .onChange(of: model.cells[index]) { _, _ in
                    Task { await model.edited(index) }
                }
                .accessibilityIdentifier("restore.word.\(index + 1)")
            }
            .font(.system(size: 16, weight: .semibold))
        }
        .padding(.horizontal, 10)
        .frame(height: 48)
        .background(
            unknown ? GeunalColor.problemBackground : (filled && !current ? Color.clear : GeunalColor.background),
            in: RoundedRectangle(cornerRadius: Radius.input)
        )
        .overlay {
            RoundedRectangle(cornerRadius: Radius.input)
                .strokeBorder(
                    unknown ? GeunalColor.sunday : (current ? GeunalColor.ink : GeunalColor.separator),
                    lineWidth: unknown || current ? 2 : (filled ? 0 : 1))
        }
        .contentShape(Rectangle())
        .onTapGesture { focus.wrappedValue = index }
    }
}

private struct RestoringView: View {
    let done: UInt64
    let total: UInt64

    var body: some View {
        VStack(spacing: Space.m) {
            Spacer()
            ProgressView(value: total == 0 ? nil : Double(done), total: Double(max(total, 1)))
                .progressViewStyle(.linear)
                .tint(GeunalColor.ink)
                .frame(maxWidth: 240)
            Text("잠금을 풀고 받는 중", bundle: .module)
                .typeStyle(.bodyStrong)
                .foregroundStyle(GeunalColor.ink)
            if total > 0 {
                Text(
                    verbatim: ByteCountFormatter.string(fromByteCount: Int64(done), countStyle: .file) + " / "
                        + ByteCountFormatter.string(fromByteCount: Int64(total), countStyle: .file)
                )
                .typeStyle(.caption)
                .monospacedDigit()
                .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// "돌아왔어요", or its no-backup variant (the AMK opened but the account had no backup).
private struct RestoreDoneView: View {
    let mode: RestoreModel.Mode
    let summary: RestoreSummary
    let onOpen: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            VStack(spacing: 14) {
                Image(systemName: "checkmark")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(GeunalColor.background)
                    .frame(width: 64, height: 64)
                    .background(GeunalColor.ink, in: Circle())
                    .accessibilityHidden(true)
                Text("돌아왔어요", bundle: .module)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                    .padding(.top, 4)
                    .accessibilityAddTraits(.isHeader)
                message
                    .font(.system(size: 16))
                    .lineSpacing(5)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(GeunalColor.ink.opacity(0.85))
                note
                    .font(.system(size: 14))
                    .lineSpacing(4)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(GeunalColor.ink.opacity(0.85))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
            }
            .padding(.horizontal, 32)
            Spacer()
            Button(action: onOpen) {
                Text("캘린더 열기", bundle: .module)
            }
            .buttonStyle(.geunal(.primary))
            .padding(.horizontal, Space.l)
            .padding(.bottom, Space.s)
            .accessibilityIdentifier("restore.open")
        }
        .background(GeunalColor.background)
    }

    private var message: Text {
        if summary.backupAt == nil {
            if mode == .guestReturn {
                return Text("계정을 찾았어요. 가져올 백업은 없어서 일정은 이 기기에서 새로 시작해요.", bundle: .module)
            }
            return summary.replacedDevice
                ? Text("가져올 백업이 없었어요. 일정은 이 기기에서 새로 시작하고, 이전 기기는 로그아웃됐어요.", bundle: .module)
                : Text("가져올 백업이 없었어요. 일정은 이 기기에서 새로 시작해요.", bundle: .module)
        }
        let events = Int(summary.events)
        let people = Int(summary.people)
        let templates = Int(summary.shiftTemplates)
        if summary.replacedDevice {
            return Text(
                "일정 \(events)개, 사람 \(people)명, 템플릿 \(templates)개를 가져왔어요. 이전 기기는 로그아웃됐어요.",
                bundle: .module)
        }
        return Text("일정 \(events)개, 사람 \(people)명, 템플릿 \(templates)개를 가져왔어요.", bundle: .module)
    }

    private var note: Text {
        if summary.backupAt == nil {
            return Text("이 기기에서 백업하면 다음부터는 이 복구 코드로 되찾을 수 있어요.", bundle: .module)
        }
        if summary.groups > 0 {
            return Text("그룹에는 다시 들어가는 중이에요. 그룹 캘린더는 잠시 뒤에 채워져요.", bundle: .module)
        }
        return Text("이 기기에서 바로 이어서 쓸 수 있어요.", bundle: .module)
    }
}

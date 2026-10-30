import DesignSystem
import GeunalCore
import SwiftUI

/// "연락처에서 생일 가져오기" (m1.md §2.4): after the permission, the contacts that have a birthday, each with a
/// checkbox, and "N명 가져오기". Without the permission the sheet says how to turn it on and offers to add by hand.
struct ContactImportSheet: View {
    @State private var model: ContactImportModel
    private let onFinished: (ContactImport) -> Void
    private let onAddByHand: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    init(model: ContactImportModel, onFinished: @escaping (ContactImport) -> Void, onAddByHand: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.onFinished = onFinished
        self.onAddByHand = onAddByHand
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            header
            switch model.phase {
            case .checking:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .denied:
                denied
            case .picking, .importing, .finished:
                if model.candidates.isEmpty {
                    nobody
                } else {
                    picking
                }
            }
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .padding(.bottom, Space.s)
        .task { await model.start() }
        // "설정에서 켜기" leaves the app; coming back with the permission on reads the contacts.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, model.phase == .denied {
                Task { await model.start() }
            }
        }
        .onChange(of: model.phase) { _, phase in
            if case .finished(let result) = phase {
                onFinished(result)
            }
        }
        .sheetChrome(height: nil)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text("연락처에서 생일 가져오기", bundle: .module)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("선택 기능이에요. 가져온 이름과 생일은 이 기기와 암호화 백업에만 있어요.", bundle: .module)
                    .font(.system(size: 14))
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            IconButton(systemImage: "xmark", label: Text("닫기", bundle: .module)) {
                dismiss()
            }
            .padding(.top, -6)
            .padding(.trailing, -12)
        }
    }

    private var denied: some View {
        VStack(spacing: Space.s) {
            NoticeCard(
                systemImage: "person.crop.square",
                text: Text("연락처 권한이 꺼져 있어요. 설정에서 켜면 생일이 있는 연락처를 골라 가져올 수 있어요.", bundle: .module),
                tone: .offline)
            Button {
                model.openSettings()
            } label: {
                Text("설정에서 켜기", bundle: .module)
            }
            .buttonStyle(.geunal(.primary))
            .accessibilityIdentifier("contacts.settings")
            Button(action: onAddByHand) {
                Text("직접 추가", bundle: .module)
            }
            .buttonStyle(.geunal(.text))
            .accessibilityIdentifier("contacts.addByHand")
            Spacer(minLength: 0)
        }
    }

    private var nobody: some View {
        VStack(spacing: Space.s) {
            NoticeCard(
                systemImage: "gift",
                text: Text("생일이 적힌 연락처가 없어요.", bundle: .module), tone: .offline)
            Button(action: onAddByHand) {
                Text("직접 추가", bundle: .module)
            }
            .buttonStyle(.geunal(.primary))
            .accessibilityIdentifier("contacts.addByHand")
            Spacer(minLength: 0)
        }
    }

    private var picking: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            HStack {
                Text("생일이 있는 연락처 \(model.candidates.count)명", bundle: .module)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(GeunalColor.secondaryText)
                Spacer()
                Button {
                    model.toggleAll()
                } label: {
                    if model.allSelected {
                        Text("모두 빼기", bundle: .module)
                    } else {
                        Text("모두 고르기", bundle: .module)
                    }
                }
                .buttonStyle(.geunal(.pill))
                .accessibilityIdentifier("contacts.all")
            }
            ScrollView {
                LazyVStack(spacing: Space.xs) {
                    ForEach(model.candidates) { candidate in
                        CheckboxRow(
                            isOn: Binding(
                                get: { model.isSelected(candidate.id) }, set: { _ in model.toggle(candidate.id) }),
                            title: Text(verbatim: candidate.name),
                            detail: Text(verbatim: PeopleText.birthday(candidate.birthday)))
                    }
                }
            }
            if model.withoutBirthday > 0 {
                Text("생일이 없는 연락처 \(model.withoutBirthday)명은 빼고 보여 줘요.", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            if model.failed {
                NoticeCard(
                    systemImage: "exclamationmark.circle",
                    text: Text("가져오지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
            }
            Button {
                Task { await model.importSelected() }
            } label: {
                if model.selected.isEmpty {
                    Text("가져올 사람을 골라 주세요", bundle: .module)
                } else {
                    Text("\(model.selected.count)명 가져오기", bundle: .module)
                }
            }
            .buttonStyle(.geunal(.primary))
            .disabled(model.selected.isEmpty || model.phase != .picking)
            .accessibilityIdentifier("contacts.import")
        }
    }
}

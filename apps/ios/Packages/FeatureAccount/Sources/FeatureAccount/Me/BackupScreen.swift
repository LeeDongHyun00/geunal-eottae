import DesignSystem
import GeunalCore
import SwiftUI

/// 암호화 백업 (m1.md screen 20, boards/Backup): the last backup with "지금 백업", the automatic switch, "최근 백업 ·
/// 3개까지 보관", and what a backup holds and leaves out.
public struct BackupScreen: View {
    @State private var model: BackupModel
    private let timeText: (Date) -> String
    private let online: Bool
    private let onRecoveryCode: () -> Void

    /// `timeText` words a backup's time ("오늘 03:12", "10월 23일 03:11"); the app has the date style.
    public init(
        model: BackupModel, online: Bool, timeText: @escaping (Date) -> String, onRecoveryCode: @escaping () -> Void
    ) {
        _model = State(initialValue: model)
        self.online = online
        self.timeText = timeText
        self.onRecoveryCode = onRecoveryCode
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                Text("암호화 백업", bundle: .module)
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("기기에서 잠근 뒤 올려요. 복구 코드가 있어야 풀 수 있고, 그날어때는 열어 볼 수 없어요.", bundle: .module)
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                if !model.recoverySaved {
                    Button(action: onRecoveryCode) {
                        NoticeCard(
                            systemImage: "key",
                            text: Text("복구 코드를 저장해야 백업을 되찾을 수 있어요 ›", bundle: .module), tone: .offline)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("backup.recovery")
                }
                statusCard
                RowGroup {
                    GeunalToggle(
                        isOn: Binding(
                            get: { model.status?.auto ?? false },
                            set: { on in Task { await model.setAuto(on) } }),
                        title: Text("자동 백업", bundle: .module),
                        detail: Text("와이파이에서 하루 한 번. 바뀐 게 없으면 건너뛰어요", bundle: .module)
                    )
                    .accessibilityIdentifier("backup.auto")
                }
                if !model.backups.isEmpty {
                    RowGroup(header: Text("최근 백업 · 3개까지 보관", bundle: .module)) {
                        ForEach(Array(model.backups.enumerated()), id: \.element.id) { index, backup in
                            BackupRow(
                                backup: backup, time: timeText(backup.createdAt),
                                separated: index < model.backups.count - 1)
                        }
                    }
                }
                HStack(alignment: .top, spacing: Space.s) {
                    contents(
                        Text("들어가는 것", bundle: .module),
                        [
                            Text("내 일정과 캘린더", bundle: .module), Text("사람·생일", bundle: .module),
                            Text("교대 템플릿", bundle: .module), Text("설정", bundle: .module),
                            Text("채팅 글(M3)", bundle: .module),
                        ])
                    contents(
                        Text("안 들어가는 것", bundle: .module),
                        [
                            Text("사진·파일", bundle: .module), Text("기기 캘린더 일정", bundle: .module),
                            Text("그룹의 상태", bundle: .module),
                        ],
                        note: Text("보관 기간 안이면 다시 받아요", bundle: .module))
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.xxl)
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(Text("암호화 백업", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        .task { await model.load() }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 4) {
                    Group {
                        if model.isRunning {
                            Text("백업하는 중", bundle: .module)
                        } else {
                            Text("마지막 백업", bundle: .module)
                        }
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(GeunalColor.secondaryText)
                    Group {
                        if model.justMade {
                            Text("방금", bundle: .module)
                        } else if let last = model.status?.last {
                            Text(verbatim: timeText(last.createdAt))
                        } else {
                            Text("아직 없어요", bundle: .module)
                        }
                    }
                    .font(.system(size: 20, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.ink)
                    if let last = model.status?.last {
                        Text(verbatim: Self.contents(of: last))
                            .typeStyle(.caption)
                            .monospacedDigit()
                            .foregroundStyle(GeunalColor.secondaryText)
                    }
                }
                Spacer(minLength: Space.s)
                Button {
                    Task { await model.backUpNow() }
                } label: {
                    if model.isRunning {
                        Text("올리는 중", bundle: .module)
                    } else if !online {
                        Text("연결되면 할 수 있어요", bundle: .module)
                    } else {
                        Text("지금 백업", bundle: .module)
                    }
                }
                .buttonStyle(.geunal(.prominentPill))
                .disabled(!model.canBackUp || !online)
                .accessibilityIdentifier("backup.now")
            }
            if case .running(let done, let total) = model.phase, total > 0 {
                ProgressView(value: Double(done), total: Double(total))
                    .tint(GeunalColor.ink)
            }
            switch model.phase {
            case .waitingForNetwork:
                Text("연결되면 다시 올릴게요", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
            case .failed:
                Text("올리지 못했어요. 다시 시도해 주세요", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.sunday)
            default:
                EmptyView()
            }
        }
        .padding(Space.m)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    private func contents(_ title: Text, _ items: [Text], note: Text? = nil) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            title
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(GeunalColor.secondaryText)
            ForEach(items.indices, id: \.self) { index in
                items[index]
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
            }
            if let note {
                note
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
    }

    /// "일정 128개 · 사람 12명 · 2.4MB", with what this device remembers of the backup.
    static func contents(of backup: BackupView) -> String {
        var parts: [String] = []
        if let events = backup.events {
            parts.append(String(localized: "일정 \(Int(events))개", bundle: .module))
        }
        if let people = backup.people {
            parts.append(String(localized: "사람 \(Int(people))명", bundle: .module))
        }
        parts.append(ByteCountFormatter.string(fromByteCount: Int64(backup.size), countStyle: .file))
        return parts.joined(separator: " · ")
    }
}

/// 오늘 03:12 · 자동 · 일정 128개 · 사람 12명 · 2.4MB.
private struct BackupRow: View {
    let backup: BackupView
    let time: String
    let separated: Bool

    var body: some View {
        HStack(spacing: Space.s) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: time)
                    .typeStyle(.body)
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.ink)
                Text(verbatim: detail)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer(minLength: Space.xs)
            Text(verbatim: ByteCountFormatter.string(fromByteCount: Int64(backup.size), countStyle: .file))
                .typeStyle(.body)
                .monospacedDigit()
                .foregroundStyle(GeunalColor.secondaryText)
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Metrics.Row.twoLine)
        .overlay(alignment: .bottom) {
            if separated {
                Rectangle().fill(GeunalColor.fill).frame(height: 1)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var detail: String {
        var parts: [String] = []
        switch backup.automatic {
        case true?: parts.append(String(localized: "자동", bundle: .module))
        case false?: parts.append(String(localized: "직접", bundle: .module))
        case nil: parts.append(String(localized: "다른 기기", bundle: .module))
        }
        if let events = backup.events {
            parts.append(String(localized: "일정 \(Int(events))개", bundle: .module))
        }
        if let people = backup.people {
            parts.append(String(localized: "사람 \(Int(people))명", bundle: .module))
        }
        return parts.joined(separator: " · ")
    }
}

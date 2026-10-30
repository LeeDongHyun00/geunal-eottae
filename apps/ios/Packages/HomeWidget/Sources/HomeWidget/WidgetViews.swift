import DesignSystem
import SwiftUI

/// The widgets' surfaces from boards/Widgets: white and #1c1c1f, with the medium widget's card on #f4f4f6 and
/// #2a2a2e. tokens.json has no widget colors, so they stay here.
public enum WidgetColors {
    public static let background = Color(light: 0xffffff, dark: 0x1c1c1f)
    static let card = Color(light: 0xf4f4f6, dark: 0x2a2a2e)
}

/// 작은 위젯 (m1.md screen 14): the weekday in its color, the big date, and today's first two events.
public struct SmallWidgetView: View {
    private let content: WidgetContent

    public init(content: WidgetContent) {
        self.content = content
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: WidgetText.weekday(content.day))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(WidgetText.weekdayColor(content.day))
            Text(verbatim: WidgetText.dayNumber(content.day))
                .font(.system(size: 40, weight: .semibold))
                .tracking(-1.2)
                .foregroundStyle(GeunalColor.ink)
                .padding(.top, 2)
            Spacer(minLength: 4)
            switch content.state {
            case .events(let today, _):
                if today.isEmpty {
                    Note(text: Text("오늘은 비어 있어요", bundle: .module))
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(today.prefix(2).enumerated()), id: \.offset) { _, event in
                            EventLine(event: event)
                        }
                    }
                }
            case .start:
                Note(text: Text("그날어때를 열어 시작하세요", bundle: .module))
            case .openApp:
                Note(text: Text("그날어때를 열어 주세요", bundle: .module))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// 중간 위젯 (m1.md screen 14): today's events on the left, and on the right the next appointment from M2, which M1
/// fills with tomorrow's events. An event opens in the editor.
public struct MediumWidgetView: View {
    private let content: WidgetContent

    public init(content: WidgetContent) {
        self.content = content
    }

    public var body: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: WidgetText.heading(content.day))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(WidgetText.weekdayColor(content.day))
                    .padding(.bottom, 4)
                switch content.state {
                case .events(let today, _):
                    if today.isEmpty {
                        Note(text: Text("오늘은 비어 있어요", bundle: .module))
                    } else {
                        ForEach(Array(today.prefix(3).enumerated()), id: \.offset) { _, event in
                            LinkedEventLine(event: event)
                        }
                    }
                case .start:
                    Note(text: Text("그날어때를 열어 시작하세요", bundle: .module))
                case .openApp:
                    Note(text: Text("그날어때를 열어 주세요", bundle: .module))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            if case .events(_, let tomorrow?) = content.state {
                VStack(alignment: .leading, spacing: 6) {
                    Text("내일", bundle: .module)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(GeunalColor.secondaryText)
                    if tomorrow.isEmpty {
                        Note(text: Text("내일은 비어 있어요", bundle: .module))
                    } else {
                        ForEach(Array(tomorrow.prefix(2).enumerated()), id: \.offset) { _, event in
                            LinkedEventLine(event: event)
                        }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(WidgetColors.card, in: RoundedRectangle(cornerRadius: 16))
                .layoutPriority(1.15)
            }
        }
    }
}

/// ● 가족 · 저녁 over 18:00–20:00.
private struct EventLine: View {
    let event: WidgetSnapshot.Event

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 6) {
                Circle()
                    .fill(WidgetText.color(event))
                    .frame(width: 8, height: 8)
                Text(verbatim: event.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                    .lineLimit(1)
            }
            Text(verbatim: WidgetText.time(event))
                .font(.system(size: 12))
                .monospacedDigit()
                .foregroundStyle(GeunalColor.secondaryText)
                .padding(.leading, 14)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The event's line, opening it when the core named it.
private struct LinkedEventLine: View {
    let event: WidgetSnapshot.Event

    var body: some View {
        if let link = WidgetLink.event(event) {
            Link(destination: link.url) {
                EventLine(event: event)
            }
        } else {
            EventLine(event: event)
        }
    }
}

private struct Note: View {
    let text: Text

    var body: some View {
        text
            .font(.system(size: 13))
            .foregroundStyle(GeunalColor.secondaryText)
            .fixedSize(horizontal: false, vertical: true)
    }
}

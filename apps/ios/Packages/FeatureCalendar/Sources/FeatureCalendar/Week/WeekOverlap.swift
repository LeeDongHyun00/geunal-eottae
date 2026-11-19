import Foundation
import GeunalCore

/// What the week view draws on one day's time axis (m1.md decision 44): an event alone, or one overlap group in place
/// of every event that overlaps another, directly or through a chain.
public enum WeekItem: Hashable, Sendable {
    case single(TimedBlock)
    case group(OverlapGroup)
}

/// Events of one day that overlap one another, as a chain: 16–17, 16–18 and 17–18 are one group from 16 to 18. Two
/// events that only touch (16–17 and 17–18) do not overlap.
public struct OverlapGroup: Hashable, Sendable {
    public let day: String
    public let startMinute: UInt16
    public let endMinute: UInt16
    /// By start, the longer first on the same start: the order of the group's color lines and of the sheet.
    public let blocks: [TimedBlock]

    /// The group's color lines: one per event, three at most (boards/WeekOverlap).
    public static let linesShown = 3

    /// Where an event lies within the group, from 0 at its start to 1 at its end: its color line and its time bar.
    public func span(of block: TimedBlock) -> ClosedRange<Double> {
        let length = Double(Int(endMinute) - Int(startMinute))
        guard length > 0 else { return 0...1 }
        let from = Double(Int(block.startMinute) - Int(startMinute)) / length
        let to = Double(Int(block.endMinute) - Int(startMinute)) / length
        return max(0, from)...min(1, max(from, to))
    }

    /// The sheet's time scale: the start, the middle and the end, on whole minutes.
    public var scale: [UInt16] {
        let middle = (Int(startMinute) + Int(endMinute)) / 2
        return [startMinute, UInt16(middle), endMinute]
    }

    /// "10월 29일 16:00부터 18:00까지 겹치는 일정 3개: 동아리 회의, 팀플 회의, 과제": the group as one button for a screen
    /// reader; the color lines are not read.
    public func spokenLabel() -> String {
        let date = Self.monthDay(day)
        let start = DayText.clock(startMinute)
        let end = DayText.clock(endMinute)
        let titles = blocks.map(\.event.title).joined(separator: ", ")
        return String(
            localized: "\(date) \(start)부터 \(end)까지 겹치는 일정 \(blocks.count)개: \(titles)", bundle: .module)
    }

    /// The sheet's second line: "16:00–18:00 · 겹치는 일정 3개".
    public var summary: String {
        let range = DayText.range(start: startMinute, end: endMinute)
        return String(localized: "\(range) · 겹치는 일정 \(blocks.count)개", bundle: .module)
    }

    private static func monthDay(_ day: String) -> String {
        guard let parsed = KoreanCalendar.parse(day) else { return day }
        return String(localized: "\(parsed.month)월 \(parsed.day)일", bundle: .module)
    }
}

public enum WeekOverlap {
    /// One day's timed blocks as the week view draws them, in start order: lone events as they are, and every chain of
    /// overlapping events as one group (decision 44).
    public static func items(day: String, blocks: [TimedBlock]) -> [WeekItem] {
        let ordered = blocks.sorted(by: order)
        var items: [WeekItem] = []
        var chain: [TimedBlock] = []
        var chainEnd: UInt16 = 0

        func close() {
            guard let first = chain.first else { return }
            if chain.count == 1 {
                items.append(.single(first))
            } else {
                items.append(
                    .group(
                        OverlapGroup(day: day, startMinute: first.startMinute, endMinute: chainEnd, blocks: chain)))
            }
            chain = []
        }

        for block in ordered {
            // Strictly before the chain's end: an event that starts as another ends only touches it.
            if !chain.isEmpty, block.startMinute < chainEnd {
                chain.append(block)
                chainEnd = max(chainEnd, block.endMinute)
            } else {
                close()
                chain = [block]
                chainEnd = block.endMinute
            }
        }
        close()
        return items
    }

    /// By start; on the same start the longer first, then by title so the order does not change between reads.
    static func order(_ a: TimedBlock, _ b: TimedBlock) -> Bool {
        if a.startMinute != b.startMinute { return a.startMinute < b.startMinute }
        if a.endMinute != b.endMinute { return a.endMinute > b.endMinute }
        return a.event.title < b.event.title
    }
}

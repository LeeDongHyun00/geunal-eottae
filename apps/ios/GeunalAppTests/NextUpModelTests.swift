import FeatureCalendar
import GeunalCore
import XCTest

/// A core whose next-up list the test sets.
final class NextUpFakeCore: Core, @unchecked Sendable {
    let upcoming = Locked<[UpNext]>([])

    init() {
        super.init(noHandle: Core.NoHandle())
    }

    required init(unsafeFromHandle handle: UInt64) {
        fatalError("a fake core never comes from Rust")
    }

    override func nextUp(count: UInt16) throws -> [UpNext] {
        Array(upcoming.current.prefix(Int(count)))
    }
}

@MainActor
final class NextUpModelTests: XCTestCase {
    // 2026-10-26 14:20 in Seoul, 3 h 40 min before the 18:00 dinner.
    private let now = Date(timeIntervalSince1970: 1_792_992_000)

    private func event(_ id: String, title: String, day: String, start: UInt16, end: UInt16) -> EventItem {
        EventItem(
            eventId: id, occurrence: nil, calendarId: "c1", calendarName: "개인", color: .blossom, title: title,
            allDay: false, start: DayTime(day: day, minute: start), end: DayTime(day: day, minute: end), soft: false,
            source: .own, recurrence: nil)
    }

    func testDescribesTheNextEventAndBirthday() async {
        let core = NextUpFakeCore()
        core.upcoming.update {
            $0 = [
                .event(
                    item: event("e1", title: "가족 · 저녁", day: "2026-10-26", start: 1080, end: 1200),
                    startsAt: now.addingTimeInterval(13_200), endsAt: now.addingTimeInterval(20_400)),
                .birthday(item: BirthdayItem(personId: "p1", name: "민수", day: "2026-10-27", lunar: false)),
            ]
        }
        let model = NextUpModel(core: core, now: { [now] in now })

        await model.refresh()

        XCTAssertEqual(model.current?.title, "가족 · 저녁")
        XCTAssertEqual(model.current?.detail, "오늘 18:00–20:00 · 3시간 40분 뒤")
        model.forward()
        XCTAssertEqual(model.current?.title, "민수 생일")
        XCTAssertEqual(model.current?.detail, "내일")
        XCTAssertFalse(model.canGoForward)
        model.back()
        XCTAssertEqual(model.index, 0)
    }

    func testStaysOnTheSameItemAfterARefresh() async {
        let core = NextUpFakeCore()
        let first = UpNext.birthday(item: BirthdayItem(personId: "p1", name: "민수", day: "2026-10-27", lunar: false))
        let second = UpNext.birthday(item: BirthdayItem(personId: "p2", name: "지영", day: "2026-10-28", lunar: false))
        core.upcoming.update { $0 = [first, second] }
        let model = NextUpModel(core: core, now: { [now] in now })
        await model.refresh()
        model.forward()

        core.upcoming.update { $0 = [second] }
        await model.refresh()

        XCTAssertEqual(model.current?.title, "지영 생일")
    }

    func testCountsDownAtEveryMinute() async {
        let core = NextUpFakeCore()
        core.upcoming.update {
            $0 = [
                .event(
                    item: event("e1", title: "가족 · 저녁", day: "2026-10-26", start: 1080, end: 1200),
                    startsAt: now.addingTimeInterval(13_200), endsAt: now.addingTimeInterval(20_400))
            ]
        }
        // 15 seconds into 14:20; the clock moves only when the model waits, and the third wait ends the loop.
        let clock = Locked(now.addingTimeInterval(15))
        let waits = Locked<[TimeInterval]>([])
        let model = NextUpModel(
            core: core, now: { clock.current },
            sleep: { duration in
                guard waits.current.count < 2 else { throw CancellationError() }
                let seconds = Double(duration.components.seconds) + Double(duration.components.attoseconds) / 1e18
                waits.update { $0.append(seconds) }
                clock.update { $0.addTimeInterval(seconds) }
            })
        await model.refresh()
        XCTAssertEqual(model.current?.detail, "오늘 18:00–20:00 · 3시간 40분 뒤")

        await model.keepCurrent()

        XCTAssertEqual(waits.current, [45, 60])
        XCTAssertEqual(model.current?.detail, "오늘 18:00–20:00 · 3시간 38분 뒤")
    }

    func testIsEmptyWhenNothingIsComing() async {
        let model = NextUpModel(core: NextUpFakeCore(), now: { [now] in now })

        await model.refresh()

        XCTAssertNil(model.current)
    }
}

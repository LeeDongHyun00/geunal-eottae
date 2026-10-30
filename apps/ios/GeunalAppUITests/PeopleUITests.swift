import XCTest

/// 사람·생일 on the real core (docs/design/screens/m1 11 and 17): the board's people (`-GeunalDemoPeople`), a lunar
/// birthday typed in with the solar day it falls on this year, and the empty list.
final class PeopleUITests: GeunalUITestCase {
    private func openPeople(_ app: XCUIApplication) {
        let me = app.tabBars.buttons["나"]
        waitFor(me)
        me.tap()
        let people = app.buttons["me.people"]
        waitFor(people)
        people.tap()
    }

    func testListsThePeopleAndAddsALunarBirthday() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalDemoPeople"])
        openPeople(app)
        let minsu = app.buttons["people.upcoming.민수"]
        waitFor(minsu)
        waitFor(app.buttons["people.person.할머니"])
        XCTAssertTrue(minsu.label.contains("D-5"), "민수's birthday is five days away, got '\(minsu.label)'")
        snapshot(app, "11-people")

        app.buttons["people.add"].tap()
        let name = app.textFields["person.name"]
        waitFor(name)
        let save = app.buttons["person.save"]
        XCTAssertEqual(save.label, "이름을 적어 주세요")
        snapshot(app, "17-person-edit")

        name.tap()
        name.typeText("이모\n")
        labeled(app.buttons, "음력").tap()
        app.buttons["person.birthday"].tap()
        app.pickerWheels.element(boundBy: 0).adjust(toPickerWheelValue: "10월")
        app.pickerWheels.element(boundBy: 1).adjust(toPickerWheelValue: "3일")
        let note = app.staticTexts["person.lunarNote"]
        waitUntil(note, "label CONTAINS %@", "올해는")
        let birthday = app.buttons["person.birthday"]
        XCTAssertTrue(birthday.label.contains("음력 10월 3일"), "got '\(birthday.label)'")
        snapshot(app, "17-person-edit--lunar")

        birthday.tap()
        waitUntil(save, "label == %@", "저장")
        save.tap()
        waitFor(app.buttons["people.person.이모"])
    }

    func testShowsTheEmptyList() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished"])
        openPeople(app)
        waitFor(app.buttons["people.addFirst"])
        XCTAssertTrue(app.buttons["people.importFirst"].exists)
        snapshot(app, "11-people--empty")
    }
}

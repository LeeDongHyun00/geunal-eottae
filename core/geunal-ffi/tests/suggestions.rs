//! The quick entry's suggestion chips through `Core` (kickoff §5.2, rust-core §6 "입력 추천"): the dictionary by
//! weekday and day off, this device's history, birthdays within a week, long weekends and substitute holidays, the
//! shift templates, and the chips that clash with the day left out.

mod common;

use common::*;
use geunal_ffi::*;

fn chips(core: &Core, day: &str) -> Vec<Suggestion> {
    core.suggestions(day.into()).unwrap()
}

fn chip_titles(core: &Core, day: &str) -> Vec<String> {
    chips(core, day).into_iter().map(|s| s.event.title).collect()
}

#[test]
fn offers_weekday_chips_and_drops_the_ones_that_clash_with_the_day() {
    let d = device();
    let cal = personal(&d.core);
    // Tuesday 27 October: work first, then the rest of the dictionary by how well it fits a working day.
    assert_eq!(chip_titles(&d.core, "2026-10-27"), vec!["회사 업무", "수업", "회식", "운동", "병원"]);
    let first = chips(&d.core, "2026-10-27").remove(0);
    assert_eq!(first.source, SuggestionSource::Dictionary);
    assert_eq!(
        first.event,
        EventInput {
            calendar_id: cal.clone(),
            title: "회사 업무".into(),
            all_day: false,
            start: at("2026-10-27", 9 * 60),
            end: at("2026-10-27", 18 * 60),
            recurrence: None,
            alert: AlertRule::Inherit,
            note: None,
        }
    );

    // 18:30–19:30 at the dentist leaves out 회식 19–22, 운동 19–20 and 데이트 18–21; an all-day chip never clashes.
    d.core.add_event(timed(&cal, "치과", "2026-10-27", 18 * 60 + 30, 19 * 60 + 30), false).unwrap();
    assert_eq!(chip_titles(&d.core, "2026-10-27"), vec!["회사 업무", "수업", "병원", "약속", "가족 모임"]);

    // Saturday: days-off chips, and no work.
    assert_eq!(chip_titles(&d.core, "2026-10-31"), vec!["가족 모임", "데이트", "약속", "운동", "병원"]);
    assert!(matches!(d.core.suggestions("2026-13-01".into()), Err(CoreError::Invalid { .. })));
}

#[test]
fn puts_a_birthday_within_a_week_first_unless_the_person_is_left_out() {
    let d = device();
    let minsu = add_person(&d.core, "민수", solar(10, 30));
    add_person(&d.core, "지은", solar(11, 20)); // more than a week away
    let found = chips(&d.core, "2026-10-27");
    assert_eq!(found[0].event.title, "민수 생일 약속");
    assert_eq!(found[0].source, SuggestionSource::Birthday { person_id: minsu.clone() });
    assert_eq!((found[0].event.start.minute, found[0].event.end.minute), (19 * 60, 21 * 60));
    assert!(found.iter().all(|s| s.event.title != "지은 생일 약속"));

    // The "날짜 잡기 추천에 쓰기" switch off.
    d.core
        .save_person(PersonInput {
            id: Some(minsu),
            name: "민수".into(),
            birthday: Some(solar(10, 30)),
            alert: AlertRule::Inherit,
            suggest: false,
        })
        .unwrap();
    assert_eq!(chip_titles(&d.core, "2026-10-27")[0], "회사 업무");
}

#[test]
fn offers_a_trip_before_and_during_a_long_weekend_and_an_outing_on_a_substitute_holiday() {
    let d = device();
    // Thursday 24 December, the day before 25–27 December.
    let before = chips(&d.core, "2026-12-24");
    assert_eq!(before[0].event.title, "연휴 여행");
    assert_eq!(before[0].source, SuggestionSource::Holiday);
    assert!(before[0].event.all_day);
    assert_eq!((before[0].event.start.day.as_str(), before[0].event.end.day.as_str()), ("2026-12-24", "2026-12-24"));
    assert_eq!(chip_titles(&d.core, "2026-12-26")[0], "연휴 여행");
    // An ordinary weekend is not a long weekend.
    assert!(chip_titles(&d.core, "2026-11-06").iter().all(|t| t != "연휴 여행"));

    // Tuesday 9 February 2027 is the 설날 substitute holiday, inside 6–9 February.
    assert_eq!(
        chip_titles(&d.core, "2027-02-09"),
        vec!["대체공휴일 나들이", "연휴 여행", "가족 모임", "데이트", "약속"]
    );
}

#[test]
fn brings_back_a_title_used_on_the_same_weekday_with_its_time_and_calendar() {
    let d = device();
    let cafe = d
        .core
        .save_calendar(CalendarInput {
            id: None,
            name: "카페".into(),
            color: Palette::Mint,
            soft: false,
            default_alert: AlertRule::Inherit,
        })
        .unwrap();
    for day in ["2026-10-27", "2026-11-03", "2026-11-10"] {
        d.core.add_event(timed(&cafe, "카페 마감", day, 17 * 60, 22 * 60), false).unwrap();
    }
    let found = chips(&d.core, "2026-11-17");
    assert_eq!(found[0].source, SuggestionSource::History);
    assert_eq!((found[0].event.title.as_str(), found[0].event.calendar_id.as_str()), ("카페 마감", cafe.as_str()));
    assert_eq!((found[0].event.start.minute, found[0].event.end.minute), (17 * 60, 22 * 60));
    // Not on a Tuesday that has it already, and not on another weekday.
    assert!(chip_titles(&d.core, "2026-11-10").iter().all(|t| t != "카페 마감"));
    assert!(chip_titles(&d.core, "2026-11-18").iter().all(|t| t != "카페 마감"));
    // Unused for 30 days it loses its recency but still leads.
    set_clock(&d, 2026, 12, 20, 0);
    assert_eq!(chip_titles(&d.core, "2026-12-22")[0], "카페 마감");
}

#[test]
fn offers_shift_templates_and_ends_a_night_shift_the_next_day() {
    let d = device();
    let template = d
        .core
        .save_shift_template(ShiftTemplateInput {
            id: None,
            name: "마감".into(),
            start_minute: 22 * 60,
            end_minute: 2 * 60,
            calendar_id: None,
            alert: AlertRule::Inherit,
        })
        .unwrap();
    let found = chips(&d.core, "2026-10-27");
    assert_eq!(
        found.iter().map(|s| s.event.title.as_str()).collect::<Vec<_>>(),
        vec!["회사 업무", "수업", "마감", "회식", "운동"]
    );
    let night = found.iter().find(|s| s.event.title == "마감").unwrap();
    assert_eq!(night.source, SuggestionSource::Shift { template_id: template });
    assert_eq!(
        (night.event.start.clone(), night.event.end.clone()),
        (at("2026-10-27", 22 * 60), at("2026-10-28", 2 * 60))
    );
    let shifts = d.core.calendars().unwrap().into_iter().find(|c| c.name == "알바").unwrap();
    assert_eq!(night.event.calendar_id, shifts.id);
    // Saving the chip as it is works: the sheet does exactly this on a tap.
    let saved = d.core.add_event(night.event.clone(), true).unwrap();
    assert_eq!(d.core.event(saved.event_id, None).unwrap().end, at("2026-10-28", 2 * 60));
}

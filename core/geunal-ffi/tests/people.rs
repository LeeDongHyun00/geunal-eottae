//! People and birthdays through `Core` (docs/design/m1.md screens 11 and 17, rust-core §6 "사람·생일"): adding,
//! editing and removing people, importing contacts, and the solar day a birthday falls on each year.

mod common;

use common::*;
use geunal_ffi::*;

#[test]
fn adds_a_lunar_birthday_and_shows_this_years_solar_day_in_the_calendar() {
    let d = device(); // Sunday 25 October 2026
    let before = *d.changes.0.lock().unwrap();
    let id = add_person(&d.core, "할머니", lunar(10, 3, false));
    assert!(*d.changes.0.lock().unwrap() > before, "the calendar shows birthdays, so it hears about people");
    let people = d.core.people().unwrap();
    assert_eq!(people.len(), 1);
    let p = &people[0];
    assert_eq!(
        (p.id.as_str(), p.name.as_str(), p.source.clone(), p.read_only),
        (id.as_str(), "할머니", PersonSource::Manual, false)
    );
    assert_eq!(p.birthday, Some(lunar(10, 3, false)));
    // The design's example: 음력 10월 3일 is Wednesday 11 November 2026 (screen 17).
    assert_eq!((p.next_birthday.as_deref(), p.days_until), (Some("2026-11-11"), Some(17)));
    assert_eq!(d.core.birthday_in_year(lunar(10, 3, false), 2026).unwrap().as_deref(), Some("2026-11-11"));

    let november = d.core.calendar_month("2026-11".into()).unwrap();
    assert_eq!(
        cell(&november, "2026-11-11").birthdays,
        vec![BirthdayItem { person_id: id, name: "할머니".into(), day: "2026-11-11".into(), lunar: true }]
    );
    assert_eq!(d.core.calendar_week("2026-11-08".into()).unwrap()[3].birthdays.len(), 1);
    // A day with only a birthday is listed.
    let agenda = d.core.calendar_agenda("2026-11-09".into(), 7).unwrap();
    assert_eq!(agenda.iter().map(|a| a.day.as_str()).collect::<Vec<_>>(), vec!["2026-11-11"]);
}

#[test]
fn edits_a_person_and_refuses_a_missing_name_or_an_impossible_birthday() {
    let d = device();
    let id = add_person(&d.core, "민수", solar(10, 30));
    let edit = |name: &str, birthday: Option<Birthday>| PersonInput {
        id: Some(id.clone()),
        name: name.into(),
        birthday,
        alert: AlertRule::Off,
        suggest: false,
    };
    let born = |year: i32, b: Birthday| Birthday { year: Some(year), ..b };
    for (name, birthday) in [
        ("  ", Some(solar(10, 30))),
        ("가".repeat(41).as_str(), Some(solar(10, 30))),
        ("민수", Some(solar(2, 30))),
        ("민수", Some(solar(4, 31))),
        ("민수", Some(solar(13, 1))),
        ("민수", Some(lunar(13, 1, false))),
        ("민수", Some(lunar(1, 31, false))),
        ("민수", Some(born(1899, solar(1, 1)))),
        ("민수", Some(born(2027, solar(1, 1)))),
        ("민수", Some(born(2023, solar(2, 29)))),
    ] {
        assert!(
            matches!(d.core.save_person(edit(name, birthday.clone())), Err(CoreError::Invalid { .. })),
            "{name:?} {birthday:?}"
        );
    }
    // 29 February and a lunar 30th exist in some years, so both are kept.
    d.core.save_person(edit("민수", Some(born(2000, solar(2, 29))))).unwrap();
    d.core.save_person(edit("민수", Some(lunar(1, 30, false)))).unwrap();

    d.core.save_person(edit(" 민수 ", None)).unwrap();
    let p = d.core.people().unwrap().remove(0);
    assert_eq!((p.name.as_str(), p.birthday, p.next_birthday, p.days_until), ("민수", None, None, None));
    assert_eq!((p.alert, p.suggest), (AlertRule::Off, false));
    let mut unknown = edit("민수", None);
    unknown.id = Some("nobody".into());
    assert!(matches!(d.core.save_person(unknown), Err(CoreError::NotFound { .. })));
}

#[test]
fn lists_upcoming_birthdays_soonest_first_from_today() {
    let d = device(); // Sunday 25 October 2026
    add_person(&d.core, "오늘", solar(10, 25));
    add_person(&d.core, "다음주", solar(11, 1));
    add_person(&d.core, "할머니", lunar(10, 3, false)); // 11 November
    add_person(&d.core, "어제", solar(10, 24));
    d.core
        .save_person(PersonInput {
            id: None,
            name: "지은".into(),
            birthday: None,
            alert: AlertRule::Inherit,
            suggest: true,
        })
        .unwrap();
    let upcoming = |days: u16| {
        d.core.upcoming_birthdays(days).unwrap().into_iter().map(|p| (p.name, p.days_until)).collect::<Vec<_>>()
    };
    assert_eq!(upcoming(7), vec![("오늘".to_owned(), Some(0)), ("다음주".to_owned(), Some(7))]);
    assert_eq!(upcoming(30).len(), 3);
    let people = d.core.people().unwrap();
    assert_eq!(
        people.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(),
        vec!["다음주", "어제", "오늘", "지은", "할머니"]
    );
    let yesterday = people.iter().find(|p| p.name == "어제").unwrap();
    assert_eq!((yesterday.next_birthday.as_deref(), yesterday.days_until), (Some("2027-10-24"), Some(364)));
}

#[test]
fn moves_a_february_29_birthday_and_a_missing_leap_month_or_30th_by_the_rules() {
    let d = device();
    assert_eq!(d.core.birthday_in_year(solar(2, 29), 2027).unwrap().as_deref(), Some("2027-02-28"));
    assert_eq!(d.core.birthday_in_year(solar(2, 29), 2028).unwrap().as_deref(), Some("2028-02-29"));

    // 2025 has a leap sixth month (윤6월) and 2026 does not: the birthday falls in the ordinary sixth month.
    let in_leap = d.core.lunar_to_solar(2025, 6, 10, true).unwrap();
    assert!(in_leap.is_some());
    assert_eq!(d.core.birthday_in_year(lunar(6, 10, true), 2025).unwrap(), in_leap);
    assert_eq!(d.core.lunar_to_solar(2026, 6, 10, true).unwrap(), None);
    assert_eq!(
        d.core.birthday_in_year(lunar(6, 10, true), 2026).unwrap(),
        d.core.lunar_to_solar(2026, 6, 10, false).unwrap()
    );

    // A 30th in a month of 29 days falls on the 29th.
    let short = (1..=12u8).find(|m| d.core.lunar_to_solar(2026, *m, 30, false).unwrap().is_none()).unwrap();
    assert_eq!(
        d.core.birthday_in_year(lunar(short, 30, false), 2026).unwrap(),
        d.core.lunar_to_solar(2026, short, 29, false).unwrap()
    );

    // Inside the table only.
    assert_eq!(d.core.lunar_to_solar(2026, 1, 1, false).unwrap().as_deref(), Some("2026-02-17"));
    assert_eq!(d.core.lunar_to_solar(1899, 1, 1, false).unwrap(), None);
    assert_eq!(d.core.lunar_to_solar(2050, 1, 1, false).unwrap(), None);
    assert_eq!(d.core.birthday_in_year(lunar(1, 1, false), 2050).unwrap(), None);
    assert!(matches!(d.core.birthday_in_year(solar(2, 30), 2026), Err(CoreError::Invalid { .. })));
    // The year someone was born does not matter for the day it falls on.
    let born_1604 = Birthday { year: Some(1604), ..solar(3, 14) };
    assert_eq!(d.core.birthday_in_year(born_1604, 2026).unwrap().as_deref(), Some("2026-03-14"));
}

#[test]
fn imports_contacts_with_a_birthday_once_and_updates_them_the_next_time() {
    let d = device();
    let contact = |id: &str, name: &str, birthday: Option<Birthday>| ContactInput {
        contact_id: id.into(),
        name: name.into(),
        birthday,
    };
    let first = d
        .core
        .import_contacts(vec![
            contact("c1", "김민수", Some(solar(10, 30))),
            contact("c2", "이지은", None),
            contact("c3", "박할머니", Some(lunar(10, 3, false))),
            contact("c4", "  ", Some(solar(1, 1))),
            // Some address books keep 1604 for a birthday saved without a year: the year goes, the contact stays.
            contact("c5", "최은지", Some(Birthday { year: Some(1604), ..solar(3, 14) })),
        ])
        .unwrap();
    assert_eq!(first, ContactImport { added: 3, updated: 0, skipped: 2 });
    let people = d.core.people().unwrap();
    assert!(people.iter().all(|p| p.source == PersonSource::Contacts && !p.read_only));
    assert_eq!(people.iter().find(|p| p.name == "최은지").unwrap().birthday, Some(solar(3, 14)));

    // The user turns 김민수's alert off; the next import changes the name and birthday and keeps that.
    let minsu = people.iter().find(|p| p.name == "김민수").unwrap().clone();
    d.core
        .save_person(PersonInput {
            id: Some(minsu.id.clone()),
            name: minsu.name.clone(),
            birthday: minsu.birthday.clone(),
            alert: AlertRule::Off,
            suggest: true,
        })
        .unwrap();
    let again = d.core.import_contacts(vec![contact("c1", "김민수 (회사)", Some(solar(10, 31)))]).unwrap();
    assert_eq!(again, ContactImport { added: 0, updated: 1, skipped: 0 });
    let people = d.core.people().unwrap();
    assert_eq!(people.len(), 3);
    let updated = people.iter().find(|p| p.id == minsu.id).unwrap();
    assert_eq!(
        (updated.name.as_str(), updated.birthday.clone(), updated.alert.clone(), updated.source.clone()),
        ("김민수 (회사)", Some(solar(10, 31)), AlertRule::Off, PersonSource::Contacts)
    );
}

#[test]
fn deletes_a_person_and_their_birthday_with_them() {
    let d = device();
    let id = add_person(&d.core, "민수", solar(10, 30));
    d.core.delete_person(id.clone()).unwrap();
    assert!(d.core.people().unwrap().is_empty());
    assert!(cell(&d.core.calendar_month("2026-10".into()).unwrap(), "2026-10-30").birthdays.is_empty());
    assert!(matches!(d.core.delete_person(id), Err(CoreError::NotFound { .. })));
}

//! The M1 calendar surface through `Core`, as the apps call it: calendars, events and their repeat edits, the month,
//! week and list views, shift stamping, the device mirror, alerts, the widget snapshot and the ICS export
//! (docs/design/m1.md, rust-core §9 "캘린더와 추천").

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use chrono::{TimeZone, Utc};
use geunal_ffi::*;

#[derive(Default)]
struct MemorySecrets(Mutex<HashMap<String, Vec<u8>>>);

impl SecretStore for MemorySecrets {
    fn get(&self, name: String) -> Option<Vec<u8>> {
        self.0.lock().unwrap().get(&name).cloned()
    }
    fn put(&self, name: String, value: Vec<u8>) {
        self.0.lock().unwrap().insert(name, value);
    }
    fn delete(&self, name: String) {
        self.0.lock().unwrap().remove(&name);
    }
}

#[derive(Default)]
struct Changes(Mutex<u32>);

impl CoreListener for Changes {
    fn on_change(&self, change: Change) {
        if change == Change::Calendar {
            *self.0.lock().unwrap() += 1;
        }
    }
}

struct Device {
    core: Arc<Core>,
    changes: Arc<Changes>,
    _dir: tempfile::TempDir,
}

/// A core on a fresh store whose clock reads Sunday 2026-10-25 09:00 in Korea.
fn device() -> Device {
    let dir = tempfile::tempdir().unwrap();
    let changes = Arc::new(Changes::default());
    let core = Core::open(
        dir.path().to_str().unwrap().to_owned(),
        "http://127.0.0.1:9".to_owned(),
        changes.clone(),
        Arc::new(MemorySecrets::default()),
    )
    .unwrap();
    core.set_clock(Some(Utc.with_ymd_and_hms(2026, 10, 25, 0, 0, 0).unwrap()));
    Device { core, changes, _dir: dir }
}

fn at(day: &str, minute: u16) -> DayTime {
    DayTime { day: day.into(), minute }
}

fn timed(calendar: &str, title: &str, day: &str, from: u16, to: u16) -> EventInput {
    EventInput {
        calendar_id: calendar.into(),
        title: title.into(),
        all_day: false,
        start: at(day, from),
        end: at(day, to),
        recurrence: None,
        alert: AlertRule::Inherit,
        note: None,
    }
}

fn weekly(days: Vec<Weekday>) -> Recurrence {
    Recurrence { frequency: Frequency::Weekly, interval: 1, weekdays: days, until: None, count: None }
}

fn personal(core: &Core) -> String {
    core.calendars().unwrap().into_iter().find(|c| c.name == "개인").unwrap().id
}

fn titles(events: &[EventItem]) -> Vec<String> {
    events.iter().map(|e| e.title.clone()).collect()
}

fn cell<'a>(month: &'a [DayCell], day: &str) -> &'a DayCell {
    month.iter().find(|c| c.day == day).unwrap()
}

#[test]
fn makes_a_default_calendar_on_first_open_and_keeps_the_last_one() {
    let d = device();
    let calendars = d.core.calendars().unwrap();
    assert_eq!(calendars.len(), 1);
    assert_eq!((calendars[0].name.as_str(), calendars[0].color), ("개인", Some(Palette::Tangerine)));
    assert!(matches!(d.core.delete_calendar(calendars[0].id.clone()), Err(CoreError::Invalid { .. })));

    let school = d
        .core
        .save_calendar(CalendarInput {
            id: None,
            name: " 학교 ".into(),
            color: Palette::Sea,
            soft: false,
            default_alert: AlertRule::Inherit,
        })
        .unwrap();
    d.core.add_event(timed(&school, "전공 수업", "2026-10-26", 600, 720), false).unwrap();
    let names: Vec<String> = d.core.calendars().unwrap().into_iter().map(|c| c.name).collect();
    assert_eq!(names, vec!["개인", "학교"]);
    assert_eq!(d.core.calendars().unwrap()[1].event_count, 1);
    // A calendar goes with its events.
    d.core.delete_calendar(school).unwrap();
    assert!(d.core.calendar_agenda("2026-10-25".into(), 7).unwrap().is_empty());
    assert!(matches!(
        d.core.save_calendar(CalendarInput {
            id: None,
            name: "  ".into(),
            color: Palette::Sea,
            soft: false,
            default_alert: AlertRule::Inherit
        }),
        Err(CoreError::Invalid { .. })
    ));
}

#[test]
fn shows_an_added_event_in_the_month_week_and_list_views() {
    let d = device();
    let cal = personal(&d.core);
    let before = *d.changes.0.lock().unwrap();
    d.core.add_event(timed(&cal, "카페 마감", "2026-10-30", 17 * 60, 22 * 60), false).unwrap();
    let mut trip = timed(&cal, "제주 여행", "2026-10-31", 0, 0);
    trip.all_day = true;
    trip.end = at("2026-11-02", 0);
    d.core.add_event(trip, false).unwrap();
    assert!(*d.changes.0.lock().unwrap() > before, "the app hears about the change");

    let month = d.core.calendar_month("2026-10".into()).unwrap();
    // October 2026 starts on a Thursday: the grid starts on Sunday 27 September and ends on Saturday 31 October.
    assert_eq!((month.first().unwrap().day.as_str(), month.last().unwrap().day.as_str()), ("2026-09-27", "2026-10-31"));
    assert_eq!(month.len(), 35);
    assert!(!cell(&month, "2026-09-27").in_month);
    assert_eq!(titles(&cell(&month, "2026-10-30").events), vec!["카페 마감"]);
    assert_eq!(cell(&month, "2026-10-31").weekday, Weekday::Saturday);
    assert_eq!(titles(&cell(&month, "2026-10-31").events), vec!["제주 여행"]);
    let november = d.core.calendar_month("2026-11".into()).unwrap();
    assert_eq!(
        titles(&cell(&november, "2026-11-02").events),
        vec!["제주 여행"],
        "an all-day event runs through its last day"
    );
    assert!(cell(&november, "2026-11-03").events.is_empty());

    let week = d.core.calendar_week("2026-10-25".into()).unwrap();
    let friday = &week[5];
    assert_eq!(friday.day, "2026-10-30");
    assert_eq!((friday.timed[0].start_minute, friday.timed[0].end_minute), (1020, 1320));
    assert_eq!(titles(&week[6].all_day), vec!["제주 여행"]);

    let agenda = d.core.calendar_agenda("2026-10-25".into(), 28).unwrap();
    let days: Vec<&str> = agenda.iter().map(|a| a.day.as_str()).collect();
    assert_eq!(days, vec!["2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02"]);
    assert!(matches!(d.core.calendar_month("2026/10".into()), Err(CoreError::Invalid { .. })));
}

#[test]
fn refuses_an_event_without_a_title_or_ending_before_it_starts() {
    let d = device();
    let cal = personal(&d.core);
    assert!(matches!(
        d.core.add_event(timed(&cal, " ", "2026-10-30", 600, 660), false),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        d.core.add_event(timed(&cal, "x", "2026-10-30", 660, 600), false),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        d.core.add_event(timed("nope", "x", "2026-10-30", 600, 660), false),
        Err(CoreError::NotFound { .. })
    ));
}

#[test]
fn offers_to_make_a_title_recurring_after_three_same_weekday_uses() {
    let d = device();
    let cal = personal(&d.core);
    let add = |day: &str| d.core.add_event(timed(&cal, "카페 마감", day, 1020, 1320), true).unwrap().repeat_hint;
    assert_eq!(add("2026-10-30"), None);
    assert_eq!(add("2026-11-06"), None);
    assert_eq!(add("2026-11-13"), Some(RepeatHint { weekday: Weekday::Friday, times: 3 }));
    // Asked once only.
    assert_eq!(add("2026-11-20"), None);
}

#[test]
fn takes_back_what_an_undone_add_counted() {
    let d = device();
    let cal = personal(&d.core);
    let add = |day: &str| d.core.add_event(timed(&cal, "헬스", day, 1140, 1200), true).unwrap();
    add("2026-10-26");
    add("2026-11-02");
    // The third add is undone at once: it neither brings the hint later nor stays in the counts.
    let third = add("2026-11-09");
    assert!(third.repeat_hint.is_some());
    d.core.delete_event(third.event_id, None, EditScope::ThisAndFollowing).unwrap();
    assert!(d.core.calendar_agenda("2026-11-09".into(), 1).unwrap().is_empty());
    // The next third use is the real third one and asks again.
    assert_eq!(add("2026-11-16").repeat_hint, Some(RepeatHint { weekday: Weekday::Monday, times: 3 }));
    // An event deleted later is not an undo: the counts stay.
    let fourth = add("2026-11-23");
    d.core.set_clock(Some(Utc.with_ymd_and_hms(2026, 10, 25, 0, 5, 0).unwrap()));
    d.core.delete_event(fourth.event_id, None, EditScope::ThisAndFollowing).unwrap();
    assert_eq!(add("2026-11-30").repeat_hint, None);
}

#[test]
fn edits_one_occurrence_of_a_weekly_event_without_touching_the_others() {
    let d = device();
    let cal = personal(&d.core);
    let mut class = timed(&cal, "전공 수업", "2026-10-26", 600, 720);
    class.recurrence = Some(weekly(vec![Weekday::Monday, Weekday::Wednesday]));
    let series = d.core.add_event(class.clone(), false).unwrap().event_id;

    let week = d.core.calendar_week("2026-10-25".into()).unwrap();
    let wednesday = &week[3].timed[0].event;
    assert_eq!(wednesday.occurrence.as_deref(), Some("2026-10-28T10:00"));
    assert_eq!(wednesday.recurrence, Some(weekly(vec![Weekday::Monday, Weekday::Wednesday])));

    // "이 일정만": the Wednesday moves to 13:00 and becomes an event of its own.
    let mut moved = class.clone();
    moved.start = at("2026-10-28", 780);
    moved.end = at("2026-10-28", 900);
    moved.title = "보강".into();
    let own = d.core.update_event(series.clone(), wednesday.occurrence.clone(), EditScope::ThisOnly, moved).unwrap();
    assert_ne!(own, series);
    let week = d.core.calendar_week("2026-10-25".into()).unwrap();
    assert_eq!(titles(&week[1].timed.iter().map(|b| b.event.clone()).collect::<Vec<_>>()), vec!["전공 수업"]);
    assert_eq!(week[3].timed.len(), 1);
    assert_eq!((week[3].timed[0].event.title.as_str(), week[3].timed[0].start_minute), ("보강", 780));
    assert_eq!(week[3].timed[0].event.occurrence, None);
    let next = d.core.calendar_week("2026-11-01".into()).unwrap();
    assert_eq!(next[3].timed[0].event.title, "전공 수업", "the next Wednesday keeps the series");

    // "이 일정만" delete of the next Monday leaves the rest.
    let monday = next[1].timed[0].event.clone();
    d.core.delete_event(series.clone(), monday.occurrence, EditScope::ThisOnly).unwrap();
    let next = d.core.calendar_week("2026-11-01".into()).unwrap();
    assert!(next[1].timed.is_empty());
    assert_eq!(next[3].timed.len(), 1);
}

#[test]
fn splits_a_weekly_series_when_this_and_following_changes() {
    let d = device();
    let cal = personal(&d.core);
    let mut class = timed(&cal, "교양 수업", "2026-10-27", 780, 900);
    class.recurrence = Some(weekly(vec![Weekday::Tuesday]));
    let series = d.core.add_event(class.clone(), false).unwrap().event_id;
    let third = d.core.calendar_week("2026-11-08".into()).unwrap()[2].timed[0].event.clone();
    assert_eq!(third.occurrence.as_deref(), Some("2026-11-10T13:00"));

    // From the third Tuesday on, the class is at 14:00.
    let mut later = class.clone();
    later.start = at("2026-11-10", 840);
    later.end = at("2026-11-10", 960);
    let following =
        d.core.update_event(series.clone(), third.occurrence.clone(), EditScope::ThisAndFollowing, later).unwrap();
    assert_ne!(following, series);
    let starts = |week: &str| d.core.calendar_week(week.into()).unwrap()[2].timed[0].start_minute;
    assert_eq!((starts("2026-10-25"), starts("2026-11-01")), (780, 780));
    assert_eq!((starts("2026-11-08"), starts("2026-11-15")), (840, 840));
    let old = d.core.event(series.clone(), None).unwrap();
    assert_eq!(old.recurrence.unwrap().until.as_deref(), Some("2026-11-09"));

    // Deleting "이후 모두" from the fourth Tuesday ends the new series the day before.
    let fourth = d.core.calendar_week("2026-11-15".into()).unwrap()[2].timed[0].event.clone();
    d.core.delete_event(following.clone(), fourth.occurrence, EditScope::ThisAndFollowing).unwrap();
    assert!(d.core.calendar_week("2026-11-15".into()).unwrap()[2].timed.is_empty());
    assert_eq!(d.core.calendar_week("2026-11-08".into()).unwrap()[2].timed.len(), 1);
    // From the first occurrence, "이후 모두" is the whole series.
    d.core.delete_event(series, Some("2026-10-27T13:00".into()), EditScope::ThisAndFollowing).unwrap();
    assert!(d.core.calendar_week("2026-10-25".into()).unwrap()[2].timed.is_empty());
}

#[test]
fn keeps_hidden_calendars_out_of_the_views() {
    let d = device();
    let cal = personal(&d.core);
    d.core.add_event(timed(&cal, "치과", "2026-10-29", 600, 660), false).unwrap();
    d.core.set_calendar_visible(cal.clone(), false).unwrap();
    assert!(d.core.calendar_agenda("2026-10-25".into(), 7).unwrap().is_empty());
    assert!(!d.core.calendars().unwrap()[0].visible);
    d.core.set_calendar_visible(cal, true).unwrap();
    assert_eq!(d.core.calendar_agenda("2026-10-25".into(), 7).unwrap().len(), 1);
}

#[test]
fn lays_overlapping_events_side_by_side_in_the_week_view() {
    let d = device();
    let cal = personal(&d.core);
    for (title, from, to) in [("a", 600, 720), ("b", 660, 780), ("c", 690, 750), ("d", 700, 710)] {
        d.core.add_event(timed(&cal, title, "2026-10-26", from, to), false).unwrap();
    }
    let monday = &d.core.calendar_week("2026-10-25".into()).unwrap()[1];
    assert_eq!(monday.timed.len(), 3);
    assert_eq!(monday.hidden, 1);
    assert!(monday.timed.iter().all(|b| b.columns == 3));
}

#[test]
fn stamps_shift_templates_and_applies_the_stamp_screen_on_finish() {
    let d = device();
    let night = d
        .core
        .save_shift_template(ShiftTemplateInput {
            id: None,
            name: "마감".into(),
            start_minute: 22 * 60,
            end_minute: 2 * 60,
            calendar_id: None,
            alert: AlertRule::Before { minutes: 30 },
        })
        .unwrap();
    let open = d
        .core
        .save_shift_template(ShiftTemplateInput {
            id: None,
            name: "오픈".into(),
            start_minute: 8 * 60,
            end_minute: 13 * 60,
            calendar_id: None,
            alert: AlertRule::Inherit,
        })
        .unwrap();
    // Without a calendar named, shifts go to "알바", made the first time.
    let calendars = d.core.calendars().unwrap();
    let alba = calendars.iter().find(|c| c.name == "알바").unwrap();
    assert_eq!((alba.color, alba.shift_template_count), (Some(Palette::Mint), 2));
    let templates = d.core.shift_templates().unwrap();
    assert_eq!(templates[0].length_minutes, 240, "22:00–02:00 is four hours past midnight");
    assert_ne!(templates[0].tint, templates[1].tint);

    let stamp = |day: &str, ids: Vec<String>| ShiftDay { day: day.into(), template_ids: ids };
    d.core
        .set_shift_days(vec![
            stamp("2026-11-06", vec![night.clone()]),
            stamp("2026-11-07", vec![open.clone()]),
            stamp("2026-11-08", vec![open.clone()]),
        ])
        .unwrap();
    assert_eq!(
        d.core.shift_days("2026-11".into()).unwrap(),
        vec![
            stamp("2026-11-06", vec![night.clone()]),
            stamp("2026-11-07", vec![open.clone()]),
            stamp("2026-11-08", vec![open.clone()])
        ]
    );
    // A night shift is one event into the next morning: two blocks in the week view.
    let week = d.core.calendar_week("2026-11-01".into()).unwrap();
    assert_eq!((week[5].timed[0].start_minute, week[5].timed[0].end_minute), (1320, 1440));
    assert_eq!((week[6].timed[0].event.title.as_str(), week[6].timed[0].end_minute), ("마감", 120));
    assert!(matches!(week[5].timed[0].event.source, EventSource::Shift { .. }));

    // Finishing again: Saturday changes template, Sunday is cleared, Friday is left alone.
    d.core.set_shift_days(vec![stamp("2026-11-07", vec![night.clone()]), stamp("2026-11-08", vec![])]).unwrap();
    assert_eq!(
        d.core.shift_days("2026-11".into()).unwrap(),
        vec![stamp("2026-11-06", vec![night.clone()]), stamp("2026-11-07", vec![night.clone()])]
    );
    // Deleting the template keeps its shifts as ordinary events.
    d.core.delete_shift_template(night).unwrap();
    assert!(d.core.shift_days("2026-11".into()).unwrap().is_empty());
    let week = d.core.calendar_week("2026-11-01".into()).unwrap();
    assert_eq!(week[5].timed[0].event.source, EventSource::Own);
    assert!(matches!(
        d.core.set_shift_days(vec![stamp("2026-11-09", vec!["gone".into()])]),
        Err(CoreError::NotFound { .. })
    ));
}

#[test]
fn mirrors_device_calendars_read_only_and_replaces_them_on_the_next_mirror() {
    let d = device();
    let event = |id: &str, title: &str, day: &str| DeviceEventInput {
        device_event_id: id.into(),
        title: title.into(),
        all_day: false,
        start: at(day, 960),
        end: at(day, 1020),
    };
    let summary = d
        .core
        .mirror_device_calendars(vec![DeviceCalendarInput {
            source_id: "icloud-1".into(),
            name: "iCloud · 개인".into(),
            events: vec![event("e1", "팀플 회의", "2026-10-29"), event("e2", "이상한 일정", "2026-10-30")],
        }])
        .unwrap();
    assert_eq!(summary, DeviceMirror { calendars: 1, events: 2 });
    let calendars = d.core.calendars().unwrap();
    let device = calendars.iter().find(|c| c.kind == CalendarKind::Device).unwrap();
    assert_eq!((device.color, device.event_count), (None, 2));
    let item = d.core.calendar_agenda("2026-10-29".into(), 1).unwrap()[0].events[0].clone();
    assert_eq!((item.source.clone(), item.color), (EventSource::Device, None));
    let detail = d.core.event(item.event_id.clone(), None).unwrap();
    assert!(detail.read_only);
    assert!(matches!(d.core.delete_event(item.event_id, None, EditScope::ThisOnly), Err(CoreError::Invalid { .. })));
    assert!(matches!(d.core.delete_calendar(device.id.clone()), Err(CoreError::Invalid { .. })));

    // The next mirror replaces the events; an empty one turns the mirror off.
    d.core
        .mirror_device_calendars(vec![DeviceCalendarInput {
            source_id: "icloud-1".into(),
            name: "iCloud · 개인".into(),
            events: vec![event("e1", "팀플 회의", "2026-10-29")],
        }])
        .unwrap();
    assert_eq!(d.core.calendars().unwrap().iter().find(|c| c.kind == CalendarKind::Device).unwrap().event_count, 1);
    d.core.mirror_device_calendars(vec![]).unwrap();
    assert!(d.core.calendars().unwrap().iter().all(|c| c.kind == CalendarKind::Own));
    assert!(d.core.calendar_agenda("2026-10-29".into(), 1).unwrap().is_empty());
}

#[test]
fn schedules_alerts_from_the_event_then_its_calendar_then_the_app_setting() {
    let d = device();
    let cal = personal(&d.core);
    let gym = d
        .core
        .save_calendar(CalendarInput {
            id: None,
            name: "운동".into(),
            color: Palette::Olive,
            soft: true,
            default_alert: AlertRule::Before { minutes: 30 },
        })
        .unwrap();
    // The app default is ten minutes; the gym calendar says thirty; one event says an hour; one says nothing.
    d.core.add_event(timed(&cal, "치과", "2026-10-26", 600, 660), false).unwrap();
    d.core.add_event(timed(&gym, "헬스", "2026-10-26", 1140, 1200), false).unwrap();
    let mut meeting = timed(&cal, "팀 회의", "2026-10-27", 600, 660);
    meeting.alert = AlertRule::Before { minutes: 60 };
    d.core.add_event(meeting, false).unwrap();
    let mut quiet = timed(&cal, "산책", "2026-10-27", 1200, 1260);
    quiet.alert = AlertRule::Off;
    d.core.add_event(quiet, false).unwrap();
    let mut leave = timed(&cal, "연차", "2026-10-28", 0, 0);
    leave.all_day = true;
    leave.end = at("2026-10-28", 0);
    d.core.add_event(leave, false).unwrap();

    let alerts = d.core.scheduled_alerts(64).unwrap();
    let fired: Vec<(String, chrono::DateTime<Utc>)> =
        alerts.iter().map(|a| (a.title.clone(), chrono::DateTime::<Utc>::from(a.fire_at))).collect();
    let kst = |d: u32, h: u32, m: u32| geunal_domain::KST.with_ymd_and_hms(2026, 10, d, h, m, 0).unwrap().to_utc();
    assert_eq!(
        fired,
        vec![
            ("치과".to_owned(), kst(26, 9, 50)),
            ("헬스".to_owned(), kst(26, 18, 30)),
            ("팀 회의".to_owned(), kst(27, 9, 0)),
            // An all-day event takes the all-day setting, "전날 18:00".
            ("연차".to_owned(), kst(27, 18, 0)),
        ]
    );
    assert_eq!(d.core.event(alerts[1].target_event(), None).unwrap().effective_alert, Some(30));

    // Turning the all-day alert off drops it; the limit keeps the nearest.
    let mut settings = d.core.notification_settings().unwrap();
    assert_eq!((settings.event_alert, settings.all_day_alert), (Some(10), Some(360)));
    settings.all_day_alert = None;
    d.core.set_notification_settings(settings).unwrap();
    assert_eq!(d.core.scheduled_alerts(64).unwrap().len(), 3);
    assert_eq!(d.core.scheduled_alerts(1).unwrap()[0].title, "치과");
}

trait TargetEvent {
    fn target_event(&self) -> String;
}

impl TargetEvent for ScheduledAlert {
    fn target_event(&self) -> String {
        match &self.target {
            AlertTarget::Event { event_id, .. } => event_id.clone(),
            other => panic!("not an event alert: {other:?}"),
        }
    }
}

#[test]
fn lists_what_is_on_now_or_next_and_writes_the_widget_snapshot() {
    let d = device();
    let cal = personal(&d.core);
    // The clock reads 09:00 on Sunday the 25th.
    d.core.add_event(timed(&cal, "아침 산책", "2026-10-25", 420, 480), false).unwrap(); // already over
    d.core.add_event(timed(&cal, "브런치", "2026-10-25", 510, 600), false).unwrap(); // on now
    d.core.add_event(timed(&cal, "가족 · 저녁", "2026-10-25", 1080, 1200), false).unwrap();
    d.core.add_event(timed(&cal, "전공 수업", "2026-10-26", 600, 720), false).unwrap();

    let next: Vec<String> = d
        .core
        .next_up(2)
        .unwrap()
        .into_iter()
        .map(|n| match n {
            UpNext::Event { item, .. } => item.title,
            UpNext::Birthday { item } => item.name,
        })
        .collect();
    assert_eq!(next, vec!["브런치", "가족 · 저녁"]);

    let snapshot: serde_json::Value = serde_json::from_str(&d.core.widget_snapshot().unwrap()).unwrap();
    assert_eq!(snapshot["v"], 1);
    assert_eq!(snapshot["day"], "2026-10-25");
    assert_eq!(snapshot["today"].as_array().unwrap().len(), 3);
    assert_eq!(snapshot["today"][2]["title"], "가족 · 저녁");
    assert_eq!(snapshot["today"][2]["color"], "tangerine");
    assert_eq!(
        (snapshot["today"][2]["start"].as_str(), snapshot["today"][2]["end"].as_str()),
        (Some("18:00"), Some("20:00"))
    );
    assert_eq!(snapshot["tomorrow"][0]["title"], "전공 수업");
    assert!(snapshot["next_appointment"].is_null());
}

#[test]
fn exports_own_events_as_ics_and_leaves_device_events_out() {
    let d = device();
    let cal = personal(&d.core);
    let mut class = timed(&cal, "전공 수업", "2026-10-26", 600, 720);
    class.recurrence = Some(weekly(vec![Weekday::Monday]));
    d.core.add_event(class, false).unwrap();
    d.core
        .mirror_device_calendars(vec![DeviceCalendarInput {
            source_id: "g".into(),
            name: "Google · 회사".into(),
            events: vec![DeviceEventInput {
                device_event_id: "x".into(),
                title: "회사 일정".into(),
                all_day: false,
                start: at("2026-10-27", 600),
                end: at("2026-10-27", 660),
            }],
        }])
        .unwrap();
    let ics = d.core.export_ics().unwrap();
    assert!(ics.contains("SUMMARY:전공 수업\r\n"));
    assert!(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n"));
    assert!(!ics.contains("회사 일정"));
}

//! What `sync()` does once a day beside the groups (protocol §14): this year's and next year's public holidays from
//! the server, and the alpha counts of a tester who agreed, against the in-process fake server.

mod phones;

use chrono::{DateTime, Duration, TimeZone, Utc};
use geunal_ffi::{AlertRule, Core, CoreError, DayTime, EventInput};
use geunal_sync::api::{AlphaDay, HolidayItem};
use geunal_sync::fake::FakeServer;
use phones::{kakao, phone, user_id};

/// 10:00 on Thursday 29 October 2026 in Korea.
fn morning() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 10, 29, 1, 0, 0).unwrap()
}

/// Moves the phone's clock and the server's together.
fn set_time(server: &FakeServer, core: &Core, at: DateTime<Utc>) {
    server.set_now(at);
    core.set_clock(Some(at));
}

fn holiday(day: &str, name: &str) -> HolidayItem {
    HolidayItem { day: day.into(), name: name.into(), substitute: false }
}

/// The holidays a month view shows, day and name.
fn holidays(core: &Core, month: &str) -> Vec<(String, String)> {
    let cells = core.calendar_month(month.into()).unwrap().into_iter().filter(|c| c.in_month);
    cells.flat_map(|c| c.holidays).map(|h| (h.day, h.name)).collect()
}

fn pairs(list: &[(&str, &str)]) -> Vec<(String, String)> {
    list.iter().map(|(day, name)| ((*day).to_owned(), (*name).to_owned())).collect()
}

fn counts(day: &str, sessions: u32, events: u32, suggested: u32, crashes: u32) -> AlphaDay {
    AlphaDay { day: day.into(), sessions, events, suggested, crashes }
}

#[test]
fn holidays_come_once_a_day_and_replace_only_the_years_the_server_knows_in_full() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        set_time(&server, &a.core, morning());
        // 2026 as the server has it, with a temporary holiday on Friday the 30th; 2027 is not announced yet.
        server.set_holidays(2026, vec![holiday("2026-10-09", "한글날"), holiday("2026-10-30", "임시공휴일")]);
        kakao(&a.core, "iris", "아이리스").await.unwrap();

        a.core.clone().sync().await.unwrap();
        assert_eq!(server.holiday_reads(), 1);
        // 2026 is the server's whole year: the built-in 개천절 goes with the rest of that year, the new one comes in.
        assert_eq!(holidays(&a.core, "2026-10"), pairs(&[("2026-10-09", "한글날"), ("2026-10-30", "임시공휴일")]));
        assert_eq!(holidays(&a.core, "2027-01"), pairs(&[("2027-01-01", "1월 1일")]), "2027 stays as built in");

        a.core.clone().sync().await.unwrap();
        assert_eq!(server.holiday_reads(), 1, "once a day");

        // The next day the server knows 2027, but one of its days is no date: nothing of that answer is taken.
        set_time(&server, &a.core, morning() + Duration::days(1));
        server.set_holidays(2027, vec![holiday("2027-01-01", "1월 1일"), holiday("2027-02-30", "설날")]);
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.holiday_reads(), 2);
        assert_eq!(holidays(&a.core, "2027-02").len(), 4, "the built-in 설날 and 대체공휴일 stay");
        assert_eq!(holidays(&a.core, "2026-10").len(), 2);
    });
}

#[test]
fn alpha_counts_go_once_a_day_from_the_day_the_tester_agreed() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        set_time(&server, &a.core, morning());
        let user = user_id(&kakao(&a.core, "jun", "준").await.unwrap());

        // Off until the tester agrees: nothing is kept or sent.
        assert!(!a.core.alpha_stats_enabled().unwrap());
        a.core.record_app_foreground().unwrap();
        a.core.clone().sync().await.unwrap();
        assert!(server.alpha_of(&user).is_empty());

        a.core.set_alpha_stats(true).unwrap();
        assert!(a.core.alpha_stats_enabled().unwrap());
        a.core.record_app_foreground().unwrap();
        let calendar = a.core.calendars().unwrap().remove(0).id;
        let at = |minute| DayTime { day: "2026-10-30".into(), minute };
        let close = EventInput {
            calendar_id: calendar,
            title: "카페 마감".into(),
            all_day: false,
            start: at(17 * 60),
            end: at(22 * 60),
            recurrence: None,
            alert: AlertRule::Inherit,
            note: None,
        };
        a.core.add_event(close, true).unwrap();
        // A day before the agreement never goes, whatever is learned about it.
        a.core.record_crashes("2026-10-28".into(), 1).unwrap();
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.alpha_of(&user), vec![counts("2026-10-29", 1, 1, 1, 0)]);

        // Once a day: what happens later today goes tomorrow.
        a.core.record_app_foreground().unwrap();
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.alpha_of(&user)[0].sessions, 1);

        // The next day yesterday goes again with its final counts and the crash learned today. A request the network
        // dropped is not the day's one: the next sync sends it.
        set_time(&server, &a.core, morning() + Duration::days(1));
        a.core.record_app_foreground().unwrap();
        a.core.record_crashes("2026-10-29".into(), 1).unwrap();
        server.fail_next("post_alpha_stats");
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.alpha_of(&user), vec![counts("2026-10-29", 1, 1, 1, 0)]);
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.alpha_of(&user), vec![counts("2026-10-29", 2, 1, 1, 1), counts("2026-10-30", 1, 0, 0, 0)]);
        assert!(matches!(a.core.record_crashes("어제".into(), 1), Err(CoreError::Invalid { .. })));

        // Off drops what this device counted: agreeing again starts from nothing.
        a.core.record_app_foreground().unwrap();
        a.core.set_alpha_stats(false).unwrap();
        a.core.set_alpha_stats(true).unwrap();
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.alpha_of(&user)[1], counts("2026-10-30", 1, 0, 0, 0));
    });
}

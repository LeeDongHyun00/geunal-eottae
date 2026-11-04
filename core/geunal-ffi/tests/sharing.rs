//! A member's availability as the core makes it from the calendars and appointments on the device (rust-core §5
//! "가용 시간 올리기", §9).

mod phones;

use std::sync::Arc;

use chrono::{TimeZone, Utc};
use geunal_domain::availability::is_free;
use geunal_ffi::sharing::GroupSharing;
use geunal_ffi::*;
use geunal_sync::fake::FakeServer;
use phones::{Phone, kakao, phone};

/// A signed-in phone in a group of its own, on Monday 2026-11-02 09:00 in Korea.
async fn member(server: &FakeServer) -> (Phone, String) {
    let p = phone(server);
    p.core.set_clock(Some(Utc.with_ymd_and_hms(2026, 11, 2, 0, 0, 0).unwrap()));
    kakao(&p.core, "a", "하린").await.unwrap();
    let group = p.core.clone().create_group(Some("모임".into())).await.unwrap();
    (p, group)
}

fn calendar(core: &Arc<Core>, name: &str, soft: bool) -> String {
    core.save_calendar(CalendarInput {
        id: None,
        name: name.into(),
        color: Palette::Sea,
        soft,
        default_alert: AlertRule::Inherit,
    })
    .unwrap()
}

fn event(core: &Arc<Core>, calendar: &str, day: &str, from: u16, to: u16) {
    let input = EventInput {
        calendar_id: calendar.into(),
        title: "일정".into(),
        all_day: false,
        start: DayTime { day: day.into(), minute: from },
        end: DayTime { day: day.into(), minute: to },
        recurrence: None,
        alert: AlertRule::Inherit,
        note: None,
    };
    core.add_event(input, false).unwrap();
}

/// This member's bitmap as the group holds it.
fn mine(core: &Arc<Core>, group: &str) -> MemberAvailability {
    let me = core.session().unwrap().user_id;
    core.group_availability(group.into()).unwrap().into_iter().find(|a| a.user_id == me).expect("shared")
}

/// The slot of `minute` on the `day`-th day of the window (0 = today).
fn slot(day: usize, minute: usize) -> usize {
    day * 48 + minute / 30
}

#[test]
fn leaves_calendars_flagged_blocks_free_0_out_of_the_group_bitmap() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, group) = member(&server).await;
        let school = calendar(&a.core, "학교", false);
        let maybe = calendar(&a.core, "어쩌면", true);
        event(&a.core, &school, "2026-11-03", 10 * 60, 12 * 60);
        event(&a.core, &maybe, "2026-11-03", 14 * 60, 16 * 60);
        a.core.clone().sync().await.unwrap();

        let shared = mine(&a.core, &group);
        assert_eq!((shared.window_start.as_str(), shared.days), ("2026-11-02", 56));
        assert!(!is_free(&shared.bits, slot(1, 10 * 60)) && !is_free(&shared.bits, slot(1, 11 * 60 + 30)));
        assert!(is_free(&shared.bits, slot(1, 12 * 60)));
        assert!(is_free(&shared.bits, slot(1, 14 * 60)), "a soft calendar's time is free to the group");
    });
}

#[test]
fn counts_timed_appointments_as_busy_and_untimed_ones_as_free() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, group) = member(&server).await;
        let timed = |day: &str, start, end| {
            a.core.clone().request_date(group.clone(), day.into(), Mode::Dictator, start, end, None, None, false)
        };
        timed("2026-11-04", Some(19 * 60), Some(21 * 60)).await.unwrap();
        timed("2026-11-05", None, None).await.unwrap();
        a.core.clone().sync().await.unwrap();

        let shared = mine(&a.core, &group);
        assert!(!is_free(&shared.bits, slot(2, 19 * 60)) && !is_free(&shared.bits, slot(2, 20 * 60 + 30)));
        assert!(is_free(&shared.bits, slot(2, 21 * 60)));
        assert!((0..48).all(|s| is_free(&shared.bits, slot(3, s * 30))), "a day without a time blocks nothing");
    });
}

#[test]
fn shares_the_chosen_weeks_and_calendars_only() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, group) = member(&server).await;
        let school = calendar(&a.core, "학교", false);
        let work = calendar(&a.core, "알바", false);
        event(&a.core, &school, "2026-11-03", 10 * 60, 12 * 60);
        event(&a.core, &work, "2026-11-03", 18 * 60, 22 * 60);
        assert_eq!(a.core.group_sharing(group.clone()).unwrap(), GroupSharing { weeks: 8, calendar_ids: None });

        let choice = GroupSharing { weeks: 2, calendar_ids: Some(vec![work.clone()]) };
        a.core.clone().set_group_sharing(group.clone(), choice.clone()).await.unwrap();
        assert_eq!(a.core.group_sharing(group.clone()).unwrap(), choice);
        let shared = mine(&a.core, &group);
        assert_eq!(shared.days, 14);
        assert!(is_free(&shared.bits, slot(1, 10 * 60)), "school is left out for this group");
        assert!(!is_free(&shared.bits, slot(1, 18 * 60)));

        let too_long = GroupSharing { weeks: 14, calendar_ids: None };
        assert!(matches!(
            a.core.clone().set_group_sharing(group.clone(), too_long).await,
            Err(CoreError::Invalid { .. })
        ));
    });
}

#[test]
fn moves_the_window_with_the_day() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, group) = member(&server).await;
        a.core.clone().sync().await.unwrap();
        assert_eq!(mine(&a.core, &group).window_start, "2026-11-02");
        a.core.set_clock(Some(Utc.with_ymd_and_hms(2026, 11, 3, 0, 0, 0).unwrap()));
        a.core.clone().sync().await.unwrap();
        assert_eq!(mine(&a.core, &group).window_start, "2026-11-03");
    });
}

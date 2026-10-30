//! What the integration tests of the M1 surface share: a core on a fresh store with a fixed clock, and builders for
//! the inputs the apps pass. Each test crate uses part of it.
#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use chrono::{TimeZone, Utc};
use geunal_ffi::*;

#[derive(Default)]
pub struct MemorySecrets(Mutex<HashMap<String, Vec<u8>>>);

impl SecretStore for MemorySecrets {
    fn get(&self, name: String) -> Result<Option<Vec<u8>>, SecretStoreError> {
        Ok(self.0.lock().unwrap().get(&name).cloned())
    }
    fn put(&self, name: String, value: Vec<u8>) -> Result<(), SecretStoreError> {
        self.0.lock().unwrap().insert(name, value);
        Ok(())
    }
    fn delete(&self, name: String) -> Result<(), SecretStoreError> {
        self.0.lock().unwrap().remove(&name);
        Ok(())
    }
}

#[derive(Default)]
pub struct Changes(pub Mutex<u32>);

impl CoreListener for Changes {
    fn on_change(&self, change: Change) {
        if change == Change::Calendar {
            *self.0.lock().unwrap() += 1;
        }
    }
}

pub struct Device {
    pub core: Arc<Core>,
    pub changes: Arc<Changes>,
    _dir: tempfile::TempDir,
}

/// A core on a fresh store whose clock reads Sunday 2026-10-25 09:00 in Korea.
pub fn device() -> Device {
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

pub fn at(day: &str, minute: u16) -> DayTime {
    DayTime { day: day.into(), minute }
}

pub fn timed(calendar: &str, title: &str, day: &str, from: u16, to: u16) -> EventInput {
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

pub fn weekly(days: Vec<Weekday>) -> Recurrence {
    Recurrence { frequency: Frequency::Weekly, interval: 1, weekdays: days, until: None, count: None }
}

pub fn personal(core: &Core) -> String {
    core.calendars().unwrap().into_iter().find(|c| c.name == "개인").unwrap().id
}

pub fn titles(events: &[EventItem]) -> Vec<String> {
    events.iter().map(|e| e.title.clone()).collect()
}

pub fn cell<'a>(month: &'a [DayCell], day: &str) -> &'a DayCell {
    month.iter().find(|c| c.day == day).unwrap()
}

/// The same core with its clock moved to `at` (UTC).
pub fn set_clock(d: &Device, y: i32, m: u32, day: u32, h: u32) {
    d.core.set_clock(Some(Utc.with_ymd_and_hms(y, m, day, h, 0, 0).unwrap()));
}

pub fn all_day(calendar: &str, title: &str, first: &str, last: &str) -> EventInput {
    EventInput {
        calendar_id: calendar.into(),
        title: title.into(),
        all_day: true,
        start: at(first, 0),
        end: at(last, 0),
        recurrence: None,
        alert: AlertRule::Inherit,
        note: None,
    }
}

pub fn solar(month: u8, day: u8) -> Birthday {
    Birthday { month, day, year: None, calendar: BirthdayCalendar::Solar }
}

pub fn lunar(month: u8, day: u8, leap_month: bool) -> Birthday {
    Birthday { month, day, year: None, calendar: BirthdayCalendar::Lunar { leap_month } }
}

pub fn add_person(core: &Core, name: &str, birthday: Birthday) -> String {
    core.save_person(PersonInput {
        id: None,
        name: name.into(),
        birthday: Some(birthday),
        alert: AlertRule::Inherit,
        suggest: true,
    })
    .unwrap()
}

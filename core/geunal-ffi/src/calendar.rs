//! The M1 calendar surface (docs/design/m1.md screens 3–10, 14–16, 18–19): calendars, events, the month, week and
//! list views, the next-up bar, the widget snapshot, input suggestions, shift templates, device calendars and the
//! local alerts the apps schedule.
//!
//! Every date and time crossing this boundary is Korean local time (Asia/Seoul), the clock every rule runs on
//! (kickoff §5): a day is `"YYYY-MM-DD"`, a month `"YYYY-MM"`, and a time of day the minutes after midnight. The apps
//! never convert time zones for the calendar; they format what they get. Instants (`SystemTime`, Swift `Date`, Kotlin
//! `Instant`) appear only where a moment is meant: when an alert fires, when a backup was made.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::time::SystemTime;

use chrono::{DateTime, Datelike, Duration, FixedOffset, NaiveDate, Utc};
use geunal_domain::calendar::{EventSpec, ShiftTemplate as DomainShift, occurrences, shift_event};
use geunal_domain::holidays::{self, Bridge};
use geunal_domain::ics::{self, IcsEvent};
use geunal_domain::layout;
use geunal_domain::recurrence::{Rule, weekday_index};
use geunal_domain::suggest::{self, Candidate};
use geunal_store::{CalendarRow, Db, EventRow, ShiftTemplateRow, Store, SuggestionStatRow};
use serde::Serialize;

use crate::local::*;
use crate::{Core, CoreError};

/// The twelve colors of calendars, shift templates and groups (docs/design/tokens.json `color.group`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Palette {
    Tomato,
    Tangerine,
    Mustard,
    Olive,
    Forest,
    Mint,
    Sky,
    Sea,
    Grape,
    Plum,
    Blossom,
    Cocoa,
}

/// Days of the week in the order of the calendar grid, which starts on Sunday.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Weekday {
    Sunday,
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
}

/// A day and a time of day in Korea. `minute` counts from midnight, 0–1439; an event that ends at midnight ends at
/// `{next day, 0}`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct DayTime {
    /// `"YYYY-MM-DD"`.
    pub day: String,
    pub minute: u16,
}

/// When an alert fires, relative to the start of what it is about. For an all-day event or a birthday the start is
/// 00:00 of the day, so 360 is "전날 18:00" and -540 is "당일 09:00".
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AlertRule {
    /// Follow the next level: an event follows its calendar, a calendar the app-wide default (`NotificationSettings`).
    Inherit,
    Off,
    Before {
        minutes: i32,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CalendarKind {
    /// Made in this app; editable, in the backup.
    Own,
    /// Mirrored from the device's calendars, read-only, never in the backup or anywhere off the device.
    Device,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CalendarView {
    pub id: String,
    pub name: String,
    /// `None` for a device calendar: the apps paint all of those in the one device-calendar color.
    pub color: Option<Palette>,
    pub kind: CalendarKind,
    /// A soft calendar shows on my screens, but groups see its time as free (kickoff §5.1).
    pub soft: bool,
    /// Hidden calendars stay out of the month, week and list views; groups still count them.
    pub visible: bool,
    pub default_alert: AlertRule,
    /// Events that go with the calendar if it is deleted (screen 16: "일정 7개도 지워져요").
    pub event_count: u32,
    pub shift_template_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CalendarInput {
    /// `None` creates a calendar.
    pub id: Option<String>,
    pub name: String,
    pub color: Palette,
    pub soft: bool,
    pub default_alert: AlertRule,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Frequency {
    Daily,
    Weekly,
    Monthly,
    Yearly,
}

/// How an event repeats. The editor's presets ("매주 금요일", "매주 화·목", "매일") and "직접 정하기" all land here.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Recurrence {
    pub frequency: Frequency,
    /// Every `interval` days, weeks, months or years; 1 or more.
    pub interval: u16,
    /// For `Weekly`: the days it falls on. Empty means the weekday of the first occurrence.
    pub weekdays: Vec<Weekday>,
    /// The last day an occurrence may start on.
    pub until: Option<String>,
    /// How many occurrences in all.
    pub count: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum EventSource {
    /// Typed, picked from a suggestion, or restored from a backup.
    Own,
    /// From a device calendar: read-only, opened as a detail instead of the editor (m1.md 2.2).
    Device,
    /// Stamped with a shift template; an ordinary event that remembers its template for the stamp screen.
    Shift { template_id: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EventInput {
    pub calendar_id: String,
    pub title: String,
    pub all_day: bool,
    /// For an all-day event only the day counts.
    pub start: DayTime,
    /// A timed event ends here (exclusive); an all-day event runs through this day.
    pub end: DayTime,
    pub recurrence: Option<Recurrence>,
    pub alert: AlertRule,
    pub note: Option<String>,
}

/// The third same title on the same weekday: the toast offers to make it weekly, once (kickoff §5.2).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RepeatHint {
    pub weekday: Weekday,
    pub times: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AddedEvent {
    pub event_id: String,
    pub repeat_hint: Option<RepeatHint>,
}

/// Which occurrences of a repeating event an edit or a delete touches. Ignored for an event that does not repeat.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EditScope {
    /// "이 일정만".
    ThisOnly,
    /// "이후 모두": this occurrence and every later one. From the first occurrence that is the whole series.
    ThisAndFollowing,
}

/// One event as the editor shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EventDetail {
    pub event_id: String,
    /// The occurrence opened, for a repeating event; pass it back to `update_event` and `delete_event`.
    pub occurrence: Option<String>,
    pub calendar_id: String,
    pub title: String,
    pub all_day: bool,
    pub start: DayTime,
    pub end: DayTime,
    pub recurrence: Option<Recurrence>,
    pub alert: AlertRule,
    /// The alert that applies after inheritance, in minutes before the start, for the "1시간 전" label. `None` is off.
    pub effective_alert: Option<i32>,
    pub note: Option<String>,
    pub source: EventSource,
    pub read_only: bool,
}

/// An event or one occurrence of it, as the views list it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EventItem {
    pub event_id: String,
    /// Set for an occurrence of a repeating event: an opaque key that stays the same while the series does.
    pub occurrence: Option<String>,
    pub calendar_id: String,
    pub calendar_name: String,
    /// `None` for a device calendar's event (see `CalendarView::color`).
    pub color: Option<Palette>,
    pub title: String,
    pub all_day: bool,
    pub start: DayTime,
    pub end: DayTime,
    pub soft: bool,
    pub source: EventSource,
    /// For the list view's description ("학교 · 매주 월·수").
    pub recurrence: Option<Recurrence>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Holiday {
    pub day: String,
    pub name: String,
    /// 대체공휴일.
    pub substitute: bool,
}

/// Someone's birthday on a given day of this calendar, already converted from the lunar calendar when it is lunar.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BirthdayItem {
    pub person_id: String,
    pub name: String,
    pub day: String,
    pub lunar: bool,
}

/// One weekday off that joins holidays and a weekend into four days or more ("연차 1일로 4일 연휴", kickoff §5.1).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LeaveBridge {
    /// The weekday to take off.
    pub leave_day: String,
    pub first_day: String,
    pub last_day: String,
    /// Days off in a row with the leave day, `first_day` through `last_day`.
    pub days_off: u8,
}

/// One cell of the month grid.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DayCell {
    pub day: String,
    pub weekday: Weekday,
    /// False for the days of the previous and next month that fill the first and last week.
    pub in_month: bool,
    /// More than one on the same day is possible (2025-05-05: 어린이날, 부처님오신날).
    pub holidays: Vec<Holiday>,
    /// Visible calendars only; all-day events first, then by start.
    pub events: Vec<EventItem>,
    pub birthdays: Vec<BirthdayItem>,
    /// Set on the leave day of a bridge, when leave suggestions are on.
    pub leave_bridge: Option<LeaveBridge>,
}

/// A timed event's part on one day of the week view, with its place among the events it overlaps.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TimedBlock {
    pub event: EventItem,
    /// Within this day, 0–1440: an event that crosses midnight is cut into one block per day.
    pub start_minute: u16,
    pub end_minute: u16,
    /// Side-by-side layout of overlapping events, at most three columns (m1.md screen 7).
    pub column: u8,
    pub columns: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WeekDay {
    pub day: String,
    pub weekday: Weekday,
    pub holidays: Vec<Holiday>,
    pub all_day: Vec<EventItem>,
    pub birthdays: Vec<BirthdayItem>,
    pub timed: Vec<TimedBlock>,
    /// Timed events that did not fit in three columns: the "+N".
    pub hidden: u32,
}

/// A day of the list view; only days with something on them are returned.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AgendaDay {
    pub day: String,
    pub weekday: Weekday,
    pub holidays: Vec<Holiday>,
    pub events: Vec<EventItem>,
    pub birthdays: Vec<BirthdayItem>,
}

/// One item of the next-up bar: my events and birthdays in M1, appointments from M2 (m1.md, introduction).
// UniFFI copies the value across the boundary and has no `Box`, so the variants' size difference costs nothing here.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum UpNext {
    /// `starts_at` and `ends_at` are the same times as the item's, as instants for the "3시간 40분 뒤" countdown.
    Event {
        item: EventItem,
        starts_at: SystemTime,
        ends_at: SystemTime,
    },
    Birthday {
        item: BirthdayItem,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SuggestionSource {
    /// The built-in dictionary (회사 업무, 회식, 수업, 운동, 약속, …).
    Dictionary,
    /// A title used before on the same weekday and time of day.
    History,
    /// A birthday within seven days ("OO 생일 약속").
    Birthday {
        person_id: String,
    },
    /// A holiday or a long weekend close by ("연휴 여행").
    Holiday,
    Shift {
        template_id: String,
    },
}

/// A suggestion chip of the quick entry sheet. Tapping it saves `event` as it is: `add_event(event, true)`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Suggestion {
    pub event: EventInput,
    pub source: SuggestionSource,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ShiftTemplate {
    pub id: String,
    pub name: String,
    /// The template's calendar color in one of its five tints (1 lightest – 5 darkest), so templates of the same
    /// calendar stay apart on the stamp screen.
    pub color: Palette,
    pub tint: u8,
    pub start_minute: u16,
    /// At or before `start_minute` means the shift ends the next day (22:00–02:00), as one event.
    pub end_minute: u16,
    pub calendar_id: String,
    pub alert: AlertRule,
    /// The shift's length, past midnight included.
    pub length_minutes: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ShiftTemplateInput {
    /// `None` creates a template.
    pub id: Option<String>,
    pub name: String,
    pub start_minute: u16,
    pub end_minute: u16,
    /// `None` puts the shifts in the "알바" calendar, created the first time (m1.md 2.3).
    pub calendar_id: Option<String>,
    pub alert: AlertRule,
}

/// The templates stamped on one day of the stamp screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ShiftDay {
    pub day: String,
    pub template_ids: Vec<String>,
}

/// One of the device's calendars the user chose to mirror, with its events in the window the app reads.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceCalendarInput {
    /// The device's own identifier (EventKit `calendarIdentifier`, CalendarContract `_ID`).
    pub source_id: String,
    pub name: String,
    pub events: Vec<DeviceEventInput>,
}

/// One occurrence of a device event: the app expands repeating events itself, because both OS calendars do.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceEventInput {
    /// Stable for the occurrence across reads (EventKit `eventIdentifier` + start, CalendarContract `Instances._ID`).
    pub device_event_id: String,
    pub title: String,
    pub all_day: bool,
    pub start: DayTime,
    pub end: DayTime,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceMirror {
    pub calendars: u32,
    pub events: u32,
}

/// The alert settings of screen 19. Scheduling stays with the apps; the core says what fires when.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NotificationSettings {
    /// Minutes before a timed event, for calendars that inherit; `None` is off. 10 by default.
    pub event_alert: Option<i32>,
    /// Minutes before 00:00 of an all-day event's day; `None` is off. 360 ("전날 18:00") by default.
    pub all_day_alert: Option<i32>,
    pub next_up_bar: bool,
    /// Birthday alerts at 09:00 of the day, unless the person says otherwise.
    pub birthday_alerts: bool,
    pub leave_suggestions: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AlertTarget {
    Event { event_id: String, occurrence: Option<String> },
    Birthday { person_id: String },
    LeaveBridge { bridge: LeaveBridge },
}

/// A local notification to schedule. The apps keep the nearest ones scheduled (iOS allows 64) and replace them
/// whenever the calendar changes.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScheduledAlert {
    /// Stable while the alert stays the same, so the app can tell which scheduled notifications to keep.
    pub id: String,
    pub fire_at: SystemTime,
    pub target: AlertTarget,
    /// The event title or the person's name; empty for a leave bridge.
    pub title: String,
    pub start: DayTime,
    pub all_day: bool,
}

#[uniffi::export]
impl Core {
    // ----- calendars (screens 15, 16) -----

    /// Own calendars in the order they were made, then the device calendars being mirrored.
    pub fn calendars(&self) -> Result<Vec<CalendarView>, CoreError> {
        self.store.read(|db| {
            let events = db.event_counts()?;
            let mut templates: HashMap<String, u32> = HashMap::new();
            for t in db.shift_templates()? {
                if let Some(c) = t.calendar_id {
                    *templates.entry(c).or_default() += 1;
                }
            }
            Ok(db
                .calendars()?
                .into_iter()
                .filter(|c| c.kind == OWN || c.kind == DEVICE)
                .map(|c| CalendarView {
                    event_count: events.get(&c.id).copied().unwrap_or(0) as u32,
                    shift_template_count: templates.get(&c.id).copied().unwrap_or(0),
                    color: palette(&c.color),
                    kind: kind(&c),
                    soft: !c.blocks_free,
                    visible: c.visible,
                    default_alert: alert_rule(c.default_alert.as_deref()),
                    id: c.id,
                    name: c.name,
                })
                .collect())
        })
    }

    /// Creates or updates an own calendar and returns its id.
    pub fn save_calendar(&self, input: CalendarInput) -> Result<String, CoreError> {
        let name = name(&input.name, "calendar")?;
        let id = self.store.write(|db| {
            let row = match &input.id {
                Some(id) => {
                    let existing = own_calendar(db, id)?;
                    CalendarRow {
                        name,
                        color: palette_key(input.color).to_owned(),
                        blocks_free: !input.soft,
                        default_alert: alert_text(&input.default_alert),
                        ..existing
                    }
                }
                None => CalendarRow {
                    id: new_id(),
                    name,
                    color: palette_key(input.color).to_owned(),
                    kind: OWN.to_owned(),
                    blocks_free: !input.soft,
                    group_id: None,
                    visible: true,
                    default_alert: alert_text(&input.default_alert),
                    position: db.next_calendar_position()?,
                    device_source_id: None,
                },
            };
            db.put_calendar(&row)?;
            mark_changed(db)?;
            Ok::<_, CoreError>(row.id)
        })?;
        self.calendar_changed();
        Ok(id)
    }

    pub fn set_calendar_visible(&self, calendar_id: String, visible: bool) -> Result<(), CoreError> {
        self.store.write(|db| {
            let row = db.calendar(&calendar_id)?.ok_or_else(|| not_found(format!("calendar {calendar_id}")))?;
            db.put_calendar(&CalendarRow { visible, ..row })?;
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    /// Deletes an own calendar with its events and shift templates. The last own calendar cannot go (`Invalid`).
    pub fn delete_calendar(&self, calendar_id: String) -> Result<(), CoreError> {
        self.store.write(|db| {
            own_calendar(db, &calendar_id)?;
            let own = db.calendars()?.iter().filter(|c| c.kind == OWN).count();
            if own <= 1 {
                return Err(invalid("the last calendar stays"));
            }
            db.delete_shift_templates_of_calendar(&calendar_id)?;
            db.delete_calendar(&calendar_id)?;
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    // ----- events (screens 5, 6, 9) -----

    /// Saves a new event. `from_suggestion` marks a tapped suggestion chip, which the alpha statistics count
    /// (protocol §14). Undo is `delete_event(event_id, None, ThisAndFollowing)`.
    pub fn add_event(&self, input: EventInput, from_suggestion: bool) -> Result<AddedEvent, CoreError> {
        let now = self.now();
        let added = self.store.write(|db| {
            own_calendar(db, &input.calendar_id)?;
            let row = event_row(new_id(), &input, now, if from_suggestion { "suggestion" } else { "typed" })?;
            db.put_event(&row)?;
            let repeat_hint = count_use(db, &row, now)?;
            db.bump_alpha(&day_text(kst_day(now)), 1, i64::from(from_suggestion))?;
            mark_changed(db)?;
            Ok::<_, CoreError>(AddedEvent { event_id: row.id, repeat_hint })
        })?;
        self.calendar_changed();
        Ok(added)
    }

    /// The event or occurrence the editor opens.
    pub fn event(&self, event_id: String, occurrence: Option<String>) -> Result<EventDetail, CoreError> {
        self.store.read(|db| {
            let row = db.event(&event_id)?.ok_or_else(|| not_found(format!("event {event_id}")))?;
            let calendar = db.calendar(&row.calendar_id)?.ok_or_else(|| malformed("event without calendar"))?;
            let settings = notifications(db)?;
            let (starts_at, ends_at) = (parse_stored(&row.starts_at)?, parse_stored(&row.ends_at)?);
            let (occurrence, starts_at, ends_at) = match (&row.rrule, occurrence) {
                (Some(_), Some(key)) => {
                    let at = occurrence_at(&key)?;
                    (Some(occurrence_key(at, row.all_day)), at, at + (ends_at - starts_at))
                }
                _ => (None, starts_at, ends_at),
            };
            Ok(EventDetail {
                event_id: row.id.clone(),
                occurrence,
                calendar_id: row.calendar_id.clone(),
                title: row.title.clone(),
                all_day: row.all_day,
                start: day_time(starts_at),
                end: end_of(row.all_day, ends_at),
                recurrence: row.rrule.as_deref().and_then(Rule::parse).map(|r| recurrence(&r)),
                alert: alert_rule(row.alert.as_deref()),
                effective_alert: effective_alert(&row, &calendar, &settings),
                note: row.note.clone(),
                source: source(&row),
                read_only: row.device_event_id.is_some(),
            })
        })
    }

    /// Saves the editor. For a repeating event `scope` says which occurrences change; "이후 모두" from a later
    /// occurrence splits the series, and the id of the event the occurrence now belongs to comes back.
    pub fn update_event(
        &self,
        event_id: String,
        occurrence: Option<String>,
        scope: EditScope,
        input: EventInput,
    ) -> Result<String, CoreError> {
        let now = self.now();
        let id = self.store.write(|db| -> Result<String, CoreError> {
            let row = editable_event(db, &event_id)?;
            own_calendar(db, &input.calendar_id)?;
            let edited = |id: String, origin: &str| -> Result<EventRow, CoreError> {
                let mut new = event_row(id, &input, now, origin)?;
                new.created_at = row.created_at.clone();
                new.origin = row.origin.clone().or(new.origin);
                new.shift_template_id = row.shift_template_id.clone();
                Ok(new)
            };
            let Some(rule_text) = row.rrule.clone() else {
                db.put_event(&edited(row.id.clone(), "typed")?)?;
                return Ok(row.id.clone());
            };
            let Some(key) = occurrence else {
                // No occurrence named: the editor opened the series itself.
                db.put_event(&whole_series(&row, edited(row.id.clone(), "typed")?)?)?;
                return Ok(row.id.clone());
            };
            let at = occurrence_at(&key)?;
            let first = parse_stored(&row.starts_at)?;
            match scope {
                EditScope::ThisOnly => {
                    // The occurrence leaves the series and becomes an event of its own, which may change anything.
                    let mut gone = exdates(&row)?;
                    gone.push(at);
                    db.put_event(&EventRow {
                        exdates: exdates_text(&gone),
                        updated_at: stored_utc(now),
                        ..row.clone()
                    })?;
                    let mut own = edited(new_id(), "typed")?;
                    own.rrule = None;
                    own.exdates = None;
                    // An edit made this row, not an add: an undo has nothing of it to take back.
                    own.created_at = None;
                    db.put_event(&own)?;
                    Ok(own.id)
                }
                EditScope::ThisAndFollowing if at <= first => {
                    db.put_event(&whole_series(&row, edited(row.id.clone(), "typed")?)?)?;
                    Ok(row.id.clone())
                }
                EditScope::ThisAndFollowing => {
                    let old = Rule::parse(&rule_text).ok_or_else(|| invalid("this repeat rule cannot be split"))?;
                    let split = at.with_timezone(&geunal_domain::KST).date_naive();
                    let (before, after): (Vec<_>, Vec<_>) = exdates(&row)?.into_iter().partition(|d| *d < at);
                    db.put_event(&EventRow {
                        rrule: Some(old.ending_before(split).to_rrule()),
                        exdates: exdates_text(&before),
                        updated_at: stored_utc(now),
                        ..row.clone()
                    })?;
                    let mut following = edited(new_id(), "typed")?;
                    // Removed occurrences after the split move with the times the edit gave the series.
                    let shift = parse_stored(&following.starts_at)? - at;
                    if following.rrule.is_some() {
                        following.exdates = exdates_text(&after.iter().map(|d| *d + shift).collect::<Vec<_>>());
                    }
                    // A count the edit left as it was is the series' total, so the following part keeps only what
                    // was left of it. A count the user changed counts from the split.
                    if let Some(total) = old.count.filter(|_| following.rrule == row.rrule) {
                        let left = total.saturating_sub(made_before(&row, first, at, total)?).max(1);
                        following.rrule = Some(Rule { count: Some(left), ..old.clone() }.to_rrule());
                    }
                    // An edit made this row, not an add: an undo has nothing of it to take back.
                    following.created_at = None;
                    db.put_event(&following)?;
                    Ok(following.id)
                }
            }
        })?;
        self.store.write(mark_changed)?;
        self.calendar_changed();
        Ok(id)
    }

    pub fn delete_event(
        &self,
        event_id: String,
        occurrence: Option<String>,
        scope: EditScope,
    ) -> Result<(), CoreError> {
        let now = self.now();
        self.store.write(|db| {
            let row = editable_event(db, &event_id)?;
            match (&row.rrule, occurrence) {
                (None, _) => {
                    undo_counts(db, &row, now)?;
                    db.delete_event(&row.id)?;
                }
                (Some(_), None) => {
                    undo_counts(db, &row, now)?;
                    db.delete_event(&row.id)?;
                }
                (Some(rule_text), Some(key)) => {
                    let at = occurrence_at(&key)?;
                    let first = parse_stored(&row.starts_at)?;
                    match scope {
                        EditScope::ThisOnly => {
                            let mut gone = exdates(&row)?;
                            gone.push(at);
                            db.put_event(&EventRow {
                                exdates: exdates_text(&gone),
                                updated_at: stored_utc(now),
                                ..row.clone()
                            })?;
                        }
                        EditScope::ThisAndFollowing if at <= first => {
                            undo_counts(db, &row, now)?;
                            db.delete_event(&row.id)?;
                        }
                        EditScope::ThisAndFollowing => {
                            let old =
                                Rule::parse(rule_text).ok_or_else(|| invalid("this repeat rule cannot be split"))?;
                            let split = at.with_timezone(&geunal_domain::KST).date_naive();
                            let before: Vec<_> = exdates(&row)?.into_iter().filter(|d| *d < at).collect();
                            db.put_event(&EventRow {
                                rrule: Some(old.ending_before(split).to_rrule()),
                                exdates: exdates_text(&before),
                                updated_at: stored_utc(now),
                                ..row.clone()
                            })?;
                        }
                    }
                }
            }
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    // ----- views (screens 4, 7, 8, 14) -----

    /// The month grid for `month` ("YYYY-MM"): the whole weeks from Sunday that cover the month, 28 to 42 cells.
    pub fn calendar_month(&self, month: String) -> Result<Vec<DayCell>, CoreError> {
        let first = crate::local::month(&month)?;
        let last = next_month(first).pred_opt().expect("a month has a last day");
        let grid_start = first - Duration::days(i64::from(first.weekday().num_days_from_sunday()));
        let grid_end = last + Duration::days(i64::from(6 - last.weekday().num_days_from_sunday()));
        let days = Days::read(&self.store, grid_start, grid_end)?;
        Ok(days
            .each(grid_start, grid_end)
            .map(|day| DayCell {
                day: day_text(day),
                weekday: weekday_of(day),
                in_month: day.month() == first.month(),
                holidays: days.holidays_on(day),
                events: days.items_on(day),
                birthdays: days.birthdays_on(day),
                leave_bridge: days.bridge_on(day),
            })
            .collect())
    }

    /// Seven days from `start_day`; the app passes the Sunday of the week it shows.
    pub fn calendar_week(&self, start_day: String) -> Result<Vec<WeekDay>, CoreError> {
        let first = day(&start_day)?;
        let last = first + Duration::days(6);
        let days = Days::read(&self.store, first, last)?;
        Ok(days
            .each(first, last)
            .map(|d| {
                let (all_day, timed): (Vec<_>, Vec<_>) = days.on(d).into_iter().partition(|(item, _, _)| item.all_day);
                let start_of_day = midnight(d);
                let blocks: Vec<(EventItem, u16, u16)> = timed
                    .into_iter()
                    .map(|(item, starts_at, ends_at)| {
                        let minutes = |at: DateTime<FixedOffset>| {
                            (at - start_of_day).num_minutes().clamp(0, i64::from(MINUTES_PER_DAY)) as u16
                        };
                        (item, minutes(starts_at), minutes(ends_at))
                    })
                    .filter(|(_, s, e)| e > s)
                    .collect();
                let places = layout::place(&blocks.iter().map(|(_, s, e)| (*s, *e)).collect::<Vec<_>>(), 3);
                let hidden = places.iter().filter(|p| p.is_none()).count() as u32;
                let timed = blocks
                    .into_iter()
                    .zip(places)
                    .filter_map(|((event, start_minute, end_minute), place)| {
                        place.map(|p| TimedBlock {
                            event,
                            start_minute,
                            end_minute,
                            column: p.column,
                            columns: p.columns,
                        })
                    })
                    .collect();
                WeekDay {
                    day: day_text(d),
                    weekday: weekday_of(d),
                    holidays: days.holidays_on(d),
                    all_day: all_day.into_iter().map(|(item, _, _)| item).collect(),
                    birthdays: days.birthdays_on(d),
                    timed,
                    hidden,
                }
            })
            .collect())
    }

    /// The days from `from_day` through the next `days` days that have something on them. The list view pages by
    /// asking for the next range; an empty answer for 28 days is its empty state.
    pub fn calendar_agenda(&self, from_day: String, days: u16) -> Result<Vec<AgendaDay>, CoreError> {
        let first = day(&from_day)?;
        if days == 0 {
            return Ok(Vec::new());
        }
        // A year at most per call keeps one page cheap; the list asks again for the next range.
        let last = first + Duration::days(i64::from(days.min(366)) - 1);
        let found = Days::read(&self.store, first, last)?;
        Ok(found
            .each(first, last)
            .map(|d| AgendaDay {
                day: day_text(d),
                weekday: weekday_of(d),
                holidays: found.holidays_on(d),
                events: found.items_on(d),
                birthdays: found.birthdays_on(d),
            })
            .filter(|a| !a.events.is_empty() || !a.holidays.is_empty() || !a.birthdays.is_empty())
            .collect())
    }

    /// What is on now or next, nearest first: events that have not ended and birthdays from today.
    pub fn next_up(&self, count: u16) -> Result<Vec<UpNext>, CoreError> {
        let now = self.now().with_timezone(&geunal_domain::KST);
        let today = now.date_naive();
        let mut found: Vec<(DateTime<FixedOffset>, u8, UpNext)> = self.store.read(|db| {
            let calendars = calendars_by_id(db)?;
            let mut found = Vec::new();
            for (row, occ) in instances(db, now, now + Duration::days(NEXT_UP_DAYS), |row| {
                calendars.get(&row.calendar_id).is_some_and(|c| c.visible)
            })? {
                if occ.ends_at <= now {
                    continue;
                }
                let item = item(&row, &occ, &calendars[&row.calendar_id]);
                // Within one start time: all-day events, then birthdays, then timed events.
                let rank = if item.all_day { 0 } else { 2 };
                found.push((
                    occ.starts_at,
                    rank,
                    UpNext::Event { item, starts_at: system_time(occ.starts_at), ends_at: system_time(occ.ends_at) },
                ));
            }
            for (d, item) in birthdays_between(db, today, today + Duration::days(NEXT_UP_DAYS))? {
                found.push((midnight(d), 1, UpNext::Birthday { item }));
            }
            Ok::<_, CoreError>(found)
        })?;
        found.sort_by_key(|(at, rank, _)| (*at, *rank));
        Ok(found.into_iter().take(usize::from(count)).map(|(_, _, n)| n).collect())
    }

    /// The JSON the widgets read (docs/parts/rust-core.md §3, "위젯 스냅샷"). The app writes it to the shared
    /// container whenever the calendar changes and at midnight; the widget extension never opens the core.
    pub fn widget_snapshot(&self) -> Result<String, CoreError> {
        let today = kst_day(self.now());
        let tomorrow = today.succ_opt().ok_or_else(|| invalid("day out of range"))?;
        let days = Days::read(&self.store, today, tomorrow)?;
        let entries = |d: NaiveDate| -> Vec<WidgetEvent> {
            days.items_on(d)
                .into_iter()
                .take(WIDGET_ITEMS)
                .map(|i| WidgetEvent {
                    color: i.color.map(palette_key),
                    start: (!i.all_day).then(|| hm(i.start.minute)),
                    end: (!i.all_day).then(|| hm(i.end.minute)),
                    all_day: i.all_day,
                    title: i.title,
                })
                .collect()
        };
        let snapshot = WidgetSnapshot {
            v: 1,
            day: day_text(today),
            today: entries(today),
            tomorrow: entries(tomorrow),
            next_appointment: None,
        };
        Ok(serde_json::to_string(&snapshot).expect("the snapshot serializes"))
    }

    // ----- suggestions (screen 5) -----

    /// Up to five chips for `day`, best first, without any that overlap what is already on that day (kickoff §5.2).
    /// Nothing about them leaves the device, and an untapped chip is never saved.
    pub fn suggestions(&self, day: String) -> Result<Vec<Suggestion>, CoreError> {
        let target = crate::local::day(&day)?;
        let now = self.now();
        let days = Days::read(&self.store, target, target)?;
        let ranked = self.store.read(|db| suggestion_candidates(db, &days, target, now))?;
        let busy: Vec<(u16, u16)> = days
            .on(target)
            .into_iter()
            .filter(|(i, _, _)| !i.all_day)
            .map(|(_, s, e)| {
                let start_of_day = midnight(target);
                let minutes = |at: DateTime<FixedOffset>| (at - start_of_day).num_minutes().clamp(0, 1440) as u16;
                (minutes(s), minutes(e))
            })
            .collect();
        let titles: HashSet<String> = days.items_on(target).into_iter().map(|i| i.title).collect();
        let default = self.store.read(default_calendar)?;
        // A shift chip saves what stamping its template would, alert included.
        let template_alerts: HashMap<String, AlertRule> = self.store.read(|db| {
            Ok::<_, CoreError>(
                db.shift_templates()?.into_iter().map(|t| (t.id, alert_rule(t.alert.as_deref()))).collect(),
            )
        })?;
        Ok(suggest::rank(ranked, &busy, &titles)
            .into_iter()
            .map(|c| {
                let next = target.succ_opt().expect("day in range");
                let end_day = if !c.all_day && c.end_minute <= c.start_minute { next } else { target };
                Suggestion {
                    event: EventInput {
                        calendar_id: c.calendar_id.clone().unwrap_or_else(|| default.id.clone()),
                        title: c.title.clone(),
                        all_day: c.all_day,
                        start: DayTime { day: day_text(target), minute: if c.all_day { 0 } else { c.start_minute } },
                        end: DayTime { day: day_text(end_day), minute: if c.all_day { 0 } else { c.end_minute } },
                        recurrence: None,
                        alert: match &c.source {
                            suggest::Source::Shift { template_id } => {
                                template_alerts.get(template_id).cloned().unwrap_or(AlertRule::Inherit)
                            }
                            _ => AlertRule::Inherit,
                        },
                        note: None,
                    },
                    source: match c.source {
                        suggest::Source::Dictionary => SuggestionSource::Dictionary,
                        suggest::Source::History => SuggestionSource::History,
                        suggest::Source::Birthday { person_id } => SuggestionSource::Birthday { person_id },
                        suggest::Source::Holiday => SuggestionSource::Holiday,
                        suggest::Source::Shift { template_id } => SuggestionSource::Shift { template_id },
                    },
                }
            })
            .collect())
    }

    // ----- shift templates (screens 10, 18) -----

    pub fn shift_templates(&self) -> Result<Vec<ShiftTemplate>, CoreError> {
        self.store.read(|db| {
            let calendars = calendars_by_id(db)?;
            db.shift_templates()?.into_iter().map(|t| template_view(&t, &calendars)).collect()
        })
    }

    /// Creates or updates a template and returns its id. Shifts stamped before stay as they are.
    pub fn save_shift_template(&self, input: ShiftTemplateInput) -> Result<String, CoreError> {
        let name = name(&input.name, "template")?;
        if input.start_minute >= MINUTES_PER_DAY || input.end_minute >= MINUTES_PER_DAY {
            return Err(invalid("a shift time is past the day"));
        }
        if input.start_minute == input.end_minute {
            return Err(invalid("a shift has no length"));
        }
        let id = self.store.write(|db| {
            let calendar = match &input.calendar_id {
                Some(id) => own_calendar(db, id)?,
                None => shift_calendar(db)?,
            };
            let existing = input.id.as_deref().map(|id| db.shift_template(id)).transpose()?;
            let existing = match (&input.id, existing) {
                (Some(id), None) => return Err(not_found(format!("template {id}"))),
                (_, e) => e.flatten(),
            };
            let tint = match &existing {
                Some(t) if t.calendar_id.as_deref() == Some(calendar.id.as_str()) => t.tint,
                _ => next_tint(db, &calendar.id)?,
            };
            let row = ShiftTemplateRow {
                id: existing.as_ref().map(|t| t.id.clone()).unwrap_or_else(new_id),
                name,
                start_hm: hm(input.start_minute),
                end_hm: hm(input.end_minute),
                color: calendar.color.clone(),
                calendar_id: Some(calendar.id.clone()),
                alert: alert_text(&input.alert),
                tint,
                position: match &existing {
                    Some(t) => t.position,
                    None => db.next_shift_template_position()?,
                },
            };
            db.put_shift_template(&row)?;
            mark_changed(db)?;
            Ok::<_, CoreError>(row.id)
        })?;
        self.calendar_changed();
        Ok(id)
    }

    /// Deletes a template; the shifts already stamped stay as ordinary events.
    pub fn delete_shift_template(&self, template_id: String) -> Result<(), CoreError> {
        self.store.write(|db| {
            db.shift_template(&template_id)?.ok_or_else(|| not_found(format!("template {template_id}")))?;
            db.detach_shift_template(&template_id)?;
            db.delete_shift_template(&template_id)?;
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    /// The days of `month` ("YYYY-MM") that have shifts, for the stamp screen.
    pub fn shift_days(&self, month: String) -> Result<Vec<ShiftDay>, CoreError> {
        let first = crate::local::month(&month)?;
        self.store.read(|db| {
            let mut by_day: BTreeMap<NaiveDate, Vec<String>> = BTreeMap::new();
            for row in db.shift_events_between(&stored(midnight(first)), &stored(midnight(next_month(first))))? {
                let Some(template) = row.shift_template_id else { continue };
                let day = parse_stored(&row.starts_at)?.with_timezone(&geunal_domain::KST).date_naive();
                let list = by_day.entry(day).or_default();
                if !list.contains(&template) {
                    list.push(template);
                }
            }
            Ok(by_day.into_iter().map(|(d, template_ids)| ShiftDay { day: day_text(d), template_ids }).collect())
        })
    }

    /// The stamp screen's "완료": each listed day ends up with exactly these templates' shifts (an empty list clears
    /// it). The screen keeps its draft until then, so "취소" changes nothing.
    pub fn set_shift_days(&self, days: Vec<ShiftDay>) -> Result<(), CoreError> {
        let now = self.now();
        let parsed: Vec<(NaiveDate, Vec<String>)> =
            days.iter().map(|d| Ok((day(&d.day)?, d.template_ids.clone()))).collect::<Result<_, CoreError>>()?;
        self.store.write(|db| {
            let templates: HashMap<String, ShiftTemplateRow> =
                db.shift_templates()?.into_iter().map(|t| (t.id.clone(), t)).collect();
            let mut added = 0i64;
            for (d, wanted) in &parsed {
                let mut wanted_set: Vec<&String> = Vec::new();
                for id in wanted {
                    if !templates.contains_key(id) {
                        return Err(not_found(format!("template {id}")));
                    }
                    if !wanted_set.contains(&id) {
                        wanted_set.push(id);
                    }
                }
                let next = d.succ_opt().ok_or_else(|| invalid("day out of range"))?;
                let mut kept: HashSet<String> = HashSet::new();
                for row in db.shift_events_between(&stored(midnight(*d)), &stored(midnight(next)))? {
                    let template = row.shift_template_id.clone().unwrap_or_default();
                    if wanted_set.iter().any(|w| **w == template) && kept.insert(template) {
                        continue;
                    }
                    db.delete_event(&row.id)?;
                }
                for id in wanted_set {
                    if kept.contains(id) {
                        continue;
                    }
                    db.put_event(&stamped(&templates[id], *d, now)?)?;
                    added += 1;
                }
            }
            if added > 0 {
                db.bump_alpha(&day_text(kst_day(now)), added, 0)?;
            }
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    // ----- device calendars (screens 3, 15) -----

    /// Replaces the mirror with these calendars, the ones the user ticked, and their events. An empty list turns
    /// the mirror off. Titles stay on the device: no backup, no group, no server ever sees them (kickoff §5.1).
    pub fn mirror_device_calendars(&self, calendars: Vec<DeviceCalendarInput>) -> Result<DeviceMirror, CoreError> {
        let now = self.now();
        let mirror = self.store.write(|db| {
            let existing: HashMap<String, CalendarRow> = db
                .calendars()?
                .into_iter()
                .filter(|c| c.kind == DEVICE)
                .filter_map(|c| c.device_source_id.clone().map(|s| (s, c)))
                .collect();
            let wanted: HashSet<&str> = calendars.iter().map(|c| c.source_id.as_str()).collect();
            for (source, row) in &existing {
                if !wanted.contains(source.as_str()) {
                    db.delete_calendar(&row.id)?;
                }
            }
            let mut mirror = DeviceMirror { calendars: 0, events: 0 };
            // Mirrored calendars sort after own ones, in the order the device listed them.
            for (position, input) in (1_000_000..).zip(calendars.iter()) {
                let row = match existing.get(&input.source_id) {
                    Some(row) => CalendarRow { name: input.name.clone(), position, ..row.clone() },
                    None => CalendarRow {
                        id: format!("device:{}", input.source_id),
                        name: input.name.clone(),
                        color: DEVICE_COLOR.to_owned(),
                        kind: DEVICE.to_owned(),
                        blocks_free: true,
                        group_id: None,
                        visible: true,
                        default_alert: None,
                        position,
                        device_source_id: Some(input.source_id.clone()),
                    },
                };
                db.put_calendar(&row)?;
                db.delete_events_of_calendar(&row.id)?;
                mirror.calendars += 1;
                for event in &input.events {
                    // Device data can be odd (a zero-length event, an end before the start); such an event is left out.
                    let Ok((starts_at, ends_at)) = range(event.all_day, &event.start, &event.end) else { continue };
                    db.put_event(&EventRow {
                        id: format!("{}:{}", row.id, event.device_event_id),
                        calendar_id: row.id.clone(),
                        title: event.title.clone(),
                        starts_at: stored(starts_at),
                        ends_at: stored(ends_at),
                        all_day: event.all_day,
                        tz: TZ.to_owned(),
                        rrule: None,
                        exdates: None,
                        note: None,
                        device_event_id: Some(event.device_event_id.clone()),
                        group_ref: None,
                        tentative: false,
                        updated_at: stored_utc(now),
                        alert: None,
                        shift_template_id: None,
                        created_at: None,
                        origin: Some("device".to_owned()),
                    })?;
                    mirror.events += 1;
                }
            }
            Ok::<_, CoreError>(mirror)
        })?;
        self.calendar_changed();
        Ok(mirror)
    }

    // ----- alerts (screen 19) -----

    pub fn notification_settings(&self) -> Result<NotificationSettings, CoreError> {
        self.store.read(notifications)
    }

    pub fn set_notification_settings(&self, settings: NotificationSettings) -> Result<(), CoreError> {
        self.store.write(|db| {
            set_notifications(db, &settings)?;
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    /// The next `limit` alerts from now, soonest first: events, birthdays and leave suggestions, after the
    /// settings and each event's own rule.
    pub fn scheduled_alerts(&self, limit: u32) -> Result<Vec<ScheduledAlert>, CoreError> {
        let now = self.now().with_timezone(&geunal_domain::KST);
        self.store.read(|db| {
            let calendars = calendars_by_id(db)?;
            let settings = notifications(db)?;
            let mut alerts = Vec::new();
            // An alert fires before its event, so look a little past the window for events whose alert falls in it.
            for (row, occ) in instances(db, now, now + Duration::days(ALERT_DAYS), |row| row.device_event_id.is_none())?
            {
                let Some(calendar) = calendars.get(&row.calendar_id) else { continue };
                let Some(minutes) = effective_alert(&row, calendar, &settings) else { continue };
                let fire_at = occ.starts_at - Duration::minutes(i64::from(minutes));
                if fire_at <= now {
                    continue;
                }
                let key = occurrence_key(occ.occurrence_at, row.all_day);
                alerts.push((
                    fire_at,
                    ScheduledAlert {
                        id: format!("event:{}:{key}:{minutes}", row.id),
                        fire_at: system_time(fire_at),
                        target: AlertTarget::Event {
                            event_id: row.id.clone(),
                            occurrence: row.rrule.as_ref().map(|_| key.clone()),
                        },
                        title: occ.title.clone().unwrap_or_else(|| row.title.clone()),
                        start: day_time(occ.starts_at),
                        all_day: row.all_day,
                    },
                ));
            }
            let today = now.date_naive();
            let horizon = today + Duration::days(ALERT_DAYS);
            for row in db.people()? {
                let minutes = match alert_rule(row.alert.as_deref()) {
                    AlertRule::Off => continue,
                    AlertRule::Before { minutes } => minutes,
                    AlertRule::Inherit if settings.birthday_alerts => BIRTHDAY_ALERT,
                    AlertRule::Inherit => continue,
                };
                for d in person_birthdays(&row, today, horizon) {
                    let fire_at = midnight(d) - Duration::minutes(i64::from(minutes));
                    if fire_at <= now {
                        continue;
                    }
                    alerts.push((
                        fire_at,
                        ScheduledAlert {
                            id: format!("birthday:{}:{}:{minutes}", row.id, day_text(d)),
                            fire_at: system_time(fire_at),
                            target: AlertTarget::Birthday { person_id: row.id.clone() },
                            title: row.name.clone(),
                            start: DayTime { day: day_text(d), minute: 0 },
                            all_day: true,
                        },
                    ));
                }
            }
            if settings.leave_suggestions {
                // A bridge is announced three weeks ahead at 09:00, in time to ask for the day off.
                let lead = Duration::days(BRIDGE_ALERT_DAYS);
                for b in open_bridges(db, today, horizon + lead)? {
                    let fire_at = midnight(b.leave_day) - lead + Duration::minutes(i64::from(9 * 60));
                    if fire_at <= now || fire_at > midnight(horizon) {
                        continue;
                    }
                    alerts.push((
                        fire_at,
                        ScheduledAlert {
                            id: format!("bridge:{}", day_text(b.leave_day)),
                            fire_at: system_time(fire_at),
                            target: AlertTarget::LeaveBridge { bridge: bridge(&b) },
                            title: String::new(),
                            start: DayTime { day: day_text(b.leave_day), minute: 0 },
                            all_day: true,
                        },
                    ));
                }
            }
            alerts.sort_by(|a, b| (a.0, &a.1.id).cmp(&(b.0, &b.1.id)));
            Ok(alerts.into_iter().take(limit as usize).map(|(_, a)| a).collect())
        })
    }

    /// Own events as an iCalendar file (screen 22: what stays on this device can be exported). Device calendars are
    /// left out; they are the device's already.
    pub fn export_ics(&self) -> Result<String, CoreError> {
        let now = self.now();
        let events = self.store.read(|db| {
            let calendars = calendars_by_id(db)?;
            db.events()?
                .into_iter()
                .filter(|row| row.device_event_id.is_none())
                .filter(|row| calendars.get(&row.calendar_id).is_some_and(|c| c.kind == OWN))
                .map(|row| {
                    Ok(IcsEvent {
                        uid: format!("{}@geunal-eottae", row.id),
                        title: row.title.clone(),
                        all_day: row.all_day,
                        starts_at: parse_stored(&row.starts_at)?,
                        ends_at: parse_stored(&row.ends_at)?,
                        rrule: row.rrule.clone(),
                        exdates: exdates(&row)?,
                        note: row.note.clone(),
                    })
                })
                .collect::<Result<Vec<_>, CoreError>>()
        })?;
        Ok(ics::calendar(&events, now))
    }
}

/// How far ahead the next-up bar looks.
const NEXT_UP_DAYS: i64 = 62;
/// A birthday alert that follows the setting fires at 09:00 of the day ("당일 09:00").
const BIRTHDAY_ALERT: i32 = -9 * 60;
/// Days around a range whose holidays a leave bridge may need.
const BRIDGE_MARGIN_DAYS: i64 = 10;
/// How long before its leave day a bridge is announced.
const BRIDGE_ALERT_DAYS: i64 = 21;
/// Words in an all-day title that mean the day is already off.
const LEAVE_WORDS: [&str; 2] = ["연차", "휴가"];
/// How far ahead alerts are computed; the apps keep the nearest ones scheduled and ask again as days pass.
const ALERT_DAYS: i64 = 35;
const WIDGET_ITEMS: usize = 5;
/// An event deleted this soon after it was added is the toast's undo ("되돌리기 5초", m1.md; protocol §14): what the
/// add counted is taken back.
const UNDO_SECONDS: i64 = 5;
/// The third same title on the same weekday brings the repeat hint (kickoff §5.2).
const HINT_USES: i64 = 3;
/// Tints given to a calendar's shift templates in turn (docs/design/m1.md screen 10: 오픈, 미들, 마감).
const TINTS: [i64; 5] = [2, 3, 5, 4, 1];
/// The calendar shifts go to when a template names none (m1.md 2.3, "기본 '알바'").
const SHIFT_CALENDAR: &str = "알바";

#[derive(Serialize)]
struct WidgetSnapshot {
    v: u32,
    day: String,
    today: Vec<WidgetEvent>,
    tomorrow: Vec<WidgetEvent>,
    next_appointment: Option<()>,
}

#[derive(Serialize)]
struct WidgetEvent {
    title: String,
    color: Option<&'static str>,
    all_day: bool,
    start: Option<String>,
    end: Option<String>,
}

/// The visible calendars' occurrences of a range of days with its holidays, birthdays and leave bridges, ready to
/// hand out per day.
struct Days {
    items: Vec<(EventItem, DateTime<FixedOffset>, DateTime<FixedOffset>)>,
    holidays: BTreeMap<NaiveDate, Vec<Holiday>>,
    birthdays: BTreeMap<NaiveDate, Vec<BirthdayItem>>,
    bridges: BTreeMap<NaiveDate, LeaveBridge>,
}

impl Days {
    fn read(store: &Store, first: NaiveDate, last: NaiveDate) -> Result<Self, CoreError> {
        let (from, to) = (midnight(first), midnight(last.succ_opt().ok_or_else(|| invalid("day out of range"))?));
        store.read(|db| {
            let calendars = calendars_by_id(db)?;
            let mut items = Vec::new();
            for (row, occ) in instances(db, from, to, |row| calendars.get(&row.calendar_id).is_some_and(|c| c.visible))?
            {
                let calendar = &calendars[&row.calendar_id];
                items.push((item(&row, &occ, calendar), occ.starts_at, occ.ends_at));
            }
            sort_items(&mut items);
            let mut holidays: BTreeMap<NaiveDate, Vec<Holiday>> = BTreeMap::new();
            for h in holidays_between(db, first, last)? {
                holidays.entry(h.day).or_default().push(Holiday {
                    day: day_text(h.day),
                    name: h.name,
                    substitute: h.substitute,
                });
            }
            let mut birthdays: BTreeMap<NaiveDate, Vec<BirthdayItem>> = BTreeMap::new();
            for (d, b) in birthdays_between(db, first, last)? {
                birthdays.entry(d).or_default().push(b);
            }
            let bridges = if notifications(db)?.leave_suggestions {
                open_bridges(db, first, last)?.iter().map(|b| (b.leave_day, bridge(b))).collect()
            } else {
                BTreeMap::new()
            };
            Ok(Self { items, holidays, birthdays, bridges })
        })
    }

    fn each(&self, first: NaiveDate, last: NaiveDate) -> impl Iterator<Item = NaiveDate> + '_ {
        first.iter_days().take_while(move |d| *d <= last)
    }

    /// The occurrences touching `day`, with their instants, in view order.
    fn on(&self, day: NaiveDate) -> Vec<(EventItem, DateTime<FixedOffset>, DateTime<FixedOffset>)> {
        let (from, to) = (midnight(day), midnight(day.succ_opt().expect("day in range")));
        self.items.iter().filter(|(_, s, e)| *s < to && *e > from).cloned().collect()
    }

    fn items_on(&self, day: NaiveDate) -> Vec<EventItem> {
        self.on(day).into_iter().map(|(item, _, _)| item).collect()
    }

    fn holidays_on(&self, day: NaiveDate) -> Vec<Holiday> {
        self.holidays.get(&day).cloned().unwrap_or_default()
    }

    fn birthdays_on(&self, day: NaiveDate) -> Vec<BirthdayItem> {
        self.birthdays.get(&day).cloned().unwrap_or_default()
    }

    fn bridge_on(&self, day: NaiveDate) -> Option<LeaveBridge> {
        self.bridges.get(&day).cloned()
    }
}

/// The leave bridges whose leave day is in [first, last] and still open. An all-day event on the leave day named
/// like a day off ("연차", "휴가"), which the bridge chip itself fills in, means the leave is taken; any other
/// all-day event (a "약속") does not.
fn open_bridges(db: &Db<'_>, first: NaiveDate, last: NaiveDate) -> Result<Vec<Bridge>, CoreError> {
    // A bridge's run of days off can reach past the range, so its holidays are read with a margin.
    let margin = Duration::days(BRIDGE_MARGIN_DAYS);
    let off: BTreeSet<NaiveDate> =
        holidays_between(db, first - margin, last + margin)?.into_iter().map(|h| h.day).collect();
    let mut open = Vec::new();
    for b in holidays::bridges(&off, first, last) {
        let (from, to) = (midnight(b.leave_day), midnight(b.leave_day + Duration::days(1)));
        let taken = instances(db, from, to, |row| row.all_day)?
            .iter()
            .any(|(row, occ)| LEAVE_WORDS.iter().any(|w| occ.title.as_deref().unwrap_or(&row.title).contains(w)));
        if !taken {
            open.push(b);
        }
    }
    Ok(open)
}

/// How many occurrences the series `row` made from `first` up to `at`, removed ones included as COUNT counts them
/// (RFC 5545), stopping at `cap`. A year at a time, so a long daily series stays under one view's expansion limit.
fn made_before(
    row: &EventRow,
    first: DateTime<FixedOffset>,
    at: DateTime<FixedOffset>,
    cap: u32,
) -> Result<u32, CoreError> {
    let all = EventSpec { exdates: Vec::new(), ..spec(row)? };
    let mut made = 0u32;
    let mut from = first;
    while from < at && made < cap {
        let to = (from + Duration::days(366)).min(at);
        let found = occurrences(&all, from, to).map_err(malformed)?;
        let found = found.iter().filter(|o| o.occurrence_at >= from && o.occurrence_at < to).count();
        made = made.saturating_add(u32::try_from(found).unwrap_or(u32::MAX));
        from = to;
    }
    Ok(made)
}

/// `edited` as the whole series: the removed occurrences move by as much as the edit moved its first start, so a
/// deleted occurrence stays deleted, and they go when the edit stops it repeating.
fn whole_series(row: &EventRow, mut edited: EventRow) -> Result<EventRow, CoreError> {
    let shift = parse_stored(&edited.starts_at)? - parse_stored(&row.starts_at)?;
    edited.exdates = if edited.rrule.is_some() {
        exdates_text(&exdates(row)?.into_iter().map(|d| d + shift).collect::<Vec<_>>())
    } else {
        None
    };
    Ok(edited)
}

fn bridge(b: &Bridge) -> LeaveBridge {
    LeaveBridge {
        leave_day: day_text(b.leave_day),
        first_day: day_text(b.first_day),
        last_day: day_text(b.last_day),
        days_off: b.days_off,
    }
}

pub(crate) fn new_id() -> String {
    uuid::Uuid::now_v7().to_string()
}

/// A trimmed name of 1–40 characters.
pub(crate) fn name(text: &str, what: &str) -> Result<String, CoreError> {
    let name = text.trim();
    if name.is_empty() {
        return Err(invalid(format!("a {what} needs a name")));
    }
    if name.chars().count() > 40 {
        return Err(invalid(format!("a {what} name is at most 40 characters")));
    }
    Ok(name.to_owned())
}

/// The first open makes "개인", so the quick entry always has a calendar to put a chip in.
pub(crate) fn ensure_default_calendar(store: &Store) -> Result<(), CoreError> {
    store.write(|db| {
        if db.calendars()?.iter().any(|c| c.kind == OWN) {
            return Ok(());
        }
        db.put_calendar(&CalendarRow {
            id: new_id(),
            name: "개인".to_owned(),
            color: palette_key(Palette::Tangerine).to_owned(),
            kind: OWN.to_owned(),
            blocks_free: true,
            group_id: None,
            visible: true,
            default_alert: None,
            position: 0,
            device_source_id: None,
        })?;
        Ok::<_, CoreError>(())
    })
}

fn own_calendar(db: &Db<'_>, id: &str) -> Result<CalendarRow, CoreError> {
    let row = db.calendar(id)?.ok_or_else(|| not_found(format!("calendar {id}")))?;
    if row.kind != OWN {
        return Err(invalid("device calendars follow the device and cannot be changed here"));
    }
    Ok(row)
}

fn editable_event(db: &Db<'_>, id: &str) -> Result<EventRow, CoreError> {
    let row = db.event(id)?.ok_or_else(|| not_found(format!("event {id}")))?;
    if row.device_event_id.is_some() {
        return Err(invalid("a device calendar's event is read-only here"));
    }
    Ok(row)
}

/// The row an editor input becomes, checked: a title, a real range and a repeat rule that expands.
fn event_row(id: String, input: &EventInput, now: DateTime<Utc>, origin: &str) -> Result<EventRow, CoreError> {
    let title = input.title.trim();
    if title.is_empty() {
        return Err(invalid("an event needs a title"));
    }
    let (starts_at, ends_at) = range(input.all_day, &input.start, &input.end)?;
    let rrule = input.recurrence.as_ref().map(rule).transpose()?.map(|r| r.to_rrule());
    if let Some(rule) = &rrule {
        let spec = EventSpec {
            starts_at,
            ends_at,
            all_day: input.all_day,
            tz: TZ.to_owned(),
            rrule: Some(rule.clone()),
            exdates: Vec::new(),
            overrides: Vec::new(),
        };
        occurrences(&spec, starts_at, starts_at + Duration::days(1)).map_err(|e| invalid(e.to_string()))?;
    }
    Ok(EventRow {
        id,
        calendar_id: input.calendar_id.clone(),
        title: title.to_owned(),
        starts_at: stored(starts_at),
        ends_at: stored(ends_at),
        all_day: input.all_day,
        tz: TZ.to_owned(),
        rrule,
        exdates: None,
        note: input.note.as_deref().map(str::trim).filter(|n| !n.is_empty()).map(str::to_owned),
        device_event_id: None,
        group_ref: None,
        tentative: false,
        updated_at: stored_utc(now),
        alert: alert_text(&input.alert),
        shift_template_id: None,
        created_at: Some(stored_utc(now)),
        origin: Some(origin.to_owned()),
    })
}

/// Counts a use of the title on its weekday, for the suggestions and the repeat hint, and returns the hint the
/// third use brings, once.
fn count_use(db: &Db<'_>, row: &EventRow, now: DateTime<Utc>) -> Result<Option<RepeatHint>, CoreError> {
    let starts_at = parse_stored(&row.starts_at)?;
    let start_day = starts_at.with_timezone(&geunal_domain::KST).date_naive();
    let weekday = weekday_index(start_day);
    let ends_at = parse_stored(&row.ends_at)?;
    let previous = db.suggestion_stat(&row.title, weekday)?;
    let uses = previous.as_ref().map(|p| p.uses).unwrap_or(0) + 1;
    let offered = previous.as_ref().is_some_and(|p| p.hint_offered);
    let hint = (row.rrule.is_none() && uses >= HINT_USES && !offered)
        .then(|| RepeatHint { weekday: weekday_of(start_day), times: uses as u32 });
    db.put_suggestion_stat(&SuggestionStatRow {
        title: row.title.clone(),
        weekday,
        start_hm: hm(day_time(starts_at).minute),
        end_hm: Some(hm(end_of(false, ends_at).minute)),
        calendar_id: Some(row.calendar_id.clone()),
        all_day: row.all_day,
        uses,
        last_used_at: stored_utc(now),
        hint_offered: offered || hint.is_some(),
    })?;
    Ok(hint)
}

/// Takes back what an add counted when the event goes again right away: the toast's undo.
fn undo_counts(db: &Db<'_>, row: &EventRow, now: DateTime<Utc>) -> Result<(), CoreError> {
    let Some(created) = row.created_at.as_deref().and_then(|c| parse_stored(c).ok()) else { return Ok(()) };
    // The store keeps whole seconds, so the two instants are compared in whole seconds too.
    if now.timestamp() - created.timestamp() > UNDO_SECONDS {
        return Ok(());
    }
    let origin = row.origin.as_deref();
    if !matches!(origin, Some("typed") | Some("suggestion")) {
        return Ok(());
    }
    let start_day = parse_stored(&row.starts_at)?.with_timezone(&geunal_domain::KST).date_naive();
    let weekday = weekday_index(start_day);
    if let Some(stat) = db.suggestion_stat(&row.title, weekday)? {
        if stat.uses <= 1 {
            db.delete_suggestion_stat(&row.title, weekday)?;
        } else {
            // An undone third use takes its repeat hint back too, so the next third use asks again.
            let uses = stat.uses - 1;
            db.put_suggestion_stat(&SuggestionStatRow {
                uses,
                hint_offered: stat.hint_offered && uses >= HINT_USES,
                ..stat
            })?;
        }
    }
    db.bump_alpha(&day_text(kst_day(created.to_utc())), -1, -i64::from(origin == Some("suggestion")))?;
    Ok(())
}

/// The calendar a template without one stamps into: "알바", made the first time.
fn shift_calendar(db: &Db<'_>) -> Result<CalendarRow, CoreError> {
    if let Some(found) = db.calendars()?.into_iter().find(|c| c.kind == OWN && c.name == SHIFT_CALENDAR) {
        return Ok(found);
    }
    let row = CalendarRow {
        id: new_id(),
        name: SHIFT_CALENDAR.to_owned(),
        color: palette_key(Palette::Mint).to_owned(),
        kind: OWN.to_owned(),
        blocks_free: true,
        group_id: None,
        visible: true,
        default_alert: None,
        position: db.next_calendar_position()?,
        device_source_id: None,
    };
    db.put_calendar(&row)?;
    Ok(row)
}

/// The next tint for a calendar's templates, so templates of one calendar stay apart.
fn next_tint(db: &Db<'_>, calendar_id: &str) -> Result<i64, CoreError> {
    let used: Vec<i64> = db
        .shift_templates()?
        .into_iter()
        .filter(|t| t.calendar_id.as_deref() == Some(calendar_id))
        .map(|t| t.tint)
        .collect();
    Ok(TINTS.into_iter().find(|t| !used.contains(t)).unwrap_or(TINTS[used.len() % TINTS.len()]))
}

fn template_view(row: &ShiftTemplateRow, calendars: &HashMap<String, CalendarRow>) -> Result<ShiftTemplate, CoreError> {
    let start_minute = parse_hm(&row.start_hm)?;
    let end_minute = parse_hm(&row.end_hm)?;
    let length =
        if end_minute > start_minute { end_minute - start_minute } else { MINUTES_PER_DAY - start_minute + end_minute };
    let calendar = row.calendar_id.as_ref().and_then(|c| calendars.get(c));
    Ok(ShiftTemplate {
        id: row.id.clone(),
        name: row.name.clone(),
        color: calendar.and_then(|c| palette(&c.color)).or_else(|| palette(&row.color)).unwrap_or(Palette::Mint),
        tint: row.tint.clamp(1, 5) as u8,
        start_minute,
        end_minute,
        calendar_id: row.calendar_id.clone().unwrap_or_default(),
        alert: alert_rule(row.alert.as_deref()),
        length_minutes: length,
    })
}

/// The ordinary event a template stamps on `day`; a shift that ends at or before it starts ends the next day.
fn stamped(template: &ShiftTemplateRow, day: NaiveDate, now: DateTime<Utc>) -> Result<EventRow, CoreError> {
    let shift = DomainShift {
        name: template.name.clone(),
        start_hm: template.start_hm.clone(),
        end_hm: template.end_hm.clone(),
    };
    let (starts_at, ends_at) = shift_event(&shift, day).map_err(|e| invalid(e.to_string()))?;
    Ok(EventRow {
        id: new_id(),
        calendar_id: template.calendar_id.clone().ok_or_else(|| malformed("template without calendar"))?,
        title: template.name.clone(),
        starts_at: stored(starts_at),
        ends_at: stored(ends_at),
        all_day: false,
        tz: TZ.to_owned(),
        rrule: None,
        exdates: None,
        note: None,
        device_event_id: None,
        group_ref: None,
        tentative: false,
        updated_at: stored_utc(now),
        alert: template.alert.clone(),
        shift_template_id: Some(template.id.clone()),
        created_at: Some(stored_utc(now)),
        origin: Some("shift".to_owned()),
    })
}

/// What the quick entry could offer for `day`: the dictionary, this device's history on that weekday, birthdays and
/// long weekends close by, and the shift templates. `rank` then picks the five chips.
fn suggestion_candidates(
    db: &Db<'_>,
    days: &Days,
    day: NaiveDate,
    now: DateTime<Utc>,
) -> Result<Vec<Candidate>, CoreError> {
    let weekday = weekday_index(day);
    let day_off =
        matches!(day.weekday(), chrono::Weekday::Sat | chrono::Weekday::Sun) || !days.holidays_on(day).is_empty();
    let own: HashSet<String> = db.calendars()?.into_iter().filter(|c| c.kind == OWN).map(|c| c.id).collect();
    let history: Vec<SuggestionStatRow> = db.suggestion_stats()?.into_iter().filter(|s| s.weekday == weekday).collect();
    let most = history.iter().map(|s| s.uses).max().unwrap_or(0).max(1) as f64;
    let recent = |s: &SuggestionStatRow| {
        parse_stored(&s.last_used_at)
            .is_ok_and(|at| now.signed_duration_since(at.to_utc()) <= Duration::days(suggest::RECENT_DAYS))
    };
    let mut out = Vec::new();
    for s in &history {
        out.push(Candidate {
            title: s.title.clone(),
            source: suggest::Source::History,
            all_day: s.all_day,
            start_minute: parse_hm(&s.start_hm)?,
            end_minute: s.end_hm.as_deref().map(parse_hm).transpose()?.unwrap_or(0),
            calendar_id: s.calendar_id.clone().filter(|c| own.contains(c)),
            context_fit: 1.0,
            personal_freq: s.uses as f64 / most,
            upcoming: 0.0,
            recency: if recent(s) { 1.0 } else { 0.0 },
        });
    }
    for e in suggest::DICTIONARY {
        let used = history.iter().find(|s| s.title == e.title);
        out.push(Candidate {
            title: e.title.to_owned(),
            source: suggest::Source::Dictionary,
            all_day: e.all_day,
            start_minute: e.start_minute,
            end_minute: e.end_minute,
            calendar_id: None,
            context_fit: if day_off { e.day_off_fit } else { e.workday_fit },
            personal_freq: used.map(|s| s.uses as f64 / most).unwrap_or(0.0),
            upcoming: 0.0,
            recency: if used.is_some_and(recent) { 1.0 } else { 0.0 },
        });
    }
    for row in db.people()?.into_iter().filter(|p| p.suggest) {
        let Some(next) = person_birthdays(&row, day, day + Duration::days(suggest::UPCOMING_DAYS)).into_iter().next()
        else {
            continue;
        };
        out.push(Candidate {
            title: format!("{} 생일 약속", row.name),
            source: suggest::Source::Birthday { person_id: row.id.clone() },
            all_day: false,
            start_minute: 19 * 60,
            end_minute: 21 * 60,
            calendar_id: None,
            context_fit: 0.8,
            personal_freq: 0.0,
            upcoming: suggest::upcoming((next - day).num_days()),
            recency: 0.0,
        });
    }
    let margin = Duration::days(suggest::UPCOMING_DAYS + BRIDGE_MARGIN_DAYS);
    let around = holidays_between(db, day - margin, day + margin)?;
    let off: BTreeSet<NaiveDate> = around.iter().map(|h| h.day).collect();
    if let Some((first, _)) =
        holidays::long_weekends(&off, day, day + Duration::days(suggest::UPCOMING_DAYS)).into_iter().next()
    {
        let inside = first <= day;
        out.push(Candidate {
            title: "연휴 여행".to_owned(),
            source: suggest::Source::Holiday,
            all_day: true,
            start_minute: 0,
            end_minute: 0,
            calendar_id: None,
            // The day before a long weekend puts the trip first ("연휴 앞이면 '연휴 여행'이 먼저", kickoff §5.2).
            context_fit: if inside { 1.0 } else { 0.8 },
            personal_freq: 0.0,
            upcoming: if inside { 1.0 } else { suggest::upcoming((first - day).num_days()) },
            recency: 0.0,
        });
    }
    if around.iter().any(|h| h.day == day && h.substitute) {
        out.push(Candidate {
            title: "대체공휴일 나들이".to_owned(),
            source: suggest::Source::Holiday,
            all_day: true,
            start_minute: 0,
            end_minute: 0,
            calendar_id: None,
            context_fit: 1.0,
            personal_freq: 0.0,
            upcoming: 1.0,
            recency: 0.0,
        });
    }
    for t in db.shift_templates()? {
        out.push(Candidate {
            title: t.name.clone(),
            source: suggest::Source::Shift { template_id: t.id.clone() },
            all_day: false,
            start_minute: parse_hm(&t.start_hm)?,
            end_minute: parse_hm(&t.end_hm)?,
            calendar_id: t.calendar_id.clone().filter(|c| own.contains(c)),
            // A template the user made fits any day they might work, so it ranks with the dictionary's good fits
            // instead of falling below all eight of them.
            context_fit: 0.8,
            personal_freq: 0.0,
            upcoming: 0.0,
            recency: 0.0,
        });
    }
    Ok(out)
}

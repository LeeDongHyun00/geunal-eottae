//! The M1 calendar surface (docs/design/m1.md screens 3–10, 14–16, 18–19): calendars, events, the month, week and
//! list views, the next-up bar, the widget snapshot, input suggestions, shift templates, device calendars and the
//! local alerts the apps schedule.
//!
//! Every date and time crossing this boundary is Korean local time (Asia/Seoul), the clock every rule runs on
//! (kickoff §5): a day is `"YYYY-MM-DD"`, a month `"YYYY-MM"`, and a time of day the minutes after midnight. The apps
//! never convert time zones for the calendar; they format what they get. Instants (`SystemTime`, Swift `Date`, Kotlin
//! `Instant`) appear only where a moment is meant: when an alert fires, when a backup was made.

use std::time::SystemTime;

use crate::{Core, CoreError, not_yet};

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
        Err(not_yet("calendars"))
    }

    /// Creates or updates an own calendar and returns its id.
    pub fn save_calendar(&self, input: CalendarInput) -> Result<String, CoreError> {
        let _ = input;
        Err(not_yet("save_calendar"))
    }

    pub fn set_calendar_visible(&self, calendar_id: String, visible: bool) -> Result<(), CoreError> {
        let _ = (calendar_id, visible);
        Err(not_yet("set_calendar_visible"))
    }

    /// Deletes an own calendar with its events and shift templates. The last own calendar cannot go (`Invalid`).
    pub fn delete_calendar(&self, calendar_id: String) -> Result<(), CoreError> {
        let _ = calendar_id;
        Err(not_yet("delete_calendar"))
    }

    // ----- events (screens 5, 6, 9) -----

    /// Saves a new event. `from_suggestion` marks a tapped suggestion chip, which the alpha statistics count
    /// (protocol §14). Undo is `delete_event(event_id, None, ThisAndFollowing)`.
    pub fn add_event(&self, input: EventInput, from_suggestion: bool) -> Result<AddedEvent, CoreError> {
        let _ = (input, from_suggestion);
        Err(not_yet("add_event"))
    }

    /// The event or occurrence the editor opens.
    pub fn event(&self, event_id: String, occurrence: Option<String>) -> Result<EventDetail, CoreError> {
        let _ = (event_id, occurrence);
        Err(not_yet("event"))
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
        let _ = (event_id, occurrence, scope, input);
        Err(not_yet("update_event"))
    }

    pub fn delete_event(
        &self,
        event_id: String,
        occurrence: Option<String>,
        scope: EditScope,
    ) -> Result<(), CoreError> {
        let _ = (event_id, occurrence, scope);
        Err(not_yet("delete_event"))
    }

    // ----- views (screens 4, 7, 8, 14) -----

    /// The month grid for `month` ("YYYY-MM"): whole weeks from Sunday, 35 or 42 cells.
    pub fn calendar_month(&self, month: String) -> Result<Vec<DayCell>, CoreError> {
        let _ = month;
        Err(not_yet("calendar_month"))
    }

    /// Seven days from `start_day`; the app passes the Sunday of the week it shows.
    pub fn calendar_week(&self, start_day: String) -> Result<Vec<WeekDay>, CoreError> {
        let _ = start_day;
        Err(not_yet("calendar_week"))
    }

    /// The days from `from_day` through the next `days` days that have something on them. The list view pages by
    /// asking for the next range; an empty answer for 28 days is its empty state.
    pub fn calendar_agenda(&self, from_day: String, days: u16) -> Result<Vec<AgendaDay>, CoreError> {
        let _ = (from_day, days);
        Err(not_yet("calendar_agenda"))
    }

    /// What is on now or next, nearest first: events that have not ended and birthdays from today.
    pub fn next_up(&self, count: u16) -> Result<Vec<UpNext>, CoreError> {
        let _ = count;
        Err(not_yet("next_up"))
    }

    /// The JSON the widgets read (docs/parts/rust-core.md §3, "위젯 스냅샷"). The app writes it to the shared
    /// container whenever the calendar changes and at midnight; the widget extension never opens the core.
    pub fn widget_snapshot(&self) -> Result<String, CoreError> {
        Err(not_yet("widget_snapshot"))
    }

    // ----- suggestions (screen 5) -----

    /// Up to five chips for `day`, best first, without any that overlap what is already on that day (kickoff §5.2).
    /// Nothing about them leaves the device, and an untapped chip is never saved.
    pub fn suggestions(&self, day: String) -> Result<Vec<Suggestion>, CoreError> {
        let _ = day;
        Err(not_yet("suggestions"))
    }

    // ----- shift templates (screens 10, 18) -----

    pub fn shift_templates(&self) -> Result<Vec<ShiftTemplate>, CoreError> {
        Err(not_yet("shift_templates"))
    }

    /// Creates or updates a template and returns its id. Shifts stamped before stay as they are.
    pub fn save_shift_template(&self, input: ShiftTemplateInput) -> Result<String, CoreError> {
        let _ = input;
        Err(not_yet("save_shift_template"))
    }

    /// Deletes a template; the shifts already stamped stay as ordinary events.
    pub fn delete_shift_template(&self, template_id: String) -> Result<(), CoreError> {
        let _ = template_id;
        Err(not_yet("delete_shift_template"))
    }

    /// The days of `month` ("YYYY-MM") that have shifts, for the stamp screen.
    pub fn shift_days(&self, month: String) -> Result<Vec<ShiftDay>, CoreError> {
        let _ = month;
        Err(not_yet("shift_days"))
    }

    /// The stamp screen's "완료": each listed day ends up with exactly these templates' shifts (an empty list clears
    /// it). The screen keeps its draft until then, so "취소" changes nothing.
    pub fn set_shift_days(&self, days: Vec<ShiftDay>) -> Result<(), CoreError> {
        let _ = days;
        Err(not_yet("set_shift_days"))
    }

    // ----- device calendars (screens 3, 15) -----

    /// Replaces the mirror with these calendars, the ones the user ticked, and their events. An empty list turns
    /// the mirror off. Titles stay on the device: no backup, no group, no server ever sees them (kickoff §5.1).
    pub fn mirror_device_calendars(&self, calendars: Vec<DeviceCalendarInput>) -> Result<DeviceMirror, CoreError> {
        let _ = calendars;
        Err(not_yet("mirror_device_calendars"))
    }

    // ----- alerts (screen 19) -----

    pub fn notification_settings(&self) -> Result<NotificationSettings, CoreError> {
        Err(not_yet("notification_settings"))
    }

    pub fn set_notification_settings(&self, settings: NotificationSettings) -> Result<(), CoreError> {
        let _ = settings;
        Err(not_yet("set_notification_settings"))
    }

    /// The next `limit` alerts from now, soonest first: events, birthdays and leave suggestions, after the
    /// settings and each event's own rule.
    pub fn scheduled_alerts(&self, limit: u32) -> Result<Vec<ScheduledAlert>, CoreError> {
        let _ = limit;
        Err(not_yet("scheduled_alerts"))
    }

    /// Own events as an iCalendar file (screen 22: what stays on this device can be exported). Device calendars are
    /// left out; they are the device's already.
    pub fn export_ics(&self) -> Result<String, CoreError> {
        Err(not_yet("export_ics"))
    }
}

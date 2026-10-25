//! What the calendar functions share: turning the FFI's Korean days and minutes into the instants the store keeps,
//! expanding stored events into the occurrences a view shows, and resolving alerts. Nothing here is exported.

use std::collections::{HashMap, HashSet};
use std::time::SystemTime;

use chrono::{DateTime, Datelike, Duration, FixedOffset, NaiveDate, NaiveTime, SecondsFormat, TimeZone, Timelike, Utc};
use geunal_domain::calendar::{EventSpec, Occurrence, occurrences};
use geunal_domain::holidays::{self, Holiday};
use geunal_domain::lunar;
use geunal_domain::recurrence::{Freq, Rule, WEEK};
use geunal_domain::time::KST;
use geunal_store::{CalendarRow, Db, EventRow, PersonRow};
use serde::{Deserialize, Serialize};

use crate::{
    AlertRule, Birthday, BirthdayCalendar, BirthdayItem, CalendarKind, CoreError, DayTime, EventItem, EventSource,
    Frequency, NotificationSettings, Palette, Recurrence, Weekday,
};

pub(crate) const TZ: &str = "Asia/Seoul";
pub(crate) const MINUTES_PER_DAY: u16 = 24 * 60;
/// The kind of a calendar made in the app; the M0 schema calls it `personal`.
pub(crate) const OWN: &str = "personal";
pub(crate) const DEVICE: &str = "device_mirror";
/// The color a mirrored device calendar carries in the store.
pub(crate) const DEVICE_COLOR: &str = "device";

pub(crate) fn invalid(reason: impl Into<String>) -> CoreError {
    CoreError::Invalid { reason: reason.into() }
}

pub(crate) fn not_found(what: impl Into<String>) -> CoreError {
    CoreError::NotFound { what: what.into() }
}

/// A row the store holds that no longer parses: the store's fault, not the caller's.
pub(crate) fn malformed(what: impl std::fmt::Display) -> CoreError {
    CoreError::Store { reason: format!("malformed row: {what}") }
}

// ----- days, minutes and instants -----

pub(crate) fn day(text: &str) -> Result<NaiveDate, CoreError> {
    NaiveDate::parse_from_str(text, "%Y-%m-%d").map_err(|_| invalid(format!("day {text:?} is not YYYY-MM-DD")))
}

pub(crate) fn day_text(day: NaiveDate) -> String {
    day.format("%Y-%m-%d").to_string()
}

/// The first day of a `"YYYY-MM"` month.
pub(crate) fn month(text: &str) -> Result<NaiveDate, CoreError> {
    NaiveDate::parse_from_str(&format!("{text}-01"), "%Y-%m-%d")
        .map_err(|_| invalid(format!("month {text:?} is not YYYY-MM")))
}

pub(crate) fn next_month(first: NaiveDate) -> NaiveDate {
    first.checked_add_months(chrono::Months::new(1)).expect("months stay in range")
}

/// 00:00 of `day` in Korea.
pub(crate) fn midnight(day: NaiveDate) -> DateTime<FixedOffset> {
    KST.from_local_datetime(&day.and_time(NaiveTime::MIN)).single().expect("+09:00 has no gaps")
}

pub(crate) fn hm(minute: u16) -> String {
    format!("{:02}:{:02}", minute / 60, minute % 60)
}

pub(crate) fn parse_hm(text: &str) -> Result<u16, CoreError> {
    let time = NaiveTime::parse_from_str(text, "%H:%M").map_err(malformed)?;
    Ok((time.hour() * 60 + time.minute()) as u16)
}

pub(crate) fn instant(at: &DayTime) -> Result<DateTime<FixedOffset>, CoreError> {
    if at.minute >= MINUTES_PER_DAY {
        return Err(invalid(format!("minute {} is past the day", at.minute)));
    }
    Ok(midnight(day(&at.day)?) + Duration::minutes(i64::from(at.minute)))
}

pub(crate) fn day_time(at: DateTime<FixedOffset>) -> DayTime {
    let local = at.with_timezone(&KST);
    let minute = local.hour() * 60 + local.minute();
    DayTime { day: day_text(local.date_naive()), minute: minute as u16 }
}

/// How the store writes an instant: RFC 3339 at +09:00, so instants compare as text.
pub(crate) fn stored(at: DateTime<FixedOffset>) -> String {
    at.with_timezone(&KST).to_rfc3339_opts(SecondsFormat::Secs, false)
}

pub(crate) fn stored_utc(at: DateTime<Utc>) -> String {
    stored(at.with_timezone(&KST))
}

pub(crate) fn parse_stored(text: &str) -> Result<DateTime<FixedOffset>, CoreError> {
    DateTime::parse_from_rfc3339(text).map_err(|_| malformed(format!("instant {text:?}")))
}

pub(crate) fn system_time(at: DateTime<FixedOffset>) -> SystemTime {
    SystemTime::from(at.to_utc())
}

pub(crate) fn kst_day(at: DateTime<Utc>) -> NaiveDate {
    at.with_timezone(&KST).date_naive()
}

/// The instants an event occupies: an all-day event runs from 00:00 of its first day to 00:00 after its last.
pub(crate) fn range(
    all_day: bool,
    start: &DayTime,
    end: &DayTime,
) -> Result<(DateTime<FixedOffset>, DateTime<FixedOffset>), CoreError> {
    if all_day {
        let (first, last) = (day(&start.day)?, day(&end.day)?);
        if last < first {
            return Err(invalid("an all-day event ends before it starts"));
        }
        Ok((midnight(first), midnight(last.succ_opt().ok_or_else(|| invalid("day out of range"))?)))
    } else {
        let (starts_at, ends_at) = (instant(start)?, instant(end)?);
        if ends_at <= starts_at {
            return Err(invalid("the event ends at or before its start"));
        }
        Ok((starts_at, ends_at))
    }
}

/// The FFI's end of an event: the last day for an all-day event, the end instant otherwise.
pub(crate) fn end_of(all_day: bool, ends_at: DateTime<FixedOffset>) -> DayTime {
    if all_day {
        let last =
            ends_at.with_timezone(&KST).date_naive().pred_opt().expect("an all-day event has a day before its end");
        DayTime { day: day_text(last), minute: 0 }
    } else {
        day_time(ends_at)
    }
}

// ----- occurrences -----

/// The key of a repeating event's occurrence: its original start, local. Opaque to the apps.
pub(crate) fn occurrence_key(at: DateTime<FixedOffset>, all_day: bool) -> String {
    let local = at.with_timezone(&KST);
    if all_day { local.format("%Y-%m-%d").to_string() } else { local.format("%Y-%m-%dT%H:%M").to_string() }
}

pub(crate) fn occurrence_at(key: &str) -> Result<DateTime<FixedOffset>, CoreError> {
    if let Ok(at) = chrono::NaiveDateTime::parse_from_str(key, "%Y-%m-%dT%H:%M") {
        return Ok(KST.from_local_datetime(&at).single().expect("+09:00 has no gaps"));
    }
    Ok(midnight(day(key).map_err(|_| invalid(format!("occurrence {key:?}")))?))
}

pub(crate) fn exdates(row: &EventRow) -> Result<Vec<DateTime<FixedOffset>>, CoreError> {
    let Some(text) = &row.exdates else { return Ok(Vec::new()) };
    let list: Vec<String> = serde_json::from_str(text).map_err(malformed)?;
    list.iter().map(|t| parse_stored(t)).collect()
}

pub(crate) fn exdates_text(list: &[DateTime<FixedOffset>]) -> Option<String> {
    if list.is_empty() {
        return None;
    }
    let text: Vec<String> = list.iter().map(|at| stored(*at)).collect();
    Some(serde_json::to_string(&text).expect("strings serialize"))
}

pub(crate) fn spec(row: &EventRow) -> Result<EventSpec, CoreError> {
    Ok(EventSpec {
        starts_at: parse_stored(&row.starts_at)?,
        ends_at: parse_stored(&row.ends_at)?,
        all_day: row.all_day,
        tz: row.tz.clone(),
        rrule: row.rrule.clone(),
        exdates: exdates(row)?,
        overrides: Vec::new(),
    })
}

/// Occurrences of the events that may show between `from` and `to`, with their rows, for the calendars `keep`
/// accepts. An event whose stored rule no longer expands is left out instead of failing the whole view.
pub(crate) fn instances(
    db: &Db<'_>,
    from: DateTime<FixedOffset>,
    to: DateTime<FixedOffset>,
    keep: impl Fn(&EventRow) -> bool,
) -> Result<Vec<(EventRow, Occurrence)>, CoreError> {
    let mut out = Vec::new();
    for row in db.events_between(&stored(from), &stored(to))? {
        if !keep(&row) {
            continue;
        }
        let Ok(spec) = spec(&row) else { continue };
        let Ok(found) = occurrences(&spec, from, to) else { continue };
        out.extend(found.into_iter().map(|o| (row.clone(), o)));
    }
    Ok(out)
}

pub(crate) fn calendars_by_id(db: &Db<'_>) -> Result<HashMap<String, CalendarRow>, CoreError> {
    Ok(db.calendars()?.into_iter().map(|c| (c.id.clone(), c)).collect())
}

pub(crate) fn source(row: &EventRow) -> EventSource {
    if row.device_event_id.is_some() {
        EventSource::Device
    } else if let Some(template_id) = &row.shift_template_id {
        EventSource::Shift { template_id: template_id.clone() }
    } else {
        EventSource::Own
    }
}

pub(crate) fn item(row: &EventRow, occurrence: &Occurrence, calendar: &CalendarRow) -> EventItem {
    EventItem {
        event_id: row.id.clone(),
        occurrence: row.rrule.as_ref().map(|_| occurrence_key(occurrence.occurrence_at, row.all_day)),
        calendar_id: calendar.id.clone(),
        calendar_name: calendar.name.clone(),
        color: palette(&calendar.color),
        title: occurrence.title.clone().unwrap_or_else(|| row.title.clone()),
        all_day: row.all_day,
        start: day_time(occurrence.starts_at),
        end: end_of(row.all_day, occurrence.ends_at),
        soft: !calendar.blocks_free,
        source: source(row),
        recurrence: row.rrule.as_deref().and_then(Rule::parse).map(|r| recurrence(&r)),
    }
}

/// The view order inside a day: all-day first, then by start, end and title.
pub(crate) fn sort_items(items: &mut [(EventItem, DateTime<FixedOffset>, DateTime<FixedOffset>)]) {
    items.sort_by(|a, b| {
        (!a.0.all_day, a.1, a.2, &a.0.title, &a.0.event_id).cmp(&(!b.0.all_day, b.1, b.2, &b.0.title, &b.0.event_id))
    });
}

// ----- palette, weekdays, rules and alerts -----

const PALETTE: [(Palette, &str); 12] = [
    (Palette::Tomato, "tomato"),
    (Palette::Tangerine, "tangerine"),
    (Palette::Mustard, "mustard"),
    (Palette::Olive, "olive"),
    (Palette::Forest, "forest"),
    (Palette::Mint, "mint"),
    (Palette::Sky, "sky"),
    (Palette::Sea, "sea"),
    (Palette::Grape, "grape"),
    (Palette::Plum, "plum"),
    (Palette::Blossom, "blossom"),
    (Palette::Cocoa, "cocoa"),
];

/// The tokens.json key of a palette color.
pub(crate) fn palette_key(color: Palette) -> &'static str {
    PALETTE.iter().find(|(p, _)| *p == color).map(|(_, k)| *k).expect("every color has a key")
}

/// `None` for the device color or anything the palette does not have.
pub(crate) fn palette(key: &str) -> Option<Palette> {
    PALETTE.iter().find(|(_, k)| *k == key).map(|(p, _)| *p)
}

pub(crate) fn kind(row: &CalendarRow) -> CalendarKind {
    if row.kind == DEVICE { CalendarKind::Device } else { CalendarKind::Own }
}

pub(crate) fn chrono_weekday(day: Weekday) -> chrono::Weekday {
    WEEK[day as usize]
}

pub(crate) fn weekday(day: chrono::Weekday) -> Weekday {
    const ORDER: [Weekday; 7] = [
        Weekday::Sunday,
        Weekday::Monday,
        Weekday::Tuesday,
        Weekday::Wednesday,
        Weekday::Thursday,
        Weekday::Friday,
        Weekday::Saturday,
    ];
    ORDER[day.num_days_from_sunday() as usize]
}

pub(crate) fn weekday_of(day: NaiveDate) -> Weekday {
    weekday(day.weekday())
}

pub(crate) fn rule(recurrence: &Recurrence) -> Result<Rule, CoreError> {
    if recurrence.interval == 0 {
        return Err(invalid("a recurrence repeats every 1 or more"));
    }
    if recurrence.count == Some(0) {
        return Err(invalid("a recurrence count is 1 or more"));
    }
    let freq = match recurrence.frequency {
        Frequency::Daily => Freq::Daily,
        Frequency::Weekly => Freq::Weekly,
        Frequency::Monthly => Freq::Monthly,
        Frequency::Yearly => Freq::Yearly,
    };
    let by_day = if freq == Freq::Weekly {
        recurrence.weekdays.iter().map(|d| chrono_weekday(*d)).collect()
    } else {
        Vec::new()
    };
    Ok(Rule {
        freq,
        interval: recurrence.interval,
        by_day,
        until: recurrence.until.as_deref().map(day).transpose()?,
        count: recurrence.count,
    })
}

pub(crate) fn recurrence(rule: &Rule) -> Recurrence {
    Recurrence {
        frequency: match rule.freq {
            Freq::Daily => Frequency::Daily,
            Freq::Weekly => Frequency::Weekly,
            Freq::Monthly => Frequency::Monthly,
            Freq::Yearly => Frequency::Yearly,
        },
        interval: rule.interval,
        weekdays: rule.by_day.iter().map(|d| weekday(*d)).collect(),
        until: rule.until.map(day_text),
        count: rule.count,
    }
}

/// How the store writes an alert rule: no value for `Inherit`, `off`, or minutes before.
pub(crate) fn alert_text(rule: &AlertRule) -> Option<String> {
    match rule {
        AlertRule::Inherit => None,
        AlertRule::Off => Some("off".to_owned()),
        AlertRule::Before { minutes } => Some(minutes.to_string()),
    }
}

pub(crate) fn alert_rule(text: Option<&str>) -> AlertRule {
    match text {
        None => AlertRule::Inherit,
        Some("off") => AlertRule::Off,
        Some(minutes) => minutes.parse().map(|minutes| AlertRule::Before { minutes }).unwrap_or(AlertRule::Inherit),
    }
}

/// The alert that applies to an event, in minutes before its start: its own rule, then (for timed events) its
/// calendar's, then the app setting. Device calendars keep their alerts in the device's own calendar app, so the core
/// never alerts for them a second time.
pub(crate) fn effective_alert(row: &EventRow, calendar: &CalendarRow, settings: &NotificationSettings) -> Option<i32> {
    if row.device_event_id.is_some() {
        return None;
    }
    let resolve = |rule: AlertRule| match rule {
        AlertRule::Inherit => Err(()),
        AlertRule::Off => Ok(None),
        AlertRule::Before { minutes } => Ok(Some(minutes)),
    };
    if let Ok(found) = resolve(alert_rule(row.alert.as_deref())) {
        return found;
    }
    if row.all_day {
        // A calendar's "10분 전" is about timed events; an all-day one takes the all-day setting ("전날 18:00").
        return settings.all_day_alert;
    }
    resolve(alert_rule(calendar.default_alert.as_deref())).unwrap_or(settings.event_alert)
}

// ----- holidays and birthdays -----

/// The holidays of the days [first, last]: a year the server sent in full comes from the store, any other year from
/// the table built into the app (protocol §14).
pub(crate) fn holidays_between(db: &Db<'_>, first: NaiveDate, last: NaiveDate) -> Result<Vec<Holiday>, CoreError> {
    let from_server: HashSet<i64> = db.holiday_years()?.into_iter().collect();
    let mut out = Vec::new();
    for year in first.year()..=last.year() {
        if from_server.contains(&i64::from(year)) {
            let (from, to) = (first.max(jan_1(year)), last.min(dec_31(year)));
            for row in db.holidays_between(&day_text(from), &day_text(to))? {
                out.push(Holiday { day: day(&row.day)?, name: row.name, substitute: row.substitute });
            }
        } else {
            out.extend(holidays::built_in(year).into_iter().filter(|h| h.day >= first && h.day <= last));
        }
    }
    out.sort();
    Ok(out)
}

fn jan_1(year: i32) -> NaiveDate {
    NaiveDate::from_ymd_opt(year, 1, 1).expect("a year has a first day")
}

fn dec_31(year: i32) -> NaiveDate {
    NaiveDate::from_ymd_opt(year, 12, 31).expect("a year has a last day")
}

/// A person's birthday as stored: month and day on the calendar `is_lunar` names.
pub(crate) fn stored_birthday(row: &PersonRow) -> Option<(u8, u8)> {
    let (m, d) = row.birthday_md.as_deref()?.split_once('-')?;
    Some((m.parse().ok()?, d.parse().ok()?))
}

pub(crate) fn birthday_of(row: &PersonRow) -> Option<Birthday> {
    let (month, day) = stored_birthday(row)?;
    Some(Birthday {
        month,
        day,
        year: row.birth_year.map(|y| y as i32),
        calendar: if row.is_lunar {
            BirthdayCalendar::Lunar { leap_month: row.leap_month }
        } else {
            BirthdayCalendar::Solar
        },
    })
}

/// The solar days in [first, last] the person's birthday falls on.
pub(crate) fn person_birthdays(row: &PersonRow, first: NaiveDate, last: NaiveDate) -> Vec<NaiveDate> {
    let Some((month, day)) = stored_birthday(row) else { return Vec::new() };
    lunar::birthdays_between(month, day, row.is_lunar, row.leap_month, first, last)
}

/// Every birthday in [first, last], by day and name.
pub(crate) fn birthdays_between(
    db: &Db<'_>,
    first: NaiveDate,
    last: NaiveDate,
) -> Result<Vec<(NaiveDate, BirthdayItem)>, CoreError> {
    let mut out = Vec::new();
    for row in db.people()? {
        for d in person_birthdays(&row, first, last) {
            out.push((
                d,
                BirthdayItem {
                    person_id: row.id.clone(),
                    name: row.name.clone(),
                    day: day_text(d),
                    lunar: row.is_lunar,
                },
            ));
        }
    }
    out.sort_by(|a, b| (a.0, &a.1.name, &a.1.person_id).cmp(&(b.0, &b.1.name, &b.1.person_id)));
    Ok(out)
}

/// The calendar a suggestion or a birthday chip goes to: the first own calendar.
pub(crate) fn default_calendar(db: &Db<'_>) -> Result<CalendarRow, CoreError> {
    db.calendars()?.into_iter().find(|c| c.kind == OWN).ok_or_else(|| malformed("no own calendar"))
}

// ----- settings -----

const NOTIFICATIONS: &str = "notifications";
const CHANGED_AT: &str = "changed_at";

#[derive(Serialize, Deserialize)]
struct StoredNotifications {
    event_alert: Option<i32>,
    all_day_alert: Option<i32>,
    next_up_bar: bool,
    birthday_alerts: bool,
    leave_suggestions: bool,
}

pub(crate) fn default_notifications() -> NotificationSettings {
    NotificationSettings {
        event_alert: Some(10),
        all_day_alert: Some(360),
        next_up_bar: true,
        birthday_alerts: true,
        leave_suggestions: true,
    }
}

pub(crate) fn notifications(db: &Db<'_>) -> Result<NotificationSettings, CoreError> {
    let Some(text) = db.setting(NOTIFICATIONS)? else { return Ok(default_notifications()) };
    let s: StoredNotifications = serde_json::from_str(&text).map_err(malformed)?;
    Ok(NotificationSettings {
        event_alert: s.event_alert,
        all_day_alert: s.all_day_alert,
        next_up_bar: s.next_up_bar,
        birthday_alerts: s.birthday_alerts,
        leave_suggestions: s.leave_suggestions,
    })
}

pub(crate) fn set_notifications(db: &Db<'_>, s: &NotificationSettings) -> Result<(), CoreError> {
    let stored = StoredNotifications {
        event_alert: s.event_alert,
        all_day_alert: s.all_day_alert,
        next_up_bar: s.next_up_bar,
        birthday_alerts: s.birthday_alerts,
        leave_suggestions: s.leave_suggestions,
    };
    db.set_setting(NOTIFICATIONS, &serde_json::to_string(&stored).expect("settings serialize"))?;
    Ok(())
}

/// Marks that something the backup carries changed, so the daily automatic backup knows to run.
pub(crate) fn mark_changed(db: &Db<'_>, now: DateTime<Utc>) -> Result<(), CoreError> {
    db.set_setting(CHANGED_AT, &stored_utc(now))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_every_instant_at_the_korean_offset_so_they_compare_as_text() {
        let at = instant(&DayTime { day: "2026-10-30".into(), minute: 17 * 60 }).unwrap();
        assert_eq!(stored(at), "2026-10-30T17:00:00+09:00");
        assert_eq!(
            day_time(parse_stored("2026-10-30T08:00:00Z").unwrap()),
            DayTime { day: "2026-10-30".into(), minute: 1020 }
        );
    }

    #[test]
    fn an_all_day_event_runs_through_its_last_day() {
        let d = |day: &str| DayTime { day: day.into(), minute: 0 };
        let (s, e) = range(true, &d("2026-10-30"), &d("2026-10-31")).unwrap();
        assert_eq!((stored(s), stored(e)), ("2026-10-30T00:00:00+09:00".into(), "2026-11-01T00:00:00+09:00".into()));
        assert_eq!(end_of(true, e), d("2026-10-31"));
        assert!(range(true, &d("2026-10-30"), &d("2026-10-29")).is_err());
        assert!(
            range(
                false,
                &DayTime { day: "2026-10-30".into(), minute: 600 },
                &DayTime { day: "2026-10-30".into(), minute: 600 }
            )
            .is_err()
        );
    }

    #[test]
    fn round_trips_occurrence_keys() {
        let at = instant(&DayTime { day: "2026-11-06".into(), minute: 9 * 60 }).unwrap();
        assert_eq!(occurrence_key(at, false), "2026-11-06T09:00");
        assert_eq!(occurrence_at("2026-11-06T09:00").unwrap(), at);
        assert_eq!(occurrence_at("2026-11-06").unwrap(), midnight(day("2026-11-06").unwrap()));
    }
}

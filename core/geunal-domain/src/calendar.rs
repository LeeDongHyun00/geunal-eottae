//! Expanding the `events` and `event_overrides` rows (rust-core §4) into the instances a calendar shows and the busy
//! ranges the availability bitmap is built from (kickoff §5.1, rust-core §6). Everything here is a pure function of its
//! arguments so the two apps draw the same instances from the same rows.

use std::collections::{BTreeMap, BTreeSet};

use crate::availability::Availability;
use crate::recurrence::Rule;
use crate::time::{KST, SLOT_MINUTES};
use chrono::{DateTime, Duration, FixedOffset, NaiveDate, NaiveTime, TimeZone};

/// The most instances one expansion may yield inside a window. 1000 is far above what a daily rule produces in the
/// eight-week window, and a view that asks for years of a daily rule gets an error instead of a long pause.
pub const MAX_OCCURRENCES: u16 = 1000;

/// The zone every stored rule repeats in. The app writes no other, and Korea has had no daylight saving since 1988,
/// so the fixed +09:00 offset is exact (`time.rs`). A rule in another zone is refused rather than expanded on the
/// wrong clock.
pub const RULE_ZONE: &str = "Asia/Seoul";

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CalendarError {
    #[error("unknown IANA time zone {0:?}")]
    UnknownTimeZone(String),
    #[error("invalid RRULE: {0}")]
    InvalidRule(String),
    #[error("event ends before or when it starts")]
    EndsBeforeStart,
    #[error("recurrence expands to more than {0} occurrences in the window")]
    TooManyOccurrences(u16),
    #[error("invalid time of day {0:?}: expected HH:MM")]
    InvalidTimeOfDay(String),
}

/// An `events` row as the expansion reads it. `tz` is the zone the rule repeats in, always [`RULE_ZONE`], so "every
/// Tuesday at 19:00" stays at 19:00 in Korea; `starts_at` is the first instance (the RFC 5545 DTSTART).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventSpec {
    pub starts_at: DateTime<FixedOffset>,
    pub ends_at: DateTime<FixedOffset>,
    pub all_day: bool,
    pub tz: String,
    /// RFC 5545 recurrence rule without the `RRULE:` prefix, e.g. `FREQ=WEEKLY;BYDAY=TU,TH`.
    pub rrule: Option<String>,
    /// Instances to leave out, identified by their original start instant.
    pub exdates: Vec<DateTime<FixedOffset>>,
    pub overrides: Vec<Override>,
}

/// One `event_overrides` row: a single instance moved, renamed or cancelled. `occurrence_at` is the instance's
/// original start, which is how the row is keyed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Override {
    pub occurrence_at: DateTime<FixedOffset>,
    pub starts_at: Option<DateTime<FixedOffset>>,
    pub ends_at: Option<DateTime<FixedOffset>>,
    pub title: Option<String>,
    pub cancelled: bool,
}

/// One instance of an event as the calendar shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Occurrence {
    /// The original instance start: the key an override for this instance carries, even after a move.
    pub occurrence_at: DateTime<FixedOffset>,
    pub starts_at: DateTime<FixedOffset>,
    pub ends_at: DateTime<FixedOffset>,
    /// Set only when an override renamed this instance; otherwise the event's own title applies.
    pub title: Option<String>,
}

/// Expands `event` into the instances overlapping [from, to), sorted by start. Each instance keeps the event's
/// duration, `exdates` drop instances, and overrides cancel, move or rename them. An override may move an instance
/// into the window from outside it, so moved instances are checked against the rule individually.
pub fn occurrences(
    event: &EventSpec,
    from: DateTime<FixedOffset>,
    to: DateTime<FixedOffset>,
) -> Result<Vec<Occurrence>, CalendarError> {
    let duration = event.ends_at.signed_duration_since(event.starts_at);
    if duration <= Duration::zero() {
        return Err(CalendarError::EndsBeforeStart);
    }
    let overrides: BTreeMap<i64, &Override> =
        event.overrides.iter().map(|o| (o.occurrence_at.timestamp(), o)).collect();
    let exdates: BTreeSet<i64> = event.exdates.iter().map(DateTime::timestamp).collect();
    // Overrides that may land inside the window although their original instance does not. Overlap is judged on the
    // moved time, falling back to the original time and duration for the side the override leaves alone.
    let moved_in: Vec<&Override> = event
        .overrides
        .iter()
        .filter(|o| !o.cancelled && (o.starts_at.is_some() || o.ends_at.is_some()))
        .filter(|o| {
            let starts = o.starts_at.unwrap_or(o.occurrence_at);
            let ends = o.ends_at.unwrap_or(starts + duration);
            starts < to && ends > from
        })
        .collect();

    let mut instances: Vec<DateTime<FixedOffset>> = match &event.rrule {
        None => {
            let mut v = vec![event.starts_at];
            v.extend(moved_in.iter().map(|o| o.occurrence_at).filter(|&at| at == event.starts_at));
            v
        }
        Some(text) => {
            let rule = rule_of(event, text)?;
            // An instance overlaps the window when it starts after `from - duration`; both bounds are inclusive in
            // `starts`, and the exact overlap test below removes the edge cases.
            let mut v = expand(&rule, event.starts_at, from - duration, to)?;
            for o in &moved_in {
                let at = o.occurrence_at;
                if (at < from - duration || at > to) && is_instance(&rule, event.starts_at, at)? {
                    v.push(at);
                }
            }
            v
        }
    };
    instances.sort();
    instances.dedup();

    let mut out: Vec<Occurrence> = instances
        .into_iter()
        .filter(|at| !exdates.contains(&at.timestamp()))
        .filter_map(|at| {
            let (starts_at, ends_at, title) = match overrides.get(&at.timestamp()) {
                Some(o) if o.cancelled => return None,
                Some(o) => {
                    let starts = o.starts_at.unwrap_or(at);
                    (starts, o.ends_at.unwrap_or(starts + duration), o.title.clone())
                }
                None => (at, at + duration, None),
            };
            (starts_at < to && ends_at > from).then_some(Occurrence { occurrence_at: at, starts_at, ends_at, title })
        })
        .collect();
    out.sort_by_key(|o| (o.starts_at, o.occurrence_at));
    Ok(out)
}

/// The stored rule of `event`, which must repeat in [`RULE_ZONE`] and be one the editor wrote.
fn rule_of(event: &EventSpec, text: &str) -> Result<Rule, CalendarError> {
    if event.tz != RULE_ZONE {
        return Err(CalendarError::UnknownTimeZone(event.tz.clone()));
    }
    let text = text.trim();
    let text = text.strip_prefix("RRULE:").unwrap_or(text);
    // One rule per event; a second line could smuggle in RDATEs or a different DTSTART.
    if text.contains(['\n', '\r']) {
        return Err(CalendarError::InvalidRule("rule must be a single line".into()));
    }
    Rule::parse(text).ok_or_else(|| CalendarError::InvalidRule(text.to_owned()))
}

/// Instances with `after <= start <= before`.
fn expand(
    rule: &Rule,
    first: DateTime<FixedOffset>,
    after: DateTime<FixedOffset>,
    before: DateTime<FixedOffset>,
) -> Result<Vec<DateTime<FixedOffset>>, CalendarError> {
    rule.starts(first, after, before, usize::from(MAX_OCCURRENCES))
        .ok_or(CalendarError::TooManyOccurrences(MAX_OCCURRENCES))
}

/// Whether `at` is an instance the rule generates: an override must point at a real instance to take effect.
fn is_instance(rule: &Rule, first: DateTime<FixedOffset>, at: DateTime<FixedOffset>) -> Result<bool, CalendarError> {
    Ok(!expand(rule, first, at, at)?.is_empty())
}

/// A shift such as "마감 17–23" stamped onto calendar days (kickoff §5.1). The result is an ordinary event.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShiftTemplate {
    pub name: String,
    /// `HH:MM`.
    pub start_hm: String,
    /// `HH:MM`; at or before `start_hm` means the shift runs past midnight into the next day.
    pub end_hm: String,
}

/// The start and end instants of `template` on `day` in Asia/Seoul.
pub fn shift_event(
    template: &ShiftTemplate,
    day: NaiveDate,
) -> Result<(DateTime<FixedOffset>, DateTime<FixedOffset>), CalendarError> {
    let start = parse_hm(&template.start_hm)?;
    let end = parse_hm(&template.end_hm)?;
    let at = |d: NaiveDate, t: NaiveTime| KST.from_local_datetime(&d.and_time(t)).single().expect("+09:00 has no gaps");
    let starts_at = at(day, start);
    // A night shift ends on the next calendar day; "17:00–01:00" cannot mean a negative length.
    let end_day = if end <= start { day.succ_opt().ok_or(CalendarError::EndsBeforeStart)? } else { day };
    Ok((starts_at, at(end_day, end)))
}

fn parse_hm(text: &str) -> Result<NaiveTime, CalendarError> {
    NaiveTime::parse_from_str(text, "%H:%M").map_err(|_| CalendarError::InvalidTimeOfDay(text.to_string()))
}

/// The half-open range of 30-minute Asia/Seoul slots an event occupies, counted from 00:00 of `window_start`
/// (slot 0). The start is floored and the end ceiled, so 18:10–19:20 occupies 18:00–19:30. `None` when the event
/// ends at or before the window start; an event straddling it starts at slot 0. The upper end is not clamped, since
/// [`Availability::mark_busy`] ignores slots past the window.
pub fn slot_range(
    starts_at: DateTime<FixedOffset>,
    ends_at: DateTime<FixedOffset>,
    window_start: NaiveDate,
) -> Option<(usize, usize)> {
    let origin = KST.from_local_datetime(&window_start.and_time(NaiveTime::MIN)).single().expect("+09:00 has no gaps");
    let slot_secs = i64::from(SLOT_MINUTES) * 60;
    let end_secs = ends_at.signed_duration_since(origin).num_seconds();
    if end_secs <= 0 {
        return None;
    }
    let start_secs = starts_at.signed_duration_since(origin).num_seconds().max(0);
    let from = start_secs / slot_secs;
    // Both operands are positive here, so the ceiling is a plain add-and-divide.
    let to = (end_secs + slot_secs - 1) / slot_secs;
    // Rounding may make a zero-length event vanish; the slot it touches is still busy.
    Some((from as usize, to.max(from + 1) as usize))
}

/// A member's availability blob from the busy ranges of their blocking calendars: every slot free, then each range
/// marked busy at slot resolution.
pub fn availability_from_busy(
    window_start: NaiveDate,
    days: u16,
    busy: &[(DateTime<FixedOffset>, DateTime<FixedOffset>)],
) -> Availability {
    let mut availability = Availability::all_free(window_start.format("%Y-%m-%d").to_string(), days);
    for &(starts_at, ends_at) in busy {
        if let Some((from, to)) = slot_range(starts_at, ends_at, window_start) {
            availability.mark_busy(from, to);
        }
    }
    availability
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::availability::is_free;

    fn kst(y: i32, m: u32, d: u32, h: u32, min: u32) -> DateTime<FixedOffset> {
        KST.with_ymd_and_hms(y, m, d, h, min, 0).single().unwrap()
    }

    fn day(y: i32, m: u32, d: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, d).unwrap()
    }

    fn weekly_tue_thu() -> EventSpec {
        EventSpec {
            starts_at: kst(2026, 10, 27, 19, 0),
            ends_at: kst(2026, 10, 27, 20, 0),
            all_day: false,
            tz: "Asia/Seoul".into(),
            rrule: Some("FREQ=WEEKLY;BYDAY=TU,TH".into()),
            exdates: vec![],
            overrides: vec![],
        }
    }

    fn plain(o: &Occurrence) -> (DateTime<FixedOffset>, DateTime<FixedOffset>) {
        (o.starts_at, o.ends_at)
    }

    #[test]
    fn expands_a_weekly_rrule_with_an_exdate_across_a_month_boundary_in_asia_seoul() {
        let mut event = weekly_tue_thu();
        event.exdates = vec![kst(2026, 10, 29, 19, 0)];
        let got = occurrences(&event, kst(2026, 10, 26, 0, 0), kst(2026, 11, 9, 0, 0)).unwrap();
        let starts: Vec<_> = got.iter().map(plain).collect();
        assert_eq!(
            starts,
            vec![
                (kst(2026, 10, 27, 19, 0), kst(2026, 10, 27, 20, 0)),
                (kst(2026, 11, 3, 19, 0), kst(2026, 11, 3, 20, 0)),
                (kst(2026, 11, 5, 19, 0), kst(2026, 11, 5, 20, 0)),
            ]
        );
        assert!(got.iter().all(|o| o.title.is_none() && o.occurrence_at == o.starts_at));
        // The instances come back in the event's zone, not UTC.
        assert!(got.iter().all(|o| o.starts_at.offset().local_minus_utc() == 9 * 3600));
    }

    #[test]
    fn refuses_a_rule_in_another_zone_instead_of_expanding_it_on_the_wrong_clock() {
        // New York leaves daylight saving time on 2026-11-01: on a fixed offset a 09:00 meeting would move an hour.
        let starts_at = DateTime::parse_from_rfc3339("2026-10-27T09:00:00-04:00").unwrap();
        let event = EventSpec {
            starts_at,
            ends_at: starts_at + Duration::hours(1),
            all_day: false,
            tz: "America/New_York".into(),
            rrule: Some("FREQ=WEEKLY;BYDAY=TU".into()),
            exdates: vec![],
            overrides: vec![],
        };
        assert_eq!(
            occurrences(&event, starts_at, starts_at + Duration::days(14)),
            Err(CalendarError::UnknownTimeZone("America/New_York".into()))
        );
    }

    #[test]
    fn applies_a_shift_template_to_three_days_as_three_ordinary_events() {
        let template = ShiftTemplate { name: "오픈".into(), start_hm: "07:00".into(), end_hm: "13:00".into() };
        let days = [day(2026, 11, 2), day(2026, 11, 3), day(2026, 11, 4)];
        let events: Vec<_> = days.iter().map(|&d| shift_event(&template, d).unwrap()).collect();
        assert_eq!(
            events,
            vec![
                (kst(2026, 11, 2, 7, 0), kst(2026, 11, 2, 13, 0)),
                (kst(2026, 11, 3, 7, 0), kst(2026, 11, 3, 13, 0)),
                (kst(2026, 11, 4, 7, 0), kst(2026, 11, 4, 13, 0)),
            ]
        );
        // Each stamped shift is a plain non-recurring event: one occurrence, nothing attached to the template.
        for (starts_at, ends_at) in events {
            let spec = EventSpec {
                starts_at,
                ends_at,
                all_day: false,
                tz: "Asia/Seoul".into(),
                rrule: None,
                exdates: vec![],
                overrides: vec![],
            };
            let got = occurrences(&spec, kst(2026, 11, 1, 0, 0), kst(2026, 11, 8, 0, 0)).unwrap();
            assert_eq!(got.iter().map(plain).collect::<Vec<_>>(), vec![(starts_at, ends_at)]);
        }
    }

    #[test]
    fn treats_an_end_time_before_the_start_time_as_a_shift_past_midnight() {
        let template = ShiftTemplate { name: "마감".into(), start_hm: "17:00".into(), end_hm: "01:00".into() };
        assert_eq!(
            shift_event(&template, day(2026, 11, 7)).unwrap(),
            (kst(2026, 11, 7, 17, 0), kst(2026, 11, 8, 1, 0))
        );
        // An end equal to the start is a full day, not an empty shift.
        let around_the_clock =
            ShiftTemplate { name: "당직".into(), start_hm: "09:00".into(), end_hm: "09:00".into() };
        assert_eq!(
            shift_event(&around_the_clock, day(2026, 11, 7)).unwrap(),
            (kst(2026, 11, 7, 9, 0), kst(2026, 11, 8, 9, 0))
        );
        let bad = ShiftTemplate { name: "x".into(), start_hm: "7am".into(), end_hm: "13:00".into() };
        assert_eq!(shift_event(&bad, day(2026, 11, 7)), Err(CalendarError::InvalidTimeOfDay("7am".into())));
    }

    #[test]
    fn rounds_an_18_10_19_20_event_to_the_18_00_19_30_slots() {
        let window = day(2026, 11, 7);
        assert_eq!(slot_range(kst(2026, 11, 7, 18, 10), kst(2026, 11, 7, 19, 20), window), Some((36, 39)));
        // Slot-aligned bounds stay as they are.
        assert_eq!(slot_range(kst(2026, 11, 7, 18, 0), kst(2026, 11, 7, 19, 30), window), Some((36, 39)));
        // The second day of the window continues the count.
        assert_eq!(slot_range(kst(2026, 11, 8, 0, 0), kst(2026, 11, 8, 0, 30), window), Some((48, 49)));
        // An event given in another offset is read in Asia/Seoul: 09:10Z is 18:10 KST.
        let utc_start = DateTime::parse_from_rfc3339("2026-11-07T09:10:00+00:00").unwrap();
        let utc_end = DateTime::parse_from_rfc3339("2026-11-07T10:20:00+00:00").unwrap();
        assert_eq!(slot_range(utc_start, utc_end, window), Some((36, 39)));
        // Before the window: nothing; straddling it: from slot 0.
        assert_eq!(slot_range(kst(2026, 11, 6, 18, 0), kst(2026, 11, 7, 0, 0), window), None);
        assert_eq!(slot_range(kst(2026, 11, 6, 23, 0), kst(2026, 11, 7, 1, 0), window), Some((0, 2)));

        let availability = availability_from_busy(window, 2, &[(kst(2026, 11, 7, 18, 10), kst(2026, 11, 7, 19, 20))]);
        assert_eq!(availability.window_start, "2026-11-07");
        assert!(is_free(&availability.bits, 35));
        assert!((36..39).all(|s| !is_free(&availability.bits, s)));
        assert!(is_free(&availability.bits, 39));
    }

    #[test]
    fn moves_one_occurrence_through_an_override_and_keeps_its_original_key() {
        let mut event = weekly_tue_thu();
        event.overrides = vec![Override {
            occurrence_at: kst(2026, 11, 3, 19, 0),
            starts_at: Some(kst(2026, 11, 4, 20, 0)),
            ends_at: Some(kst(2026, 11, 4, 21, 30)),
            title: Some("미뤄진 모임".into()),
            cancelled: false,
        }];
        let got = occurrences(&event, kst(2026, 11, 2, 0, 0), kst(2026, 11, 9, 0, 0)).unwrap();
        assert_eq!(got.len(), 2);
        assert_eq!(
            got[0],
            Occurrence {
                occurrence_at: kst(2026, 11, 3, 19, 0),
                starts_at: kst(2026, 11, 4, 20, 0),
                ends_at: kst(2026, 11, 4, 21, 30),
                title: Some("미뤄진 모임".into()),
            }
        );
        assert_eq!(plain(&got[1]), (kst(2026, 11, 5, 19, 0), kst(2026, 11, 5, 20, 0)));
    }

    #[test]
    fn shows_an_occurrence_moved_into_the_window_from_outside_it() {
        let mut event = weekly_tue_thu();
        // The 11-10 instance lies past the window but was moved into it; a bogus key pointing at no instance is
        // ignored, and a start-only move keeps the event's duration.
        event.overrides = vec![
            Override {
                occurrence_at: kst(2026, 11, 10, 19, 0),
                starts_at: Some(kst(2026, 11, 6, 12, 0)),
                ends_at: None,
                title: None,
                cancelled: false,
            },
            Override {
                occurrence_at: kst(2026, 11, 11, 19, 0),
                starts_at: Some(kst(2026, 11, 6, 15, 0)),
                ends_at: None,
                title: None,
                cancelled: false,
            },
        ];
        let got = occurrences(&event, kst(2026, 11, 6, 0, 0), kst(2026, 11, 9, 0, 0)).unwrap();
        assert_eq!(got.iter().map(plain).collect::<Vec<_>>(), vec![(kst(2026, 11, 6, 12, 0), kst(2026, 11, 6, 13, 0))]);
        assert_eq!(got[0].occurrence_at, kst(2026, 11, 10, 19, 0));
    }

    #[test]
    fn drops_a_cancelled_occurrence() {
        let mut event = weekly_tue_thu();
        event.overrides = vec![Override {
            occurrence_at: kst(2026, 11, 3, 19, 0),
            starts_at: None,
            ends_at: None,
            title: None,
            cancelled: true,
        }];
        let got = occurrences(&event, kst(2026, 11, 2, 0, 0), kst(2026, 11, 9, 0, 0)).unwrap();
        assert_eq!(got.iter().map(plain).collect::<Vec<_>>(), vec![(kst(2026, 11, 5, 19, 0), kst(2026, 11, 5, 20, 0))]);
    }

    #[test]
    fn returns_a_non_recurring_event_once_and_only_when_it_overlaps_the_window() {
        let event = EventSpec {
            starts_at: kst(2026, 11, 7, 18, 0),
            ends_at: kst(2026, 11, 7, 20, 0),
            all_day: false,
            tz: "Asia/Seoul".into(),
            rrule: None,
            exdates: vec![],
            overrides: vec![],
        };
        let hit = occurrences(&event, kst(2026, 11, 7, 19, 0), kst(2026, 11, 8, 0, 0)).unwrap();
        assert_eq!(hit.iter().map(plain).collect::<Vec<_>>(), vec![(kst(2026, 11, 7, 18, 0), kst(2026, 11, 7, 20, 0))]);
        assert!(occurrences(&event, kst(2026, 11, 7, 20, 0), kst(2026, 11, 8, 0, 0)).unwrap().is_empty());
        assert!(occurrences(&event, kst(2026, 11, 1, 0, 0), kst(2026, 11, 7, 18, 0)).unwrap().is_empty());
        // A zone the rule never uses is not looked up, so a stray value does not hide a plain event.
        let odd_tz = EventSpec { tz: "Mars/Olympus".into(), ..event.clone() };
        assert_eq!(occurrences(&odd_tz, kst(2026, 11, 1, 0, 0), kst(2026, 11, 9, 0, 0)).unwrap().len(), 1);
    }

    #[test]
    fn stops_a_counted_rule_after_its_count() {
        let mut event = weekly_tue_thu();
        event.rrule = Some("RRULE:FREQ=WEEKLY;BYDAY=TU,TH;COUNT=3".into());
        let got = occurrences(&event, kst(2026, 10, 1, 0, 0), kst(2027, 1, 1, 0, 0)).unwrap();
        let days: Vec<NaiveDate> = got.iter().map(|o| o.starts_at.date_naive()).collect();
        assert_eq!(days, vec![day(2026, 10, 27), day(2026, 10, 29), day(2026, 11, 3)]);
    }

    #[test]
    fn rejects_a_bad_rule_an_unknown_zone_and_an_unbounded_expansion() {
        let mut bad_rule = weekly_tue_thu();
        bad_rule.rrule = Some("FREQ=SOMETIMES".into());
        assert!(matches!(
            occurrences(&bad_rule, kst(2026, 10, 1, 0, 0), kst(2026, 11, 1, 0, 0)),
            Err(CalendarError::InvalidRule(_))
        ));
        let mut two_lines = weekly_tue_thu();
        two_lines.rrule = Some("FREQ=DAILY\nRDATE:20261225T000000Z".into());
        assert!(matches!(
            occurrences(&two_lines, kst(2026, 10, 1, 0, 0), kst(2026, 11, 1, 0, 0)),
            Err(CalendarError::InvalidRule(_))
        ));
        let mut bad_tz = weekly_tue_thu();
        bad_tz.tz = "Asia/Nowhere".into();
        assert_eq!(
            occurrences(&bad_tz, kst(2026, 10, 1, 0, 0), kst(2026, 11, 1, 0, 0)),
            Err(CalendarError::UnknownTimeZone("Asia/Nowhere".into()))
        );
        let mut backwards = weekly_tue_thu();
        backwards.ends_at = backwards.starts_at;
        assert_eq!(
            occurrences(&backwards, kst(2026, 10, 1, 0, 0), kst(2026, 11, 1, 0, 0)),
            Err(CalendarError::EndsBeforeStart)
        );
        // The editor never writes a rule finer than a day.
        let mut every_minute = weekly_tue_thu();
        every_minute.rrule = Some("FREQ=MINUTELY".into());
        assert!(matches!(
            occurrences(&every_minute, kst(2026, 10, 1, 0, 0), kst(2026, 11, 1, 0, 0)),
            Err(CalendarError::InvalidRule(_))
        ));
        // Three years of a daily rule is more than one view may ask for.
        let mut daily = weekly_tue_thu();
        daily.rrule = Some("FREQ=DAILY".into());
        assert_eq!(
            occurrences(&daily, kst(2026, 10, 1, 0, 0), kst(2029, 10, 1, 0, 0)),
            Err(CalendarError::TooManyOccurrences(MAX_OCCURRENCES))
        );
    }
}

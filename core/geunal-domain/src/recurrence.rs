//! The repeat rules the event editor makes (kickoff §5.1: presets such as "매주 화·목", and "직접 정하기"), written as
//! the RFC 5545 rules the `events` table stores, read back for the editor, and expanded into instances. Every stored
//! rule is one this module wrote, so only what it writes is read back and expanded; anything else reads as `None`.
//!
//! The expansion follows RFC 5545 for these rules: instances keep the first one's wall-clock time in Korea, a day the
//! rule names that does not exist (the 31st of a short month, 29 February in a common year) is skipped, anything
//! before the first start is not an instance and does not count, and UNTIL is inclusive.

use chrono::{DateTime, Datelike, Duration, FixedOffset, NaiveDate, NaiveTime, TimeZone, Utc, Weekday};

use crate::time::KST;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Freq {
    Daily,
    Weekly,
    Monthly,
    Yearly,
}

/// A repeat rule as the editor sees it. Dates are Korean days.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    pub freq: Freq,
    /// Every `interval` periods; 1 or more.
    pub interval: u16,
    /// Weekly only. Empty means the weekday of the first occurrence, as RFC 5545 does.
    pub by_day: Vec<Weekday>,
    /// The last day an occurrence may start on.
    pub until: Option<NaiveDate>,
    pub count: Option<u32>,
}

/// Weekdays in the order of the calendar grid, which starts on Sunday.
pub const WEEK: [Weekday; 7] =
    [Weekday::Sun, Weekday::Mon, Weekday::Tue, Weekday::Wed, Weekday::Thu, Weekday::Fri, Weekday::Sat];

fn code(day: Weekday) -> &'static str {
    match day {
        Weekday::Sun => "SU",
        Weekday::Mon => "MO",
        Weekday::Tue => "TU",
        Weekday::Wed => "WE",
        Weekday::Thu => "TH",
        Weekday::Fri => "FR",
        Weekday::Sat => "SA",
    }
}

fn weekday_of(code: &str) -> Option<Weekday> {
    WEEK.into_iter().find(|d| self::code(*d) == code)
}

/// The UNTIL instant for a last day: the end of that day in Korea, in UTC as RFC 5545 requires next to a DTSTART
/// with a time zone.
fn until_instant(day: NaiveDate) -> DateTime<Utc> {
    let end = NaiveTime::from_hms_opt(23, 59, 59).expect("valid time");
    KST.from_local_datetime(&day.and_time(end)).single().expect("+09:00 has no gaps").to_utc()
}

impl Rule {
    /// The rule as stored, without the `RRULE:` prefix. Weekdays come out in calendar order whatever order they came
    /// in, so the same choice always writes the same text.
    pub fn to_rrule(&self) -> String {
        let freq = match self.freq {
            Freq::Daily => "DAILY",
            Freq::Weekly => "WEEKLY",
            Freq::Monthly => "MONTHLY",
            Freq::Yearly => "YEARLY",
        };
        let mut parts = vec![format!("FREQ={freq}")];
        if self.interval > 1 {
            parts.push(format!("INTERVAL={}", self.interval));
        }
        if self.freq == Freq::Weekly {
            let days: Vec<&str> = WEEK.into_iter().filter(|d| self.by_day.contains(d)).map(code).collect();
            if !days.is_empty() {
                parts.push(format!("BYDAY={}", days.join(",")));
            }
            // Every other week counts weeks from Sunday, the first column of the grid.
            if self.interval > 1 {
                parts.push("WKST=SU".to_owned());
            }
        }
        if let Some(day) = self.until {
            parts.push(format!("UNTIL={}", until_instant(day).format("%Y%m%dT%H%M%SZ")));
        }
        if let Some(count) = self.count {
            parts.push(format!("COUNT={count}"));
        }
        parts.join(";")
    }

    /// Reads back a rule [`Rule::to_rrule`] wrote. `None` for a part the editor cannot show.
    pub fn parse(text: &str) -> Option<Rule> {
        let text = text.trim();
        let text = text.strip_prefix("RRULE:").unwrap_or(text);
        let mut rule = Rule { freq: Freq::Daily, interval: 1, by_day: Vec::new(), until: None, count: None };
        let mut freq = None;
        for part in text.split(';').filter(|p| !p.is_empty()) {
            let (key, value) = part.split_once('=')?;
            match key {
                "FREQ" => {
                    freq = Some(match value {
                        "DAILY" => Freq::Daily,
                        "WEEKLY" => Freq::Weekly,
                        "MONTHLY" => Freq::Monthly,
                        "YEARLY" => Freq::Yearly,
                        _ => return None,
                    })
                }
                "INTERVAL" => rule.interval = value.parse().ok().filter(|i| *i >= 1)?,
                "BYDAY" => {
                    rule.by_day = value.split(',').map(weekday_of).collect::<Option<Vec<_>>>()?;
                }
                "WKST" => {}
                "UNTIL" => {
                    let at = chrono::NaiveDateTime::parse_from_str(value, "%Y%m%dT%H%M%SZ").ok()?.and_utc();
                    rule.until = Some(at.with_timezone(&KST).date_naive());
                }
                "COUNT" => rule.count = Some(value.parse().ok()?),
                _ => return None,
            }
        }
        rule.freq = freq?;
        if rule.freq != Freq::Weekly && !rule.by_day.is_empty() {
            return None;
        }
        Some(rule)
    }

    /// The same rule, ending on the day before `day`: the old half of a series split at an occurrence on `day`
    /// ("이후 모두"). A count gives way to the end day, since RFC 5545 allows only one of the two.
    pub fn ending_before(&self, day: NaiveDate) -> Rule {
        Rule { until: day.pred_opt(), count: None, ..self.clone() }
    }
}

/// How many periods (days, weeks, months or years) one expansion walks at most. A rule that skips most of its periods
/// (29 February every year) still meets an instance within a few; this only bounds a huge count or a far window.
const MAX_PERIODS: u32 = 100_000;

impl Rule {
    /// The starts of this rule's instances with `after <= start <= before`, in order, for an event whose first start
    /// (the RFC 5545 DTSTART) is `first`. `None` when more than `limit` of them fall in that range.
    pub fn starts(
        &self,
        first: DateTime<FixedOffset>,
        after: DateTime<FixedOffset>,
        before: DateTime<FixedOffset>,
        limit: usize,
    ) -> Option<Vec<DateTime<FixedOffset>>> {
        let first = first.with_timezone(&KST);
        let (d0, t0) = (first.date_naive(), first.time());
        let until = self.until.map(until_instant);
        let interval = u32::from(self.interval.max(1));
        let days: Vec<Weekday> = if self.by_day.is_empty() {
            vec![d0.weekday()]
        } else {
            WEEK.into_iter().filter(|d| self.by_day.contains(d)).collect()
        };
        // Without a count nothing before the window matters, so the walk can start where the window opens.
        let mut period =
            if self.count.is_none() { self.period_of(d0, after.with_timezone(&KST).date_naive(), interval) } else { 0 };
        let mut counted = 0u32;
        let mut out = Vec::new();
        for _ in 0..MAX_PERIODS {
            let Some(found) = self.days_in(d0, period, interval, &days) else { break };
            for day in found {
                let at = KST.from_local_datetime(&day.and_time(t0)).single().expect("a fixed offset has no gaps");
                if at < first {
                    continue;
                }
                if at > before || until.is_some_and(|u| at.to_utc() > u) || self.count.is_some_and(|c| counted >= c) {
                    return Some(out);
                }
                counted += 1;
                if at >= after {
                    if out.len() == limit {
                        return None;
                    }
                    out.push(at);
                }
            }
            period += 1;
        }
        Some(out)
    }

    /// The period, counted from the first start's, that `day` falls in; 0 for a day before it.
    fn period_of(&self, d0: NaiveDate, day: NaiveDate, interval: u32) -> u32 {
        if day <= d0 {
            return 0;
        }
        let between = match self.freq {
            Freq::Daily => (day - d0).num_days(),
            Freq::Weekly => (sunday_of(day) - sunday_of(d0)).num_days() / 7,
            Freq::Monthly => i64::from(day.year() - d0.year()) * 12 + i64::from(day.month0()) - i64::from(d0.month0()),
            Freq::Yearly => i64::from(day.year() - d0.year()),
        };
        u32::try_from(between / i64::from(interval)).unwrap_or(u32::MAX)
    }

    /// The days the rule names in period `period`, in order; empty when the period's day does not exist, and `None`
    /// past the end of the calendar.
    fn days_in(&self, d0: NaiveDate, period: u32, interval: u32, days: &[Weekday]) -> Option<Vec<NaiveDate>> {
        let step = i64::from(period).checked_mul(i64::from(interval))?;
        Some(match self.freq {
            Freq::Daily => vec![d0.checked_add_signed(Duration::try_days(step)?)?],
            Freq::Weekly => {
                let week = sunday_of(d0).checked_add_signed(Duration::try_weeks(step)?)?;
                days.iter().map(|d| week + Duration::days(i64::from(d.num_days_from_sunday()))).collect()
            }
            Freq::Monthly => {
                let month = i64::from(d0.year()) * 12 + i64::from(d0.month0()) + step;
                let year = i32::try_from(month.div_euclid(12)).ok()?;
                NaiveDate::from_ymd_opt(year, 1, 1)?;
                NaiveDate::from_ymd_opt(year, month.rem_euclid(12) as u32 + 1, d0.day()).into_iter().collect()
            }
            Freq::Yearly => {
                let year = i32::try_from(i64::from(d0.year()) + step).ok()?;
                NaiveDate::from_ymd_opt(year, 1, 1)?;
                NaiveDate::from_ymd_opt(year, d0.month(), d0.day()).into_iter().collect()
            }
        })
    }
}

/// The Sunday a week of the calendar grid starts on (WKST=SU).
fn sunday_of(day: NaiveDate) -> NaiveDate {
    day - Duration::days(i64::from(day.weekday().num_days_from_sunday()))
}

/// The weekday `day` falls on, Sunday = 0, as the suggestion counters store it.
pub fn weekday_index(day: NaiveDate) -> i64 {
    i64::from(day.weekday().num_days_from_sunday())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(y: i32, m: u32, d: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, d).unwrap()
    }

    #[test]
    fn writes_the_editor_presets_as_rfc5545_rules_and_reads_them_back() {
        let weekly =
            |days: Vec<Weekday>| Rule { freq: Freq::Weekly, interval: 1, by_day: days, until: None, count: None };
        let cases = [
            (weekly(vec![Weekday::Fri]), "FREQ=WEEKLY;BYDAY=FR"),
            (weekly(vec![Weekday::Thu, Weekday::Tue]), "FREQ=WEEKLY;BYDAY=TU,TH"),
            (Rule { freq: Freq::Daily, interval: 1, by_day: vec![], until: None, count: None }, "FREQ=DAILY"),
            (
                Rule {
                    freq: Freq::Weekly,
                    interval: 2,
                    by_day: vec![Weekday::Mon],
                    until: Some(day(2026, 12, 31)),
                    count: None,
                },
                "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;WKST=SU;UNTIL=20261231T145959Z",
            ),
            (
                Rule { freq: Freq::Monthly, interval: 1, by_day: vec![], until: None, count: Some(5) },
                "FREQ=MONTHLY;COUNT=5",
            ),
            (Rule { freq: Freq::Yearly, interval: 1, by_day: vec![], until: None, count: None }, "FREQ=YEARLY"),
        ];
        for (rule, text) in cases {
            assert_eq!(rule.to_rrule(), text);
            let mut sorted = rule.clone();
            sorted.by_day = WEEK.into_iter().filter(|d| rule.by_day.contains(d)).collect();
            assert_eq!(Rule::parse(text), Some(sorted), "{text}");
        }
    }

    #[test]
    fn reads_nothing_it_did_not_write() {
        assert_eq!(Rule::parse("FREQ=HOURLY"), None);
        assert_eq!(Rule::parse("FREQ=MONTHLY;BYMONTHDAY=-1"), None);
        assert_eq!(Rule::parse("INTERVAL=2"), None);
        assert_eq!(Rule::parse("FREQ=DAILY;BYDAY=MO"), None);
    }

    fn at(y: i32, m: u32, d: u32, h: u32) -> DateTime<FixedOffset> {
        KST.with_ymd_and_hms(y, m, d, h, 0, 0).single().unwrap()
    }

    fn rule(text: &str) -> Rule {
        Rule::parse(text).unwrap()
    }

    fn days(found: Option<Vec<DateTime<FixedOffset>>>) -> Vec<NaiveDate> {
        found.unwrap().iter().map(|a| a.date_naive()).collect()
    }

    #[test]
    fn skips_the_days_a_month_or_a_year_does_not_have() {
        // The 31st of every month: February and April have none.
        let monthly = rule("FREQ=MONTHLY").starts(at(2027, 1, 31, 9), at(2027, 1, 1, 0), at(2027, 6, 1, 0), 100);
        assert_eq!(days(monthly), vec![day(2027, 1, 31), day(2027, 3, 31), day(2027, 5, 31)]);
        // 29 February comes back every leap year only.
        let yearly = rule("FREQ=YEARLY").starts(at(2028, 2, 29, 9), at(2028, 1, 1, 0), at(2033, 1, 1, 0), 100);
        assert_eq!(days(yearly), vec![day(2028, 2, 29), day(2032, 2, 29)]);
    }

    #[test]
    fn starts_at_the_first_named_day_and_counts_from_there() {
        // Made on Monday 26 October for Tuesdays and Thursdays: Monday is not an instance and is not counted.
        let found = rule("FREQ=WEEKLY;BYDAY=TU,TH;COUNT=3").starts(
            at(2026, 10, 26, 19),
            at(2026, 10, 1, 0),
            at(2027, 1, 1, 0),
            100,
        );
        assert_eq!(days(found), vec![day(2026, 10, 27), day(2026, 10, 29), day(2026, 11, 3)]);
    }

    #[test]
    fn repeats_every_other_week_by_the_weeks_of_the_grid() {
        // Weeks start on Sunday (WKST=SU), so from Wednesday 28 October the Monday of that week is past and the next
        // one is two grid weeks later.
        let found = rule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SA;WKST=SU").starts(
            at(2026, 10, 28, 9),
            at(2026, 10, 1, 0),
            at(2026, 11, 22, 0),
            100,
        );
        assert_eq!(days(found), vec![day(2026, 10, 31), day(2026, 11, 9), day(2026, 11, 14)]);
    }

    #[test]
    fn includes_the_last_day_until_names() {
        let daily = Rule { freq: Freq::Daily, interval: 1, by_day: vec![], until: Some(day(2026, 11, 2)), count: None };
        let found = daily.starts(at(2026, 10, 31, 23), at(2026, 10, 1, 0), at(2027, 1, 1, 0), 100);
        assert_eq!(days(found), vec![day(2026, 10, 31), day(2026, 11, 1), day(2026, 11, 2)]);
    }

    #[test]
    fn walks_straight_to_a_far_window_and_refuses_one_too_wide() {
        let daily = rule("FREQ=DAILY;INTERVAL=3");
        let found = daily.starts(at(2026, 1, 1, 8), at(2036, 1, 1, 0), at(2036, 1, 10, 0), 100);
        // 1 January 2036 is 3652 days on (two leap days), one past a multiple of three, so the next is the 3rd.
        assert_eq!(days(found), vec![day(2036, 1, 3), day(2036, 1, 6), day(2036, 1, 9)]);
        assert_eq!(daily.starts(at(2026, 1, 1, 8), at(2026, 1, 1, 0), at(2030, 1, 1, 0), 100), None);
    }

    #[test]
    fn ends_a_split_series_on_the_day_before_the_edited_occurrence() {
        let rule = Rule { freq: Freq::Weekly, interval: 1, by_day: vec![Weekday::Fri], until: None, count: Some(10) };
        let old = rule.ending_before(day(2026, 11, 13));
        assert_eq!(old.until, Some(day(2026, 11, 12)));
        assert_eq!(old.count, None);
        assert_eq!(old.to_rrule(), "FREQ=WEEKLY;BYDAY=FR;UNTIL=20261112T145959Z");
    }
}

//! The repeat rules the event editor makes (kickoff §5.1: presets such as "매주 화·목", and "직접 정하기"), written as
//! the RFC 5545 rules the `events` table stores and read back for the editor. Only rules this module writes are read
//! back; a rule from elsewhere that does not fit the editor reads as `None`, and the event still expands through
//! [`crate::calendar::occurrences`].

use chrono::{DateTime, Datelike, NaiveDate, NaiveTime, TimeZone, Utc, Weekday};

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

    #[test]
    fn ends_a_split_series_on_the_day_before_the_edited_occurrence() {
        let rule = Rule { freq: Freq::Weekly, interval: 1, by_day: vec![Weekday::Fri], until: None, count: Some(10) };
        let old = rule.ending_before(day(2026, 11, 13));
        assert_eq!(old.until, Some(day(2026, 11, 12)));
        assert_eq!(old.count, None);
        assert_eq!(old.to_rrule(), "FREQ=WEEKLY;BYDAY=FR;UNTIL=20261112T145959Z");
    }
}

//! Public holidays and the leave bridges they make (kickoff §5.1). The app starts from the table below and replaces a
//! whole year whenever the server sends it (protocol §14), so a temporary holiday added later still shows.
//!
//! Sources of the built-in table:
//! - 2026: 우주항공청 「2026년 월력요항」, with 노동절 and 제헌절 from the amended 관공서의 공휴일에 관한 규정
//!   (대통령령 제36290호, in force 2026-05-01, with their substitute-holiday rule) and the 9th local elections
//!   (2026-06-03) from the 공직선거법 election day.
//! - 2027: 우주항공청 「2027년 월력요항」 (announced 2026-06-29; 한국천문연구원 천문우주지식정보 달력자료 2027).

use std::collections::BTreeSet;

use chrono::{Datelike, Duration, NaiveDate, Weekday};

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Holiday {
    pub day: NaiveDate,
    pub name: String,
    /// 대체공휴일.
    pub substitute: bool,
}

/// The years the built-in table covers completely.
pub const BUILT_IN_YEARS: [i32; 2] = [2026, 2027];

const BUILT_IN: &[(&str, &str, bool)] = &[
    ("2026-01-01", "1월 1일", false),
    ("2026-02-16", "설날", false),
    ("2026-02-17", "설날", false),
    ("2026-02-18", "설날", false),
    ("2026-03-01", "3·1절", false),
    ("2026-03-02", "대체공휴일", true),
    ("2026-05-01", "노동절", false),
    ("2026-05-05", "어린이날", false),
    ("2026-05-24", "부처님오신날", false),
    ("2026-05-25", "대체공휴일", true),
    ("2026-06-03", "전국동시지방선거", false),
    ("2026-06-06", "현충일", false),
    ("2026-07-17", "제헌절", false),
    ("2026-08-15", "광복절", false),
    ("2026-08-17", "대체공휴일", true),
    ("2026-09-24", "추석", false),
    ("2026-09-25", "추석", false),
    ("2026-09-26", "추석", false),
    ("2026-10-03", "개천절", false),
    ("2026-10-05", "대체공휴일", true),
    ("2026-10-09", "한글날", false),
    ("2026-12-25", "기독탄신일", false),
    ("2027-01-01", "1월 1일", false),
    ("2027-02-06", "설날", false),
    ("2027-02-07", "설날", false),
    ("2027-02-08", "설날", false),
    ("2027-02-09", "대체공휴일", true),
    ("2027-03-01", "3·1절", false),
    ("2027-05-01", "노동절", false),
    ("2027-05-03", "대체공휴일", true),
    ("2027-05-05", "어린이날", false),
    ("2027-05-13", "부처님오신날", false),
    ("2027-06-06", "현충일", false),
    ("2027-07-17", "제헌절", false),
    ("2027-07-19", "대체공휴일", true),
    ("2027-08-15", "광복절", false),
    ("2027-08-16", "대체공휴일", true),
    ("2027-09-14", "추석", false),
    ("2027-09-15", "추석", false),
    ("2027-09-16", "추석", false),
    ("2027-10-03", "개천절", false),
    ("2027-10-04", "대체공휴일", true),
    ("2027-10-09", "한글날", false),
    ("2027-10-11", "대체공휴일", true),
    ("2027-12-25", "기독탄신일", false),
    ("2027-12-27", "대체공휴일", true),
];

/// The built-in holidays of `year`; empty for a year the table does not cover.
pub fn built_in(year: i32) -> Vec<Holiday> {
    BUILT_IN
        .iter()
        .map(|(day, name, substitute)| Holiday {
            day: NaiveDate::parse_from_str(day, "%Y-%m-%d").expect("the table holds dates"),
            name: (*name).to_owned(),
            substitute: *substitute,
        })
        .filter(|h| h.day.year() == year)
        .collect()
}

/// A day off without taking leave: a weekend day or a public holiday.
pub fn is_day_off(day: NaiveDate, holidays: &BTreeSet<NaiveDate>) -> bool {
    matches!(day.weekday(), Weekday::Sat | Weekday::Sun) || holidays.contains(&day)
}

/// One weekday off that joins days off on both sides into four days or more (kickoff §5.1: "연차 1일로 4일 연휴").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Bridge {
    pub leave_day: NaiveDate,
    pub first_day: NaiveDate,
    pub last_day: NaiveDate,
    pub days_off: u8,
}

/// The bridges whose leave day falls in [from, to]. `holidays` must cover a few days beyond the range on both sides,
/// since a bridge's run of days off can reach past it.
pub fn bridges(holidays: &BTreeSet<NaiveDate>, from: NaiveDate, to: NaiveDate) -> Vec<Bridge> {
    let off = |d: NaiveDate| is_day_off(d, holidays);
    let mut out = Vec::new();
    let mut day = from;
    while day <= to {
        let (before, after) = (day.pred_opt(), day.succ_opt());
        if let (Some(before), Some(after)) = (before, after)
            && !off(day)
            && off(before)
            && off(after)
        {
            let mut first = before;
            while let Some(p) = first.pred_opt().filter(|p| off(*p)) {
                first = p;
            }
            let mut last = after;
            while let Some(n) = last.succ_opt().filter(|n| off(*n)) {
                last = n;
            }
            let days_off = (last - first).num_days() + 1;
            if days_off >= 4 {
                out.push(Bridge {
                    leave_day: day,
                    first_day: first,
                    last_day: last,
                    days_off: days_off.min(255) as u8,
                });
            }
        }
        day += Duration::days(1);
    }
    out
}

/// Runs of three days off or more ending on or after `from` and starting by `to`, as (first, last): the long
/// weekends the "연휴 여행" suggestion is about.
pub fn long_weekends(holidays: &BTreeSet<NaiveDate>, from: NaiveDate, to: NaiveDate) -> Vec<(NaiveDate, NaiveDate)> {
    let off = |d: NaiveDate| is_day_off(d, holidays);
    let mut out = Vec::new();
    let mut day = from - Duration::days(7);
    let end = to + Duration::days(7);
    while day <= end {
        if off(day) {
            let first = day;
            while off(day + Duration::days(1)) {
                day += Duration::days(1);
            }
            if (day - first).num_days() + 1 >= 3 && day >= from && first <= to {
                out.push((first, day));
            }
        }
        day += Duration::days(1);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    fn days(year: i32) -> BTreeSet<NaiveDate> {
        built_in(year).into_iter().map(|h| h.day).collect()
    }

    #[test]
    fn marks_substitute_holidays_and_accepts_two_holidays_on_one_date() {
        let h2026 = built_in(2026);
        assert!(h2026.iter().any(|h| h.day == d(2026, 3, 2) && h.substitute && h.name == "대체공휴일"));
        assert!(h2026.iter().any(|h| h.day == d(2026, 5, 1) && h.name == "노동절"));
        assert!(h2026.iter().any(|h| h.day == d(2026, 7, 17) && h.name == "제헌절"));
        // The table type keeps two names on one day, as 2025-05-05 had (어린이날, 부처님오신날).
        let mut two = BTreeSet::new();
        two.insert(Holiday { day: d(2025, 5, 5), name: "어린이날".into(), substitute: false });
        two.insert(Holiday { day: d(2025, 5, 5), name: "부처님오신날".into(), substitute: false });
        assert_eq!(two.len(), 2);
        // KASA's count for 2027: 24 holiday days besides Sundays' own count, four of them on a Sunday.
        let h2027 = built_in(2027);
        assert_eq!(h2027.len(), 24);
        assert_eq!(h2027.iter().filter(|h| h.day.weekday() == Weekday::Sun).count(), 4);
        assert!(built_in(2028).is_empty());
    }

    #[test]
    fn suggests_one_leave_day_that_bridges_a_holiday_into_a_four_day_weekend() {
        // 2026: 노동절 is Friday 1 May and 어린이날 Tuesday 5 May. Taking Monday 4 May off makes Friday through Tuesday,
        // five days.
        let found = bridges(&days(2026), d(2026, 5, 1), d(2026, 5, 31));
        assert_eq!(
            found,
            vec![Bridge { leave_day: d(2026, 5, 4), first_day: d(2026, 5, 1), last_day: d(2026, 5, 5), days_off: 5 }]
        );
        // 2027: 부처님오신날 is Thursday 13 May; Friday 14 May off makes Thursday to Sunday.
        let found = bridges(&days(2027), d(2027, 5, 10), d(2027, 5, 20));
        assert_eq!(found[0].leave_day, d(2027, 5, 14));
        assert_eq!(found[0].days_off, 4);
        // An ordinary Friday before a weekend bridges nothing.
        assert!(bridges(&days(2026), d(2026, 11, 1), d(2026, 11, 30)).is_empty());
    }

    #[test]
    fn finds_the_long_weekends_around_a_range() {
        let found = long_weekends(&days(2026), d(2026, 9, 20), d(2026, 9, 30));
        assert_eq!(found, vec![(d(2026, 9, 24), d(2026, 9, 27))]);
    }
}

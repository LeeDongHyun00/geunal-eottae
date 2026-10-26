//! Korean lunar dates for lunar birthdays (kickoff §5.1, §10). The table covers lunar 1900–2049 and comes from two
//! independent sources built on 한국천문연구원 data that agree on every one of those years (`table.rs`,
//! `core/scripts/gen-lunar-table.py`). Outside the table nothing is converted: a guessed date would be wrong without
//! anyone noticing.
//!
//! The two birthday rules (docs/parts/rust-core.md §6):
//! - a birthday in a leap month (윤달) falls in that leap month when the year has it, and in the ordinary month of
//!   the same number when it does not;
//! - a birthday on the 30th falls on the 29th in a year whose month has only 29 days.

mod table;

use std::sync::OnceLock;

use chrono::{Datelike, Duration, NaiveDate};

pub use table::{FIRST_YEAR, LAST_YEAR};

/// A date on the Korean lunar calendar.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct LunarDate {
    pub year: i32,
    pub month: u8,
    pub day: u8,
    /// The date is in the leap month that follows ordinary month `month`.
    pub leap: bool,
}

struct Year {
    /// Lengths of the ordinary months 1–12.
    months: [u8; 12],
    leap_month: Option<u8>,
    leap_length: u8,
}

fn year(y: i32) -> Option<Year> {
    let packed = *table::YEARS.get(usize::try_from(y - FIRST_YEAR).ok()?)?;
    let mut months = [29u8; 12];
    for (m, len) in months.iter_mut().enumerate() {
        if packed & (1 << m) != 0 {
            *len = 30;
        }
    }
    let leap = ((packed >> 12) & 0xf) as u8;
    Some(Year {
        months,
        leap_month: (leap > 0).then_some(leap),
        leap_length: if packed & (1 << 16) != 0 { 30 } else { 29 },
    })
}

impl Year {
    fn length(&self) -> i64 {
        let ordinary: i64 = self.months.iter().map(|&m| i64::from(m)).sum();
        ordinary + self.leap_month.map(|_| i64::from(self.leap_length)).unwrap_or(0)
    }
}

/// The solar day of each lunar new year in the table, counted once.
fn new_years() -> &'static [NaiveDate] {
    static NEW_YEARS: OnceLock<Vec<NaiveDate>> = OnceLock::new();
    NEW_YEARS.get_or_init(|| {
        let (y, m, d) = table::FIRST_NEW_YEAR;
        let mut day = NaiveDate::from_ymd_opt(y, m, d).expect("the table's first new year is a date");
        let mut out = Vec::with_capacity(table::YEARS.len());
        for lunar in FIRST_YEAR..=LAST_YEAR {
            out.push(day);
            day += Duration::days(year(lunar).expect("in the table").length());
        }
        out
    })
}

/// The solar day of a lunar date, or `None` when the table has no such date: a year outside it, a leap month the
/// year does not have, or a day past the month's length.
pub fn to_solar(date: LunarDate) -> Option<NaiveDate> {
    let info = year(date.year)?;
    if !(1..=12).contains(&date.month) || date.day == 0 {
        return None;
    }
    if date.leap && info.leap_month != Some(date.month) {
        return None;
    }
    let length = if date.leap { info.leap_length } else { info.months[usize::from(date.month - 1)] };
    if date.day > length {
        return None;
    }
    let mut offset = 0i64;
    for m in 1..date.month {
        offset += i64::from(info.months[usize::from(m - 1)]);
        if info.leap_month == Some(m) {
            offset += i64::from(info.leap_length);
        }
    }
    if date.leap {
        offset += i64::from(info.months[usize::from(date.month - 1)]);
    }
    let new_year = new_years()[usize::try_from(date.year - FIRST_YEAR).ok()?];
    Some(new_year + Duration::days(offset + i64::from(date.day) - 1))
}

/// The lunar date of a solar day inside the table.
pub fn from_solar(day: NaiveDate) -> Option<LunarDate> {
    let years = new_years();
    let index = years.partition_point(|ny| *ny <= day).checked_sub(1)?;
    let lunar_year = FIRST_YEAR + index as i32;
    let info = year(lunar_year)?;
    let mut left = (day - years[index]).num_days();
    if left >= info.length() {
        return None; // past the end of the table
    }
    for m in 1..=12u8 {
        let len = i64::from(info.months[usize::from(m - 1)]);
        if left < len {
            return Some(LunarDate { year: lunar_year, month: m, day: (left + 1) as u8, leap: false });
        }
        left -= len;
        if info.leap_month == Some(m) {
            let len = i64::from(info.leap_length);
            if left < len {
                return Some(LunarDate { year: lunar_year, month: m, day: (left + 1) as u8, leap: true });
            }
            left -= len;
        }
    }
    None
}

/// The solar day a lunar birthday (`month`/`day`, born in a leap month when `leap`) falls on in lunar year `year`,
/// after the two rules in the module comment. `None` outside the table.
pub fn birthday(year_: i32, month: u8, day: u8, leap: bool) -> Option<NaiveDate> {
    let info = year(year_)?;
    if !(1..=12).contains(&month) || !(1..=30).contains(&day) {
        return None;
    }
    let leap = leap && info.leap_month == Some(month);
    let length = if leap { info.leap_length } else { info.months[usize::from(month - 1)] };
    to_solar(LunarDate { year: year_, month, day: day.min(length), leap })
}

/// The solar day a solar birthday falls on in `year`: 29 February falls on the 28th outside leap years.
pub fn solar_birthday(year: i32, month: u32, day: u32) -> Option<NaiveDate> {
    NaiveDate::from_ymd_opt(year, month, day)
        .or_else(|| (month == 2 && day == 29).then(|| NaiveDate::from_ymd_opt(year, 2, 28)).flatten())
}

/// Every solar day in [from, to] a birthday falls on. A lunar birthday late in the lunar year can fall twice in one
/// solar year (early January and late December), so the lunar years around the range are all checked.
pub fn birthdays_between(
    month: u8,
    day: u8,
    lunar: bool,
    leap: bool,
    from: NaiveDate,
    to: NaiveDate,
) -> Vec<NaiveDate> {
    let mut out = Vec::new();
    for y in from.year() - 1..=to.year() {
        let found =
            if lunar { birthday(y, month, day, leap) } else { solar_birthday(y, u32::from(month), u32::from(day)) };
        if let Some(d) = found.filter(|d| *d >= from && *d <= to) {
            out.push(d);
        }
    }
    out.sort();
    out.dedup();
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    fn lunar(year: i32, month: u8, day: u8) -> LunarDate {
        LunarDate { year, month, day, leap: false }
    }

    #[test]
    fn converts_the_published_new_years_and_chuseoks() {
        // 1997: Korea's 설날 was 8 February, a day after China's (kickoff §10).
        assert_eq!(to_solar(lunar(1997, 1, 1)), Some(d(1997, 2, 8)));
        assert_eq!(to_solar(lunar(2025, 8, 15)), Some(d(2025, 10, 6)));
        assert_eq!(to_solar(lunar(2026, 1, 1)), Some(d(2026, 2, 17)));
        assert_eq!(to_solar(lunar(2026, 8, 15)), Some(d(2026, 9, 25)));
        assert_eq!(to_solar(lunar(2027, 1, 1)), Some(d(2027, 2, 7)));
        assert_eq!(to_solar(lunar(2030, 1, 1)), Some(d(2030, 2, 3)));
        // The design's example: 음력 10월 3일 is 2026-11-11 (docs/design/m1.md screen 17).
        assert_eq!(birthday(2026, 10, 3, false), Some(d(2026, 11, 11)));
    }

    #[test]
    fn round_trips_every_day_of_the_table() {
        let first = to_solar(lunar(FIRST_YEAR, 1, 1)).unwrap();
        let last_year = year(LAST_YEAR).unwrap();
        let last = to_solar(lunar(LAST_YEAR, 12, last_year.months[11])).unwrap();
        let mut day = first;
        while day <= last {
            let l = from_solar(day).unwrap_or_else(|| panic!("{day} has no lunar date"));
            assert_eq!(to_solar(l), Some(day), "{l:?}");
            day = day.succ_opt().unwrap();
        }
        assert_eq!(from_solar(first.pred_opt().unwrap()), None);
        assert_eq!(from_solar(last.succ_opt().unwrap()), None);
        assert_eq!(to_solar(lunar(FIRST_YEAR - 1, 1, 1)), None);
        assert_eq!(to_solar(lunar(LAST_YEAR + 1, 1, 1)), None);
    }

    #[test]
    fn puts_a_leap_month_birthday_in_the_ordinary_month_of_a_year_without_that_leap_month() {
        let leap_year = (FIRST_YEAR..=LAST_YEAR).find(|y| year(*y).unwrap().leap_month == Some(2)).unwrap();
        let in_leap = birthday(leap_year, 2, 10, true).unwrap();
        assert_eq!(from_solar(in_leap), Some(LunarDate { year: leap_year, month: 2, day: 10, leap: true }));
        let plain_year = (leap_year + 1..=LAST_YEAR).find(|y| year(*y).unwrap().leap_month != Some(2)).unwrap();
        assert_eq!(birthday(plain_year, 2, 10, true), to_solar(lunar(plain_year, 2, 10)));
        assert_eq!(to_solar(LunarDate { year: plain_year, month: 2, day: 10, leap: true }), None);
    }

    #[test]
    fn puts_a_30th_birthday_on_the_29th_of_a_short_month() {
        let (y, m) = (FIRST_YEAR..=LAST_YEAR)
            .flat_map(|y| (1..=12u8).map(move |m| (y, m)))
            .find(|(y, m)| year(*y).unwrap().months[usize::from(m - 1)] == 29)
            .unwrap();
        assert_eq!(birthday(y, m, 30, false), to_solar(lunar(y, m, 29)));
        assert_eq!(to_solar(lunar(y, m, 30)), None);
    }

    #[test]
    fn finds_every_birthday_in_a_range_including_a_late_lunar_one_twice_in_a_solar_year() {
        assert_eq!(birthdays_between(2, 29, false, false, d(2027, 1, 1), d(2027, 12, 31)), vec![d(2027, 2, 28)]);
        assert_eq!(birthdays_between(2, 29, false, false, d(2028, 1, 1), d(2028, 12, 31)), vec![d(2028, 2, 29)]);
        let twice = (1900..2049).find(|y| birthdays_between(12, 1, true, false, d(*y, 1, 1), d(*y, 12, 31)).len() == 2);
        assert!(twice.is_some(), "lunar 12/1 falls twice in some solar year");
        assert!(birthdays_between(1, 1, true, false, d(2060, 1, 1), d(2060, 12, 31)).is_empty(), "outside the table");
    }
}

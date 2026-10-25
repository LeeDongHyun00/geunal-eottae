//! Asia/Seoul is the one zone every rule runs in (kickoff §5): slots, "the day has passed" and poll closing all read
//! the clock there. Korea has had no daylight saving since 1988, so a fixed offset is exact and needs no zone table.

use chrono::{DateTime, FixedOffset, NaiveDate, NaiveTime, TimeZone, Utc};

/// The `Asia/Seoul` offset, UTC+9.
pub type Kst = FixedOffset;

/// `Asia/Seoul` as a fixed offset.
pub const KST: Kst = FixedOffset::east_opt(9 * 3600).expect("+09:00 is a valid offset");

/// Minutes per 30-minute slot.
pub const SLOT_MINUTES: u32 = 30;
/// 30-minute slots in a day.
pub const SLOTS_PER_DAY: usize = 48;

/// The Korean calendar day a server timestamp falls on.
pub fn kst_date(at: DateTime<Utc>) -> NaiveDate {
    at.with_timezone(&KST).date_naive()
}

/// 00:00 Asia/Seoul of `day`, as an instant.
pub fn kst_midnight(day: NaiveDate) -> DateTime<Utc> {
    KST.from_local_datetime(&day.and_time(NaiveTime::MIN)).single().expect("a fixed offset has no gaps").to_utc()
}

/// Parses a `YYYY-MM-DD` day as entries carry it (protocol §4).
pub fn parse_day(day: &str) -> Option<NaiveDate> {
    NaiveDate::parse_from_str(day, "%Y-%m-%d").ok()
}

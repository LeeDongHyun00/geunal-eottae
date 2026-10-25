//! The group calendar is members' schedules merged. Nothing here decides what "free" means (kickoff §3 principle 5).
//! A bitmap holds one bit per 30-minute slot in Asia/Seoul, MSB first; 1 means the member has no event in that slot.

use serde::{Deserialize, Serialize};

pub fn is_free(bitmap: &[u8], slot: usize) -> bool {
    bitmap.get(slot >> 3).is_some_and(|b| (b >> (7 - (slot & 7))) & 1 == 1)
}

/// How many members have no event in each slot: the 30-minute day table. 0 stays blank, 1 is the lightest shade.
pub fn free_counts(members: &[&[u8]], slots: usize) -> Vec<u8> {
    (0..slots).map(|s| members.iter().filter(|m| is_free(m, s)).count() as u8).collect()
}

/// Members with no event for every slot of [from, to): who is free throughout a dragged range.
pub fn free_throughout(members: &[&[u8]], from: usize, to: usize) -> Vec<usize> {
    members.iter().enumerate().filter(|(_, m)| (from..to).all(|s| is_free(m, s))).map(|(i, _)| i).collect()
}

/// Members free for the whole hour: the 1-hour strip in a month cell. Half an hour free is not an hour free.
pub fn hour_counts(members: &[&[u8]], day: usize) -> [u8; 24] {
    let mut out = [0u8; 24];
    for (h, c) in out.iter_mut().enumerate() {
        *c = free_throughout(members, day * 48 + h * 2, day * 48 + h * 2 + 2).len() as u8;
    }
    out
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct TimeOption {
    pub start: usize,
    pub end: usize,
    pub free: usize,
} // slot offsets within the day

/// The recommender's window, 09:00-21:00 by default; a group setting, not a rule about when people are free.
pub const DEFAULT_WINDOW: (usize, usize) = (18, 42);

/// Three non-overlapping hour-aligned ranges inside the window: most participants free throughout first, earliest first
/// on ties.
pub fn time_options(members: &[&[u8]], day: usize, len_slots: usize, window: (usize, usize)) -> Vec<TimeOption> {
    let (from, to) = window;
    let base = day * 48;
    let mut ranked: Vec<TimeOption> = (from..=to.saturating_sub(len_slots))
        .step_by(2)
        .map(|s| TimeOption {
            start: s,
            end: s + len_slots,
            free: free_throughout(members, base + s, base + s + len_slots).len(),
        })
        .collect();
    ranked.sort_by(|a, b| b.free.cmp(&a.free).then(a.start.cmp(&b.start)));
    let mut chosen: Vec<TimeOption> = Vec::new();
    for o in ranked {
        if chosen.len() == 3 {
            break;
        }
        if chosen.iter().all(|c| o.end <= c.start || o.start >= c.end) {
            chosen.push(o);
        }
    }
    chosen
}

/// `TimeOption` as a poll option string, `HH:MM-HH:MM` in Asia/Seoul, so the vote result can be read back into
/// minutes with [`parse_time_option`].
pub fn format_time_option(start_slot: usize, end_slot: usize) -> String {
    let m = |slot: usize| format!("{:02}:{:02}", slot / 2, (slot % 2) * 30);
    format!("{}-{}", m(start_slot), m(end_slot))
}

/// The minutes after midnight of an `HH:MM-HH:MM` option, or `None` for a member-added option in another form.
pub fn parse_time_option(option: &str) -> Option<(u16, u16)> {
    let (a, b) = option.split_once('-')?;
    let minutes = |s: &str| -> Option<u16> {
        let (h, m) = s.split_once(':')?;
        let (h, m): (u16, u16) = (h.parse().ok()?, m.parse().ok()?);
        (h <= 24 && m < 60).then_some(h * 60 + m)
    };
    let (start, end) = (minutes(a)?, minutes(b)?);
    (start < end && end <= 24 * 60).then_some((start, end))
}

/// The `availability` state blob's plaintext (protocol §5): the window it covers and one bit per 30-minute slot.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct Availability {
    /// `YYYY-MM-DD` in Asia/Seoul; the window rolls from today.
    pub window_start: String,
    pub days: u16,
    /// `days * 48` bits, MSB first, 1 = no event in the slot.
    #[serde(with = "crate::entry::cbor_bytes")]
    pub bits: Vec<u8>,
}

impl Availability {
    /// A member with no event anywhere in the window.
    pub fn all_free(window_start: String, days: u16) -> Self {
        Self { window_start, days, bits: vec![0xff; (days as usize * 48).div_ceil(8)] }
    }

    /// Marks [from, to) slots as busy. Slots outside the window are ignored.
    pub fn mark_busy(&mut self, from: usize, to: usize) {
        let total = self.days as usize * 48;
        for slot in from.min(total)..to.min(total) {
            self.bits[slot >> 3] &= !(1 << (7 - (slot & 7)));
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        ciborium::into_writer(self, &mut out).expect("in-memory write");
        out
    }

    pub fn decode(bytes: &[u8]) -> Option<Self> {
        ciborium::from_reader(bytes).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A free only 13-15, B free only 14-16, C free all day (the worked example in docs/parts/rust-core.md §6).
    fn members() -> Vec<Vec<u8>> {
        let free_only = |from: usize, to: usize| {
            let mut a = Availability::all_free("2026-11-07".into(), 1);
            a.mark_busy(0, from);
            a.mark_busy(to, 48);
            a.bits
        };
        vec![free_only(26, 30), free_only(28, 32), vec![0xff; 6]]
    }

    #[test]
    fn counts_free_members_per_30_minute_slot_without_any_threshold() {
        let m = members();
        let refs: Vec<&[u8]> = m.iter().map(Vec::as_slice).collect();
        let counts = free_counts(&refs, 48);
        assert_eq!((counts[26], counts[28], counts[32]), (2, 3, 1));
        // 03:00 with no event is free too: no "at least n hours" rule.
        assert_eq!(counts[6], 1);
    }

    #[test]
    fn counts_a_member_for_an_hour_cell_only_when_both_half_hours_are_free() {
        let m = members();
        let refs: Vec<&[u8]> = m.iter().map(Vec::as_slice).collect();
        let hours = hour_counts(&refs, 0);
        assert_eq!((hours[13], hours[14], hours[15], hours[16]), (2, 3, 2, 1));
        let mut half = Availability::all_free("2026-11-07".into(), 1);
        half.mark_busy(26, 27); // 13:00-13:30 busy, free from 13:30
        assert_eq!(hour_counts(&[&half.bits], 0)[13], 0);
    }

    #[test]
    fn lists_members_free_throughout_a_dragged_range() {
        let m = members();
        let refs: Vec<&[u8]> = m.iter().map(Vec::as_slice).collect();
        assert_eq!(free_throughout(&refs, 26, 32), vec![2]);
    }

    #[test]
    fn recommends_three_non_overlapping_two_hour_ranges() {
        let m = members();
        let refs: Vec<&[u8]> = m.iter().map(Vec::as_slice).collect();
        // Most members free throughout first, earliest first on ties, never overlapping: 13-15 (A and C), then the
        // morning hours where only C is free. 14-16 also has two but overlaps 13-15.
        let options = time_options(&refs, 0, 4, DEFAULT_WINDOW);
        assert_eq!(
            options,
            vec![
                TimeOption { start: 26, end: 30, free: 2 },
                TimeOption { start: 18, end: 22, free: 1 },
                TimeOption { start: 22, end: 26, free: 1 },
            ]
        );
        // When everyone is free all evening, the evening slot wins on count.
        let mut evening = Availability::all_free("2026-11-07".into(), 1);
        evening.mark_busy(0, 38);
        let all: Vec<&[u8]> = vec![&evening.bits, &evening.bits, &m[2]];
        assert_eq!(time_options(&all, 0, 4, DEFAULT_WINDOW)[0], TimeOption { start: 38, end: 42, free: 3 });
        assert_eq!(format_time_option(38, 42), "19:00-21:00");
        assert!(time_options(&refs, 0, 26, DEFAULT_WINDOW).is_empty(), "longer than the window: no recommendation");
    }

    #[test]
    fn time_option_strings_round_trip() {
        assert_eq!(parse_time_option(&format_time_option(38, 42)), Some((19 * 60, 21 * 60)));
        assert_eq!(parse_time_option("아무 때나"), None);
        assert_eq!(parse_time_option("21:00-19:00"), None);
    }

    #[test]
    fn eight_week_blob_is_376_bytes() {
        // kickoff appendix B.
        assert_eq!(Availability::all_free("2026-11-07".into(), 56).encode().len(), 376);
    }
}

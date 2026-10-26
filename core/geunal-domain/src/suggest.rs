//! Suggestion chips for the quick entry sheet (kickoff §5.2). Everything runs on the device and nothing about it
//! leaves it. The caller gathers candidates from the dictionary below, the device's own history, birthdays,
//! holidays and shift templates; this module scores them, drops the ones that clash with the day, and ranks them.
//!
//! suggestScore = 0.4 · contextFit + 0.3 · personalFreq + 0.2 · upcoming + 0.1 · recency. The weights are set by
//! hand from the alpha's suggestion rate (plan.md §3), so they live here as constants.

use std::collections::HashSet;

pub const W_CONTEXT: f64 = 0.4;
pub const W_FREQ: f64 = 0.3;
pub const W_UPCOMING: f64 = 0.2;
pub const W_RECENCY: f64 = 0.1;

/// Chips the sheet shows.
pub const CHIPS: usize = 5;
/// Birthdays and long weekends within this many days count as upcoming.
pub const UPCOMING_DAYS: i64 = 7;
/// A title used within this many days counts as recent.
pub const RECENT_DAYS: i64 = 30;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Source {
    Dictionary,
    History,
    Birthday { person_id: String },
    Holiday,
    Shift { template_id: String },
}

/// What a chip would save, with the four terms of its score, each 0–1.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub title: String,
    pub source: Source,
    pub all_day: bool,
    /// Minutes of the day; an end at or before the start ends the next day (a night shift).
    pub start_minute: u16,
    pub end_minute: u16,
    /// `None` puts the chip in the default calendar.
    pub calendar_id: Option<String>,
    pub context_fit: f64,
    pub personal_freq: f64,
    pub upcoming: f64,
    pub recency: f64,
}

impl Candidate {
    pub fn score(&self) -> f64 {
        W_CONTEXT * self.context_fit
            + W_FREQ * self.personal_freq
            + W_UPCOMING * self.upcoming
            + W_RECENCY * self.recency
    }

    /// The chip's minutes on its first day, with a night shift cut at midnight.
    fn span(&self) -> (u16, u16) {
        let end = if self.end_minute > self.start_minute { self.end_minute } else { 24 * 60 };
        (self.start_minute, end)
    }
}

/// One entry of the built-in dictionary (kickoff §5.2): its usual time and how well it fits a working day or a day
/// off.
pub struct Entry {
    pub title: &'static str,
    pub all_day: bool,
    pub start_minute: u16,
    pub end_minute: u16,
    pub workday_fit: f64,
    pub day_off_fit: f64,
}

/// The dictionary. "알바" comes from the shift templates and "생일" from the people instead of from here.
pub const DICTIONARY: &[Entry] = &[
    Entry {
        title: "회사 업무",
        all_day: false,
        start_minute: 9 * 60,
        end_minute: 18 * 60,
        workday_fit: 1.0,
        day_off_fit: 0.0,
    },
    Entry {
        title: "회식",
        all_day: false,
        start_minute: 19 * 60,
        end_minute: 22 * 60,
        workday_fit: 0.8,
        day_off_fit: 0.2,
    },
    Entry {
        title: "수업",
        all_day: false,
        start_minute: 10 * 60,
        end_minute: 12 * 60,
        workday_fit: 0.9,
        day_off_fit: 0.1,
    },
    Entry {
        title: "운동",
        all_day: false,
        start_minute: 19 * 60,
        end_minute: 20 * 60,
        workday_fit: 0.7,
        day_off_fit: 0.6,
    },
    Entry { title: "약속", all_day: true, start_minute: 0, end_minute: 0, workday_fit: 0.4, day_off_fit: 0.8 },
    Entry {
        title: "데이트",
        all_day: false,
        start_minute: 18 * 60,
        end_minute: 21 * 60,
        workday_fit: 0.4,
        day_off_fit: 0.9,
    },
    Entry {
        title: "가족 모임",
        all_day: false,
        start_minute: 12 * 60,
        end_minute: 14 * 60,
        workday_fit: 0.2,
        day_off_fit: 0.9,
    },
    Entry {
        title: "병원",
        all_day: false,
        start_minute: 10 * 60,
        end_minute: 11 * 60,
        workday_fit: 0.6,
        day_off_fit: 0.2,
    },
];

/// The chips for a day: candidates that clash with what the day already has go, a title appears once (its best
/// score), and the rest are ranked by score, then title. `busy` holds the day's timed events in minutes and
/// `titles` every title already on the day.
pub fn rank(candidates: Vec<Candidate>, busy: &[(u16, u16)], titles: &HashSet<String>) -> Vec<Candidate> {
    let mut kept: Vec<Candidate> = Vec::new();
    for c in candidates {
        if titles.contains(&c.title) {
            continue;
        }
        if !c.all_day {
            let (start, end) = c.span();
            if busy.iter().any(|&(s, e)| start < e && end > s) {
                continue;
            }
        }
        match kept.iter_mut().find(|k| k.title == c.title) {
            Some(k) if c.score() > k.score() => *k = c,
            Some(_) => {}
            None => kept.push(c),
        }
    }
    kept.sort_by(|a, b| b.score().total_cmp(&a.score()).then_with(|| a.title.cmp(&b.title)));
    kept.truncate(CHIPS);
    kept
}

/// The upcoming term for something `days` days away: 1 on the day, falling to 0 after [`UPCOMING_DAYS`].
pub fn upcoming(days: i64) -> f64 {
    if !(0..=UPCOMING_DAYS).contains(&days) {
        return 0.0;
    }
    1.0 - days as f64 / (UPCOMING_DAYS + 1) as f64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dict(title: &str, day_off: bool) -> Candidate {
        let e = DICTIONARY.iter().find(|e| e.title == title).unwrap();
        Candidate {
            title: e.title.into(),
            source: Source::Dictionary,
            all_day: e.all_day,
            start_minute: e.start_minute,
            end_minute: e.end_minute,
            calendar_id: None,
            context_fit: if day_off { e.day_off_fit } else { e.workday_fit },
            personal_freq: 0.0,
            upcoming: 0.0,
            recency: 0.0,
        }
    }

    fn all(day_off: bool) -> Vec<Candidate> {
        DICTIONARY.iter().map(|e| dict(e.title, day_off)).collect()
    }

    #[test]
    fn ranks_weekday_work_above_a_weekend_outing_on_a_tuesday() {
        let tuesday = rank(all(false), &[], &HashSet::new());
        assert_eq!(tuesday[0].title, "회사 업무");
        let saturday = rank(all(true), &[], &HashSet::new());
        assert_ne!(saturday[0].title, "회사 업무");
        assert!(!saturday.iter().any(|c| c.title == "회사 업무"), "a zero fit loses to the rest");
        assert_eq!(tuesday.len(), CHIPS);
    }

    #[test]
    fn drops_a_suggestion_that_overlaps_an_existing_event() {
        // A 18:30–19:30 event clashes with 운동 19–20 and 회식 19–22 but not with 회사 업무 9–18.
        let found = rank(all(false), &[(18 * 60 + 30, 19 * 60 + 30)], &HashSet::new());
        assert!(found.iter().all(|c| c.title != "운동" && c.title != "회식"));
        assert!(found.iter().any(|c| c.title == "회사 업무"));
        // A title already on the day is not offered again, even all day.
        let titles: HashSet<String> = ["약속".to_owned()].into();
        assert!(rank(all(true), &[], &titles).iter().all(|c| c.title != "약속"));
    }

    #[test]
    fn puts_a_frequent_recent_title_and_a_near_birthday_first() {
        let mut history = dict("운동", false);
        history.title = "카페 마감".into();
        history.source = Source::History;
        history.context_fit = 1.0;
        history.personal_freq = 1.0;
        history.recency = 1.0;
        let birthday = Candidate {
            title: "민수 생일 약속".into(),
            source: Source::Birthday { person_id: "p".into() },
            all_day: false,
            start_minute: 19 * 60,
            end_minute: 21 * 60,
            calendar_id: None,
            context_fit: 0.8,
            personal_freq: 0.0,
            upcoming: upcoming(0),
            recency: 0.0,
        };
        let mut candidates = all(false);
        candidates.push(birthday);
        candidates.push(history);
        let found = rank(candidates, &[], &HashSet::new());
        // History first (0.8), then the birthday today (0.52, "친구 생일이 가까우면 먼저"), then weekday work (0.4).
        assert_eq!(found[0].title, "카페 마감");
        assert_eq!(found[1].title, "민수 생일 약속");
        assert_eq!(found[2].title, "회사 업무");
        assert_eq!((upcoming(8), upcoming(-1)), (0.0, 0.0));
    }

    #[test]
    fn keeps_a_title_once_with_its_best_score() {
        let mut better = dict("운동", false);
        better.personal_freq = 1.0;
        let found = rank(vec![dict("운동", false), better.clone()], &[], &HashSet::new());
        assert_eq!(found, vec![better]);
    }

    #[test]
    fn cuts_a_night_shift_at_midnight_when_checking_the_day() {
        let night = Candidate {
            title: "마감".into(),
            source: Source::Shift { template_id: "t".into() },
            all_day: false,
            start_minute: 22 * 60,
            end_minute: 2 * 60,
            calendar_id: None,
            context_fit: 0.5,
            personal_freq: 0.0,
            upcoming: 0.0,
            recency: 0.0,
        };
        assert!(rank(vec![night.clone()], &[(23 * 60, 23 * 60 + 30)], &HashSet::new()).is_empty());
        assert_eq!(rank(vec![night.clone()], &[(60, 120)], &HashSet::new()), vec![night]);
    }
}

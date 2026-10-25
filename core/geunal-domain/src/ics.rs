//! An iCalendar (RFC 5545) file of my own events, for "ICS 내보내기" (docs/design/m1.md screen 22): what stays on the
//! device after the account is gone can be taken to another calendar app. Times are written in Asia/Seoul with the
//! zone defined inline, so any importer places them the same way.

use std::fmt::Write as _;

use chrono::{DateTime, FixedOffset, Utc};

/// One event as the file needs it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IcsEvent {
    pub uid: String,
    pub title: String,
    pub all_day: bool,
    pub starts_at: DateTime<FixedOffset>,
    /// Exclusive; for an all-day event 00:00 of the day after the last.
    pub ends_at: DateTime<FixedOffset>,
    /// The stored RFC 5545 rule, written as it is.
    pub rrule: Option<String>,
    pub exdates: Vec<DateTime<FixedOffset>>,
    pub note: Option<String>,
}

/// The whole file, CRLF line ends and lines folded at 75 octets as RFC 5545 §3.1 asks.
pub fn calendar(events: &[IcsEvent], now: DateTime<Utc>) -> String {
    let mut lines = vec![
        "BEGIN:VCALENDAR".to_owned(),
        "VERSION:2.0".to_owned(),
        "PRODID:-//geunal-eottae//calendar export//KO".to_owned(),
        "CALSCALE:GREGORIAN".to_owned(),
        // Korea has kept +09:00 without daylight saving since 1988, so one STANDARD block describes every date here.
        "BEGIN:VTIMEZONE".to_owned(),
        "TZID:Asia/Seoul".to_owned(),
        "BEGIN:STANDARD".to_owned(),
        "DTSTART:19700101T000000".to_owned(),
        "TZOFFSETFROM:+0900".to_owned(),
        "TZOFFSETTO:+0900".to_owned(),
        "TZNAME:KST".to_owned(),
        "END:STANDARD".to_owned(),
        "END:VTIMEZONE".to_owned(),
    ];
    let stamp = now.format("%Y%m%dT%H%M%SZ").to_string();
    for event in events {
        lines.push("BEGIN:VEVENT".to_owned());
        lines.push(format!("UID:{}", text(&event.uid)));
        lines.push(format!("DTSTAMP:{stamp}"));
        if event.all_day {
            lines.push(format!("DTSTART;VALUE=DATE:{}", event.starts_at.format("%Y%m%d")));
            lines.push(format!("DTEND;VALUE=DATE:{}", event.ends_at.format("%Y%m%d")));
        } else {
            lines.push(format!("DTSTART;TZID=Asia/Seoul:{}", local(event.starts_at)));
            lines.push(format!("DTEND;TZID=Asia/Seoul:{}", local(event.ends_at)));
        }
        lines.push(format!("SUMMARY:{}", text(&event.title)));
        if let Some(rule) = &event.rrule {
            lines.push(format!("RRULE:{}", rule.trim().trim_start_matches("RRULE:")));
        }
        for exdate in &event.exdates {
            lines.push(if event.all_day {
                format!("EXDATE;VALUE=DATE:{}", exdate.format("%Y%m%d"))
            } else {
                format!("EXDATE;TZID=Asia/Seoul:{}", local(*exdate))
            });
        }
        if let Some(note) = event.note.as_deref().filter(|n| !n.is_empty()) {
            lines.push(format!("DESCRIPTION:{}", text(note)));
        }
        lines.push("END:VEVENT".to_owned());
    }
    lines.push("END:VCALENDAR".to_owned());
    lines.iter().fold(String::new(), |mut out, line| {
        out.push_str(&fold(line));
        out.push_str("\r\n");
        out
    })
}

fn local(at: DateTime<FixedOffset>) -> String {
    at.with_timezone(&crate::time::KST).format("%Y%m%dT%H%M%S").to_string()
}

/// RFC 5545 §3.3.11 TEXT: backslash, semicolon and comma escaped, line breaks as `\n`.
fn text(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            ';' => out.push_str("\\;"),
            ',' => out.push_str("\\,"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            c => out.push(c),
        }
    }
    out
}

/// Folds a content line at 75 octets without splitting a UTF-8 character: Hangul takes three octets each.
fn fold(line: &str) -> String {
    let mut out = String::with_capacity(line.len() + line.len() / 70 * 3);
    let mut width = 0;
    for c in line.chars() {
        let len = c.len_utf8();
        if width + len > 75 {
            out.push_str("\r\n ");
            width = 1;
        }
        let _ = write!(out, "{c}");
        width += len;
    }
    out
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;
    use crate::time::KST;

    #[test]
    fn writes_a_timed_repeating_event_and_an_all_day_one() {
        let at = |d: u32, h: u32| KST.with_ymd_and_hms(2026, 10, d, h, 0, 0).single().unwrap();
        let events = [
            IcsEvent {
                uid: "e1@geunal".into(),
                title: "전공 수업, 2층".into(),
                all_day: false,
                starts_at: at(26, 10),
                ends_at: at(26, 12),
                rrule: Some("FREQ=WEEKLY;BYDAY=MO,WE".into()),
                exdates: vec![at(28, 10)],
                note: Some("교재\n챙기기".into()),
            },
            IcsEvent {
                uid: "e2@geunal".into(),
                title: "연차".into(),
                all_day: true,
                starts_at: at(30, 0),
                ends_at: at(31, 0),
                rrule: None,
                exdates: vec![],
                note: None,
            },
        ];
        let ics = calendar(&events, Utc.with_ymd_and_hms(2026, 10, 25, 0, 0, 0).unwrap());
        for line in [
            "DTSTART;TZID=Asia/Seoul:20261026T100000",
            "DTEND;TZID=Asia/Seoul:20261026T120000",
            "SUMMARY:전공 수업\\, 2층",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE",
            "EXDATE;TZID=Asia/Seoul:20261028T100000",
            "DESCRIPTION:교재\\n챙기기",
            "DTSTART;VALUE=DATE:20261030",
            "DTEND;VALUE=DATE:20261031",
            "DTSTAMP:20261025T000000Z",
        ] {
            assert!(ics.contains(&format!("{line}\r\n")), "missing {line:?} in\n{ics}");
        }
        assert!(ics.starts_with("BEGIN:VCALENDAR\r\n") && ics.ends_with("END:VCALENDAR\r\n"));
    }

    #[test]
    fn folds_long_lines_between_characters() {
        let line = format!("SUMMARY:{}", "가".repeat(40));
        let folded = fold(&line);
        assert!(folded.split("\r\n").all(|part| part.len() <= 75), "{folded:?}");
        assert_eq!(folded.replace("\r\n ", ""), line);
    }
}

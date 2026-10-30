//! The device's own calendar data (M1, docs/design/m1.md): calendars, events, shift templates, people, settings,
//! public holidays, suggestion counters, alpha counts and backup notes. These rows never leave the device except
//! inside the encrypted backup, and only the calendar screens and the backup read them.
//!
//! Every function runs on a [`Db`] handed out by [`Store::read`] or [`Store::write`], so a caller can group several
//! reads and writes into one transaction. Times are text: RFC 3339 with the `+09:00` offset for event instants, which
//! compare correctly as strings because every row uses the same offset.

use std::collections::HashMap;

use rusqlite::{Connection, OptionalExtension, params};

use crate::StoreError;

type Result<T> = std::result::Result<T, StoreError>;

/// One connection, or one transaction on it, for the duration of a [`crate::Store::read`] or `write` closure.
pub struct Db<'c> {
    conn: &'c Connection,
}

impl<'c> Db<'c> {
    pub(crate) fn new(conn: &'c Connection) -> Self {
        Self { conn }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CalendarRow {
    pub id: String,
    pub name: String,
    /// A palette key (`mint`, `sea`, …) or `device` for a mirrored device calendar.
    pub color: String,
    /// `personal`, `routine`, `device_mirror` or `group`.
    pub kind: String,
    /// False for a soft calendar: I see it, but groups see the time as free.
    pub blocks_free: bool,
    pub group_id: Option<String>,
    pub visible: bool,
    /// `None` follows the app default, `"off"`, or minutes before the start.
    pub default_alert: Option<String>,
    pub position: i64,
    pub device_source_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventRow {
    pub id: String,
    pub calendar_id: String,
    pub title: String,
    /// RFC 3339 with offset; for an all-day event 00:00 of its first day.
    pub starts_at: String,
    /// Exclusive; for an all-day event 00:00 of the day after its last.
    pub ends_at: String,
    pub all_day: bool,
    pub tz: String,
    /// RFC 5545 rule without the `RRULE:` prefix.
    pub rrule: Option<String>,
    /// JSON array of the original starts of removed occurrences.
    pub exdates: Option<String>,
    pub note: Option<String>,
    pub device_event_id: Option<String>,
    pub group_ref: Option<String>,
    pub tentative: bool,
    pub updated_at: String,
    /// `None` follows the calendar, `"off"`, or minutes before the start.
    pub alert: Option<String>,
    pub shift_template_id: Option<String>,
    pub created_at: Option<String>,
    /// How the event came to be: `typed`, `suggestion`, `shift`, `device` or `restore`.
    pub origin: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShiftTemplateRow {
    pub id: String,
    pub name: String,
    /// `HH:MM`.
    pub start_hm: String,
    /// `HH:MM`; at or before `start_hm` means the next day.
    pub end_hm: String,
    /// The palette key of the template's calendar when it was saved.
    pub color: String,
    pub calendar_id: Option<String>,
    pub alert: Option<String>,
    pub tint: i64,
    pub position: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PersonRow {
    pub id: String,
    pub name: String,
    /// `MM-DD` on the calendar `is_lunar` names.
    pub birthday_md: Option<String>,
    pub is_lunar: bool,
    pub leap_month: bool,
    pub birth_year: Option<i64>,
    /// `manual`, `contacts` or `group`.
    pub source: String,
    pub group_user_id: Option<String>,
    /// The group the member made the birthday public in, when `source` is `group`.
    pub group_id: Option<String>,
    pub alert: Option<String>,
    pub suggest: bool,
    pub contact_id: Option<String>,
}

/// How a title has been used on one weekday: the counter behind "personalFreq" and the repeat hint (kickoff §5.2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SuggestionStatRow {
    pub title: String,
    /// 0 = Sunday.
    pub weekday: i64,
    pub start_hm: String,
    pub end_hm: Option<String>,
    pub calendar_id: Option<String>,
    pub all_day: bool,
    pub uses: i64,
    /// RFC 3339.
    pub last_used_at: String,
    pub hint_offered: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HolidayRow {
    pub day: String,
    pub name: String,
    pub substitute: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlphaDayRow {
    pub day: String,
    pub events: i64,
    pub suggested: i64,
    pub sent_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupRecordRow {
    pub id: i64,
    pub created_at: String,
    pub size: i64,
    pub automatic: bool,
    pub events: i64,
    pub people: i64,
}

const CALENDAR_COLUMNS: &str =
    "id, name, color, kind, blocks_free, group_id, visible, default_alert, position, device_source_id";
const EVENT_COLUMNS: &str = "id, calendar_id, title, starts_at, ends_at, all_day, tz, rrule, exdates, note, \
     device_event_id, group_ref, tentative, updated_at, alert, shift_template_id, created_at, origin";
const TEMPLATE_COLUMNS: &str = "id, name, start_hm, end_hm, color, calendar_id, alert, tint, position";
const PERSON_COLUMNS: &str = "id, name, birthday_md, is_lunar, leap_month, birth_year, source, group_user_id, alert, suggest, contact_id, group_id";
const STAT_COLUMNS: &str = "title, weekday, start_hm, end_hm, calendar_id, all_day, uses, last_used_at, hint_offered";

impl Db<'_> {
    fn all<T>(
        &self,
        sql: &str,
        params: impl rusqlite::Params,
        map: impl FnMut(&rusqlite::Row<'_>) -> rusqlite::Result<T>,
    ) -> Result<Vec<T>> {
        let mut stmt = self.conn.prepare(sql)?;
        let rows = stmt.query_map(params, map)?;
        rows.map(|r| r.map_err(StoreError::from)).collect()
    }

    fn one<T>(
        &self,
        sql: &str,
        params: impl rusqlite::Params,
        map: impl FnOnce(&rusqlite::Row<'_>) -> rusqlite::Result<T>,
    ) -> Result<Option<T>> {
        Ok(self.conn.query_row(sql, params, map).optional()?)
    }

    // ----- calendars -----

    /// Every calendar, own ones in the order they were made and mirrored device calendars after them.
    pub fn calendars(&self) -> Result<Vec<CalendarRow>> {
        self.all(
            &format!(
                "SELECT {CALENDAR_COLUMNS} FROM calendars
                 ORDER BY kind = 'device_mirror', position, rowid"
            ),
            [],
            calendar_row,
        )
    }

    pub fn calendar(&self, id: &str) -> Result<Option<CalendarRow>> {
        self.one(&format!("SELECT {CALENDAR_COLUMNS} FROM calendars WHERE id = ?1"), [id], calendar_row)
    }

    pub fn put_calendar(&self, row: &CalendarRow) -> Result<()> {
        self.conn.execute(
            "INSERT INTO calendars (id, name, color, kind, blocks_free, group_id, visible, default_alert, position,
               device_source_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
             ON CONFLICT (id) DO UPDATE SET name = excluded.name, color = excluded.color, kind = excluded.kind,
               blocks_free = excluded.blocks_free, group_id = excluded.group_id, visible = excluded.visible,
               default_alert = excluded.default_alert, position = excluded.position,
               device_source_id = excluded.device_source_id",
            params![
                row.id,
                row.name,
                row.color,
                row.kind,
                row.blocks_free,
                row.group_id,
                row.visible,
                row.default_alert,
                row.position,
                row.device_source_id
            ],
        )?;
        Ok(())
    }

    /// Deletes the calendar; its events go with it through the foreign key.
    pub fn delete_calendar(&self, id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM calendars WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn next_calendar_position(&self) -> Result<i64> {
        Ok(self.conn.query_row("SELECT coalesce(max(position), -1) + 1 FROM calendars", [], |r| r.get(0))?)
    }

    /// Events per calendar, for "일정 42개" and the delete warning.
    pub fn event_counts(&self) -> Result<HashMap<String, i64>> {
        Ok(self
            .all("SELECT calendar_id, count(*) FROM events GROUP BY calendar_id", [], |r| Ok((r.get(0)?, r.get(1)?)))?
            .into_iter()
            .collect())
    }

    // ----- events -----

    pub fn put_event(&self, row: &EventRow) -> Result<()> {
        self.conn.execute(
            "INSERT INTO events (id, calendar_id, title, starts_at, ends_at, all_day, tz, rrule, exdates, note,
               device_event_id, group_ref, tentative, updated_at, alert, shift_template_id, created_at, origin)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)
             ON CONFLICT (id) DO UPDATE SET calendar_id = excluded.calendar_id, title = excluded.title,
               starts_at = excluded.starts_at, ends_at = excluded.ends_at, all_day = excluded.all_day, tz = excluded.tz,
               rrule = excluded.rrule, exdates = excluded.exdates, note = excluded.note,
               device_event_id = excluded.device_event_id, group_ref = excluded.group_ref,
               tentative = excluded.tentative, updated_at = excluded.updated_at, alert = excluded.alert,
               shift_template_id = excluded.shift_template_id, created_at = excluded.created_at,
               origin = excluded.origin",
            params![
                row.id,
                row.calendar_id,
                row.title,
                row.starts_at,
                row.ends_at,
                row.all_day,
                row.tz,
                row.rrule,
                row.exdates,
                row.note,
                row.device_event_id,
                row.group_ref,
                row.tentative,
                row.updated_at,
                row.alert,
                row.shift_template_id,
                row.created_at,
                row.origin
            ],
        )?;
        Ok(())
    }

    pub fn event(&self, id: &str) -> Result<Option<EventRow>> {
        self.one(&format!("SELECT {EVENT_COLUMNS} FROM events WHERE id = ?1"), [id], event_row)
    }

    pub fn delete_event(&self, id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM events WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Every event, by start.
    pub fn events(&self) -> Result<Vec<EventRow>> {
        self.all(&format!("SELECT {EVENT_COLUMNS} FROM events ORDER BY starts_at, id"), [], event_row)
    }

    /// Events that may show between `from` and `to` (RFC 3339): one-off events overlapping the range, and every
    /// repeating event that starts before its end, which the caller expands.
    pub fn events_between(&self, from: &str, to: &str) -> Result<Vec<EventRow>> {
        self.all(
            &format!(
                "SELECT {EVENT_COLUMNS} FROM events
                 WHERE (rrule IS NULL AND starts_at < ?2 AND ends_at > ?1) OR (rrule IS NOT NULL AND starts_at < ?2)
                 ORDER BY starts_at, id"
            ),
            params![from, to],
            event_row,
        )
    }

    /// Events placed by a group appointment (`group_ref` = appt id).
    pub fn events_for_appointment(&self, appt: &str) -> Result<Vec<EventRow>> {
        self.all(&format!("SELECT {EVENT_COLUMNS} FROM events WHERE group_ref = ?1 ORDER BY id"), [appt], event_row)
    }

    /// Stamped shifts starting in [from, to), for the stamp screen.
    pub fn shift_events_between(&self, from: &str, to: &str) -> Result<Vec<EventRow>> {
        self.all(
            &format!(
                "SELECT {EVENT_COLUMNS} FROM events
                 WHERE shift_template_id IS NOT NULL AND starts_at >= ?1 AND starts_at < ?2
                 ORDER BY starts_at, id"
            ),
            params![from, to],
            event_row,
        )
    }

    /// Turns the shifts of a deleted template into ordinary events: they stay, the template link goes (m1.md 2.3).
    pub fn detach_shift_template(&self, template_id: &str) -> Result<()> {
        self.conn.execute("UPDATE events SET shift_template_id = NULL WHERE shift_template_id = ?1", [template_id])?;
        Ok(())
    }

    /// Removes a mirrored calendar's events before the mirror writes them again.
    pub fn delete_events_of_calendar(&self, calendar_id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM events WHERE calendar_id = ?1", [calendar_id])?;
        Ok(())
    }

    // ----- shift templates -----

    pub fn shift_templates(&self) -> Result<Vec<ShiftTemplateRow>> {
        self.all(&format!("SELECT {TEMPLATE_COLUMNS} FROM shift_templates ORDER BY position, rowid"), [], template_row)
    }

    pub fn shift_template(&self, id: &str) -> Result<Option<ShiftTemplateRow>> {
        self.one(&format!("SELECT {TEMPLATE_COLUMNS} FROM shift_templates WHERE id = ?1"), [id], template_row)
    }

    pub fn put_shift_template(&self, row: &ShiftTemplateRow) -> Result<()> {
        self.conn.execute(
            "INSERT INTO shift_templates (id, name, start_hm, end_hm, color, calendar_id, alert, tint, position)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
             ON CONFLICT (id) DO UPDATE SET name = excluded.name, start_hm = excluded.start_hm,
               end_hm = excluded.end_hm, color = excluded.color, calendar_id = excluded.calendar_id,
               alert = excluded.alert, tint = excluded.tint, position = excluded.position",
            params![
                row.id,
                row.name,
                row.start_hm,
                row.end_hm,
                row.color,
                row.calendar_id,
                row.alert,
                row.tint,
                row.position
            ],
        )?;
        Ok(())
    }

    pub fn delete_shift_template(&self, id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM shift_templates WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Templates that put their shifts in this calendar, which go with it when it is deleted.
    pub fn delete_shift_templates_of_calendar(&self, calendar_id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM shift_templates WHERE calendar_id = ?1", [calendar_id])?;
        Ok(())
    }

    pub fn next_shift_template_position(&self) -> Result<i64> {
        Ok(self.conn.query_row("SELECT coalesce(max(position), -1) + 1 FROM shift_templates", [], |r| r.get(0))?)
    }

    // ----- people -----

    pub fn people(&self) -> Result<Vec<PersonRow>> {
        self.all(&format!("SELECT {PERSON_COLUMNS} FROM people ORDER BY name, id"), [], person_row)
    }

    pub fn person(&self, id: &str) -> Result<Option<PersonRow>> {
        self.one(&format!("SELECT {PERSON_COLUMNS} FROM people WHERE id = ?1"), [id], person_row)
    }

    pub fn person_by_contact(&self, contact_id: &str) -> Result<Option<PersonRow>> {
        self.one(&format!("SELECT {PERSON_COLUMNS} FROM people WHERE contact_id = ?1"), [contact_id], person_row)
    }

    pub fn put_person(&self, row: &PersonRow) -> Result<()> {
        self.conn.execute(
            "INSERT INTO people (id, name, birthday_md, is_lunar, leap_month, birth_year, source, group_user_id, alert,
               suggest, contact_id, group_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
             ON CONFLICT (id) DO UPDATE SET name = excluded.name, birthday_md = excluded.birthday_md,
               is_lunar = excluded.is_lunar, leap_month = excluded.leap_month, birth_year = excluded.birth_year,
               source = excluded.source, group_user_id = excluded.group_user_id, alert = excluded.alert,
               suggest = excluded.suggest, contact_id = excluded.contact_id, group_id = excluded.group_id",
            params![
                row.id,
                row.name,
                row.birthday_md,
                row.is_lunar,
                row.leap_month,
                row.birth_year,
                row.source,
                row.group_user_id,
                row.alert,
                row.suggest,
                row.contact_id,
                row.group_id
            ],
        )?;
        Ok(())
    }

    pub fn delete_person(&self, id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM people WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn count(&self, table: Counted) -> Result<i64> {
        Ok(self.conn.query_row(table.sql(), [], |r| r.get(0))?)
    }

    // ----- settings -----

    pub fn setting(&self, key: &str) -> Result<Option<String>> {
        self.one("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0))
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn delete_setting(&self, key: &str) -> Result<()> {
        self.conn.execute("DELETE FROM settings WHERE key = ?1", [key])?;
        Ok(())
    }

    // ----- suggestion counters -----

    pub fn suggestion_stat(&self, title: &str, weekday: i64) -> Result<Option<SuggestionStatRow>> {
        self.one(
            &format!("SELECT {STAT_COLUMNS} FROM suggestion_stats WHERE title = ?1 AND weekday = ?2"),
            params![title, weekday],
            stat_row,
        )
    }

    pub fn suggestion_stats(&self) -> Result<Vec<SuggestionStatRow>> {
        self.all(&format!("SELECT {STAT_COLUMNS} FROM suggestion_stats ORDER BY title, weekday"), [], stat_row)
    }

    pub fn put_suggestion_stat(&self, row: &SuggestionStatRow) -> Result<()> {
        self.conn.execute(
            "INSERT INTO suggestion_stats (title, weekday, start_hm, end_hm, calendar_id, all_day, uses, last_used_at,
               hint_offered)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
             ON CONFLICT (title, weekday) DO UPDATE SET start_hm = excluded.start_hm, end_hm = excluded.end_hm,
               calendar_id = excluded.calendar_id, all_day = excluded.all_day, uses = excluded.uses,
               last_used_at = excluded.last_used_at, hint_offered = excluded.hint_offered",
            params![
                row.title,
                row.weekday,
                row.start_hm,
                row.end_hm,
                row.calendar_id,
                row.all_day,
                row.uses,
                row.last_used_at,
                row.hint_offered
            ],
        )?;
        Ok(())
    }

    pub fn delete_suggestion_stat(&self, title: &str, weekday: i64) -> Result<()> {
        self.conn.execute("DELETE FROM suggestion_stats WHERE title = ?1 AND weekday = ?2", params![title, weekday])?;
        Ok(())
    }

    // ----- holidays -----

    /// Holidays the server sent for days in [from, to] (`YYYY-MM-DD`, inclusive).
    pub fn holidays_between(&self, from: &str, to: &str) -> Result<Vec<HolidayRow>> {
        self.all(
            "SELECT day, name, substitute FROM holidays WHERE day >= ?1 AND day <= ?2 ORDER BY day, name",
            params![from, to],
            |r| Ok(HolidayRow { day: r.get(0)?, name: r.get(1)?, substitute: r.get(2)? }),
        )
    }

    /// Years the server sent in full; their holidays replace the built-in table for those years.
    pub fn holiday_years(&self) -> Result<Vec<i64>> {
        self.all("SELECT year FROM holiday_years ORDER BY year", [], |r| r.get(0))
    }

    /// Replaces one year's holidays with what the server sent, all of them at once (protocol §14).
    pub fn replace_holiday_year(&self, year: i64, rows: &[HolidayRow], updated_at: &str) -> Result<()> {
        let (from, to) = (format!("{year:04}-01-01"), format!("{year:04}-12-31"));
        self.conn.execute("DELETE FROM holidays WHERE day >= ?1 AND day <= ?2", params![from, to])?;
        for row in rows {
            self.conn.execute(
                "INSERT OR REPLACE INTO holidays (day, name, substitute) VALUES (?1, ?2, ?3)",
                params![row.day, row.name, row.substitute],
            )?;
        }
        self.conn.execute(
            "INSERT INTO holiday_years (year, updated_at) VALUES (?1, ?2)
             ON CONFLICT (year) DO UPDATE SET updated_at = excluded.updated_at",
            params![year, updated_at],
        )?;
        Ok(())
    }

    // ----- alpha counts -----

    /// Adds to a day's counts; a negative delta takes back an undone add. Counts never go below zero.
    pub fn bump_alpha(&self, day: &str, events: i64, suggested: i64) -> Result<()> {
        self.conn.execute(
            "INSERT INTO alpha_stats (day, events, suggested) VALUES (?1, max(?2, 0), max(?3, 0))
             ON CONFLICT (day) DO UPDATE SET events = max(events + ?2, 0), suggested = max(suggested + ?3, 0),
               sent_at = NULL",
            params![day, events, suggested],
        )?;
        Ok(())
    }

    pub fn alpha_days(&self) -> Result<Vec<AlphaDayRow>> {
        self.all("SELECT day, events, suggested, sent_at FROM alpha_stats ORDER BY day", [], |r| {
            Ok(AlphaDayRow { day: r.get(0)?, events: r.get(1)?, suggested: r.get(2)?, sent_at: r.get(3)? })
        })
    }

    pub fn mark_alpha_sent(&self, day: &str, sent_at: &str) -> Result<()> {
        self.conn.execute("UPDATE alpha_stats SET sent_at = ?2 WHERE day = ?1", params![day, sent_at])?;
        Ok(())
    }

    /// Days before `day` are dropped once sent: the server takes at most the last 14 days (protocol §14).
    pub fn prune_alpha(&self, before_day: &str) -> Result<()> {
        self.conn.execute("DELETE FROM alpha_stats WHERE day < ?1", [before_day])?;
        Ok(())
    }

    // ----- backup notes -----

    /// Keeps the notes of `ids` only: the backups the server still has. An empty list clears them, as when "새로
    /// 시작" deletes every backup or the store changes hands.
    pub fn retain_backup_records(&self, ids: &[i64]) -> Result<()> {
        // Integers only, so they go into the statement as they are; SQLite takes an empty list.
        let keep: Vec<String> = ids.iter().map(i64::to_string).collect();
        self.conn.execute(&format!("DELETE FROM backup_records WHERE id NOT IN ({})", keep.join(",")), [])?;
        Ok(())
    }

    pub fn put_backup_record(&self, row: &BackupRecordRow) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO backup_records (id, created_at, size, automatic, events, people)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![row.id, row.created_at, row.size, row.automatic, row.events, row.people],
        )?;
        Ok(())
    }

    pub fn backup_records(&self) -> Result<Vec<BackupRecordRow>> {
        self.all(
            "SELECT id, created_at, size, automatic, events, people FROM backup_records ORDER BY created_at DESC, id DESC",
            [],
            |r| {
                Ok(BackupRecordRow {
                    id: r.get(0)?,
                    created_at: r.get(1)?,
                    size: r.get(2)?,
                    automatic: r.get(3)?,
                    events: r.get(4)?,
                    people: r.get(5)?,
                })
            },
        )
    }

    // ----- device revocation -----

    /// Forgets every group on this device and the MLS state under them, for a device another sign-in signed out
    /// (protocol §13 "기기 해제"). Own calendars, events and people stay. Group calendars and the people groups made
    /// public go too: this device is no longer a member, and one that signs in again gets them back as it rejoins.
    pub fn wipe_group_state(&self) -> Result<()> {
        self.conn.execute_batch(
            "DELETE FROM outbox;
             DELETE FROM chat_fts;
             DELETE FROM groups;
             DELETE FROM calendars WHERE kind = 'group';
             DELETE FROM people WHERE source = 'group';",
        )?;
        // mls-rs-provider-sqlite makes its tables when an engine first opens the file, so a device that never signed in
        // has none. Epochs go before their groups, which they reference.
        for table in ["epoch", "mls_group", "key_package", "psk", "kvs"] {
            let exists: bool = self.conn.query_row(
                "SELECT count(*) > 0 FROM sqlite_master WHERE type = 'table' AND name = ?1",
                [table],
                |r| r.get(0),
            )?;
            if exists {
                self.conn.execute(&format!("DELETE FROM {table}"), [])?;
            }
        }
        Ok(())
    }
}

/// Tables whose size the "나" screen shows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Counted {
    OwnCalendars,
    DeviceCalendars,
    ShiftTemplates,
    People,
    OwnEvents,
}

impl Counted {
    fn sql(self) -> &'static str {
        match self {
            Counted::OwnCalendars => "SELECT count(*) FROM calendars WHERE kind <> 'device_mirror'",
            Counted::DeviceCalendars => "SELECT count(*) FROM calendars WHERE kind = 'device_mirror'",
            Counted::ShiftTemplates => "SELECT count(*) FROM shift_templates",
            Counted::People => "SELECT count(*) FROM people",
            Counted::OwnEvents => "SELECT count(*) FROM events WHERE device_event_id IS NULL",
        }
    }
}

fn calendar_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<CalendarRow> {
    Ok(CalendarRow {
        id: r.get(0)?,
        name: r.get(1)?,
        color: r.get(2)?,
        kind: r.get(3)?,
        blocks_free: r.get(4)?,
        group_id: r.get(5)?,
        visible: r.get(6)?,
        default_alert: r.get(7)?,
        position: r.get(8)?,
        device_source_id: r.get(9)?,
    })
}

fn event_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<EventRow> {
    Ok(EventRow {
        id: r.get(0)?,
        calendar_id: r.get(1)?,
        title: r.get(2)?,
        starts_at: r.get(3)?,
        ends_at: r.get(4)?,
        all_day: r.get(5)?,
        tz: r.get(6)?,
        rrule: r.get(7)?,
        exdates: r.get(8)?,
        note: r.get(9)?,
        device_event_id: r.get(10)?,
        group_ref: r.get(11)?,
        tentative: r.get(12)?,
        updated_at: r.get(13)?,
        alert: r.get(14)?,
        shift_template_id: r.get(15)?,
        created_at: r.get(16)?,
        origin: r.get(17)?,
    })
}

fn template_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<ShiftTemplateRow> {
    Ok(ShiftTemplateRow {
        id: r.get(0)?,
        name: r.get(1)?,
        start_hm: r.get(2)?,
        end_hm: r.get(3)?,
        color: r.get(4)?,
        calendar_id: r.get(5)?,
        alert: r.get(6)?,
        tint: r.get(7)?,
        position: r.get(8)?,
    })
}

fn person_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<PersonRow> {
    Ok(PersonRow {
        id: r.get(0)?,
        name: r.get(1)?,
        birthday_md: r.get(2)?,
        is_lunar: r.get(3)?,
        leap_month: r.get(4)?,
        birth_year: r.get(5)?,
        source: r.get(6)?,
        group_user_id: r.get(7)?,
        alert: r.get(8)?,
        suggest: r.get(9)?,
        contact_id: r.get(10)?,
        group_id: r.get(11)?,
    })
}

fn stat_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<SuggestionStatRow> {
    Ok(SuggestionStatRow {
        title: r.get(0)?,
        weekday: r.get(1)?,
        start_hm: r.get(2)?,
        end_hm: r.get(3)?,
        calendar_id: r.get(4)?,
        all_day: r.get(5)?,
        uses: r.get(6)?,
        last_used_at: r.get(7)?,
        hint_offered: r.get(8)?,
    })
}

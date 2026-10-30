//! The backup archive (protocol §10 "백업"): the part of this device's own data that a restore brings back, as CBOR.
//!
//! It holds the user's own calendars with their events, the shift templates, the people the user added or imported
//! from contacts, and the settings that belong to the account. It leaves out what is rebuilt elsewhere or must stay on
//! this device: mirrored device calendars, group calendars (a restore joins the groups again), people a group member
//! made public, the suggestion counters, the alpha counts and this device's notes about its backups. The fields are the
//! archive's own rather than the store's rows, so the schema can change without changing the format. A later version
//! adds fields with defaults, and every version in core/vectors/backups.json must still restore.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::{CalendarRow, Db, EventRow, PersonRow, ShiftTemplateRow, StoreError};

/// The format this build writes. It reads this one and every earlier one.
pub const VERSION: u32 = 1;

/// The calendar kind of the user's own calendars; device mirrors and group calendars stay out.
const OWN_KIND: &str = "personal";
/// People sources the user made; `group` people come back when the groups do.
const OWN_SOURCES: [&str; 2] = ["manual", "contacts"];
/// Settings that follow the account to a new device. The rest of the table is about this device: when its data last
/// changed, whether its owner wrote the code down, and so on.
const ACCOUNT_SETTINGS: [&str; 1] = ["notifications"];
/// What a restored event says about where it came from: not an add on this device, so nothing counts it.
const RESTORED: &str = "restore";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Archive {
    pub version: u32,
    /// When the device made the archive (RFC 3339). Inside the ciphertext, so whoever can write to the server or the
    /// storage cannot pass an old backup off as new on the restore screen.
    pub made_at: String,
    #[serde(default)]
    pub calendars: Vec<Calendar>,
    #[serde(default)]
    pub events: Vec<Event>,
    #[serde(default)]
    pub shift_templates: Vec<ShiftTemplate>,
    #[serde(default)]
    pub people: Vec<Person>,
    #[serde(default)]
    pub settings: BTreeMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Calendar {
    pub id: String,
    pub name: String,
    pub color: String,
    pub blocks_free: bool,
    pub visible: bool,
    pub default_alert: Option<String>,
    pub position: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Event {
    pub id: String,
    pub calendar_id: String,
    pub title: String,
    pub starts_at: String,
    pub ends_at: String,
    pub all_day: bool,
    pub tz: String,
    pub rrule: Option<String>,
    pub exdates: Option<String>,
    pub note: Option<String>,
    pub alert: Option<String>,
    pub shift_template_id: Option<String>,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShiftTemplate {
    pub id: String,
    pub name: String,
    pub start_hm: String,
    pub end_hm: String,
    pub color: String,
    pub calendar_id: Option<String>,
    pub alert: Option<String>,
    pub tint: i64,
    pub position: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Person {
    pub id: String,
    pub name: String,
    pub birthday_md: Option<String>,
    pub is_lunar: bool,
    pub leap_month: bool,
    pub birth_year: Option<i64>,
    /// `manual` or `contacts`.
    pub source: String,
    pub alert: Option<String>,
    pub suggest: bool,
    pub contact_id: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum ArchiveError {
    /// An archive from a newer app; this one cannot know what its fields mean.
    #[error("the archive is version {0}, newer than this build reads")]
    Newer(u32),
    #[error("not a backup archive: {0}")]
    Malformed(String),
}

/// How many rows of each kind a restore wrote, for "일정 120개를 가져왔어요".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Restored {
    pub calendars: u32,
    pub events: u32,
    pub shift_templates: u32,
    pub people: u32,
}

/// The user's own events and people in a store, as the question before restoring someone else's store asks about.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct OwnData {
    pub events: u32,
    pub people: u32,
}

pub fn encode(archive: &Archive) -> Vec<u8> {
    let mut out = Vec::new();
    // Writing CBOR into a Vec has no I/O to fail, and every field serializes.
    ciborium::into_writer(archive, &mut out).expect("an archive always serializes to CBOR");
    out
}

pub fn decode(bytes: &[u8]) -> Result<Archive, ArchiveError> {
    // The version first, on its own: a newer archive may not fit this build's fields at all, and "update the app"
    // is the useful thing to say about it.
    #[derive(Deserialize)]
    struct Version {
        version: u32,
    }
    let Version { version } = ciborium::from_reader(bytes).map_err(|e| ArchiveError::Malformed(e.to_string()))?;
    if version > VERSION {
        return Err(ArchiveError::Newer(version));
    }
    ciborium::from_reader(bytes).map_err(|e| ArchiveError::Malformed(e.to_string()))
}

impl Db<'_> {
    /// Gathers what the backup carries.
    pub fn archive(&self, made_at: &str) -> Result<Archive, StoreError> {
        let calendars: Vec<CalendarRow> = self.calendars()?.into_iter().filter(|c| c.kind == OWN_KIND).collect();
        let own = |id: &str| calendars.iter().any(|c| c.id == id);
        let events = self
            .events()?
            .into_iter()
            .filter(|e| own(&e.calendar_id) && e.device_event_id.is_none() && e.group_ref.is_none())
            .map(|e| Event {
                id: e.id,
                calendar_id: e.calendar_id,
                title: e.title,
                starts_at: e.starts_at,
                ends_at: e.ends_at,
                all_day: e.all_day,
                tz: e.tz,
                rrule: e.rrule,
                exdates: e.exdates,
                note: e.note,
                alert: e.alert,
                shift_template_id: e.shift_template_id,
                updated_at: e.updated_at,
            })
            .collect();
        let shift_templates = self
            .shift_templates()?
            .into_iter()
            .map(|t| ShiftTemplate {
                id: t.id,
                name: t.name,
                start_hm: t.start_hm,
                end_hm: t.end_hm,
                color: t.color,
                calendar_id: t.calendar_id,
                alert: t.alert,
                tint: t.tint,
                position: t.position,
            })
            .collect();
        let people = self
            .people()?
            .into_iter()
            .filter(|p| OWN_SOURCES.contains(&p.source.as_str()))
            .map(|p| Person {
                id: p.id,
                name: p.name,
                birthday_md: p.birthday_md,
                is_lunar: p.is_lunar,
                leap_month: p.leap_month,
                birth_year: p.birth_year,
                source: p.source,
                alert: p.alert,
                suggest: p.suggest,
                contact_id: p.contact_id,
            })
            .collect();
        let mut settings = BTreeMap::new();
        for key in ACCOUNT_SETTINGS {
            if let Some(value) = self.setting(key)? {
                settings.insert(key.to_owned(), value);
            }
        }
        Ok(Archive {
            version: VERSION,
            made_at: made_at.to_owned(),
            calendars: calendars
                .into_iter()
                .map(|c| Calendar {
                    id: c.id,
                    name: c.name,
                    color: c.color,
                    blocks_free: c.blocks_free,
                    visible: c.visible,
                    default_alert: c.default_alert,
                    position: c.position,
                })
                .collect(),
            events,
            shift_templates,
            people,
            settings,
        })
    }

    /// Writes an archive into this store (protocol §10 "복원" 4): rows with the same id take the archive's values,
    /// and rows the archive does not have stay. An own calendar with no events or templates whose name the archive
    /// also has goes first, so the "개인" a new device makes at its first open does not sit next to the restored one.
    pub fn restore_archive(&self, archive: &Archive) -> Result<Restored, StoreError> {
        let counts = self.event_counts()?;
        let used: Vec<Option<String>> = self.shift_templates()?.into_iter().map(|t| t.calendar_id).collect();
        for local in self.calendars()? {
            let empty =
                !counts.contains_key(&local.id) && !used.iter().any(|c| c.as_deref() == Some(local.id.as_str()));
            let restored = archive.calendars.iter().any(|c| c.name == local.name && c.id != local.id);
            if local.kind == OWN_KIND && empty && restored {
                self.delete_calendar(&local.id)?;
            }
        }
        for c in &archive.calendars {
            self.put_calendar(&CalendarRow {
                id: c.id.clone(),
                name: c.name.clone(),
                color: c.color.clone(),
                kind: OWN_KIND.to_owned(),
                blocks_free: c.blocks_free,
                group_id: None,
                visible: c.visible,
                default_alert: c.default_alert.clone(),
                position: c.position,
                device_source_id: None,
            })?;
        }
        for t in &archive.shift_templates {
            self.put_shift_template(&ShiftTemplateRow {
                id: t.id.clone(),
                name: t.name.clone(),
                start_hm: t.start_hm.clone(),
                end_hm: t.end_hm.clone(),
                color: t.color.clone(),
                calendar_id: t.calendar_id.clone(),
                alert: t.alert.clone(),
                tint: t.tint,
                position: t.position,
            })?;
        }
        for e in &archive.events {
            self.put_event(&EventRow {
                id: e.id.clone(),
                calendar_id: e.calendar_id.clone(),
                title: e.title.clone(),
                starts_at: e.starts_at.clone(),
                ends_at: e.ends_at.clone(),
                all_day: e.all_day,
                tz: e.tz.clone(),
                rrule: e.rrule.clone(),
                exdates: e.exdates.clone(),
                note: e.note.clone(),
                device_event_id: None,
                group_ref: None,
                tentative: false,
                updated_at: e.updated_at.clone(),
                alert: e.alert.clone(),
                shift_template_id: e.shift_template_id.clone(),
                created_at: None,
                origin: Some(RESTORED.to_owned()),
            })?;
        }
        // Only people the user entered or imported: an archive is authenticated by the master key, but a bug on the
        // writing side must still not turn someone a group shared into a person of mine.
        let people: Vec<&Person> = archive.people.iter().filter(|p| OWN_SOURCES.contains(&p.source.as_str())).collect();
        for p in &people {
            self.put_person(&PersonRow {
                id: p.id.clone(),
                name: p.name.clone(),
                birthday_md: p.birthday_md.clone(),
                is_lunar: p.is_lunar,
                leap_month: p.leap_month,
                birth_year: p.birth_year,
                source: p.source.clone(),
                group_user_id: None,
                group_id: None,
                alert: p.alert.clone(),
                suggest: p.suggest,
                contact_id: p.contact_id.clone(),
            })?;
        }
        for (key, value) in &archive.settings {
            if ACCOUNT_SETTINGS.contains(&key.as_str()) {
                self.set_setting(key, value)?;
            }
        }
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        Ok(Restored {
            calendars: count(archive.calendars.len()),
            events: count(archive.events.len()),
            shift_templates: count(archive.shift_templates.len()),
            people: count(people.len()),
        })
    }

    /// The user's own events and people: what a backup would carry of them.
    pub fn own_data(&self) -> Result<OwnData, StoreError> {
        let archive = self.archive("")?;
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        Ok(OwnData { events: count(archive.events.len()), people: count(archive.people.len()) })
    }

    /// "지우기" before restoring another account (protocol §10 "복원" 4): removes everything an archive of this store
    /// would carry, and the suggestion counters, which remember the same titles. Device calendars stay; they follow
    /// the device.
    pub fn discard_own_data(&self) -> Result<(), StoreError> {
        for c in self.calendars()?.into_iter().filter(|c| c.kind == OWN_KIND) {
            self.delete_calendar(&c.id)?;
        }
        for t in self.shift_templates()? {
            self.delete_shift_template(&t.id)?;
        }
        for p in self.people()?.into_iter().filter(|p| OWN_SOURCES.contains(&p.source.as_str())) {
            self.delete_person(&p.id)?;
        }
        for s in self.suggestion_stats()? {
            self.delete_suggestion_stat(&s.title, s.weekday)?;
        }
        for key in ACCOUNT_SETTINGS {
            self.delete_setting(key)?;
        }
        Ok(())
    }
}

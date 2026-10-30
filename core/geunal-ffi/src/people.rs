//! The M1 people and birthday surface (docs/design/m1.md screens 11 and 17). Names and birthdays stay on the device
//! and in the encrypted backup; nothing about them goes to the server.
//!
//! A lunar birthday is stored as lunar and converted each year with the lunar table the core carries (kickoff §10:
//! 한국천문연구원 data, 1900–2049). A year outside the table gets no date rather than a guessed one.

use chrono::{Datelike, Duration, NaiveDate};
use geunal_domain::lunar::{self, LunarDate};
use geunal_store::PersonRow;

use crate::calendar::{name, new_id};
use crate::local::*;
use crate::{AlertRule, Core, CoreError};

const MANUAL: &str = "manual";
const CONTACTS: &str = "contacts";
const GROUP: &str = "group";
/// A lunar year is at most 385 days long, so the next birthday is always within this many days when it has one.
const NEXT_BIRTHDAY_DAYS: i64 = 400;
/// The earliest birth year the sheet accepts; the lunar table starts here too.
const FIRST_BIRTH_YEAR: i32 = 1900;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BirthdayCalendar {
    Solar,
    /// `leap_month` for a birthday in a leap month (윤달).
    Lunar {
        leap_month: bool,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Birthday {
    pub month: u8,
    pub day: u8,
    /// Optional ("몰라도 돼요"); no age is ever shown.
    pub year: Option<i32>,
    pub calendar: BirthdayCalendar,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum PersonSource {
    Manual,
    Contacts,
    /// A member who made their birthday public in a group (M2): name and birthday are read-only here.
    Group {
        group_id: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Person {
    pub id: String,
    pub name: String,
    pub birthday: Option<Birthday>,
    /// The next birthday on the solar calendar, today included; `None` without a birthday or when a lunar date falls
    /// outside the table.
    pub next_birthday: Option<String>,
    /// Days from today to `next_birthday`, for the D-day pill.
    pub days_until: Option<u16>,
    pub source: PersonSource,
    /// Only the alert can change when the person comes from a group.
    pub read_only: bool,
    /// `Inherit` follows the birthday alert setting (당일 09:00).
    pub alert: AlertRule,
    /// Birthdays within seven days become "OO 생일 약속" chips (kickoff §5.2).
    pub suggest: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PersonInput {
    /// `None` adds a person.
    pub id: Option<String>,
    pub name: String,
    pub birthday: Option<Birthday>,
    pub alert: AlertRule,
    pub suggest: bool,
}

/// A contact the user picked to import. The app reads Contacts (iOS `nonGregorianBirthday` gives lunar ones).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContactInput {
    /// The contact's identifier, so importing again updates instead of adding a second copy.
    pub contact_id: String,
    pub name: String,
    pub birthday: Option<Birthday>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContactImport {
    pub added: u32,
    pub updated: u32,
    /// Contacts without a birthday.
    pub skipped: u32,
}

#[uniffi::export]
impl Core {
    /// Everyone, by name.
    pub fn people(&self) -> Result<Vec<Person>, CoreError> {
        let today = kst_day(self.now());
        let rows = self.store.read(|db| db.people())?;
        Ok(rows.iter().map(|row| person(row, today)).collect())
    }

    /// People whose next birthday is at most `days` days away (0 is today), soonest first.
    pub fn upcoming_birthdays(&self, days: u16) -> Result<Vec<Person>, CoreError> {
        let mut found: Vec<Person> =
            self.people()?.into_iter().filter(|p| p.days_until.is_some_and(|d| d <= days)).collect();
        found.sort_by(|a, b| (a.days_until, &a.name, &a.id).cmp(&(b.days_until, &b.name, &b.id)));
        Ok(found)
    }

    /// Adds or updates a person and returns the id. A person a group member made public keeps the name and birthday
    /// the group shows; only `alert` is applied.
    pub fn save_person(&self, input: PersonInput) -> Result<String, CoreError> {
        let now = self.now();
        let today = kst_day(now);
        let id = self.store.write(|db| {
            let existing = match &input.id {
                Some(id) => Some(db.person(id)?.ok_or_else(|| not_found(format!("person {id}")))?),
                None => None,
            };
            let row = match existing {
                Some(mut row) if row.source == GROUP => {
                    row.alert = alert_text(&input.alert);
                    row
                }
                Some(mut row) => {
                    row.name = name(&input.name, "person")?;
                    set_birthday(&mut row, input.birthday.as_ref(), today)?;
                    row.alert = alert_text(&input.alert);
                    row.suggest = input.suggest;
                    row
                }
                None => {
                    let mut row = blank(new_id(), name(&input.name, "person")?, MANUAL);
                    set_birthday(&mut row, input.birthday.as_ref(), today)?;
                    row.alert = alert_text(&input.alert);
                    row.suggest = input.suggest;
                    row
                }
            };
            db.put_person(&row)?;
            mark_changed(db)?;
            Ok::<_, CoreError>(row.id)
        })?;
        self.calendar_changed();
        Ok(id)
    }

    /// Removes a person and their birthday from the calendar. A group member's birthday leaves with the group (M2),
    /// so it cannot be removed here.
    pub fn delete_person(&self, person_id: String) -> Result<(), CoreError> {
        self.store.write(|db| {
            let row = db.person(&person_id)?.ok_or_else(|| not_found(format!("person {person_id}")))?;
            if row.source == GROUP {
                return Err(invalid("a birthday made public in a group goes away with the group"));
            }
            db.delete_person(&row.id)?;
            mark_changed(db)
        })?;
        self.calendar_changed();
        Ok(())
    }

    /// Adds the picked contacts that have a birthday. A contact imported before is updated in place (name and
    /// birthday; its alert and suggestion switch stay as the user set them), so importing twice adds no copies.
    pub fn import_contacts(&self, contacts: Vec<ContactInput>) -> Result<ContactImport, CoreError> {
        let now = self.now();
        let today = kst_day(now);
        let mut result = ContactImport { added: 0, updated: 0, skipped: 0 };
        self.store.write(|db| {
            for contact in &contacts {
                let Some(birthday) = contact.birthday.as_ref().and_then(|b| contact_birthday(b, today)) else {
                    result.skipped += 1;
                    continue;
                };
                let Some(contact_name) = contact_name(&contact.name) else {
                    result.skipped += 1;
                    continue;
                };
                let mut row = match db.person_by_contact(&contact.contact_id)? {
                    Some(row) => {
                        result.updated += 1;
                        row
                    }
                    None => {
                        result.added += 1;
                        let mut row = blank(new_id(), String::new(), CONTACTS);
                        row.contact_id = Some(contact.contact_id.clone());
                        row
                    }
                };
                row.name = contact_name;
                set_birthday(&mut row, Some(&birthday), today)?;
                db.put_person(&row)?;
            }
            if result.added + result.updated > 0 {
                mark_changed(db)?;
            }
            Ok::<_, CoreError>(())
        })?;
        if result.added + result.updated > 0 {
            self.calendar_changed();
        }
        Ok(result)
    }

    /// The solar day a birthday falls on in `year` ("올해는 11월 11일 수요일이에요"), after the rules for a leap
    /// month the year does not have and a 30th the month does not have (docs/parts/rust-core.md §6). `None` outside
    /// the lunar table.
    pub fn birthday_in_year(&self, birthday: Birthday, year: i32) -> Result<Option<String>, CoreError> {
        // The sheet asks while the user is still typing, so only the month and day have to make sense.
        valid_day(&birthday)?;
        let found = match birthday.calendar {
            BirthdayCalendar::Solar => lunar::solar_birthday(year, u32::from(birthday.month), u32::from(birthday.day)),
            BirthdayCalendar::Lunar { leap_month } => lunar::birthday(year, birthday.month, birthday.day, leap_month),
        };
        Ok(found.map(day_text))
    }

    /// The exact solar day of a lunar date, or `None` when the table has no such date.
    pub fn lunar_to_solar(&self, year: i32, month: u8, day: u8, leap_month: bool) -> Result<Option<String>, CoreError> {
        Ok(lunar::to_solar(LunarDate { year, month, day, leap: leap_month }).map(day_text))
    }
}

fn blank(id: String, name: String, source: &str) -> PersonRow {
    PersonRow {
        id,
        name,
        birthday_md: None,
        is_lunar: false,
        leap_month: false,
        birth_year: None,
        source: source.to_owned(),
        group_user_id: None,
        alert: None,
        suggest: true,
        contact_id: None,
        group_id: None,
    }
}

fn person(row: &PersonRow, today: NaiveDate) -> Person {
    let next = person_birthdays(row, today, today + Duration::days(NEXT_BIRTHDAY_DAYS)).into_iter().next();
    Person {
        id: row.id.clone(),
        name: row.name.clone(),
        birthday: birthday_of(row),
        next_birthday: next.map(day_text),
        days_until: next.map(|d| (d - today).num_days().clamp(0, i64::from(u16::MAX)) as u16),
        source: match row.source.as_str() {
            GROUP => PersonSource::Group { group_id: row.group_id.clone().unwrap_or_default() },
            CONTACTS => PersonSource::Contacts,
            _ => PersonSource::Manual,
        },
        read_only: row.source == GROUP,
        alert: alert_rule(row.alert.as_deref()),
        suggest: row.suggest,
    }
}

/// A month and day that exist on their calendar: 29 February and a lunar 30th count, since some years have them.
fn valid_day(b: &Birthday) -> Result<(), CoreError> {
    let exists = match b.calendar {
        // 2024 is a leap year, so every day a solar birthday can be on exists in it.
        BirthdayCalendar::Solar => NaiveDate::from_ymd_opt(2024, u32::from(b.month), u32::from(b.day)).is_some(),
        BirthdayCalendar::Lunar { .. } => (1..=12).contains(&b.month) && (1..=30).contains(&b.day),
    };
    if !exists {
        return Err(invalid(format!("no birthday on {}-{}", b.month, b.day)));
    }
    Ok(())
}

/// [`valid_day`], and a birth year from 1900 to this year that has the day.
fn valid_birthday(b: &Birthday, today: NaiveDate) -> Result<(), CoreError> {
    valid_day(b)?;
    if let Some(year) = b.year {
        if !(FIRST_BIRTH_YEAR..=today.year()).contains(&year) {
            return Err(invalid(format!("a birth year from {FIRST_BIRTH_YEAR} to this year, not {year}")));
        }
        if b.calendar == BirthdayCalendar::Solar
            && NaiveDate::from_ymd_opt(year, u32::from(b.month), u32::from(b.day)).is_none()
        {
            return Err(invalid(format!("{year} has no {}-{}", b.month, b.day)));
        }
    }
    Ok(())
}

fn set_birthday(row: &mut PersonRow, birthday: Option<&Birthday>, today: NaiveDate) -> Result<(), CoreError> {
    match birthday {
        None => {
            row.birthday_md = None;
            row.is_lunar = false;
            row.leap_month = false;
            row.birth_year = None;
        }
        Some(b) => {
            valid_birthday(b, today)?;
            row.birthday_md = Some(format!("{:02}-{:02}", b.month, b.day));
            (row.is_lunar, row.leap_month) = match b.calendar {
                BirthdayCalendar::Solar => (false, false),
                BirthdayCalendar::Lunar { leap_month } => (true, leap_month),
            };
            row.birth_year = b.year.map(i64::from);
        }
    }
    Ok(())
}

/// A contact's birthday as a person's. A year the sheet would refuse is dropped instead of the contact: some address
/// books keep a placeholder such as 1604 for a birthday saved without one.
fn contact_birthday(b: &Birthday, today: NaiveDate) -> Option<Birthday> {
    if valid_birthday(b, today).is_ok() {
        return Some(b.clone());
    }
    let without_year = Birthday { year: None, ..b.clone() };
    valid_birthday(&without_year, today).is_ok().then_some(without_year)
}

/// A contact's display name as a person's name: trimmed and cut at 40 characters, `None` when nothing is left.
fn contact_name(text: &str) -> Option<String> {
    let trimmed = text.trim();
    (!trimmed.is_empty()).then(|| trimmed.chars().take(40).collect::<String>().trim_end().to_owned())
}

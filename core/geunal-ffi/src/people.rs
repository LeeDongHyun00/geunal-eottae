//! The M1 people and birthday surface (docs/design/m1.md screens 11 and 17). Names and birthdays stay on the device
//! and in the encrypted backup; nothing about them goes to the server.
//!
//! A lunar birthday is stored as lunar and converted each year with the lunar table the core carries (kickoff §10:
//! 한국천문연구원 data, 1900–2049). A year outside the table gets no date rather than a guessed one.

use crate::{AlertRule, Core, CoreError, not_yet};

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
        Err(not_yet("people"))
    }

    /// People whose birthday falls within the next `days` days (today included), soonest first.
    pub fn upcoming_birthdays(&self, days: u16) -> Result<Vec<Person>, CoreError> {
        let _ = days;
        Err(not_yet("upcoming_birthdays"))
    }

    /// Adds or updates a person and returns the id.
    pub fn save_person(&self, input: PersonInput) -> Result<String, CoreError> {
        let _ = input;
        Err(not_yet("save_person"))
    }

    pub fn delete_person(&self, person_id: String) -> Result<(), CoreError> {
        let _ = person_id;
        Err(not_yet("delete_person"))
    }

    pub fn import_contacts(&self, contacts: Vec<ContactInput>) -> Result<ContactImport, CoreError> {
        let _ = contacts;
        Err(not_yet("import_contacts"))
    }

    /// The solar day a birthday falls on in `year` ("올해는 11월 11일 수요일이에요"), after the rules for a leap
    /// month the year does not have and a 30th the month does not have (docs/parts/rust-core.md §6). `None` outside
    /// the lunar table.
    pub fn birthday_in_year(&self, birthday: Birthday, year: i32) -> Result<Option<String>, CoreError> {
        let _ = (birthday, year);
        Err(not_yet("birthday_in_year"))
    }

    /// The exact solar day of a lunar date, or `None` when the table has no such date.
    pub fn lunar_to_solar(&self, year: i32, month: u8, day: u8, leap_month: bool) -> Result<Option<String>, CoreError> {
        let _ = (year, month, day, leap_month);
        Err(not_yet("lunar_to_solar"))
    }
}

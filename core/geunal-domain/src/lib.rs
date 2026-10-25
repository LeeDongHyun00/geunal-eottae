//! What every device must compute identically from the same log (kickoff §3): entries and their CBOR envelope
//! (protocol §4), calendar expansion, availability bitmaps and the group calendar, date-setting and polls, suggestions,
//! holidays and the chat timeline. Pure functions only: no I/O, clock or randomness, so one log gives one state.
#![forbid(unsafe_code)]

pub mod appointments;
pub mod availability;
pub mod calendar;
pub mod entry;
pub mod time;

pub use time::{KST, Kst};

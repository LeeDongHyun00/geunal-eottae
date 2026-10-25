//! Every MLS application message in a group carries exactly one Entry, CBOR-encoded (protocol §4). The shapes here are
//! the contract; changing them means changing protocol.md and core/vectors first.

use serde::{Deserialize, Serialize};

/// serde writes a `Vec<u8>` as an array of integers; CBOR has byte strings, which are a third of the size for keys,
/// hashes and bitmaps. Every byte field in the contract uses this (the sizes in protocol §4 and §5 assume it).
pub mod cbor_bytes {
    use serde::de::{Error as _, SeqAccess, Visitor};
    use serde::{Deserializer, Serializer};

    pub fn serialize<S: Serializer>(bytes: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_bytes(bytes)
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        struct Bytes;
        impl<'de> Visitor<'de> for Bytes {
            type Value = Vec<u8>;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("a byte string")
            }
            fn visit_bytes<E: serde::de::Error>(self, v: &[u8]) -> Result<Vec<u8>, E> {
                Ok(v.to_vec())
            }
            fn visit_byte_buf<E: serde::de::Error>(self, v: Vec<u8>) -> Result<Vec<u8>, E> {
                Ok(v)
            }
            // An array of small integers is accepted too, so an encoder without byte strings still interoperates.
            fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Vec<u8>, A::Error> {
                let mut out = Vec::with_capacity(seq.size_hint().unwrap_or(0));
                while let Some(b) = seq.next_element::<u64>()? {
                    out.push(u8::try_from(b).map_err(|_| A::Error::custom("byte out of range"))?);
                }
                Ok(out)
            }
        }
        d.deserialize_byte_buf(Bytes)
    }

    pub mod option {
        use serde::{Deserialize, Deserializer, Serializer};

        pub fn serialize<S: Serializer>(bytes: &Option<Vec<u8>>, s: S) -> Result<S::Ok, S::Error> {
            match bytes {
                Some(b) => s.serialize_some(&serde_bytes_wrapper::Wrap(b)),
                None => s.serialize_none(),
            }
        }

        pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Vec<u8>>, D::Error> {
            Option::<serde_bytes_wrapper::Owned>::deserialize(d).map(|o| o.map(|w| w.0))
        }

        mod serde_bytes_wrapper {
            use serde::{Deserialize, Deserializer, Serialize, Serializer};

            pub struct Wrap<'a>(pub &'a [u8]);
            impl Serialize for Wrap<'_> {
                fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
                    super::super::serialize(self.0, s)
                }
            }

            pub struct Owned(pub Vec<u8>);
            impl<'de> Deserialize<'de> for Owned {
                fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
                    super::super::deserialize(d).map(Owned)
                }
            }
        }
    }
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    Vote,
    Dictator,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Time,
    Activity,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Choice {
    Opt(String),
    Any,
}

// Times are minutes after 00:00 Asia/Seoul on `day`; an appointment never crosses midnight in the MVP.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(tag = "k", rename_all = "snake_case")]
pub enum Entry {
    Text {
        id: String,
        body: String,
        reply_to: Option<String>,
    },
    // `appt` set: an attachment of that appointment, not a chat message. `name` is the file name shown for files.
    Media {
        id: String,
        object: String,
        #[serde(with = "cbor_bytes")]
        key: Vec<u8>,
        size: u64,
        mime: String,
        #[serde(with = "cbor_bytes")]
        sha256: Vec<u8>,
        #[serde(with = "cbor_bytes::option")]
        thumb: Option<Vec<u8>>,
        name: Option<String>,
        appt: Option<String>,
    },
    MediaRemove {
        id: String,
    },
    // `leader_options` copies the group setting at request time so every device applies the same rule to this
    // appointment.
    Request {
        appt: String,
        day: String,
        mode: Mode,
        start: Option<u16>,
        end: Option<u16>,
        activity: Option<String>,
        note: Option<String>,
        #[serde(default)]
        leader_options: bool,
    },
    RequestUpdate {
        appt: String,
        mode: Option<Mode>,
        start: Option<u16>,
        end: Option<u16>,
        activity: Option<String>,
        note: Option<String>,
    },
    LeadTransfer {
        appt: String,
        to: String,
    },
    Response {
        appt: String,
        accept: bool,
    },
    Poll {
        appt: String,
        stage: Stage,
        options: Vec<String>,
    },
    PollOption {
        appt: String,
        stage: Stage,
        option: String,
    },
    PollVote {
        appt: String,
        stage: Stage,
        choice: Choice,
    },
    PollClose {
        appt: String,
        stage: Stage,
    },
    Cancel {
        appt: String,
    },
}

impl Entry {
    /// The `k` tag, as stored in `entries.kind`.
    pub fn kind(&self) -> &'static str {
        match self {
            Entry::Text { .. } => "text",
            Entry::Media { .. } => "media",
            Entry::MediaRemove { .. } => "media_remove",
            Entry::Request { .. } => "request",
            Entry::RequestUpdate { .. } => "request_update",
            Entry::LeadTransfer { .. } => "lead_transfer",
            Entry::Response { .. } => "response",
            Entry::Poll { .. } => "poll",
            Entry::PollOption { .. } => "poll_option",
            Entry::PollVote { .. } => "poll_vote",
            Entry::PollClose { .. } => "poll_close",
            Entry::Cancel { .. } => "cancel",
        }
    }
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
pub struct Envelope {
    pub v: u8,
    pub entry: Entry,
}

/// The envelope version this build writes.
pub const ENVELOPE_VERSION: u8 = 1;

/// An entry is at most 64KB (protocol §4); bigger content goes out as media.
pub const MAX_ENTRY_BYTES: usize = 64 * 1024;

pub type DecodeError = ciborium::de::Error<std::io::Error>;

pub fn encode(entry: &Entry) -> Vec<u8> {
    let mut out = Vec::new();
    ciborium::into_writer(&Envelope { v: ENVELOPE_VERSION, entry: entry.clone() }, &mut out).expect("in-memory write");
    out
}

pub fn decode(bytes: &[u8]) -> Result<Entry, DecodeError> {
    ciborium::from_reader::<Envelope, _>(bytes).map(|e| e.entry)
}

/// The `k` tag of an envelope this build may not know, so an entry from a newer version can still be stored by kind
/// and shown as "업데이트가 필요한 메시지" (protocol §4).
pub fn peek_kind(bytes: &[u8]) -> Option<String> {
    #[derive(Deserialize)]
    struct Tag {
        k: String,
    }
    #[derive(Deserialize)]
    struct Loose {
        entry: Tag,
    }
    ciborium::from_reader::<Loose, _>(bytes).ok().map(|l| l.entry.k)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_every_kind() {
        let entries = vec![
            Entry::Text { id: "t".into(), body: "토요일 7시 어때?".into(), reply_to: None },
            Entry::Request {
                appt: "a".into(),
                day: "2026-11-07".into(),
                mode: Mode::Vote,
                start: None,
                end: None,
                activity: None,
                note: Some("저녁".into()),
                leader_options: false,
            },
            Entry::Media {
                id: "m".into(),
                object: "o/1".into(),
                key: vec![1; 32],
                size: 10,
                mime: "image/jpeg".into(),
                sha256: vec![2; 32],
                thumb: Some(vec![3; 4]),
                name: None,
                appt: Some("a".into()),
            },
            Entry::Media {
                id: "m2".into(),
                object: "o/2".into(),
                key: vec![1; 32],
                size: 10,
                mime: "text/plain".into(),
                sha256: vec![2; 32],
                thumb: None,
                name: Some("a.txt".into()),
                appt: None,
            },
            Entry::PollVote { appt: "a".into(), stage: Stage::Time, choice: Choice::Any },
            Entry::PollVote { appt: "a".into(), stage: Stage::Activity, choice: Choice::Opt("영화".into()) },
            Entry::Cancel { appt: "a".into() },
        ];
        for entry in entries {
            assert_eq!(decode(&encode(&entry)).unwrap(), entry);
        }
    }

    #[test]
    fn a_short_chat_message_stays_far_under_the_entry_limit() {
        let entry = Entry::Text {
            id: "0192f1e0-1234-7abc-8def-0123456789ab".into(),
            body: "토요일 7시 어때?".into(),
            reply_to: None,
        };
        assert!(encode(&entry).len() < 128);
    }

    #[test]
    fn writes_byte_fields_as_cbor_byte_strings() {
        let entry = Entry::Media {
            id: "m".into(),
            object: "o".into(),
            key: vec![0xab; 32],
            size: 1,
            mime: "x".into(),
            sha256: vec![0xcd; 32],
            thumb: None,
            name: None,
            appt: None,
        };
        let bytes = encode(&entry);
        // 0x58 0x20: a 32-byte string. As an array the key would take 65 bytes instead of 34.
        assert!(bytes.windows(3).any(|w| w == [0x58, 0x20, 0xab]));
        assert!(bytes.len() < 160, "{}", bytes.len());
    }

    #[test]
    fn missing_leader_options_defaults_to_false() {
        // An older build's Request has no leader_options; the default keeps the pre-flag rule (anyone adds options).
        let json = serde_json::json!({"v": 1, "entry": {"k": "request", "appt": "a", "day": "2026-11-07", "mode": "vote",
            "start": null, "end": null, "activity": null, "note": null}});
        let mut bytes = Vec::new();
        ciborium::into_writer(&json, &mut bytes).unwrap();
        assert!(matches!(decode(&bytes).unwrap(), Entry::Request { leader_options: false, .. }));
    }

    #[test]
    fn peeks_the_kind_of_an_unknown_entry() {
        let json = serde_json::json!({"v": 2, "entry": {"k": "reaction", "id": "x", "emoji": "👍"}});
        let mut bytes = Vec::new();
        ciborium::into_writer(&json, &mut bytes).unwrap();
        assert!(decode(&bytes).is_err());
        assert_eq!(peek_kind(&bytes).as_deref(), Some("reaction"));
    }
}

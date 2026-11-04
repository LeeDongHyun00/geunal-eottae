//! The `group_meta` state blob (protocol §5): what any member may change about the group, and how one member's change
//! is laid over whatever the group holds now. Members change different fields at once, so a write that lost a race
//! re-applies only the fields its member changed on top of the newer blob (design m2 decision 9).

use serde::{Deserialize, Serialize};

use crate::entry::Mode;

/// A file sealed in object storage: the cover here (protocol §5, §9).
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct MediaRef {
    pub object: String,
    #[serde(with = "crate::entry::cbor_bytes")]
    pub key: Vec<u8>,
    #[serde(with = "crate::entry::cbor_bytes")]
    pub sha256: Vec<u8>,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum OptionsBy {
    Anyone,
    Leader,
}

/// The date-setting defaults a request copies when it is made (protocol §4 "그룹 설정은 신청에 담습니다").
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
pub struct Plan {
    pub mode: Mode,
    /// 30-minute slots of the day, [from, to).
    pub window: [u16; 2],
    pub options_by: OptionsBy,
}

impl Default for Plan {
    fn default() -> Self {
        let (from, to) = crate::availability::DEFAULT_WINDOW;
        Self { mode: Mode::Vote, window: [from as u16, to as u16], options_by: OptionsBy::Anyone }
    }
}

/// The plaintext of a `group_meta` blob.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct GroupMeta {
    pub name: String,
    /// One of the twelve `color.group.*` names (`invite::GROUP_COLORS`).
    pub color: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cover: Option<MediaRef>,
    pub plan: Plan,
    /// Who last changed a field; a device that only re-seals the blob for a new epoch leaves it as it is.
    pub changed_by: String,
    /// RFC 3339, the changing device's clock: for "마지막으로 바꾼 사람 · 어제" only.
    pub changed_at: String,
}

impl GroupMeta {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        ciborium::into_writer(self, &mut out).expect("in-memory write");
        out
    }

    pub fn decode(bytes: &[u8]) -> Option<Self> {
        ciborium::from_reader(bytes).ok()
    }
}

/// One member's change: only the fields they touched. `cover: Some(None)` takes the cover away.
#[derive(Serialize, Deserialize, Debug, Clone, Default, PartialEq, Eq)]
pub struct MetaChange {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub color: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", deserialize_with = "present")]
    pub cover: Option<Option<MediaRef>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mode: Option<Mode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub window: Option<[u16; 2]>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub options_by: Option<OptionsBy>,
}

impl MetaChange {
    pub fn is_empty(&self) -> bool {
        *self == Self::default()
    }

    /// The change laid over `base`, the newest blob the group holds (`None` before anyone wrote one). Fields the
    /// change leaves alone keep `base`'s values, so two members changing different fields both keep their change.
    pub fn apply(&self, base: Option<&GroupMeta>, by: &str, at: &str) -> GroupMeta {
        let mut meta = base.cloned().unwrap_or_else(|| GroupMeta {
            name: String::new(),
            color: crate::invite::GROUP_COLORS[0].to_owned(),
            cover: None,
            plan: Plan::default(),
            changed_by: String::new(),
            changed_at: String::new(),
        });
        if let Some(name) = &self.name {
            meta.name.clone_from(name);
        }
        if let Some(color) = &self.color {
            meta.color.clone_from(color);
        }
        if let Some(cover) = &self.cover {
            meta.cover.clone_from(cover);
        }
        if let Some(mode) = self.mode {
            meta.plan.mode = mode;
        }
        if let Some(window) = self.window {
            meta.plan.window = window;
        }
        if let Some(options_by) = self.options_by {
            meta.plan.options_by = options_by;
        }
        meta.changed_by = by.to_owned();
        meta.changed_at = at.to_owned();
        meta
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

/// A field that is there, even as null, is `Some`: `cover: null` means "take the cover away", not "left alone".
fn present<'de, D: serde::Deserializer<'de>, T: Deserialize<'de>>(d: D) -> Result<Option<Option<T>>, D::Error> {
    Option::<T>::deserialize(d).map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> GroupMeta {
        MetaChange { name: Some("동네 친구".into()), color: Some("mint".into()), ..Default::default() }.apply(
            None,
            "a",
            "2026-11-01T09:00:00Z",
        )
    }

    #[test]
    fn re_applies_only_the_changed_fields_over_a_newer_blob() {
        // B renamed the group while A was changing its colour: A's write lays only the colour over B's blob.
        let newer = MetaChange { name: Some("주말모임".into()), ..Default::default() }.apply(Some(&base()), "b", "t1");
        let mine = MetaChange { color: Some("sky".into()), ..Default::default() };
        let merged = mine.apply(Some(&newer), "a", "t2");
        assert_eq!((merged.name.as_str(), merged.color.as_str()), ("주말모임", "sky"));
        assert_eq!(merged.changed_by, "a");
    }

    #[test]
    fn reads_the_blob_the_state_vector_pins() {
        let file: serde_json::Value =
            serde_json::from_str(include_str!("../../vectors/state_blobs.json")).expect("vector file");
        let blob = file["blobs"].as_array().unwrap().iter().find(|b| b["kind"] == "group_meta").unwrap();
        let plaintext = hex::decode(blob["plaintext_hex"].as_str().unwrap()).unwrap();
        let meta = GroupMeta::decode(&plaintext).expect("decodes");
        assert_eq!(meta.name, "동네 친구");
        assert_eq!(meta.plan, Plan { mode: Mode::Vote, window: [18, 42], options_by: OptionsBy::Anyone });
        assert_eq!(GroupMeta::decode(&meta.encode()), Some(meta));
    }

    #[test]
    fn takes_the_cover_away_with_an_explicit_none() {
        let cover = MediaRef { object: "cover/1".into(), key: vec![1; 32], sha256: vec![2; 32] };
        let with = MetaChange { cover: Some(Some(cover)), ..Default::default() }.apply(Some(&base()), "a", "t");
        let without = MetaChange { cover: Some(None), ..Default::default() }.apply(Some(&with), "a", "t");
        assert!(with.cover.is_some() && without.cover.is_none());
        assert_eq!(
            MetaChange::decode(&MetaChange { cover: Some(None), ..Default::default() }.encode()).unwrap().cover,
            Some(None)
        );
    }
}

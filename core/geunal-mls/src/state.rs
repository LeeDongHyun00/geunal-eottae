//! State blobs (availability, group meta, member profile, newcomer bundle) are sealed with a per-epoch key from the
//! MLS exporter, so any member of the epoch can read them any number of times and the server can overwrite them
//! (protocol §5).

pub use geunal_crypto::Key;

/// The exporter label; the key changes with every epoch.
pub const EXPORTER_LABEL: &[u8] = b"geunal state v1";

/// AAD pins a blob to its group, epoch, kind and owner so the server cannot move it to another slot.
pub fn state_aad(group_id: &str, epoch: u64, kind: &str, owner: &str) -> String {
    format!("state|{group_id}|{epoch}|{kind}|{owner}")
}

/// Random 24-byte nonce ‖ ciphertext ‖ 16-byte tag.
pub fn seal(key: &Key, aad: &str, plaintext: &[u8]) -> Vec<u8> {
    geunal_crypto::seal(key, aad.as_bytes(), plaintext)
}

pub fn open(key: &Key, aad: &str, blob: &[u8]) -> Option<Vec<u8>> {
    geunal_crypto::open(key, aad.as_bytes(), blob)
}

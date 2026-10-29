//! One-shot XChaCha20-Poly1305 with associated data: the state blob format of protocol §5, and the wrapper for the
//! account master key (kickoff §6).
//!
//! The blob is `nonce (24) ‖ ciphertext ‖ tag (16)`. XChaCha's 192-bit nonce is what makes a random nonce safe here:
//! the state key lives for a whole epoch and seals an unbounded number of blobs, so there is no counter to keep in
//! sync between devices, and the collision probability of random 24-byte nonces is negligible.

use chacha20poly1305::aead::{AeadInPlace, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand_core::{OsRng, RngCore};

/// Key length of XChaCha20-Poly1305.
pub const KEY_LEN: usize = 32;
/// Nonce length of XChaCha20-Poly1305 (the X variant; the plain IETF variant would be 12).
pub const NONCE_LEN: usize = 24;
/// Poly1305 tag length.
pub const TAG_LEN: usize = 16;

/// A 256-bit symmetric key: state key, media key, AMK or recovery key.
pub type Key = [u8; KEY_LEN];

/// A fresh key from the operating system's CSPRNG.
pub fn random_key() -> Key {
    let mut key = [0u8; KEY_LEN];
    OsRng.fill_bytes(&mut key);
    key
}

/// Seals `plaintext` under `key`, binding it to `aad`. Returns `nonce ‖ ciphertext ‖ tag`.
pub fn seal(key: &Key, aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    seal_at(key, &nonce, aad, plaintext)
}

/// [`seal`] with a caller-chosen nonce. Only for test vectors (core/vectors/state_blobs.json), which must be
/// reproducible; production code never reuses a nonce under a key, so it always goes through [`seal`]. Behind the
/// `test-vectors` feature, which only the vector tests turn on, so other code cannot call it by mistake.
#[cfg(feature = "test-vectors")]
pub fn seal_with_nonce(key: &Key, nonce: &[u8; NONCE_LEN], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    seal_at(key, nonce, aad, plaintext)
}

fn seal_at(key: &Key, nonce: &[u8; NONCE_LEN], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let nonce = *nonce;
    // Encrypt in place behind the nonce so the blob is assembled in a single allocation.
    let mut blob = Vec::with_capacity(NONCE_LEN + plaintext.len() + TAG_LEN);
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(plaintext);
    let tag = XChaCha20Poly1305::new(key.into())
        .encrypt_in_place_detached(&XNonce::from(nonce), aad, &mut blob[NONCE_LEN..])
        // The detached form writes into a buffer we already sized, and XChaCha20-Poly1305 has no message length limit
        // a `usize` can reach, so the only error path of the trait cannot occur. A panic here would be a bug in this
        // crate, not a property of the input.
        .expect("in-place XChaCha20-Poly1305 encryption of a correctly sized buffer cannot fail");
    blob.extend_from_slice(&tag);
    blob
}

/// Opens a blob produced by [`seal`]. `None` when the blob is too short to hold a nonce and a tag, or when the key,
/// the AAD or the ciphertext does not match. Callers get no detail on purpose: every failure means the same thing,
/// "do not use this".
pub fn open(key: &Key, aad: &[u8], blob: &[u8]) -> Option<Vec<u8>> {
    if blob.len() < NONCE_LEN + TAG_LEN {
        return None;
    }
    let (nonce, sealed) = blob.split_at(NONCE_LEN);
    let nonce: &[u8; NONCE_LEN] = nonce.try_into().ok()?;
    chacha20poly1305::aead::Aead::decrypt(
        &XChaCha20Poly1305::new(key.into()),
        &XNonce::from(*nonce),
        Payload { msg: sealed, aad },
    )
    .ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const AAD: &[u8] = b"state|g1|7|availability|m1";

    #[test]
    fn seal_then_open_returns_the_plaintext() {
        let key = random_key();
        let blob = seal(&key, AAD, b"hello");
        assert_eq!(open(&key, AAD, &blob), Some(b"hello".to_vec()));
    }

    #[test]
    fn blob_layout_is_nonce_ciphertext_tag() {
        let key = random_key();
        let blob = seal(&key, AAD, b"0123456789");
        assert_eq!(blob.len(), NONCE_LEN + 10 + TAG_LEN);
    }

    #[test]
    fn open_with_different_aad_fails() {
        let key = random_key();
        let blob = seal(&key, AAD, b"hello");
        assert_eq!(open(&key, b"state|g1|8|availability|m1", &blob), None);
    }

    #[test]
    fn open_with_different_key_fails() {
        let blob = seal(&random_key(), AAD, b"hello");
        assert_eq!(open(&random_key(), AAD, &blob), None);
    }

    #[test]
    fn open_of_short_or_truncated_blob_returns_none_without_panicking() {
        let key = random_key();
        assert_eq!(open(&key, AAD, b""), None);
        assert_eq!(open(&key, AAD, &[0u8; NONCE_LEN + TAG_LEN - 1]), None);
        let blob = seal(&key, AAD, b"hello");
        assert_eq!(open(&key, AAD, &blob[..blob.len() - 1]), None);
    }

    #[test]
    fn open_of_tampered_blob_fails_in_every_region() {
        let key = random_key();
        let blob = seal(&key, AAD, b"hello");
        // Nonce, ciphertext and tag each get one flipped bit.
        for index in [0, NONCE_LEN, NONCE_LEN + 2, blob.len() - 1] {
            let mut tampered = blob.clone();
            tampered[index] ^= 0x01;
            assert_eq!(open(&key, AAD, &tampered), None, "byte {index} tampered");
        }
    }

    #[test]
    fn empty_plaintext_round_trips() {
        let key = random_key();
        let blob = seal(&key, AAD, b"");
        assert_eq!(blob.len(), NONCE_LEN + TAG_LEN);
        assert_eq!(open(&key, AAD, &blob), Some(Vec::new()));
    }

    #[test]
    fn two_seals_of_the_same_message_differ_because_the_nonce_is_random() {
        let key = random_key();
        let a = seal(&key, AAD, b"hello");
        let b = seal(&key, AAD, b"hello");
        assert_ne!(a, b);
        assert_ne!(a[..NONCE_LEN], b[..NONCE_LEN]);
    }

    #[test]
    fn random_keys_are_distinct() {
        assert_ne!(random_key(), random_key());
    }
}

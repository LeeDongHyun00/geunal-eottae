//! Key derivation for the recovery code and the account master key (protocol §10, kickoff §6).
//!
//! One Argon2id over the code's 16-byte entropy `E` gives the root `K`, and every value the server keeps comes from
//! `K` by HKDF-Expand. Even a full copy of the server's database then costs one 64 MiB Argon2id per guess of `E`,
//! which matters when part of a written-down code is seen: four unknown words still leave about 2^40 guesses. The
//! parameters follow the OWASP "first recommended" Argon2id setting with three passes, because this runs once per
//! sign-up or restore and the cost is paid by a phone, not a server. The 64 MiB is also why `docs/parts/rust-core.md`
//! §8 keeps this path out of the iOS share extension and its memory limit.

use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use sha2::Sha256;

use crate::aead::{self, KEY_LEN, Key, NONCE_LEN};

/// The fixed Argon2id salt. A per-account salt guards against precomputation, which a 128-bit `E` already rules out,
/// and a guest coming back on a new device must derive `K` before the server knows which account it is.
pub const SALT: &[u8] = b"geunal recovery v1";
/// Argon2id memory, in KiB (64 MiB).
pub const MEMORY_KIB: u32 = 64 * 1024;
/// Argon2id passes.
pub const ITERATIONS: u32 = 3;
/// Argon2id lanes. One: the derivation is not on a hot path and phones gain little from more.
pub const PARALLELISM: u32 = 1;
/// Derived key length; `K` is the HKDF pseudorandom key for SHA-256.
pub const OUTPUT_LEN: usize = KEY_LEN;
/// AAD of the wrapped AMK. Versioned so a later wrapping scheme can coexist with blobs written by this one.
pub const AMK_AAD: &[u8] = b"geunal amk v1";
/// The wrapped AMK: `nonce (24) ‖ ciphertext (32) ‖ tag (16)`.
pub const WRAPPED_AMK_LEN: usize = NONCE_LEN + KEY_LEN + aead::TAG_LEN;

const BACKUP_KEY_INFO: &[u8] = b"geunal backup key v1";

// Validated at compile time: `Params::new` is `const`, so a bad constant above fails the build instead of a restore.
const PARAMS: Params = match Params::new(MEMORY_KIB, ITERATIONS, PARALLELISM, Some(OUTPUT_LEN)) {
    Ok(params) => params,
    Err(_) => panic!("Argon2id parameters are out of range"),
};

/// Why a derivation failed. Nothing here is expected in practice: the parameters are compile-time checked and the
/// input lengths are fixed by the types, so this surfaces only an internal error of the Argon2 implementation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum KdfError {
    #[error("argon2id failed: {0}")]
    Argon2(argon2::Error),
}

/// The Argon2id parameters this crate uses, for display and for test vectors.
pub fn params() -> Params {
    PARAMS.clone()
}

/// `K`, the root every recovery value comes from: Argon2id over the code's entropy with the fixed [`SALT`].
pub fn recovery_root(entropy: &[u8]) -> Result<Key, KdfError> {
    let mut root = [0u8; KEY_LEN];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params())
        .hash_password_into(entropy, SALT, &mut root)
        .map_err(KdfError::Argon2)?;
    Ok(root)
}

/// HKDF-SHA256-Expand with `prk` as the pseudorandom key. Both inputs that use it, `K` and the AMK, are uniformly
/// random 32-byte keys already, so the Extract step would add nothing.
pub fn expand<const N: usize>(prk: &Key, info: &[u8]) -> [u8; N] {
    let mut out = [0u8; N];
    // A 32-byte PRK is SHA-256's own length, and every caller asks for at most 32 bytes, far below HKDF's limit of
    // 255 blocks, so neither error of the hkdf crate can occur.
    Hkdf::<Sha256>::from_prk(prk)
        .expect("a 32-byte key is a valid HKDF-SHA256 PRK")
        .expand(info, &mut out)
        .expect("HKDF-SHA256 can expand to 32 bytes");
    out
}

/// The key that seals backups (protocol §10 "암호화"). Derived rather than the AMK itself, so a later use of the AMK
/// does not share a key with the backups.
pub fn backup_key(amk: &Key) -> Key {
    expand(amk, BACKUP_KEY_INFO)
}

/// Wraps the AMK under the wrap key. The result is what the server stores ([`WRAPPED_AMK_LEN`] bytes).
pub fn wrap_amk(wrap_key: &Key, amk: &Key) -> Vec<u8> {
    aead::seal(wrap_key, AMK_AAD, amk)
}

/// [`wrap_amk`] with a caller-chosen nonce, only for test vectors (core/vectors/recovery.json).
pub fn wrap_amk_with_nonce(wrap_key: &Key, nonce: &[u8; NONCE_LEN], amk: &Key) -> Vec<u8> {
    aead::seal_with_nonce(wrap_key, nonce, AMK_AAD, amk)
}

/// Unwraps the AMK. `None` when the wrap key is wrong (a mistyped code), the blob was tampered with, or the plaintext
/// is not a 32-byte key.
pub fn unwrap_amk(wrap_key: &Key, wrapped: &[u8]) -> Option<Key> {
    let amk = aead::open(wrap_key, AMK_AAD, wrapped)?;
    amk.as_slice().try_into().ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::aead::random_key;

    #[test]
    fn params_match_the_documented_constants() {
        let params = params();
        assert_eq!(params.m_cost(), 65536);
        assert_eq!(params.t_cost(), 3);
        assert_eq!(params.p_cost(), 1);
        assert_eq!(params.output_len(), Some(32));
        assert_eq!(SALT.len(), 18);
    }

    #[test]
    fn known_answers_pin_the_parameters_and_the_labels() {
        // Cross-checked with argon2-cffi and Python's hmac: a change to the algorithm, version, parameters, salt or an
        // HKDF label breaks these, and with them every wrapped AMK and backup already written.
        let entropy = hex::decode("00112233445566778899aabbccddeeff").unwrap();
        let root = recovery_root(&entropy).unwrap();
        assert_eq!(hex::encode(root), "1e08e69d09bf44502849097fc8d572d6e4f99358523dc29b0d7ccf7c1e524395");
        let amk: Key = [7u8; KEY_LEN];
        assert_eq!(hex::encode(backup_key(&amk)), "0f8e41aaca5a850c7523d7031153236cbbfebd2db8d5643543679e3fc02c0ab2");
    }

    #[test]
    fn expand_separates_labels() {
        let prk = random_key();
        let a: [u8; 32] = expand(&prk, b"geunal amk wrap v1");
        let b: [u8; 32] = expand(&prk, b"geunal recovery sign v1");
        let short: [u8; 16] = expand(&prk, b"geunal amk wrap v1");
        assert_ne!(a, b);
        // HKDF output is a prefix of the longer output under the same label, which is why every label differs.
        assert_eq!(short, a[..16]);
    }

    #[test]
    fn wrapped_amk_round_trips_and_rejects_the_wrong_wrap_key() {
        let wrap_key = random_key();
        let amk = random_key();
        let wrapped = wrap_amk(&wrap_key, &amk);
        assert_eq!(wrapped.len(), WRAPPED_AMK_LEN);
        assert_eq!(unwrap_amk(&wrap_key, &wrapped), Some(amk));
        assert_eq!(unwrap_amk(&random_key(), &wrapped), None);
        assert_eq!(unwrap_amk(&wrap_key, &wrapped[..wrapped.len() - 1]), None);
    }

    #[test]
    fn unwrap_rejects_a_plaintext_that_is_not_a_key() {
        let wrap_key = random_key();
        let not_a_key = aead::seal(&wrap_key, AMK_AAD, &[0u8; 31]);
        assert_eq!(unwrap_amk(&wrap_key, &not_a_key), None);
        // The same bytes under a different label must not unwrap either.
        let other_label = aead::seal(&wrap_key, b"geunal amk v2", &[0u8; 32]);
        assert_eq!(unwrap_amk(&wrap_key, &other_label), None);
    }
}

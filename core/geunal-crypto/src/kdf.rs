//! The recovery key and the account master key (kickoff §6, protocol §10).
//!
//! The recovery code is twelve words the user writes down. Argon2id turns it into the recovery key, which wraps the
//! account master key (AMK); the server stores only the salt and the wrapped AMK, so a database leak leaves an attacker
//! with an Argon2id search over the word list at 64 MiB per guess. The parameters follow the OWASP "first
//! recommended" Argon2id setting but with three passes, because this derivation runs once at restore time and the
//! cost is paid by a phone, not a server. The 64 MiB is also why `docs/parts/rust-core.md` §8 keeps this path out of
//! the iOS share extension and its memory limit.

use argon2::{Algorithm, Argon2, Params, Version};
use rand_core::{OsRng, RngCore};

use crate::aead::{self, KEY_LEN, Key};

/// Salt length stored next to the wrapped AMK on the server.
pub const SALT_LEN: usize = 16;
/// Argon2id memory, in KiB (64 MiB).
pub const MEMORY_KIB: u32 = 64 * 1024;
/// Argon2id passes.
pub const ITERATIONS: u32 = 3;
/// Argon2id lanes. One: the derivation is not on a hot path and phones gain little from more.
pub const PARALLELISM: u32 = 1;
/// Derived key length; the recovery key is an XChaCha20-Poly1305 key.
pub const OUTPUT_LEN: usize = KEY_LEN;
/// AAD of the wrapped AMK. Versioned so a later wrapping scheme can coexist with blobs written by this one.
pub const AMK_AAD: &[u8] = b"geunal amk v1";

// Validated at compile time: `Params::new` is `const`, so a bad constant above fails the build instead of a restore.
const PARAMS: Params = match Params::new(MEMORY_KIB, ITERATIONS, PARALLELISM, Some(OUTPUT_LEN)) {
    Ok(params) => params,
    Err(_) => panic!("Argon2id parameters are out of range"),
};

/// Why a derivation failed. Nothing here is expected in practice: the parameters are compile-time checked and the
/// salt length is fixed by the type, so this surfaces only an internal error of the Argon2 implementation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum KdfError {
    #[error("argon2id failed: {0}")]
    Argon2(argon2::Error),
}

/// The Argon2id parameters this crate uses, for display and for test vectors.
pub fn params() -> Params {
    PARAMS.clone()
}

/// A fresh salt for a new account's recovery key.
pub fn random_salt() -> [u8; SALT_LEN] {
    let mut salt = [0u8; SALT_LEN];
    OsRng.fill_bytes(&mut salt);
    salt
}

/// The canonical spelling of a recovery code: lowercase, no leading or trailing whitespace, and single spaces
/// between words. Users type the twelve words with stray spaces, line breaks and capitals, and the same key must
/// come out every time.
pub fn normalize_recovery_code(recovery_code: &str) -> String {
    recovery_code.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

/// Derives the recovery key from a recovery code and the account's salt with Argon2id.
pub fn recovery_key(recovery_code: &str, salt: &[u8; SALT_LEN]) -> Result<Key, KdfError> {
    let code = normalize_recovery_code(recovery_code);
    let mut key = [0u8; KEY_LEN];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params())
        .hash_password_into(code.as_bytes(), salt, &mut key)
        .map_err(KdfError::Argon2)?;
    Ok(key)
}

/// Wraps the AMK under the recovery key. The result is what the server stores next to the salt.
pub fn wrap_amk(recovery_key: &Key, amk: &Key) -> Vec<u8> {
    aead::seal(recovery_key, AMK_AAD, amk)
}

/// Unwraps the AMK. `None` when the recovery key is wrong (a mistyped code), the blob was tampered with, or the
/// plaintext is not a 32-byte key.
pub fn unwrap_amk(recovery_key: &Key, wrapped: &[u8]) -> Option<Key> {
    let amk = aead::open(recovery_key, AMK_AAD, wrapped)?;
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
    }

    #[test]
    fn normalization_trims_lowercases_and_collapses_whitespace() {
        assert_eq!(normalize_recovery_code("  Apple  banana "), "apple banana");
        assert_eq!(normalize_recovery_code("apple\tbanana\n\ncherry"), "apple banana cherry");
        assert_eq!(normalize_recovery_code(""), "");
        assert_eq!(normalize_recovery_code("   "), "");
    }

    // Argon2id at 64 MiB takes about a second per call in a debug build, so the derivation tests below share a small
    // number of calls instead of one per property.
    #[test]
    fn same_code_and_salt_give_the_same_key_regardless_of_spacing_and_case() {
        let salt = [7u8; SALT_LEN];
        let typed = recovery_key("  Apple  banana ", &salt).unwrap();
        let canonical = recovery_key("apple banana", &salt).unwrap();
        assert_eq!(typed, canonical);

        let other_salt = [8u8; SALT_LEN];
        let with_other_salt = recovery_key("apple banana", &other_salt).unwrap();
        assert_ne!(canonical, with_other_salt);
    }

    #[test]
    fn known_answer_pins_the_parameters() {
        // Generated once with this crate; a change to the algorithm, version or parameters breaks it, and with it
        // every existing wrapped AMK, which is exactly what should be noticed.
        let salt: [u8; SALT_LEN] = hex::decode("000102030405060708090a0b0c0d0e0f").unwrap().try_into().unwrap();
        let key =
            recovery_key("abandon ability able about above absent absorb abstract absurd abuse access accident", &salt)
                .unwrap();
        assert_eq!(hex::encode(key), "3feac34b018ebd03621bbb9a6847166cee42a84786f81689220eace1d99e67d1");
    }

    #[test]
    fn wrapped_amk_round_trips_and_rejects_the_wrong_recovery_key() {
        let recovery = random_key();
        let amk = random_key();
        let wrapped = wrap_amk(&recovery, &amk);
        assert_eq!(wrapped.len(), aead::NONCE_LEN + KEY_LEN + aead::TAG_LEN);
        assert_eq!(unwrap_amk(&recovery, &wrapped), Some(amk));
        assert_eq!(unwrap_amk(&random_key(), &wrapped), None);
        assert_eq!(unwrap_amk(&recovery, &wrapped[..wrapped.len() - 1]), None);
    }

    #[test]
    fn unwrap_rejects_a_plaintext_that_is_not_a_key() {
        let recovery = random_key();
        let not_a_key = aead::seal(&recovery, AMK_AAD, &[0u8; 31]);
        assert_eq!(unwrap_amk(&recovery, &not_a_key), None);
        // The same bytes under a different label must not unwrap either.
        let other_label = aead::seal(&recovery, b"geunal amk v2", &[0u8; 32]);
        assert_eq!(unwrap_amk(&recovery, &other_label), None);
    }
}

//! Sealing a backup archive (protocol §10 "암호화"): the STREAM construction of protocol §9 under a key derived from
//! the AMK, so only a device that holds the account's recovery code can open it.

use crate::aead::Key;
use crate::kdf;
use crate::stream::{self, NONCE_LEN, StreamError};

/// AAD of every chunk of a backup object.
pub const AAD: &[u8] = b"geunal backup v1";

/// The object to upload: `nonce prefix (19) ‖ chunks`.
pub fn seal(amk: &Key, archive: &[u8]) -> Vec<u8> {
    stream::encrypt(&kdf::backup_key(amk), AAD, archive)
}

/// [`seal`] with a caller-chosen nonce prefix, only for test vectors (core/vectors/backups.json).
pub fn seal_with_nonce(amk: &Key, nonce: &[u8; NONCE_LEN], archive: &[u8]) -> Vec<u8> {
    stream::encrypt_with_nonce(&kdf::backup_key(amk), nonce, AAD, archive)
}

/// The archive inside an object; an error when another AMK sealed it or it was cut short or altered.
pub fn open(amk: &Key, object: &[u8]) -> Result<Vec<u8>, StreamError> {
    stream::decrypt(&kdf::backup_key(amk), AAD, object)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::aead::random_key;

    #[test]
    fn opens_only_with_the_amk_that_sealed_it() {
        let amk = random_key();
        let archive = vec![7u8; stream::CHUNK_LEN + 5];
        let object = seal(&amk, &archive);
        assert_eq!(open(&amk, &object).unwrap(), archive);
        assert_eq!(open(&random_key(), &object), Err(StreamError::Authentication));
        assert!(open(&amk, &object[..object.len() - 1]).is_err());
        // A stream under the same key with another label is not a backup.
        let other = stream::encrypt(&kdf::backup_key(&amk), b"geunal other v1", &archive);
        assert!(open(&amk, &other).is_err());
    }
}

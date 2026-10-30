//! Chunked encryption for media (protocol §9) and backups (protocol §10): the STREAM construction of
//! Hoang–Reyhanitabar–Rogaway–Vizár over XChaCha20-Poly1305 with a 32-bit big-endian counter, exactly as RustCrypto's
//! `aead::stream` implements it.
//!
//! Format: `nonce prefix (19) ‖ chunk_1 ‖ … ‖ chunk_n`. Every chunk but the last seals exactly [`CHUNK_LEN`] bytes of
//! plaintext and is [`SEALED_CHUNK_LEN`] bytes long; the last chunk seals whatever remains (0..=[`CHUNK_LEN`] bytes) and
//! carries the STREAM "last" flag. The counter and the flag go into each chunk's nonce, so a receiver notices chunks
//! that were reordered, duplicated, dropped or cut off at the end: a plain per-chunk AEAD would accept all of those.
//! The 19-byte prefix is what remains of XChaCha's 24-byte nonce after the counter (4 bytes) and the flag (1 byte).
//!
//! The chunk size keeps the receiver's memory bounded: a 10 MB file is decrypted in 64 KiB pieces, which matters in an
//! iOS share extension where the whole process gets a few dozen megabytes.

use chacha20poly1305::XChaCha20Poly1305;
use chacha20poly1305::aead::Payload;
use chacha20poly1305::aead::generic_array::GenericArray;
use chacha20poly1305::aead::stream::{DecryptorBE32, EncryptorBE32};
use rand_core::{OsRng, RngCore};

use crate::aead::{Key, TAG_LEN};

/// Plaintext bytes per chunk, except possibly the last.
pub const CHUNK_LEN: usize = 64 * 1024;
/// Length of the stream header, the random nonce prefix shared by every chunk.
pub const NONCE_LEN: usize = 19;
/// Ciphertext bytes of every chunk but the last.
pub const SEALED_CHUNK_LEN: usize = CHUNK_LEN + TAG_LEN;

/// Why a STREAM operation was refused. Decryption failures deliberately do not say which chunk or which byte: the
/// data came from the network and any failure means "discard it".
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum StreamError {
    /// The data is shorter than a nonce prefix plus one tag, or the header is not [`NONCE_LEN`] bytes.
    #[error("stream too short")]
    TooShort,
    /// An incremental call got a chunk of the wrong length: `next` needs a full chunk, `finish` at most one.
    #[error("chunk has the wrong length")]
    ChunkLength,
    /// A chunk failed authentication, or the stream was reordered, truncated or malformed.
    #[error("stream authentication failed")]
    Authentication,
    /// The 32-bit chunk counter ran out; 2^32 − 1 chunks of 64 KiB is 256 TiB, far past any file this app moves.
    #[error("stream exhausted its chunk counter")]
    Exhausted,
}

/// Encrypts `plaintext` in one go. Returns `nonce ‖ chunks`.
pub fn encrypt(key: &Key, aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    encrypt_at(key, &nonce, aad, plaintext)
}

/// [`encrypt`] with a caller-chosen nonce prefix. Only for test vectors (core/vectors/backups.json), which must be
/// reproducible; production code never reuses a prefix under a key, so it always goes through [`encrypt`]. Behind
/// the `test-vectors` feature.
#[cfg(feature = "test-vectors")]
pub fn encrypt_with_nonce(key: &Key, nonce: &[u8; NONCE_LEN], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    encrypt_at(key, nonce, aad, plaintext)
}

fn encrypt_at(key: &Key, nonce: &[u8; NONCE_LEN], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let mut encryptor = Encryptor::with_nonce(key, nonce, aad);
    let mut out = nonce.to_vec();
    out.reserve(plaintext.len() + TAG_LEN * (plaintext.len() / CHUNK_LEN + 1));

    // The last chunk must be non-empty when there is any plaintext at all, so a plaintext of exactly n chunks ends
    // with a full last chunk rather than an empty one. `split_last_chunk` does that split.
    let (full, last) = split_last_chunk(plaintext);
    for chunk in full.chunks_exact(CHUNK_LEN) {
        // `next` fails only on counter overflow, after 2^32 - 1 chunks: a slice that long (256 TiB) cannot exist, so
        // the error is unreachable and a panic here would be a bug in this crate, not a property of the input.
        let sealed = encryptor.next(chunk).expect("a slice cannot hold enough chunks to overflow the STREAM counter");
        out.extend_from_slice(&sealed);
    }
    let sealed = encryptor.finish(last).expect("the last position of a STREAM is always encryptable");
    out.extend_from_slice(&sealed);
    out
}

/// Decrypts the output of [`encrypt`] or of an [`Encryptor`] whose pieces were concatenated after the header.
pub fn decrypt(key: &Key, aad: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, StreamError> {
    if ciphertext.len() < NONCE_LEN + TAG_LEN {
        return Err(StreamError::TooShort);
    }
    let (header, body) = ciphertext.split_at(NONCE_LEN);
    let mut decryptor = Decryptor::new(key, aad, header)?;

    // Body = k full sealed chunks + one last sealed chunk of TAG_LEN..=SEALED_CHUNK_LEN bytes. Because a full chunk is
    // longer than any plaintext a last chunk may carry, this split is unique; a remainder larger than CHUNK_LEN cannot
    // come from an honest encryptor and is reported like any other tampering.
    let full_count = (body.len() - TAG_LEN) / SEALED_CHUNK_LEN;
    let last_len = body.len() - full_count * SEALED_CHUNK_LEN;
    if last_len > SEALED_CHUNK_LEN {
        return Err(StreamError::Authentication);
    }
    let (full, last) = body.split_at(full_count * SEALED_CHUNK_LEN);

    let mut out = Vec::with_capacity(body.len() - TAG_LEN * (full_count + 1));
    for chunk in full.chunks_exact(SEALED_CHUNK_LEN) {
        out.extend_from_slice(&decryptor.next(chunk)?);
    }
    out.extend_from_slice(&decryptor.finish(last)?);
    Ok(out)
}

/// Splits a plaintext into the full chunks and the last chunk the way [`encrypt`] lays them out: the last chunk is
/// empty only when the whole plaintext is empty.
fn split_last_chunk(plaintext: &[u8]) -> (&[u8], &[u8]) {
    let remainder = plaintext.len() % CHUNK_LEN;
    let last_len = if remainder == 0 && !plaintext.is_empty() { CHUNK_LEN } else { remainder };
    plaintext.split_at(plaintext.len() - last_len)
}

/// Incremental encryption for files that should not be held in memory at once. Feed exactly [`CHUNK_LEN`] bytes to
/// [`Encryptor::next`] for every chunk but the last, then the remainder (0..=[`CHUNK_LEN`] bytes) to
/// [`Encryptor::finish`]. Writing the header and then every returned piece in order yields the same format as
/// [`encrypt`].
pub struct Encryptor {
    inner: EncryptorBE32<XChaCha20Poly1305>,
    // Owned so the encryptor can live across FFI calls without a borrow; the AAD is a short label.
    aad: Vec<u8>,
}

impl Encryptor {
    /// Starts a stream with a fresh random nonce. The second value is the header to write before the first chunk.
    pub fn new(key: &Key, aad: &[u8]) -> (Self, Vec<u8>) {
        let mut nonce = [0u8; NONCE_LEN];
        OsRng.fill_bytes(&mut nonce);
        (Self::with_nonce(key, &nonce, aad), nonce.to_vec())
    }

    fn with_nonce(key: &Key, nonce: &[u8; NONCE_LEN], aad: &[u8]) -> Self {
        Self { inner: EncryptorBE32::new(key.into(), &GenericArray::from(*nonce)), aad: aad.to_vec() }
    }

    /// Seals one full chunk. `chunk` must be exactly [`CHUNK_LEN`] bytes.
    pub fn next(&mut self, chunk: &[u8]) -> Result<Vec<u8>, StreamError> {
        if chunk.len() != CHUNK_LEN {
            return Err(StreamError::ChunkLength);
        }
        // Encryption itself cannot fail; the only error the primitive reports is counter overflow.
        self.inner.encrypt_next(Payload { msg: chunk, aad: &self.aad }).map_err(|_| StreamError::Exhausted)
    }

    /// Seals the last chunk, which may be empty but not longer than [`CHUNK_LEN`], and ends the stream.
    pub fn finish(self, last: &[u8]) -> Result<Vec<u8>, StreamError> {
        if last.len() > CHUNK_LEN {
            return Err(StreamError::ChunkLength);
        }
        // The last position is always encryptable: `next` refuses to reach the counter's maximum value so that a last
        // chunk can still follow.
        self.inner.encrypt_last(Payload { msg: last, aad: &self.aad }).map_err(|_| StreamError::Exhausted)
    }
}

/// Incremental decryption, the mirror of [`Encryptor`]: construct it from the header, feed every full sealed chunk
/// ([`SEALED_CHUNK_LEN`] bytes) to [`Decryptor::next`], and the remaining bytes to [`Decryptor::finish`].
pub struct Decryptor {
    inner: DecryptorBE32<XChaCha20Poly1305>,
    aad: Vec<u8>,
}

impl Decryptor {
    /// Starts reading a stream from its header, the first [`NONCE_LEN`] bytes of the ciphertext.
    pub fn new(key: &Key, aad: &[u8], header: &[u8]) -> Result<Self, StreamError> {
        let nonce: [u8; NONCE_LEN] = header.try_into().map_err(|_| StreamError::TooShort)?;
        let inner = DecryptorBE32::new(key.into(), &GenericArray::from(nonce));
        Ok(Self { inner, aad: aad.to_vec() })
    }

    /// Opens one full sealed chunk. `chunk` must be exactly [`SEALED_CHUNK_LEN`] bytes.
    pub fn next(&mut self, chunk: &[u8]) -> Result<Vec<u8>, StreamError> {
        if chunk.len() != SEALED_CHUNK_LEN {
            return Err(StreamError::ChunkLength);
        }
        // The primitive folds counter overflow into the same error as a bad tag. Overflow needs 2^32 − 1 chunks of
        // input, so reporting it as an authentication failure loses nothing a caller could act on.
        self.inner.decrypt_next(Payload { msg: chunk, aad: &self.aad }).map_err(|_| StreamError::Authentication)
    }

    /// Opens the last sealed chunk: a tag plus 0..=[`CHUNK_LEN`] bytes. Fails if the stream was cut before its real
    /// last chunk, because that chunk lacks the "last" flag in its nonce.
    pub fn finish(self, last: &[u8]) -> Result<Vec<u8>, StreamError> {
        if last.len() < TAG_LEN || last.len() > SEALED_CHUNK_LEN {
            return Err(StreamError::ChunkLength);
        }
        self.inner.decrypt_last(Payload { msg: last, aad: &self.aad }).map_err(|_| StreamError::Authentication)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::aead::random_key;

    const AAD: &[u8] = b"media|chat";

    /// Deterministic but non-repeating content so a reordering bug cannot hide behind equal chunks.
    fn plaintext(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8 ^ (i / 251) as u8).collect()
    }

    #[test]
    fn round_trips_every_chunk_boundary() {
        let key = random_key();
        for len in [0, 1, CHUNK_LEN - 1, CHUNK_LEN, CHUNK_LEN + 1, 2 * CHUNK_LEN, 2 * CHUNK_LEN + 777] {
            let message = plaintext(len);
            let sealed = encrypt(&key, AAD, &message);
            assert_eq!(decrypt(&key, AAD, &sealed).as_deref(), Ok(message.as_slice()), "len {len}");
        }
    }

    #[test]
    fn ciphertext_length_follows_the_chunk_layout() {
        let key = random_key();
        // Empty plaintext: nonce and a single tag-only last chunk.
        assert_eq!(encrypt(&key, AAD, b"").len(), NONCE_LEN + TAG_LEN);
        // One byte: nonce and a 17-byte last chunk.
        assert_eq!(encrypt(&key, AAD, b"x").len(), NONCE_LEN + 1 + TAG_LEN);
        // Exactly one chunk: a single (last) chunk, not a full chunk followed by an empty one.
        assert_eq!(encrypt(&key, AAD, &plaintext(CHUNK_LEN)).len(), NONCE_LEN + SEALED_CHUNK_LEN);
        // One chunk and a byte: a full chunk and a 17-byte last chunk.
        assert_eq!(encrypt(&key, AAD, &plaintext(CHUNK_LEN + 1)).len(), NONCE_LEN + SEALED_CHUNK_LEN + 1 + TAG_LEN);
    }

    #[test]
    fn truncating_the_last_chunk_fails() {
        let key = random_key();
        let sealed = encrypt(&key, AAD, &plaintext(2 * CHUNK_LEN + 100));
        assert_eq!(decrypt(&key, AAD, &sealed[..sealed.len() - 1]), Err(StreamError::Authentication));
        // Cutting it down to just a tag still has the right shape, and still fails.
        let cut = NONCE_LEN + 2 * SEALED_CHUNK_LEN + TAG_LEN;
        assert_eq!(decrypt(&key, AAD, &sealed[..cut]), Err(StreamError::Authentication));
    }

    #[test]
    fn swapping_two_full_chunks_fails() {
        let key = random_key();
        let mut sealed = encrypt(&key, AAD, &plaintext(3 * CHUNK_LEN));
        let first = NONCE_LEN..NONCE_LEN + SEALED_CHUNK_LEN;
        let second = NONCE_LEN + SEALED_CHUNK_LEN..NONCE_LEN + 2 * SEALED_CHUNK_LEN;
        let (a, b) = (sealed[first.clone()].to_vec(), sealed[second.clone()].to_vec());
        sealed[first].copy_from_slice(&b);
        sealed[second].copy_from_slice(&a);
        assert_eq!(decrypt(&key, AAD, &sealed), Err(StreamError::Authentication));
    }

    #[test]
    fn dropping_the_last_chunk_fails_because_the_last_flag_is_missing() {
        let key = random_key();
        let sealed = encrypt(&key, AAD, &plaintext(CHUNK_LEN + 5));
        // What remains is a well-formed stream of one chunk, but that chunk was sealed as "not last".
        assert_eq!(decrypt(&key, AAD, &sealed[..NONCE_LEN + SEALED_CHUNK_LEN]), Err(StreamError::Authentication));
    }

    #[test]
    fn duplicating_a_full_chunk_fails() {
        let key = random_key();
        let mut sealed = encrypt(&key, AAD, &plaintext(CHUNK_LEN + 5));
        let first = sealed[NONCE_LEN..NONCE_LEN + SEALED_CHUNK_LEN].to_vec();
        sealed.splice(NONCE_LEN..NONCE_LEN, first);
        assert_eq!(decrypt(&key, AAD, &sealed), Err(StreamError::Authentication));
    }

    #[test]
    fn wrong_aad_or_key_fails() {
        let key = random_key();
        let sealed = encrypt(&key, AAD, &plaintext(CHUNK_LEN + 5));
        assert_eq!(decrypt(&key, b"media|appt", &sealed), Err(StreamError::Authentication));
        assert_eq!(decrypt(&random_key(), AAD, &sealed), Err(StreamError::Authentication));
    }

    #[test]
    fn short_input_is_rejected_before_any_cryptography() {
        let key = random_key();
        assert_eq!(decrypt(&key, AAD, b""), Err(StreamError::TooShort));
        assert_eq!(decrypt(&key, AAD, &[0u8; NONCE_LEN + TAG_LEN - 1]), Err(StreamError::TooShort));
        // Exactly nonce + tag has the right shape and fails only on authentication.
        assert_eq!(decrypt(&key, AAD, &[0u8; NONCE_LEN + TAG_LEN]), Err(StreamError::Authentication));
        assert!(matches!(Decryptor::new(&key, AAD, &[0u8; NONCE_LEN - 1]), Err(StreamError::TooShort)));
        assert!(matches!(Decryptor::new(&key, AAD, &[0u8; NONCE_LEN + 1]), Err(StreamError::TooShort)));
    }

    #[test]
    fn body_whose_last_chunk_exceeds_a_sealed_chunk_is_rejected() {
        let key = random_key();
        // Zero full chunks and a last chunk one byte longer than a sealed full chunk: no layout produces it, and the
        // shape check reports it the same way as a forged tag.
        let bogus = vec![0u8; NONCE_LEN + SEALED_CHUNK_LEN + 1];
        assert_eq!(decrypt(&key, AAD, &bogus), Err(StreamError::Authentication));
    }

    #[test]
    fn incremental_encrypt_is_read_by_one_shot_decrypt() {
        let key = random_key();
        let message = plaintext(2 * CHUNK_LEN + 300);
        let (mut encryptor, mut sealed) = Encryptor::new(&key, AAD);
        assert_eq!(sealed.len(), NONCE_LEN);
        let (full, last) = message.split_at(2 * CHUNK_LEN);
        for chunk in full.chunks(CHUNK_LEN) {
            sealed.extend_from_slice(&encryptor.next(chunk).unwrap());
        }
        sealed.extend_from_slice(&encryptor.finish(last).unwrap());
        assert_eq!(decrypt(&key, AAD, &sealed), Ok(message));
    }

    #[test]
    fn incremental_encrypt_with_an_empty_last_chunk_is_read_by_one_shot_decrypt() {
        let key = random_key();
        let message = plaintext(CHUNK_LEN);
        let (mut encryptor, mut sealed) = Encryptor::new(&key, AAD);
        sealed.extend_from_slice(&encryptor.next(&message).unwrap());
        sealed.extend_from_slice(&encryptor.finish(b"").unwrap());
        assert_eq!(sealed.len(), NONCE_LEN + SEALED_CHUNK_LEN + TAG_LEN);
        assert_eq!(decrypt(&key, AAD, &sealed), Ok(message));
    }

    #[test]
    fn one_shot_encrypt_is_read_by_incremental_decrypt() {
        let key = random_key();
        let message = plaintext(2 * CHUNK_LEN + 300);
        let sealed = encrypt(&key, AAD, &message);
        let (header, body) = sealed.split_at(NONCE_LEN);
        let mut decryptor = Decryptor::new(&key, AAD, header).unwrap();
        let (full, last) = body.split_at(2 * SEALED_CHUNK_LEN);
        let mut opened = Vec::new();
        for chunk in full.chunks(SEALED_CHUNK_LEN) {
            opened.extend_from_slice(&decryptor.next(chunk).unwrap());
        }
        opened.extend_from_slice(&decryptor.finish(last).unwrap());
        assert_eq!(opened, message);
    }

    #[test]
    fn incremental_api_refuses_chunks_of_the_wrong_length() {
        let key = random_key();
        let (mut encryptor, header) = Encryptor::new(&key, AAD);
        assert_eq!(encryptor.next(&plaintext(CHUNK_LEN - 1)), Err(StreamError::ChunkLength));
        assert_eq!(encryptor.next(&plaintext(CHUNK_LEN + 1)), Err(StreamError::ChunkLength));
        assert_eq!(encryptor.finish(&plaintext(CHUNK_LEN + 1)), Err(StreamError::ChunkLength));

        let mut decryptor = Decryptor::new(&key, AAD, &header).unwrap();
        assert_eq!(decryptor.next(&[0u8; SEALED_CHUNK_LEN - 1]), Err(StreamError::ChunkLength));
        assert_eq!(decryptor.next(&[0u8; SEALED_CHUNK_LEN + 1]), Err(StreamError::ChunkLength));
        let decryptor = Decryptor::new(&key, AAD, &header).unwrap();
        assert_eq!(decryptor.finish(&[0u8; TAG_LEN - 1]), Err(StreamError::ChunkLength));
        let decryptor = Decryptor::new(&key, AAD, &header).unwrap();
        assert_eq!(decryptor.finish(&[0u8; SEALED_CHUNK_LEN + 1]), Err(StreamError::ChunkLength));
    }

    #[test]
    fn two_encryptions_of_the_same_file_differ_because_the_nonce_is_random() {
        let key = random_key();
        let a = encrypt(&key, AAD, b"same");
        let b = encrypt(&key, AAD, b"same");
        assert_ne!(a[..NONCE_LEN], b[..NONCE_LEN]);
        assert_ne!(a, b);
    }
}

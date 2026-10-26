//! Encryption the core does itself, outside MLS: XChaCha20-Poly1305 for state blobs (protocol §5), the STREAM
//! construction for media and backups (protocol §9, §10), the recovery code with everything derived from it and the
//! account master key (protocol §10), and what a sign-in binds to the device (protocol §13). Every algorithm comes
//! from RustCrypto; nothing here is a home-made cipher.
#![forbid(unsafe_code)]

pub mod aead;
pub mod backup;
pub mod kdf;
pub mod login;
pub mod recovery;
pub mod stream;

#[cfg(feature = "test-vectors")]
pub use aead::seal_with_nonce;
pub use aead::{Key, open, random_key, seal};

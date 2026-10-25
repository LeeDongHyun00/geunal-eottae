//! Encryption the core does itself, outside MLS: XChaCha20-Poly1305 for state blobs (protocol §5), the STREAM
//! construction for media and backups (protocol §9, §10), Argon2id over the recovery code and the account master key
//! (kickoff §6). Every algorithm comes from RustCrypto; nothing here is a home-made cipher.
#![forbid(unsafe_code)]

pub mod aead;
pub mod kdf;
pub mod stream;

pub use aead::{Key, open, random_key, seal, seal_with_nonce};

//! The mls-rs wrapper: clients, key packages, commits, the exporter-derived state key and external join (protocol §2,
//! §5). Group state lives in the store's SQLCipher file, in tables owned by mls-rs-provider-sqlite
//! (docs/parts/rust-core.md §4). Only cipher suite 0x0003 exists here: the provider is built with that one suite
//! enabled, so a group, key package or Welcome on any other suite is refused before it is used.
#![forbid(unsafe_code)]

mod client;
pub mod header;
pub mod state;

pub use client::{CommitBundle, Identity, KeyPackageInfo, MemberInfo, MlsClient, MlsGroup, Received, SUITE};
pub use mls_rs::error::MlsError;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("mls: {0}")]
    Mls(#[from] MlsError),
    #[error("mls storage: {0}")]
    Storage(#[from] mls_rs_provider_sqlite::SqLiteDataStorageError),
    #[error("mls crypto: {0}")]
    Crypto(String),
    #[error("cipher suite {0:?} is not 0x0003")]
    WrongCipherSuite(mls_rs::CipherSuite),
    #[error("the message is not a {0}")]
    WrongMessageType(&'static str),
    #[error("credential is not \"{{user_id}}:{{device_id}}\"")]
    BadCredential,
    #[error("the group id is not 16 bytes")]
    BadGroupId,
}

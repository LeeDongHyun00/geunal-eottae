//! The mls-rs wrapper: clients, key packages, commits, the exporter-derived state key and external join (protocol §2,
//! §5). Group state lives in the store's SQLCipher file, in tables owned by mls-rs-provider-sqlite
//! (docs/parts/rust-core.md §4).
#![forbid(unsafe_code)]

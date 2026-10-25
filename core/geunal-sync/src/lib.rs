//! Sync with the servers: writes go through the outbox in group order, reads catch up from per-group cursors, and the
//! gateway's Phoenix channel only says that something new arrived (docs/parts/rust-core.md §5, protocol §6, §7, §11).
//!
//! The engine talks to the API through the [`Transport`] trait. [`HttpTransport`] is the real client; [`fake`] is an
//! in-process server with the same rules (epoch preemption, stale epochs, cursors, 410) for tests.
#![forbid(unsafe_code)]

pub mod api;
pub mod engine;
pub mod fake;
pub mod http;
pub mod realtime;
pub mod tls;

pub use api::{ApiError, Transport};
pub use engine::{Alert, Change, CommitIntent, Engine, Listener, Session, sign_in_guest};
pub use http::HttpTransport;

#[derive(Debug, thiserror::Error)]
pub enum SyncError {
    #[error(transparent)]
    Api(#[from] ApiError),
    #[error(transparent)]
    Mls(#[from] geunal_mls::Error),
    #[error(transparent)]
    Store(#[from] geunal_store::StoreError),
    #[error("not signed in")]
    NotSignedIn,
    #[error("unknown group {0}")]
    UnknownGroup(String),
    #[error("this device is no longer a member of {0}")]
    NotAMember(String),
    #[error("malformed data from the server: {0}")]
    Malformed(String),
    #[error("realtime: {0}")]
    Realtime(String),
}

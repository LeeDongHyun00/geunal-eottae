//! Sync with the servers: writes go through the outbox in group order, reads catch up from per-group cursors, and the
//! gateway's Phoenix channel only says that something new arrived (docs/parts/rust-core.md §5, protocol §6, §7, §11).
#![forbid(unsafe_code)]

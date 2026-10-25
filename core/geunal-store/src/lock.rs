//! The cross-process write lock: `flock` on a file next to the database. SQLite's own locking serialises
//! transactions, but the extension must know up front whether it can finish within its time budget, so writers take
//! this lock first and the extension stops waiting after five seconds (rust-core §4).

use std::fs::{File, OpenOptions};
use std::path::Path;
use std::time::{Duration, Instant};

use rustix::fs::{FlockOperation, flock};

use crate::StoreError;

/// Held while writing; dropping it releases the lock.
pub struct WriteLock {
    _file: File,
}

impl WriteLock {
    pub(crate) fn acquire(path: &Path, timeout: Duration) -> Result<Self, StoreError> {
        let file = OpenOptions::new().create(true).truncate(false).write(true).open(path)?;
        let deadline = Instant::now() + timeout;
        loop {
            match flock(&file, FlockOperation::NonBlockingLockExclusive) {
                Ok(()) => return Ok(Self { _file: file }),
                Err(rustix::io::Errno::WOULDBLOCK) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(20));
                }
                Err(rustix::io::Errno::WOULDBLOCK) => return Err(StoreError::LockTimeout),
                Err(e) => return Err(StoreError::LockIo(e.into())),
            }
        }
    }
}

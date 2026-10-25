//! The M0 surface the apps build their first screen on: Core.open, sync and the secret-store callback.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use geunal_ffi::{Change, Core, CoreError, CoreListener, SecretStore};

#[derive(Default)]
struct MemorySecrets(Mutex<HashMap<String, Vec<u8>>>);

impl SecretStore for MemorySecrets {
    fn get(&self, name: String) -> Option<Vec<u8>> {
        self.0.lock().unwrap().get(&name).cloned()
    }
    fn put(&self, name: String, value: Vec<u8>) {
        self.0.lock().unwrap().insert(name, value);
    }
    fn delete(&self, name: String) {
        self.0.lock().unwrap().remove(&name);
    }
}

struct Quiet;

impl CoreListener for Quiet {
    fn on_change(&self, _change: Change) {}
}

fn open(dir: &tempfile::TempDir, secrets: &Arc<MemorySecrets>) -> Result<Arc<Core>, CoreError> {
    Core::open(
        dir.path().to_str().unwrap().to_owned(),
        "http://localhost:8080".to_owned(),
        Arc::new(Quiet),
        secrets.clone(),
    )
}

#[test]
fn opens_the_store_under_a_key_it_keeps_in_the_secret_store() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());

    let core = open(&dir, &secrets).unwrap();
    assert!(!core.cipher_version().unwrap().is_empty());
    assert_eq!(secrets.get("db_key".to_owned()).map(|key| key.len()), Some(32));
    drop(core);

    // Reopening reads the same key back instead of making a new one.
    let key = secrets.get("db_key".to_owned());
    open(&dir, &secrets).unwrap();
    assert_eq!(secrets.get("db_key".to_owned()), key);
}

#[test]
fn refuses_a_store_whose_key_was_lost() {
    let dir = tempfile::tempdir().unwrap();
    drop(open(&dir, &Arc::new(MemorySecrets::default())).unwrap());

    assert!(matches!(open(&dir, &Arc::new(MemorySecrets::default())), Err(CoreError::Store { .. })));
}

#[test]
fn rejects_a_stored_key_of_the_wrong_length() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    secrets.put("db_key".to_owned(), vec![1; 16]);

    assert!(matches!(open(&dir, &secrets), Err(CoreError::SecretStore { .. })));
}

#[test]
fn syncs_with_nothing_to_catch_up() {
    let dir = tempfile::tempdir().unwrap();
    let core = open(&dir, &Arc::new(MemorySecrets::default())).unwrap();

    pollster::block_on(core.sync()).unwrap();
}

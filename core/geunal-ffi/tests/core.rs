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
    assert!(!core.cipher_provider().unwrap().version.is_empty());
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
fn syncs_with_nothing_to_do_before_a_sign_in_and_reports_no_session() {
    let dir = tempfile::tempdir().unwrap();
    let core = open(&dir, &Arc::new(MemorySecrets::default())).unwrap();

    assert!(core.session().is_none());
    // The M0 status screens open the core and sync without an account; that is not an error.
    pollster::block_on(core.clone().sync()).unwrap();
    assert!(matches!(core.groups(), Err(CoreError::NotSignedIn)));
}

#[test]
fn restores_a_saved_session_on_open_and_opens_the_mls_client_over_the_store() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    // What sign_in_guest would have left behind: a session and the device signing key.
    let (secret, _) = geunal_mls::MlsClient::generate_signing_key().unwrap();
    secrets.put("device_signing_key".to_owned(), secret);
    secrets.put(
        "session".to_owned(),
        br#"{"user_id":"u1","device_id":"d1","access_token":"tok","refresh_token":"r"}"#.to_vec(),
    );

    let core = open(&dir, &secrets).unwrap();
    assert_eq!(core.session().map(|s| (s.user_id, s.device_id)), Some(("u1".to_owned(), "d1".to_owned())));
    assert!(core.groups().unwrap().is_empty());
    assert!(!core.cipher_version().unwrap().is_empty());
}

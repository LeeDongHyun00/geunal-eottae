//! The M0 surface the apps build their first screen on: Core.open, sync and the secret-store callback.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use geunal_ffi::{AlertRule, Change, Core, CoreError, CoreListener, PersonInput, SecretStore, SecretStoreError};

/// How reading the database key fails.
#[derive(Clone, Copy)]
enum KeyFault {
    /// The keychain before the first unlock: the key is there and will read later.
    Unavailable,
    /// The Android Keystore key that sealed it is gone: it will never read again.
    Lost,
}

#[derive(Default)]
struct MemorySecrets {
    values: Mutex<HashMap<String, Vec<u8>>>,
    key_fault: Mutex<Option<KeyFault>>,
    /// The database key is refused on writes.
    key_unwritable: Mutex<bool>,
}

impl SecretStore for MemorySecrets {
    fn get(&self, name: String) -> Result<Option<Vec<u8>>, SecretStoreError> {
        match (name.as_str(), *self.key_fault.lock().unwrap()) {
            ("db_key", Some(KeyFault::Unavailable)) => Err(SecretStoreError::Unavailable { reason: "locked".into() }),
            ("db_key", Some(KeyFault::Lost)) => Err(SecretStoreError::Lost { reason: "tag mismatch".into() }),
            _ => Ok(self.value(&name)),
        }
    }
    fn put(&self, name: String, value: Vec<u8>) -> Result<(), SecretStoreError> {
        if name == "db_key" && *self.key_unwritable.lock().unwrap() {
            return Err(SecretStoreError::Unavailable { reason: "locked".into() });
        }
        self.values.lock().unwrap().insert(name, value);
        Ok(())
    }
    fn delete(&self, name: String) -> Result<(), SecretStoreError> {
        self.values.lock().unwrap().remove(&name);
        Ok(())
    }
}

impl MemorySecrets {
    fn value(&self, name: &str) -> Option<Vec<u8>> {
        self.values.lock().unwrap().get(name).cloned()
    }

    fn fail_key(&self, fault: Option<KeyFault>) {
        *self.key_fault.lock().unwrap() = fault;
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
    assert_eq!(secrets.value("db_key").map(|key| key.len()), Some(32));
    assert_eq!(core.store_reset().unwrap(), None);
    drop(core);

    // Reopening reads the same key back instead of making a new one.
    let key = secrets.value("db_key");
    open(&dir, &secrets).unwrap();
    assert_eq!(secrets.value("db_key"), key);
}

#[test]
fn fails_to_open_while_the_store_cannot_read_the_key_and_writes_no_new_one() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    let core = open(&dir, &secrets).unwrap();
    let person = core.save_person(person("하나")).unwrap();
    drop(core);
    let key = secrets.value("db_key");

    secrets.fail_key(Some(KeyFault::Unavailable));
    assert!(matches!(open(&dir, &secrets), Err(CoreError::SecretStore { .. })));
    assert_eq!(secrets.value("db_key"), key, "a key the store failed to read is never written over");

    // Once the keychain answers, the same store opens with everything in it.
    secrets.fail_key(None);
    let core = open(&dir, &secrets).unwrap();
    assert_eq!(core.people().unwrap().iter().map(|p| p.id.clone()).collect::<Vec<_>>(), [person]);
    assert_eq!(core.store_reset().unwrap(), None);
}

#[test]
fn sets_a_store_found_without_its_key_aside_and_starts_an_empty_one() {
    let dir = tempfile::tempdir().unwrap();
    let core = open(&dir, &Arc::new(MemorySecrets::default())).unwrap();
    core.save_person(person("하나")).unwrap();
    drop(core);

    // A secret store without the key, as after the keychain items went: nothing can open the old file any more.
    let core = open(&dir, &Arc::new(MemorySecrets::default())).unwrap();
    assert!(core.store_reset().unwrap().is_some());
    assert!(core.people().unwrap().is_empty());
    assert!(core.session().is_none());
    assert!(!core.calendars().unwrap().is_empty(), "the new store starts like a first one");
    drop(core);

    // The old file is kept aside, still ciphertext: SQLCipher encrypts the header too.
    let aside: Vec<_> = std::fs::read_dir(dir.path().join("unreadable")).unwrap().map(|e| e.unwrap().path()).collect();
    assert_eq!(aside.len(), 1);
    let old = std::fs::read(aside[0].join("geunal.db")).unwrap();
    assert!(!old.starts_with(b"SQLite format 3"));
}

#[test]
fn reports_a_store_set_aside_by_an_open_that_could_not_write_the_new_key() {
    let dir = tempfile::tempdir().unwrap();
    drop(open(&dir, &Arc::new(MemorySecrets::default())).unwrap());

    // The old store goes aside, then the keychain refuses the new key: that open fails after the move.
    let secrets = Arc::new(MemorySecrets::default());
    *secrets.key_unwritable.lock().unwrap() = true;
    assert!(matches!(open(&dir, &secrets), Err(CoreError::SecretStore { .. })));
    assert!(!dir.path().join("geunal.db").exists());

    // The next open finds nothing left to move, and still tells the app the calendar was set aside.
    *secrets.key_unwritable.lock().unwrap() = false;
    let core = open(&dir, &secrets).unwrap();
    assert!(core.store_reset().unwrap().is_some());
    drop(core);
    assert!(!dir.path().join("geunal.reset").exists(), "reported once, then the marker goes");
    assert!(open(&dir, &secrets).unwrap().store_reset().unwrap().is_some(), "until the next sign-in");
}

#[test]
fn sets_the_store_aside_with_the_account_once_its_key_is_lost_for_good() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    drop(open(&dir, &secrets).unwrap());
    signed_in(&secrets);

    // The Android Keystore key that sealed every secret is gone: the database key reads as lost.
    secrets.fail_key(Some(KeyFault::Lost));
    let core = open(&dir, &secrets).unwrap();
    assert!(core.store_reset().unwrap().is_some());
    // The session and the device key belonged to the MLS state in the old file.
    assert!(core.session().is_none());
    for name in ["session", "device_signing_key"] {
        assert_eq!(secrets.value(name), None, "{name}");
    }
    secrets.fail_key(None);
    drop(core);

    // The new key opens the new store, which stays once the notice is read.
    let core = open(&dir, &secrets).unwrap();
    assert!(core.store_reset().unwrap().is_some());
    assert_eq!(std::fs::read_dir(dir.path().join("unreadable")).unwrap().count(), 1);
}

#[test]
fn rejects_a_stored_key_of_the_wrong_length() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    secrets.put("db_key".to_owned(), vec![1; 16]).unwrap();

    assert!(matches!(open(&dir, &secrets), Err(CoreError::SecretStore { .. })));
}

#[test]
fn takes_any_other_callback_failure_for_a_store_that_cannot_answer() {
    // What UniFFI hands over when the app's callback throws something other than SecretStoreError: it must never
    // count as lost, or the store would go aside for a keychain that only failed once.
    let trace =
        "android.security.KeyStoreException: busy\n\tat android.security.KeyStore2.getKeyEntry(KeyStore2.java:1)";
    let error = SecretStoreError::from(uniffi::UnexpectedUniFFICallbackError::new(trace));
    // Kotlin hands over the whole stack trace; the reason keeps its first line.
    assert!(
        matches!(&error, SecretStoreError::Unavailable { reason } if reason == "android.security.KeyStoreException: busy"),
        "{error:?}"
    );
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
    drop(open(&dir, &secrets).unwrap());
    signed_in(&secrets);

    let core = open(&dir, &secrets).unwrap();
    assert_eq!(core.session().map(|s| (s.user_id, s.device_id)), Some(("u1".to_owned(), "d1".to_owned())));
    assert!(core.groups().unwrap().is_empty());
    assert!(!core.cipher_version().unwrap().is_empty());
}

#[test]
fn refuses_plain_http_outside_the_local_stack() {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    let refused = Core::open(
        dir.path().to_str().unwrap().to_owned(),
        "http://api.example".to_owned(),
        Arc::new(Quiet),
        secrets.clone(),
    );
    assert!(matches!(refused, Err(CoreError::Invalid { .. })));
}

/// What sign_in_guest leaves behind in the secret store: a session and the device signing key.
fn signed_in(secrets: &MemorySecrets) {
    let (secret, _) = geunal_mls::MlsClient::generate_signing_key().unwrap();
    secrets.put("device_signing_key".to_owned(), secret).unwrap();
    secrets
        .put(
            "session".to_owned(),
            br#"{"user_id":"u1","device_id":"d1","access_token":"tok","refresh_token":"r"}"#.to_vec(),
        )
        .unwrap();
}

fn person(name: &str) -> PersonInput {
    PersonInput { id: None, name: name.to_owned(), birthday: None, alert: AlertRule::Inherit, suggest: false }
}

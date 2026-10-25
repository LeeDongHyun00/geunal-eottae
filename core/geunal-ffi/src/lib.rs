//! The UniFFI facade the apps call (docs/parts/rust-core.md §3): Swift sees `Core.open(...)` and `sync() async throws`,
//! Kotlin `Core.open(...)` and `suspend fun sync()`. Only the M0 surface exists so far. Opening the encrypted store
//! here also makes every app build link SQLCipher; `sync` has nothing to do until the sync engine lands.

use std::path::Path;
use std::sync::{Arc, Mutex};

use geunal_store::Store;
use rand_core::{OsRng, RngCore};

uniffi::setup_scaffolding!();

/// The name under which the app's keychain or keystore holds the database key.
const DB_KEY: &str = "db_key";

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("store: {reason}")]
    Store { reason: String },
    #[error("secret store: {reason}")]
    SecretStore { reason: String },
}

/// Implemented by the app over the iOS keychain or the Android keystore. The core keeps its secrets (device signing
/// key, account master key, database key) only here, never in SQLite (docs/parts/rust-core.md §3).
#[uniffi::export(with_foreign)]
pub trait SecretStore: Send + Sync {
    fn get(&self, name: String) -> Option<Vec<u8>>;
    fn put(&self, name: String, value: Vec<u8>);
    fn delete(&self, name: String);
}

/// What changed, so the app knows which screens to redraw.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Change {
    Calendar,
    Group { group_id: String },
    Chat { group_id: String },
}

/// Implemented by the app to hear about changes.
#[uniffi::export(with_foreign)]
pub trait CoreListener: Send + Sync {
    fn on_change(&self, change: Change);
}

#[derive(uniffi::Object)]
pub struct Core {
    store: Mutex<Store>,
    // Kept from `open` for the sync engine (docs/parts/rust-core.md §5), which does not exist yet.
    #[allow(dead_code)]
    api_base: String,
    #[allow(dead_code)]
    listener: Arc<dyn CoreListener>,
}

#[uniffi::export]
impl Core {
    /// Opens or creates the encrypted store in `store_dir`. The database key is made on first open and kept in
    /// `secrets`, which is why `open` takes it in addition to the arguments in docs/parts/rust-core.md §3.
    #[uniffi::constructor]
    pub fn open(
        store_dir: String,
        api_base: String,
        listener: Arc<dyn CoreListener>,
        secrets: Arc<dyn SecretStore>,
    ) -> Result<Arc<Self>, CoreError> {
        let key = database_key(secrets.as_ref())?;
        let store = Store::open(&Path::new(&store_dir).join("geunal.db"), &key)
            .map_err(|e| CoreError::Store { reason: e.to_string() })?;
        Ok(Arc::new(Self { store: Mutex::new(store), api_base, listener }))
    }

    /// Catches every group up from its cursor.
    pub async fn sync(&self) -> Result<(), CoreError> {
        Ok(())
    }

    /// The SQLCipher release inside the app build, for the M0 screen that shows the core runs on the device.
    pub fn cipher_version(&self) -> Result<String, CoreError> {
        let store = self.store.lock().map_err(|_| CoreError::Store { reason: "store lock poisoned".into() })?;
        store.cipher_version().map_err(|e| CoreError::Store { reason: e.to_string() })
    }
}

fn database_key(secrets: &dyn SecretStore) -> Result<[u8; 32], CoreError> {
    if let Some(stored) = secrets.get(DB_KEY.to_owned()) {
        return stored.try_into().map_err(|_| CoreError::SecretStore { reason: format!("{DB_KEY} is not 32 bytes") });
    }
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    secrets.put(DB_KEY.to_owned(), key.to_vec());
    Ok(key)
}

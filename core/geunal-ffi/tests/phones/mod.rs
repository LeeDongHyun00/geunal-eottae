//! Phones on the in-process fake server, for the account and restore tests.
// Each test binary uses part of this.
#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use geunal_crypto::recovery::{self, RecoveryKeys};
use geunal_ffi::{AuthResult, Change, Core, CoreError, CoreListener, DeletionProof, NewAccount, SecretStore};
use geunal_sync::fake::FakeServer;

#[derive(Default)]
pub struct MemorySecrets(Mutex<HashMap<String, Vec<u8>>>);

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

impl MemorySecrets {
    pub fn has(&self, name: &str) -> bool {
        self.0.lock().unwrap().contains_key(name)
    }
}

pub struct Quiet;

impl CoreListener for Quiet {
    fn on_change(&self, _change: Change) {}
}

/// One phone: its store directory, its secret store and its core.
pub struct Phone {
    pub _dir: tempfile::TempDir,
    pub secrets: Arc<MemorySecrets>,
    pub core: Arc<Core>,
}

pub fn phone(server: &FakeServer) -> Phone {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    let core =
        Core::open_fake(dir.path().to_str().unwrap().to_owned(), server.client(), Arc::new(Quiet), secrets.clone())
            .unwrap();
    Phone { _dir: dir, secrets, core }
}

pub fn new_account(name: &str) -> NewAccount {
    NewAccount { display_name: name.to_owned(), age_confirmed_at: SystemTime::now() }
}

pub async fn kakao(core: &Arc<Core>, subject: &str, name: &str) -> Result<AuthResult, CoreError> {
    let nonce = core.clone().sign_in_nonce().await?;
    let id_token = FakeServer::id_token("kakao", subject, &nonce.provider_nonce);
    core.clone().sign_in_kakao(nonce.nonce, id_token, new_account(name)).await
}

/// A fresh Kakao proof for `delete_account`, as the app gets one from the Kakao SDK.
pub async fn kakao_deletion(core: &Arc<Core>, subject: &str) -> DeletionProof {
    let nonce = core.clone().sign_in_nonce().await.unwrap();
    DeletionProof::Kakao { id_token: FakeServer::id_token("kakao", subject, &nonce.provider_nonce), nonce: nonce.nonce }
}

/// The keys the words on screen derive: the uploaded recovery material must be theirs.
pub fn keys_of(core: &Core) -> RecoveryKeys {
    let words = core.recovery_words().unwrap();
    assert_eq!(words.len(), recovery::WORD_COUNT);
    RecoveryKeys::derive(&recovery::entropy(&words).unwrap()).unwrap()
}

pub fn user_id(result: &AuthResult) -> String {
    match result {
        AuthResult::Registered { session, .. } => session.user_id.clone(),
        AuthResult::ReplaceRequired { user_id, .. } => user_id.clone(),
    }
}

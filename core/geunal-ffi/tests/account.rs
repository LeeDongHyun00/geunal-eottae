//! The M1 account flows (protocol §10, §13) through the FFI against the in-process fake server, which keeps the
//! server's rules: nonce binding, replacement tickets, recovery proofs and device revocation.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use geunal_crypto::recovery::{self, RecoveryKeys};
use geunal_ffi::{
    AlertRule, AuthResult, Change, Core, CoreError, CoreListener, NewAccount, PersonInput, Provider, RecoveryCodeCheck,
    RevokedVia, SecretStore,
};
use geunal_sync::fake::FakeServer;

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

impl MemorySecrets {
    fn has(&self, name: &str) -> bool {
        self.0.lock().unwrap().contains_key(name)
    }
}

struct Quiet;

impl CoreListener for Quiet {
    fn on_change(&self, _change: Change) {}
}

/// One phone: its store directory, its secret store and its core.
struct Phone {
    _dir: tempfile::TempDir,
    secrets: Arc<MemorySecrets>,
    core: Arc<Core>,
}

fn phone(server: &FakeServer) -> Phone {
    let dir = tempfile::tempdir().unwrap();
    let secrets = Arc::new(MemorySecrets::default());
    let core =
        Core::open_fake(dir.path().to_str().unwrap().to_owned(), server.client(), Arc::new(Quiet), secrets.clone())
            .unwrap();
    Phone { _dir: dir, secrets, core }
}

fn new_account(name: &str) -> NewAccount {
    NewAccount { display_name: name.to_owned(), age_confirmed_at: SystemTime::now() }
}

async fn kakao(core: &Arc<Core>, subject: &str, name: &str) -> Result<AuthResult, CoreError> {
    let nonce = core.clone().sign_in_nonce().await?;
    let id_token = FakeServer::id_token("kakao", subject, &nonce.provider_nonce);
    core.clone().sign_in_kakao(nonce.nonce, id_token, new_account(name)).await
}

/// The keys the words on screen derive: the uploaded recovery material must be theirs.
fn keys_of(core: &Core) -> RecoveryKeys {
    let words = core.recovery_words().unwrap();
    assert_eq!(words.len(), recovery::WORD_COUNT);
    RecoveryKeys::derive(&recovery::entropy(&words).unwrap()).unwrap()
}

fn user_id(result: &AuthResult) -> String {
    match result {
        AuthResult::Registered { session, .. } => session.user_id.clone(),
        AuthResult::ReplaceRequired { user_id, .. } => user_id.clone(),
    }
}

#[test]
fn a_kakao_sign_in_makes_the_account_and_uploads_the_code_it_shows() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);

        let result = kakao(&a.core, "alice", "앨리스").await.unwrap();
        assert!(matches!(result, AuthResult::Registered { new_account: true, recovery: false, .. }));
        let user = user_id(&result);

        let status = a.core.recovery_status().unwrap();
        assert!(status.created && status.uploaded && !status.saved);
        let keys = keys_of(&a.core);
        let (wrapped, recovery_id, public_key) = server.recovery_of(&user).unwrap();
        assert_eq!(recovery_id, keys.id());
        assert_eq!(public_key, keys.public_key());
        assert!(keys.unwrap_amk(&wrapped).is_some());

        let account = a.core.account().unwrap().unwrap();
        assert_eq!((account.display_name.as_str(), account.providers), ("앨리스", vec![Provider::Kakao]));
        assert!(!a.core.me_summary().unwrap().recovery_saved);
        a.core.mark_recovery_saved().unwrap();
        assert!(a.core.me_summary().unwrap().recovery_saved);
    });
}

#[test]
fn a_provider_token_bound_to_another_key_is_refused() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));

        // The token was issued for phone A's nonce; phone B cannot replay it with its own key.
        let nonce = a.core.clone().sign_in_nonce().await.unwrap();
        let id_token = FakeServer::id_token("kakao", "alice", &nonce.provider_nonce);
        let err = b.core.clone().sign_in_kakao(nonce.nonce, id_token, new_account("앨리스")).await.unwrap_err();
        assert!(
            matches!(err, CoreError::Api { status: 401, ref code, .. } if code == "provider_token_invalid"),
            "{err:?}"
        );
        assert!(b.core.session().is_none());
    });
}

#[test]
fn a_second_phone_starts_fresh_and_the_first_learns_who_signed_it_out() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        let user = user_id(&kakao(&a.core, "bob", "밥").await.unwrap());
        let first_code = a.core.recovery_words().unwrap();
        let person =
            PersonInput { id: None, name: "엄마".into(), birthday: None, alert: AlertRule::Inherit, suggest: true };
        a.core.save_person(person).unwrap();
        let calendars = a.core.calendars().unwrap().len();

        let result = kakao(&b.core, "bob", "밥").await.unwrap();
        let AuthResult::ReplaceRequired { active_platform, recovery, .. } = result else { panic!("{result:?}") };
        assert_eq!(active_platform, "android");
        assert!(recovery);
        assert!(b.core.session().is_none(), "nothing is registered before the replacement");

        let session = b.core.clone().start_fresh().await.unwrap();
        assert_eq!(server.active_device(&user), Some(session.device_id.clone()));
        assert_eq!(server.backups_cleared(&user), 1, "nobody can open the old backups");
        assert!(b.core.recovery_status().unwrap().uploaded);
        assert_ne!(b.core.recovery_words().unwrap(), first_code);
        let (_, recovery_id, _) = server.recovery_of(&user).unwrap();
        assert_eq!(recovery_id, keys_of(&b.core).id());

        // Phone A hears about it on its next request.
        let err = a.core.clone().sync().await.unwrap_err();
        assert!(matches!(err, CoreError::Api { status: 403, ref code, .. } if code == "device_revoked"), "{err:?}");
        let revocation = a.core.revocation().unwrap().unwrap();
        assert_eq!((revocation.by_platform.as_str(), revocation.via), ("android", RevokedVia::Provider));
        assert!(a.core.session().is_none() && a.core.account().unwrap().is_none());
        for secret in ["session", "device_signing_key", "recovery_entropy", "account_master_key"] {
            assert!(!a.secrets.has(secret), "{secret} is gone");
        }
        assert!(a.secrets.has("db_key"), "the calendar still opens");
        assert!(matches!(a.core.recovery_words(), Err(CoreError::NotFound { .. })));
        assert_eq!(a.core.people().unwrap().len(), 1);
        assert_eq!(a.core.calendars().unwrap().len(), calendars);

        // Signing in again is a new device: it has to replace phone B in turn.
        let again = kakao(&a.core, "bob", "밥").await.unwrap();
        assert!(matches!(again, AuthResult::ReplaceRequired { .. }));
        assert!(a.core.revocation().unwrap().is_some(), "kept until a sign-in registers this device");
    });
}

#[test]
fn a_guest_gets_a_code_at_once_and_links_kakao_later() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);

        let session = a.core.clone().sign_in_guest(new_account("  게스트  ")).await.unwrap();
        assert!(a.core.recovery_status().unwrap().uploaded);
        assert_eq!(server.recovery_of(&session.user_id).unwrap().1, keys_of(&a.core).id());
        let account = a.core.account().unwrap().unwrap();
        assert_eq!(account.display_name, "게스트");
        assert!(account.providers.is_empty());

        let nonce = a.core.clone().sign_in_nonce().await.unwrap();
        let id_token = FakeServer::id_token("kakao", "carol", &nonce.provider_nonce);
        assert_eq!(a.core.clone().link_kakao(nonce.nonce, id_token).await.unwrap(), vec![Provider::Kakao]);
        assert_eq!(a.core.account().unwrap().unwrap().providers, vec![Provider::Kakao]);
        assert_eq!(server.providers_of(&session.user_id), vec!["kakao".to_owned()]);
    });
}

#[test]
fn a_new_account_without_a_name_takes_the_default_and_a_long_one_is_cut() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        a.core.clone().sign_in_guest(new_account("   ")).await.unwrap();
        assert_eq!(a.core.account().unwrap().unwrap().display_name, "게스트");
        let b = phone(&server);
        kakao(&b.core, "gina", "").await.unwrap();
        assert_eq!(b.core.account().unwrap().unwrap().display_name, "카카오 사용자");
        let c = phone(&server);
        kakao(&c.core, "hana", &"가".repeat(41)).await.unwrap();
        assert_eq!(c.core.account().unwrap().unwrap().display_name, "가".repeat(40));
    });
}

#[test]
fn starting_over_works_on_a_registered_device_and_needs_a_new_sign_in_after_thirty_minutes() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let t0 = chrono::Utc::now();
        server.set_now(t0);
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "erin", "에린").await.unwrap());
        // The code is gone from this device while the server keeps the material: the "나중에 넣을게요" state.
        for secret in ["recovery_entropy", "account_master_key"] {
            a.secrets.delete(secret.to_owned());
        }
        let again = kakao(&a.core, "erin", "에린").await.unwrap();
        assert!(matches!(again, AuthResult::Registered { new_account: false, recovery: true, .. }), "{again:?}");
        assert!(!a.core.recovery_status().unwrap().created);

        server.set_now(t0 + chrono::Duration::minutes(31));
        assert!(matches!(a.core.clone().start_fresh().await, Err(CoreError::SignInAgain)));
        assert!(!a.core.recovery_status().unwrap().created, "the code the server refused is dropped");
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.backups_cleared(&user), 0);

        kakao(&a.core, "erin", "에린").await.unwrap();
        a.core.clone().start_fresh().await.unwrap();
        assert_eq!(server.backups_cleared(&user), 1);
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&a.core).id());
    });
}

#[test]
fn a_replacement_ticket_that_ran_out_needs_a_new_sign_in() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let t0 = chrono::Utc::now();
        server.set_now(t0);
        let (a, b) = (phone(&server), phone(&server));
        let user = user_id(&kakao(&a.core, "finn", "핀").await.unwrap());
        let result = kakao(&b.core, "finn", "핀").await.unwrap();
        assert!(matches!(result, AuthResult::ReplaceRequired { .. }), "{result:?}");

        server.set_now(t0 + chrono::Duration::minutes(31));
        assert!(matches!(b.core.clone().start_fresh().await, Err(CoreError::SignInAgain)));
        assert!(b.core.session().is_none());
        assert_eq!(server.active_device(&user), a.core.session().map(|s| s.device_id));
        a.core.clone().sync().await.unwrap();
    });
}

#[test]
fn an_expired_access_token_is_refreshed_and_the_new_pair_survives_a_restart() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let dir = tempfile::tempdir().unwrap();
        let secrets = Arc::new(MemorySecrets::default());
        let open = || {
            Core::open_fake(dir.path().to_str().unwrap().to_owned(), server.client(), Arc::new(Quiet), secrets.clone())
        };
        let core = open().unwrap();
        core.clone().sign_in_guest(new_account("게스트")).await.unwrap();
        let before = secrets.get("session".to_owned()).unwrap();

        server.expire_access_tokens();
        core.clone().sync().await.unwrap();
        let after = secrets.get("session".to_owned()).unwrap();
        assert_ne!(before, after, "the refreshed tokens are kept");
        drop(core);

        // The old refresh token was spent; a restarted app goes on with the new one.
        let core = open().unwrap();
        core.clone().sync().await.unwrap();
        assert!(core.account().unwrap().is_some());
    });
}

#[test]
fn the_code_check_tells_a_missing_word_from_a_wrong_checksum_without_the_server() {
    let server = FakeServer::new();
    let a = phone(&server);
    let words: Vec<String> = recovery::words(&[7; 16]).iter().map(|w| (*w).to_owned()).collect();
    assert_eq!(a.core.check_recovery_code(words.clone()), RecoveryCodeCheck::Valid);

    // The last word carries the checksum in its low four bits: its neighbour in the list keeps the entropy bits and
    // breaks the checksum.
    let last = recovery::list().iter().position(|w| *w == words[11]).unwrap();
    let mut wrong = words.clone();
    wrong[11] = recovery::list()[last ^ 1].to_owned();
    assert_eq!(a.core.check_recovery_code(wrong), RecoveryCodeCheck::Checksum);

    let mut unknown = words.clone();
    unknown[3] = "없는말".into();
    assert_eq!(a.core.check_recovery_code(unknown), RecoveryCodeCheck::UnknownWord { index: 3 });
    assert_eq!(a.core.check_recovery_code(words[..11].to_vec()), RecoveryCodeCheck::WrongWordCount { count: 11 });
}

#[test]
fn the_word_helpers_follow_the_list() {
    let server = FakeServer::new();
    let a = phone(&server);
    let word = recovery::list()[100];
    assert_eq!(a.core.check_recovery_word(format!(" {word} ")).unwrap().as_deref(), Some(word));
    assert_eq!(a.core.check_recovery_word("없는말".into()).unwrap(), None);
    assert!(a.core.recovery_completions(word.chars().take(1).collect()).unwrap().contains(&word.to_owned()));
    assert_eq!(a.core.split_recovery_code(format!("1. {word}\n2) {word}")).unwrap(), vec![word, word]);
}

//! Backups and restores (protocol §10) through the FFI against the fake server: a second phone, a guest coming back,
//! and another account's events left on a phone.

mod phones;

use std::sync::{Arc, Mutex};

use geunal_ffi::{
    AlertRule, AuthResult, Core, CoreError, LocalData, LocalDataChoice, PersonInput, ProgressListener, RevokedVia,
    SecretStore,
};
use geunal_sync::fake::FakeServer;
use phones::{Phone, kakao, new_account, phone, user_id};

fn person(name: &str) -> PersonInput {
    PersonInput { id: None, name: name.into(), birthday: None, alert: AlertRule::Inherit, suggest: true }
}

fn names(core: &Core) -> Vec<String> {
    let mut names: Vec<String> = core.people().unwrap().into_iter().map(|p| p.name).collect();
    names.sort();
    names
}

#[derive(Default)]
struct Steps(Mutex<Vec<(u64, u64)>>);

impl ProgressListener for Steps {
    fn on_progress(&self, done: u64, total: u64) {
        self.0.lock().unwrap().push((done, total));
    }
}

/// A Kakao phone with two people and one backup.
async fn backed_up(server: &FakeServer, subject: &str) -> (Phone, String, Vec<String>) {
    let a = phone(server);
    let user = user_id(&kakao(&a.core, subject, "하린").await.unwrap());
    a.core.save_person(person("엄마")).unwrap();
    a.core.save_person(person("아빠")).unwrap();
    let view = a.core.clone().backup_now(None).await.unwrap();
    assert_eq!((view.people, view.automatic), (Some(2), Some(false)));
    let words = a.core.recovery_words().unwrap();
    (a, user, words)
}

#[test]
fn a_second_phone_brings_the_backup_over_with_the_code_and_replaces_the_first() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, user, words) = backed_up(&server, "ivy").await;
        let status = a.core.backup_status().unwrap();
        assert!(status.ready && !status.changed && status.last.is_some());

        let b = phone(&server);
        let result = kakao(&b.core, "ivy", "하린").await.unwrap();
        assert!(matches!(result, AuthResult::ReplaceRequired { recovery: true, .. }), "{result:?}");
        assert_eq!(b.core.local_data_of_other_account().unwrap(), None, "a new phone has nothing to ask about");
        assert_eq!(b.core.clone().backups().await.unwrap().len(), 1, "the ticket reads the list");

        // A code of another account passes the checksum but does not unwrap the master key; the ticket stays.
        let other: Vec<String> = geunal_crypto::recovery::words(&[9; 16]).iter().map(|w| (*w).to_owned()).collect();
        let wrong = b.core.clone().restore_from_code(other, LocalDataChoice::Keep, None).await;
        assert!(matches!(wrong, Err(CoreError::WrongRecoveryCode)), "{wrong:?}");
        assert!(b.core.session().is_none());

        let steps = Arc::new(Steps::default());
        let summary = b.core.clone().restore_from_code(words.clone(), LocalDataChoice::Keep, Some(steps.clone())).await;
        let summary = summary.unwrap();
        assert_eq!((summary.people, summary.replaced_device), (2, true));
        assert!(summary.backup_at.is_some());
        let last = *steps.0.lock().unwrap().last().unwrap();
        assert_eq!(last.0, last.1, "the progress ends at the full size");
        assert_eq!(names(&b.core), ["아빠", "엄마"]);
        assert_eq!(server.active_device(&user), b.core.session().map(|s| s.device_id));
        assert_eq!(b.core.recovery_words().unwrap(), words);
        let status = b.core.recovery_status().unwrap();
        assert!(status.uploaded && status.saved, "the code was just typed from where it was kept");

        // The first phone is signed out, and the new one backs up under the same code.
        assert!(a.core.clone().sync().await.is_err());
        assert_eq!(a.core.revocation().unwrap().map(|r| r.via), Some(RevokedVia::Provider));
        b.core.clone().backup_now(None).await.unwrap();
        assert_eq!(server.backups_of(&user).len(), 2);
    });
}

#[test]
fn a_guest_comes_back_on_a_new_phone_with_the_code_alone() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let guest = a.core.clone().sign_in_guest(new_account("게스트")).await.unwrap();
        a.core.save_person(person("동생")).unwrap();
        a.core.clone().backup_now(None).await.unwrap();
        let words = a.core.recovery_words().unwrap();

        let b = phone(&server);
        let unknown: Vec<String> = geunal_crypto::recovery::words(&[9; 16]).iter().map(|w| (*w).to_owned()).collect();
        let wrong = b.core.clone().restore_from_code(unknown, LocalDataChoice::Keep, None).await;
        assert!(matches!(wrong, Err(CoreError::WrongRecoveryCode)), "{wrong:?}");
        assert!(b.core.session().is_none());

        let summary = b.core.clone().restore_from_code(words, LocalDataChoice::Keep, None).await.unwrap();
        assert_eq!((summary.people, summary.replaced_device), (1, true));
        assert_eq!(b.core.session().map(|s| s.user_id), Some(guest.user_id));
        assert_eq!(names(&b.core), ["동생"]);
        assert!(a.core.clone().sync().await.is_err());
        assert_eq!(a.core.revocation().unwrap().map(|r| r.via), Some(RevokedVia::Recovery));
    });
}

#[test]
fn a_mistyped_code_never_reaches_the_server() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let b = phone(&server);
        let typo = vec!["없는말".to_owned(); 12];
        let result = b.core.clone().restore_from_code(typo, LocalDataChoice::Keep, None).await;
        assert!(matches!(result, Err(CoreError::WrongRecoveryCode)));
        assert!(b.core.session().is_none());
    });
}

#[test]
fn another_accounts_events_go_only_once_the_code_opens_the_backup() {
    pollster::block_on(async {
        let server = FakeServer::new();
        // Phone P was Jay's; Jay's new phone signed it out, and Jay's people stayed on it.
        let p = phone(&server);
        kakao(&p.core, "jay", "제이").await.unwrap();
        p.core.save_person(person("제이 엄마")).unwrap();
        let jay_new = phone(&server);
        let result = kakao(&jay_new.core, "jay", "제이").await.unwrap();
        assert!(matches!(result, AuthResult::ReplaceRequired { .. }));
        jay_new.core.clone().start_fresh(None).await.unwrap();
        assert!(p.core.clone().sync().await.is_err());
        assert_eq!(p.core.local_data_of_other_account().unwrap(), Some(LocalData { events: 0, people: 1 }));

        // Lee, with a backup on another phone, signs in on P.
        let (_lee_phone, _, lee_words) = backed_up(&server, "lee").await;
        assert!(matches!(kakao(&p.core, "lee", "리").await.unwrap(), AuthResult::ReplaceRequired { .. }));
        assert_eq!(p.core.local_data_of_other_account().unwrap(), Some(LocalData { events: 0, people: 1 }));

        // A wrong code deletes nothing, whatever the choice.
        let other: Vec<String> = geunal_crypto::recovery::words(&[9; 16]).iter().map(|w| (*w).to_owned()).collect();
        let wrong = p.core.clone().restore_from_code(other, LocalDataChoice::Discard, None).await;
        assert!(matches!(wrong, Err(CoreError::WrongRecoveryCode)));
        assert_eq!(names(&p.core), ["제이 엄마"]);

        p.core.clone().restore_from_code(lee_words, LocalDataChoice::Discard, None).await.unwrap();
        assert_eq!(names(&p.core), ["아빠", "엄마"]);
        assert_eq!(p.core.local_data_of_other_account().unwrap(), None);
    });
}

#[test]
fn a_new_account_on_a_phone_with_someone_elses_events_settles_them_before_any_backup() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let p = phone(&server);
        kakao(&p.core, "kai", "카이").await.unwrap();
        p.core.save_person(person("카이 친구")).unwrap();
        // Kai's other phone takes the account over, and Mina signs up on P.
        let other = phone(&server);
        kakao(&other.core, "kai", "카이").await.unwrap();
        other.core.clone().start_fresh(None).await.unwrap();
        assert!(p.core.clone().sync().await.is_err());
        let mina = kakao(&p.core, "mina", "미나").await.unwrap();
        assert!(matches!(mina, AuthResult::Registered { new_account: true, .. }));

        assert_eq!(p.core.local_data_of_other_account().unwrap(), Some(LocalData { events: 0, people: 1 }));
        assert!(!p.core.backup_status().unwrap().ready);
        assert!(matches!(p.core.clone().backup_now(None).await, Err(CoreError::Invalid { .. })));

        p.core.settle_local_data(LocalDataChoice::Discard).unwrap();
        assert!(names(&p.core).is_empty());
        assert_eq!(p.core.local_data_of_other_account().unwrap(), None);
        assert!(!p.core.calendars().unwrap().is_empty(), "the default calendar comes back");
        p.core.clone().backup_now(None).await.unwrap();
    });
}

#[test]
fn the_automatic_backup_runs_only_after_a_change() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, user, _) = backed_up(&server, "noah").await;
        assert!(a.core.clone().auto_backup().await.unwrap().is_none(), "nothing changed since");

        a.core.save_person(person("할머니")).unwrap();
        assert!(a.core.backup_status().unwrap().changed);
        let view = a.core.clone().auto_backup().await.unwrap().unwrap();
        assert_eq!((view.automatic, view.people), (Some(true), Some(3)));
        assert!(!a.core.backup_status().unwrap().changed);

        a.core.set_auto_backup(false).unwrap();
        a.core.save_person(person("할아버지")).unwrap();
        assert!(a.core.clone().auto_backup().await.unwrap().is_none(), "turned off");
        assert!(!a.core.backup_status().unwrap().auto);

        // The server keeps three, and so does the list.
        a.core.clone().backup_now(None).await.unwrap();
        a.core.clone().backup_now(None).await.unwrap();
        let list = a.core.clone().backups().await.unwrap();
        assert_eq!(list.len(), 3);
        assert_eq!(server.backups_of(&user).len(), 3);
        assert!(list.iter().all(|b| b.automatic.is_some()), "this phone made them all");
    });
}

#[test]
fn starting_over_forgets_the_deleted_backups() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, user, words) = backed_up(&server, "olive").await;
        assert!(a.core.backup_status().unwrap().last.is_some());
        // Starting over is for a lost code: the code is gone from this phone, and the server keeps the material.
        for secret in ["recovery_entropy", "account_master_key"] {
            a.secrets.delete(secret.to_owned());
        }
        a.core.clone().start_fresh(None).await.unwrap();
        assert!(server.backups_of(&user).is_empty());
        assert!(a.core.backup_status().unwrap().last.is_none());
        assert!(a.core.clone().backups().await.unwrap().is_empty());
        assert_ne!(a.core.recovery_words().unwrap(), words);
    });
}

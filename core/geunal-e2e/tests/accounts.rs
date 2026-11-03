//! The M1 account flows against the local stack (protocol §10, §13), through the same `Core` the apps call: a guest's
//! recovery code goes up at sign-in, a backup goes to the object store through a presigned URL, a second phone comes
//! back with the twelve words alone and signs the first out, and the account is deleted with the guest's own proof.
//!
//! A guest needs neither Kakao nor Apple nor the auth stub, so this runs against any M1 API.

use std::sync::Arc;
use std::time::SystemTime;

use geunal_e2e::{MemorySecrets, Quiet, api_url};
use geunal_ffi::{AlertRule, Core, CoreError, DeletionProof, LocalDataChoice, NewAccount, PersonInput, RevokedVia};
use pollster::block_on;

/// A fresh install: its own store and secrets, nobody signed in.
fn phone() -> (tempfile::TempDir, Arc<Core>) {
    let dir = tempfile::tempdir().unwrap();
    let core = Core::open(
        dir.path().to_str().unwrap().to_owned(),
        api_url(),
        Arc::new(Quiet),
        Arc::new(MemorySecrets::default()),
    )
    .unwrap();
    (dir, core)
}

#[test]
fn a_guest_backs_up_comes_back_on_a_second_phone_with_the_code_and_deletes_the_account() {
    let (_a_dir, a) = phone();
    let account = NewAccount { display_name: "e2e 게스트".into(), age_confirmed_at: SystemTime::now() };
    let session = block_on(a.clone().sign_in_guest(account)).unwrap();
    let status = a.recovery_status().unwrap();
    assert!(status.created && status.uploaded, "the code went up at the sign-in: {status:?}");

    let person =
        PersonInput { id: None, name: "엄마".into(), birthday: None, alert: AlertRule::Inherit, suggest: true };
    a.save_person(person).unwrap();
    let backup = block_on(a.clone().backup_now(None)).unwrap();
    assert_eq!(backup.people, Some(1), "{backup:?}");
    let words = a.recovery_words().unwrap();

    // The second phone has nothing but the twelve words.
    let (_b_dir, b) = phone();
    let summary = block_on(b.clone().restore_from_code(words.clone(), LocalDataChoice::Keep, None)).unwrap();
    assert_eq!((summary.people, summary.replaced_device), (1, true), "{summary:?}");
    assert_eq!(b.session().map(|s| s.user_id), Some(session.user_id.clone()));
    assert_eq!(b.people().unwrap().into_iter().map(|p| p.name).collect::<Vec<_>>(), ["엄마"]);

    // The first phone hears it was signed out by a comeback with the code, and keeps its calendar.
    assert!(block_on(a.clone().sync()).is_err());
    assert!(a.session().is_none());
    assert_eq!(a.revocation().unwrap().map(|r| r.via), Some(RevokedVia::Recovery));
    assert_eq!(a.people().unwrap().len(), 1);

    // The guest deletes the account; the core signs the proof with the code and the device key.
    block_on(b.clone().delete_account(DeletionProof::Guest)).unwrap();
    assert!(b.session().is_none());
    assert_eq!(b.people().unwrap().len(), 1, "the calendar stays on the phone");

    // Nothing is left for the code to come back to.
    let (_c_dir, c) = phone();
    let gone = block_on(c.clone().restore_from_code(words, LocalDataChoice::Keep, None));
    assert!(matches!(gone, Err(CoreError::WrongRecoveryCode)), "{gone:?}");
}

//! The M1 account flows (protocol §10, §13) through the FFI against the in-process fake server, which keeps the
//! server's rules: nonce binding, replacement tickets, recovery proofs and device revocation.

mod phones;

use std::sync::Arc;

use geunal_crypto::recovery;
use geunal_ffi::{
    AlertRule, AuthResult, Core, CoreError, DeletionProof, PersonInput, Provider, RecoveryCodeCheck, RevokedVia,
    SecretStore,
};
use geunal_sync::fake::FakeServer;
use phones::{MemorySecrets, Quiet, kakao, keys_of, new_account, phone, user_id};

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

        let session = b.core.clone().start_fresh(None).await.unwrap();
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
fn deleting_an_account_takes_a_fresh_proof_of_it_and_leaves_the_calendar() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "dana", "다나").await.unwrap());
        let person =
            PersonInput { id: None, name: "엄마".into(), birthday: None, alert: AlertRule::Inherit, suggest: true };
        a.core.save_person(person).unwrap();
        a.core.set_alpha_stats(true).unwrap();

        // Another Kakao account's token, or a proof of the wrong kind, deletes nothing and keeps the session.
        let nonce = a.core.clone().sign_in_nonce().await.unwrap();
        let other = DeletionProof::Kakao {
            id_token: FakeServer::id_token("kakao", "someone-else", &nonce.provider_nonce),
            nonce: nonce.nonce,
        };
        assert!(matches!(a.core.clone().delete_account(other).await, Err(CoreError::ReauthRequired)));
        assert!(matches!(a.core.clone().delete_account(DeletionProof::Guest).await, Err(CoreError::ReauthRequired)));
        assert!(server.has_account(&user) && a.core.session().is_some());

        let nonce = a.core.clone().sign_in_nonce().await.unwrap();
        let proof = DeletionProof::Kakao {
            id_token: FakeServer::id_token("kakao", "dana", &nonce.provider_nonce),
            nonce: nonce.nonce,
        };
        a.core.clone().delete_account(proof).await.unwrap();
        assert!(!server.has_account(&user));
        assert!(a.core.session().is_none() && a.core.account().unwrap().is_none());
        assert!(a.core.revocation().unwrap().is_none(), "no other device signed this one out");
        for secret in ["session", "device_signing_key", "recovery_entropy", "account_master_key"] {
            assert!(!a.secrets.has(secret), "{secret} is gone");
        }
        assert!(a.secrets.has("db_key"), "the calendar still opens");
        assert_eq!(a.core.people().unwrap().len(), 1);
        assert!(!a.core.alpha_stats_enabled().unwrap(), "a new account agrees again");
        a.core.clone().sync().await.unwrap();

        // The same Kakao account signs up anew and is asked about what the deleted account left on this device.
        let again = kakao(&a.core, "dana", "다나").await.unwrap();
        assert!(matches!(again, AuthResult::Registered { new_account: true, .. }), "{again:?}");
        assert!(a.core.local_data_of_other_account().unwrap().is_some());
    });
}

#[test]
fn a_guest_deletes_with_its_code_once_the_material_the_server_lacked_goes_up() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        // The code's upload drops at the sign-in, so the server has nothing to check a guest's proof against.
        server.fail_next("put_recovery");
        let session = a.core.clone().sign_in_guest(new_account("게스트")).await.unwrap();
        assert!(server.recovery_of(&session.user_id).is_none());

        a.core.clone().delete_account(DeletionProof::Guest).await.unwrap();
        assert!(!server.has_account(&session.user_id));
        assert!(a.core.session().is_none() && !a.secrets.has("recovery_entropy"));
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
fn starting_over_works_on_a_registered_device_and_needs_a_provider_proof_after_thirty_minutes() {
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
        assert!(matches!(a.core.clone().start_fresh(None).await, Err(CoreError::ReauthRequired)));
        assert!(!a.core.recovery_status().unwrap().created, "the code the server refused is dropped");
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.backups_cleared(&user), 0);

        // A proof for another provider account changes nothing.
        let nonce = a.core.clone().sign_in_nonce().await.unwrap();
        let other = DeletionProof::Kakao {
            id_token: FakeServer::id_token("kakao", "someone-else", &nonce.provider_nonce),
            nonce: nonce.nonce,
        };
        assert!(matches!(a.core.clone().start_fresh(Some(other)).await, Err(CoreError::ReauthRequired)));
        let apple = DeletionProof::Apple { authorization_code: FakeServer::apple_code("erin") };
        assert!(matches!(a.core.clone().start_fresh(Some(apple)).await, Err(CoreError::ReauthRequired)));
        assert_eq!(server.backups_cleared(&user), 0);

        // A fresh Kakao proof for this account, bound to this device's key, starts over without signing in again.
        let nonce = a.core.clone().sign_in_nonce().await.unwrap();
        let proof = DeletionProof::Kakao {
            id_token: FakeServer::id_token("kakao", "erin", &nonce.provider_nonce),
            nonce: nonce.nonce,
        };
        a.core.clone().start_fresh(Some(proof)).await.unwrap();
        assert_eq!(server.backups_cleared(&user), 1);
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&a.core).id());
        assert!(matches!(a.core.clone().start_fresh(Some(DeletionProof::Guest)).await, Err(CoreError::Invalid { .. })));

        // With the code on this device nothing is lost: a new key would only make the backups unreadable.
        server.set_now(t0 + chrono::Duration::minutes(32));
        assert!(matches!(a.core.clone().start_fresh(None).await, Err(CoreError::Invalid { .. })));
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&a.core).id());
        assert_eq!(server.backups_cleared(&user), 1);
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
        assert!(matches!(b.core.clone().start_fresh(None).await, Err(CoreError::SignInAgain)));
        assert!(b.core.session().is_none());
        assert_eq!(server.active_device(&user), a.core.session().map(|s| s.device_id));
        a.core.clone().sync().await.unwrap();
    });
}

#[test]
fn a_replacement_that_never_reached_the_server_can_be_tried_again() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        let user = user_id(&kakao(&a.core, "jay", "제이").await.unwrap());
        assert!(matches!(kakao(&b.core, "jay", "제이").await.unwrap(), AuthResult::ReplaceRequired { .. }));

        server.fail_next("replace");
        let err = b.core.clone().start_fresh(None).await.unwrap_err();
        assert!(matches!(err, CoreError::Network { .. }), "{err:?}");
        assert_eq!(server.active_device(&user), a.core.session().map(|s| s.device_id));

        // The ticket is still in memory and still good.
        let session = b.core.clone().start_fresh(None).await.unwrap();
        assert_eq!(server.active_device(&user), Some(session.device_id));
    });
}

#[test]
fn a_new_code_whose_answer_was_lost_goes_up_after_the_thirty_minutes() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let t0 = chrono::Utc::now();
        server.set_now(t0);
        let (a, b) = (phone(&server), phone(&server));
        let user = user_id(&kakao(&a.core, "ivy", "아이비").await.unwrap());
        assert!(matches!(kakao(&b.core, "ivy", "아이비").await.unwrap(), AuthResult::ReplaceRequired { .. }));

        // The server stores the new code, and its answer never arrives.
        server.lose_next("put_recovery");
        let err = b.core.clone().start_fresh(None).await.unwrap_err();
        assert!(matches!(err, CoreError::Network { .. }), "{err:?}");
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&b.core).id());
        assert!(!b.core.recovery_status().unwrap().uploaded);

        // Past the 30 minutes `new_key` alone is refused; the code on the server is this one, and it proves itself.
        server.set_now(t0 + chrono::Duration::minutes(31));
        b.core.clone().sync().await.unwrap();
        assert!(b.core.recovery_status().unwrap().uploaded);
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&b.core).id());
        assert_eq!(server.backups_cleared(&user), 1);
    });
}

#[test]
fn a_revoke_event_signs_the_device_out_even_when_the_server_cannot_say_how() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        kakao(&a.core, "kim", "킴").await.unwrap();
        let device = a.core.session().unwrap().device_id;

        // An event the server stands behind is one GET /me no longer answers.
        a.core.clone().deliver_fake_event(serde_json::json!({ "t": "revoke" })).await.unwrap();
        assert!(a.core.session().is_some());

        kakao(&b.core, "kim", "킴").await.unwrap();
        b.core.clone().start_fresh(None).await.unwrap();
        let revoke = server.take_events(&device).into_iter().find(|e| e["t"] == "revoke").unwrap();
        server.fail_next("me");
        a.core.clone().deliver_fake_event(revoke).await.unwrap();

        assert!(a.core.session().is_none() && a.core.account().unwrap().is_none());
        for secret in ["session", "device_signing_key", "recovery_entropy", "account_master_key"] {
            assert!(!a.secrets.has(secret), "{secret} is gone");
        }
        assert!(a.core.revocation().unwrap().is_none(), "nobody said when or how");
    });
}

#[test]
fn key_packages_that_did_not_go_up_at_the_sign_in_go_up_with_the_next_sync() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);

        server.fail_next("upload_key_packages");
        let session = a.core.clone().sign_in_guest(new_account("게스트")).await.unwrap();
        // The sign-in stands, and the code went up all the same.
        assert!(a.core.recovery_status().unwrap().uploaded);
        assert_eq!(server.key_packages_of(&session.user_id), 0);

        a.core.clone().sync().await.unwrap();
        assert_eq!(server.key_packages_of(&session.user_id), 10);
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.key_packages_of(&session.user_id), 10, "once");
    });
}

#[test]
fn a_device_left_without_a_code_makes_it_on_the_next_run() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let dir = tempfile::tempdir().unwrap();
        let secrets = Arc::new(MemorySecrets::default());
        let open = || {
            Core::open_fake(dir.path().to_str().unwrap().to_owned(), server.client(), Arc::new(Quiet), secrets.clone())
        };
        let core = open().unwrap();
        server.fail_next("put_recovery");
        let session = core.clone().sign_in_guest(new_account("게스트")).await.unwrap();
        // As if the app had been closed before it made the code: the session is kept, and nothing else.
        for secret in ["recovery_entropy", "account_master_key"] {
            secrets.delete(secret.to_owned());
        }
        assert!(server.recovery_of(&session.user_id).is_none());
        drop(core);

        let core = open().unwrap();
        core.clone().sync().await.unwrap();
        let status = core.recovery_status().unwrap();
        assert!(status.created && status.uploaded);
        assert_eq!(server.recovery_of(&session.user_id).unwrap().1, keys_of(&core).id());
    });
}

#[test]
fn a_replacement_left_behind_does_not_act_for_the_next_sign_in() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        let amy = user_id(&kakao(&a.core, "amy", "에이미").await.unwrap());
        // Amy's sign-in on phone B asks to replace phone A; the user backs out and signs up as Ben instead.
        assert!(matches!(kakao(&b.core, "amy", "에이미").await.unwrap(), AuthResult::ReplaceRequired { .. }));
        let ben = user_id(&kakao(&b.core, "ben", "벤").await.unwrap());

        // Starting over on B is about Ben, whose code B holds; Amy's phone and backups are not touched.
        assert!(matches!(b.core.clone().start_fresh(None).await, Err(CoreError::Invalid { .. })));
        assert_eq!(server.active_device(&amy), a.core.session().map(|s| s.device_id));
        assert_eq!(server.backups_cleared(&amy), 0);
        assert_eq!(b.core.session().unwrap().user_id, ben);
    });
}

#[test]
fn the_account_a_sign_in_could_not_fetch_comes_with_the_next_sync() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);

        server.fail_next("me");
        kakao(&a.core, "cleo", "클레오").await.unwrap();
        let account = a.core.account().unwrap().unwrap();
        assert_eq!((account.display_name.as_str(), account.providers.len()), ("클레오", 0), "the name it sent");
        a.core.clone().sync().await.unwrap();
        assert_eq!(a.core.account().unwrap().unwrap().providers, vec![Provider::Kakao]);

        // Signing in again while GET /me fails keeps what the device knew.
        server.fail_next("me");
        kakao(&a.core, "cleo", "클레오").await.unwrap();
        assert_eq!(a.core.account().unwrap().unwrap().providers, vec![Provider::Kakao]);
    });
}

#[test]
fn the_saved_mark_of_one_accounts_code_is_not_another_accounts() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        // Bee's code is on the server; this phone then makes and saves a code for Ace.
        kakao(&a.core, "bee", "비").await.unwrap();
        kakao(&a.core, "ace", "에이스").await.unwrap();
        a.core.mark_recovery_saved().unwrap();
        assert!(a.core.me_summary().unwrap().recovery_saved);

        // Back on Bee, whose code this phone does not hold.
        let bee = kakao(&a.core, "bee", "비").await.unwrap();
        assert!(matches!(bee, AuthResult::Registered { recovery: true, .. }), "{bee:?}");
        assert!(!a.core.recovery_status().unwrap().created);
        assert!(!a.core.me_summary().unwrap().recovery_saved);
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

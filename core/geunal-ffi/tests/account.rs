//! The M1 account flows (protocol §10, §13) through the FFI against the in-process fake server, which keeps the
//! server's rules: nonce binding, replacement tickets, recovery proofs and device revocation.

mod phones;

use std::sync::Arc;

use geunal_crypto::login::{provider_nonce, recovery_upload_message};
use geunal_crypto::recovery::{self, RecoveryKeys};
use geunal_ffi::{
    AlertRule, AuthResult, Core, CoreError, DeletionProof, LocalDataChoice, PersonInput, Provider, RecoveryCodeCheck,
    RevokedVia, SecretStore,
};
use geunal_mls::MlsClient;
use geunal_sync::api::{DeleteAccount, RecoveryUpload};
use geunal_sync::fake::{FakeClient, FakeServer};
use geunal_sync::{ApiError, Session, Transport};
use phones::{Fault, MemorySecrets, Phone, Quiet, kakao, kakao_deletion, keys_of, new_account, phone, user_id};

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
fn a_stolen_session_with_a_fresh_provider_proof_neither_deletes_nor_starts_over_without_the_device_key() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "fern", "펀").await.unwrap());
        // What a man in the middle collects (threat model §3.7): the session's tokens and provider proofs bound to
        // this device's public key. The device's secret key stays on the phone.
        let device_key = a.secrets.get("device_signing_key".into()).unwrap().unwrap()[32..].to_vec();
        let thief = stolen_session(&server, &a);
        let kakao_proof = |nonce: &str| FakeServer::id_token("kakao", "fern", &provider_nonce(nonce, &device_key));
        let refused = |result: Result<(), ApiError>| matches!(result, Err(ref e) if e.is(403, "reauth_required"));

        let nonce = thief.nonce().await.unwrap();
        let unsigned =
            DeleteAccount { kakao_id_token: Some(kakao_proof(&nonce)), nonce: Some(nonce), ..DeleteAccount::default() };
        assert!(refused(thief.delete_account(unsigned).await));
        let nonce = thief.nonce().await.unwrap();
        let forged = DeleteAccount {
            kakao_id_token: Some(kakao_proof(&nonce)),
            nonce: Some(nonce),
            device_signature: Some(vec![7; 64]),
            ..DeleteAccount::default()
        };
        assert!(refused(thief.delete_account(forged).await));
        let nonce = thief.nonce().await.unwrap();
        let apple = DeleteAccount {
            nonce: Some(nonce),
            apple_authorization_code: Some(FakeServer::apple_code("fern")),
            ..DeleteAccount::default()
        };
        assert!(refused(thief.delete_account(apple).await));

        // Within the 30 minutes of the sign-in, too: a new key would delete every backup.
        let (wrapped, recovery_id, public_key) = server.recovery_of(&user).unwrap();
        let nonce = thief.nonce().await.unwrap();
        let start_over = RecoveryUpload {
            amk_wrapped: wrapped,
            recovery_id: vec![9; 16],
            recovery_public_key: public_key,
            new_key: true,
            kakao_id_token: Some(kakao_proof(&nonce)),
            nonce: Some(nonce),
            proof: None,
            device_signature: None,
            apple_authorization_code: None,
        };
        assert!(refused(thief.put_recovery(start_over).await));
        assert_eq!(server.recovery_of(&user).unwrap().1, recovery_id);
        assert!(server.has_account(&user));

        // The phone itself signs, and goes through.
        a.core.clone().delete_account(kakao_deletion(&a.core, "fern").await).await.unwrap();
        assert!(!server.has_account(&user));
    });
}

/// A client holding the phone's session tokens, as a thief or a man in the middle has them.
fn stolen_session(server: &FakeServer, phone: &Phone) -> FakeClient {
    let session: Session = serde_json::from_slice(&phone.secrets.get("session".into()).unwrap().unwrap()).unwrap();
    let thief = server.client();
    thief.restore_session(&session.access_token, &session.refresh_token);
    thief
}

/// The material of `keys` in an upload over `nonce`, signed by nobody yet.
fn upload_of(keys: &RecoveryKeys, amk: &[u8; 32], new_key: bool, nonce: String) -> RecoveryUpload {
    RecoveryUpload {
        amk_wrapped: keys.wrap_amk(amk),
        recovery_id: keys.id().to_vec(),
        recovery_public_key: keys.public_key().to_vec(),
        new_key,
        nonce: Some(nonce),
        proof: None,
        device_signature: None,
        kakao_id_token: None,
        apple_authorization_code: None,
    }
}

/// `upload` as the phone would send it: signed with its device key over the nonce and the material.
fn signed_by(phone: &Phone, upload: RecoveryUpload) -> RecoveryUpload {
    let secret = phone.secrets.get("device_signing_key".into()).unwrap().unwrap();
    let nonce = upload.nonce.as_deref().unwrap();
    let (id, public_key, wrapped) = (&upload.recovery_id, &upload.recovery_public_key, &upload.amk_wrapped);
    let message = recovery_upload_message(upload.new_key, nonce, id, public_key, wrapped);
    RecoveryUpload { device_signature: Some(MlsClient::sign_with(&secret, &message).unwrap()), ..upload }
}

/// `upload` with `other`'s material put in its place, the rest (nonce, proofs, the device's signature) kept.
fn swapped(upload: &RecoveryUpload, other: &RecoveryUpload) -> RecoveryUpload {
    RecoveryUpload {
        amk_wrapped: other.amk_wrapped.clone(),
        recovery_id: other.recovery_id.clone(),
        recovery_public_key: other.recovery_public_key.clone(),
        ..upload.clone()
    }
}

#[test]
fn a_stolen_session_cannot_put_recovery_material_before_the_device() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        // The upload right after the sign-in fails, so the server holds no material: the window this closes.
        server.fail_next("put_recovery");
        let user = a.core.clone().sign_in_guest(new_account("게스트")).await.unwrap().user_id;
        assert!(server.recovery_of(&user).is_none());

        // The thief's own code, put up with the session alone or with a signature of its own making.
        let thief = stolen_session(&server, &a);
        let entropy = [7u8; 16];
        let keys = RecoveryKeys::derive(&entropy).unwrap();
        let refused =
            |result: Result<(), ApiError>| matches!(result, Err(ref e) if e.is(403, "recovery_proof_required"));
        let unsigned = upload_of(&keys, &[5; 32], false, thief.nonce().await.unwrap());
        assert!(refused(thief.put_recovery(unsigned).await));
        let forged = RecoveryUpload {
            device_signature: Some(vec![7; 64]),
            ..upload_of(&keys, &[5; 32], false, thief.nonce().await.unwrap())
        };
        assert!(refused(thief.put_recovery(forged).await));
        assert!(server.recovery_of(&user).is_none());

        // The phone's next sync puts its own material up, and the thief's code brings back nothing.
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&a.core).id());
        let words: Vec<String> = recovery::words(&entropy).iter().map(|w| (*w).to_owned()).collect();
        let b = phone(&server);
        let result = b.core.clone().restore_from_code(words, LocalDataChoice::Keep, None).await;
        assert!(matches!(result, Err(CoreError::WrongRecoveryCode)), "{result:?}");
        assert_eq!(server.active_device(&user), a.core.session().map(|s| s.device_id));
    });
}

#[test]
fn recovery_material_swapped_under_the_devices_signature_is_refused() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        server.fail_next("put_recovery");
        let user = user_id(&kakao(&a.core, "gwen", "그웬").await.unwrap());
        assert!(server.recovery_of(&user).is_none());
        // Someone between the app and the API holds the session and sees each request the phone signs.
        let middle = stolen_session(&server, &a);
        let theirs = RecoveryKeys::derive(&[7u8; 16]).unwrap();
        let mine = keys_of(&a.core);
        let amk: [u8; 32] = a.secrets.get("account_master_key".into()).unwrap().unwrap().try_into().unwrap();

        // The first upload.
        let nonce = middle.nonce().await.unwrap();
        let first = signed_by(&a, upload_of(&mine, &amk, false, nonce.clone()));
        let swap = swapped(&first, &upload_of(&theirs, &[5; 32], false, nonce));
        let result = middle.put_recovery(swap).await;
        assert!(matches!(result, Err(ref e) if e.is(403, "recovery_proof_required")), "{result:?}");
        assert!(server.recovery_of(&user).is_none());
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.recovery_of(&user).unwrap().1, mine.id());
        a.core.clone().backup_now(None).await.unwrap();

        // An overwrite to a new code of the phone's, proven by the current one.
        let next = RecoveryKeys::derive(&[8u8; 16]).unwrap();
        let nonce = middle.nonce().await.unwrap();
        let rotation = RecoveryUpload {
            proof: Some(mine.sign_rotation(&nonce).to_vec()),
            ..signed_by(&a, upload_of(&next, &amk, false, nonce.clone()))
        };
        let swap = swapped(&rotation, &upload_of(&theirs, &[5; 32], false, nonce));
        let result = middle.put_recovery(swap).await;
        assert!(matches!(result, Err(ref e) if e.is(403, "recovery_proof_required")), "{result:?}");

        // "새 키로 시작" within the 30 minutes of the Kakao sign-in.
        let nonce = middle.nonce().await.unwrap();
        let start_over = signed_by(&a, upload_of(&next, &amk, true, nonce.clone()));
        let swap = swapped(&start_over, &upload_of(&theirs, &[5; 32], true, nonce));
        let result = middle.put_recovery(swap).await;
        assert!(matches!(result, Err(ref e) if e.is(403, "reauth_required")), "{result:?}");

        // A signature for an upload that keeps the backups does not count for a new key that deletes them.
        let nonce = middle.nonce().await.unwrap();
        let keep = signed_by(&a, upload_of(&next, &amk, false, nonce));
        let result = middle.put_recovery(RecoveryUpload { new_key: true, ..keep }).await;
        assert!(matches!(result, Err(ref e) if e.is(403, "reauth_required")), "{result:?}");

        // Nothing moved: the phone's material and its backup are where they were.
        assert_eq!(server.recovery_of(&user).unwrap().1, mine.id());
        assert_eq!((server.backups_of(&user).len(), server.backups_cleared(&user)), (1, 0));

        // What was refused was the swap: the phone's own requests, signed over fresh nonces, go through.
        let nonce = middle.nonce().await.unwrap();
        let rotation = RecoveryUpload {
            proof: Some(mine.sign_rotation(&nonce).to_vec()),
            ..signed_by(&a, upload_of(&next, &amk, false, nonce))
        };
        middle.put_recovery(rotation).await.unwrap();
        assert_eq!(server.recovery_of(&user).unwrap().1, next.id());
        let nonce = middle.nonce().await.unwrap();
        middle.put_recovery(signed_by(&a, upload_of(&mine, &amk, true, nonce))).await.unwrap();
        assert_eq!(server.recovery_of(&user).unwrap().1, mine.id());
        assert_eq!((server.backups_of(&user).len(), server.backups_cleared(&user)), (0, 1));
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
fn a_deletion_whose_answer_was_lost_finishes_when_tried_again() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "erin", "에린").await.unwrap());
        a.core.set_alpha_stats(true).unwrap();

        // The server deletes the account, and the answer never comes back.
        server.lose_next("delete_account");
        let lost = a.core.clone().delete_account(kakao_deletion(&a.core, "erin").await).await;
        assert!(matches!(lost, Err(CoreError::Network { .. })), "{lost:?}");
        assert!(!server.has_account(&user));
        assert!(a.core.session().is_some(), "the phone does not know yet");

        // The same tokens now get 401 account_deleted, which finishes the deletion as the 204 would have.
        a.core.clone().delete_account(kakao_deletion(&a.core, "erin").await).await.unwrap();
        assert!(a.core.session().is_none() && a.core.revocation().unwrap().is_none());
        for secret in ["session", "device_signing_key", "recovery_entropy", "account_master_key"] {
            assert!(!a.secrets.has(secret), "{secret} is gone");
        }
        assert!(!a.core.alpha_stats_enabled().unwrap());
    });
}

#[test]
fn finishes_leaving_a_deleted_account_when_a_later_request_answers_account_deleted() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let t0 = chrono::Utc::now();
        server.set_now(t0);
        let (a, b) = (phone(&server), phone(&server));
        for (phone, subject) in [(&a, "finn"), (&b, "gail")] {
            kakao(&phone.core, subject, "핀").await.unwrap();
            let person =
                PersonInput {
                    id: None, name: "엄마".into(), birthday: None, alert: AlertRule::Inherit, suggest: true
                };
            phone.core.save_person(person).unwrap();
            phone.core.set_alpha_stats(true).unwrap();
            // The server deletes the account, and the answer never comes back.
            server.lose_next("delete_account");
            assert!(phone.core.clone().delete_account(kakao_deletion(&phone.core, subject).await).await.is_err());
            assert!(phone.core.session().is_some(), "the phone does not know yet");
        }

        // The next sync meets 401 account_deleted: at once on the access token, and past its 15 minutes on the refresh
        // token, which the transport never trades for new ones (geunal-sync's http tests).
        let deleted =
            |e: &CoreError| matches!(e, CoreError::Api { status: 401, code, .. } if code == "account_deleted");
        let error = a.core.clone().sync().await.unwrap_err();
        assert!(deleted(&error), "{error:?}");
        server.set_now(t0 + chrono::Duration::minutes(20));
        let error = b.core.clone().sync().await.unwrap_err();
        assert!(deleted(&error), "{error:?}");

        // Either phone is left as the 204 would have left it.
        for phone in [&a, &b] {
            assert!(phone.core.session().is_none() && phone.core.revocation().unwrap().is_none());
            for secret in ["session", "device_signing_key", "recovery_entropy", "account_master_key"] {
                assert!(!phone.secrets.has(secret), "{secret} is gone");
            }
            assert!(phone.secrets.has("db_key"), "the calendar still opens");
            assert_eq!(phone.core.people().unwrap().len(), 1);
            assert!(!phone.core.alpha_stats_enabled().unwrap());
            phone.core.clone().sync().await.unwrap();
        }
    });
}

#[test]
fn a_deleted_accounts_tokens_read_as_expired_once_they_would_have_run_out() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let t0 = chrono::Utc::now();
        server.set_now(t0);
        let a = phone(&server);
        kakao(&a.core, "hugo", "휴고").await.unwrap();
        // Signed in at t0, the account is deleted ten minutes later and the answer is lost.
        server.set_now(t0 + chrono::Duration::minutes(10));
        server.lose_next("delete_account");
        assert!(a.core.clone().delete_account(kakao_deletion(&a.core, "hugo").await).await.is_err());

        // The refresh token handed out at t0 ends 30 days on, whenever the deletion came. Past that the server cannot
        // tell any more, and a session that merely ended is no deletion: the session goes, the account's keys stay.
        server.set_now(t0 + chrono::Duration::days(30) + chrono::Duration::minutes(5));
        let error = a.core.clone().sync().await.unwrap_err();
        assert!(matches!(error, CoreError::SignInAgain), "{error:?}");
        assert!(a.core.session().is_none() && a.secrets.has("device_signing_key") && a.secrets.has("recovery_entropy"));
    });
}

#[test]
fn ends_only_the_session_when_the_server_refuses_the_refresh_token() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let t0 = chrono::Utc::now();
        server.set_now(t0);
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "ivan", "이반").await.unwrap());
        let person =
            PersonInput { id: None, name: "엄마".into(), birthday: None, alert: AlertRule::Inherit, suggest: true };
        a.core.save_person(person).unwrap();
        let words = a.core.recovery_words().unwrap();

        // Thirty days without a connection: the refresh token handed out at the sign-in has run out.
        server.set_now(t0 + chrono::Duration::days(30) + chrono::Duration::minutes(1));
        let error = a.core.clone().sync().await.unwrap_err();
        assert!(matches!(error, CoreError::SignInAgain), "{error:?}");
        assert!(a.core.session().is_none() && !a.secrets.has("session"));
        a.core.clone().sync().await.unwrap();
        assert!(a.core.session().is_none(), "a signed-out sync tries no refresh again");
        // Nobody else signed this device out, and what the account keeps here stays for the next sign-in.
        assert!(a.core.revocation().unwrap().is_none());
        for secret in ["device_signing_key", "recovery_entropy", "account_master_key", "db_key"] {
            assert!(a.secrets.has(secret), "{secret} stays");
        }
        assert_eq!(a.core.people().unwrap().len(), 1);

        // Signing in again on the same phone picks the account up with the same code, without a restore.
        let again = kakao(&a.core, "ivan", "이반").await.unwrap();
        assert!(matches!(again, AuthResult::Registered { new_account: false, .. }), "{again:?}");
        assert_eq!(user_id(&again), user);
        assert_eq!(a.core.recovery_words().unwrap(), words);
        a.core.clone().sync().await.unwrap();
    });
}

#[test]
fn ends_the_session_when_reuse_detection_refuses_the_refresh_token() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        kakao(&a.core, "jade", "제이드").await.unwrap();
        // Someone holding a copy of the session refreshes first; the phone's own refresh then presents a spent token,
        // and the server ends every session of the device.
        let session: Session = serde_json::from_slice(&a.secrets.get("session".into()).unwrap().unwrap()).unwrap();
        let copy = server.client();
        copy.restore_session(&session.access_token, &session.refresh_token);
        server.expire_access_tokens();
        copy.me().await.unwrap();
        let error = a.core.clone().sync().await.unwrap_err();
        assert!(matches!(error, CoreError::SignInAgain), "{error:?}");
        assert!(a.core.session().is_none() && a.secrets.has("device_signing_key"));
        assert!(copy.me().await.is_err(), "the copy's session ended too");
    });
}

#[test]
fn a_code_the_store_cannot_read_is_never_replaced_by_a_new_one() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        // The upload drops, so the code lives on this phone alone: the one the person may already have written down.
        server.fail_next("put_recovery");
        let session = a.core.clone().sign_in_guest(new_account("게스트")).await.unwrap();
        let words = a.core.recovery_words().unwrap();

        // The server still lacks material, which is when a device without a code makes one (protocol §10 "올리는 때").
        a.secrets.fail("recovery_entropy", Fault::Read);
        assert!(matches!(a.core.clone().sync().await, Err(CoreError::SecretStore { .. })));
        assert!(matches!(a.core.recovery_words(), Err(CoreError::SecretStore { .. })));

        a.secrets.heal();
        assert_eq!(a.core.recovery_words().unwrap(), words);
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.recovery_of(&session.user_id).unwrap().1, keys_of(&a.core).id());
    });
}

#[test]
fn a_device_key_the_store_cannot_read_fails_the_sign_in_and_stays() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "ivy", "아이비").await.unwrap());
        let device = server.active_device(&user);
        let key = a.secrets.value("device_signing_key");

        a.secrets.fail("device_signing_key", Fault::Read);
        assert!(matches!(kakao(&a.core, "ivy", "아이비").await, Err(CoreError::SecretStore { .. })));
        assert_eq!(a.secrets.value("device_signing_key"), key);

        // The same key signs the next sign-in, so the server still knows this phone, rather than asking to replace it.
        a.secrets.heal();
        let again = kakao(&a.core, "ivy", "아이비").await.unwrap();
        assert!(matches!(again, AuthResult::Registered { new_account: false, .. }), "{again:?}");
        assert_eq!(server.active_device(&user), device);
    });
}

#[test]
fn a_code_the_store_half_wrote_is_not_shown_and_the_next_sync_makes_a_whole_one() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        a.secrets.fail("account_master_key", Fault::Write);
        // The phone is registered by then, but the code it made never became whole.
        assert!(matches!(kakao(&a.core, "june", "준").await, Err(CoreError::SecretStore { .. })));
        let user = a.core.session().unwrap().user_id;
        assert!(a.secrets.has("recovery_entropy"));
        assert!(!a.core.recovery_status().unwrap().created);
        assert!(matches!(a.core.recovery_words(), Err(CoreError::NotFound { .. })));
        // A sync while the store still fails tries and fails the same way, and leaves the next one to try again.
        assert!(matches!(a.core.clone().sync().await, Err(CoreError::SecretStore { .. })));

        a.secrets.heal();
        a.core.clone().sync().await.unwrap();
        assert!(a.core.recovery_status().unwrap().uploaded);
        assert_eq!(server.recovery_of(&user).unwrap().1, keys_of(&a.core).id());
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
            a.secrets.delete(secret.to_owned()).unwrap();
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
            secrets.delete(secret.to_owned()).unwrap();
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
        let before = secrets.value("session").unwrap();

        server.expire_access_tokens();
        core.clone().sync().await.unwrap();
        let after = secrets.value("session").unwrap();
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

#[test]
fn keeps_e_and_the_amk_in_the_secret_store_and_never_in_the_database() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        kakao(&a.core, "ruth", "루스").await.unwrap();
        a.core.clone().backup_now(None).await.unwrap();
        a.core.mark_recovery_saved().unwrap();
        let entropy = a.secrets.get("recovery_entropy".into()).unwrap().unwrap();
        let amk = a.secrets.get("account_master_key".into()).unwrap().unwrap();
        assert_eq!((entropy.len(), amk.len()), (16, 32));
        let words = a.core.recovery_words().unwrap().join(" ");
        // What the code derives must stay off the database too: K (Argon2id), and from it the wrap key and the recovery
        // signing seed. Any of them opens the backups or speaks for the account.
        let root = geunal_crypto::kdf::recovery_root(&entropy).unwrap();
        let derived = RecoveryKeys::from_root(&root);
        let seed: [u8; 32] = geunal_crypto::kdf::expand(&root, b"geunal recovery sign v1");
        // The seed is the one the code really signs with: a signature it makes checks against the code's public key.
        let pair = [seed.as_slice(), derived.public_key().as_slice()].concat();
        let signed = MlsClient::sign_with(&pair, b"probe").unwrap();
        assert!(MlsClient::verify(&derived.public_key(), b"probe", &signed).unwrap());
        let (root, wrap_key) = (root.to_vec(), derived.wrap_key().to_vec());

        // Every value of every table, read through the database key, in each form a secret could be written in.
        let key: [u8; 32] = a.secrets.get("db_key".into()).unwrap().unwrap().try_into().unwrap();
        let store = geunal_store::Store::open(&a._dir.path().join(geunal_store::DB_FILE), &key).unwrap();
        let db = store.connection();
        let tables: Vec<String> = db
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
            .unwrap()
            .query_map([], |row| row.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        assert!(tables.len() > 5, "{tables:?}");
        let mut values = 0;
        for table in tables {
            let mut select = db.prepare(&format!("SELECT * FROM \"{table}\"")).unwrap();
            let columns = select.column_count();
            let mut rows = select.query([]).unwrap();
            while let Some(row) = rows.next().unwrap() {
                for i in 0..columns {
                    let bytes = match row.get_ref(i).unwrap() {
                        rusqlite::types::ValueRef::Blob(b) | rusqlite::types::ValueRef::Text(b) => b.to_vec(),
                        _ => continue,
                    };
                    values += 1;
                    let text = String::from_utf8_lossy(&bytes).to_lowercase();
                    for secret in [&entropy, &amk, &root, &wrap_key, &seed.to_vec()] {
                        assert!(!bytes.windows(secret.len()).any(|w| w == secret.as_slice()), "{table}: raw bytes");
                        assert!(!text.contains(&hex::encode(secret)), "{table}: hex");
                        for b64 in [
                            base64::engine::general_purpose::STANDARD,
                            base64::engine::general_purpose::URL_SAFE_NO_PAD,
                        ] {
                            let encoded = base64::Engine::encode(&b64, secret).to_lowercase();
                            assert!(!text.contains(encoded.trim_end_matches('=')), "{table}: base64");
                        }
                    }
                    assert!(!text.contains(&words), "{table}: the words");
                }
            }
        }
        assert!(values > 0);
    });
}

#[test]
fn an_account_linked_to_kakao_or_apple_cannot_come_back_with_the_twelve_words_alone() {
    pollster::block_on(async {
        let server = FakeServer::new();
        // A Kakao account, and a guest that linked Apple later.
        let a = phone(&server);
        let kakao_user = user_id(&kakao(&a.core, "sam", "샘").await.unwrap());
        let g = phone(&server);
        let guest_user = g.core.clone().sign_in_guest(new_account("게스트")).await.unwrap().user_id;
        let nonce = g.core.clone().sign_in_nonce().await.unwrap();
        let id_token = FakeServer::id_token("apple", "sam-apple", &nonce.provider_nonce);
        assert_eq!(g.core.clone().link_apple(nonce.nonce, id_token).await.unwrap(), vec![Provider::Apple]);

        for (owner, user) in [(&a, kakao_user), (&g, guest_user)] {
            let words = owner.core.recovery_words().unwrap();
            let b = phone(&server);
            let result = b.core.clone().restore_from_code(words, LocalDataChoice::Keep, None).await;
            assert!(matches!(result, Err(CoreError::WrongRecoveryCode)), "{result:?}");
            assert!(b.core.session().is_none());
            assert_eq!(server.active_device(&user), owner.core.session().map(|s| s.device_id), "nobody was signed out");
            owner.core.clone().sync().await.unwrap();
        }
    });
}

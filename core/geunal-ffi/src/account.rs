//! The M1 account surface (docs/design/m1.md screens 2, 12, 13, 20–22; protocol §10, §13, §14): signing in with
//! Kakao, Apple or as a guest, replacing a device, the recovery code, encrypted backups, account deletion and the
//! alpha statistics.
//!
//! The secrets this needs (the recovery code's entropy `E`, the account master key, the device signing key) live in
//! the app's `SecretStore` only. A replacement ticket lives in memory: it is good for 30 minutes and a restarted app
//! signs in again.

use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant, SystemTime};

use chrono::{DateTime, Utc};
use geunal_crypto::recovery::{self, CodeError, Entropy, RecoveryKeys};
use geunal_crypto::{backup, login};
use geunal_mls::MlsClient;
use geunal_store::archive::{self, Restored};
use geunal_store::{BackupRecordRow, Counted, Db};
use geunal_sync::api::{
    AlphaDay, AuthOutcome, DeleteAccount, DeviceProof, GuestSignIn, LinkRequest, NewAccountFields, ProviderSignIn,
    RecoverSignIn, RecoveryUpload, ReplaceRequest, Replaced,
};
use geunal_sync::{ApiError, Transport as _};
use serde::{Deserialize, Serialize};
use unicode_segmentation::UnicodeSegmentation;

use crate::calendar::ensure_default_calendar;
use crate::local::{changes, day_text, kst_day, parse_stored, stored_utc, system_time};
use crate::{
    ACCOUNT_DELETED, Change, Core, CoreError, KEY_PACKAGES, SESSION, SIGNING_KEY, STORE_RESET, Session, State,
    account_deleted_error, build_engine, revoked_error, signing_secret,
};

// The code's entropy and the master key live in the app's secret store; what follows the code on this device lives in
// its settings, never in the backup.
const ENTROPY: &str = "recovery_entropy";
const AMK: &str = "account_master_key";
/// What leaves with the account: the secrets kept for it, as opposed to the database key, which stays with the store.
pub(crate) const ACCOUNT_SECRETS: [&str; 4] = [SESSION, SIGNING_KEY, ENTROPY, AMK];
const ACCOUNT: &str = "account";
const RECOVERY_SAVED: &str = "recovery_saved";
const RECOVERY_UPLOADED: &str = "recovery_uploaded";
/// The code waiting for upload replaces one nobody can prove ("백업 없이 새로 시작"), so every retry sends `new_key`.
const RECOVERY_NEW_KEY: &str = "recovery_new_key";
/// The account the code on this device belongs to: a sign-in into another account makes a new one.
const RECOVERY_OWNER: &str = "recovery_owner";
const REVOCATION: &str = "revocation";
/// Whose calendar this store holds (protocol §10 "복원" 4). Unlike the account settings it stays through a sign-out,
/// so the next account to sign in is asked before another account's events become its own.
const STORE_OWNER: &str = "store_owner";
const AUTO_BACKUP: &str = "auto_backup";
/// The change count the last backup of this device carried, against `changes` for the automatic backup.
const BACKED_UP_CHANGES: &str = "backed_up_changes";
/// Key packages a sign-in has yet to put up. Set before the upload, so an app closed in the middle puts them up on its
/// next `sync()`.
const KEY_PACKAGES_DUE: &str = "key_packages_due";
/// The account as stored is the sign-in's guess, as `GET /me` did not answer then; `sync()` asks again.
const ACCOUNT_STALE: &str = "account_stale";
/// The Korean day the tester agreed to send the alpha counts; days before it never go. Absent while they are off.
const ALPHA_SINCE: &str = "alpha_stats_since";
/// The Korean day the counts last went to the server, which takes them once a day.
const ALPHA_SENT_ON: &str = "alpha_stats_sent_on";
/// The alpha counts cover the last 14 Korean days, today included (protocol §14).
const ALPHA_DAYS: i64 = 14;
/// The platform a sign-in names.
const PLATFORM: &str = if cfg!(target_os = "ios") { "ios" } else { "android" };
const KAKAO: &str = "kakao";
const APPLE: &str = "apple";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Provider {
    Kakao,
    Apple,
}

/// A one-time nonce and the value a provider sign-in is bound to (protocol §13 "nonce 묶기"). `provider_nonce` is
/// `hex(SHA-256("geunal login v1" ‖ nonce ‖ this device's signature key))`; the app hands it to the Kakao or Apple
/// SDK as the nonce, for both providers alike, and passes `nonce` back to `sign_in_kakao`, `sign_in_apple`, `link_*`
/// or `DeletionProof::Kakao`. A provider token is then good only together with this device's key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SignInNonce {
    pub nonce: String,
    pub provider_nonce: String,
}

/// What an account is made with. The server reads it only when the sign-in creates the account.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NewAccount {
    /// Trimmed. Empty takes the default ("카카오 사용자", "Apple 사용자", "게스트"), and a longer name is cut at a
    /// character (grapheme) boundary to the server's 40.
    pub display_name: String,
    /// When the user ticked "만 14세 이상이에요". Only the time is kept, never a birth date (kickoff §6).
    pub age_confirmed_at: SystemTime,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AuthResult {
    /// This device is the account's active device now. `recovery` says the server holds recovery material: with
    /// `new_account` false the app offers to restore (`restore_from_code`).
    Registered { session: Session, new_account: bool, recovery: bool },
    /// Another device is active and nothing is registered yet. The core keeps the replacement ticket; next comes
    /// `restore_from_code` or `start_fresh`, and either of them makes this the active device and signs the other
    /// one out. `active_platform` is "ios" or "android".
    ReplaceRequired { user_id: String, active_platform: String, active_since: SystemTime, recovery: bool },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AccountView {
    pub user_id: String,
    pub display_name: String,
    /// Empty for a guest, who comes back on another device by recovery code only.
    pub providers: Vec<Provider>,
}

/// The values of the "나" screen (screen 12) in one call.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MeSummary {
    /// `None` before a sign-in: the calendar works without an account.
    pub account: Option<AccountView>,
    pub calendars: u32,
    pub shift_templates: u32,
    pub people: u32,
    /// Device calendars being mirrored; 0 when the mirror is off.
    pub device_calendars: u32,
    pub recovery_saved: bool,
    pub last_backup_at: Option<SystemTime>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RecoveryStatus {
    /// The code exists on this device: made at the sign-in that found no recovery material on the server, or entered
    /// when restoring.
    pub created: bool,
    /// The user pressed "종이에 적었어요". Kept on the device only (protocol §10).
    pub saved: bool,
    /// The server holds the recovery material; backups wait for this.
    pub uploaded: bool,
}

/// What the device can tell about the words without the server (screen 21's error states, protocol §10).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RecoveryCodeCheck {
    /// Every word is in the list and the checksum matches. Whose code it is shows only when it unwraps the master
    /// key: one wrong code in sixteen passes the checksum.
    Valid,
    WrongWordCount {
        count: u32,
    },
    /// The first word outside the list, counted from 0.
    UnknownWord {
        index: u32,
    },
    /// All twelve are in the list but the checksum does not match: a word differs or two swapped places.
    Checksum,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupView {
    pub id: i64,
    pub created_at: SystemTime,
    /// Bytes of the sealed object.
    pub size: u64,
    /// What this device remembers of its own backups; `None` for one it did not make (protocol §10: the server keeps
    /// only id, size and time).
    pub automatic: Option<bool>,
    pub events: Option<u32>,
    pub people: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupStatus {
    pub last: Option<BackupView>,
    pub auto: bool,
    /// Something changed since the last backup; the automatic backup skips a day without changes. With no backup of
    /// this store on record (a new account, a restored phone, after starting over or a sign-in that kept another
    /// account's events), everything in it counts as changed.
    pub changed: bool,
    /// The recovery material is on the server, so a backup can be made and opened later.
    pub ready: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RestoreSummary {
    pub events: u32,
    pub people: u32,
    pub calendars: u32,
    pub shift_templates: u32,
    /// When the restored backup was made; `None` when the account had none and only the code came back.
    pub backup_at: Option<SystemTime>,
    /// Another device was signed out ("이전 기기는 로그아웃됐어요").
    pub replaced_device: bool,
    /// Groups this device is joining again by external commit (M2).
    pub groups: u32,
}

/// Own events and people on this device that may not be the restored account's (protocol §10 "복원" 4).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LocalData {
    pub events: u32,
    pub people: u32,
}

/// What a restore does with [`LocalData`] when the store turns out not to be the restored account's. When it is the
/// same account, the restore merges whatever the choice: the backup wins for the same ids and local-only items stay.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LocalDataChoice {
    /// "가져오기": keep it, now under the restored account. (Not `Import`: that is a keyword in Swift.)
    Keep,
    /// "지우기": delete it before the backup is opened.
    Discard,
}

/// The proof `DELETE /me` asks for, by account kind (protocol §13 "계정 삭제"). Deleting is the one thing that cannot
/// be undone, so a stolen session alone must not do it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum DeletionProof {
    /// A guest, or an account whose provider link was cut: the core signs with the recovery key derived from this
    /// device's code, without asking the user.
    Guest,
    /// An ID token from a Kakao sign-in made just now with the `provider_nonce` of `nonce` (`sign_in_nonce`).
    Kakao { nonce: String, id_token: String },
    /// A fresh Sign in with Apple authorization code. On Android it is the `code` of the web sign-in callback.
    Apple { authorization_code: String },
}

/// How this device came to be signed out by another one (protocol §13 "기기 해제"), for the "기기 로그아웃됨"
/// screen: when, on which platform, and by what. `Recovery` tells a guest that their recovery code was used.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Revocation {
    pub at: SystemTime,
    /// "ios" or "android".
    pub by_platform: String,
    pub via: RevokedVia,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RevokedVia {
    /// A Kakao or Apple sign-in.
    Provider,
    /// A guest comeback with the recovery code.
    Recovery,
}

/// Progress of a backup or a restore, in bytes. Called on a core thread; the app hops to its main thread itself.
#[uniffi::export(with_foreign)]
pub trait ProgressListener: Send + Sync {
    fn on_progress(&self, done: u64, total: u64);
}

#[uniffi::export]
impl Core {
    // ----- sign-in (screens 2, 21; protocol §13) -----

    /// Fetches a one-time nonce (`GET /auth/nonce`) for a Kakao or Apple sign-in or link.
    pub async fn sign_in_nonce(self: Arc<Self>) -> Result<SignInNonce, CoreError> {
        let this = self.clone();
        self.run(async move {
            let nonce = this.api.nonce().await?;
            let key = MlsClient::public_key_of(&signing_secret(this.secrets.as_ref())?)?;
            let provider_nonce = login::provider_nonce(&nonce, &key);
            Ok(SignInNonce { nonce, provider_nonce })
        })
        .await
    }

    /// Signs in with a Kakao OIDC ID token issued for `nonce`.
    pub async fn sign_in_kakao(
        self: Arc<Self>,
        nonce: String,
        id_token: String,
        account: NewAccount,
    ) -> Result<AuthResult, CoreError> {
        self.sign_in_provider(KAKAO, nonce, id_token, account).await
    }

    /// Signs in with an Apple identity token issued for the `provider_nonce` of `nonce`. The server keeps no Apple
    /// token; deleting the account brings a fresh authorization code instead (`DeletionProof::Apple`).
    pub async fn sign_in_apple(
        self: Arc<Self>,
        nonce: String,
        id_token: String,
        account: NewAccount,
    ) -> Result<AuthResult, CoreError> {
        self.sign_in_provider(APPLE, nonce, id_token, account).await
    }

    /// Creates a guest account and registers this device. A guest always gets a new account.
    pub async fn sign_in_guest(self: Arc<Self>, account: NewAccount) -> Result<Session, CoreError> {
        let display_name = display_name(&account.display_name, GUEST_NAME);
        let this = self.clone();
        self.run(async move {
            let proof = this.proof_for(this.api.nonce().await?)?;
            let request = GuestSignIn {
                proof,
                age_confirmed_at: stored_utc(DateTime::<Utc>::from(account.age_confirmed_at)),
                display_name: display_name.clone(),
            };
            let outcome = this.api.sign_in_guest(request).await?;
            match this.finish_sign_in(outcome, Some(display_name)).await? {
                AuthResult::Registered { session, .. } => Ok(session),
                AuthResult::ReplaceRequired { .. } => Err(malformed("a guest sign-in asked for a replacement")),
            }
        })
        .await
    }

    /// Own events and people on this device that may belong to someone else, so the app can ask before a restore
    /// whether to bring them along (`LocalDataChoice`): the store is another account's, or it has an owner and the
    /// account is not known before the code is checked (a guest coming back). `None` when there is nothing to ask: the
    /// store is the signing-in account's own, or nobody owns it yet (used before any sign-in), which makes it the
    /// first account's to register.
    pub fn local_data_of_other_account(&self) -> Result<Option<LocalData>, CoreError> {
        // The account to compare with: the signed-in one, or the one a replacement is for. Before a guest's code is
        // checked there is none, and then a store with an owner is someone else's as far as this question goes.
        let account = match self.current_session()? {
            Some(session) => Some(session.user_id),
            None => self.pending_ticket().ok().flatten().map(|p| p.user_id),
        };
        self.store.read(|db| {
            let Some(owner) = db.setting(STORE_OWNER)? else { return Ok(None) };
            if account.as_deref() == Some(owner.as_str()) {
                return Ok(None);
            }
            let own = db.own_data()?;
            Ok((own.events > 0 || own.people > 0).then_some(LocalData { events: own.events, people: own.people }))
        })
    }

    /// The answer to `local_data_of_other_account` after a sign-in that restores nothing: a new account, one without a
    /// backup, or a fresh start (screen 24). `Keep` makes the events this account's; `Discard` deletes them first.
    /// Until it is answered no backup is made, so another account's events never reach this account's backups.
    pub fn settle_local_data(&self, choice: LocalDataChoice) -> Result<(), CoreError> {
        let session = self.current_session()?.ok_or(CoreError::NotSignedIn)?;
        self.store.write(|db| {
            if !db.setting(STORE_OWNER)?.is_none_or(|owner| owner == session.user_id) {
                if choice == LocalDataChoice::Discard {
                    db.discard_own_data()?;
                }
                // Notes of the other account's backups.
                forget_backups(db)?;
            }
            Ok::<_, CoreError>(db.set_setting(STORE_OWNER, &session.user_id)?)
        })?;
        ensure_default_calendar(&self.store)?;
        self.calendar_changed();
        self.listener.on_change(Change::Account);
        Ok(())
    }

    /// Restores with the recovery code: unwraps the account master key, downloads and opens the newest backup and
    /// fills this device, then makes it the active device. It covers the three ways a device gets here: a
    /// `ReplaceRequired` sign-in (with its ticket), a `Registered` sign-in with `recovery`, and a guest coming back
    /// without any sign-in (`POST /auth/recover`, protocol §13). The store is marked as the account's own; local data
    /// of another account is kept or deleted as `local_data` says, and the store of the same account merges. Fails
    /// with `WrongRecoveryCode` when the words are not this account's code.
    pub async fn restore_from_code(
        self: Arc<Self>,
        words: Vec<String>,
        local_data: LocalDataChoice,
        progress: Option<Arc<dyn ProgressListener>>,
    ) -> Result<RestoreSummary, CoreError> {
        // Checked on the device first: a word outside the list or a wrong checksum never reaches Argon2id or the
        // server.
        let entropy = recovery::entropy(&words).map_err(|_| CoreError::WrongRecoveryCode)?;
        let this = self.clone();
        self.run(async move {
            let report = |done: u64, total: u64| {
                if let Some(listener) = &progress {
                    listener.on_progress(done, total);
                }
            };
            let keys = this.keys_of(entropy).await?;
            let pending = this.pending_ticket()?;
            let (user_id, ticket, recovered) = match (&pending, this.current_session()?) {
                (Some(p), _) => (p.user_id.clone(), Some(p.ticket.clone()), false),
                (None, Some(session)) => (session.user_id, None, false),
                (None, None) => (this.recover_guest(&keys).await?, None, true),
            };
            let read = |e: CoreError| match e {
                CoreError::Api { status: 401, ref code, .. } if ticket.is_some() && code == "token_expired" => {
                    CoreError::SignInAgain
                }
                e => e,
            };
            // The master key says whose code this is. Nothing on the device has changed yet.
            let stored = this.checked(this.api.get_recovery(ticket.as_deref()).await).await.map_err(read)?;
            let amk = keys.unwrap_amk(&stored.amk_wrapped).ok_or(CoreError::WrongRecoveryCode)?;
            // The newest backup, downloaded and opened before anything local is touched.
            let latest = this.checked(this.api.latest_backup(ticket.as_deref()).await).await.map_err(read)?;
            let archive = match &latest {
                Some(download) => {
                    report(0, download.backup.size);
                    let object = this.api.get_object(&download.download_url, &report).await?;
                    let cbor = backup::open(&amk, &object).map_err(|_| malformed("the backup does not open"))?;
                    Some(archive::decode(&cbor).map_err(malformed)?)
                }
                None => None,
            };
            // One write: another account's events go only now, with the code proven and the backup open, and a
            // failure leaves the store as it was. The same account's store merges whatever `local_data` says.
            let restored = this.store.write(|db| {
                if !db.setting(STORE_OWNER)?.is_none_or(|owner| owner == user_id) {
                    if local_data == LocalDataChoice::Discard {
                        db.discard_own_data()?;
                    }
                    forget_backups(db)?;
                }
                let restored = match &archive {
                    Some(archive) => db.restore_archive(archive)?,
                    None => Restored::default(),
                };
                db.set_setting(STORE_OWNER, &user_id)?;
                Ok::<_, CoreError>(restored)
            })?;
            ensure_default_calendar(&this.store)?;
            this.calendar_changed();
            // A replacement makes this the active device now; a guest's `/auth/recover` already did.
            let replaced_device = match pending {
                Some(pending) => {
                    this.replace_device(pending).await?;
                    true
                }
                None => recovered,
            };
            this.keep_recovery(&user_id, &entropy, &amk, true, false)?;
            // The user has just typed the code from where they kept it, so the "write it down" banner has done its job.
            this.store.write(|db| Ok::<_, CoreError>(db.set_setting(RECOVERY_SAVED, "1")?))?;
            this.listener.on_change(Change::Account);
            let backup_at = archive.as_ref().map(|a| parse_stored(&a.made_at).map(system_time)).transpose()?;
            Ok(RestoreSummary {
                events: restored.events,
                people: restored.people,
                calendars: restored.calendars,
                shift_templates: restored.shift_templates,
                backup_at,
                replaced_device,
                groups: 0,
            })
        })
        .await
    }

    /// "백업 없이 새로 시작" for a Kakao or Apple account whose code is lost: a new code and master key are made, and
    /// the account's backups are deleted on the server because nobody can open them any more (protocol §10). It
    /// finishes a replacement (`ReplaceRequired`, with its ticket), and works as well on a device that is registered
    /// already but has no code yet (`Registered` with `recovery`, after "나중에 넣을게요").
    ///
    /// Within 30 minutes of the provider sign-in no `proof` is needed. After that the server answers 403
    /// `reauth_required`, which comes back as `ReauthRequired`: the app gets a fresh Kakao or Apple proof, the same as
    /// for deleting the account (`DeletionProof::Kakao` with a `sign_in_nonce`, or `DeletionProof::Apple`), and calls
    /// this again with it. A guest has no such way back; it starts a new guest account instead.
    ///
    /// A device that holds the account's code gets `Invalid`: nothing is lost, and a new key would only make the
    /// backups unreadable. A new code an earlier try left waiting goes up again instead of another one.
    pub async fn start_fresh(self: Arc<Self>, proof: Option<DeletionProof>) -> Result<Session, CoreError> {
        if proof == Some(DeletionProof::Guest) {
            return Err(CoreError::Invalid { reason: "a guest starts over with a new guest account".into() });
        }
        let this = self.clone();
        self.run(async move {
            let pending = this.pending_ticket()?;
            let user_id = match (&pending, this.current_session()?) {
                (Some(pending), _) => pending.user_id.clone(),
                (None, Some(session)) => session.user_id,
                (None, None) => return Err(CoreError::NotSignedIn),
            };
            let waiting = this.store.read(|db| db.setting(RECOVERY_NEW_KEY))?.is_some();
            if !waiting && this.recovery_secrets_of(&user_id)?.is_some() {
                return Err(CoreError::Invalid { reason: "this device holds the account's recovery code".into() });
            }
            let session = match pending {
                Some(pending) => this.replace_device(pending).await?,
                None => this.current_session()?.ok_or(CoreError::NotSignedIn)?,
            };
            this.make_recovery(&session.user_id, true, proof.as_ref()).await?;
            // The server deleted every backup; so go the notes about them.
            this.store.write(forget_backups)?;
            Ok(session)
        })
        .await
    }

    /// Links a guest account to Kakao; afterwards the account no longer comes back by recovery code alone.
    pub async fn link_kakao(self: Arc<Self>, nonce: String, id_token: String) -> Result<Vec<Provider>, CoreError> {
        self.link(KAKAO, nonce, id_token).await
    }

    pub async fn link_apple(self: Arc<Self>, nonce: String, id_token: String) -> Result<Vec<Provider>, CoreError> {
        self.link(APPLE, nonce, id_token).await
    }

    /// The signed-in account as this device knows it; `None` before a sign-in.
    pub fn account(&self) -> Result<Option<AccountView>, CoreError> {
        if self.current_session()?.is_none() {
            return Ok(None);
        }
        Ok(self.stored_account()?.map(|a| AccountView {
            user_id: a.user_id,
            display_name: a.display_name,
            providers: a.providers.iter().filter_map(|p| provider_of(p)).collect(),
        }))
    }

    pub fn me_summary(&self) -> Result<MeSummary, CoreError> {
        let account = self.account()?;
        // "종이에 적었어요" belongs to a code: one this device holds for another account says nothing about this one.
        let has_code = self.recovery_secrets()?.is_some();
        let count = |n: i64| u32::try_from(n).unwrap_or(0);
        self.store.read(|db| {
            let last_backup_at = match db.backup_records()?.first() {
                Some(record) => Some(system_time(parse_stored(&record.created_at)?)),
                None => None,
            };
            Ok(MeSummary {
                account,
                calendars: count(db.count(Counted::OwnCalendars)?),
                shift_templates: count(db.count(Counted::ShiftTemplates)?),
                people: count(db.count(Counted::People)?),
                device_calendars: count(db.count(Counted::DeviceCalendars)?),
                recovery_saved: has_code && db.setting(RECOVERY_SAVED)?.is_some(),
                last_backup_at,
            })
        })
    }

    /// Deletes the account on the server (`DELETE /me` with `proof`), then the session, the device signing key, the
    /// master key and the code's entropy, and the group state on this device. Events, calendars and people stay, and
    /// the app keeps working as a calendar without groups (m1.md 2.5). The alpha statistics go off: a new account
    /// agrees again. `ReauthRequired` leaves everything as it was and the session valid: ask for the proof again.
    ///
    /// A guest's recovery material that never reached the server (409 `recovery_missing`) goes up first, and the
    /// deletion is tried once more (m1.md screen 22); `RecoveryMissing` when it still cannot be. A device with no code
    /// to sign with is `Invalid` for `DeletionProof::Guest`.
    ///
    /// A try whose answer was lost leaves the device as it was. The next call with the deleted account's tokens gets
    /// 401 `account_deleted` and finishes the deletion: this one returns `Ok`, any other its error.
    pub async fn delete_account(self: Arc<Self>, proof: DeletionProof) -> Result<(), CoreError> {
        let this = self.clone();
        self.run(async move {
            this.current_session()?.ok_or(CoreError::NotSignedIn)?;
            let mut uploaded = false;
            loop {
                let req = this.deletion_request(&proof).await?;
                match this.checked(this.api.delete_account(req).await).await {
                    Ok(()) => break,
                    // An earlier try deleted the account and its answer was lost; `checked` has finished the deletion.
                    Err(CoreError::Api { status: 401, code, .. }) if code == ACCOUNT_DELETED => return Ok(()),
                    Err(CoreError::Api { status: 403, code, .. }) if code == "reauth_required" => {
                        return Err(CoreError::ReauthRequired);
                    }
                    Err(CoreError::Api { status: 409, code, .. }) if code == "recovery_missing" => {
                        if uploaded || this.recovery_secrets()?.is_none() {
                            return Err(CoreError::RecoveryMissing);
                        }
                        uploaded = true;
                        this.upload_recovery(false, None).await?;
                    }
                    Err(e) => return Err(e),
                }
            }
            this.account_deleted().await
        })
        .await
    }

    /// Set when another device's sign-in signed this one out (403 `device_revoked` with `replaced_at`, protocol §13),
    /// until the next sign-in. The core then keeps events, people and the database key and wipes the rest: the
    /// session, the device signing key, the code's entropy, the master key and the group state.
    pub fn revocation(&self) -> Result<Option<Revocation>, CoreError> {
        let Some(text) = self.store.read(|db| db.setting(REVOCATION))? else { return Ok(None) };
        let stored: StoredRevocation = serde_json::from_str(&text).map_err(malformed)?;
        Ok(Some(Revocation {
            at: system_time(parse_stored(&stored.at)?),
            by_platform: stored.by,
            via: if stored.via == "recovery" { RevokedVia::Recovery } else { RevokedVia::Provider },
        }))
    }

    // ----- recovery code (screens 13, 21; protocol §10) -----

    /// The twelve words of this account's recovery code, in Hangul (NFC). This only reads the code: it is made and
    /// uploaded at the sign-in that finds no recovery material on the server, before any backup (protocol §10
    /// "올리는 때"). `NotFound` before that.
    pub fn recovery_words(&self) -> Result<Vec<String>, CoreError> {
        let (entropy, _) =
            self.recovery_secrets()?.ok_or_else(|| CoreError::NotFound { what: "recovery code".into() })?;
        Ok(recovery::words(&entropy).iter().map(|w| (*w).to_owned()).collect())
    }

    pub fn recovery_status(&self) -> Result<RecoveryStatus, CoreError> {
        let created = self.recovery_secrets()?.is_some();
        self.store.read(|db| {
            Ok(RecoveryStatus {
                created,
                saved: created && db.setting(RECOVERY_SAVED)?.is_some(),
                uploaded: created && db.setting(RECOVERY_UPLOADED)?.is_some(),
            })
        })
    }

    /// "종이에 적었어요": the banner and the "저장 안 함" label go away.
    pub fn mark_recovery_saved(&self) -> Result<(), CoreError> {
        if self.recovery_secrets()?.is_none() {
            return Err(CoreError::NotFound { what: "recovery code".into() });
        }
        self.store.write(|db| Ok::<_, CoreError>(db.set_setting(RECOVERY_SAVED, "1")?))?;
        self.listener.on_change(Change::Account);
        Ok(())
    }

    /// The word as it is in the list when `text` (trimmed, any Unicode normalization) is one of the 2048 words, else
    /// `None`: the input cell turns red.
    pub fn check_recovery_word(&self, text: String) -> Result<Option<String>, CoreError> {
        Ok(recovery::word(&text).map(str::to_owned))
    }

    /// Checks twelve words before anything slow or online: the button stays off for a word outside the list or a
    /// checksum that does not match (screen 21).
    pub fn check_recovery_code(&self, words: Vec<String>) -> RecoveryCodeCheck {
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        match recovery::entropy(&words) {
            Ok(_) => RecoveryCodeCheck::Valid,
            Err(CodeError::WordCount(n)) => RecoveryCodeCheck::WrongWordCount { count: count(n) },
            Err(CodeError::UnknownWord(i)) => RecoveryCodeCheck::UnknownWord { index: count(i) },
            Err(CodeError::Checksum) => RecoveryCodeCheck::Checksum,
        }
    }

    /// Words of the list that start with `prefix`, for the input cell's completion. Two syllables always single one
    /// out (protocol §10).
    pub fn recovery_completions(&self, prefix: String) -> Result<Vec<String>, CoreError> {
        Ok(recovery::completions(&prefix).into_iter().map(str::to_owned).collect())
    }

    /// The words of a pasted code in order, with numbering, punctuation and line breaks dropped.
    pub fn split_recovery_code(&self, text: String) -> Result<Vec<String>, CoreError> {
        Ok(recovery::split(&text))
    }

    // ----- backup (screen 20; protocol §10) -----

    pub fn backup_status(&self) -> Result<BackupStatus, CoreError> {
        let ready = self.backup_ready()?;
        self.store.read(|db| {
            let last = db.backup_records()?.first().map(view_of_note).transpose()?;
            let auto = db.setting(AUTO_BACKUP)?.as_deref() != Some("0");
            let changed = match db.setting(BACKED_UP_CHANGES)?.and_then(|n| n.parse::<u64>().ok()) {
                Some(carried) => changes(db)? > carried,
                None => true,
            };
            Ok(BackupStatus { last, auto, changed, ready })
        })
    }

    /// "지금 백업": seals the archive with the account master key and uploads it.
    pub async fn backup_now(
        self: Arc<Self>,
        progress: Option<Arc<dyn ProgressListener>>,
    ) -> Result<BackupView, CoreError> {
        let this = self.clone();
        self.run(async move { this.backup(false, progress).await }).await
    }

    /// The app's daily Wi-Fi task: backs up when automatic backup is on and something changed since the last one.
    /// `None` when it was not due.
    pub async fn auto_backup(self: Arc<Self>) -> Result<Option<BackupView>, CoreError> {
        let status = self.backup_status()?;
        if !(status.auto && status.changed && status.ready) {
            return Ok(None);
        }
        let this = self.clone();
        self.run(async move { this.backup(true, None).await.map(Some) }).await
    }

    /// The account's backups on the server, newest first, at most three. A replacement in progress reads them with
    /// its ticket, for "백업 3개 지우고 새로 시작".
    pub async fn backups(self: Arc<Self>) -> Result<Vec<BackupView>, CoreError> {
        let this = self.clone();
        self.run(async move {
            let signed_in = this.current_session()?.is_some();
            let ticket = match signed_in {
                true => None,
                false => Some(this.pending_ticket()?.ok_or(CoreError::NotSignedIn)?.ticket),
            };
            let list = match this.checked(this.api.backups(ticket.as_deref()).await).await {
                Err(CoreError::Api { status: 401, code, .. }) if ticket.is_some() && code == "token_expired" => {
                    return Err(CoreError::SignInAgain);
                }
                other => other?,
            };
            let notes = this.store.read(|db| db.backup_records())?;
            if signed_in && this.owns_store()? {
                let ids: Vec<i64> = list.iter().map(|b| b.id).collect();
                this.store.write(|db| Ok::<_, CoreError>(db.retain_backup_records(&ids)?))?;
            }
            let count = |n: i64| u32::try_from(n).unwrap_or(0);
            list.iter()
                .map(|b| {
                    let note = notes.iter().find(|n| n.id == b.id);
                    Ok(BackupView {
                        id: b.id,
                        created_at: system_time(parse_stored(&b.created_at)?),
                        size: b.size,
                        automatic: note.map(|n| n.automatic),
                        events: note.map(|n| count(n.events)),
                        people: note.map(|n| count(n.people)),
                    })
                })
                .collect()
        })
        .await
    }

    pub fn set_auto_backup(&self, enabled: bool) -> Result<(), CoreError> {
        self.store.write(|db| Ok::<_, CoreError>(db.set_setting(AUTO_BACKUP, if enabled { "1" } else { "0" })?))?;
        self.listener.on_change(Change::Account);
        Ok(())
    }

    // ----- alpha statistics (protocol §14) -----

    /// Whether this device sends the daily counts. Alpha builds only, and only for a tester who agreed; off until then.
    pub fn alpha_stats_enabled(&self) -> Result<bool, CoreError> {
        Ok(self.store.read(|db| db.setting(ALPHA_SINCE))?.is_some())
    }

    /// Turns the daily counts on or off. `sync()` sends them at most once a day while on, from the day the tester
    /// agreed: what was counted before goes nowhere. Off drops every count this device kept.
    pub fn set_alpha_stats(&self, enabled: bool) -> Result<(), CoreError> {
        let today = day_text(kst_day(self.now()));
        self.store.write(|db| {
            match enabled {
                true if db.setting(ALPHA_SINCE)?.is_none() => db.set_setting(ALPHA_SINCE, &today)?,
                true => {}
                false => {
                    db.clear_alpha()?;
                    db.delete_setting(ALPHA_SINCE)?;
                    db.delete_setting(ALPHA_SENT_ON)?;
                }
            }
            Ok::<_, CoreError>(())
        })?;
        self.listener.on_change(Change::Account);
        Ok(())
    }

    /// The app came to the foreground: one more of the day's `sessions`, the count the calendar-activity and
    /// crash-free-session gates use (protocol §14). Kept only while the alpha statistics are on.
    pub fn record_app_foreground(&self) -> Result<(), CoreError> {
        let today = day_text(kst_day(self.now()));
        self.store.write(|db| {
            if db.setting(ALPHA_SINCE)?.is_some() {
                db.count_alpha(&today, 1, 0)?;
            }
            Ok::<_, CoreError>(())
        })
    }

    /// Crashes the OS reported for a Korean `day`, learned on a later launch: iOS MetricKit `MXCrashDiagnostic`,
    /// Android `ApplicationExitInfo` with `REASON_CRASH` or `REASON_CRASH_NATIVE` only. They add to that day, and the
    /// day goes to the server again, which keeps the newest copy. The app reports each crash once (Android remembers
    /// the newest exit it has counted). Kept only while the alpha statistics are on; a day older than the 14 the
    /// server takes is dropped.
    pub fn record_crashes(&self, day: String, count: u32) -> Result<(), CoreError> {
        let crashed = crate::local::day(&day)?;
        let today = kst_day(self.now());
        if count == 0 || crashed > today || (today - crashed).num_days() >= ALPHA_DAYS {
            return Ok(());
        }
        self.store.write(|db| {
            if db.setting(ALPHA_SINCE)?.is_some() {
                db.count_alpha(&day_text(crashed), 0, i64::from(count))?;
            }
            Ok::<_, CoreError>(())
        })
    }
}

/// The ticket of a `ReplaceRequired` sign-in, until `restore_from_code` or `start_fresh` spends it.
#[derive(Clone)]
pub(crate) struct PendingReplace {
    pub(crate) user_id: String,
    pub(crate) ticket: String,
    pub(crate) expires_at: Instant,
}

/// The account as the last sign-in or link left it, for screens that work offline.
#[derive(Serialize, Deserialize)]
struct StoredAccount {
    user_id: String,
    display_name: String,
    providers: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct StoredRevocation {
    at: String,
    by: String,
    via: String,
}

/// The backups this device had on record are gone or another account's: their notes go, and so does the change count
/// the last one carried, so the store counts as not backed up until the next backup.
fn forget_backups(db: &Db<'_>) -> Result<(), CoreError> {
    db.retain_backup_records(&[])?;
    Ok(db.delete_setting(BACKED_UP_CHANGES)?)
}

fn malformed(e: impl std::fmt::Display) -> CoreError {
    CoreError::Sync { reason: e.to_string() }
}

fn view_of_note(note: &BackupRecordRow) -> Result<BackupView, CoreError> {
    let count = |n: i64| u32::try_from(n).unwrap_or(0);
    Ok(BackupView {
        id: note.id,
        created_at: system_time(parse_stored(&note.created_at)?),
        size: u64::try_from(note.size).unwrap_or(0),
        automatic: Some(note.automatic),
        events: Some(count(note.events)),
        people: Some(count(note.people)),
    })
}

/// The answers after which this device has no session left: another device's sign-in (403 `device_revoked`) or the
/// account's deletion (401 `account_deleted`). Unlike other failures, they end the call rather than wait for a retry.
fn ends_session(e: &CoreError) -> bool {
    match e {
        CoreError::Api { status: 403, code, .. } => code == "device_revoked",
        CoreError::Api { status: 401, code, .. } => code == ACCOUNT_DELETED,
        _ => false,
    }
}

fn provider_of(name: &str) -> Option<Provider> {
    match name {
        KAKAO => Some(Provider::Kakao),
        APPLE => Some(Provider::Apple),
        _ => None,
    }
}

/// The names an account starts with when the provider gave none (design decision 22). They go to the server as data,
/// so they are not screen strings.
const KAKAO_NAME: &str = "카카오 사용자";
const APPLE_NAME: &str = "Apple 사용자";
const GUEST_NAME: &str = "게스트";
/// The server's limit, which it counts in UTF-16 units: one per Hangul syllable, two for most emoji.
const NAME_UNITS: usize = 40;

/// The name a new account is made with: trimmed, `default` when nothing is left, and cut after the last whole
/// character (grapheme) that fits the server's limit, so an emoji is never split.
fn display_name(text: &str, default: &str) -> String {
    let fit = |name: &str| {
        let mut units = 0;
        let mut end = 0;
        for grapheme in name.graphemes(true) {
            units += grapheme.encode_utf16().count();
            if units > NAME_UNITS {
                break;
            }
            end += grapheme.len();
        }
        name[..end].trim_end().to_owned()
    };
    let name = fit(text.trim());
    if name.is_empty() { default.to_owned() } else { name }
}

impl Core {
    async fn sign_in_provider(
        self: Arc<Self>,
        provider: &'static str,
        nonce: String,
        id_token: String,
        account: NewAccount,
    ) -> Result<AuthResult, CoreError> {
        let name = display_name(&account.display_name, if provider == APPLE { APPLE_NAME } else { KAKAO_NAME });
        let this = self.clone();
        self.run(async move {
            let request = ProviderSignIn {
                proof: this.proof_for(nonce)?,
                account: NewAccountFields {
                    age_confirmed_at: Some(stored_utc(DateTime::<Utc>::from(account.age_confirmed_at))),
                    display_name: Some(name.clone()),
                },
                id_token,
            };
            let outcome = this.api.sign_in_provider(provider, request).await?;
            this.finish_sign_in(outcome, Some(name)).await
        })
        .await
    }

    async fn link(
        self: Arc<Self>,
        provider: &'static str,
        nonce: String,
        id_token: String,
    ) -> Result<Vec<Provider>, CoreError> {
        let this = self.clone();
        self.run(async move {
            let request = LinkRequest { provider: provider.to_owned(), id_token, nonce };
            let providers = this.checked(this.api.link(request).await).await?;
            if let Some(mut account) = this.stored_account()? {
                account.providers.clone_from(&providers);
                this.store_account(&account)?;
            }
            this.listener.on_change(Change::Account);
            Ok(providers.iter().filter_map(|p| provider_of(p)).collect())
        })
        .await
    }

    /// This device's proof over a server nonce.
    fn proof_for(&self, nonce: String) -> Result<DeviceProof, CoreError> {
        let secret = signing_secret(self.secrets.as_ref())?;
        Ok(DeviceProof {
            platform: PLATFORM.to_owned(),
            signature_key: MlsClient::public_key_of(&secret)?,
            signature: MlsClient::sign_with(&secret, nonce.as_bytes())?,
            nonce,
        })
    }

    /// After any sign-in. A registered device gets its session, its engine and key packages, and recovery material when
    /// the server holds none (protocol §10 "올리는 때"); a device that has to replace another keeps the ticket.
    pub(crate) async fn finish_sign_in(
        &self,
        outcome: AuthOutcome,
        display_name: Option<String>,
    ) -> Result<AuthResult, CoreError> {
        match outcome {
            AuthOutcome::Registered { tokens, new_account, recovery } => {
                let session = self.register_session(&tokens, display_name).await?;
                if !recovery {
                    self.make_recovery(&session.user_id, false, None).await?;
                }
                Ok(AuthResult::Registered { session, new_account, recovery })
            }
            AuthOutcome::ReplaceRequired { user_id, ticket, expires_in, active_device, recovery } => {
                let lifetime = Duration::from_secs(u64::try_from(expires_in).unwrap_or(0));
                *self.ticket.lock().unwrap_or_else(|p| p.into_inner()) =
                    Some(PendingReplace { user_id: user_id.clone(), ticket, expires_at: Instant::now() + lifetime });
                Ok(AuthResult::ReplaceRequired {
                    user_id,
                    active_platform: active_device.platform,
                    active_since: parse_stored(&active_device.created_at)
                        .map(system_time)
                        .unwrap_or_else(|_| SystemTime::now()),
                    recovery,
                })
            }
        }
    }

    /// The ticket of a replacement in progress. It stays until the replacement succeeds, so a restore that failed on a
    /// wrong code or the network can be tried again; one that ran out means signing in again.
    pub(crate) fn pending_ticket(&self) -> Result<Option<PendingReplace>, CoreError> {
        let mut ticket = self.ticket.lock().unwrap_or_else(|p| p.into_inner());
        match ticket.as_ref() {
            Some(pending) if pending.expires_at <= Instant::now() => {
                *ticket = None;
                Err(CoreError::SignInAgain)
            }
            pending => Ok(pending.cloned()),
        }
    }

    /// `POST /auth/replace`: this device becomes the active one and the other is signed out.
    pub(crate) async fn replace_device(&self, pending: PendingReplace) -> Result<Session, CoreError> {
        let secret = signing_secret(self.secrets.as_ref())?;
        let signature = MlsClient::sign_with(&secret, &login::replace_message(&pending.ticket))?;
        let outcome = match self.api.replace(ReplaceRequest { ticket: pending.ticket, signature }).await {
            Err(e) if e.is(401, "token_expired") => {
                *self.ticket.lock().unwrap_or_else(|p| p.into_inner()) = None;
                return Err(CoreError::SignInAgain);
            }
            other => other?,
        };
        let AuthOutcome::Registered { tokens, .. } = outcome else {
            return Err(malformed("a replacement asked for another replacement"));
        };
        if tokens.user_id != pending.user_id {
            return Err(malformed("the replacement registered another account"));
        }
        *self.ticket.lock().unwrap_or_else(|p| p.into_inner()) = None;
        self.register_session(&tokens, None).await
    }

    /// A guest coming back with the code (`POST /auth/recover`, protocol §13): the device registers and the account's
    /// other device is signed out. A code the server does not know is `WrongRecoveryCode`.
    async fn recover_guest(&self, keys: &RecoveryKeys) -> Result<String, CoreError> {
        let nonce = self.api.nonce().await?;
        let request = RecoverSignIn {
            proof: self.proof_for(nonce.clone())?,
            recovery_id: keys.id().to_vec(),
            recovery_signature: keys.sign_recovery(&nonce).to_vec(),
        };
        let outcome = match self.api.recover(request).await {
            Err(e) if e.is(401, "recovery_invalid") => return Err(CoreError::WrongRecoveryCode),
            other => other?,
        };
        let AuthOutcome::Registered { tokens, .. } = outcome else {
            return Err(malformed("a guest comeback asked for a replacement"));
        };
        Ok(self.register_session(&tokens, None).await?.user_id)
    }

    /// Whether the store is the signed-in account's: claimed by it, or by nobody yet.
    fn owns_store(&self) -> Result<bool, CoreError> {
        let Some(session) = self.current_session()? else { return Ok(false) };
        Ok(self.store.read(|db| db.setting(STORE_OWNER))?.is_none_or(|owner| owner == session.user_id))
    }

    /// A backup needs the code on this device, its material on the server and a store that is this account's.
    fn backup_ready(&self) -> Result<bool, CoreError> {
        let has_code = self.recovery_secrets()?.is_some();
        let uploaded = self.store.read(|db| db.setting(RECOVERY_UPLOADED))?.is_some();
        Ok(has_code && uploaded && self.owns_store()?)
    }

    /// Seals the archive under the master key and uploads it (protocol §10 "올리기").
    async fn backup(
        &self,
        automatic: bool,
        progress: Option<Arc<dyn ProgressListener>>,
    ) -> Result<BackupView, CoreError> {
        let session = self.current_session()?.ok_or(CoreError::NotSignedIn)?;
        if !self.owns_store()? {
            return Err(CoreError::Invalid {
                reason: "another account's events are on this device; settle them first".into(),
            });
        }
        // The recovery material is what opens a backup later, so it goes up first.
        self.upload_pending_recovery().await?;
        let uploaded = self.store.read(|db| db.setting(RECOVERY_UPLOADED))?.is_some();
        let Some((_, amk)) = self.recovery_secrets()?.filter(|_| uploaded) else {
            return Err(CoreError::RecoveryMissing);
        };
        let made_at = stored_utc(self.now());
        // The count goes with the snapshot: a change made during the upload still counts afterwards.
        let (archive, carried) = self.store.write(|db| {
            db.set_setting(STORE_OWNER, &session.user_id)?;
            Ok::<_, CoreError>((db.archive(&made_at)?, changes(db)?))
        })?;
        let object = backup::seal(&amk, &archive::encode(&archive));
        let size = object.len() as u64;
        let upload = match self.checked(self.api.start_backup(size).await).await {
            Err(CoreError::Api { status: 409, code, .. }) if code == "recovery_missing" => {
                return Err(CoreError::RecoveryMissing);
            }
            other => other?,
        };
        let report = |done| {
            if let Some(listener) = &progress {
                listener.on_progress(done, size);
            }
        };
        report(0);
        self.api.put_object(&upload.upload_url, object).await?;
        report(size);
        let info = self.checked(self.api.complete_backup(upload.id).await).await?;
        let count = |n: usize| i64::try_from(n).unwrap_or(i64::MAX);
        let note = BackupRecordRow {
            id: info.id,
            created_at: info.created_at,
            size: i64::try_from(info.size).unwrap_or(i64::MAX),
            automatic,
            events: count(archive.events.len()),
            people: count(archive.people.len()),
        };
        self.store.write(|db| {
            db.put_backup_record(&note)?;
            // The server keeps the newest three; so do the notes.
            let ids: Vec<i64> = db.backup_records()?.iter().take(3).map(|n| n.id).collect();
            db.retain_backup_records(&ids)?;
            Ok::<_, CoreError>(db.set_setting(BACKED_UP_CHANGES, &carried.to_string())?)
        })?;
        self.listener.on_change(Change::Account);
        view_of_note(&note)
    }

    /// Keeps the session, starts the engine with fresh key packages, and remembers the account.
    /// Keeps the session, starts the engine with fresh key packages, and remembers the account. Key packages that do
    /// not go up do not undo the sign-in, as the device is registered by then: `sync()` puts them up.
    async fn register_session(
        &self,
        tokens: &geunal_sync::api::SessionInfo,
        display_name: Option<String>,
    ) -> Result<Session, CoreError> {
        let session = geunal_sync::session_of(tokens);
        self.secrets.put(SESSION.to_owned(), serde_json::to_vec(&session).map_err(malformed)?)?;
        let engine = build_engine(
            &self.store_dir,
            &self.store,
            &self.db_key,
            &session,
            self.secrets.as_ref(),
            self.api.clone(),
            self.listener.clone(),
        )?;
        *self.state.lock().await = State::SignedIn(Box::new(engine));
        // A replacement begun for another sign-in and left behind must not act for this one (`start_fresh`).
        *self.ticket.lock().unwrap_or_else(|p| p.into_inner()) = None;
        // A new session asks the server again what a stopped sign-in left undone (`upload_pending_recovery`).
        self.recovery_checked.store(false, Ordering::Relaxed);
        self.store.write(|db| Ok::<_, CoreError>(db.set_setting(KEY_PACKAGES_DUE, "1")?))?;
        if let Err(e) = self.publish_due_key_packages().await
            && ends_session(&e)
        {
            return Err(e);
        }
        match self.api.me().await {
            Ok(me) => self.store_me(me)?,
            // What this device knew of the account stays; a first sign-in keeps the name it sent. `sync()` asks again.
            Err(_) => {
                let known = self.stored_account()?.filter(|a| a.user_id == session.user_id);
                if known.is_none() {
                    self.store_account(&StoredAccount {
                        user_id: session.user_id.clone(),
                        display_name: display_name.unwrap_or_default(),
                        providers: Vec::new(),
                    })?;
                }
                self.store.write(|db| Ok::<_, CoreError>(db.set_setting(ACCOUNT_STALE, "1")?))?;
            }
        }
        self.store.write(|db| {
            db.delete_setting(REVOCATION)?;
            db.delete_setting(STORE_RESET)?;
            // A store nobody owns yet, used before any sign-in, or one with nothing of anyone's in it, is this
            // account's. One with another account's events waits for `settle_local_data` or a restore.
            let own = db.own_data()?;
            if db.setting(STORE_OWNER)?.is_none() || (own.events == 0 && own.people == 0) {
                db.set_setting(STORE_OWNER, &session.user_id)?;
            }
            Ok::<_, CoreError>(())
        })?;
        self.listener.on_change(Change::Account);
        Ok(Session { user_id: session.user_id, device_id: session.device_id })
    }

    fn stored_account(&self) -> Result<Option<StoredAccount>, CoreError> {
        let Some(text) = self.store.read(|db| db.setting(ACCOUNT))? else { return Ok(None) };
        Ok(Some(serde_json::from_str(&text).map_err(malformed)?))
    }

    fn store_account(&self, account: &StoredAccount) -> Result<(), CoreError> {
        let text = serde_json::to_string(account).map_err(malformed)?;
        self.store.write(|db| Ok::<_, CoreError>(db.set_setting(ACCOUNT, &text)?))
    }

    /// The account as `GET /me` tells it. An M0 server has no providers there.
    fn store_me(&self, me: geunal_sync::api::MeInfo) -> Result<(), CoreError> {
        self.store_account(&StoredAccount {
            user_id: me.user_id,
            display_name: me.display_name,
            providers: me.providers,
        })?;
        self.store.write(|db| Ok::<_, CoreError>(db.delete_setting(ACCOUNT_STALE)?))
    }

    /// Asks `GET /me` again for an account the sign-in could not (`ACCOUNT_STALE`). A network error waits for the
    /// next `sync()`.
    pub(crate) async fn refresh_account(&self) -> Result<(), CoreError> {
        if self.store.read(|db| db.setting(ACCOUNT_STALE))?.is_none() {
            return Ok(());
        }
        match self.checked(self.api.me().await).await {
            Ok(me) => {
                self.store_me(me)?;
                self.listener.on_change(Change::Account);
                Ok(())
            }
            Err(CoreError::Network { .. }) => Ok(()),
            Err(e) => Err(e),
        }
    }

    /// The code's entropy and the master key, when this device holds them for the signed-in account.
    pub(crate) fn recovery_secrets(&self) -> Result<Option<(Entropy, geunal_crypto::Key)>, CoreError> {
        let Some(session) = self.current_session()? else { return Ok(None) };
        self.recovery_secrets_of(&session.user_id)
    }

    /// The code's entropy and the master key, when this device holds them for `user_id`.
    fn recovery_secrets_of(&self, user_id: &str) -> Result<Option<(Entropy, geunal_crypto::Key)>, CoreError> {
        let owner = self.store.read(|db| db.setting(RECOVERY_OWNER))?;
        if owner.as_deref() != Some(user_id) {
            return Ok(None);
        }
        let entropy = self.secrets.get(ENTROPY.to_owned())?.and_then(|e| Entropy::try_from(e.as_slice()).ok());
        let amk = self.secrets.get(AMK.to_owned())?.and_then(|k| geunal_crypto::Key::try_from(k.as_slice()).ok());
        Ok(entropy.zip(amk))
    }

    /// Keeps a code and a master key as this account's on this device. A code that is not on the server yet waits for
    /// `upload_recovery`, with `new_key` when it replaces the server's material without a proof.
    pub(crate) fn keep_recovery(
        &self,
        user_id: &str,
        entropy: &Entropy,
        amk: &geunal_crypto::Key,
        uploaded: bool,
        new_key: bool,
    ) -> Result<(), CoreError> {
        // The owner marks the pair as whole. A store that takes the entropy and then fails on the key leaves no owner,
        // so the new words are never shown, or uploaded, with the old key.
        self.store.write(|db| Ok::<_, CoreError>(db.delete_setting(RECOVERY_OWNER)?))?;
        self.secrets.put(ENTROPY.to_owned(), entropy.to_vec())?;
        self.secrets.put(AMK.to_owned(), amk.to_vec())?;
        self.store.write(|db| {
            db.set_setting(RECOVERY_OWNER, user_id)?;
            db.delete_setting(RECOVERY_SAVED)?;
            if uploaded {
                db.set_setting(RECOVERY_UPLOADED, "1")?;
            } else {
                db.delete_setting(RECOVERY_UPLOADED)?;
            }
            if new_key && !uploaded {
                db.set_setting(RECOVERY_NEW_KEY, "1")?;
            } else {
                db.delete_setting(RECOVERY_NEW_KEY)?;
            }
            Ok::<_, CoreError>(())
        })
    }

    /// Drops this device's code and master key.
    fn forget_recovery(&self) -> Result<(), CoreError> {
        self.secrets.delete(ENTROPY.to_owned())?;
        self.secrets.delete(AMK.to_owned())?;
        *self.recovery_keys.lock().unwrap_or_else(|p| p.into_inner()) = None;
        self.store.write(|db| {
            for key in [RECOVERY_SAVED, RECOVERY_UPLOADED, RECOVERY_NEW_KEY, RECOVERY_OWNER] {
                db.delete_setting(key)?;
            }
            Ok::<_, CoreError>(())
        })?;
        self.listener.on_change(Change::Account);
        Ok(())
    }

    /// Makes the recovery code and the master key when this device has none for the account, and uploads the
    /// material, with `new_key` for "백업 없이 새로 시작". A code this device holds already is one that an earlier try
    /// left waiting (`start_fresh` refuses any other), and it goes up again: that try may have gone through with its
    /// answer lost. A failed upload stays pending for `sync()`, which sends `new_key` again. After a sign-in the failure
    /// does not undo the sign-in: the device is registered by then, and `sync()` reports what keeps failing. "백업 없이
    /// 새로 시작" reports it at once, as the server takes `new_key` only within 30 minutes of the provider sign-in.
    async fn make_recovery(
        &self,
        user_id: &str,
        new_key: bool,
        proof: Option<&DeletionProof>,
    ) -> Result<(), CoreError> {
        if self.recovery_secrets()?.is_none() {
            self.keep_recovery(user_id, &recovery::new_entropy(), &geunal_crypto::random_key(), false, new_key)?;
        }
        match self.upload_recovery(new_key, proof).await {
            Err(e) if !new_key && !ends_session(&e) => Ok(()),
            other => other,
        }
    }

    /// The upload `sync()` retries until it succeeds (protocol §10 "올리는 때"). A network error waits for the next
    /// `sync()`, and so does a 404: an M0 server has no `/me/recovery` yet, and the M1 one never answers it with 404.
    /// A new code the server no longer takes without a provider proof is dropped quietly: `recovery_status()` shows
    /// the device has no code, and "백업 없이 새로 시작" asks for the proof. Anything else is reported.
    ///
    /// A device without a code asks `GET /me` once a run whether the account has recovery material, and makes the
    /// code when it has none: a sign-in that stopped before making it, as when the app was closed, ends here.
    pub(crate) async fn upload_pending_recovery(&self) -> Result<(), CoreError> {
        let Some(session) = self.current_session()? else { return Ok(()) };
        if self.recovery_secrets()?.is_none() {
            if self.recovery_checked.load(Ordering::Relaxed) {
                return Ok(());
            }
            let me = match self.checked(self.api.me().await).await {
                Err(CoreError::Network { .. }) => return Ok(()),
                other => other?,
            };
            if !me.recovery {
                self.make_recovery(&session.user_id, false, None).await?;
            }
            // Only once the code is made: a secret store that failed to take it leaves the next sync to try again.
            self.recovery_checked.store(true, Ordering::Relaxed);
            return Ok(());
        }
        let (uploaded, new_key) = self.store.read(|db| {
            Ok::<_, CoreError>((db.setting(RECOVERY_UPLOADED)?.is_some(), db.setting(RECOVERY_NEW_KEY)?.is_some()))
        })?;
        if uploaded {
            return Ok(());
        }
        match self.upload_recovery(new_key, None).await {
            Err(CoreError::Network { .. } | CoreError::Api { status: 404, .. } | CoreError::ReauthRequired) => Ok(()),
            other => other,
        }
    }

    /// `PUT /me/recovery`. A retry may find its own earlier upload stored, which the same code then proves; an id
    /// another account already uses means a new `E` (409 `recovery_conflict`, protocol §11).
    async fn upload_recovery(&self, new_key: bool, provider: Option<&DeletionProof>) -> Result<(), CoreError> {
        let session = self.current_session()?.ok_or(CoreError::NotSignedIn)?;
        let mut conflicts = 0;
        loop {
            let (entropy, amk) = self.recovery_secrets()?.ok_or_else(|| malformed("no recovery code to upload"))?;
            let keys = self.keys_of(entropy).await?;
            let upload = RecoveryUpload {
                amk_wrapped: keys.wrap_amk(&amk),
                recovery_id: keys.id().to_vec(),
                recovery_public_key: keys.public_key().to_vec(),
                new_key,
                nonce: None,
                proof: None,
                kakao_id_token: None,
                apple_authorization_code: None,
            };
            let upload = match provider.filter(|_| new_key) {
                Some(DeletionProof::Kakao { nonce, id_token }) => {
                    RecoveryUpload { nonce: Some(nonce.clone()), kakao_id_token: Some(id_token.clone()), ..upload }
                }
                Some(DeletionProof::Apple { authorization_code }) => {
                    RecoveryUpload { apple_authorization_code: Some(authorization_code.clone()), ..upload }
                }
                _ => upload,
            };
            let result = match self.api.put_recovery(upload.clone()).await {
                // The server may hold this code already, from a try whose answer was lost: the code then proves
                // itself. Past the 30 minutes that is how a waiting `new_key` upload goes through without a provider
                // proof.
                Err(first)
                    if first.is(403, "recovery_proof_required") || (new_key && first.is(403, "reauth_required")) =>
                {
                    let nonce = self.api.nonce().await?;
                    let retry = RecoveryUpload {
                        new_key: false,
                        proof: Some(keys.sign_rotation(&nonce).to_vec()),
                        nonce: Some(nonce),
                        kakao_id_token: None,
                        apple_authorization_code: None,
                        ..upload
                    };
                    match self.api.put_recovery(retry).await {
                        // Another code is on the server: the first answer stands.
                        Err(e) if e.is(403, "recovery_proof_required") => Err(first),
                        other => other,
                    }
                }
                other => other,
            };
            match self.checked(result).await {
                Ok(()) => break,
                Err(CoreError::Api { status: 409, code, .. }) if code == "recovery_conflict" && conflicts == 0 => {
                    conflicts += 1;
                    self.keep_recovery(&session.user_id, &recovery::new_entropy(), &amk, false, new_key)?;
                }
                // Past the 30 minutes without a provider proof that holds. Nothing changed on the server; the new
                // code goes, the device is back to having none, and the app comes back with a fresh proof.
                Err(CoreError::Api { status: 403, code, .. }) if new_key && code == "reauth_required" => {
                    self.forget_recovery()?;
                    return Err(CoreError::ReauthRequired);
                }
                Err(e) => return Err(e),
            }
        }
        self.store.write(|db| {
            db.set_setting(RECOVERY_UPLOADED, "1")?;
            Ok::<_, CoreError>(db.delete_setting(RECOVERY_NEW_KEY)?)
        })?;
        self.listener.on_change(Change::Account);
        Ok(())
    }

    /// The body of `DELETE /me` for `proof`. A guest's is signed here with the recovery key of this device's code over
    /// a fresh nonce, without asking the user (protocol §13 "계정 삭제").
    async fn deletion_request(&self, proof: &DeletionProof) -> Result<DeleteAccount, CoreError> {
        Ok(match proof {
            DeletionProof::Guest => {
                let (entropy, _) = self.recovery_secrets()?.ok_or_else(|| CoreError::Invalid {
                    reason: "this device holds no recovery code to sign with".into(),
                })?;
                let keys = self.keys_of(entropy).await?;
                let nonce = self.checked(self.api.nonce().await).await?;
                DeleteAccount {
                    recovery_signature: Some(keys.sign_deletion(&nonce).to_vec()),
                    nonce: Some(nonce),
                    ..DeleteAccount::default()
                }
            }
            DeletionProof::Kakao { nonce, id_token } => DeleteAccount {
                nonce: Some(nonce.clone()),
                kakao_id_token: Some(id_token.clone()),
                ..DeleteAccount::default()
            },
            DeletionProof::Apple { authorization_code } => {
                DeleteAccount { apple_authorization_code: Some(authorization_code.clone()), ..DeleteAccount::default() }
            }
        })
    }

    /// The keys of `entropy`, from memory after the first derivation of this run.
    pub(crate) async fn keys_of(&self, entropy: Entropy) -> Result<Arc<RecoveryKeys>, CoreError> {
        let cached = self.recovery_keys.lock().unwrap_or_else(|p| p.into_inner()).clone();
        if let Some((_, keys)) = cached.filter(|(of, _)| *of == entropy) {
            return Ok(keys);
        }
        let keys = Arc::new(derive(entropy).await?);
        *self.recovery_keys.lock().unwrap_or_else(|p| p.into_inner()) = Some((entropy, keys.clone()));
        Ok(keys)
    }

    /// A call made outside the engine: a 403 `device_revoked` signs this device out here too, and a 401
    /// `account_deleted` finishes the deletion.
    pub(crate) async fn checked<R>(&self, result: Result<R, ApiError>) -> Result<R, CoreError> {
        match result {
            Err(ApiError::Revoked { replaced }) => {
                *self.state.lock().await = State::SignedOut;
                self.signed_out_by_another(replaced).await?;
                Err(revoked_error())
            }
            Err(e) if e.is(401, ACCOUNT_DELETED) => {
                self.account_deleted().await?;
                Err(account_deleted_error())
            }
            other => other.map_err(CoreError::from),
        }
    }

    /// After a realtime `revoke`, which signs this device out by itself (protocol §13 "기기 해제"). The server's 403
    /// says when, where and how; when the server cannot be asked, the device goes without those. Only an answer to
    /// `GET /me` keeps the session: the server still counts this device as active.
    pub(crate) async fn confirm_revocation(&self) -> Result<(), CoreError> {
        let replaced = match self.api.me().await {
            Ok(_) => return Ok(()),
            Err(ApiError::Revoked { replaced }) => replaced,
            Err(e) if e.is(401, ACCOUNT_DELETED) => return self.account_deleted().await,
            Err(_) => None,
        };
        *self.state.lock().await = State::SignedOut;
        self.signed_out_by_another(replaced).await
    }

    /// Puts up the key packages a sign-in could not (`KEY_PACKAGES_DUE`). A network error waits for the next `sync()`.
    pub(crate) async fn publish_due_key_packages(&self) -> Result<(), CoreError> {
        if self.store.read(|db| db.setting(KEY_PACKAGES_DUE))?.is_none() {
            return Ok(());
        }
        match self.engine(Box::new(|e| Box::pin(async move { e.publish_key_packages(KEY_PACKAGES).await }))).await {
            Ok(()) => Ok(self.store.write(|db| Ok::<_, CoreError>(db.delete_setting(KEY_PACKAGES_DUE)?))?),
            Err(CoreError::Network { .. }) => Ok(()),
            Err(e) => Err(e),
        }
    }

    /// Once a day while the tester agrees: the days since they agreed whose counts changed since they last went, of
    /// the last 14 (protocol §14). Today goes as it stands and again tomorrow with its final counts, so the last day a
    /// tester opened the app reaches the server even if they never open it again. Nothing here fails `sync()` but a
    /// revocation: a network error tries again at the next `sync()`, and any other answer waits for tomorrow.
    pub(crate) async fn send_alpha_stats(&self) -> Result<(), CoreError> {
        let now = self.now();
        let today = kst_day(now);
        let first = day_text(today - chrono::Duration::days(ALPHA_DAYS - 1));
        let today = day_text(today);
        let due = self.store.write(|db| {
            let Some(since) = db.setting(ALPHA_SINCE)? else { return Ok::<_, CoreError>(Vec::new()) };
            if db.setting(ALPHA_SENT_ON)?.as_deref() == Some(today.as_str()) {
                return Ok(Vec::new());
            }
            db.prune_alpha(&first)?;
            Ok(db.alpha_days()?.into_iter().filter(|d| d.sent_at.is_none() && d.day >= since).collect())
        })?;
        if due.is_empty() {
            return Ok(());
        }
        let count = |n: i64| u32::try_from(n).unwrap_or(0);
        let days = due
            .iter()
            .map(|d| AlphaDay {
                day: d.day.clone(),
                sessions: count(d.sessions),
                events: count(d.events),
                suggested: count(d.suggested),
                crashes: count(d.crashes),
            })
            .collect();
        let sent = match self.checked(self.api.post_alpha_stats(days).await).await {
            Ok(()) => true,
            Err(CoreError::Network { .. }) => return Ok(()),
            Err(e) if ends_session(&e) => return Err(e),
            Err(_) => false,
        };
        let sent_at = stored_utc(now);
        self.store.write(|db| {
            if sent {
                for day in &due {
                    db.mark_alpha_sent(day, &sent_at)?;
                }
            }
            Ok::<_, CoreError>(db.set_setting(ALPHA_SENT_ON, &today)?)
        })
    }

    /// The account is gone: `DELETE /me` answered 204, or a 401 `account_deleted` told a device that lost that answer
    /// (protocol §13 "답을 잃은 삭제"). The device leaves it as on a revocation, without the note, and the alpha counts
    /// stop with it: the server deleted them too, so a new account agrees again.
    pub(crate) async fn account_deleted(&self) -> Result<(), CoreError> {
        // Before the app hears this device left the account.
        self.store.write(|db| {
            db.clear_alpha()?;
            for key in [ALPHA_SINCE, ALPHA_SENT_ON] {
                db.delete_setting(key)?;
            }
            Ok::<_, CoreError>(())
        })?;
        *self.state.lock().await = State::SignedOut;
        self.leave_account(None).await
    }

    /// Another device's sign-in signed this one out (protocol §13 "기기 해제"); `replaced` says when, where and how.
    pub(crate) async fn signed_out_by_another(&self, replaced: Option<Replaced>) -> Result<(), CoreError> {
        let revocation = replaced
            .map(|r| StoredRevocation { at: r.replaced_at, by: r.replaced_by, via: r.replaced_via })
            .map(|r| serde_json::to_string(&r).map_err(malformed))
            .transpose()?;
        self.leave_account(revocation).await
    }

    /// What goes when this device leaves its account, signed out by another or deleting it: the session, the device
    /// signing key, the code's entropy and the master key, and the group state. Events, people, birthdays and the
    /// database key that opens them stay. A stolen phone then cannot take a guest account back with its `E` or open a
    /// backup it downloaded.
    async fn leave_account(&self, revocation: Option<String>) -> Result<(), CoreError> {
        self.api.clear_session();
        *self.realtime.lock().await = None;
        // A secret the store fails to delete does not stop the rest: the account is gone either way. The failure comes
        // back once everything else is wiped.
        let mut failed = None;
        for name in ACCOUNT_SECRETS {
            if let Err(e) = self.secrets.delete(name.to_owned()) {
                failed.get_or_insert(e);
            }
        }
        *self.recovery_keys.lock().unwrap_or_else(|p| p.into_inner()) = None;
        self.store.write(|db| {
            db.wipe_group_state()?;
            // The notes of backups this device can no longer open. The store's owner stays, so another account
            // signing in here is asked about these events first.
            forget_backups(db)?;
            for key in [ACCOUNT, ACCOUNT_STALE, RECOVERY_SAVED, RECOVERY_UPLOADED, RECOVERY_NEW_KEY, RECOVERY_OWNER] {
                db.delete_setting(key)?;
            }
            if let Some(text) = &revocation {
                db.set_setting(REVOCATION, text)?;
            }
            Ok::<_, CoreError>(())
        })?;
        self.listener.on_change(Change::Account);
        failed.map_or(Ok(()), |e| Err(e.into()))
    }
}

/// Argon2id at 64 MiB takes a second or more on a phone, so it runs off the core's two async workers.
pub(crate) async fn derive(entropy: Entropy) -> Result<RecoveryKeys, CoreError> {
    tokio::task::spawn_blocking(move || RecoveryKeys::derive(&entropy))
        .await
        .map_err(|e| malformed(format!("derivation task: {e}")))?
        .map_err(malformed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_name_is_trimmed_defaulted_and_cut_after_the_last_whole_character() {
        assert_eq!(display_name("  하린  ", GUEST_NAME), "하린");
        assert_eq!(display_name(" \n ", KAKAO_NAME), "카카오 사용자");
        assert_eq!(display_name(&"가".repeat(41), GUEST_NAME), "가".repeat(40));
        // A family emoji is seven code points and eleven UTF-16 units; a fourth one would pass 40.
        let family = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}\u{200D}\u{1F466}";
        assert_eq!(display_name(&family.repeat(4), GUEST_NAME), family.repeat(3));
        // A flag is two regional indicators and four units: it goes whole or not at all.
        let flag = "\u{1F1F0}\u{1F1F7}";
        let fits = format!("{}{flag}", "가".repeat(36));
        assert_eq!(display_name(&fits, GUEST_NAME), fits);
        assert_eq!(display_name(&format!("{}{flag}", "가".repeat(37)), GUEST_NAME), "가".repeat(37));
        // A cut that leaves a space at the end drops it too.
        assert_eq!(display_name(&format!("{} 나", "가".repeat(39)), GUEST_NAME), "가".repeat(39));
    }
}

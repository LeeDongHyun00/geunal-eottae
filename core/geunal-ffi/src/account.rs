//! The M1 account surface (docs/design/m1.md screens 2, 12, 13, 20–22; protocol §10, §13, §14): signing in with
//! Kakao, Apple or as a guest, replacing a device, the recovery code, encrypted backups, account deletion and the
//! alpha statistics.
//!
//! The secrets this needs (the recovery code's entropy `E`, the account master key, the device signing key) live in
//! the app's `SecretStore` only. A replacement ticket lives in memory: it is good for 30 minutes and a restarted app
//! signs in again.

use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use chrono::{DateTime, Utc};
use geunal_crypto::login;
use geunal_crypto::recovery::{self, Entropy, RecoveryKeys};
use geunal_mls::MlsClient;
use geunal_store::Counted;
use geunal_sync::api::{
    AuthOutcome, DeviceProof, GuestSignIn, LinkRequest, NewAccountFields, ProviderSignIn, RecoveryUpload,
    ReplaceRequest, Replaced,
};
use geunal_sync::{ApiError, Transport as _};
use serde::{Deserialize, Serialize};

use crate::local::{parse_stored, stored_utc, system_time};
use crate::{
    Change, Core, CoreError, KEY_PACKAGES, SESSION, SIGNING_KEY, Session, State, build_engine, not_yet, revoked_error,
    signing_secret,
};

// The code's entropy and the master key live in the app's secret store; what follows the code on this device lives in
// its settings, never in the backup.
const ENTROPY: &str = "recovery_entropy";
const AMK: &str = "account_master_key";
const ACCOUNT: &str = "account";
const RECOVERY_SAVED: &str = "recovery_saved";
const RECOVERY_UPLOADED: &str = "recovery_uploaded";
/// The code waiting for upload replaces one nobody can prove ("백업 없이 새로 시작"), so every retry sends `new_key`.
const RECOVERY_NEW_KEY: &str = "recovery_new_key";
/// The account the code on this device belongs to: a sign-in into another account makes a new one.
const RECOVERY_OWNER: &str = "recovery_owner";
const REVOCATION: &str = "revocation";
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
    /// 1–40 characters.
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
    /// Something changed since the last backup; the automatic backup skips a day without changes.
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
        let display_name = display_name(&account.display_name)?;
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
    /// whether to bring them along (`LocalDataChoice`): the store was another account's, no sign-in has claimed it
    /// yet, or the account is not known before the code is checked (a guest coming back). `None` when there is
    /// nothing to ask, such as a store that is the signing-in account's own.
    pub fn local_data_of_other_account(&self) -> Result<Option<LocalData>, CoreError> {
        Err(not_yet("local_data_of_other_account"))
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
        let _ = (words, local_data, progress);
        Err(not_yet("restore_from_code"))
    }

    /// "백업 없이 새로 시작": finishes a replacement without the code. A new code and master key are made, and the
    /// account's backups are deleted on the server because nobody can open them any more (protocol §10).
    pub async fn start_fresh(self: Arc<Self>) -> Result<Session, CoreError> {
        let this = self.clone();
        self.run(async move {
            let session = match this.take_ticket() {
                Some(pending) => this.replace_device(pending).await?,
                None => this.session().ok_or(CoreError::NotSignedIn)?,
            };
            this.make_recovery(&session.user_id, true).await?;
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
        if self.session().is_none() {
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
                recovery_saved: db.setting(RECOVERY_SAVED)?.is_some(),
                last_backup_at,
            })
        })
    }

    /// Deletes the account on the server (`DELETE /me` with `proof`), then the session, the device signing key, the
    /// master key and the code's entropy, and the group state on this device. Events, calendars and people stay, and
    /// the app keeps working as a calendar without groups (m1.md 2.5). `ReauthRequired` leaves everything as it was
    /// and the session valid: ask for the proof again. `RecoveryMissing` means a guest's recovery material is not on
    /// the server yet; the core uploads it on the next `sync()`, and then the deletion can be tried again.
    pub async fn delete_account(self: Arc<Self>, proof: DeletionProof) -> Result<(), CoreError> {
        let _ = proof;
        Err(not_yet("delete_account"))
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
        Err(not_yet("backup_status"))
    }

    /// "지금 백업": seals the archive with the account master key and uploads it.
    pub async fn backup_now(
        self: Arc<Self>,
        progress: Option<Arc<dyn ProgressListener>>,
    ) -> Result<BackupView, CoreError> {
        let _ = progress;
        Err(not_yet("backup_now"))
    }

    /// The app's daily Wi-Fi task: backs up when automatic backup is on and something changed since the last one.
    /// `None` when it was not due.
    pub async fn auto_backup(self: Arc<Self>) -> Result<Option<BackupView>, CoreError> {
        Err(not_yet("auto_backup"))
    }

    /// The account's backups on the server, newest first, at most three.
    pub async fn backups(self: Arc<Self>) -> Result<Vec<BackupView>, CoreError> {
        Err(not_yet("backups"))
    }

    pub fn set_auto_backup(&self, enabled: bool) -> Result<(), CoreError> {
        let _ = enabled;
        Err(not_yet("set_auto_backup"))
    }

    // ----- alpha statistics (protocol §14) -----

    /// Whether this device sends the daily counts. Alpha builds only, and only for a tester who agreed.
    pub fn alpha_stats_enabled(&self) -> Result<bool, CoreError> {
        Err(not_yet("alpha_stats_enabled"))
    }

    /// Turns the daily counts on or off. `sync()` sends them at most once a day while on.
    pub fn set_alpha_stats(&self, enabled: bool) -> Result<(), CoreError> {
        let _ = enabled;
        Err(not_yet("set_alpha_stats"))
    }

    /// The app came to the foreground: one more of the day's `sessions`, the count the calendar-activity and
    /// crash-free-session gates use (protocol §14). Kept only while the alpha statistics are on.
    pub fn record_app_foreground(&self) -> Result<(), CoreError> {
        Err(not_yet("record_app_foreground"))
    }

    /// Crashes the OS reported for a Korean `day`, learned on a later launch: iOS MetricKit `MXCrashDiagnostic`,
    /// Android `ApplicationExitInfo` with `REASON_CRASH` or `REASON_CRASH_NATIVE` only. They add to that day, and the
    /// day goes to the server again, which keeps the newest copy. The app reports each crash once (Android remembers
    /// the newest exit it has counted).
    pub fn record_crashes(&self, day: String, count: u32) -> Result<(), CoreError> {
        let _ = (day, count);
        Err(not_yet("record_crashes"))
    }
}

/// The ticket of a `ReplaceRequired` sign-in, until `restore_from_code` or `start_fresh` spends it.
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

fn malformed(e: impl std::fmt::Display) -> CoreError {
    CoreError::Sync { reason: e.to_string() }
}

fn is_revoked(e: &CoreError) -> bool {
    matches!(e, CoreError::Api { status: 403, code, .. } if code == "device_revoked")
}

fn provider_of(name: &str) -> Option<Provider> {
    match name {
        KAKAO => Some(Provider::Kakao),
        APPLE => Some(Provider::Apple),
        _ => None,
    }
}

/// 1–40 characters after trimming, as the server takes it.
fn display_name(text: &str) -> Result<String, CoreError> {
    let name = text.trim();
    if name.is_empty() || name.chars().count() > 40 {
        return Err(CoreError::Invalid { reason: "a display name has 1 to 40 characters".into() });
    }
    Ok(name.to_owned())
}

impl Core {
    async fn sign_in_provider(
        self: Arc<Self>,
        provider: &'static str,
        nonce: String,
        id_token: String,
        account: NewAccount,
    ) -> Result<AuthResult, CoreError> {
        let name = display_name(&account.display_name)?;
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
                    self.make_recovery(&session.user_id, false).await?;
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

    /// The ticket of a replacement in progress, if it is still good.
    pub(crate) fn take_ticket(&self) -> Option<PendingReplace> {
        let pending = self.ticket.lock().unwrap_or_else(|p| p.into_inner()).take();
        pending.filter(|p| p.expires_at > Instant::now())
    }

    /// `POST /auth/replace`: this device becomes the active one and the other is signed out.
    pub(crate) async fn replace_device(&self, pending: PendingReplace) -> Result<Session, CoreError> {
        let secret = signing_secret(self.secrets.as_ref())?;
        let signature = MlsClient::sign_with(&secret, &login::replace_message(&pending.ticket))?;
        let outcome = self.api.replace(ReplaceRequest { ticket: pending.ticket, signature }).await?;
        let AuthOutcome::Registered { tokens, .. } = outcome else {
            return Err(malformed("a replacement asked for another replacement"));
        };
        if tokens.user_id != pending.user_id {
            return Err(malformed("the replacement registered another account"));
        }
        self.register_session(&tokens, None).await
    }

    /// Keeps the session, starts the engine with fresh key packages, and remembers the account.
    async fn register_session(
        &self,
        tokens: &geunal_sync::api::SessionInfo,
        display_name: Option<String>,
    ) -> Result<Session, CoreError> {
        let session = geunal_sync::session_of(tokens);
        self.secrets.put(SESSION.to_owned(), serde_json::to_vec(&session).map_err(malformed)?);
        let engine = build_engine(
            &self.store_dir,
            &self.store,
            &self.db_key,
            &session,
            self.secrets.as_ref(),
            self.api.clone(),
            self.listener.clone(),
        )?;
        engine.publish_key_packages(KEY_PACKAGES).await?;
        *self.state.lock().await = State::SignedIn(Box::new(engine));
        // An M0 server has no providers on /me; the name the sign-in sent stands in.
        let account = match self.api.me().await {
            Ok(me) => StoredAccount { user_id: me.user_id, display_name: me.display_name, providers: me.providers },
            Err(_) => StoredAccount {
                user_id: session.user_id.clone(),
                display_name: display_name.unwrap_or_default(),
                providers: Vec::new(),
            },
        };
        self.store_account(&account)?;
        self.store.write(|db| Ok::<_, CoreError>(db.delete_setting(REVOCATION)?))?;
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

    /// The code's entropy and the master key, when this device holds them for the signed-in account.
    pub(crate) fn recovery_secrets(&self) -> Result<Option<(Entropy, geunal_crypto::Key)>, CoreError> {
        let Some(session) = self.session() else { return Ok(None) };
        let owner = self.store.read(|db| db.setting(RECOVERY_OWNER))?;
        if owner.as_deref() != Some(session.user_id.as_str()) {
            return Ok(None);
        }
        let entropy = self.secrets.get(ENTROPY.to_owned()).and_then(|e| Entropy::try_from(e.as_slice()).ok());
        let amk = self.secrets.get(AMK.to_owned()).and_then(|k| geunal_crypto::Key::try_from(k.as_slice()).ok());
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
        self.secrets.put(ENTROPY.to_owned(), entropy.to_vec());
        self.secrets.put(AMK.to_owned(), amk.to_vec());
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

    /// Makes the recovery code and the master key when this device has none for the account, or always with
    /// `new_key` ("백업 없이 새로 시작"), and uploads the material. A failed upload stays pending for `sync()`, which
    /// sends `new_key` again. After a sign-in the failure does not undo the sign-in: the device is registered by then,
    /// and `sync()` reports what keeps failing. "백업 없이 새로 시작" reports it at once, as the server takes `new_key`
    /// only within 30 minutes of the provider sign-in.
    async fn make_recovery(&self, user_id: &str, new_key: bool) -> Result<(), CoreError> {
        if new_key || self.recovery_secrets()?.is_none() {
            self.keep_recovery(user_id, &recovery::new_entropy(), &geunal_crypto::random_key(), false, new_key)?;
        }
        match self.upload_recovery(new_key).await {
            Err(e) if !new_key && !is_revoked(&e) => Ok(()),
            other => other,
        }
    }

    /// The upload `sync()` retries until it succeeds (protocol §10 "올리는 때"). A network error waits for the next
    /// `sync()`, and so does a 404: an M0 server has no `/me/recovery` yet, and the M1 one never answers it with 404.
    /// Anything else is reported.
    pub(crate) async fn upload_pending_recovery(&self) -> Result<(), CoreError> {
        let (uploaded, new_key) = self.store.read(|db| {
            Ok::<_, CoreError>((db.setting(RECOVERY_UPLOADED)?.is_some(), db.setting(RECOVERY_NEW_KEY)?.is_some()))
        })?;
        if uploaded || self.recovery_secrets()?.is_none() {
            return Ok(());
        }
        match self.upload_recovery(new_key).await {
            Err(CoreError::Network { .. } | CoreError::Api { status: 404, .. }) => Ok(()),
            other => other,
        }
    }

    /// `PUT /me/recovery`. A retry may find its own earlier upload stored, which the same code then proves; an id
    /// another account already uses means a new `E` (409 `recovery_conflict`, protocol §11).
    async fn upload_recovery(&self, new_key: bool) -> Result<(), CoreError> {
        let session = self.session().ok_or(CoreError::NotSignedIn)?;
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
            };
            let result = match self.api.put_recovery(upload.clone()).await {
                Err(e) if !new_key && e.is(403, "recovery_proof_required") => {
                    let nonce = self.api.nonce().await?;
                    let proof = keys.sign_rotation(&nonce).to_vec();
                    self.api.put_recovery(RecoveryUpload { nonce: Some(nonce), proof: Some(proof), ..upload }).await
                }
                other => other,
            };
            match self.checked(result).await {
                Ok(()) => break,
                Err(CoreError::Api { status: 409, code, .. }) if code == "recovery_conflict" && conflicts == 0 => {
                    conflicts += 1;
                    self.keep_recovery(&session.user_id, &recovery::new_entropy(), &amk, false, new_key)?;
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

    /// A call made outside the engine: a 403 `device_revoked` signs this device out here too.
    pub(crate) async fn checked<R>(&self, result: Result<R, ApiError>) -> Result<R, CoreError> {
        match result {
            Err(ApiError::Revoked { replaced }) => {
                *self.state.lock().await = State::SignedOut;
                self.signed_out_by_another(replaced).await?;
                Err(revoked_error())
            }
            other => other.map_err(CoreError::from),
        }
    }

    /// After a realtime `revoke`: asks the server, whose 403 says when, where and how.
    pub(crate) async fn confirm_revocation(&self) -> Result<(), CoreError> {
        match self.api.me().await {
            Err(ApiError::Revoked { replaced }) => {
                *self.state.lock().await = State::SignedOut;
                self.signed_out_by_another(replaced).await
            }
            _ => Ok(()),
        }
    }

    /// Another device's sign-in signed this one out (protocol §13 "기기 해제"). The same secrets go as with an account
    /// deletion: the session, the device signing key, the code's entropy and the master key, and the group state.
    /// Events, people, birthdays and the database key that opens them stay. A stolen phone then cannot take a guest
    /// account back with its `E` or open a backup it downloaded.
    pub(crate) async fn signed_out_by_another(&self, replaced: Option<Replaced>) -> Result<(), CoreError> {
        self.api.clear_session();
        *self.realtime.lock().await = None;
        for name in [SESSION, SIGNING_KEY, ENTROPY, AMK] {
            self.secrets.delete(name.to_owned());
        }
        *self.recovery_keys.lock().unwrap_or_else(|p| p.into_inner()) = None;
        let revocation = replaced
            .map(|r| StoredRevocation { at: r.replaced_at, by: r.replaced_by, via: r.replaced_via })
            .map(|r| serde_json::to_string(&r).map_err(malformed))
            .transpose()?;
        self.store.write(|db| {
            db.wipe_group_state()?;
            for key in [ACCOUNT, RECOVERY_SAVED, RECOVERY_UPLOADED, RECOVERY_NEW_KEY, RECOVERY_OWNER] {
                db.delete_setting(key)?;
            }
            if let Some(text) = &revocation {
                db.set_setting(REVOCATION, text)?;
            }
            Ok::<_, CoreError>(())
        })?;
        self.listener.on_change(Change::Account);
        Ok(())
    }
}

/// Argon2id at 64 MiB takes a second or more on a phone, so it runs off the core's two async workers.
pub(crate) async fn derive(entropy: Entropy) -> Result<RecoveryKeys, CoreError> {
    tokio::task::spawn_blocking(move || RecoveryKeys::derive(&entropy))
        .await
        .map_err(|e| malformed(format!("derivation task: {e}")))?
        .map_err(malformed)
}

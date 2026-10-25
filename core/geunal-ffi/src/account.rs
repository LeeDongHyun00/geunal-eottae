//! The M1 account surface (docs/design/m1.md screens 2, 12, 13, 20–22; protocol §10, §13, §14): signing in with
//! Kakao, Apple or as a guest, replacing a device, the recovery code, encrypted backups, account deletion and the
//! alpha statistics.
//!
//! The secrets this needs (the recovery code's entropy `E`, the account master key, the device signing key) live in
//! the app's `SecretStore` only. A replacement ticket lives in memory: it is good for 30 minutes and a restarted app
//! signs in again.

use std::sync::Arc;
use std::time::SystemTime;

use crate::{Core, CoreError, Session, not_yet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Provider {
    Kakao,
    Apple,
}

/// The value a provider sign-in is bound to (protocol §13): Kakao gets `nonce` as it is, Apple gets `nonce_sha256`
/// (lowercase hex). The same `nonce` then goes to `sign_in_kakao`, `sign_in_apple` or `link_*`, which sign it with
/// the device key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SignInNonce {
    pub nonce: String,
    pub nonce_sha256: String,
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
    /// The code exists on this device (made at the first sign-in, or entered when restoring).
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
        Err(not_yet("sign_in_nonce"))
    }

    /// Signs in with a Kakao OIDC ID token issued for `nonce`.
    pub async fn sign_in_kakao(
        self: Arc<Self>,
        nonce: String,
        id_token: String,
        account: NewAccount,
    ) -> Result<AuthResult, CoreError> {
        let _ = (nonce, id_token, account);
        Err(not_yet("sign_in_kakao"))
    }

    /// Signs in with an Apple identity token issued for SHA-256(`nonce`). `authorization_code` lets the server revoke
    /// the Apple tokens when the account is deleted (App Store Review Guideline 5.1.1(v)).
    pub async fn sign_in_apple(
        self: Arc<Self>,
        nonce: String,
        id_token: String,
        authorization_code: String,
        account: NewAccount,
    ) -> Result<AuthResult, CoreError> {
        let _ = (nonce, id_token, authorization_code, account);
        Err(not_yet("sign_in_apple"))
    }

    /// Creates a guest account and registers this device. A guest always gets a new account.
    pub async fn sign_in_guest(self: Arc<Self>, account: NewAccount) -> Result<Session, CoreError> {
        let _ = account;
        Err(not_yet("sign_in_guest"))
    }

    /// Restores with the recovery code: unwraps the account master key, downloads and opens the newest backup and
    /// fills this device, then makes it the active device. It covers the three ways a device gets here: a
    /// `ReplaceRequired` sign-in (with its ticket), a `Registered` sign-in with `recovery`, and a guest coming back
    /// without any sign-in (`POST /auth/recover`, protocol §13). Fails with `WrongRecoveryCode` when the words are not
    /// this account's code.
    pub async fn restore_from_code(
        self: Arc<Self>,
        words: Vec<String>,
        progress: Option<Arc<dyn ProgressListener>>,
    ) -> Result<RestoreSummary, CoreError> {
        let _ = (words, progress);
        Err(not_yet("restore_from_code"))
    }

    /// "백업 없이 새로 시작": finishes a replacement without the code. A new code and master key are made, and the
    /// account's backups are deleted on the server because nobody can open them any more (protocol §10).
    pub async fn start_fresh(self: Arc<Self>) -> Result<Session, CoreError> {
        Err(not_yet("start_fresh"))
    }

    /// Links a guest account to Kakao; afterwards the account no longer comes back by recovery code alone.
    pub async fn link_kakao(self: Arc<Self>, nonce: String, id_token: String) -> Result<Vec<Provider>, CoreError> {
        let _ = (nonce, id_token);
        Err(not_yet("link_kakao"))
    }

    pub async fn link_apple(
        self: Arc<Self>,
        nonce: String,
        id_token: String,
        authorization_code: String,
    ) -> Result<Vec<Provider>, CoreError> {
        let _ = (nonce, id_token, authorization_code);
        Err(not_yet("link_apple"))
    }

    /// The signed-in account as this device knows it; `None` before a sign-in.
    pub fn account(&self) -> Result<Option<AccountView>, CoreError> {
        Err(not_yet("account"))
    }

    pub fn me_summary(&self) -> Result<MeSummary, CoreError> {
        Err(not_yet("me_summary"))
    }

    /// Deletes the account on the server (`DELETE /me`), then the session, keys and group state on this device.
    /// Events, calendars and people stay, and the app keeps working as a calendar without groups (m1.md 2.5).
    pub async fn delete_account(self: Arc<Self>) -> Result<(), CoreError> {
        Err(not_yet("delete_account"))
    }

    // ----- recovery code (screens 13, 21; protocol §10) -----

    /// The twelve words of this account's recovery code, in Hangul (NFC). Made on the first call and the same from
    /// then on.
    pub fn recovery_words(&self) -> Result<Vec<String>, CoreError> {
        Err(not_yet("recovery_words"))
    }

    pub fn recovery_status(&self) -> Result<RecoveryStatus, CoreError> {
        Err(not_yet("recovery_status"))
    }

    /// "종이에 적었어요": the banner and the "저장 안 함" label go away.
    pub fn mark_recovery_saved(&self) -> Result<(), CoreError> {
        Err(not_yet("mark_recovery_saved"))
    }

    /// The word as it is in the list when `text` (trimmed, any Unicode normalization) is one of the 2048 words, else
    /// `None`: the input cell turns red.
    pub fn check_recovery_word(&self, text: String) -> Result<Option<String>, CoreError> {
        let _ = text;
        Err(not_yet("check_recovery_word"))
    }

    /// Words of the list that start with `prefix`, for the input cell's completion. Two syllables always single one
    /// out (protocol §10).
    pub fn recovery_completions(&self, prefix: String) -> Result<Vec<String>, CoreError> {
        let _ = prefix;
        Err(not_yet("recovery_completions"))
    }

    /// The words of a pasted code in order, with numbering, punctuation and line breaks dropped.
    pub fn split_recovery_code(&self, text: String) -> Result<Vec<String>, CoreError> {
        let _ = text;
        Err(not_yet("split_recovery_code"))
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
}

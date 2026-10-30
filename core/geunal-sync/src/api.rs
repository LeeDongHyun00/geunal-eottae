//! What the core asks of the API and the shapes it expects back: the byte-level contract is `server/api/openapi.yaml`
//! (api-server.md §7 lists the endpoints). Field names are snake_case, bytes travel as standard base64 with padding,
//! and errors follow protocol §11: `{"error": code, "message": ...}`.

use std::future::Future;

use serde::{Deserialize, Serialize};

/// A failed call. `Api` is a response the server chose to send; the rest never reached a decision.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ApiError {
    #[error("{status} {code}: {message}")]
    Api { status: u16, code: String, message: String },
    /// 403 `device_revoked`: another sign-in made a different device the active one (protocol §13 "기기 해제").
    /// `replaced` says when, where and how, from M1; `None` from an M0 server or for a revocation of another kind.
    #[error("403 device_revoked")]
    Revoked { replaced: Option<Replaced> },
    #[error("network: {0}")]
    Network(String),
    #[error("unexpected response: {0}")]
    Malformed(String),
}

impl ApiError {
    pub fn is(&self, status: u16, code: &str) -> bool {
        match self {
            ApiError::Api { status: s, code: c, .. } => *s == status && c == code,
            ApiError::Revoked { .. } => status == 403 && code == "device_revoked",
            _ => false,
        }
    }

    pub fn code(&self) -> Option<&str> {
        match self {
            ApiError::Api { code, .. } => Some(code),
            ApiError::Revoked { .. } => Some("device_revoked"),
            _ => None,
        }
    }
}

/// The extra fields of a 403 `device_revoked` after a device replacement.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Replaced {
    /// RFC 3339.
    pub replaced_at: String,
    /// `ios` or `android`: the account's active device now.
    pub replaced_by: String,
    /// `provider` (a Kakao or Apple sign-in) or `recovery` (a guest coming back with the code).
    pub replaced_via: String,
}

/// `POST /auth/stub`: a device registering with its Ed25519 key and the proof over the server's nonce. `subject`
/// stands for the identity Kakao or Apple would assert; the same subject is the same account.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StubSignIn {
    pub subject: String,
    pub display_name: String,
    /// `ios` or `android`.
    pub platform: String,
    #[serde(with = "b64")]
    pub signature_key: Vec<u8>,
    pub nonce: String,
    #[serde(with = "b64")]
    pub signature: Vec<u8>,
}

/// `Tokens`: what a sign-in returns; the transport keeps the token, the engine the identity.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SessionInfo {
    pub user_id: String,
    pub device_id: String,
    pub access_token: String,
    #[serde(default)]
    pub expires_in: i64,
    pub refresh_token: String,
}

/// What every M1 sign-in proves: this device holds the Ed25519 key it registers (`DeviceProof`, protocol §13).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeviceProof {
    /// `ios` or `android`.
    pub platform: String,
    #[serde(with = "b64")]
    pub signature_key: Vec<u8>,
    pub nonce: String,
    #[serde(with = "b64")]
    pub signature: Vec<u8>,
}

/// Read only when the sign-in creates the account (`NewAccount`).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct NewAccountFields {
    /// RFC 3339: when the user ticked "만 14세 이상이에요".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub age_confirmed_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
}

/// `POST /auth/kakao` and `POST /auth/apple`: the provider's ID token, whose `nonce` claim binds it to `proof`'s key.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderSignIn {
    #[serde(flatten)]
    pub proof: DeviceProof,
    #[serde(flatten)]
    pub account: NewAccountFields,
    pub id_token: String,
}

/// `POST /auth/guest`: always a new account.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct GuestSignIn {
    #[serde(flatten)]
    pub proof: DeviceProof,
    pub age_confirmed_at: String,
    pub display_name: String,
}

/// `POST /auth/recover`: a guest coming back with the recovery code (protocol §10, §13).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RecoverSignIn {
    #[serde(flatten)]
    pub proof: DeviceProof,
    #[serde(with = "b64")]
    pub recovery_id: Vec<u8>,
    #[serde(with = "b64")]
    pub recovery_signature: Vec<u8>,
}

/// `POST /auth/replace`: the ticket of a `replace_required` sign-in, signed by the key that sign-in proved.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ReplaceRequest {
    pub ticket: String,
    #[serde(with = "b64")]
    pub signature: Vec<u8>,
}

/// The account's active device when a sign-in needs a replacement.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ActiveDevice {
    pub platform: String,
    /// RFC 3339.
    pub created_at: String,
}

/// What a sign-in answers (`Registered` or `ReplaceRequired`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthOutcome {
    /// This device is the account's active device. `recovery` says the server holds recovery material.
    Registered { tokens: SessionInfo, new_account: bool, recovery: bool },
    /// Another device is active; nothing is registered until `POST /auth/replace` with the ticket.
    ReplaceRequired { user_id: String, ticket: String, expires_in: i64, active_device: ActiveDevice, recovery: bool },
}

impl<'de> Deserialize<'de> for AuthOutcome {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        struct Wire {
            #[serde(default)]
            result: Option<String>,
            #[serde(default)]
            new_account: bool,
            #[serde(default)]
            recovery: bool,
            #[serde(default)]
            ticket: Option<String>,
            #[serde(default)]
            expires_in: i64,
            #[serde(default)]
            active_device: Option<ActiveDevice>,
            #[serde(flatten)]
            rest: serde_json::Map<String, serde_json::Value>,
        }
        let wire = Wire::deserialize(d)?;
        match wire.result.as_deref() {
            Some("replace_required") => {
                let user_id = wire.rest.get("user_id").and_then(|v| v.as_str()).map(str::to_owned);
                Ok(AuthOutcome::ReplaceRequired {
                    user_id: user_id.ok_or_else(|| serde::de::Error::missing_field("user_id"))?,
                    ticket: wire.ticket.ok_or_else(|| serde::de::Error::missing_field("ticket"))?,
                    expires_in: wire.expires_in,
                    active_device: wire
                        .active_device
                        .ok_or_else(|| serde::de::Error::missing_field("active_device"))?,
                    recovery: wire.recovery,
                })
            }
            // An M0 server answers bare tokens without `result`: registered, and it says nothing about the account.
            Some("registered") | None => {
                let mut tokens = wire.rest;
                tokens.insert("expires_in".into(), wire.expires_in.into());
                let tokens: SessionInfo =
                    serde_json::from_value(serde_json::Value::Object(tokens)).map_err(serde::de::Error::custom)?;
                Ok(AuthOutcome::Registered { tokens, new_account: wire.new_account, recovery: wire.recovery })
            }
            Some(other) => Err(serde::de::Error::unknown_variant(other, &["registered", "replace_required"])),
        }
    }
}

/// `POST /auth/link`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LinkRequest {
    /// `kakao` or `apple`.
    pub provider: String,
    pub id_token: String,
    pub nonce: String,
}

/// `GET /me`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MeInfo {
    pub user_id: String,
    pub display_name: String,
    #[serde(default)]
    pub key_packages: i64,
    /// `kakao`, `apple`; empty for a guest. From M1.
    #[serde(default)]
    pub providers: Vec<String>,
    /// The server holds recovery material. From M1.
    #[serde(default)]
    pub recovery: bool,
}

/// `PUT /me/recovery` (protocol §10).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RecoveryUpload {
    #[serde(with = "b64")]
    pub amk_wrapped: Vec<u8>,
    #[serde(with = "b64")]
    pub recovery_id: Vec<u8>,
    #[serde(with = "b64")]
    pub recovery_public_key: Vec<u8>,
    /// "백업 없이 새로 시작" without the code: within 30 minutes of a provider sign-in, or with a fresh provider proof
    /// below. The server deletes the old backups.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub new_key: bool,
    /// From `GET /auth/nonce`, with `proof` or `kakao_id_token`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub nonce: Option<String>,
    /// The current code's signature, to overwrite material already stored.
    #[serde(default, with = "b64::option", skip_serializing_if = "Option::is_none")]
    pub proof: Option<Vec<u8>>,
    /// `new_key` past the 30 minutes, Kakao: an ID token bound to `nonce` and this device's registered key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kakao_id_token: Option<String>,
    /// `new_key` past the 30 minutes, Apple: a fresh Sign in with Apple authorization code.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apple_authorization_code: Option<String>,
}

/// `GET /me/recovery`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredRecovery {
    #[serde(with = "b64")]
    pub amk_wrapped: Vec<u8>,
    #[serde(default)]
    pub updated_at: String,
}

/// `POST /me/backups`: where to put a sealed object of `size` bytes. The URL is signed with that length.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BackupUpload {
    pub id: i64,
    pub upload_url: String,
    #[serde(default)]
    pub expires_in: i64,
}

/// A completed backup as the server knows it: id, size and time only (protocol §10 "목록").
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BackupInfo {
    pub id: i64,
    pub size: u64,
    /// RFC 3339: when the upload was completed.
    pub created_at: String,
}

/// `GET /me/backups/latest`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BackupDownload {
    #[serde(flatten)]
    pub backup: BackupInfo,
    pub download_url: String,
    #[serde(default)]
    pub expires_in: i64,
}

/// Bytes moved so far and in all, for a progress bar.
pub type Progress<'a> = &'a (dyn Fn(u64, u64) + Send + Sync);

/// `DELETE /me`: the account proven again by its kind (protocol §13 "계정 삭제"). A guest fills `nonce` and
/// `recovery_signature`, a Kakao account `nonce` and `kakao_id_token`, an Apple account `apple_authorization_code`.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeleteAccount {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub nonce: Option<String>,
    /// The recovery key's signature over `"geunal delete v1" ‖ nonce`.
    #[serde(default, with = "b64::option", skip_serializing_if = "Option::is_none")]
    pub recovery_signature: Option<Vec<u8>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kakao_id_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apple_authorization_code: Option<String>,
}

/// `GET /holidays` (protocol §14): of the years asked, those the server knows in full, and every holiday of them.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct HolidayYears {
    pub years: Vec<i32>,
    pub holidays: Vec<HolidayItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct HolidayItem {
    /// `YYYY-MM-DD`.
    pub day: String,
    pub name: String,
    /// 대체공휴일.
    pub substitute: bool,
}

/// One Korean day of `POST /me/alpha-stats` (protocol §14).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AlphaDay {
    pub day: String,
    pub sessions: u32,
    pub events: u32,
    pub suggested: u32,
    pub crashes: u32,
}

/// One row of `GET /users/{id}/devices`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeviceInfo {
    pub id: String,
    #[serde(default)]
    pub platform: String,
    #[serde(with = "b64")]
    pub signature_key: Vec<u8>,
}

/// One row of `GET /me/groups`, and the body of `POST /groups` (role and status absent there).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct GroupSummary {
    pub id: String,
    pub kind: String,
    pub epoch: u64,
    pub last_seq: i64,
    #[serde(default)]
    pub role: String,
    /// `pending` or `active`; `POST /groups` leaves it empty.
    #[serde(default)]
    pub status: String,
}

/// One row of `GET /groups/{id}/members`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MemberInfo {
    pub user_id: String,
    #[serde(default)]
    pub display_name: String,
    /// The member's active device, if any.
    #[serde(default)]
    pub device_id: Option<String>,
    pub role: String,
    /// `pending`, `active` or `removed`.
    pub status: String,
}

/// `POST /groups/{id}/commits`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CommitRequest {
    /// The epoch the commit was built in; the server accepts one commit per epoch.
    pub epoch: u64,
    #[serde(with = "b64")]
    pub commit: Vec<u8>,
    /// GroupInfo of the epoch the commit creates.
    #[serde(with = "b64")]
    pub group_info: Vec<u8>,
    #[serde(default, with = "b64::option", skip_serializing_if = "Option::is_none")]
    pub welcome: Option<Vec<u8>>,
    /// Devices the commit adds; the server marks their users active and files the Welcome for them.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub added: Vec<String>,
}

/// `201` from a commit.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CommitAccepted {
    pub seq: i64,
    pub epoch: u64,
    /// The server time stored with the entry. Optional on the wire so a server from before the field existed still
    /// parses; the log echo carries the same value either way.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub received_at: Option<String>,
}

/// `201` from a message.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MessageAccepted {
    pub seq: i64,
    /// The server time stored with the entry (protocol §4): the sender keeps its own entry under this clock. Optional
    /// for the same reason as in [`CommitAccepted`]; without it the log echo supplies the time later.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub received_at: Option<String>,
}

/// Who the server should notify (protocol §8).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case", untagged)]
pub enum AlertTarget {
    Everyone(AllOrNone),
    Users(Vec<String>),
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AllOrNone {
    All,
    None,
}

/// `POST /groups/{id}/messages`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MessageRequest {
    pub epoch: u64,
    #[serde(with = "b64")]
    pub message: Vec<u8>,
    pub alert: AlertTarget,
    /// `chat` or `plan` when `alert` is not `none`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cat: Option<String>,
}

/// One row of `GET /groups/{id}/log`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LogItem {
    pub seq: i64,
    pub epoch: u64,
    /// `commit` or `app`.
    pub kind: String,
    pub author_id: String,
    #[serde(with = "b64")]
    pub ciphertext: Vec<u8>,
    pub received_at: String,
}

/// `GET /groups/{id}/log`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LogPage {
    pub entries: Vec<LogItem>,
    /// The newest seq in the group; the device is caught up when the entries end here.
    pub last_seq: i64,
}

/// `GET` and `PUT /groups/{id}/group-info`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct GroupInfo {
    /// The epoch the GroupInfo describes.
    pub epoch: u64,
    #[serde(with = "b64")]
    pub group_info: Vec<u8>,
}

/// One row of `GET /me/welcomes`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WelcomeItem {
    pub group_id: String,
    /// The seq of the Add commit that brought this device in: the Welcome stands for that commit, so the log is read
    /// from the item after it. Absent from a server before the field existed, when the read starts at 0.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seq: Option<i64>,
    #[serde(with = "b64")]
    pub data: Vec<u8>,
}

/// `PUT /groups/{id}/state/{kind}`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PutState {
    pub epoch: u64,
    #[serde(with = "b64")]
    pub ciphertext: Vec<u8>,
    /// `group_meta` only: the version the writer read, 0 for none (api-server.md §5).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_version: Option<i64>,
}

/// One row of `GET /groups/{id}/state`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StateBlob {
    pub kind: String,
    pub owner_id: String,
    pub epoch: u64,
    pub version: i64,
    #[serde(with = "b64")]
    pub ciphertext: Vec<u8>,
    #[serde(default)]
    pub updated_at: String,
}

/// The largest page of `GET /groups/{id}/log` (api-server.md §5).
pub const LOG_PAGE: u32 = 500;

/// Told the new tokens as soon as a refresh spends the old refresh token, so they are kept before anything else runs:
/// the server ends every session of a device that presents a spent refresh token (protocol §13).
pub type TokenListener = std::sync::Arc<dyn Fn(&SessionInfo) + Send + Sync>;

/// The API as the engine sees it. One implementation speaks HTTP, another is the in-process fake server; both keep
/// the caller's session after a sign-in that registers this device, and both trade an expired access token for a new
/// one once per call (`POST /auth/refresh`) before they report `token_expired`.
pub trait Transport: Send + Sync {
    fn nonce(&self) -> impl Future<Output = Result<String, ApiError>> + Send;
    /// `POST /auth/stub`, on local and CI servers only. From M1 it follows the Kakao rules: a second device gets a
    /// replacement ticket.
    fn sign_in(&self, req: StubSignIn) -> impl Future<Output = Result<AuthOutcome, ApiError>> + Send;
    /// `POST /auth/kakao` or `POST /auth/apple`; `provider` is `kakao` or `apple`.
    fn sign_in_provider(
        &self,
        provider: &str,
        req: ProviderSignIn,
    ) -> impl Future<Output = Result<AuthOutcome, ApiError>> + Send;
    fn sign_in_guest(&self, req: GuestSignIn) -> impl Future<Output = Result<AuthOutcome, ApiError>> + Send;
    fn recover(&self, req: RecoverSignIn) -> impl Future<Output = Result<AuthOutcome, ApiError>> + Send;
    fn replace(&self, req: ReplaceRequest) -> impl Future<Output = Result<AuthOutcome, ApiError>> + Send;
    /// The account's providers after linking.
    fn link(&self, req: LinkRequest) -> impl Future<Output = Result<Vec<String>, ApiError>> + Send;
    fn me(&self) -> impl Future<Output = Result<MeInfo, ApiError>> + Send;
    fn put_recovery(&self, req: RecoveryUpload) -> impl Future<Output = Result<(), ApiError>> + Send;
    /// With `ticket`, as the bearer of a replacement in progress; otherwise with the session.
    fn get_recovery(&self, ticket: Option<&str>) -> impl Future<Output = Result<StoredRecovery, ApiError>> + Send;
    /// `POST /me/backups`.
    fn start_backup(&self, size: u64) -> impl Future<Output = Result<BackupUpload, ApiError>> + Send;
    /// `PUT` to a presigned URL, which carries its own authorization: the session's token never goes there.
    fn put_object(&self, url: &str, body: Vec<u8>) -> impl Future<Output = Result<(), ApiError>> + Send;
    /// `POST /me/backups/{id}/complete`.
    fn complete_backup(&self, id: i64) -> impl Future<Output = Result<BackupInfo, ApiError>> + Send;
    /// `GET /me/backups`, newest first; with `ticket` as for `get_recovery`.
    fn backups(&self, ticket: Option<&str>) -> impl Future<Output = Result<Vec<BackupInfo>, ApiError>> + Send;
    /// `GET /me/backups/latest`; `None` when the account has no completed backup.
    fn latest_backup(
        &self,
        ticket: Option<&str>,
    ) -> impl Future<Output = Result<Option<BackupDownload>, ApiError>> + Send;
    /// `GET` from a presigned URL, reporting the bytes as they arrive.
    fn get_object(&self, url: &str, progress: Progress<'_>) -> impl Future<Output = Result<Vec<u8>, ApiError>> + Send;
    /// `DELETE /me`. After it the session's tokens no longer work.
    fn delete_account(&self, req: DeleteAccount) -> impl Future<Output = Result<(), ApiError>> + Send;
    /// `GET /holidays` for whole years, at most two, without the session.
    fn holidays(&self, from_year: i32, to_year: i32) -> impl Future<Output = Result<HolidayYears, ApiError>> + Send;
    /// `POST /me/alpha-stats`; a day sent again replaces what the server had for it.
    fn post_alpha_stats(&self, days: Vec<AlphaDay>) -> impl Future<Output = Result<(), ApiError>> + Send;
    /// Reuses a session from a previous run.
    fn restore_session(&self, access_token: &str, refresh_token: &str);
    /// Forgets the session: signed out by another device, or the account is gone.
    fn clear_session(&self);
    fn set_token_listener(&self, listener: TokenListener);
    fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> impl Future<Output = Result<(), ApiError>> + Send;
    fn devices(&self, user_id: &str) -> impl Future<Output = Result<Vec<DeviceInfo>, ApiError>> + Send;
    fn claim_key_package(&self, device_id: &str) -> impl Future<Output = Result<Vec<u8>, ApiError>> + Send;
    fn my_groups(&self) -> impl Future<Output = Result<Vec<GroupSummary>, ApiError>> + Send;
    /// The API mints the group id; its 16 bytes become the MLS group id (protocol §2).
    fn create_group(&self) -> impl Future<Output = Result<GroupSummary, ApiError>> + Send;
    /// The epoch-0 GroupInfo, published once right after the MLS group exists; commits carry later ones.
    fn put_group_info(&self, group_id: &str, info: GroupInfo) -> impl Future<Output = Result<(), ApiError>> + Send;
    fn members(&self, group_id: &str) -> impl Future<Output = Result<Vec<MemberInfo>, ApiError>> + Send;
    fn remove_member(&self, group_id: &str, user_id: &str) -> impl Future<Output = Result<(), ApiError>> + Send;
    fn post_commit(
        &self,
        group_id: &str,
        req: CommitRequest,
    ) -> impl Future<Output = Result<CommitAccepted, ApiError>> + Send;
    fn post_message(
        &self,
        group_id: &str,
        req: MessageRequest,
    ) -> impl Future<Output = Result<MessageAccepted, ApiError>> + Send;
    fn log(&self, group_id: &str, after: i64, limit: u32) -> impl Future<Output = Result<LogPage, ApiError>> + Send;
    fn group_info(&self, group_id: &str) -> impl Future<Output = Result<GroupInfo, ApiError>> + Send;
    fn welcomes(&self) -> impl Future<Output = Result<Vec<WelcomeItem>, ApiError>> + Send;
    fn put_state(
        &self,
        group_id: &str,
        kind: &str,
        req: PutState,
    ) -> impl Future<Output = Result<i64, ApiError>> + Send;
    fn state(&self, group_id: &str) -> impl Future<Output = Result<Vec<StateBlob>, ApiError>> + Send;
}

/// A shared transport: the app signs in through the same client the engine then uses.
impl<T: Transport> Transport for std::sync::Arc<T> {
    async fn nonce(&self) -> Result<String, ApiError> {
        (**self).nonce().await
    }
    async fn sign_in(&self, req: StubSignIn) -> Result<AuthOutcome, ApiError> {
        (**self).sign_in(req).await
    }
    async fn sign_in_provider(&self, provider: &str, req: ProviderSignIn) -> Result<AuthOutcome, ApiError> {
        (**self).sign_in_provider(provider, req).await
    }
    async fn sign_in_guest(&self, req: GuestSignIn) -> Result<AuthOutcome, ApiError> {
        (**self).sign_in_guest(req).await
    }
    async fn recover(&self, req: RecoverSignIn) -> Result<AuthOutcome, ApiError> {
        (**self).recover(req).await
    }
    async fn replace(&self, req: ReplaceRequest) -> Result<AuthOutcome, ApiError> {
        (**self).replace(req).await
    }
    async fn link(&self, req: LinkRequest) -> Result<Vec<String>, ApiError> {
        (**self).link(req).await
    }
    async fn me(&self) -> Result<MeInfo, ApiError> {
        (**self).me().await
    }
    async fn put_recovery(&self, req: RecoveryUpload) -> Result<(), ApiError> {
        (**self).put_recovery(req).await
    }
    async fn get_recovery(&self, ticket: Option<&str>) -> Result<StoredRecovery, ApiError> {
        (**self).get_recovery(ticket).await
    }
    async fn start_backup(&self, size: u64) -> Result<BackupUpload, ApiError> {
        (**self).start_backup(size).await
    }
    async fn put_object(&self, url: &str, body: Vec<u8>) -> Result<(), ApiError> {
        (**self).put_object(url, body).await
    }
    async fn complete_backup(&self, id: i64) -> Result<BackupInfo, ApiError> {
        (**self).complete_backup(id).await
    }
    async fn backups(&self, ticket: Option<&str>) -> Result<Vec<BackupInfo>, ApiError> {
        (**self).backups(ticket).await
    }
    async fn latest_backup(&self, ticket: Option<&str>) -> Result<Option<BackupDownload>, ApiError> {
        (**self).latest_backup(ticket).await
    }
    async fn get_object(&self, url: &str, progress: Progress<'_>) -> Result<Vec<u8>, ApiError> {
        (**self).get_object(url, progress).await
    }
    async fn delete_account(&self, req: DeleteAccount) -> Result<(), ApiError> {
        (**self).delete_account(req).await
    }
    async fn holidays(&self, from_year: i32, to_year: i32) -> Result<HolidayYears, ApiError> {
        (**self).holidays(from_year, to_year).await
    }
    async fn post_alpha_stats(&self, days: Vec<AlphaDay>) -> Result<(), ApiError> {
        (**self).post_alpha_stats(days).await
    }
    fn restore_session(&self, access_token: &str, refresh_token: &str) {
        (**self).restore_session(access_token, refresh_token)
    }
    fn clear_session(&self) {
        (**self).clear_session()
    }
    fn set_token_listener(&self, listener: TokenListener) {
        (**self).set_token_listener(listener)
    }
    async fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> Result<(), ApiError> {
        (**self).upload_key_packages(packages).await
    }
    async fn devices(&self, user_id: &str) -> Result<Vec<DeviceInfo>, ApiError> {
        (**self).devices(user_id).await
    }
    async fn claim_key_package(&self, device_id: &str) -> Result<Vec<u8>, ApiError> {
        (**self).claim_key_package(device_id).await
    }
    async fn my_groups(&self) -> Result<Vec<GroupSummary>, ApiError> {
        (**self).my_groups().await
    }
    async fn create_group(&self) -> Result<GroupSummary, ApiError> {
        (**self).create_group().await
    }
    async fn put_group_info(&self, group_id: &str, info: GroupInfo) -> Result<(), ApiError> {
        (**self).put_group_info(group_id, info).await
    }
    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        (**self).members(group_id).await
    }
    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        (**self).remove_member(group_id, user_id).await
    }
    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<CommitAccepted, ApiError> {
        (**self).post_commit(group_id, req).await
    }
    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<MessageAccepted, ApiError> {
        (**self).post_message(group_id, req).await
    }
    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<LogPage, ApiError> {
        (**self).log(group_id, after, limit).await
    }
    async fn group_info(&self, group_id: &str) -> Result<GroupInfo, ApiError> {
        (**self).group_info(group_id).await
    }
    async fn welcomes(&self) -> Result<Vec<WelcomeItem>, ApiError> {
        (**self).welcomes().await
    }
    async fn put_state(&self, group_id: &str, kind: &str, req: PutState) -> Result<i64, ApiError> {
        (**self).put_state(group_id, kind, req).await
    }
    async fn state(&self, group_id: &str) -> Result<Vec<StateBlob>, ApiError> {
        (**self).state(group_id).await
    }
}

/// Standard base64 with padding for byte fields in JSON.
pub mod b64 {
    use base64::Engine as _;
    use base64::engine::general_purpose::STANDARD;
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn encode(bytes: &[u8]) -> String {
        STANDARD.encode(bytes)
    }

    pub fn decode(text: &str) -> Option<Vec<u8>> {
        STANDARD.decode(text).ok()
    }

    pub fn serialize<S: Serializer>(bytes: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&encode(bytes))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let text = String::deserialize(d)?;
        decode(&text).ok_or_else(|| serde::de::Error::custom("invalid base64"))
    }

    pub mod option {
        use serde::{Deserialize, Deserializer, Serializer};

        pub fn serialize<S: Serializer>(bytes: &Option<Vec<u8>>, s: S) -> Result<S::Ok, S::Error> {
            match bytes {
                Some(b) => s.serialize_some(&super::encode(b)),
                None => s.serialize_none(),
            }
        }

        pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Vec<u8>>, D::Error> {
            let text = Option::<String>::deserialize(d)?;
            text.map(|t| super::decode(&t).ok_or_else(|| serde::de::Error::custom("invalid base64"))).transpose()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alert_targets_serialize_as_the_protocol_writes_them() {
        assert_eq!(serde_json::to_string(&AlertTarget::Everyone(AllOrNone::All)).unwrap(), r#""all""#);
        assert_eq!(serde_json::to_string(&AlertTarget::Everyone(AllOrNone::None)).unwrap(), r#""none""#);
        assert_eq!(serde_json::to_string(&AlertTarget::Users(vec!["u1".into()])).unwrap(), r#"["u1"]"#);
        assert_eq!(serde_json::from_str::<AlertTarget>(r#""none""#).unwrap(), AlertTarget::Everyone(AllOrNone::None));
    }

    #[test]
    fn bytes_travel_as_base64_and_names_as_snake_case() {
        let req = PutState { epoch: 3, ciphertext: vec![1, 2, 3], base_version: None };
        assert_eq!(serde_json::to_string(&req).unwrap(), r#"{"epoch":3,"ciphertext":"AQID"}"#);
        let req = CommitRequest { epoch: 1, commit: vec![1], group_info: vec![2], welcome: None, added: vec![] };
        assert_eq!(serde_json::to_string(&req).unwrap(), r#"{"epoch":1,"commit":"AQ==","group_info":"Ag=="}"#);
        let page: LogPage = serde_json::from_str(
            r#"{"entries":[{"seq":1,"epoch":0,"kind":"app","author_id":"u","ciphertext":"AQID","received_at":"2026-11-01T00:00:00.123456Z"}],"last_seq":1}"#,
        )
        .unwrap();
        assert_eq!(page.entries[0].ciphertext, vec![1, 2, 3]);
        let tokens: SessionInfo = serde_json::from_str(
            r#"{"user_id":"u","device_id":"d","access_token":"t","expires_in":900,"refresh_token":"r"}"#,
        )
        .unwrap();
        assert_eq!(tokens.expires_in, 900);
    }

    #[test]
    fn reads_the_server_time_and_welcome_seq_when_present_and_copes_without_them() {
        let accepted: MessageAccepted =
            serde_json::from_str(r#"{"seq":7,"received_at":"2026-11-01T00:00:00.123456Z"}"#).unwrap();
        assert_eq!(accepted.received_at.as_deref(), Some("2026-11-01T00:00:00.123456Z"));
        let accepted: CommitAccepted = serde_json::from_str(r#"{"seq":2,"epoch":1}"#).unwrap();
        assert_eq!(accepted.received_at, None);
        let welcome: WelcomeItem = serde_json::from_str(r#"{"group_id":"g","seq":2,"data":"AQ=="}"#).unwrap();
        assert_eq!(welcome.seq, Some(2));
        let welcome: WelcomeItem = serde_json::from_str(r#"{"group_id":"g","data":"AQ=="}"#).unwrap();
        assert_eq!(welcome.seq, None);
    }

    #[test]
    fn reads_both_sign_in_results_and_the_bare_tokens_of_an_m0_server() {
        let tokens = r#""user_id":"u","device_id":"d","access_token":"t","expires_in":900,"refresh_token":"r""#;
        let registered: AuthOutcome =
            serde_json::from_str(&format!(r#"{{"result":"registered",{tokens},"new_account":true,"recovery":false}}"#))
                .unwrap();
        let AuthOutcome::Registered { tokens: session, new_account, recovery } = registered else { panic!() };
        assert_eq!((session.device_id.as_str(), session.expires_in, new_account, recovery), ("d", 900, true, false));
        let m0: AuthOutcome = serde_json::from_str(&format!("{{{tokens}}}")).unwrap();
        assert!(matches!(m0, AuthOutcome::Registered { new_account: false, recovery: false, .. }));

        let replace: AuthOutcome = serde_json::from_str(
            r#"{"result":"replace_required","user_id":"u","ticket":"k","expires_in":1800,"active_device":{"platform":"ios","created_at":"2026-10-01T09:00:00Z"},"recovery":true}"#,
        )
        .unwrap();
        let AuthOutcome::ReplaceRequired { user_id, ticket, expires_in, active_device, recovery } = replace else {
            panic!()
        };
        assert_eq!((user_id.as_str(), ticket.as_str(), expires_in, recovery), ("u", "k", 1800, true));
        assert_eq!(active_device.platform, "ios");
        assert!(serde_json::from_str::<AuthOutcome>(r#"{"result":"replace_required","user_id":"u"}"#).is_err());
        assert!(serde_json::from_str::<AuthOutcome>(r#"{"result":"later"}"#).is_err());
    }

    #[test]
    fn writes_m1_requests_flat_with_the_contract_names() {
        let proof =
            DeviceProof { platform: "ios".into(), signature_key: vec![1, 2, 3], nonce: "n".into(), signature: vec![4] };
        let new = ProviderSignIn {
            proof: proof.clone(),
            account: NewAccountFields {
                age_confirmed_at: Some("2026-10-26T00:00:00.000Z".into()),
                display_name: Some("앨리스".into()),
            },
            id_token: "tok".into(),
        };
        assert_eq!(
            serde_json::to_value(&new).unwrap(),
            serde_json::json!({
                "platform": "ios", "signature_key": "AQID", "nonce": "n", "signature": "BA==",
                "age_confirmed_at": "2026-10-26T00:00:00.000Z", "display_name": "앨리스", "id_token": "tok",
            })
        );
        let existing = ProviderSignIn { proof, account: NewAccountFields::default(), id_token: "tok".into() };
        let existing = serde_json::to_value(&existing).unwrap();
        assert!(existing.get("display_name").is_none() && existing.get("age_confirmed_at").is_none());

        let first = RecoveryUpload {
            amk_wrapped: vec![1],
            recovery_id: vec![2],
            recovery_public_key: vec![3],
            new_key: false,
            nonce: None,
            proof: None,
            kakao_id_token: None,
            apple_authorization_code: None,
        };
        assert_eq!(
            serde_json::to_value(&first).unwrap(),
            serde_json::json!({"amk_wrapped": "AQ==", "recovery_id": "Ag==", "recovery_public_key": "Aw=="})
        );
        let rotation = RecoveryUpload { nonce: Some("n".into()), proof: Some(vec![9]), ..first.clone() };
        let rotation = serde_json::to_value(&rotation).unwrap();
        assert_eq!((&rotation["nonce"], &rotation["proof"]), (&serde_json::json!("n"), &serde_json::json!("CQ==")));
        assert!(rotation.get("new_key").is_none());
        let start_over = RecoveryUpload {
            new_key: true,
            nonce: Some("n".into()),
            kakao_id_token: Some("tok".into()),
            ..first.clone()
        };
        let start_over = serde_json::to_value(&start_over).unwrap();
        assert_eq!(
            (&start_over["new_key"], &start_over["kakao_id_token"]),
            (&serde_json::json!(true), &serde_json::json!("tok"))
        );
        assert!(start_over.get("apple_authorization_code").is_none() && start_over.get("proof").is_none());
    }

    #[test]
    fn writes_the_deletion_proofs_and_the_alpha_counts_with_the_contract_names() {
        let guest = DeleteAccount {
            nonce: Some("n".into()),
            recovery_signature: Some(vec![1, 2, 3]),
            ..DeleteAccount::default()
        };
        assert_eq!(
            serde_json::to_value(&guest).unwrap(),
            serde_json::json!({"nonce": "n", "recovery_signature": "AQID"})
        );
        let kakao =
            DeleteAccount { nonce: Some("n".into()), kakao_id_token: Some("tok".into()), ..DeleteAccount::default() };
        assert_eq!(serde_json::to_value(&kakao).unwrap(), serde_json::json!({"nonce": "n", "kakao_id_token": "tok"}));
        let apple = DeleteAccount { apple_authorization_code: Some("code".into()), ..DeleteAccount::default() };
        assert_eq!(serde_json::to_value(&apple).unwrap(), serde_json::json!({"apple_authorization_code": "code"}));

        let day = AlphaDay { day: "2026-10-29".into(), sessions: 3, events: 2, suggested: 1, crashes: 0 };
        assert_eq!(
            serde_json::to_value(&day).unwrap(),
            serde_json::json!({"day": "2026-10-29", "sessions": 3, "events": 2, "suggested": 1, "crashes": 0})
        );
        let answer: HolidayYears = serde_json::from_str(
            r#"{"years":[2026],"holidays":[{"day":"2026-10-03","name":"개천절","substitute":false}]}"#,
        )
        .unwrap();
        assert_eq!((answer.years, answer.holidays[0].name.as_str()), (vec![2026], "개천절"));
    }
}

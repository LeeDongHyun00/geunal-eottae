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
    #[error("network: {0}")]
    Network(String),
    #[error("unexpected response: {0}")]
    Malformed(String),
}

impl ApiError {
    pub fn is(&self, status: u16, code: &str) -> bool {
        matches!(self, ApiError::Api { status: s, code: c, .. } if *s == status && c == code)
    }

    pub fn code(&self) -> Option<&str> {
        match self {
            ApiError::Api { code, .. } => Some(code),
            _ => None,
        }
    }
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

/// The API as the engine sees it. One implementation speaks HTTP, another is the in-process fake server; both keep
/// the caller's session after `sign_in`.
pub trait Transport: Send + Sync {
    fn nonce(&self) -> impl Future<Output = Result<String, ApiError>> + Send;
    fn sign_in(&self, req: StubSignIn) -> impl Future<Output = Result<SessionInfo, ApiError>> + Send;
    /// Reuses a session from a previous run.
    fn restore_session(&self, access_token: &str);
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
    async fn sign_in(&self, req: StubSignIn) -> Result<SessionInfo, ApiError> {
        (**self).sign_in(req).await
    }
    fn restore_session(&self, access_token: &str) {
        (**self).restore_session(access_token)
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
}

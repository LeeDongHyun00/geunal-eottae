//! What the core asks of the API (api-server.md §7) and the shapes it expects back. Bytes travel as standard base64
//! in JSON, as the push payload already does (protocol §8). Errors follow protocol §11: `{"error": code, ...}`.

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

/// `POST /auth/guest`: a device registering with its Ed25519 key and the proof over the server's nonce.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct GuestSignIn {
    pub display_name: String,
    /// `ios` or `android` (the `devices.platform` check).
    pub platform: String,
    #[serde(with = "b64")]
    pub signature_key: Vec<u8>,
    pub nonce: String,
    #[serde(with = "b64")]
    pub signature: Vec<u8>,
}

/// What a sign-in returns; the transport keeps the token, the engine the identity.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SessionInfo {
    pub user_id: String,
    pub device_id: String,
    pub access_token: String,
    pub refresh_token: String,
}

/// `GET /users/{id}/devices`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeviceInfo {
    pub device_id: String,
    #[serde(with = "b64")]
    pub signature_key: Vec<u8>,
}

/// `GET /me/groups`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct GroupSummary {
    pub group_id: String,
    pub kind: String,
    pub epoch: u64,
    pub latest_seq: i64,
}

/// `GET /groups/{id}/members`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MemberInfo {
    pub user_id: String,
    pub role: String,
    /// `pending`, `active` or `removed`.
    pub status: String,
}

/// `POST /groups/{id}/commits`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CommitRequest {
    /// The epoch the commit was built on; the server accepts one commit per epoch.
    pub epoch: u64,
    #[serde(with = "b64")]
    pub commit: Vec<u8>,
    /// GroupInfo of the epoch the commit creates.
    #[serde(with = "b64")]
    pub group_info: Vec<u8>,
    #[serde(default, with = "b64::option", skip_serializing_if = "Option::is_none")]
    pub welcome: Option<Vec<u8>>,
    /// Users whose devices the commit adds; the server marks them active and files the Welcome for them.
    #[serde(default)]
    pub added_user_ids: Vec<String>,
    /// An external commit by a member's own device rejoining.
    #[serde(default)]
    pub external: bool,
}

/// `201` from a commit or message.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Accepted {
    pub seq: i64,
    /// Server time, RFC 3339; the device's clock for every rule (protocol §4).
    pub received_at: String,
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
    pub ciphertext: Vec<u8>,
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

/// One row of `GET /me/welcomes`: `seq` is the Add commit's place in the log, where the new member's cursor starts.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WelcomeItem {
    pub group_id: String,
    pub seq: i64,
    #[serde(with = "b64")]
    pub welcome: Vec<u8>,
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
    #[serde(with = "b64")]
    pub ciphertext: Vec<u8>,
    pub version: i64,
}

/// The largest page of `GET /groups/{id}/log` (api-server.md §5).
pub const LOG_PAGE: u32 = 500;

/// The API as the engine sees it. One implementation speaks HTTP, another is the in-process fake server; both keep
/// the caller's session after `sign_in_guest`.
pub trait Transport: Send + Sync {
    fn nonce(&self) -> impl Future<Output = Result<String, ApiError>> + Send;
    fn sign_in_guest(&self, req: GuestSignIn) -> impl Future<Output = Result<SessionInfo, ApiError>> + Send;
    /// Reuses a session from a previous run.
    fn restore_session(&self, access_token: &str);
    fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> impl Future<Output = Result<(), ApiError>> + Send;
    fn devices(&self, user_id: &str) -> impl Future<Output = Result<Vec<DeviceInfo>, ApiError>> + Send;
    fn claim_key_package(&self, device_id: &str) -> impl Future<Output = Result<Vec<u8>, ApiError>> + Send;
    fn my_groups(&self) -> impl Future<Output = Result<Vec<GroupSummary>, ApiError>> + Send;
    /// The device picks the group id (the MLS group id must exist before the first GroupInfo does).
    fn create_group(&self, group_id: &str, group_info: Vec<u8>) -> impl Future<Output = Result<(), ApiError>> + Send;
    fn create_invite(&self, group_id: &str) -> impl Future<Output = Result<String, ApiError>> + Send;
    fn join_invite(&self, code: &str) -> impl Future<Output = Result<String, ApiError>> + Send;
    fn members(&self, group_id: &str) -> impl Future<Output = Result<Vec<MemberInfo>, ApiError>> + Send;
    fn remove_member(&self, group_id: &str, user_id: &str) -> impl Future<Output = Result<(), ApiError>> + Send;
    fn post_commit(
        &self,
        group_id: &str,
        req: CommitRequest,
    ) -> impl Future<Output = Result<Accepted, ApiError>> + Send;
    fn post_message(
        &self,
        group_id: &str,
        req: MessageRequest,
    ) -> impl Future<Output = Result<Accepted, ApiError>> + Send;
    fn log(
        &self,
        group_id: &str,
        after: i64,
        limit: u32,
    ) -> impl Future<Output = Result<Vec<LogItem>, ApiError>> + Send;
    fn group_info(&self, group_id: &str) -> impl Future<Output = Result<Vec<u8>, ApiError>> + Send;
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
    async fn sign_in_guest(&self, req: GuestSignIn) -> Result<SessionInfo, ApiError> {
        (**self).sign_in_guest(req).await
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
    async fn create_group(&self, group_id: &str, group_info: Vec<u8>) -> Result<(), ApiError> {
        (**self).create_group(group_id, group_info).await
    }
    async fn create_invite(&self, group_id: &str) -> Result<String, ApiError> {
        (**self).create_invite(group_id).await
    }
    async fn join_invite(&self, code: &str) -> Result<String, ApiError> {
        (**self).join_invite(code).await
    }
    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        (**self).members(group_id).await
    }
    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        (**self).remove_member(group_id, user_id).await
    }
    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<Accepted, ApiError> {
        (**self).post_commit(group_id, req).await
    }
    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<Accepted, ApiError> {
        (**self).post_message(group_id, req).await
    }
    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<Vec<LogItem>, ApiError> {
        (**self).log(group_id, after, limit).await
    }
    async fn group_info(&self, group_id: &str) -> Result<Vec<u8>, ApiError> {
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
    fn bytes_travel_as_base64() {
        let req = PutState { epoch: 3, ciphertext: vec![1, 2, 3], base_version: None };
        assert_eq!(serde_json::to_string(&req).unwrap(), r#"{"epoch":3,"ciphertext":"AQID"}"#);
        let item: LogItem = serde_json::from_str(
            r#"{"seq":1,"epoch":0,"kind":"app","author_id":"u","ciphertext":"AQID","received_at":"2026-11-01T00:00:00Z"}"#,
        )
        .unwrap();
        assert_eq!(item.ciphertext, vec![1, 2, 3]);
    }
}

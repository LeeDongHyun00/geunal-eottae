//! The HTTP side of [`Transport`]: reqwest over the core's own rustls configuration, a bearer token once signed in,
//! and protocol §11 error bodies turned into [`ApiError::Api`]. Paths and shapes follow `server/api/openapi.yaml`.

use std::sync::Mutex;

use reqwest::{Client, Method, RequestBuilder, Response};
use serde::Deserialize;
use serde::de::DeserializeOwned;

use crate::api::*;

pub struct HttpTransport {
    base: String,
    client: Client,
    token: Mutex<Option<String>>,
}

#[derive(Deserialize)]
struct ErrorBody {
    error: String,
    #[serde(default)]
    message: String,
}

impl HttpTransport {
    /// `base` is the API origin, `https://api.example` or `http://127.0.0.1:8080` for the local stack.
    pub fn new(base: &str) -> Result<Self, ApiError> {
        let client = Client::builder()
            .use_preconfigured_tls((*crate::tls::client_config()).clone())
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(|e| ApiError::Network(e.to_string()))?;
        Ok(Self { base: base.trim_end_matches('/').to_owned(), client, token: Mutex::new(None) })
    }

    pub fn access_token(&self) -> Option<String> {
        self.token.lock().ok().and_then(|t| t.clone())
    }

    fn request(&self, method: Method, path: &str) -> RequestBuilder {
        let mut req = self.client.request(method, format!("{}{path}", self.base));
        if let Some(token) = self.access_token() {
            req = req.bearer_auth(token);
        }
        req
    }

    async fn send<T: DeserializeOwned>(req: RequestBuilder) -> Result<T, ApiError> {
        let response = req.send().await.map_err(|e| ApiError::Network(e.to_string()))?;
        let response = Self::check(response).await?;
        response.json().await.map_err(|e| ApiError::Malformed(e.to_string()))
    }

    async fn send_empty(req: RequestBuilder) -> Result<(), ApiError> {
        let response = req.send().await.map_err(|e| ApiError::Network(e.to_string()))?;
        Self::check(response).await.map(|_| ())
    }

    async fn check(response: Response) -> Result<Response, ApiError> {
        let status = response.status();
        if status.is_success() {
            return Ok(response);
        }
        let text = response.text().await.unwrap_or_default();
        let (code, message) = match serde_json::from_str::<ErrorBody>(&text) {
            Ok(body) => (body.error, body.message),
            Err(_) => (format!("http_{}", status.as_u16()), String::new()),
        };
        // Bodies never reach a log (api-server.md §10); the code and status are enough to act on.
        Err(ApiError::Api { status: status.as_u16(), code, message })
    }
}

#[derive(Deserialize)]
struct Nonce {
    nonce: String,
}
#[derive(Deserialize)]
struct Devices {
    devices: Vec<DeviceInfo>,
}
#[derive(Deserialize)]
struct KeyPackage {
    #[serde(with = "b64")]
    key_package: Vec<u8>,
}
#[derive(Deserialize)]
struct Groups {
    groups: Vec<GroupSummary>,
}
#[derive(Deserialize)]
struct Members {
    members: Vec<MemberInfo>,
}
#[derive(Deserialize)]
struct Welcomes {
    welcomes: Vec<WelcomeItem>,
}
#[derive(Deserialize)]
struct Version {
    version: i64,
}
#[derive(Deserialize)]
struct States {
    states: Vec<StateBlob>,
}

impl Transport for HttpTransport {
    async fn nonce(&self) -> Result<String, ApiError> {
        Ok(Self::send::<Nonce>(self.request(Method::GET, "/auth/nonce")).await?.nonce)
    }

    async fn sign_in(&self, req: StubSignIn) -> Result<SessionInfo, ApiError> {
        let session: SessionInfo = Self::send(self.request(Method::POST, "/auth/stub").json(&req)).await?;
        self.restore_session(&session.access_token);
        Ok(session)
    }

    fn restore_session(&self, access_token: &str) {
        if let Ok(mut token) = self.token.lock() {
            *token = Some(access_token.to_owned());
        }
    }

    async fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> Result<(), ApiError> {
        let body = serde_json::json!({ "key_packages": packages.iter().map(|p| b64::encode(p)).collect::<Vec<_>>() });
        Self::send_empty(self.request(Method::POST, "/me/key-packages").json(&body)).await
    }

    async fn devices(&self, user_id: &str) -> Result<Vec<DeviceInfo>, ApiError> {
        Ok(Self::send::<Devices>(self.request(Method::GET, &format!("/users/{user_id}/devices"))).await?.devices)
    }

    async fn claim_key_package(&self, device_id: &str) -> Result<Vec<u8>, ApiError> {
        let path = format!("/devices/{device_id}/key-packages/claim");
        Ok(Self::send::<KeyPackage>(self.request(Method::POST, &path)).await?.key_package)
    }

    async fn my_groups(&self) -> Result<Vec<GroupSummary>, ApiError> {
        Ok(Self::send::<Groups>(self.request(Method::GET, "/me/groups")).await?.groups)
    }

    async fn create_group(&self) -> Result<GroupSummary, ApiError> {
        Self::send(self.request(Method::POST, "/groups")).await
    }

    async fn put_group_info(&self, group_id: &str, info: GroupInfo) -> Result<(), ApiError> {
        Self::send_empty(self.request(Method::PUT, &format!("/groups/{group_id}/group-info")).json(&info)).await
    }

    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        Ok(Self::send::<Members>(self.request(Method::GET, &format!("/groups/{group_id}/members"))).await?.members)
    }

    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        Self::send_empty(self.request(Method::DELETE, &format!("/groups/{group_id}/members/{user_id}"))).await
    }

    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<CommitAccepted, ApiError> {
        Self::send(self.request(Method::POST, &format!("/groups/{group_id}/commits")).json(&req)).await
    }

    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<MessageAccepted, ApiError> {
        Self::send(self.request(Method::POST, &format!("/groups/{group_id}/messages")).json(&req)).await
    }

    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<LogPage, ApiError> {
        let path = format!("/groups/{group_id}/log?after={after}&limit={limit}");
        Self::send(self.request(Method::GET, &path)).await
    }

    async fn group_info(&self, group_id: &str) -> Result<GroupInfo, ApiError> {
        Self::send(self.request(Method::GET, &format!("/groups/{group_id}/group-info"))).await
    }

    async fn welcomes(&self) -> Result<Vec<WelcomeItem>, ApiError> {
        Ok(Self::send::<Welcomes>(self.request(Method::GET, "/me/welcomes")).await?.welcomes)
    }

    async fn put_state(&self, group_id: &str, kind: &str, req: PutState) -> Result<i64, ApiError> {
        let path = format!("/groups/{group_id}/state/{kind}");
        Ok(Self::send::<Version>(self.request(Method::PUT, &path).json(&req)).await?.version)
    }

    async fn state(&self, group_id: &str) -> Result<Vec<StateBlob>, ApiError> {
        Ok(Self::send::<States>(self.request(Method::GET, &format!("/groups/{group_id}/state"))).await?.states)
    }
}

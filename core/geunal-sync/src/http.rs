//! The HTTP side of [`Transport`]: reqwest over the core's own rustls configuration, a bearer token once signed in,
//! and protocol §11 error bodies turned into [`ApiError::Api`]. Paths and shapes follow `server/api/openapi.yaml`.
//!
//! An authenticated call that meets 401 `token_expired` trades the refresh token for new tokens and goes once more.
//! The trade runs one at a time: the server ends every session of a device that presents a refresh token twice, and
//! two calls can find the access token expired together. No other answer is traded: 401 `account_deleted` means the
//! account is gone (protocol §13), and the core finishes the deletion.

use std::sync::Mutex;

use reqwest::{Client, Method, RequestBuilder, Response};
use serde::Deserialize;
use serde::de::DeserializeOwned;

use crate::api::*;

pub struct HttpTransport {
    base: String,
    client: Client,
    /// Access and refresh token.
    tokens: Mutex<Option<(String, String)>>,
    listener: Mutex<Option<TokenListener>>,
    refreshing: tokio::sync::Mutex<()>,
}

/// A failure to reach or read the server, in words without the URL. reqwest ends its text with the whole URL, and a
/// presigned one carries its signature (`X-Amz-Signature`), which would then ride the app's exception message into a
/// crash report.
fn network(e: reqwest::Error) -> ApiError {
    ApiError::Network(e.without_url().to_string())
}

fn malformed(e: reqwest::Error) -> ApiError {
    ApiError::Malformed(e.without_url().to_string())
}

/// The contract's limit for one sealed object (protocol §10, 64 MiB). A longer download is not one of ours, and its
/// claimed length must not size an allocation that fails and aborts the app.
const MAX_OBJECT: u64 = 64 << 20;

/// What a failed response stands for. Bodies never reach a log (api-server.md §10); the code and status are enough to
/// act on.
fn api_error(status: u16, body: &str) -> ApiError {
    match serde_json::from_str::<ErrorBody>(body) {
        Ok(body) if status == 403 && body.error == "device_revoked" => ApiError::Revoked { replaced: body.replaced },
        Ok(body) => ApiError::Api { status, code: body.error, message: body.message },
        Err(_) => ApiError::Api { status, code: format!("http_{status}"), message: String::new() },
    }
}

#[derive(Deserialize)]
struct ErrorBody {
    error: String,
    #[serde(default)]
    message: String,
    #[serde(flatten)]
    replaced: Option<Replaced>,
}

/// Whether `base` may be plain http: only the local stack, on this machine or, from the Android emulator, on the host
/// at 10.0.2.2. The apps' transport security settings do not reach the core's own sockets, so the rule lives here.
fn local_stack(base: &reqwest::Url) -> bool {
    let host = base.host_str().unwrap_or_default();
    match host.trim_start_matches('[').trim_end_matches(']').parse::<std::net::IpAddr>() {
        Ok(ip) => ip.is_loopback() || ip == std::net::Ipv4Addr::new(10, 0, 2, 2),
        Err(_) => host == "localhost",
    }
}

impl HttpTransport {
    /// `base` is the API origin: `https://api.example`, or plain http for the local stack (`http://127.0.0.1:8080`,
    /// `http://10.0.2.2:8080` from the Android emulator). Tokens never travel in the clear otherwise, not even after a
    /// redirect. An empty `base` is a build without a server: the calendar works and every call fails.
    pub fn new(base: &str) -> Result<Self, ApiError> {
        let https_only = match base {
            "" => false,
            _ => {
                let url = reqwest::Url::parse(base).map_err(|e| ApiError::Malformed(format!("api base: {e}")))?;
                match url.scheme() {
                    "https" => true,
                    "http" if local_stack(&url) => false,
                    _ => return Err(ApiError::Malformed("the api base must be https outside the local stack".into())),
                }
            }
        };
        let client = Client::builder()
            .use_preconfigured_tls((*crate::tls::client_config()).clone())
            .https_only(https_only)
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(network)?;
        Ok(Self {
            base: base.trim_end_matches('/').to_owned(),
            client,
            tokens: Mutex::new(None),
            listener: Mutex::new(None),
            refreshing: tokio::sync::Mutex::new(()),
        })
    }

    pub fn access_token(&self) -> Option<String> {
        self.tokens.lock().ok().and_then(|t| t.as_ref().map(|(access, _)| access.clone()))
    }

    fn refresh_token(&self) -> Option<String> {
        self.tokens.lock().ok().and_then(|t| t.as_ref().map(|(_, refresh)| refresh.clone()))
    }

    fn set_tokens(&self, tokens: &SessionInfo) {
        if let Ok(mut t) = self.tokens.lock() {
            *t = Some((tokens.access_token.clone(), tokens.refresh_token.clone()));
        }
    }

    /// A request carrying the session's bearer token.
    fn request(&self, method: Method, path: &str) -> RequestBuilder {
        let req = self.anonymous(method, path);
        match self.access_token() {
            Some(token) => req.bearer_auth(token),
            None => req,
        }
    }

    /// A request without the session: the `/auth` calls, which prove themselves.
    fn anonymous(&self, method: Method, path: &str) -> RequestBuilder {
        self.client.request(method, format!("{}{path}", self.base))
    }

    /// Sends what `build` makes, and once more after a refresh when the access token had expired. `build` runs again
    /// for the second try so the request carries the new token.
    async fn execute(&self, build: impl Fn(&Self) -> RequestBuilder) -> Result<Response, ApiError> {
        let used = self.access_token();
        match Self::check(build(self).send().await.map_err(network)?).await {
            Err(e) if used.is_some() && e.is(401, "token_expired") => {
                self.refresh(used.as_deref()).await?;
                Self::check(build(self).send().await.map_err(network)?).await
            }
            other => other,
        }
    }

    async fn send<T: DeserializeOwned>(&self, build: impl Fn(&Self) -> RequestBuilder) -> Result<T, ApiError> {
        self.execute(build).await?.json().await.map_err(malformed)
    }

    async fn send_empty(&self, build: impl Fn(&Self) -> RequestBuilder) -> Result<(), ApiError> {
        self.execute(build).await.map(|_| ())
    }

    /// A call that never refreshes: the `/auth` calls, and reads under a replacement ticket.
    async fn once<T: DeserializeOwned>(req: RequestBuilder) -> Result<T, ApiError> {
        let response = req.send().await.map_err(network)?;
        Self::check(response).await?.json().await.map_err(malformed)
    }

    /// Trades the refresh token for new tokens, unless another call already did while this one waited.
    async fn refresh(&self, expired: Option<&str>) -> Result<(), ApiError> {
        let _one_at_a_time = self.refreshing.lock().await;
        if self.access_token().as_deref() != expired {
            return Ok(());
        }
        let refresh_token = self.refresh_token().ok_or_else(|| ApiError::Api {
            status: 401,
            code: "token_expired".into(),
            message: String::new(),
        })?;
        let body = serde_json::json!({ "refresh_token": refresh_token });
        let tokens: SessionInfo = Self::once(self.anonymous(Method::POST, "/auth/refresh").json(&body)).await?;
        self.set_tokens(&tokens);
        let listener = self.listener.lock().ok().and_then(|l| l.clone());
        if let Some(listener) = listener {
            listener(&tokens);
        }
        Ok(())
    }

    async fn check(response: Response) -> Result<Response, ApiError> {
        let status = response.status();
        if status.is_success() {
            return Ok(response);
        }
        let text = response.text().await.unwrap_or_default();
        Err(api_error(status.as_u16(), &text))
    }

    /// Keeps the tokens of a sign-in that registered this device; a replacement answer registers nothing.
    fn keep(&self, outcome: AuthOutcome) -> AuthOutcome {
        if let AuthOutcome::Registered { tokens, .. } = &outcome {
            self.set_tokens(tokens);
        }
        outcome
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
#[derive(Deserialize)]
struct Providers {
    providers: Vec<String>,
}
#[derive(Deserialize)]
struct Backups {
    backups: Vec<BackupInfo>,
}

impl Transport for HttpTransport {
    async fn nonce(&self) -> Result<String, ApiError> {
        Ok(Self::once::<Nonce>(self.anonymous(Method::GET, "/auth/nonce")).await?.nonce)
    }

    async fn sign_in(&self, req: StubSignIn) -> Result<AuthOutcome, ApiError> {
        Ok(self.keep(Self::once(self.anonymous(Method::POST, "/auth/stub").json(&req)).await?))
    }

    async fn sign_in_provider(&self, provider: &str, req: ProviderSignIn) -> Result<AuthOutcome, ApiError> {
        let path = format!("/auth/{provider}");
        Ok(self.keep(Self::once(self.anonymous(Method::POST, &path).json(&req)).await?))
    }

    async fn sign_in_guest(&self, req: GuestSignIn) -> Result<AuthOutcome, ApiError> {
        Ok(self.keep(Self::once(self.anonymous(Method::POST, "/auth/guest").json(&req)).await?))
    }

    async fn recover(&self, req: RecoverSignIn) -> Result<AuthOutcome, ApiError> {
        Ok(self.keep(Self::once(self.anonymous(Method::POST, "/auth/recover").json(&req)).await?))
    }

    async fn replace(&self, req: ReplaceRequest) -> Result<AuthOutcome, ApiError> {
        Ok(self.keep(Self::once(self.anonymous(Method::POST, "/auth/replace").json(&req)).await?))
    }

    async fn link(&self, req: LinkRequest) -> Result<Vec<String>, ApiError> {
        Ok(self.send::<Providers>(|t| t.request(Method::POST, "/auth/link").json(&req)).await?.providers)
    }

    async fn me(&self) -> Result<MeInfo, ApiError> {
        self.send(|t| t.request(Method::GET, "/me")).await
    }

    async fn put_recovery(&self, req: RecoveryUpload) -> Result<(), ApiError> {
        self.send_empty(|t| t.request(Method::PUT, "/me/recovery").json(&req)).await
    }

    async fn get_recovery(&self, ticket: Option<&str>) -> Result<StoredRecovery, ApiError> {
        match ticket {
            Some(ticket) => Self::once(self.anonymous(Method::GET, "/me/recovery").bearer_auth(ticket)).await,
            None => self.send(|t| t.request(Method::GET, "/me/recovery")).await,
        }
    }

    async fn start_backup(&self, size: u64) -> Result<BackupUpload, ApiError> {
        let body = serde_json::json!({ "size": size });
        self.send(|t| t.request(Method::POST, "/me/backups").json(&body)).await
    }

    async fn put_object(&self, url: &str, body: Vec<u8>) -> Result<(), ApiError> {
        // The signature covers Content-Length, which reqwest sets from the body.
        let response = self.client.put(url).body(body).send().await.map_err(network)?;
        Self::check(response).await.map(|_| ())
    }

    async fn complete_backup(&self, id: i64) -> Result<BackupInfo, ApiError> {
        let path = format!("/me/backups/{id}/complete");
        self.send(|t| t.request(Method::POST, &path)).await
    }

    async fn backups(&self, ticket: Option<&str>) -> Result<Vec<BackupInfo>, ApiError> {
        let list: Backups = match ticket {
            Some(ticket) => Self::once(self.anonymous(Method::GET, "/me/backups").bearer_auth(ticket)).await?,
            None => self.send(|t| t.request(Method::GET, "/me/backups")).await?,
        };
        Ok(list.backups)
    }

    async fn latest_backup(&self, ticket: Option<&str>) -> Result<Option<BackupDownload>, ApiError> {
        let found = match ticket {
            Some(ticket) => Self::once(self.anonymous(Method::GET, "/me/backups/latest").bearer_auth(ticket)).await,
            None => self.send(|t| t.request(Method::GET, "/me/backups/latest")).await,
        };
        match found {
            Err(e) if e.is(404, "not_found") => Ok(None),
            other => other.map(Some),
        }
    }

    async fn get_object(&self, url: &str, progress: Progress<'_>) -> Result<Vec<u8>, ApiError> {
        let mut response = Self::check(self.client.get(url).send().await.map_err(network)?).await?;
        let total = response.content_length().unwrap_or(0);
        let too_large = || ApiError::Malformed("an object over 64 MiB".into());
        if total > MAX_OBJECT {
            return Err(too_large());
        }
        let mut body = Vec::with_capacity(usize::try_from(total).unwrap_or(0));
        while let Some(chunk) = response.chunk().await.map_err(network)? {
            if (body.len() + chunk.len()) as u64 > MAX_OBJECT {
                return Err(too_large());
            }
            body.extend_from_slice(&chunk);
            progress(body.len() as u64, total.max(body.len() as u64));
        }
        Ok(body)
    }

    async fn delete_account(&self, req: DeleteAccount) -> Result<(), ApiError> {
        self.send_empty(|t| t.request(Method::DELETE, "/me").json(&req)).await
    }

    async fn holidays(&self, from_year: i32, to_year: i32) -> Result<HolidayYears, ApiError> {
        let path = format!("/holidays?from_year={from_year}&to_year={to_year}");
        Self::once(self.anonymous(Method::GET, &path)).await
    }

    async fn post_alpha_stats(&self, days: Vec<AlphaDay>) -> Result<(), ApiError> {
        let body = serde_json::json!({ "stats": days });
        self.send_empty(|t| t.request(Method::POST, "/me/alpha-stats").json(&body)).await
    }

    fn restore_session(&self, access_token: &str, refresh_token: &str) {
        if let Ok(mut tokens) = self.tokens.lock() {
            *tokens = Some((access_token.to_owned(), refresh_token.to_owned()));
        }
    }

    fn clear_session(&self) {
        if let Ok(mut tokens) = self.tokens.lock() {
            *tokens = None;
        }
    }

    fn set_token_listener(&self, listener: TokenListener) {
        if let Ok(mut l) = self.listener.lock() {
            *l = Some(listener);
        }
    }

    async fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> Result<(), ApiError> {
        let body = serde_json::json!({ "key_packages": packages.iter().map(|p| b64::encode(p)).collect::<Vec<_>>() });
        self.send_empty(|t| t.request(Method::POST, "/me/key-packages").json(&body)).await
    }

    async fn devices(&self, user_id: &str) -> Result<Vec<DeviceInfo>, ApiError> {
        let path = format!("/users/{user_id}/devices");
        Ok(self.send::<Devices>(|t| t.request(Method::GET, &path)).await?.devices)
    }

    async fn claim_key_package(&self, device_id: &str) -> Result<Vec<u8>, ApiError> {
        let path = format!("/devices/{device_id}/key-packages/claim");
        Ok(self.send::<KeyPackage>(|t| t.request(Method::POST, &path)).await?.key_package)
    }

    async fn my_groups(&self) -> Result<Vec<GroupSummary>, ApiError> {
        Ok(self.send::<Groups>(|t| t.request(Method::GET, "/me/groups")).await?.groups)
    }

    async fn create_group(&self) -> Result<GroupSummary, ApiError> {
        self.send(|t| t.request(Method::POST, "/groups")).await
    }

    async fn put_group_info(&self, group_id: &str, info: GroupInfo) -> Result<(), ApiError> {
        let path = format!("/groups/{group_id}/group-info");
        self.send_empty(|t| t.request(Method::PUT, &path).json(&info)).await
    }

    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        let path = format!("/groups/{group_id}/members");
        Ok(self.send::<Members>(|t| t.request(Method::GET, &path)).await?.members)
    }

    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        let path = format!("/groups/{group_id}/members/{user_id}");
        self.send_empty(|t| t.request(Method::DELETE, &path)).await
    }

    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<CommitAccepted, ApiError> {
        let path = format!("/groups/{group_id}/commits");
        self.send(|t| t.request(Method::POST, &path).json(&req)).await
    }

    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<MessageAccepted, ApiError> {
        let path = format!("/groups/{group_id}/messages");
        self.send(|t| t.request(Method::POST, &path).json(&req)).await
    }

    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<LogPage, ApiError> {
        let path = format!("/groups/{group_id}/log?after={after}&limit={limit}");
        self.send(|t| t.request(Method::GET, &path)).await
    }

    async fn group_info(&self, group_id: &str) -> Result<GroupInfo, ApiError> {
        let path = format!("/groups/{group_id}/group-info");
        self.send(|t| t.request(Method::GET, &path)).await
    }

    async fn welcomes(&self) -> Result<Vec<WelcomeItem>, ApiError> {
        Ok(self.send::<Welcomes>(|t| t.request(Method::GET, "/me/welcomes")).await?.welcomes)
    }

    async fn put_state(&self, group_id: &str, kind: &str, req: PutState) -> Result<i64, ApiError> {
        let path = format!("/groups/{group_id}/state/{kind}");
        Ok(self.send::<Version>(|t| t.request(Method::PUT, &path).json(&req)).await?.version)
    }

    async fn state(&self, group_id: &str) -> Result<Vec<StateBlob>, ApiError> {
        let path = format!("/groups/{group_id}/state");
        Ok(self.send::<States>(|t| t.request(Method::GET, &path)).await?.states)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn takes_plain_http_only_for_the_local_stack() {
        for base in [
            "https://api.example",
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://[::1]:8080",
            "http://10.0.2.2:8080",
            "",
        ] {
            assert!(HttpTransport::new(base).is_ok(), "{base}");
        }
        for base in [
            "http://api.example",
            "http://192.168.0.10:8080",
            "http://10.0.2.3:8080",
            "ftp://api.example",
            "api.example",
        ] {
            assert!(matches!(HttpTransport::new(base), Err(ApiError::Malformed(_))), "{base}");
        }
    }

    #[tokio::test]
    async fn a_failed_download_keeps_the_signed_url_out_of_its_error() {
        // Nothing listens on the discard port, so the request fails before any answer.
        let api = HttpTransport::new("http://127.0.0.1:9").unwrap();
        let url = "http://127.0.0.1:9/backups/1?X-Amz-Signature=secret";
        let err = api.get_object(url, &|_, _| {}).await.unwrap_err();
        let ApiError::Network(text) = err else { panic!("{err:?}") };
        assert!(!text.contains("X-Amz-Signature") && !text.contains("secret"), "{text}");
    }

    #[tokio::test]
    async fn a_deleted_accounts_token_is_not_traded_for_new_ones() {
        // A server that answers every request as it does a deleted account's token (protocol §13 "답을 잃은 삭제").
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let requests = std::sync::Arc::new(Mutex::new(Vec::<String>::new()));
        let seen = requests.clone();
        tokio::spawn(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            while let Ok((mut socket, _)) = listener.accept().await {
                let mut head = vec![0; 4096];
                let read = socket.read(&mut head).await.unwrap_or(0);
                let line = String::from_utf8_lossy(&head[..read]).lines().next().unwrap_or_default().to_owned();
                seen.lock().unwrap().push(line);
                let body = r#"{"error":"account_deleted","message":"m"}"#;
                let answer = format!(
                    "HTTP/1.1 401 Unauthorized\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = socket.write_all(answer.as_bytes()).await;
            }
        });
        let api = HttpTransport::new(&base).unwrap();
        api.restore_session("access", "refresh");

        let err = api.me().await.unwrap_err();
        assert!(err.is(401, "account_deleted"), "{err:?}");
        // One request and no `POST /auth/refresh`: refreshing is for 401 `token_expired` only.
        assert_eq!(requests.lock().unwrap().as_slice(), ["GET /me HTTP/1.1"]);
    }

    #[test]
    fn a_revoked_device_reads_how_it_was_replaced_when_the_body_says() {
        let body = r#"{"error":"device_revoked","message":"m","replaced_at":"2026-10-26T05:00:00Z","replaced_by":"ios","replaced_via":"recovery"}"#;
        let replaced = Replaced {
            replaced_at: "2026-10-26T05:00:00Z".into(),
            replaced_by: "ios".into(),
            replaced_via: "recovery".into(),
        };
        assert_eq!(api_error(403, body), ApiError::Revoked { replaced: Some(replaced) });
        // An M0 server, or a revocation that was not a replacement.
        assert_eq!(api_error(403, r#"{"error":"device_revoked","message":"m"}"#), ApiError::Revoked { replaced: None });
        assert!(api_error(403, r#"{"error":"replace_required","message":"m"}"#).is(403, "replace_required"));
        assert!(api_error(502, "<html>bad gateway</html>").is(502, "http_502"));
    }
}

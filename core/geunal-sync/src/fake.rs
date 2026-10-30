//! An in-process API server with the rules the engine depends on (protocol §6, §11, api-server.md §5,
//! server/api/openapi.yaml): one commit per epoch, messages only in the current epoch, a 30-day mailbox that answers
//! 410 behind its floor, Welcomes filed per device, state blobs that never go back an epoch. Several [`FakeClient`]s
//! share one server, so tests run three devices in one process and the end-to-end test keeps the same engine code
//! against the real stack.
//!
//! The M1 account rules (protocol §10, §13) are here too: one active device per account with replacement tickets, a
//! guest coming back by recovery signature, recovery material that only its own code overwrites, revocations that say
//! when, where and how, and refresh tokens that end a device's sessions when one is presented twice. A provider ID
//! token is [`FakeServer::id_token`]'s plain text instead of a signed JWT, with the nonce claim the server checks.

use std::collections::{BTreeMap, VecDeque};
use std::sync::{Arc, Mutex};

use chrono::{DateTime, Datelike, Duration, Utc};
use serde_json::{Value, json};

use crate::api::*;

#[derive(Default)]
struct Device {
    device_id: String,
    signature_key: Vec<u8>,
    key_packages: VecDeque<Vec<u8>>,
    platform: String,
    /// When a Kakao, Apple or stub sign-in registered it: "새로 시작" may replace the recovery material within 30
    /// minutes of that.
    by_provider_at: Option<DateTime<Utc>>,
}

struct Recovery {
    amk_wrapped: Vec<u8>,
    recovery_id: Vec<u8>,
    public_key: Vec<u8>,
    updated_at: String,
}

#[derive(Default)]
struct User {
    device: Option<Device>,
    display_name: String,
    /// `kakao`, `apple`; empty for a guest.
    providers: Vec<String>,
    recovery: Option<Recovery>,
    /// How the last replacement signed the previous device out, for its 403 `device_revoked`.
    replaced: Option<Replaced>,
    /// Backups deleted by "새로 시작" (`new_key`), for tests.
    backups_cleared: u32,
    /// Uploads in the order they started; the completed ones are the account's backups.
    backups: Vec<Backup>,
}

/// A backup upload, listed once it is completed (protocol §10 "올리기").
struct Backup {
    id: i64,
    size: u64,
    /// Stands for the presigned URL: the only authorization a PUT or GET of the object carries.
    url: String,
    object: Option<Vec<u8>>,
    completed_at: Option<String>,
}

/// 64 MiB, the contract's limit for one sealed object.
const MAX_BACKUP: u64 = 64 << 20;

fn backup_info(backup: &Backup) -> BackupInfo {
    BackupInfo { id: backup.id, size: backup.size, created_at: backup.completed_at.clone().unwrap_or_default() }
}

/// A `replace_required` sign-in waiting for `POST /auth/replace`.
struct Ticket {
    user_id: String,
    signature_key: Vec<u8>,
    platform: String,
    expires_at: DateTime<Utc>,
}

struct Refresh {
    user_id: String,
    device_id: String,
    spent: bool,
}

/// What a client holds after a sign-in.
#[derive(Clone)]
struct ClientSession {
    user_id: String,
    device_id: String,
    access_token: String,
    refresh_token: String,
}

struct Member {
    role: String,
    status: String,
}

struct Group {
    kind: String,
    epoch: u64,
    next_seq: i64,
    group_info: Option<GroupInfo>,
    members: BTreeMap<String, Member>,
    log: Vec<LogItem>,
    /// Items up to this seq have left the mailbox; a cursor behind it is expired.
    dropped_through: i64,
    state: BTreeMap<(String, String), StateBlob>,
}

#[derive(Default)]
struct State {
    users: BTreeMap<String, User>,
    /// `subject` → user id: the same subject is the same account (openapi.yaml, /auth/stub). Provider accounts are
    /// under `kakao:<sub>` and `apple:<sub>`.
    subjects: BTreeMap<String, String>,
    tokens: BTreeMap<String, (String, String)>,
    /// Access tokens past their 15 minutes.
    expired: std::collections::BTreeSet<String>,
    refresh: BTreeMap<String, Refresh>,
    tickets: BTreeMap<String, Ticket>,
    nonces: std::collections::BTreeSet<String>,
    groups: BTreeMap<String, Group>,
    welcomes: BTreeMap<String, Vec<WelcomeItem>>,
    /// Realtime events the API would publish, per device id, as the gateway forwards them (protocol §7).
    events: BTreeMap<String, Vec<Value>>,
    counter: u64,
    now: Option<DateTime<Utc>>,
    epoch_conflicts: u32,
    stale_epochs: u32,
    log_reads: u32,
    /// Calls a test made fail, by route (`FakeServer::fail_next`, `FakeServer::lose_next`).
    faults: BTreeMap<&'static str, Fault>,
    /// The years whose holidays the server knows in full, as the daily job leaves them (protocol §14).
    holidays: BTreeMap<i32, Vec<HolidayItem>>,
    holiday_reads: u32,
    /// What `POST /me/alpha-stats` kept, by account and Korean day.
    alpha: BTreeMap<(String, String), AlphaDay>,
}

/// How a call fails as the network drops it.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Fault {
    /// Before the server sees the request.
    Fail,
    /// After the server handled it: only the answer is lost.
    Lose,
}

impl State {
    /// The connection error of a call whose fault a test set for this point of it, once.
    fn fault(&mut self, route: &str, at: Fault) -> Result<(), ApiError> {
        if self.faults.get(route) != Some(&at) {
            return Ok(());
        }
        self.faults.remove(route);
        Err(ApiError::Network(format!("{route}: connection reset")))
    }

    fn now(&mut self) -> DateTime<Utc> {
        // Each call moves the clock one millisecond so received_at stays strictly increasing and deterministic.
        let now = self.now.unwrap_or_else(Utc::now) + Duration::milliseconds(1);
        self.now = Some(now);
        now
    }

    fn now_text(&mut self) -> String {
        self.now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
    }

    fn today(&mut self) -> chrono::NaiveDate {
        self.now().with_timezone(&geunal_domain::KST).date_naive()
    }

    fn next_id(&mut self, prefix: &str) -> String {
        self.counter += 1;
        format!("{prefix}{}", self.counter)
    }

    fn device_of(&self, user: &str) -> Option<String> {
        self.users.get(user).and_then(|u| u.device.as_ref()).map(|d| d.device_id.clone())
    }

    fn user_of_device(&self, device_id: &str) -> Option<String> {
        self.users
            .iter()
            .find(|(_, u)| u.device.as_ref().is_some_and(|d| d.device_id == device_id))
            .map(|(id, _)| id.clone())
    }

    fn publish(&mut self, group_id: &str, except_user: Option<&str>, event: Value) {
        let Some(group) = self.groups.get(group_id) else { return };
        let devices: Vec<String> = group
            .members
            .iter()
            .filter(|(user, m)| m.status == "active" && Some(user.as_str()) != except_user)
            .filter_map(|(user, _)| self.device_of(user))
            .collect();
        for device in devices {
            self.events.entry(device).or_default().push(event.clone());
        }
    }
}

fn err(status: u16, code: &str) -> ApiError {
    ApiError::Api { status, code: code.to_owned(), message: String::new() }
}

fn verify(key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    geunal_mls::MlsClient::verify(key, message, signature).unwrap_or(false)
}

impl State {
    /// A single-use nonce and the device's proof over it.
    fn check_proof(&mut self, proof: &DeviceProof) -> Result<(), ApiError> {
        if !self.nonces.remove(&proof.nonce) || (proof.platform != "ios" && proof.platform != "android") {
            return Err(err(400, "invalid_request"));
        }
        if !verify(&proof.signature_key, proof.nonce.as_bytes(), &proof.signature) {
            return Err(err(400, "invalid_request"));
        }
        Ok(())
    }

    /// Registers `key` as the account's active device and revokes the one before it, which then hears when, where
    /// and how. The same key signing in again is the same device.
    fn register(
        &mut self,
        user_id: &str,
        key: &[u8],
        platform: &str,
        via: Option<&str>,
        new_account: bool,
    ) -> (AuthOutcome, ClientSession) {
        let now = self.now();
        let user = self.users.entry(user_id.to_owned()).or_default();
        let same = user.device.as_ref().filter(|d| d.signature_key == key).map(|d| d.device_id.clone());
        let device_id = same.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        let previous = user.device.as_ref().map(|d| d.device_id.clone()).filter(|_| same.is_none());
        let by_provider_at = (via == Some("provider")).then_some(now);
        if same.is_none() {
            user.device = Some(Device {
                device_id: device_id.clone(),
                signature_key: key.to_vec(),
                key_packages: VecDeque::new(),
                platform: platform.to_owned(),
                by_provider_at,
            });
        } else if let Some(device) = user.device.as_mut() {
            device.by_provider_at = by_provider_at.or(device.by_provider_at);
        }
        if let (Some(old), Some(via)) = (previous, via) {
            user.replaced = Some(Replaced {
                replaced_at: now.to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
                replaced_by: platform.to_owned(),
                replaced_via: via.to_owned(),
            });
            self.events.entry(old).or_default().push(json!({ "t": "revoke" }));
        }
        let recovery = self.users.get(user_id).is_some_and(|u| u.recovery.is_some());
        let access_token = self.next_id("tok-");
        let refresh_token = self.next_id("refresh-");
        self.tokens.insert(access_token.clone(), (user_id.to_owned(), device_id.clone()));
        self.refresh.insert(
            refresh_token.clone(),
            Refresh { user_id: user_id.to_owned(), device_id: device_id.clone(), spent: false },
        );
        let session = ClientSession {
            user_id: user_id.to_owned(),
            device_id: device_id.clone(),
            access_token: access_token.clone(),
            refresh_token: refresh_token.clone(),
        };
        let tokens =
            SessionInfo { user_id: user_id.to_owned(), device_id, access_token, expires_in: 900, refresh_token };
        (AuthOutcome::Registered { tokens, new_account, recovery }, session)
    }

    /// A provider-style sign-in (stub, Kakao, Apple): a new account, this device, or a ticket for a replacement.
    fn provider_sign_in(
        &mut self,
        subject: &str,
        proof: &DeviceProof,
        display_name: Option<&str>,
    ) -> Result<(AuthOutcome, Option<ClientSession>), ApiError> {
        let existing = self.subjects.get(subject).cloned();
        let Some(user_id) = existing else {
            let display_name = display_name.filter(|n| !n.is_empty()).ok_or_else(|| err(400, "invalid_request"))?;
            let user_id = uuid::Uuid::new_v4().to_string();
            self.subjects.insert(subject.to_owned(), user_id.clone());
            let user = self.users.entry(user_id.clone()).or_default();
            user.display_name = display_name.to_owned();
            if let Some(provider) = subject.split_once(':').map(|(p, _)| p).filter(|p| *p == "kakao" || *p == "apple") {
                user.providers.push(provider.to_owned());
            }
            let (outcome, session) =
                self.register(&user_id, &proof.signature_key, &proof.platform, Some("provider"), true);
            return Ok((outcome, Some(session)));
        };
        let active = self
            .users
            .get(&user_id)
            .and_then(|u| u.device.as_ref())
            .map(|d| (d.signature_key.clone(), d.platform.clone()));
        match active {
            Some((key, platform)) if key != proof.signature_key => {
                let ticket = self.next_id("ticket-");
                let expires_at = self.now() + Duration::minutes(30);
                let active_device = ActiveDevice {
                    platform,
                    created_at: Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
                };
                let recovery = self.users.get(&user_id).is_some_and(|u| u.recovery.is_some());
                self.tickets.insert(
                    ticket.clone(),
                    Ticket {
                        user_id: user_id.clone(),
                        signature_key: proof.signature_key.clone(),
                        platform: proof.platform.clone(),
                        expires_at,
                    },
                );
                Ok((AuthOutcome::ReplaceRequired { user_id, ticket, expires_in: 1800, active_device, recovery }, None))
            }
            _ => {
                let (outcome, session) =
                    self.register(&user_id, &proof.signature_key, &proof.platform, Some("provider"), false);
                Ok((outcome, Some(session)))
            }
        }
    }
}

#[derive(Clone, Default)]
pub struct FakeServer {
    state: Arc<Mutex<State>>,
}

impl FakeServer {
    pub fn new() -> Self {
        Self::default()
    }

    /// Pins the server clock; `received_at` values then advance one millisecond per call from here.
    pub fn set_now(&self, now: DateTime<Utc>) {
        self.state.lock().expect("fake server").now = Some(now);
    }

    pub fn client(&self) -> FakeClient {
        FakeClient { state: self.state.clone(), session: Mutex::new(None), listener: Mutex::new(None) }
    }

    /// The ID token a Kakao or Apple sign-in would return for `subject`, with `nonce` as its nonce claim: the app
    /// passes the `provider_nonce` of its sign-in nonce to the SDK, and the server checks it against the request.
    pub fn id_token(provider: &str, subject: &str, nonce: &str) -> String {
        format!("fake-id-token|{provider}|{subject}|{nonce}")
    }

    /// The authorization code a fresh Sign in with Apple would give for `subject`; the server exchanges it for an
    /// identity token with that `sub`.
    pub fn apple_code(subject: &str) -> String {
        format!("fake-apple-code|{subject}")
    }

    fn apple_subject(code: &str) -> Option<String> {
        code.strip_prefix("fake-apple-code|").map(|subject| format!("apple:{subject}"))
    }

    /// Lets every access token handed out so far run out, as fifteen minutes would.
    pub fn expire_access_tokens(&self) {
        let mut state = self.state.lock().expect("fake server");
        let tokens: Vec<String> = state.tokens.keys().cloned().collect();
        state.expired.extend(tokens);
    }

    /// The account's stored recovery material: wrapped AMK, recovery id and public key.
    pub fn recovery_of(&self, user_id: &str) -> Option<(Vec<u8>, Vec<u8>, Vec<u8>)> {
        let state = self.state.lock().expect("fake server");
        state
            .users
            .get(user_id)?
            .recovery
            .as_ref()
            .map(|r| (r.amk_wrapped.clone(), r.recovery_id.clone(), r.public_key.clone()))
    }

    pub fn providers_of(&self, user_id: &str) -> Vec<String> {
        let state = self.state.lock().expect("fake server");
        state.users.get(user_id).map(|u| u.providers.clone()).unwrap_or_default()
    }

    /// The account's active device, if any.
    pub fn active_device(&self, user_id: &str) -> Option<String> {
        self.state.lock().expect("fake server").device_of(user_id)
    }

    /// The account's completed backups, newest first.
    pub fn backups_of(&self, user_id: &str) -> Vec<BackupInfo> {
        let state = self.state.lock().expect("fake server");
        let mut list: Vec<BackupInfo> = state
            .users
            .get(user_id)
            .map(|u| u.backups.iter().filter(|b| b.completed_at.is_some()).map(backup_info).collect())
            .unwrap_or_default();
        list.reverse();
        list
    }

    /// How many times "새로 시작" deleted the account's backups.
    pub fn backups_cleared(&self, user_id: &str) -> u32 {
        self.state.lock().expect("fake server").users.get(user_id).map(|u| u.backups_cleared).unwrap_or(0)
    }

    /// The key packages the account's active device has waiting on the server.
    pub fn key_packages_of(&self, user_id: &str) -> usize {
        let state = self.state.lock().expect("fake server");
        state.users.get(user_id).and_then(|u| u.device.as_ref()).map(|d| d.key_packages.len()).unwrap_or(0)
    }

    /// Whether the account still exists: `DELETE /me` removes it.
    pub fn has_account(&self, user_id: &str) -> bool {
        self.state.lock().expect("fake server").users.contains_key(user_id)
    }

    /// Makes `holidays` the server's full table for `year`, as the daily job does once the year is announced.
    pub fn set_holidays(&self, year: i32, holidays: Vec<HolidayItem>) {
        self.state.lock().expect("fake server").holidays.insert(year, holidays);
    }

    /// How many times `GET /holidays` was called.
    pub fn holiday_reads(&self) -> u32 {
        self.state.lock().expect("fake server").holiday_reads
    }

    /// The account's alpha counts as the server keeps them, oldest day first.
    pub fn alpha_of(&self, user_id: &str) -> Vec<AlphaDay> {
        let state = self.state.lock().expect("fake server");
        state.alpha.iter().filter(|((user, _), _)| user == user_id).map(|(_, day)| day.clone()).collect()
    }

    /// The next call of `route` fails with a connection error before the server sees it. Routes: `me`, `replace`,
    /// `put_recovery`, `upload_key_packages`, `delete_account`, `post_alpha_stats`.
    pub fn fail_next(&self, route: &'static str) {
        self.state.lock().expect("fake server").faults.insert(route, Fault::Fail);
    }

    /// The server handles the next call of `route` and its answer is lost: the client sees a connection error.
    /// Routes: `put_recovery`, `delete_account`.
    pub fn lose_next(&self, route: &'static str) {
        self.state.lock().expect("fake server").faults.insert(route, Fault::Lose);
    }

    /// Drops every log item up to `through_seq`, as the daily partition job would after 30 days (api-server.md §8).
    pub fn expire_log(&self, group_id: &str, through_seq: i64) {
        let mut state = self.state.lock().expect("fake server");
        if let Some(group) = state.groups.get_mut(group_id) {
            group.log.retain(|item| item.seq > through_seq);
            group.dropped_through = group.dropped_through.max(through_seq);
        }
    }

    /// Replaces a member's blob with bytes no key opens, as a blob sealed in an epoch a reader lacks.
    pub fn corrupt_state(&self, group_id: &str, kind: &str, owner_id: &str) {
        let mut state = self.state.lock().expect("fake server");
        if let Some(blob) =
            state.groups.get_mut(group_id).and_then(|g| g.state.get_mut(&(kind.to_owned(), owner_id.to_owned())))
        {
            blob.ciphertext = vec![0; blob.ciphertext.len()];
        }
    }

    pub fn epoch_conflicts(&self) -> u32 {
        self.state.lock().expect("fake server").epoch_conflicts
    }

    pub fn stale_epochs(&self) -> u32 {
        self.state.lock().expect("fake server").stale_epochs
    }

    /// How many times `GET /groups/{id}/log` was called, to show an event was applied without a fetch.
    pub fn log_reads(&self) -> u32 {
        self.state.lock().expect("fake server").log_reads
    }

    pub fn group_epoch(&self, group_id: &str) -> Option<u64> {
        self.state.lock().expect("fake server").groups.get(group_id).map(|g| g.epoch)
    }

    pub fn log_len(&self, group_id: &str) -> usize {
        self.state.lock().expect("fake server").groups.get(group_id).map(|g| g.log.len()).unwrap_or(0)
    }

    pub fn state_blobs(&self, group_id: &str) -> Vec<StateBlob> {
        let state = self.state.lock().expect("fake server");
        state.groups.get(group_id).map(|g| g.state.values().cloned().collect()).unwrap_or_default()
    }

    /// The realtime events queued for a device, as `{"t": ..., ...}` JSON, drained.
    pub fn take_events(&self, device_id: &str) -> Vec<Value> {
        self.state.lock().expect("fake server").events.remove(device_id).unwrap_or_default()
    }
}

pub struct FakeClient {
    state: Arc<Mutex<State>>,
    session: Mutex<Option<ClientSession>>,
    listener: Mutex<Option<TokenListener>>,
}

impl FakeClient {
    /// Another client with the same session: the notification extension beside the app.
    pub fn clone_session(&self) -> FakeClient {
        FakeClient {
            state: self.state.clone(),
            session: Mutex::new(self.session.lock().expect("session").clone()),
            listener: Mutex::new(None),
        }
    }

    /// The caller, after the checks every authenticated request goes through: an expired access token is traded for
    /// new tokens as the HTTP transport does, a refresh token presented twice ends the device's sessions, and a device
    /// the account no longer has gets 403 `device_revoked`.
    fn who(&self) -> Result<(String, String), ApiError> {
        let mut session = self.session.lock().ok().and_then(|s| s.clone()).ok_or_else(|| err(401, "token_expired"))?;
        let mut state = self.lock();
        if !state.tokens.contains_key(&session.access_token) {
            return Err(err(401, "token_expired"));
        }
        if state.expired.contains(&session.access_token) {
            let refresh = state.refresh.get_mut(&session.refresh_token).ok_or_else(|| err(401, "token_expired"))?;
            if refresh.spent {
                // Reuse: whoever holds the spent token is not the device; end every session of the device.
                let device = refresh.device_id.clone();
                state.tokens.retain(|_, (_, d)| *d != device);
                state.refresh.retain(|_, r| r.device_id != device);
                return Err(err(401, "token_expired"));
            }
            refresh.spent = true;
            let (user_id, device_id) = (refresh.user_id.clone(), refresh.device_id.clone());
            let access_token = state.next_id("tok-");
            let refresh_token = state.next_id("refresh-");
            state.tokens.insert(access_token.clone(), (user_id.clone(), device_id.clone()));
            state.refresh.insert(
                refresh_token.clone(),
                Refresh { user_id: user_id.clone(), device_id: device_id.clone(), spent: false },
            );
            session.access_token = access_token.clone();
            session.refresh_token = refresh_token.clone();
            *self.session.lock().expect("session") = Some(session.clone());
            let listener = self.listener.lock().ok().and_then(|l| l.clone());
            drop(state);
            if let Some(listener) = listener {
                listener(&SessionInfo { user_id, device_id, access_token, expires_in: 900, refresh_token });
            }
            state = self.lock();
        }
        if state.device_of(&session.user_id).as_deref() != Some(session.device_id.as_str()) {
            let replaced = state.users.get(&session.user_id).and_then(|u| u.replaced.clone());
            return Err(ApiError::Revoked { replaced });
        }
        Ok((session.user_id, session.device_id))
    }

    fn keep(&self, outcome: AuthOutcome, session: Option<ClientSession>) -> AuthOutcome {
        if let Some(session) = session {
            *self.session.lock().expect("session") = Some(session);
        }
        outcome
    }

    /// The account and signature key a provider ID token from [`FakeServer::id_token`] stands for, after checking
    /// that its nonce claim binds it to `nonce` and `signature_key` (protocol §13 "nonce 묶기").
    fn provider_subject(provider: &str, id_token: &str, nonce: &str, signature_key: &[u8]) -> Result<String, ApiError> {
        let parts: Vec<&str> = id_token.split('|').collect();
        let bound = geunal_crypto::login::provider_nonce(nonce, signature_key);
        match parts.as_slice() {
            ["fake-id-token", p, subject, claim] if *p == provider && *claim == bound => {
                Ok(format!("{provider}:{subject}"))
            }
            _ => Err(err(401, "provider_token_invalid")),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().expect("fake server")
    }

    /// A fresh Kakao ID token bound to a live nonce and the device's registered key, or an Apple authorization code,
    /// for `user`'s provider account (protocol §13 "계정 삭제").
    fn provider_proof(
        state: &mut State,
        user: &str,
        key: &[u8],
        kakao_id_token: Option<&str>,
        apple_authorization_code: Option<&str>,
        nonce: Option<&str>,
    ) -> bool {
        let subject = match (kakao_id_token, apple_authorization_code, nonce) {
            (Some(id_token), None, Some(nonce)) if state.nonces.remove(nonce) => {
                Self::provider_subject("kakao", id_token, nonce, key).ok()
            }
            (None, Some(code), _) => FakeServer::apple_subject(code),
            _ => None,
        };
        subject.is_some_and(|s| state.subjects.get(&s).is_some_and(|u| u == user))
    }

    /// Who reads: the bearer of a replacement ticket, which may only read recovery material and backups, or the
    /// session.
    fn reader(&self, ticket: Option<&str>) -> Result<String, ApiError> {
        match ticket {
            Some(ticket) => {
                let mut state = self.lock();
                let now = state.now();
                let user = state.tickets.get(ticket).filter(|t| t.expires_at > now).map(|t| t.user_id.clone());
                user.ok_or_else(|| err(401, "token_expired"))
            }
            None => Ok(self.who()?.0),
        }
    }

    fn require_active<'a>(state: &'a mut State, group_id: &str, user: &str) -> Result<&'a mut Group, ApiError> {
        let group = state.groups.get_mut(group_id).ok_or_else(|| err(404, "not_found"))?;
        match group.members.get(user) {
            Some(m) if m.status == "active" => Ok(group),
            _ => Err(err(403, "not_member")),
        }
    }
}

impl Transport for FakeClient {
    async fn nonce(&self) -> Result<String, ApiError> {
        let mut state = self.lock();
        let nonce = state.next_id("nonce-");
        state.nonces.insert(nonce.clone());
        Ok(nonce)
    }

    async fn sign_in(&self, req: StubSignIn) -> Result<AuthOutcome, ApiError> {
        let proof = DeviceProof {
            platform: req.platform,
            signature_key: req.signature_key,
            nonce: req.nonce,
            signature: req.signature,
        };
        let mut state = self.lock();
        state.check_proof(&proof)?;
        let (outcome, session) = state.provider_sign_in(&req.subject, &proof, Some(&req.display_name))?;
        drop(state);
        Ok(self.keep(outcome, session))
    }

    async fn sign_in_provider(&self, provider: &str, req: ProviderSignIn) -> Result<AuthOutcome, ApiError> {
        let mut state = self.lock();
        state.check_proof(&req.proof)?;
        let subject = Self::provider_subject(provider, &req.id_token, &req.proof.nonce, &req.proof.signature_key)?;
        let new = !state.subjects.contains_key(&subject);
        if new && req.account.age_confirmed_at.is_none() {
            return Err(err(400, "invalid_request"));
        }
        let (outcome, session) = state.provider_sign_in(&subject, &req.proof, req.account.display_name.as_deref())?;
        drop(state);
        Ok(self.keep(outcome, session))
    }

    async fn sign_in_guest(&self, req: GuestSignIn) -> Result<AuthOutcome, ApiError> {
        let mut state = self.lock();
        state.check_proof(&req.proof)?;
        if req.display_name.is_empty() || req.age_confirmed_at.is_empty() {
            return Err(err(400, "invalid_request"));
        }
        let user_id = uuid::Uuid::new_v4().to_string();
        state.users.entry(user_id.clone()).or_default().display_name = req.display_name;
        let (outcome, session) = state.register(&user_id, &req.proof.signature_key, &req.proof.platform, None, true);
        drop(state);
        Ok(self.keep(outcome, Some(session)))
    }

    async fn recover(&self, req: RecoverSignIn) -> Result<AuthOutcome, ApiError> {
        let mut state = self.lock();
        state.check_proof(&req.proof)?;
        let found = state
            .users
            .iter()
            .find(|(_, u)| u.recovery.as_ref().is_some_and(|r| r.recovery_id == req.recovery_id))
            .map(|(id, u)| (id.clone(), u.providers.is_empty(), u.recovery.as_ref().map(|r| r.public_key.clone())));
        let Some((user_id, guest, Some(public_key))) = found else { return Err(err(401, "recovery_invalid")) };
        let message = [geunal_crypto::recovery::RECOVER_LABEL, req.proof.nonce.as_bytes()].concat();
        if !guest || !verify(&public_key, &message, &req.recovery_signature) {
            return Err(err(401, "recovery_invalid"));
        }
        let (outcome, session) =
            state.register(&user_id, &req.proof.signature_key, &req.proof.platform, Some("recovery"), false);
        drop(state);
        Ok(self.keep(outcome, Some(session)))
    }

    async fn replace(&self, req: ReplaceRequest) -> Result<AuthOutcome, ApiError> {
        let mut state = self.lock();
        state.fault("replace", Fault::Fail)?;
        let now = state.now();
        let ticket = state.tickets.remove(&req.ticket).filter(|t| t.expires_at > now);
        let Some(ticket) = ticket else { return Err(err(401, "token_expired")) };
        if !verify(&ticket.signature_key, &geunal_crypto::login::replace_message(&req.ticket), &req.signature) {
            state.tickets.insert(req.ticket, ticket);
            return Err(err(400, "invalid_request"));
        }
        let (outcome, session) =
            state.register(&ticket.user_id, &ticket.signature_key, &ticket.platform, Some("provider"), false);
        drop(state);
        Ok(self.keep(outcome, Some(session)))
    }

    async fn link(&self, req: LinkRequest) -> Result<Vec<String>, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let key = state.users.get(&user).and_then(|u| u.device.as_ref()).map(|d| d.signature_key.clone());
        let key = key.ok_or_else(|| err(403, "device_revoked"))?;
        if !state.nonces.remove(&req.nonce) {
            return Err(err(400, "invalid_request"));
        }
        let subject = Self::provider_subject(&req.provider, &req.id_token, &req.nonce, &key)?;
        match state.subjects.get(&subject) {
            Some(owner) if *owner == user => return Err(err(409, "already_linked")),
            Some(_) => return Err(err(409, "account_exists")),
            None => {}
        }
        let account = state.users.get_mut(&user).expect("the session's account exists");
        if account.providers.contains(&req.provider) {
            return Err(err(409, "already_linked"));
        }
        account.providers.push(req.provider.clone());
        let providers = account.providers.clone();
        state.subjects.insert(subject, user);
        Ok(providers)
    }

    async fn me(&self) -> Result<MeInfo, ApiError> {
        self.lock().fault("me", Fault::Fail)?;
        let (user, _) = self.who()?;
        let state = self.lock();
        let account = state.users.get(&user).ok_or_else(|| err(404, "not_found"))?;
        Ok(MeInfo {
            user_id: user.clone(),
            display_name: account.display_name.clone(),
            key_packages: account.device.as_ref().map(|d| d.key_packages.len() as i64).unwrap_or(0),
            providers: account.providers.clone(),
            recovery: account.recovery.is_some(),
        })
    }

    async fn put_recovery(&self, req: RecoveryUpload) -> Result<(), ApiError> {
        self.lock().fault("put_recovery", Fault::Fail)?;
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let now = state.now();
        if req.amk_wrapped.len() != 72 || req.recovery_id.len() != 16 || req.recovery_public_key.len() != 32 {
            return Err(err(400, "invalid_request"));
        }
        if state
            .users
            .iter()
            .any(|(id, u)| *id != user && u.recovery.as_ref().is_some_and(|r| r.recovery_id == req.recovery_id))
        {
            return Err(err(409, "recovery_conflict"));
        }
        let account = state.users.get(&user).expect("the session's account exists");
        if let Some(current) = &account.recovery {
            if req.new_key {
                // Without the code the account proves itself through its provider: a session from a provider
                // sign-in under 30 minutes old, or a fresh provider proof in the body (protocol §10 "새 키로 시작").
                let device = account.device.as_ref().ok_or_else(|| err(403, "device_revoked"))?;
                let fresh = device.by_provider_at.is_some_and(|at| now - at < Duration::minutes(30));
                let key = device.signature_key.clone();
                let (kakao, apple, nonce) =
                    (req.kakao_id_token.as_deref(), req.apple_authorization_code.as_deref(), req.nonce.as_deref());
                if !fresh && !Self::provider_proof(&mut state, &user, &key, kakao, apple, nonce) {
                    return Err(err(403, "reauth_required"));
                }
            } else {
                let Some((nonce, proof)) = req.nonce.as_ref().zip(req.proof.as_ref()) else {
                    return Err(err(403, "recovery_proof_required"));
                };
                let message = [geunal_crypto::recovery::ROTATE_LABEL, nonce.as_bytes()].concat();
                let public_key = current.public_key.clone();
                if !state.nonces.remove(nonce) || !verify(&public_key, &message, proof) {
                    return Err(err(403, "recovery_proof_required"));
                }
            }
        }
        let updated_at = now.to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
        let account = state.users.get_mut(&user).expect("the session's account exists");
        if req.new_key && account.recovery.is_some() {
            account.backups_cleared += 1;
            account.backups.clear();
        }
        account.recovery = Some(Recovery {
            amk_wrapped: req.amk_wrapped,
            recovery_id: req.recovery_id,
            public_key: req.recovery_public_key,
            updated_at,
        });
        state.fault("put_recovery", Fault::Lose)
    }

    async fn get_recovery(&self, ticket: Option<&str>) -> Result<StoredRecovery, ApiError> {
        let user = self.reader(ticket)?;
        let state = self.lock();
        let recovery = state.users.get(&user).and_then(|u| u.recovery.as_ref()).ok_or_else(|| err(404, "not_found"))?;
        Ok(StoredRecovery { amk_wrapped: recovery.amk_wrapped.clone(), updated_at: recovery.updated_at.clone() })
    }

    async fn start_backup(&self, size: u64) -> Result<BackupUpload, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        if size == 0 {
            return Err(err(400, "invalid_request"));
        }
        if size > MAX_BACKUP {
            return Err(err(413, "too_large"));
        }
        if state.users.get(&user).is_none_or(|u| u.recovery.is_none()) {
            return Err(err(409, "recovery_missing"));
        }
        let id = i64::try_from(state.counter).unwrap_or(i64::MAX);
        state.counter += 1;
        let url = format!("fake-object://backups/{user}/{id}");
        let account = state.users.get_mut(&user).expect("the session's account exists");
        account.backups.push(Backup { id, size, url: url.clone(), object: None, completed_at: None });
        Ok(BackupUpload { id, upload_url: url, expires_in: 900 })
    }

    async fn put_object(&self, url: &str, body: Vec<u8>) -> Result<(), ApiError> {
        let mut state = self.lock();
        let backup = state
            .users
            .values_mut()
            .flat_map(|u| u.backups.iter_mut())
            .find(|b| b.url == url && b.completed_at.is_none())
            .ok_or_else(|| err(403, "http_403"))?;
        // The URL is signed with the declared Content-Length.
        if body.len() as u64 != backup.size {
            return Err(err(403, "http_403"));
        }
        backup.object = Some(body);
        Ok(())
    }

    async fn complete_backup(&self, id: i64) -> Result<BackupInfo, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let now = state.now_text();
        let account = state.users.get_mut(&user).expect("the session's account exists");
        let backup = account.backups.iter_mut().find(|b| b.id == id).ok_or_else(|| err(404, "not_found"))?;
        if backup.object.as_ref().map(|o| o.len() as u64) != Some(backup.size) {
            return Err(err(409, "upload_incomplete"));
        }
        backup.completed_at.get_or_insert(now);
        let info = backup_info(backup);
        // The newest three completed backups stay.
        let completed: Vec<i64> = account.backups.iter().filter(|b| b.completed_at.is_some()).map(|b| b.id).collect();
        let dropped: Vec<i64> = completed.iter().take(completed.len().saturating_sub(3)).copied().collect();
        account.backups.retain(|b| !dropped.contains(&b.id));
        Ok(info)
    }

    async fn backups(&self, ticket: Option<&str>) -> Result<Vec<BackupInfo>, ApiError> {
        let user = self.reader(ticket)?;
        let state = self.lock();
        let mut list: Vec<BackupInfo> = state
            .users
            .get(&user)
            .map(|u| u.backups.iter().filter(|b| b.completed_at.is_some()).map(backup_info).collect())
            .unwrap_or_default();
        list.reverse();
        Ok(list)
    }

    async fn latest_backup(&self, ticket: Option<&str>) -> Result<Option<BackupDownload>, ApiError> {
        let user = self.reader(ticket)?;
        let state = self.lock();
        let latest = state.users.get(&user).and_then(|u| u.backups.iter().rev().find(|b| b.completed_at.is_some()));
        Ok(latest.map(|b| BackupDownload { backup: backup_info(b), download_url: b.url.clone(), expires_in: 900 }))
    }

    async fn get_object(&self, url: &str, progress: Progress<'_>) -> Result<Vec<u8>, ApiError> {
        let object = {
            let state = self.lock();
            let found = state
                .users
                .values()
                .flat_map(|u| u.backups.iter())
                .find(|b| b.url == url && b.completed_at.is_some())
                .and_then(|b| b.object.clone());
            found.ok_or_else(|| err(404, "http_404"))?
        };
        // In 64 KiB steps, as a download reports them.
        let total = object.len() as u64;
        for done in (0..object.len()).step_by(64 << 10).skip(1) {
            progress(done as u64, total);
        }
        progress(total, total);
        Ok(object)
    }

    async fn delete_account(&self, req: DeleteAccount) -> Result<(), ApiError> {
        self.lock().fault("delete_account", Fault::Fail)?;
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let account = state.users.get(&user).expect("the session's account exists");
        let key = account.device.as_ref().map(|d| d.signature_key.clone()).ok_or_else(|| err(403, "device_revoked"))?;
        let proven = if account.providers.is_empty() {
            // A guest proves the account with its recovery key, which the server has to hold first.
            let public_key =
                account.recovery.as_ref().map(|r| r.public_key.clone()).ok_or_else(|| err(409, "recovery_missing"))?;
            match (&req.nonce, &req.recovery_signature) {
                (Some(nonce), Some(signature)) => {
                    let message = [geunal_crypto::recovery::DELETE_LABEL, nonce.as_bytes()].concat();
                    state.nonces.remove(nonce) && verify(&public_key, &message, signature)
                }
                _ => false,
            }
        } else {
            let (kakao, apple, nonce) =
                (req.kakao_id_token.as_deref(), req.apple_authorization_code.as_deref(), req.nonce.as_deref());
            Self::provider_proof(&mut state, &user, &key, kakao, apple, nonce)
        };
        if !proven {
            return Err(err(403, "reauth_required"));
        }
        // One transaction: the account with its device, sessions, recovery material, backups, memberships and alpha
        // counts. The members left in its groups hear of the membership change.
        state.users.remove(&user);
        state.subjects.retain(|_, u| *u != user);
        state.tokens.retain(|_, (u, _)| *u != user);
        state.refresh.retain(|_, r| r.user_id != user);
        state.tickets.retain(|_, t| t.user_id != user);
        state.alpha.retain(|(u, _), _| *u != user);
        let groups: Vec<String> =
            state.groups.iter().filter(|(_, g)| g.members.contains_key(&user)).map(|(id, _)| id.clone()).collect();
        for group_id in groups {
            if let Some(member) = state.groups.get_mut(&group_id).and_then(|g| g.members.get_mut(&user)) {
                member.status = "removed".into();
            }
            state.publish(&group_id, None, json!({"t": "membership", "g": group_id}));
        }
        state.fault("delete_account", Fault::Lose)
    }

    async fn holidays(&self, from_year: i32, to_year: i32) -> Result<HolidayYears, ApiError> {
        let mut state = self.lock();
        state.holiday_reads += 1;
        let next_year = state.today().year() + 1;
        if to_year < from_year || to_year > from_year + 1 || from_year > next_year {
            return Err(err(400, "invalid_request"));
        }
        let years: Vec<i32> = (from_year..=to_year).filter(|y| state.holidays.contains_key(y)).collect();
        let holidays = years.iter().flat_map(|y| state.holidays[y].iter().cloned()).collect();
        Ok(HolidayYears { years, holidays })
    }

    async fn post_alpha_stats(&self, days: Vec<AlphaDay>) -> Result<(), ApiError> {
        self.lock().fault("post_alpha_stats", Fault::Fail)?;
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let today = state.today();
        // The last 14 Korean days, today included.
        let recent = |day: &str| {
            chrono::NaiveDate::parse_from_str(day, "%Y-%m-%d")
                .is_ok_and(|d| d <= today && today - d < Duration::days(14))
        };
        if days.is_empty() || days.len() > 14 || days.iter().any(|d| d.suggested > d.events || !recent(&d.day)) {
            return Err(err(400, "invalid_request"));
        }
        for day in days {
            state.alpha.insert((user.clone(), day.day.clone()), day);
        }
        Ok(())
    }

    fn restore_session(&self, access_token: &str, refresh_token: &str) {
        let who = self.lock().tokens.get(access_token).cloned();
        *self.session.lock().expect("session") = who.map(|(user_id, device_id)| ClientSession {
            user_id,
            device_id,
            access_token: access_token.to_owned(),
            refresh_token: refresh_token.to_owned(),
        });
    }

    fn clear_session(&self) {
        *self.session.lock().expect("session") = None;
    }

    fn set_token_listener(&self, listener: TokenListener) {
        *self.listener.lock().expect("listener") = Some(listener);
    }

    async fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> Result<(), ApiError> {
        self.lock().fault("upload_key_packages", Fault::Fail)?;
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let device =
            state.users.get_mut(&user).and_then(|u| u.device.as_mut()).ok_or_else(|| err(403, "device_revoked"))?;
        device.key_packages.extend(packages);
        Ok(())
    }

    async fn devices(&self, user_id: &str) -> Result<Vec<DeviceInfo>, ApiError> {
        self.who()?;
        let state = self.lock();
        Ok(state
            .users
            .get(user_id)
            .and_then(|u| u.device.as_ref())
            .map(|d| DeviceInfo {
                id: d.device_id.clone(),
                platform: "android".into(),
                signature_key: d.signature_key.clone(),
            })
            .into_iter()
            .collect())
    }

    async fn claim_key_package(&self, device_id: &str) -> Result<Vec<u8>, ApiError> {
        self.who()?;
        let mut state = self.lock();
        let device = state
            .users
            .values_mut()
            .filter_map(|u| u.device.as_mut())
            .find(|d| d.device_id == device_id)
            .ok_or_else(|| err(404, "not_found"))?;
        device.key_packages.pop_front().ok_or_else(|| err(404, "not_found"))
    }

    async fn my_groups(&self) -> Result<Vec<GroupSummary>, ApiError> {
        let (user, _) = self.who()?;
        let state = self.lock();
        Ok(state
            .groups
            .iter()
            .filter_map(|(id, g)| {
                let m = g.members.get(&user).filter(|m| m.status != "removed")?;
                Some(GroupSummary {
                    id: id.clone(),
                    kind: g.kind.clone(),
                    epoch: g.epoch,
                    last_seq: g.next_seq - 1,
                    role: m.role.clone(),
                    status: m.status.clone(),
                })
            })
            .collect())
    }

    async fn create_group(&self) -> Result<GroupSummary, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let group_id = uuid::Uuid::new_v4().to_string();
        let mut members = BTreeMap::new();
        members.insert(user, Member { role: "owner".into(), status: "active".into() });
        state.groups.insert(
            group_id.clone(),
            Group {
                kind: "circle".into(),
                epoch: 0,
                next_seq: 1,
                group_info: None,
                members,
                log: Vec::new(),
                dropped_through: 0,
                state: BTreeMap::new(),
            },
        );
        Ok(GroupSummary {
            id: group_id,
            kind: "circle".into(),
            epoch: 0,
            last_seq: 0,
            role: String::new(),
            status: String::new(),
        })
    }

    async fn put_group_info(&self, group_id: &str, info: GroupInfo) -> Result<(), ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let group = Self::require_active(&mut state, group_id, &user)?;
        if info.epoch != group.epoch {
            return Err(err(409, "stale_epoch"));
        }
        group.group_info = Some(info);
        Ok(())
    }

    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        let (user, _) = self.who()?;
        let state = self.lock();
        let group = state.groups.get(group_id).ok_or_else(|| err(404, "not_found"))?;
        if !group.members.get(&user).is_some_and(|m| m.status == "active") {
            return Err(err(403, "not_member"));
        }
        Ok(group
            .members
            .iter()
            .map(|(u, m)| MemberInfo {
                user_id: u.clone(),
                display_name: String::new(),
                device_id: state.device_of(u),
                role: m.role.clone(),
                status: m.status.clone(),
            })
            .collect())
    }

    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let group = Self::require_active(&mut state, group_id, &user)?;
        let is_owner = group.members.get(&user).is_some_and(|m| m.role == "owner");
        if user != user_id && !is_owner {
            return Err(err(403, "not_owner"));
        }
        let member = group.members.get_mut(user_id).ok_or_else(|| err(404, "not_found"))?;
        if member.status == "removed" {
            return Err(err(404, "not_found"));
        }
        member.status = "removed".into();
        state.publish(group_id, None, json!({"t": "membership", "g": group_id}));
        if let Some(device) = state.device_of(user_id) {
            state.events.entry(device).or_default().push(json!({"t": "membership", "g": group_id}));
        }
        Ok(())
    }

    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<CommitAccepted, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let received_at = state.now_text();
        Self::require_active(&mut state, group_id, &user)?;
        if req.added.is_empty() != req.welcome.is_none() {
            return Err(err(400, "invalid_request"));
        }
        let added_users: Vec<(String, String)> = req
            .added
            .iter()
            .map(|d| state.user_of_device(d).map(|u| (u, d.clone())).ok_or_else(|| err(404, "not_found")))
            .collect::<Result<_, _>>()?;
        let group = state.groups.get_mut(group_id).expect("checked");
        if req.epoch != group.epoch {
            state.epoch_conflicts += 1;
            return Err(err(409, "epoch_conflict"));
        }
        let seq = group.next_seq;
        group.next_seq += 1;
        group.epoch += 1;
        group.group_info = Some(GroupInfo { epoch: group.epoch, group_info: req.group_info });
        group.log.push(LogItem {
            seq,
            epoch: req.epoch,
            kind: "commit".into(),
            author_id: user.clone(),
            ciphertext: req.commit.clone(),
            received_at: received_at.clone(),
        });
        for (added_user, _) in &added_users {
            match group.members.get_mut(added_user) {
                Some(m) => m.status = "active".into(),
                None => {
                    group.members.insert(added_user.clone(), Member { role: "member".into(), status: "active".into() });
                }
            }
        }
        let small = req.commit.len() <= 4096;
        let event = json!({"t": "entry", "g": group_id, "s": seq, "kind": "commit", "at": received_at, "c": small.then(|| b64::encode(&req.commit))});
        state.publish(group_id, Some(&user), event);
        if let Some(welcome) = req.welcome {
            for (_, device_id) in added_users {
                // The joiner gets its Welcome, not the entry event for the commit that added it.
                if let Some(events) = state.events.get_mut(&device_id) {
                    events.retain(|e| e["s"] != json!(seq));
                }
                state.welcomes.entry(device_id.clone()).or_default().push(WelcomeItem {
                    group_id: group_id.to_owned(),
                    seq: Some(seq),
                    data: welcome.clone(),
                });
                state.events.entry(device_id).or_default().push(json!({"t": "welcome"}));
            }
        }
        Ok(CommitAccepted { seq, epoch: req.epoch, received_at: Some(received_at) })
    }

    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<MessageAccepted, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let received_at = state.now_text();
        let group = Self::require_active(&mut state, group_id, &user)?;
        if req.epoch != group.epoch {
            state.stale_epochs += 1;
            return Err(err(409, "stale_epoch"));
        }
        if req.message.len() > 65536 {
            return Err(err(413, "too_large"));
        }
        let seq = group.next_seq;
        group.next_seq += 1;
        group.log.push(LogItem {
            seq,
            epoch: req.epoch,
            kind: "app".into(),
            author_id: user.clone(),
            ciphertext: req.message.clone(),
            received_at: received_at.clone(),
        });
        let small = req.message.len() <= 4096;
        let event = json!({"t": "entry", "g": group_id, "s": seq, "kind": "app", "at": received_at, "c": small.then(|| b64::encode(&req.message))});
        state.publish(group_id, Some(&user), event);
        Ok(MessageAccepted { seq, received_at: Some(received_at) })
    }

    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<LogPage, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        state.log_reads += 1;
        let group = Self::require_active(&mut state, group_id, &user)?;
        let last_seq = group.next_seq - 1;
        let entries: Vec<LogItem> = group.log.iter().filter(|i| i.seq > after).take(limit as usize).cloned().collect();
        // The API's rule: the item right after the cursor is gone when the first one back is later, or when nothing
        // came back although the group has moved on.
        let expired = entries.first().map(|first| first.seq != after + 1).unwrap_or(after < last_seq);
        if expired {
            return Err(err(410, "cursor_expired"));
        }
        Ok(LogPage { entries, last_seq })
    }

    async fn group_info(&self, group_id: &str) -> Result<GroupInfo, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        Self::require_active(&mut state, group_id, &user)?.group_info.clone().ok_or_else(|| err(404, "not_found"))
    }

    async fn welcomes(&self) -> Result<Vec<WelcomeItem>, ApiError> {
        let (_, device) = self.who()?;
        Ok(self.lock().welcomes.remove(&device).unwrap_or_default())
    }

    async fn put_state(&self, group_id: &str, kind: &str, req: PutState) -> Result<i64, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let updated_at = state.now_text();
        let group = Self::require_active(&mut state, group_id, &user)?;
        if req.epoch > group.epoch {
            return Err(err(400, "invalid_request"));
        }
        let slot = (kind.to_owned(), user.clone());
        let version = match group.state.get_mut(&slot) {
            Some(existing) if existing.epoch > req.epoch => return Err(err(409, "stale_epoch")),
            Some(existing) => {
                existing.epoch = req.epoch;
                existing.ciphertext = req.ciphertext;
                existing.version += 1;
                existing.updated_at = updated_at;
                existing.version
            }
            None => {
                group.state.insert(
                    slot,
                    StateBlob {
                        kind: kind.to_owned(),
                        owner_id: user.clone(),
                        epoch: req.epoch,
                        version: 1,
                        ciphertext: req.ciphertext,
                        updated_at,
                    },
                );
                1
            }
        };
        let event = json!({"t": "state", "g": group_id, "kind": kind, "owner": user, "epoch": req.epoch});
        state.publish(group_id, Some(&user), event);
        Ok(version)
    }

    async fn state(&self, group_id: &str) -> Result<Vec<StateBlob>, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        Ok(Self::require_active(&mut state, group_id, &user)?.state.values().cloned().collect())
    }
}

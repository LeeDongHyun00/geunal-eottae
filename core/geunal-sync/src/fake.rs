//! An in-process API server with the rules the engine depends on (protocol §6, §11, api-server.md §5): one commit per
//! epoch, messages only in the current epoch, a 30-day mailbox that answers 410 behind its floor, Welcomes filed per
//! device, state blobs that never go back an epoch. Several [`FakeClient`]s share one server, so tests run three
//! devices in one process and the end-to-end test keeps the same engine code against the real stack.

use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::sync::{Arc, Mutex};

use chrono::{DateTime, Duration, Utc};
use serde_json::{Value, json};

use crate::api::*;

#[derive(Default)]
struct Device {
    device_id: String,
    signature_key: Vec<u8>,
    key_packages: VecDeque<Vec<u8>>,
}

#[derive(Default)]
struct User {
    device: Option<Device>,
}

struct Member {
    role: String,
    status: String,
}

struct Group {
    kind: String,
    epoch: u64,
    next_seq: i64,
    group_info: Vec<u8>,
    members: BTreeMap<String, Member>,
    log: Vec<LogItem>,
    /// Items up to this seq have left the mailbox; a cursor behind it is expired.
    dropped_through: i64,
    state: BTreeMap<(String, String), StateBlob>,
}

#[derive(Default)]
struct State {
    users: BTreeMap<String, User>,
    tokens: BTreeMap<String, (String, String)>,
    nonces: BTreeSet<String>,
    groups: BTreeMap<String, Group>,
    invites: BTreeMap<String, String>,
    welcomes: BTreeMap<String, Vec<WelcomeItem>>,
    /// Realtime events the API would publish, per device id, as the gateway forwards them (protocol §7).
    events: BTreeMap<String, Vec<Value>>,
    counter: u64,
    now: Option<DateTime<Utc>>,
    epoch_conflicts: u32,
    stale_epochs: u32,
}

impl State {
    fn now(&mut self) -> DateTime<Utc> {
        // Each call moves the clock one millisecond so received_at stays strictly increasing and deterministic.
        let now = self.now.unwrap_or_else(Utc::now) + Duration::milliseconds(1);
        self.now = Some(now);
        now
    }

    fn now_text(&mut self) -> String {
        self.now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
    }

    fn next_id(&mut self, prefix: &str) -> String {
        self.counter += 1;
        format!("{prefix}{}", self.counter)
    }

    fn publish(&mut self, group_id: &str, except_user: Option<&str>, event: Value) {
        let Some(group) = self.groups.get(group_id) else { return };
        let devices: Vec<String> = group
            .members
            .iter()
            .filter(|(user, m)| m.status == "active" && Some(user.as_str()) != except_user)
            .filter_map(|(user, _)| self.users.get(user).and_then(|u| u.device.as_ref()).map(|d| d.device_id.clone()))
            .collect();
        for device in devices {
            self.events.entry(device).or_default().push(event.clone());
        }
    }
}

fn err(status: u16, code: &str) -> ApiError {
    ApiError::Api { status, code: code.to_owned(), message: String::new() }
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
        FakeClient { state: self.state.clone(), session: Mutex::new(None) }
    }

    /// Drops every log item up to `through_seq`, as the daily partition job would after 30 days (api-server.md §8).
    pub fn expire_log(&self, group_id: &str, through_seq: i64) {
        let mut state = self.state.lock().expect("fake server");
        if let Some(group) = state.groups.get_mut(group_id) {
            group.log.retain(|item| item.seq > through_seq);
            group.dropped_through = group.dropped_through.max(through_seq);
        }
    }

    /// Replaces a member's availability blob with bytes no key opens, as a blob sealed in an epoch a reader lacks.
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

    pub fn group_epoch(&self, group_id: &str) -> Option<u64> {
        self.state.lock().expect("fake server").groups.get(group_id).map(|g| g.epoch)
    }

    pub fn log_len(&self, group_id: &str) -> usize {
        self.state.lock().expect("fake server").groups.get(group_id).map(|g| g.log.len()).unwrap_or(0)
    }

    pub fn state_blobs(&self, group_id: &str) -> Vec<StateBlob> {
        self.state
            .lock()
            .expect("fake server")
            .groups
            .get(group_id)
            .map(|g| g.state.values().cloned().collect())
            .unwrap_or_default()
    }

    /// The realtime events queued for a device, as `{"t": ..., ...}` JSON, drained.
    pub fn take_events(&self, device_id: &str) -> Vec<Value> {
        self.state.lock().expect("fake server").events.remove(device_id).unwrap_or_default()
    }
}

pub struct FakeClient {
    state: Arc<Mutex<State>>,
    session: Mutex<Option<(String, String)>>,
}

impl FakeClient {
    /// Another client with the same session: the notification extension beside the app.
    pub fn clone_session(&self) -> FakeClient {
        FakeClient { state: self.state.clone(), session: Mutex::new(self.session.lock().expect("session").clone()) }
    }

    fn who(&self) -> Result<(String, String), ApiError> {
        self.session.lock().ok().and_then(|s| s.clone()).ok_or_else(|| err(401, "token_expired"))
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().expect("fake server")
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

    async fn sign_in_guest(&self, req: GuestSignIn) -> Result<SessionInfo, ApiError> {
        let mut state = self.lock();
        if !state.nonces.remove(&req.nonce) {
            return Err(err(401, "bad_nonce"));
        }
        if req.platform != "ios" && req.platform != "android" {
            return Err(err(400, "bad_platform"));
        }
        if !geunal_mls::MlsClient::verify(&req.signature_key, req.nonce.as_bytes(), &req.signature).unwrap_or(false) {
            return Err(err(401, "bad_signature"));
        }
        let user_id = uuid::Uuid::new_v4().to_string();
        let device_id = uuid::Uuid::new_v4().to_string();
        let access_token = state.next_id("tok-");
        state.users.insert(
            user_id.clone(),
            User {
                device: Some(Device {
                    device_id: device_id.clone(),
                    signature_key: req.signature_key,
                    key_packages: VecDeque::new(),
                }),
            },
        );
        state.tokens.insert(access_token.clone(), (user_id.clone(), device_id.clone()));
        *self.session.lock().expect("session") = Some((user_id.clone(), device_id.clone()));
        Ok(SessionInfo { user_id, device_id, access_token, refresh_token: state.next_id("refresh-") })
    }

    fn restore_session(&self, access_token: &str) {
        let who = self.lock().tokens.get(access_token).cloned();
        *self.session.lock().expect("session") = who;
    }

    async fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> Result<(), ApiError> {
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
        let user = state.users.get(user_id).ok_or_else(|| err(404, "not_found"))?;
        Ok(user
            .device
            .iter()
            .map(|d| DeviceInfo { device_id: d.device_id.clone(), signature_key: d.signature_key.clone() })
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
            .filter(|(_, g)| g.members.get(&user).is_some_and(|m| m.status == "active"))
            .map(|(id, g)| GroupSummary {
                group_id: id.clone(),
                kind: g.kind.clone(),
                epoch: g.epoch,
                latest_seq: g.next_seq - 1,
            })
            .collect())
    }

    async fn create_group(&self, group_id: &str, group_info: Vec<u8>) -> Result<(), ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        if state.groups.contains_key(group_id) {
            return Err(err(409, "exists"));
        }
        let mut members = BTreeMap::new();
        members.insert(user, Member { role: "owner".into(), status: "active".into() });
        state.groups.insert(
            group_id.to_owned(),
            Group {
                kind: "circle".into(),
                epoch: 0,
                next_seq: 1,
                group_info,
                members,
                log: Vec::new(),
                dropped_through: 0,
                state: BTreeMap::new(),
            },
        );
        Ok(())
    }

    async fn create_invite(&self, group_id: &str) -> Result<String, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        Self::require_active(&mut state, group_id, &user)?;
        let code = state.next_id("INVITE");
        state.invites.insert(code.clone(), group_id.to_owned());
        Ok(code)
    }

    async fn join_invite(&self, code: &str) -> Result<String, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let group_id = state.invites.get(code).cloned().ok_or_else(|| err(404, "not_found"))?;
        let group = state.groups.get_mut(&group_id).ok_or_else(|| err(404, "not_found"))?;
        match group.members.get_mut(&user) {
            Some(m) if m.status == "active" => {}
            Some(m) => m.status = "pending".into(),
            None => {
                group.members.insert(user.clone(), Member { role: "member".into(), status: "pending".into() });
            }
        }
        state.publish(&group_id, None, json!({"t": "membership", "g": group_id}));
        Ok(group_id)
    }

    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        let (user, _) = self.who()?;
        let state = self.lock();
        let group = state.groups.get(group_id).ok_or_else(|| err(404, "not_found"))?;
        if !group.members.contains_key(&user) {
            return Err(err(403, "not_member"));
        }
        Ok(group
            .members
            .iter()
            .map(|(u, m)| MemberInfo { user_id: u.clone(), role: m.role.clone(), status: m.status.clone() })
            .collect())
    }

    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let group = Self::require_active(&mut state, group_id, &user)?;
        let is_owner = group.members.get(&user).is_some_and(|m| m.role == "owner");
        if user != user_id && !is_owner {
            return Err(err(403, "not_member"));
        }
        let member = group.members.get_mut(user_id).ok_or_else(|| err(404, "not_found"))?;
        member.status = "removed".into();
        state.publish(group_id, None, json!({"t": "membership", "g": group_id}));
        Ok(())
    }

    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<Accepted, ApiError> {
        let (user, device) = self.who()?;
        let mut state = self.lock();
        let received_at = state.now_text();
        Self::require_active(&mut state, group_id, &user)?;
        let current_device = state.users.get(&user).and_then(|u| u.device.as_ref()).map(|d| d.device_id.clone());
        if req.external && current_device.as_deref() != Some(device.as_str()) {
            return Err(err(403, "device_revoked"));
        }
        let added_devices: Vec<String> = req
            .added_user_ids
            .iter()
            .filter_map(|u| state.users.get(u).and_then(|u| u.device.as_ref()).map(|d| d.device_id.clone()))
            .collect();
        let group = state.groups.get_mut(group_id).expect("checked");
        if req.epoch != group.epoch {
            state.epoch_conflicts += 1;
            return Err(err(409, "epoch_conflict"));
        }
        let seq = group.next_seq;
        group.next_seq += 1;
        group.epoch += 1;
        group.group_info = req.group_info;
        group.log.push(LogItem {
            seq,
            epoch: req.epoch,
            kind: "commit".into(),
            author_id: user.clone(),
            ciphertext: req.commit.clone(),
            received_at: received_at.clone(),
        });
        for added in &req.added_user_ids {
            if let Some(m) = group.members.get_mut(added) {
                m.status = "active".into();
            }
        }
        if let Some(welcome) = req.welcome {
            for device_id in added_devices {
                state.welcomes.entry(device_id.clone()).or_default().push(WelcomeItem {
                    group_id: group_id.to_owned(),
                    seq,
                    welcome: welcome.clone(),
                });
                state.events.entry(device_id).or_default().push(json!({"t": "welcome"}));
            }
        }
        let small = req.commit.len() <= 4096;
        let event = json!({"t": "entry", "g": group_id, "s": seq, "kind": "commit", "c": small.then(|| b64::encode(&req.commit))});
        state.publish(group_id, Some(&user), event);
        Ok(Accepted { seq, received_at })
    }

    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<Accepted, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let received_at = state.now_text();
        let group = Self::require_active(&mut state, group_id, &user)?;
        if req.epoch != group.epoch {
            state.stale_epochs += 1;
            return Err(err(409, "stale_epoch"));
        }
        if req.ciphertext.len() > 65536 {
            return Err(err(413, "too_large"));
        }
        let seq = group.next_seq;
        group.next_seq += 1;
        group.log.push(LogItem {
            seq,
            epoch: req.epoch,
            kind: "app".into(),
            author_id: user.clone(),
            ciphertext: req.ciphertext.clone(),
            received_at: received_at.clone(),
        });
        let small = req.ciphertext.len() <= 4096;
        let event = json!({"t": "entry", "g": group_id, "s": seq, "kind": "app", "c": small.then(|| b64::encode(&req.ciphertext))});
        state.publish(group_id, Some(&user), event);
        Ok(Accepted { seq, received_at })
    }

    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<Vec<LogItem>, ApiError> {
        let (user, _) = self.who()?;
        let state = self.lock();
        let group = state.groups.get(group_id).ok_or_else(|| err(404, "not_found"))?;
        if !group.members.contains_key(&user) {
            return Err(err(403, "not_member"));
        }
        if after < group.dropped_through {
            return Err(err(410, "cursor_expired"));
        }
        Ok(group.log.iter().filter(|i| i.seq > after).take(limit as usize).cloned().collect())
    }

    async fn group_info(&self, group_id: &str) -> Result<Vec<u8>, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        Ok(Self::require_active(&mut state, group_id, &user)?.group_info.clone())
    }

    async fn welcomes(&self) -> Result<Vec<WelcomeItem>, ApiError> {
        let (_, device) = self.who()?;
        Ok(self.lock().welcomes.remove(&device).unwrap_or_default())
    }

    async fn put_state(&self, group_id: &str, kind: &str, req: PutState) -> Result<i64, ApiError> {
        let (user, _) = self.who()?;
        let mut state = self.lock();
        let group = Self::require_active(&mut state, group_id, &user)?;
        let slot = (kind.to_owned(), user.clone());
        let version = match group.state.get_mut(&slot) {
            Some(existing) if existing.epoch > req.epoch => existing.version,
            Some(existing) => {
                existing.epoch = req.epoch;
                existing.ciphertext = req.ciphertext;
                existing.version += 1;
                existing.version
            }
            None => {
                group.state.insert(
                    slot,
                    StateBlob {
                        kind: kind.to_owned(),
                        owner_id: user.clone(),
                        epoch: req.epoch,
                        ciphertext: req.ciphertext,
                        version: 1,
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

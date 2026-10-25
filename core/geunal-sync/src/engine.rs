//! The sync engine (rust-core §5). Writes queue in the outbox and go out in order per group; reads catch up from the
//! per-group cursor and apply the log in server order: MLS → entry decode → store → the apps redraw from the stored
//! log. The engine never decides what a group means; it only moves bytes and keeps the invariants protocol §6 needs.

use std::collections::BTreeSet;
use std::sync::Arc;

use chrono::{DateTime, Duration, Utc};
use geunal_domain::appointments::{GroupState, LogEntry};
use geunal_domain::availability::Availability;
use geunal_domain::entry::{self, Entry};
use geunal_mls::state::{open as open_blob, seal as seal_blob, state_aad};
use geunal_mls::{Identity, MlsClient, MlsGroup, Received};
use geunal_store::{AvailabilityRow, EntryRow, GroupKind, OutboxKind, Store};
use serde::{Deserialize, Serialize};

use crate::SyncError;
use crate::api::*;
use crate::realtime::Event;

/// What changed, so the app knows which screens to redraw.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Change {
    Calendar,
    Group { group_id: String },
    Chat { group_id: String },
}

/// Implemented by the app (through the FFI) to hear about changes.
pub trait Listener: Send + Sync {
    fn on_change(&self, change: Change);
}

/// The signed-in device, kept by the app in its SecretStore between runs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Session {
    pub user_id: String,
    pub device_id: String,
    pub access_token: String,
    pub refresh_token: String,
}

/// Who a message alerts (protocol §8).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Alert {
    All,
    None,
    Users(Vec<String>),
}

impl From<Alert> for AlertTarget {
    fn from(alert: Alert) -> Self {
        match alert {
            Alert::All => AlertTarget::Everyone(AllOrNone::All),
            Alert::None => AlertTarget::Everyone(AllOrNone::None),
            Alert::Users(users) => AlertTarget::Users(users),
        }
    }
}

/// What a commit should achieve. The commit itself is built when the row reaches the head of the outbox, in the epoch
/// of that moment, so an `epoch_conflict` only means "build it again" (protocol §2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CommitIntent {
    Add { user_ids: Vec<String> },
    Remove { user_ids: Vec<String> },
    SelfUpdate,
}

/// An outbox payload. Application entries stay plaintext here so a `stale_epoch` refusal can re-encrypt them.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
enum Outgoing {
    App { entry: Vec<u8>, alert: AlertTarget, cat: Option<String> },
    Commit(CommitIntent),
    State { kind: String, plaintext: Vec<u8> },
}

impl Outgoing {
    fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        ciborium::into_writer(self, &mut out).expect("in-memory write");
        out
    }

    fn decode(bytes: &[u8]) -> Result<Self, SyncError> {
        ciborium::from_reader(bytes).map_err(|e| SyncError::Malformed(format!("outbox payload: {e}")))
    }
}

/// Days the availability window covers by default (kickoff §5.3).
pub const DEFAULT_WINDOW_DAYS: u16 = 56;
/// State keys older than this are dropped (protocol §5).
const STATE_KEY_DAYS: i64 = 30;
/// How many times one outbox row is retried within a single flush before the flush moves on.
const FLUSH_RETRIES: usize = 3;
/// A push or gateway event this far ahead of the cursor is fetched from the API instead of waited for.
const GAP_FETCH_LIMIT: i64 = 20;

pub struct Engine<T: Transport> {
    store: Store,
    mls: MlsClient,
    api: T,
    me: Identity,
    listener: Arc<dyn Listener>,
    clock: Box<dyn Fn() -> DateTime<Utc> + Send + Sync>,
}

/// Registers a new device as a guest (M0 auth stub): nonce → Ed25519 proof → session. Done before an engine exists,
/// because the MLS client needs the identity the server hands back.
pub async fn sign_in_guest<T: Transport>(
    api: &T,
    display_name: &str,
    platform: &str,
    signing_secret: &[u8],
) -> Result<Session, SyncError> {
    let nonce = api.nonce().await?;
    let signature = MlsClient::sign_with(signing_secret, nonce.as_bytes())?;
    let info = api
        .sign_in_guest(GuestSignIn {
            display_name: display_name.to_owned(),
            platform: platform.to_owned(),
            signature_key: MlsClient::public_key_of(signing_secret)?,
            nonce,
            signature,
        })
        .await?;
    Ok(Session {
        user_id: info.user_id,
        device_id: info.device_id,
        access_token: info.access_token,
        refresh_token: info.refresh_token,
    })
}

impl<T: Transport> Engine<T> {
    pub fn new(store: Store, mls: MlsClient, api: T, listener: Arc<dyn Listener>) -> Self {
        let me = mls.identity().clone();
        Self { store, mls, api, me, listener, clock: Box::new(Utc::now) }
    }

    /// Replaces the clock the engine stamps outbox rows and state keys with; tests pin it.
    pub fn with_clock(mut self, clock: impl Fn() -> DateTime<Utc> + Send + Sync + 'static) -> Self {
        self.clock = Box::new(clock);
        self
    }

    pub fn store(&self) -> &Store {
        &self.store
    }

    pub fn api(&self) -> &T {
        &self.api
    }

    pub fn me(&self) -> &Identity {
        &self.me
    }

    fn now(&self) -> DateTime<Utc> {
        (self.clock)()
    }

    fn now_text(&self) -> String {
        self.now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
    }

    fn notify(&self, change: Change) {
        self.listener.on_change(change);
    }

    // ----- account -----

    /// Uploads `count` single-use key packages so other members can add this device.
    pub async fn publish_key_packages(&self, count: usize) -> Result<(), SyncError> {
        let packages = (0..count).map(|_| self.mls.key_package()).collect::<Result<Vec<_>, _>>()?;
        Ok(self.api.upload_key_packages(packages).await?)
    }

    // ----- groups -----

    /// Creates a group: the device picks the UUID, creates the MLS group under its 16 bytes and registers both with
    /// the first GroupInfo (protocol §2).
    pub async fn create_group(&mut self, name: Option<&str>) -> Result<String, SyncError> {
        let id = uuid::Uuid::new_v4();
        let (mut group, info) = self.mls.create_group(id.as_bytes())?;
        let group_id = id.to_string();
        self.api.create_group(&group_id, info).await?;
        self.store.upsert_group(&group_id, GroupKind::Circle, name)?;
        self.remember_state_key(&group_id, &group)?;
        group.save()?;
        self.notify(Change::Group { group_id: group_id.clone() });
        Ok(group_id)
    }

    pub async fn create_invite(&self, group_id: &str) -> Result<String, SyncError> {
        Ok(self.api.create_invite(group_id).await?)
    }

    /// Asks to join; the server lists this user as pending until an active member's device adds it and the Welcome
    /// arrives through `sync`.
    pub async fn join_invite(&self, code: &str) -> Result<String, SyncError> {
        Ok(self.api.join_invite(code).await?)
    }

    /// Removes a member (leave or kick) and, as the device that saw the change first, queues the Remove commit.
    pub async fn remove_member(&mut self, group_id: &str, user_id: &str) -> Result<(), SyncError> {
        self.api.remove_member(group_id, user_id).await?;
        self.reconcile_members(group_id).await?;
        self.flush_group(group_id).await
    }

    /// Queues an application entry; `send` also flushes it.
    pub fn enqueue_entry(
        &self,
        group_id: &str,
        entry: &Entry,
        alert: Alert,
        cat: Option<&str>,
    ) -> Result<i64, SyncError> {
        let payload = Outgoing::App { entry: entry::encode(entry), alert: alert.into(), cat: cat.map(str::to_owned) };
        Ok(self.store.enqueue(group_id, OutboxKind::App, &payload.encode(), &self.now_text())?)
    }

    pub async fn send(
        &mut self,
        group_id: &str,
        entry: &Entry,
        alert: Alert,
        cat: Option<&str>,
    ) -> Result<(), SyncError> {
        self.enqueue_entry(group_id, entry, alert, cat)?;
        self.flush_group(group_id).await
    }

    /// Queues this member's availability blob for the group (sealed at send time with the epoch's key).
    pub fn set_availability(&self, group_id: &str, availability: &Availability) -> Result<(), SyncError> {
        let payload = Outgoing::State { kind: "availability".into(), plaintext: availability.encode() };
        self.store.enqueue(group_id, OutboxKind::State, &payload.encode(), &self.now_text())?;
        Ok(())
    }

    /// The group's decoded log in order. Entries this build cannot decode are left out here and shown by kind.
    pub fn log(&self, group_id: &str) -> Result<Vec<LogEntry>, SyncError> {
        let rows = self.store.entries(group_id)?;
        Ok(rows
            .iter()
            .filter_map(|row| {
                let entry = entry::decode(&row.body).ok()?;
                let received_at = DateTime::parse_from_rfc3339(&row.received_at).ok()?.to_utc();
                Some(LogEntry { seq: row.seq, author_id: row.author_id.clone(), received_at, entry })
            })
            .collect())
    }

    /// The group's appointments as of the latest server time seen in its log (kickoff §3 principle 3).
    pub fn group_state(&self, group_id: &str) -> Result<GroupState, SyncError> {
        let log = self.log(group_id)?;
        let now = log.iter().map(|e| e.received_at).max().unwrap_or_else(|| self.now());
        Ok(GroupState::derive(&log, now))
    }

    /// Members' latest availability as the group calendar sees it: whoever has no readable blob is absent.
    pub fn group_availability(&self, group_id: &str) -> Result<Vec<AvailabilityRow>, SyncError> {
        Ok(self.store.availability(group_id)?)
    }

    pub fn roster(&self, group_id: &str) -> Result<Vec<Identity>, SyncError> {
        let group = self.load_group(group_id)?.ok_or_else(|| SyncError::NotAMember(group_id.to_owned()))?;
        Ok(group.roster()?.into_iter().map(|m| m.identity).collect())
    }

    pub fn epoch(&self, group_id: &str) -> Result<Option<u64>, SyncError> {
        Ok(self.load_group(group_id)?.map(|g| g.epoch()))
    }

    // ----- sync -----

    /// Catches every group up: new Welcomes, the log from each cursor, state blobs, membership changes, then the
    /// outbox.
    pub async fn sync(&mut self) -> Result<(), SyncError> {
        self.pull_welcomes().await?;
        for summary in self.api.my_groups().await? {
            let kind = if summary.kind == "dm" { GroupKind::Dm } else { GroupKind::Circle };
            if self.store.group(&summary.group_id)?.is_some() {
                self.store.upsert_group(&summary.group_id, kind, None)?;
            }
        }
        let groups: Vec<String> = self.store.groups()?.into_iter().map(|g| g.id).collect();
        for group_id in &groups {
            self.catch_up(group_id).await?;
            if self.load_group(group_id)?.is_some() {
                self.reconcile_members(group_id).await?;
                self.pull_state(group_id).await?;
            }
        }
        self.flush_outbox().await
    }

    /// Reacts to a gateway or push event (protocol §7): fetch what it names, or process the item it carries.
    pub async fn handle_event(&mut self, event: Event) -> Result<(), SyncError> {
        match event {
            Event::Entry { g, s, c: Some(ciphertext), kind } if s == self.store.cursor(&g)? + 1 => {
                let item = LogItem {
                    seq: s,
                    epoch: 0,
                    kind,
                    author_id: String::new(),
                    ciphertext,
                    received_at: self.now_text(),
                };
                self.apply_item(&g, &item).await?;
                self.flush_group(&g).await
            }
            Event::Entry { g, s, .. } => {
                if s - self.store.cursor(&g)? > GAP_FETCH_LIMIT {
                    // Far behind: a full catch-up, the same path the app takes when it comes to the foreground.
                    self.catch_up(&g).await?;
                } else {
                    self.catch_up(&g).await?;
                }
                self.flush_group(&g).await
            }
            Event::State { g, .. } => self.pull_state(&g).await,
            Event::Membership { g } => {
                self.reconcile_members(&g).await?;
                self.flush_group(&g).await
            }
            Event::Welcome {} => {
                self.pull_welcomes().await?;
                self.flush_outbox().await
            }
            Event::Revoke {} | Event::Joined | Event::Closed(_) => Ok(()),
        }
    }

    fn load_group(&self, group_id: &str) -> Result<Option<MlsGroup>, SyncError> {
        Ok(self.mls.load_group(mls_group_id(group_id)?.as_slice())?)
    }

    async fn pull_welcomes(&mut self) -> Result<(), SyncError> {
        for welcome in self.api.welcomes().await? {
            let mut group = match self.mls.join_welcome(&welcome.welcome) {
                Ok(group) => group,
                // A Welcome for a suite or group we cannot use is dropped; the server deletes it once fetched.
                Err(_) => continue,
            };
            if group.group_id() != mls_group_id(&welcome.group_id)?.as_slice() {
                continue;
            }
            // Local history before a removal stays; the cursor restarts at the Add commit (protocol §6).
            self.store.upsert_group(&welcome.group_id, GroupKind::Circle, None)?;
            self.store.set_cursor(&welcome.group_id, welcome.seq)?;
            self.remember_state_key(&welcome.group_id, &group)?;
            group.save()?;
            self.pull_state(&welcome.group_id).await?;
            self.notify(Change::Group { group_id: welcome.group_id.clone() });
        }
        Ok(())
    }

    /// Fetches and applies the log after the cursor, page by page. 410 means the mailbox has moved past the cursor:
    /// drop the MLS state and come back by external commit (protocol §6).
    pub async fn catch_up(&mut self, group_id: &str) -> Result<(), SyncError> {
        let mut rejoined = false;
        loop {
            let cursor = self.store.cursor(group_id)?;
            let items = match self.api.log(group_id, cursor, LOG_PAGE).await {
                Ok(items) => items,
                // Once: a second 410 right after rejoining is a server fault, not something to loop on.
                Err(e) if e.is(410, "cursor_expired") && !rejoined => {
                    rejoined = true;
                    self.rejoin(group_id).await?;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            let count = items.len();
            for item in &items {
                self.apply_item(group_id, item).await?;
            }
            if count < LOG_PAGE as usize {
                return Ok(());
            }
        }
    }

    /// One log item in order: MLS first, then the entry, then the store (protocol §6). The cursor moves past an item
    /// this device cannot read (its own echo, a message from before it joined) so one bad item never blocks a group.
    async fn apply_item(&mut self, group_id: &str, item: &LogItem) -> Result<(), SyncError> {
        let cursor = self.store.cursor(group_id)?;
        if item.seq <= cursor {
            return Ok(());
        }
        let Some(mut group) = self.load_group(group_id)? else {
            // Removed from the group: nothing here is readable until a Welcome restarts the cursor.
            return Ok(self.store.set_cursor(group_id, item.seq)?);
        };
        match item.kind.as_str() {
            "commit" => {
                // The echo of a commit this device already applied: its epoch is behind ours.
                if item.epoch < group.epoch() && item.epoch != 0 || self.is_own_echo(&group, item) {
                    self.store.set_cursor(group_id, item.seq)?;
                    return Ok(());
                }
                match group.process(&item.ciphertext) {
                    Ok(Received::Commit { epoch, removed_me: true }) => {
                        let _ = epoch;
                        self.mls.forget_group(group.group_id())?;
                        self.store.set_cursor(group_id, item.seq)?;
                        self.notify(Change::Group { group_id: group_id.to_owned() });
                        return Ok(());
                    }
                    Ok(Received::Commit { .. }) => {
                        self.store.set_cursor(group_id, item.seq)?;
                        self.after_epoch_change(group_id, &mut group).await?;
                    }
                    Ok(_) | Err(_) => {
                        self.store.set_cursor(group_id, item.seq)?;
                    }
                }
            }
            _ => {
                if self.store.has_entry(group_id, item.seq)? {
                    // Our own message, stored when the server accepted it.
                    self.store.set_cursor(group_id, item.seq)?;
                    return Ok(());
                }
                if let Ok(Received::Application { sender, data }) = group.process(&item.ciphertext) {
                    self.store_entry(group_id, item.seq, &sender.user_id, &data, &item.received_at)?;
                }
                self.store.set_cursor(group_id, item.seq)?;
            }
        }
        Ok(())
    }

    /// A commit echo is ours when the pending epoch moved past the item's epoch; a gateway item without an epoch
    /// (0) is checked by trying to process it instead.
    fn is_own_echo(&self, group: &MlsGroup, item: &LogItem) -> bool {
        item.epoch != 0 && item.epoch + 1 == group.epoch() && item.author_id == self.me.user_id
    }

    fn store_entry(
        &self,
        group_id: &str,
        seq: i64,
        author_id: &str,
        body: &[u8],
        received_at: &str,
    ) -> Result<(), SyncError> {
        let kind = entry::peek_kind(body).unwrap_or_else(|| "unknown".to_owned());
        let row = EntryRow {
            group_id: group_id.to_owned(),
            seq,
            author_id: author_id.to_owned(),
            kind,
            body: body.to_vec(),
            received_at: received_at.to_owned(),
        };
        if !self.store.insert_entry(&row)? {
            return Ok(());
        }
        match entry::decode(body) {
            Ok(Entry::Text { body: text, .. }) => {
                self.store.index_text(group_id, seq, &text)?;
                self.notify(Change::Chat { group_id: group_id.to_owned() });
            }
            Ok(Entry::Media { .. } | Entry::MediaRemove { .. }) => {
                self.notify(Change::Chat { group_id: group_id.to_owned() });
            }
            _ => {
                self.notify(Change::Group { group_id: group_id.to_owned() });
                self.notify(Change::Calendar);
            }
        }
        Ok(())
    }

    fn remember_state_key(&self, group_id: &str, group: &MlsGroup) -> Result<(), SyncError> {
        let key = group.state_key()?;
        self.store.put_state_key(group_id, group.epoch() as i64, &key, &self.now_text())?;
        let cutoff = (self.now() - Duration::days(STATE_KEY_DAYS)).to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
        self.store.prune_state_keys(group_id, &cutoff)?;
        Ok(())
    }

    /// After a new epoch (rust-core §5): keep its state key, re-seal what this member owns, drop old keys.
    async fn after_epoch_change(&mut self, group_id: &str, group: &mut MlsGroup) -> Result<(), SyncError> {
        self.remember_state_key(group_id, group)?;
        let mine = self.store.availability(group_id)?.into_iter().find(|a| a.user_id == self.me.user_id);
        if let Some(row) = mine {
            let availability = Availability { window_start: row.window_start, days: row.days as u16, bits: row.bits };
            self.put_state_now(group_id, group, "availability", &availability.encode()).await?;
        }
        self.notify(Change::Group { group_id: group_id.to_owned() });
        Ok(())
    }

    /// Seals and uploads one state blob in the group's current epoch.
    async fn put_state_now(
        &self,
        group_id: &str,
        group: &MlsGroup,
        kind: &str,
        plaintext: &[u8],
    ) -> Result<(), SyncError> {
        let key = group.state_key()?;
        let aad = state_aad(group_id, group.epoch(), kind, &self.me.user_id);
        let ciphertext = seal_blob(&key, &aad, plaintext);
        let req = PutState { epoch: group.epoch(), ciphertext, base_version: None };
        self.api.put_state(group_id, kind, req).await?;
        if kind == "availability"
            && let Some(availability) = Availability::decode(plaintext)
        {
            self.store.put_availability(&AvailabilityRow {
                group_id: group_id.to_owned(),
                user_id: self.me.user_id.clone(),
                window_start: availability.window_start,
                days: i64::from(availability.days),
                bits: availability.bits,
                epoch: group.epoch() as i64,
            })?;
        }
        Ok(())
    }

    /// Reads every state blob of the group. A blob sealed in an epoch whose key this device lacks, or that fails to
    /// open, leaves its owner absent from the group calendar (protocol §5).
    pub async fn pull_state(&mut self, group_id: &str) -> Result<(), SyncError> {
        let blobs = self.api.state(group_id).await?;
        let mut bundle: Option<Vec<u8>> = None;
        let mut seen_availability = BTreeSet::new();
        for blob in &blobs {
            let key = self.store.state_key(group_id, blob.epoch as i64)?;
            let aad = state_aad(group_id, blob.epoch, &blob.kind, &blob.owner_id);
            let plaintext = key.and_then(|k| open_blob(&k, &aad, &blob.ciphertext));
            match (blob.kind.as_str(), plaintext) {
                ("availability", Some(plain)) => {
                    seen_availability.insert(blob.owner_id.clone());
                    if let Some(a) = Availability::decode(&plain) {
                        self.store.put_availability(&AvailabilityRow {
                            group_id: group_id.to_owned(),
                            user_id: blob.owner_id.clone(),
                            window_start: a.window_start,
                            days: i64::from(a.days),
                            bits: a.bits,
                            epoch: blob.epoch as i64,
                        })?;
                    } else {
                        self.store.remove_availability(group_id, &blob.owner_id)?;
                    }
                }
                ("availability", None) => self.store.remove_availability(group_id, &blob.owner_id)?,
                ("newcomer_bundle", Some(plain)) => bundle = Some(plain),
                _ => {}
            }
        }
        for row in self.store.availability(group_id)? {
            if !seen_availability.contains(&row.user_id) && row.user_id != self.me.user_id {
                self.store.remove_availability(group_id, &row.user_id)?;
            }
        }
        if let Some(bundle) = bundle {
            self.absorb_bundle(group_id, &bundle)?;
        }
        self.notify(Change::Group { group_id: group_id.to_owned() });
        Ok(())
    }

    /// Entries of live appointments from before this device joined, taken on trust from the member who added it
    /// (protocol §5). They take negative seqs in bundle order; ones already in the log are skipped.
    fn absorb_bundle(&self, group_id: &str, bundle: &[u8]) -> Result<(), SyncError> {
        let items: Vec<BundleItem> =
            ciborium::from_reader(bundle).map_err(|e| SyncError::Malformed(format!("newcomer bundle: {e}")))?;
        let existing = self.store.entries(group_id)?;
        let fresh: Vec<&BundleItem> = items
            .iter()
            .filter(|i| {
                !existing
                    .iter()
                    .any(|e| e.author_id == i.author_id && e.received_at == i.received_at && e.body == i.entry)
            })
            .collect();
        // Bundle order must survive `ORDER BY seq`, so the first fresh item gets the lowest number.
        let first = self.store.lowest_entry_seq(group_id)? - fresh.len() as i64;
        for (seq, item) in (first..).zip(fresh) {
            self.store_entry(group_id, seq, &item.author_id, &item.entry, &item.received_at)?;
        }
        Ok(())
    }

    /// Seals the entries of appointments whose day has not passed for a member this device just added (protocol §5).
    async fn publish_newcomer_bundle(&self, group_id: &str, group: &MlsGroup) -> Result<(), SyncError> {
        let state = self.group_state(group_id)?;
        let live: BTreeSet<&str> = state.appointments.keys().map(String::as_str).collect();
        let items: Vec<BundleItem> = self
            .store
            .entries(group_id)?
            .into_iter()
            .filter(|row| {
                entry::decode(&row.body)
                    .ok()
                    .and_then(|e| appointment_of(&e).map(|a| live.contains(a)))
                    .unwrap_or(false)
            })
            .map(|row| BundleItem { author_id: row.author_id, received_at: row.received_at, entry: row.body })
            .collect();
        let mut plaintext = Vec::new();
        ciborium::into_writer(&items, &mut plaintext).expect("in-memory write");
        self.put_state_now(group_id, group, "newcomer_bundle", &plaintext).await
    }

    /// The server's member list against the MLS roster: pending members get an Add, removed ones a Remove. Any active
    /// device may do this; the server's epoch preemption settles races (protocol §2).
    pub async fn reconcile_members(&mut self, group_id: &str) -> Result<(), SyncError> {
        let Some(group) = self.load_group(group_id)? else { return Ok(()) };
        let members = self.api.members(group_id).await?;
        let roster: BTreeSet<String> = group.roster()?.into_iter().map(|m| m.identity.user_id).collect();
        let pending: Vec<String> = members
            .iter()
            .filter(|m| m.status == "pending" && !roster.contains(&m.user_id))
            .map(|m| m.user_id.clone())
            .collect();
        let active: BTreeSet<&str> =
            members.iter().filter(|m| m.status == "active").map(|m| m.user_id.as_str()).collect();
        let gone: Vec<String> = roster.iter().filter(|u| !active.contains(u.as_str())).cloned().collect();
        let queued: Vec<CommitIntent> = self
            .store
            .outbox(group_id)?
            .iter()
            .filter(|row| row.kind == OutboxKind::Commit)
            .filter_map(|row| match Outgoing::decode(&row.payload) {
                Ok(Outgoing::Commit(intent)) => Some(intent),
                _ => None,
            })
            .collect();
        if !pending.is_empty() {
            let intent = CommitIntent::Add { user_ids: pending };
            if !queued.contains(&intent) {
                self.store.enqueue(
                    group_id,
                    OutboxKind::Commit,
                    &Outgoing::Commit(intent).encode(),
                    &self.now_text(),
                )?;
            }
        }
        if !gone.is_empty() {
            let intent = CommitIntent::Remove { user_ids: gone };
            if !queued.contains(&intent) {
                self.store.enqueue(
                    group_id,
                    OutboxKind::Commit,
                    &Outgoing::Commit(intent).encode(),
                    &self.now_text(),
                )?;
            }
        }
        Ok(())
    }

    /// Comes back after `cursor_expired` with an external commit, removing this device's old leaf. Local plaintext
    /// history stays (protocol §6).
    async fn rejoin(&mut self, group_id: &str) -> Result<(), SyncError> {
        let previous = self.load_group(group_id)?.map(|g| g.my_index());
        for _ in 0..FLUSH_RETRIES {
            let info = self.api.group_info(group_id).await?;
            let (mut group, commit) = self.mls.external_join(&info, previous)?;
            let req = CommitRequest {
                epoch: group.epoch() - 1,
                commit,
                group_info: group.group_info()?,
                welcome: None,
                added_user_ids: Vec::new(),
                external: true,
            };
            match self.api.post_commit(group_id, req).await {
                Ok(accepted) => {
                    group.save()?;
                    self.store.upsert_group(group_id, GroupKind::Circle, None)?;
                    self.store.set_cursor(group_id, accepted.seq)?;
                    self.after_epoch_change(group_id, &mut group).await?;
                    return Ok(());
                }
                Err(e) if e.is(409, "epoch_conflict") => continue,
                Err(e) => return Err(e.into()),
            }
        }
        Err(SyncError::Api(ApiError::Api { status: 409, code: "epoch_conflict".into(), message: "rejoin".into() }))
    }

    // ----- outbox -----

    pub async fn flush_outbox(&mut self) -> Result<(), SyncError> {
        for group_id in self.store.outbox_groups()? {
            self.flush_group(&group_id).await?;
        }
        Ok(())
    }

    /// Sends a group's waiting writes in order. A row that keeps failing stays at the head with its attempt count;
    /// the caller's backoff decides when to try again.
    pub async fn flush_group(&mut self, group_id: &str) -> Result<(), SyncError> {
        while let Some(row) = self.store.outbox_head(group_id)? {
            let mut done = false;
            for _ in 0..FLUSH_RETRIES {
                match self.send_row(group_id, &row.payload).await? {
                    SendOutcome::Sent | SendOutcome::Dropped => {
                        self.store.dequeue(row.id)?;
                        done = true;
                        break;
                    }
                    SendOutcome::Retry => continue,
                    SendOutcome::Later => {
                        self.store.bump_attempts(row.id)?;
                        return Ok(());
                    }
                }
            }
            if !done {
                self.store.bump_attempts(row.id)?;
                return Ok(());
            }
        }
        Ok(())
    }

    async fn send_row(&mut self, group_id: &str, payload: &[u8]) -> Result<SendOutcome, SyncError> {
        let Some(mut group) = self.load_group(group_id)? else {
            // No longer a member: nothing queued for this group can be sent.
            return Ok(SendOutcome::Dropped);
        };
        match Outgoing::decode(payload)? {
            Outgoing::App { entry: bytes, alert, cat } => {
                let ciphertext = group.encrypt(&bytes)?;
                let req = MessageRequest { epoch: group.epoch(), ciphertext, alert, cat };
                match self.api.post_message(group_id, req).await {
                    Ok(accepted) => {
                        self.store_entry(group_id, accepted.seq, &self.me.user_id, &bytes, &accepted.received_at)?;
                        if self.store.cursor(group_id)? + 1 == accepted.seq {
                            self.store.set_cursor(group_id, accepted.seq)?;
                        }
                        Ok(SendOutcome::Sent)
                    }
                    Err(e) if e.is(409, "stale_epoch") => {
                        // Process the commit that moved the epoch, then encrypt again (protocol §11).
                        self.catch_up(group_id).await?;
                        Ok(SendOutcome::Retry)
                    }
                    Err(e) if e.is(403, "not_member") => Ok(SendOutcome::Dropped),
                    Err(e) if is_transient(&e) => Ok(SendOutcome::Later),
                    Err(e) => Err(e.into()),
                }
            }
            Outgoing::State { kind, plaintext } => {
                match self.put_state_now(group_id, &group, &kind, &plaintext).await {
                    Ok(()) => Ok(SendOutcome::Sent),
                    Err(SyncError::Api(e)) if e.is(409, "state_conflict") => Ok(SendOutcome::Retry),
                    Err(SyncError::Api(e)) if is_transient(&e) => Ok(SendOutcome::Later),
                    Err(e) => Err(e),
                }
            }
            Outgoing::Commit(intent) => self.send_commit(group_id, &mut group, intent).await,
        }
    }

    async fn send_commit(
        &mut self,
        group_id: &str,
        group: &mut MlsGroup,
        intent: CommitIntent,
    ) -> Result<SendOutcome, SyncError> {
        let roster = group.roster()?;
        let (bundle, added) = match &intent {
            CommitIntent::Add { user_ids } => {
                let mut packages = Vec::new();
                let mut added = Vec::new();
                for user_id in user_ids {
                    if roster.iter().any(|m| m.identity.user_id == *user_id) {
                        continue;
                    }
                    // Protocol §2: the key package must belong to a device the server lists for this user.
                    let devices = self.api.devices(user_id).await?;
                    for device in devices {
                        let package = self.api.claim_key_package(&device.device_id).await?;
                        let info = MlsClient::inspect_key_package(&package)?;
                        if info.identity != Identity::new(user_id.clone(), device.device_id.clone())
                            || info.signature_key != device.signature_key
                        {
                            return Err(SyncError::Malformed(format!(
                                "key package of {user_id} does not match its device"
                            )));
                        }
                        packages.push(package);
                    }
                    added.push(user_id.clone());
                }
                if packages.is_empty() {
                    return Ok(SendOutcome::Dropped);
                }
                (group.add_members(&packages)?, added)
            }
            CommitIntent::Remove { user_ids } => {
                let indexes: Vec<u32> =
                    roster.iter().filter(|m| user_ids.contains(&m.identity.user_id)).map(|m| m.index).collect();
                if indexes.is_empty() {
                    return Ok(SendOutcome::Dropped);
                }
                (group.remove_members(&indexes)?, Vec::new())
            }
            CommitIntent::SelfUpdate => (group.self_update()?, Vec::new()),
        };
        let req = CommitRequest {
            epoch: group.epoch(),
            commit: bundle.commit,
            group_info: bundle.group_info,
            welcome: bundle.welcome,
            added_user_ids: added.clone(),
            external: false,
        };
        match self.api.post_commit(group_id, req).await {
            Ok(accepted) => {
                group.apply_pending_commit()?;
                if self.store.cursor(group_id)? + 1 == accepted.seq {
                    self.store.set_cursor(group_id, accepted.seq)?;
                }
                self.after_epoch_change(group_id, group).await?;
                if !added.is_empty() {
                    self.publish_newcomer_bundle(group_id, group).await?;
                }
                Ok(SendOutcome::Sent)
            }
            Err(e) if e.is(409, "epoch_conflict") => {
                // Someone else's commit won this epoch: forget ours, take theirs, rebuild on the next round.
                group.clear_pending_commit();
                self.catch_up(group_id).await?;
                Ok(SendOutcome::Retry)
            }
            Err(e) if e.is(403, "not_member") => {
                group.clear_pending_commit();
                Ok(SendOutcome::Dropped)
            }
            Err(e) if is_transient(&e) => {
                group.clear_pending_commit();
                Ok(SendOutcome::Later)
            }
            Err(e) => {
                group.clear_pending_commit();
                Err(e.into())
            }
        }
    }
}

enum SendOutcome {
    Sent,
    Dropped,
    Retry,
    Later,
}

/// Rate limits and network failures: keep the row and try later with backoff (protocol §11).
fn is_transient(e: &ApiError) -> bool {
    matches!(e, ApiError::Network(_))
        || e.is(429, "rate_limited")
        || matches!(e, ApiError::Api { status, .. } if *status >= 500)
}

/// One entry in a newcomer bundle: `(author_id, received_at, Entry)` (protocol §5), with the entry as its envelope.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct BundleItem {
    author_id: String,
    received_at: String,
    #[serde(with = "geunal_domain::entry::cbor_bytes")]
    entry: Vec<u8>,
}

fn appointment_of(entry: &Entry) -> Option<&str> {
    match entry {
        Entry::Request { appt, .. }
        | Entry::RequestUpdate { appt, .. }
        | Entry::LeadTransfer { appt, .. }
        | Entry::Response { appt, .. }
        | Entry::Poll { appt, .. }
        | Entry::PollOption { appt, .. }
        | Entry::PollVote { appt, .. }
        | Entry::PollClose { appt, .. }
        | Entry::Cancel { appt, .. } => Some(appt),
        Entry::Media { appt, .. } => appt.as_deref(),
        Entry::Text { .. } | Entry::MediaRemove { .. } => None,
    }
}

/// The MLS group id is the group UUID's 16 bytes (protocol §1).
pub fn mls_group_id(group_id: &str) -> Result<[u8; 16], SyncError> {
    uuid::Uuid::parse_str(group_id)
        .map(|u| *u.as_bytes())
        .map_err(|_| SyncError::Malformed(format!("group id {group_id:?} is not a UUID")))
}

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
use geunal_store::{AvailabilityRow, EntryRow, GroupKind, OutboxKind, OutboxRow, Store};
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
    App {
        entry: Vec<u8>,
        alert: AlertTarget,
        cat: Option<String>,
    },
    Commit(CommitIntent),
    State {
        kind: String,
        plaintext: Vec<u8>,
    },
    /// A commit row once its commit is built and saved as the group's pending commit: the request as it goes to the
    /// server. Until the server's answer is known the same commit goes out again, never a second one, because the
    /// server may have taken the first while its answer was lost; only this device's pending commit opens the copy
    /// the log then brings back.
    Posted {
        intent: CommitIntent,
        request: CommitRequest,
    },
    /// What is left of an Add row once its commit is in: the newcomer bundle (protocol §5). It stays queued until
    /// the upload is through, so a failed upload or a stopped process does not leave the newcomer without the
    /// entries from before it joined.
    Bundle,
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

    /// What a commit row is for, whether or not its commit was built yet.
    fn intent(self) -> Option<CommitIntent> {
        match self {
            Outgoing::Commit(intent) | Outgoing::Posted { intent, .. } => Some(intent),
            _ => None,
        }
    }
}

/// How one log item went (`Engine::apply_item`). Only `Done` moved the cursor.
enum Applied {
    Done,
    /// An application message MLS would not open and nothing stored stands in for.
    Unreadable,
    /// The group moved to an epoch this device cannot follow: a commit it cannot process, or an item from an epoch
    /// ahead of its own. Nothing later is readable until it rejoins.
    Forked,
}

/// Days the availability window covers by default (kickoff §5.3).
pub const DEFAULT_WINDOW_DAYS: u16 = 56;
/// State keys older than this are dropped (protocol §5).
const STATE_KEY_DAYS: i64 = 30;
/// How many times one outbox row is retried within a single flush before the flush moves on.
const FLUSH_RETRIES: usize = 3;

pub struct Engine<T: Transport> {
    store: Store,
    mls: MlsClient,
    api: T,
    me: Identity,
    listener: Arc<dyn Listener>,
    clock: Box<dyn Fn() -> DateTime<Utc> + Send + Sync>,
    /// Whether `sync` fetches Welcomes and joins the groups they name. Off, the Welcomes stay on the server untouched.
    join_welcomes: bool,
}

/// The proof every sign-in carries: a fresh nonce from the server, signed with this device's key.
pub async fn device_proof<T: Transport>(
    api: &T,
    platform: &str,
    signing_secret: &[u8],
) -> Result<DeviceProof, SyncError> {
    let nonce = api.nonce().await?;
    Ok(DeviceProof {
        platform: platform.to_owned(),
        signature_key: MlsClient::public_key_of(signing_secret)?,
        signature: MlsClient::sign_with(signing_secret, nonce.as_bytes())?,
        nonce,
    })
}

/// The session a registered sign-in hands back.
pub fn session_of(tokens: &SessionInfo) -> Session {
    Session {
        user_id: tokens.user_id.clone(),
        device_id: tokens.device_id.clone(),
        access_token: tokens.access_token.clone(),
        refresh_token: tokens.refresh_token.clone(),
    }
}

/// Signs in through the auth stub (`POST /auth/stub`), which stands for a Kakao or Apple sign-in on local and CI
/// servers: `subject` is the identity a provider would assert. Done before an engine exists, because the MLS client
/// needs the identity the server hands back.
pub async fn sign_in_stub<T: Transport>(
    api: &T,
    subject: &str,
    display_name: &str,
    platform: &str,
    signing_secret: &[u8],
) -> Result<AuthOutcome, SyncError> {
    let proof = device_proof(api, platform, signing_secret).await?;
    Ok(api
        .sign_in(StubSignIn {
            subject: subject.to_owned(),
            display_name: display_name.to_owned(),
            platform: proof.platform,
            signature_key: proof.signature_key,
            nonce: proof.nonce,
            signature: proof.signature,
        })
        .await?)
}

/// [`sign_in_stub`] for a device that must end up registered, as every test device and the M0 gate does. Another
/// device already active on the account is an error here.
pub async fn sign_in<T: Transport>(
    api: &T,
    subject: &str,
    display_name: &str,
    platform: &str,
    signing_secret: &[u8],
) -> Result<Session, SyncError> {
    match sign_in_stub(api, subject, display_name, platform, signing_secret).await? {
        AuthOutcome::Registered { tokens, .. } => Ok(session_of(&tokens)),
        AuthOutcome::ReplaceRequired { .. } => {
            Err(SyncError::Malformed("another device is active on this account".into()))
        }
    }
}

impl<T: Transport> Engine<T> {
    pub fn new(store: Store, mls: MlsClient, api: T, listener: Arc<dyn Listener>) -> Self {
        let me = mls.identity().clone();
        Self { store, mls, api, me, listener, clock: Box::new(Utc::now), join_welcomes: true }
    }

    /// Whether Welcomes are fetched and their groups joined (on by default). The apps turn it off until a member
    /// accepts an invitation, so that nobody is put into a group they did not agree to join.
    pub fn with_welcomes(mut self, join: bool) -> Self {
        self.join_welcomes = join;
        self
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

    /// Registers this device's push token with the server, or clears it with `None`.
    pub async fn set_push_token(&self, token: Option<String>) -> Result<(), SyncError> {
        Ok(self.api.set_push_token(token).await?)
    }

    // ----- groups -----

    /// Creates a group: the API mints the UUID, the device creates the MLS group under its 16 bytes and publishes the
    /// epoch-0 GroupInfo (protocol §2).
    pub async fn create_group(&mut self, name: Option<&str>) -> Result<String, SyncError> {
        let summary = self.api.create_group().await?;
        let group_id = summary.id;
        let (mut group, info) = self.mls.create_group(&mls_group_id(&group_id)?)?;
        self.api.put_group_info(&group_id, GroupInfo { epoch: 0, group_info: info }).await?;
        self.store.upsert_group(&group_id, GroupKind::Circle, name)?;
        self.remember_state_key(&group_id, &group)?;
        group.save()?;
        self.notify(Change::Group { group_id: group_id.clone() });
        Ok(group_id)
    }

    /// Adds members by user id: their devices' key packages are claimed and checked against the server's device list
    /// (protocol §2), and the Add commit goes out with the Welcome. At M0 members are added by id; invites come at M2.
    pub async fn add_members(&mut self, group_id: &str, user_ids: &[String]) -> Result<(), SyncError> {
        let intent = CommitIntent::Add { user_ids: user_ids.to_vec() };
        self.store.enqueue(group_id, OutboxKind::Commit, &Outgoing::Commit(intent).encode(), &self.now_text())?;
        self.flush_group(group_id).await
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
        let bytes = entry::encode(entry);
        // The server refuses a bigger entry for good (protocol §4), so it never enters the queue.
        if bytes.len() > entry::MAX_ENTRY_BYTES {
            return Err(SyncError::EntryTooLarge(bytes.len()));
        }
        let payload = Outgoing::App { entry: bytes, alert: alert.into(), cat: cat.map(str::to_owned) };
        Ok(self.store.enqueue(group_id, OutboxKind::App, &payload.encode(), &self.now_text())?)
    }

    pub async fn send(
        &mut self,
        group_id: &str,
        entry: &Entry,
        alert: Alert,
        cat: Option<&str>,
    ) -> Result<(), SyncError> {
        let id = self.enqueue_entry(group_id, entry, alert, cat)?;
        let refused = self.flush_rows(group_id).await?;
        // The caller hears the refusal of what it just sent; the row is already out of the queue.
        match refused.into_iter().find(|(row, _)| *row == id) {
            Some((_, e)) => Err(e.into()),
            None => Ok(()),
        }
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
            if self.store.group(&summary.id)?.is_some() {
                self.store.upsert_group(&summary.id, kind, None)?;
            }
        }
        let groups: Vec<String> = self.store.groups()?.into_iter().map(|g| g.id).collect();
        for group_id in &groups {
            match self.sync_group(group_id).await {
                Ok(()) => {}
                Err(SyncError::Api(e)) if e.is(403, "not_member") => self.left_group(group_id)?,
                Err(e) => return Err(e),
            }
        }
        self.flush_outbox().await
    }

    async fn sync_group(&mut self, group_id: &str) -> Result<(), SyncError> {
        self.catch_up(group_id).await?;
        if self.load_group(group_id)?.is_some() {
            self.reconcile_members(group_id).await?;
            self.pull_state(group_id).await?;
        }
        Ok(())
    }

    /// Reacts to a gateway or push event (protocol §7): fetch what it names, or process the item it carries.
    pub async fn handle_event(&mut self, event: Event) -> Result<(), SyncError> {
        match event {
            // The next item, with its ciphertext and the server's received_at, is applied in place; anything else
            // (a gap, a large item, an event without `at`) is read from the log, which brings the gap along.
            Event::Entry { g, s, kind, at, c } => {
                let cursor = self.store.cursor(&g)?;
                if s > cursor {
                    let inline = match (at, c) {
                        (Some(at), Some(c)) if s == cursor + 1 => self.pushed_item(&g, s, &kind, c, at),
                        _ => None,
                    };
                    // An item that does not apply in place is read from the log, which names its author and decides
                    // whether the group has to be rejoined.
                    let applied = match inline {
                        Some(item) => matches!(self.apply_item(&g, &item).await?, Applied::Done),
                        None => false,
                    };
                    if !applied {
                        self.catch_up(&g).await?;
                    }
                }
                self.flush_group(&g).await
            }
            Event::State { g, .. } => self.pull_state(&g).await,
            Event::Membership { g } => {
                match self.reconcile_members(&g).await {
                    Ok(()) => {}
                    Err(SyncError::Api(e)) if e.is(403, "not_member") => self.left_group(&g)?,
                    Err(e) => return Err(e),
                }
                self.flush_group(&g).await
            }
            Event::Welcome {} => {
                self.pull_welcomes().await?;
                self.flush_outbox().await
            }
            Event::Revoke {} | Event::Joined | Event::Closed(_) => Ok(()),
        }
    }

    /// The log item a pushed `entry` event stands for, when the event carries enough to apply it without a round trip
    /// (protocol §7). The epoch comes from the MLS header, as the server reads it; a ciphertext that does not name
    /// this group is left to the log, where the server vouches for it. The event names no author: the sender comes out
    /// of MLS, and an entry already stored at this seq (our own, or one the extension decrypted) is left as it is.
    fn pushed_item(
        &self,
        group_id: &str,
        seq: i64,
        kind: &str,
        ciphertext: Vec<u8>,
        received_at: String,
    ) -> Option<LogItem> {
        let header = geunal_mls::header::parse(&ciphertext).ok()?;
        if header.group_id != mls_group_id(group_id).ok()?.as_slice() {
            return None;
        }
        Some(LogItem {
            seq,
            epoch: header.epoch,
            kind: kind.to_owned(),
            author_id: String::new(),
            ciphertext,
            received_at,
        })
    }

    /// The server no longer counts this device as a member (removed, or its account left). The Remove commit is not
    /// readable from outside, so the MLS state is dropped here; local plaintext history stays, and a later Welcome
    /// starts the group again (protocol §2).
    fn left_group(&self, group_id: &str) -> Result<(), SyncError> {
        self.store.set_rejoin_owed(group_id, false)?;
        if self.load_group(group_id)?.is_some() {
            self.mls.forget_group(&mls_group_id(group_id)?)?;
            self.store.drop_outbox_commits(group_id)?;
            self.notify(Change::Group { group_id: group_id.to_owned() });
        }
        Ok(())
    }

    fn load_group(&self, group_id: &str) -> Result<Option<MlsGroup>, SyncError> {
        Ok(self.mls.load_group(mls_group_id(group_id)?.as_slice())?)
    }

    async fn pull_welcomes(&mut self) -> Result<(), SyncError> {
        if !self.join_welcomes {
            return Ok(());
        }
        for welcome in self.api.welcomes().await? {
            let (mut group, adder) = match self.mls.join_welcome_from(&welcome.data) {
                Ok(joined) => joined,
                // A Welcome for a suite or group we cannot use is dropped; the server deletes it once fetched.
                Err(_) => continue,
            };
            if group.group_id() != mls_group_id(&welcome.group_id)?.as_slice() {
                continue;
            }
            // Local history before a removal stays. The Welcome stands for the Add commit at `seq`, so the log is
            // read from the item after it; items before it belong to epochs this device was not in and are unreadable
            // by design. A server that names no seq restarts the read at 0, and those items are skipped by epoch
            // without decryption: a read of the mailbox, not of keys.
            self.store.upsert_group(&welcome.group_id, GroupKind::Circle, None)?;
            self.store.set_rejoin_owed(&welcome.group_id, false)?;
            let through = welcome.seq.unwrap_or(0);
            self.store.set_cursor(&welcome.group_id, through)?;
            // The member whose commit added this device owes it one bundle of what came before (protocol §5).
            self.store.expect_bundle(&welcome.group_id, &adder.user_id, group.epoch() as i64, welcome.seq)?;
            self.remember_state_key(&welcome.group_id, &group)?;
            group.save()?;
            self.pull_state(&welcome.group_id).await?;
            self.notify(Change::Group { group_id: welcome.group_id.clone() });
        }
        Ok(())
    }

    /// Fetches and applies the log after the cursor, page by page. 410 means the mailbox has moved past the cursor:
    /// come back by external commit (protocol §6). The same way back is taken, once, when the log shows the group in
    /// an epoch this device cannot reach; an item that stays unreadable is skipped with a marker, so one bad item
    /// never blocks a group.
    pub async fn catch_up(&mut self, group_id: &str) -> Result<(), SyncError> {
        let mut rejoined = false;
        // An earlier rejoin dropped the MLS state and did not get through. The log is not read before it does: the
        // rejoin moves the cursor to its own commit.
        if self.store.rejoin_owed(group_id)? {
            if self.load_group(group_id)?.is_some() {
                // The new state was saved and the process stopped before the flag was cleared.
                self.store.set_rejoin_owed(group_id, false)?;
            } else {
                rejoined = true;
                if !self.try_rejoin(group_id).await? {
                    return Ok(());
                }
            }
        }
        loop {
            let cursor = self.store.cursor(group_id)?;
            let page = match self.api.log(group_id, cursor, LOG_PAGE).await {
                Ok(page) => page,
                // Once: a second 410 right after rejoining is a server fault, not something to loop on.
                Err(e) if e.is(410, "cursor_expired") && !rejoined => {
                    rejoined = true;
                    self.rejoin(group_id).await?;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            let caught_up = page.entries.len() < LOG_PAGE as usize
                || page.entries.last().is_some_and(|last| last.seq >= page.last_seq);
            let mut reread = false;
            for item in &page.entries {
                match self.apply_item(group_id, item).await? {
                    Applied::Done => {}
                    Applied::Unreadable => self.skip_item(group_id, item)?,
                    // Once: an item that still cannot be followed right after rejoining is skipped like any other
                    // this device cannot read.
                    Applied::Forked if rejoined => self.skip_item(group_id, item)?,
                    Applied::Forked => {
                        rejoined = true;
                        if !self.try_rejoin(group_id).await? {
                            return Ok(());
                        }
                        // The cursor now stands at the external commit: read on from there.
                        reread = true;
                        break;
                    }
                }
            }
            if reread {
                continue;
            }
            if caught_up {
                return Ok(());
            }
        }
    }

    /// Moves the cursor past an item this device cannot read. Another member's item leaves a marker the app can
    /// show (`Engine::unreadable`); this device's own does not, since its content is in the outbox or was never the
    /// log's to give back.
    fn skip_item(&self, group_id: &str, item: &LogItem) -> Result<(), SyncError> {
        if item.author_id == self.me.user_id {
            return Ok(self.store.set_cursor(group_id, item.seq)?);
        }
        self.store.skip_unreadable(group_id, item.seq, &item.author_id, &item.received_at)?;
        self.notify(Change::Group { group_id: group_id.to_owned() });
        Ok(())
    }

    /// The seqs of the log items of other members this device could not open: the group's state may differ from
    /// theirs by what those items said.
    pub fn unreadable(&self, group_id: &str) -> Result<Vec<i64>, SyncError> {
        Ok(self.store.unreadable(group_id)?)
    }

    /// One log item in order: MLS first, then the entry, then the store (protocol §6). An item from before this
    /// device joined, or its own echo, is passed over; one it should have been able to read is reported to the
    /// caller, which decides between rejoining and skipping.
    async fn apply_item(&mut self, group_id: &str, item: &LogItem) -> Result<Applied, SyncError> {
        let cursor = self.store.cursor(group_id)?;
        if item.seq <= cursor {
            return Ok(Applied::Done);
        }
        let Some(mut group) = self.load_group(group_id)? else {
            // On its way back in: the rejoin decides where the cursor goes.
            if self.store.rejoin_owed(group_id)? {
                return Ok(Applied::Forked);
            }
            // Removed from the group: nothing here is readable until a Welcome restarts the cursor.
            self.store.set_cursor(group_id, item.seq)?;
            return Ok(Applied::Done);
        };
        // An item from an epoch behind ours is either this device's own commit, already applied, or older than the
        // epoch it joined in and so unreadable by design; neither touches MLS.
        if item.epoch < group.epoch() {
            if self.store.has_entry(group_id, item.seq)? && item.author_id == self.me.user_id {
                self.store.set_entry_received_at(group_id, item.seq, &item.received_at)?;
            }
            self.store.set_cursor(group_id, item.seq)?;
            // The commit that made the current epoch, with the epoch's state key missing: the process stopped
            // between saving the MLS state and the bookkeeping that follows a commit. It is done now.
            if item.kind == "commit"
                && item.epoch + 1 == group.epoch()
                && self.store.state_key(group_id, group.epoch() as i64)?.is_none()
            {
                self.after_epoch_change(group_id, &mut group).await?;
            }
            return Ok(Applied::Done);
        }
        // A commit between this device's epoch and the item's never reached it.
        if item.epoch > group.epoch() {
            return Ok(Applied::Forked);
        }
        match item.kind.as_str() {
            // The MLS state is saved before the store hears of the commit: the lines above finish the bookkeeping
            // after a crash in between, where the other order would leave the cursor past a commit MLS never took.
            "commit" => match group.process(&item.ciphertext) {
                Ok(Received::Commit { removed_me: true, .. }) => {
                    self.mls.forget_group(group.group_id())?;
                    self.store.set_cursor(group_id, item.seq)?;
                    self.notify(Change::Group { group_id: group_id.to_owned() });
                }
                Ok(Received::Commit { mine, .. }) => {
                    // This device's own commit, back through the log because the server's answer to it was lost:
                    // the row that posted it is done.
                    let sent = match self.store.outbox_head(group_id)? {
                        Some(row) if mine && row.kind == OutboxKind::Commit => Some(row),
                        _ => None,
                    };
                    // An Add still owes the newcomer its bundle: the row stays, as that, for the outbox to send.
                    let owes_bundle = sent.as_ref().is_some_and(|row| {
                        matches!(
                            Outgoing::decode(&row.payload).ok().and_then(Outgoing::intent),
                            Some(CommitIntent::Add { .. })
                        )
                    });
                    let bundle = Outgoing::Bundle.encode();
                    self.store.enter_epoch(
                        group_id,
                        item.seq,
                        group.epoch() as i64,
                        &group.state_key()?,
                        &self.now_text(),
                        sent.as_ref().map(|row| (row.id, owes_bundle.then_some(bundle.as_slice()))),
                    )?;
                    self.after_epoch_change(group_id, &mut group).await?;
                }
                Ok(_) => self.store.set_cursor(group_id, item.seq)?,
                // The group's next epoch starts with a commit this device cannot take: its own, whose pending copy
                // is gone, or one that does not verify.
                Err(_) => return Ok(Applied::Forked),
            },
            _ => {
                let stored = self.store.entry_author(group_id, item.seq)?;
                if stored.as_deref() == Some(self.me.user_id.as_str()) {
                    // Our own message, stored when the server accepted it; the server's clock replaces the local one.
                    if item.author_id == self.me.user_id {
                        self.store.set_entry_received_at(group_id, item.seq, &item.received_at)?;
                    }
                    self.store.set_cursor(group_id, item.seq)?;
                    return Ok(Applied::Done);
                }
                match group.process_unsaved(&item.ciphertext) {
                    // The entry and the cursor are stored before the ratchet's step is saved
                    // (`MlsGroup::process_unsaved`). An entry already at this seq was decrypted by the extension;
                    // the log's item is the one that belongs there.
                    Ok(Received::Application { sender, data }) => {
                        self.store_received(
                            group_id,
                            item.seq,
                            &sender.user_id,
                            &data,
                            &item.received_at,
                            stored.is_some(),
                        )?;
                        group.save()?;
                    }
                    Ok(_) => {
                        group.save()?;
                        self.store.set_cursor(group_id, item.seq)?;
                    }
                    // The extension decrypted it first, and MLS will not open a message twice.
                    Err(_) if stored.is_some() => self.store.set_cursor(group_id, item.seq)?,
                    Err(_) => return Ok(Applied::Unreadable),
                }
            }
        }
        Ok(Applied::Done)
    }

    /// Stores an entry the log delivered together with the cursor past it.
    fn store_received(
        &self,
        group_id: &str,
        seq: i64,
        author_id: &str,
        body: &[u8],
        received_at: &str,
        replace: bool,
    ) -> Result<(), SyncError> {
        let row = EntryRow {
            group_id: group_id.to_owned(),
            seq,
            author_id: author_id.to_owned(),
            kind: entry::peek_kind(body).unwrap_or_else(|| "unknown".to_owned()),
            body: body.to_vec(),
            received_at: received_at.to_owned(),
        };
        if self.store.store_received(&row, chat_text(body).as_deref(), replace)? {
            self.announce_entry(group_id, body);
        }
        Ok(())
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
        if let Some(text) = chat_text(body) {
            self.store.index_text(group_id, seq, &text)?;
        }
        self.announce_entry(group_id, body);
        Ok(())
    }

    /// Tells the app which screens a newly stored entry changes.
    fn announce_entry(&self, group_id: &str, body: &[u8]) {
        match entry::decode(body) {
            Ok(Entry::Text { .. } | Entry::Media { .. } | Entry::MediaRemove { .. }) => {
                self.notify(Change::Chat { group_id: group_id.to_owned() });
            }
            _ => {
                self.notify(Change::Group { group_id: group_id.to_owned() });
                self.notify(Change::Calendar);
            }
        }
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
        let expected = self.store.expected_bundle(group_id)?;
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
                // Only the bundle the Welcome promised: sealed by the member who added this device, in the epoch it
                // joined in or later.
                ("newcomer_bundle", Some(plain))
                    if expected
                        .as_ref()
                        .is_some_and(|x| blob.owner_id == x.from_user && blob.epoch as i64 >= x.epoch) =>
                {
                    bundle = Some(plain)
                }
                _ => {}
            }
        }
        for row in self.store.availability(group_id)? {
            if !seen_availability.contains(&row.user_id) && row.user_id != self.me.user_id {
                self.store.remove_availability(group_id, &row.user_id)?;
            }
        }
        if let (Some(bundle), Some(expected)) = (bundle, expected) {
            // Taken once: a later bundle in the group is for a later newcomer. The entries and the end of the promise
            // are one write, so a failed one is tried again whole by the next sync; a bundle that does not decode
            // never will, and is let go.
            match self.absorb_bundle(group_id, &bundle, expected.through) {
                Ok(()) => {}
                Err(SyncError::Malformed(_)) => self.store.clear_expected_bundle(group_id)?,
                Err(e) => return Err(e),
            }
        }
        self.notify(Change::Group { group_id: group_id.to_owned() });
        Ok(())
    }

    /// Entries of live appointments from before this device joined, taken on trust from the member who added it
    /// (protocol §5). An item that names its seq lands at that seq, so it sorts exactly as the server ordered it and a
    /// copy this device already has is a no-op; it must come before the Add commit at `through`, since every later
    /// seq is the log's to fill. One without a seq (an older bundle), or any item when the Welcome named no seq, takes
    /// a negative seq in bundle order, ahead of the log, unless a matching entry exists.
    fn absorb_bundle(&self, group_id: &str, bundle: &[u8], through: Option<i64>) -> Result<(), SyncError> {
        let items: Vec<BundleItem> =
            ciborium::from_reader(bundle).map_err(|e| SyncError::Malformed(format!("newcomer bundle: {e}")))?;
        let existing = self.store.entries(group_id)?;
        let same_instant = |a: &str, b: &str| {
            a == b
                || matches!(
                    (DateTime::parse_from_rfc3339(a), DateTime::parse_from_rfc3339(b)),
                    (Ok(x), Ok(y)) if x == y
                )
        };
        let (placed, unplaced): (Vec<&BundleItem>, Vec<&BundleItem>) =
            items.iter().partition(|i| i.seq.is_some() && through.is_some());
        let mut rows = Vec::new();
        for item in placed {
            let (seq, through) = (item.seq.expect("partitioned"), through.expect("partitioned"));
            if seq <= 0 || seq > through {
                continue;
            }
            rows.push(bundle_row(group_id, seq, item));
        }
        let fresh: Vec<&BundleItem> = unplaced
            .into_iter()
            .filter(|i| {
                !existing.iter().any(|e| {
                    e.author_id == i.author_id && e.body == i.entry && same_instant(&e.received_at, &i.received_at)
                })
            })
            .collect();
        let first = self.store.lowest_entry_seq(group_id)? - fresh.len() as i64;
        rows.extend((first..).zip(fresh).map(|(seq, item)| bundle_row(group_id, seq, item)));
        for row in self.store.absorb_bundle(group_id, &rows)? {
            self.announce_entry(group_id, &row.body);
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
            .map(|row| BundleItem {
                seq: (row.seq > 0).then_some(row.seq),
                author_id: row.author_id,
                received_at: row.received_at,
                entry: row.body,
            })
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
            .filter_map(|row| Outgoing::decode(&row.payload).ok().and_then(Outgoing::intent))
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

    /// Comes back with an external commit, removing this device's old leaf: after `cursor_expired`, or when the
    /// group went on to an epoch this device cannot reach. Local plaintext history stays (protocol §6).
    async fn rejoin(&mut self, group_id: &str) -> Result<(), SyncError> {
        // The old state is dropped first (protocol §6): mls-rs keeps prior epochs contiguous per group, and a state
        // that fell behind cannot be continued by an external commit several epochs later. Until the server takes
        // the commit the group has no MLS state, as one this device left; the flag tells the two apart, so the
        // queued writes wait and the next sync tries again.
        self.store.set_rejoin_owed(group_id, true)?;
        self.mls.forget_group(&mls_group_id(group_id)?)?;
        for _ in 0..FLUSH_RETRIES {
            let info = self.api.group_info(group_id).await?;
            let (mut group, commit) = self.mls.external_join(&info.group_info)?;
            let req = CommitRequest {
                epoch: group.epoch() - 1,
                commit,
                group_info: group.group_info()?,
                welcome: None,
                added: Vec::new(),
            };
            match self.api.post_commit(group_id, req).await {
                Ok(accepted) => {
                    group.save()?;
                    self.store.upsert_group(group_id, GroupKind::Circle, None)?;
                    self.store.set_cursor(group_id, accepted.seq)?;
                    self.store.set_rejoin_owed(group_id, false)?;
                    self.after_epoch_change(group_id, &mut group).await?;
                    return Ok(());
                }
                Err(e) if e.is(409, "epoch_conflict") => continue,
                Err(e) => return Err(e.into()),
            }
        }
        Err(SyncError::Api(ApiError::Api { status: 409, code: "epoch_conflict".into(), message: "rejoin".into() }))
    }

    /// `rejoin` for a caller that reads on afterwards: whether the device is back in the group. A failure the next
    /// sync may not meet again is the caller's error; any other (a GroupInfo that does not open, a refusal) leaves
    /// the group waiting for a later GroupInfo without failing the sync of the groups after it.
    async fn try_rejoin(&mut self, group_id: &str) -> Result<bool, SyncError> {
        match self.rejoin(group_id).await {
            Ok(()) => Ok(true),
            Err(e) if comes_back(&e) => Err(e),
            // Removed while it was on its way back: the caller's handling of a lost membership ends the rejoin and
            // lets the queued writes go, which no later GroupInfo would.
            Err(SyncError::Api(e)) if e.is(403, "not_member") => Err(e.into()),
            Err(_) => Ok(false),
        }
    }

    // ----- outbox -----

    /// Sends every group's waiting writes. One group's failure does not hold back the others; the first error comes
    /// back after the rest were tried, unless it is about the whole session, which every group would hit again.
    pub async fn flush_outbox(&mut self) -> Result<(), SyncError> {
        let mut first = None;
        for group_id in self.store.outbox_groups()? {
            match self.flush_group(&group_id).await {
                Ok(()) => {}
                Err(e) if ends_flush(&e) => return Err(e),
                Err(e) => {
                    first.get_or_insert(e);
                }
            }
        }
        first.map_or(Ok(()), Err)
    }

    /// Sends a group's waiting writes in order. A row that keeps failing stays at the head with its attempt count;
    /// the caller's backoff decides when to try again. A row the server refuses for good leaves the queue, so the rows
    /// behind it still go out, and the app hears about the group.
    pub async fn flush_group(&mut self, group_id: &str) -> Result<(), SyncError> {
        self.flush_rows(group_id).await.map(drop)
    }

    /// `flush_group`, answering which rows the server refused for good and why.
    async fn flush_rows(&mut self, group_id: &str) -> Result<Vec<(i64, ApiError)>, SyncError> {
        let mut refused = Vec::new();
        let mut retries = 0;
        // The head is read again after every try: a retry may have settled the row another way (its own commit came
        // back through the log), and then the next row is the one to send.
        while let Some(row) = self.store.outbox_head(group_id)? {
            match self.send_row(group_id, &row).await? {
                SendOutcome::Sent | SendOutcome::Dropped => {
                    self.store.dequeue(row.id)?;
                    retries = 0;
                }
                SendOutcome::Refused(e) => {
                    self.store.dequeue(row.id)?;
                    self.notify(Change::Group { group_id: group_id.to_owned() });
                    refused.push((row.id, e));
                    retries = 0;
                }
                SendOutcome::Retry => {
                    retries += 1;
                    if retries >= FLUSH_RETRIES {
                        self.store.bump_attempts(row.id)?;
                        return Ok(refused);
                    }
                }
                SendOutcome::Later => {
                    self.store.bump_attempts(row.id)?;
                    return Ok(refused);
                }
            }
        }
        Ok(refused)
    }

    async fn send_row(&mut self, group_id: &str, row: &OutboxRow) -> Result<SendOutcome, SyncError> {
        let Some(mut group) = self.load_group(group_id)? else {
            // On its way back in by external commit: the writes wait for it.
            if self.store.rejoin_owed(group_id)? {
                return Ok(SendOutcome::Later);
            }
            // No longer a member: nothing queued for this group can be sent.
            return Ok(SendOutcome::Dropped);
        };
        match Outgoing::decode(&row.payload)? {
            Outgoing::App { entry: bytes, alert, cat } => {
                let message = group.encrypt(&bytes)?;
                let req = MessageRequest { epoch: group.epoch(), message, alert, cat };
                match self.api.post_message(group_id, req).await {
                    Ok(accepted) => match accepted.received_at {
                        // Stored under the server's clock (protocol §4). The cursor moves past our own item when it
                        // is the next one, so the next pushed seq can be applied in place.
                        Some(received_at) => {
                            self.store_entry(group_id, accepted.seq, &self.me.user_id, &bytes, &received_at)?;
                            if self.store.cursor(group_id)? + 1 == accepted.seq {
                                self.store.set_cursor(group_id, accepted.seq)?;
                            }
                            Ok(SendOutcome::Sent)
                        }
                        // A server from before the 201 carried received_at: stored under the local clock for now,
                        // and the cursor stays behind so the next catch-up reads the echo and replaces the time.
                        None => {
                            let provisional = self.now_text();
                            self.store_entry(group_id, accepted.seq, &self.me.user_id, &bytes, &provisional)?;
                            Ok(SendOutcome::Sent)
                        }
                    },
                    Err(e) if e.is(409, "stale_epoch") => {
                        // Process the commit that moved the epoch, then encrypt again (protocol §11).
                        self.catch_up(group_id).await?;
                        Ok(SendOutcome::Retry)
                    }
                    Err(e) if e.is(403, "not_member") => Ok(SendOutcome::Dropped),
                    Err(e) if is_transient(&e) => Ok(SendOutcome::Later),
                    Err(e) if is_refusal(&e) => Ok(SendOutcome::Refused(e)),
                    Err(e) => Err(e.into()),
                }
            }
            Outgoing::State { kind, plaintext } => {
                match self.put_state_now(group_id, &group, &kind, &plaintext).await {
                    Ok(()) => Ok(SendOutcome::Sent),
                    Err(SyncError::Api(e)) if e.is(409, "state_conflict") => Ok(SendOutcome::Retry),
                    Err(SyncError::Api(e)) if is_transient(&e) => Ok(SendOutcome::Later),
                    Err(SyncError::Api(e)) if is_refusal(&e) => Ok(SendOutcome::Refused(e)),
                    Err(e) => Err(e),
                }
            }
            Outgoing::Bundle => match self.publish_newcomer_bundle(group_id, &group).await {
                Ok(()) => Ok(SendOutcome::Sent),
                Err(SyncError::Api(e)) if is_transient(&e) => Ok(SendOutcome::Later),
                Err(SyncError::Api(e)) if is_refusal(&e) => Ok(SendOutcome::Refused(e)),
                Err(e) => Err(e),
            },
            Outgoing::Commit(intent) => self.send_commit(group_id, row.id, &mut group, intent, None).await,
            Outgoing::Posted { intent, request } => {
                self.send_commit(group_id, row.id, &mut group, intent, Some(request)).await
            }
        }
    }

    /// Builds the commit an intent asks for in the group's current epoch, or `None` when the group already is as the
    /// intent wants it.
    async fn build_commit(
        &mut self,
        group_id: &str,
        group: &mut MlsGroup,
        intent: &CommitIntent,
    ) -> Result<Option<CommitRequest>, SyncError> {
        let roster = group.roster()?;
        let (bundle, added) = match intent {
            CommitIntent::Add { user_ids } => {
                let mut packages = Vec::new();
                let mut added = Vec::new();
                for user_id in user_ids {
                    if roster.iter().any(|m| m.identity.user_id == *user_id) {
                        continue;
                    }
                    // Protocol §2: the key package must belong to a device the server lists for this user. The server
                    // lists only someone this member may add (invited, or a member's new device); anyone else is
                    // left out of the intent, which a rebuild would not change.
                    let devices = match self.api.devices(user_id).await {
                        Ok(devices) => devices,
                        Err(e) if e.is(404, "not_found") => {
                            self.notify(Change::Group { group_id: group_id.to_owned() });
                            continue;
                        }
                        Err(e) => return Err(e.into()),
                    };
                    for device in devices {
                        let package = self.api.claim_key_package(&device.id).await?;
                        let info = MlsClient::inspect_key_package(&package)?;
                        if info.identity != Identity::new(user_id.clone(), device.id.clone())
                            || info.signature_key != device.signature_key
                        {
                            return Err(SyncError::Malformed(format!(
                                "key package of {user_id} does not match its device"
                            )));
                        }
                        packages.push(package);
                        // The server wants device ids here, and it marks their users active (openapi.yaml).
                        added.push(device.id.clone());
                    }
                }
                if packages.is_empty() {
                    return Ok(None);
                }
                (group.add_members(&packages)?, added)
            }
            CommitIntent::Remove { user_ids } => {
                let indexes: Vec<u32> =
                    roster.iter().filter(|m| user_ids.contains(&m.identity.user_id)).map(|m| m.index).collect();
                if indexes.is_empty() {
                    return Ok(None);
                }
                (group.remove_members(&indexes)?, Vec::new())
            }
            CommitIntent::SelfUpdate => (group.self_update()?, Vec::new()),
        };
        Ok(Some(CommitRequest {
            epoch: group.epoch(),
            commit: bundle.commit,
            group_info: bundle.group_info,
            welcome: bundle.welcome,
            added,
        }))
    }

    /// Sends a commit row. `posted` is the commit an earlier try built and may have delivered.
    async fn send_commit(
        &mut self,
        group_id: &str,
        row_id: i64,
        group: &mut MlsGroup,
        intent: CommitIntent,
        posted: Option<CommitRequest>,
    ) -> Result<SendOutcome, SyncError> {
        let request = match posted {
            // The answer to the earlier try never came. The same commit goes out again: taken now, or refused as
            // `epoch_conflict` because it was taken then, and the log brings it back.
            Some(request) if group.has_pending_commit() && request.epoch == group.epoch() => request,
            _ => {
                // A pending commit without its request was saved by a try that stopped before posting, and one
                // from another epoch lost to a commit the log brought: neither reached the server.
                if group.has_pending_commit() {
                    group.clear_pending_commit();
                    group.save()?;
                }
                let Some(request) = self.build_commit(group_id, group, &intent).await? else {
                    return Ok(SendOutcome::Dropped);
                };
                // Saved before it is posted, the pending commit first: whatever stops this process from here on,
                // the next try finds the commit the server may have and the means to apply it.
                group.save()?;
                let posted = Outgoing::Posted { intent: intent.clone(), request: request.clone() };
                self.store.set_outbox_payload(row_id, &posted.encode())?;
                request
            }
        };
        let added = !request.added.is_empty();
        match self.api.post_commit(group_id, request).await {
            Ok(accepted) => {
                group.apply_pending_commit()?;
                if self.store.cursor(group_id)? + 1 == accepted.seq {
                    self.store.set_cursor(group_id, accepted.seq)?;
                }
                // The row of an Add becomes the bundle it owes before anything else can fail, and the flush sends it
                // next.
                if added {
                    self.store.set_outbox_payload(row_id, &Outgoing::Bundle.encode())?;
                }
                self.after_epoch_change(group_id, group).await?;
                Ok(if added { SendOutcome::Retry } else { SendOutcome::Sent })
            }
            Err(e) if e.is(409, "epoch_conflict") => {
                // A commit won this epoch, and the log says whose. Another member's clears the pending commit as it
                // is processed, and the intent is rebuilt on the next round; this device's own earlier try is
                // applied from the pending commit, which settles the row.
                self.catch_up(group_id).await?;
                if let Some(mut current) = self.load_group(group_id)?
                    && current.has_pending_commit()
                {
                    current.clear_pending_commit();
                    current.save()?;
                }
                Ok(SendOutcome::Retry)
            }
            Err(e) if e.is(403, "not_member") => {
                self.discard_commit(row_id, group, &intent)?;
                Ok(SendOutcome::Dropped)
            }
            // The server may or may not have taken it: the pending commit and the request stay for the next try.
            Err(e) if is_transient(&e) => Ok(SendOutcome::Later),
            // The server answered and did not take this commit. No refusal is final for the row: the intent is
            // rebuilt from the roster and the devices listed at the next try, so an answer about the commit as
            // built (a device replaced since its key package was claimed) is not one about the intent.
            Err(e @ ApiError::Api { status: 400..=499, .. }) if !ends_flush_api(&e) => {
                self.discard_commit(row_id, group, &intent)?;
                Err(e.into())
            }
            // The session's trouble, not the commit's: nothing says the server did not take it.
            Err(e) => Err(e.into()),
        }
    }

    /// Forgets a commit the server answered it will not take: the pending commit goes from the stored state and the
    /// row is an intent again.
    fn discard_commit(&self, row_id: i64, group: &mut MlsGroup, intent: &CommitIntent) -> Result<(), SyncError> {
        group.clear_pending_commit();
        group.save()?;
        self.store.set_outbox_payload(row_id, &Outgoing::Commit(intent.clone()).encode())?;
        Ok(())
    }
}

enum SendOutcome {
    Sent,
    /// Nothing left to send: this device left the group, or the change is already in it.
    Dropped,
    /// The server will never take this row (`is_refusal`).
    Refused(ApiError),
    Retry,
    Later,
}

/// Answers that sending the same entry or state blob again cannot change (protocol §11): a malformed or oversized
/// request, a group that is gone, or a member this device may not act on. Commit intents are not judged by it. Session and device answers (401, `device_revoked`,
/// `replace_required`) and the 409s the engine resolves are not among them: they say nothing about the row.
fn is_refusal(e: &ApiError) -> bool {
    match e {
        ApiError::Api { status: 400 | 404 | 413 | 422, .. } => true,
        ApiError::Api { status: 403, code, .. } => matches!(code.as_str(), "not_member" | "not_owner" | "blocked"),
        _ => false,
    }
}

/// Errors every group's flush would hit again: no point trying the next group.
fn ends_flush(e: &SyncError) -> bool {
    match e {
        SyncError::Api(e) => ends_flush_api(e),
        SyncError::NotSignedIn | SyncError::Store(_) => true,
        _ => false,
    }
}

/// Answers about the session or the device, which say nothing about the request they came back for.
fn ends_flush_api(e: &ApiError) -> bool {
    matches!(e, ApiError::Revoked { .. } | ApiError::Api { status: 401, .. })
        || e.is(403, "device_revoked")
        || e.is(403, "replace_required")
}

/// Failures of a rejoin that the next sync may not meet again: the network, a rate limit, the session. Anything
/// else is about the group itself and will not change by waiting.
fn comes_back(e: &SyncError) -> bool {
    ends_flush(e) || matches!(e, SyncError::Api(e) if is_transient(e))
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
    /// The server seq when the sender had it, so the receiver files the entry where the log order puts it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    seq: Option<i64>,
    author_id: String,
    received_at: String,
    #[serde(with = "geunal_domain::entry::cbor_bytes")]
    entry: Vec<u8>,
}

/// The text a chat entry adds to the search index.
fn chat_text(body: &[u8]) -> Option<String> {
    match entry::decode(body) {
        Ok(Entry::Text { body, .. }) => Some(body),
        _ => None,
    }
}

/// A newcomer bundle item as the row it is stored as, with its text for the search index.
fn bundle_row(group_id: &str, seq: i64, item: &BundleItem) -> (EntryRow, Option<String>) {
    let row = EntryRow {
        group_id: group_id.to_owned(),
        seq,
        author_id: item.author_id.clone(),
        kind: entry::peek_kind(&item.entry).unwrap_or_else(|| "unknown".to_owned()),
        body: item.entry.clone(),
        received_at: item.received_at.clone(),
    };
    (row, chat_text(&item.entry))
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

//! The UniFFI facade the apps call (docs/parts/rust-core.md §3): Swift sees `Core.open(...)` and `sync() async throws`,
//! Kotlin `Core.open(...)` and `suspend fun sync()`. The M0 surface: open the encrypted store, sign in as a guest,
//! sync, make and join groups, send entries, and read the state every device derives from the same log. Network work
//! runs on a tokio runtime the core owns; the app's own executor only awaits the result.
//!
//! The M1 surface (docs/design/m1.md) lives in [`calendar`], [`people`] and [`account`]. Every function the screens
//! call is a method of `Core`, so a view model depends on the one generated protocol and a test hands it a fake.

pub mod account;
pub mod calendar;
mod local;
pub mod people;

pub use account::*;
pub use calendar::*;
pub use people::*;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use geunal_domain::appointments::GroupState;
use geunal_domain::availability::Availability;
use geunal_domain::entry::{Choice, Entry};
use geunal_mls::{Identity, MlsClient};
use geunal_store::{DB_FILE, Store};
use geunal_sync::realtime::{Event, Realtime};
use geunal_sync::{Alert, Engine, HttpTransport, Listener, Session as SyncSession, SyncError, Transport as _};
use rand_core::{OsRng, RngCore};
use tokio::sync::Mutex;

uniffi::setup_scaffolding!();

/// Names under which the app's keychain or keystore holds the core's secrets.
const DB_KEY: &str = "db_key";
const SIGNING_KEY: &str = "device_signing_key";
const SESSION: &str = "session";
/// Key packages uploaded at sign-in; others consume one each time they add this device.
const KEY_PACKAGES: usize = 10;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("store: {reason}")]
    Store { reason: String },
    #[error("secret store: {reason}")]
    SecretStore { reason: String },
    #[error("not signed in")]
    NotSignedIn,
    /// A response the server chose to send (protocol §11); the app branches on `code`. The field is `detail`, not
    /// `message`, because Kotlin's `Throwable` already has a `message` and the generated exception would clash.
    #[error("api {status} {code}: {detail}")]
    Api { status: u16, code: String, detail: String },
    #[error("network: {reason}")]
    Network { reason: String },
    #[error("sync: {reason}")]
    Sync { reason: String },
    #[error("realtime: {reason}")]
    Realtime { reason: String },
    /// The input breaks a rule the screen should already have enforced: an empty title, an end before the start, the
    /// last calendar. `reason` is for logs and tests, not for display.
    #[error("invalid: {reason}")]
    Invalid { reason: String },
    /// No such calendar, event, person or template on this device.
    #[error("not found: {what}")]
    NotFound { what: String },
    /// The twelve words are not this account's recovery code: a word outside the list, a checksum that does not
    /// match, or a code that does not unwrap the account master key (protocol §10).
    #[error("wrong recovery code")]
    WrongRecoveryCode,
    /// The server wants the account proven again before deleting it (403 `reauth_required`, protocol §13). Nothing
    /// was deleted and the session is still valid: get a fresh `DeletionProof` and try again.
    #[error("the account has to be proven again")]
    ReauthRequired,
    /// The server has no recovery material for this account yet (409 `recovery_missing`), so a guest cannot prove the
    /// account and no backup can be made. The core uploads it first; try again after the next `sync()`.
    #[error("recovery material not uploaded yet")]
    RecoveryMissing,
    /// A function of the M1 surface whose body has not landed yet. Gone by the end of M1.
    #[error("not implemented yet: {what}")]
    NotImplemented { what: String },
}

/// The error of an M1 function whose body comes in a later pull request; the signature is final already.
pub(crate) fn not_yet(what: &str) -> CoreError {
    CoreError::NotImplemented { what: what.to_owned() }
}

impl From<SyncError> for CoreError {
    fn from(e: SyncError) -> Self {
        match e {
            SyncError::Api(geunal_sync::ApiError::Api { status, code, message }) => {
                CoreError::Api { status, code, detail: message }
            }
            SyncError::Api(geunal_sync::ApiError::Network(reason)) => CoreError::Network { reason },
            SyncError::Store(e) => CoreError::Store { reason: e.to_string() },
            SyncError::NotSignedIn => CoreError::NotSignedIn,
            SyncError::Realtime(reason) => CoreError::Realtime { reason },
            other => CoreError::Sync { reason: other.to_string() },
        }
    }
}

impl From<geunal_store::StoreError> for CoreError {
    fn from(e: geunal_store::StoreError) -> Self {
        CoreError::Store { reason: e.to_string() }
    }
}

impl From<geunal_mls::Error> for CoreError {
    fn from(e: geunal_mls::Error) -> Self {
        CoreError::Sync { reason: e.to_string() }
    }
}

/// Implemented by the app over the iOS keychain or the Android keystore. The core keeps its secrets (device signing
/// key, session, database key) only here, never in SQLite (docs/parts/rust-core.md §3).
#[uniffi::export(with_foreign)]
pub trait SecretStore: Send + Sync {
    fn get(&self, name: String) -> Option<Vec<u8>>;
    fn put(&self, name: String, value: Vec<u8>);
    fn delete(&self, name: String);
}

/// What changed, so the app knows which screens to redraw.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Change {
    /// Calendars, events, shift templates, people or holidays: every calendar screen and the widget snapshot.
    Calendar,
    Group {
        group_id: String,
    },
    Chat {
        group_id: String,
    },
    /// The account changed: signed in, linked, revoked by another device's sign-in, or deleted. The app reads
    /// `session()` and `account()` again, and `revocation()` for the "기기 로그아웃됨" screen.
    Account,
}

/// Implemented by the app to hear about changes.
#[uniffi::export(with_foreign)]
pub trait CoreListener: Send + Sync {
    fn on_change(&self, change: Change);
}

struct Forward(Arc<dyn CoreListener>);

impl Listener for Forward {
    fn on_change(&self, change: geunal_sync::Change) {
        self.0.on_change(match change {
            geunal_sync::Change::Calendar => Change::Calendar,
            geunal_sync::Change::Group { group_id } => Change::Group { group_id },
            geunal_sync::Change::Chat { group_id } => Change::Chat { group_id },
        })
    }
}

/// The signed-in account and device.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Session {
    pub user_id: String,
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupInfo {
    pub group_id: String,
    pub name: Option<String>,
    /// Last seq applied.
    pub cursor: i64,
    /// `None` while this device is not (or no longer) a member.
    pub epoch: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Mode {
    Vote,
    Dictator,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Stage {
    Time,
    Activity,
}

impl From<Mode> for geunal_domain::entry::Mode {
    fn from(m: Mode) -> Self {
        match m {
            Mode::Vote => Self::Vote,
            Mode::Dictator => Self::Dictator,
        }
    }
}

impl From<Stage> for geunal_domain::entry::Stage {
    fn from(s: Stage) -> Self {
        match s {
            Stage::Time => Self::Time,
            Stage::Activity => Self::Activity,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PollView {
    pub stage: Stage,
    pub options: Vec<String>,
    /// Participants who have voted.
    pub voters: Vec<String>,
    pub closed: bool,
    pub result: Option<String>,
}

/// An appointment as every device derives it from the log.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppointmentView {
    pub appt: String,
    pub leader: String,
    pub day: String,
    pub mode: Mode,
    pub start: Option<u16>,
    pub end: Option<u16>,
    pub activity: Option<String>,
    pub note: Option<String>,
    pub participants: Vec<String>,
    pub tentative: bool,
    pub cancelled: bool,
    pub polls: Vec<PollView>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupStateView {
    pub appointments: Vec<AppointmentView>,
    /// The full state in one canonical string, so two devices can be compared for equality.
    pub digest: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatMessage {
    pub seq: i64,
    pub author_id: String,
    pub body: String,
    pub received_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MemberAvailability {
    pub user_id: String,
    pub window_start: String,
    pub days: u16,
    pub bits: Vec<u8>,
}

/// A gateway event after the core has acted on it (protocol §7).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RealtimeEvent {
    Entry { group_id: String, seq: i64 },
    State { group_id: String },
    Membership { group_id: String },
    Welcome,
    Revoke,
    Joined,
    Closed { reason: String },
}

enum State {
    SignedOut,
    SignedIn(Box<Engine<Arc<HttpTransport>>>),
}

#[derive(uniffi::Object)]
pub struct Core {
    runtime: tokio::runtime::Runtime,
    store_dir: PathBuf,
    /// The one connection to the store. The calendar reads and writes through it directly, signed in or not, so a
    /// month view never waits for a sync holding the engine; the engine keeps a clone of it.
    store: Store,
    api: Arc<HttpTransport>,
    listener: Arc<dyn CoreListener>,
    secrets: Arc<dyn SecretStore>,
    db_key: [u8; 32],
    state: Mutex<State>,
    realtime: Mutex<Option<Realtime>>,
    /// A fixed "now" for tests; `None` reads the system clock.
    clock: std::sync::Mutex<Option<chrono::DateTime<chrono::Utc>>>,
}

#[uniffi::export]
impl Core {
    /// Opens or creates the encrypted store in `store_dir`. The database key is made on first open and kept in
    /// `secrets`, which is why `open` takes it: the key must come from the keychain before the file can be opened.
    /// A session kept from an earlier run is restored, so `sync` works right away.
    #[uniffi::constructor]
    pub fn open(
        store_dir: String,
        api_base: String,
        listener: Arc<dyn CoreListener>,
        secrets: Arc<dyn SecretStore>,
    ) -> Result<Arc<Self>, CoreError> {
        let db_key = database_key(secrets.as_ref())?;
        let store_dir = PathBuf::from(store_dir);
        let store = Store::open(&store_dir.join(DB_FILE), &db_key)?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("geunal-core")
            .build()
            .map_err(|e| CoreError::Sync { reason: e.to_string() })?;
        let api = Arc::new(HttpTransport::new(&api_base).map_err(|e| CoreError::Network { reason: e.to_string() })?);
        calendar::ensure_default_calendar(&store)?;
        let state = match saved_session(secrets.as_ref()) {
            Some(session) => {
                api.restore_session(&session.access_token);
                let engine = build_engine(
                    &store_dir,
                    &store,
                    &db_key,
                    &session,
                    secrets.as_ref(),
                    api.clone(),
                    listener.clone(),
                )?;
                State::SignedIn(Box::new(engine))
            }
            None => State::SignedOut,
        };
        Ok(Arc::new(Self {
            runtime,
            store_dir,
            store,
            api,
            listener,
            secrets,
            db_key,
            state: Mutex::new(state),
            realtime: Mutex::new(None),
            clock: std::sync::Mutex::new(None),
        }))
    }

    /// The SQLCipher release inside the app build, for the M0 screen that shows the core runs on the device.
    pub fn cipher_version(&self) -> Result<String, CoreError> {
        Ok(self.store.cipher_version()?)
    }

    /// The signed-in account, if any.
    pub fn session(&self) -> Option<Session> {
        saved_session(self.secrets.as_ref()).map(|s| Session { user_id: s.user_id, device_id: s.device_id })
    }

    /// Signs in through the auth stub (`POST /auth/stub`, only on local and CI servers): `subject` stands for the
    /// identity Kakao or Apple would assert. The device key is made and kept in the secret store, the session too, and
    /// the first key packages go up. From M1 the stub follows the Kakao rules (protocol §13), so a second device gets
    /// `ReplaceRequired`; against an M0 server every call registers.
    pub async fn sign_in_stub(
        self: Arc<Self>,
        subject: String,
        display_name: String,
        platform: String,
    ) -> Result<AuthResult, CoreError> {
        let this = self.clone();
        self.run(async move {
            let secret = signing_secret(this.secrets.as_ref())?;
            let session = geunal_sync::sign_in(&this.api, &subject, &display_name, &platform, &secret).await?;
            let json = serde_json::to_vec(&session).map_err(|e| CoreError::Sync { reason: e.to_string() })?;
            this.secrets.put(SESSION.to_owned(), json);
            let engine = build_engine(
                &this.store_dir,
                &this.store,
                &this.db_key,
                &session,
                this.secrets.as_ref(),
                this.api.clone(),
                this.listener.clone(),
            )?;
            engine.publish_key_packages(KEY_PACKAGES).await?;
            *this.state.lock().await = State::SignedIn(Box::new(engine));
            // An M0 server says neither whether the account is new nor whether it holds recovery material.
            Ok(AuthResult::Registered {
                session: Session { user_id: session.user_id, device_id: session.device_id },
                new_account: false,
                recovery: false,
            })
        })
        .await
    }

    /// Catches every group up from its cursor and sends what is waiting in the outbox. Before a sign-in there is
    /// nothing to catch up, so this returns at once: the apps' first screen opens the core and syncs without an account.
    pub async fn sync(self: Arc<Self>) -> Result<(), CoreError> {
        if saved_session(self.secrets.as_ref()).is_none() {
            return Ok(());
        }
        let this = self.clone();
        self.run(async move { this.engine(Box::new(|e| Box::pin(async move { e.sync().await }))).await }).await
    }

    pub async fn create_group(self: Arc<Self>, name: Option<String>) -> Result<String, CoreError> {
        let this = self.clone();
        self.run(async move {
            this.engine(Box::new(|e| Box::pin(async move { e.create_group(name.as_deref()).await }))).await
        })
        .await
    }

    /// Adds members by user id (M0; invites come at M2). Their devices get a Welcome and join on their next `sync`.
    pub async fn add_members(self: Arc<Self>, group_id: String, user_ids: Vec<String>) -> Result<(), CoreError> {
        let this = self.clone();
        self.run(async move {
            this.engine(Box::new(|e| Box::pin(async move { e.add_members(&group_id, &user_ids).await }))).await
        })
        .await
    }

    pub async fn remove_member(self: Arc<Self>, group_id: String, user_id: String) -> Result<(), CoreError> {
        let this = self.clone();
        self.run(async move {
            this.engine(Box::new(|e| Box::pin(async move { e.remove_member(&group_id, &user_id).await }))).await
        })
        .await
    }

    pub async fn send_text(self: Arc<Self>, group_id: String, body: String) -> Result<String, CoreError> {
        let id = uuid_v7();
        let entry = Entry::Text { id: id.clone(), body, reply_to: None };
        self.send(group_id, entry, Alert::All, Some("chat")).await?;
        Ok(id)
    }

    /// Requests a date (kickoff §5.4); the requester becomes the leader. Returns the appointment id.
    #[allow(clippy::too_many_arguments)]
    pub async fn request_date(
        self: Arc<Self>,
        group_id: String,
        day: String,
        mode: Mode,
        start: Option<u16>,
        end: Option<u16>,
        activity: Option<String>,
        note: Option<String>,
        leader_options: bool,
    ) -> Result<String, CoreError> {
        let appt = uuid_v7();
        let entry =
            Entry::Request { appt: appt.clone(), day, mode: mode.into(), start, end, activity, note, leader_options };
        self.send(group_id, entry, Alert::All, Some("plan")).await?;
        Ok(appt)
    }

    /// Accept or "later"; the leader is alerted.
    pub async fn respond(self: Arc<Self>, group_id: String, appt: String, accept: bool) -> Result<(), CoreError> {
        let leader = self.leader_of(&group_id, &appt).await;
        let entry = Entry::Response { appt, accept };
        self.send(group_id, entry, Alert::Users(leader.into_iter().collect()), Some("plan")).await
    }

    /// Opens (or reopens) a poll with the options the leader's device fixed.
    pub async fn open_poll(
        self: Arc<Self>,
        group_id: String,
        appt: String,
        stage: Stage,
        options: Vec<String>,
    ) -> Result<(), CoreError> {
        let entry = Entry::Poll { appt, stage: stage.into(), options };
        self.send(group_id, entry, Alert::All, Some("plan")).await
    }

    /// Votes for an option, or for "any" when `option` is `None`.
    pub async fn vote(
        self: Arc<Self>,
        group_id: String,
        appt: String,
        stage: Stage,
        option: Option<String>,
    ) -> Result<(), CoreError> {
        let choice = option.map(Choice::Opt).unwrap_or(Choice::Any);
        let entry = Entry::PollVote { appt, stage: stage.into(), choice };
        self.send(group_id, entry, Alert::None, None).await
    }

    pub async fn close_poll(self: Arc<Self>, group_id: String, appt: String, stage: Stage) -> Result<(), CoreError> {
        let entry = Entry::PollClose { appt, stage: stage.into() };
        self.send(group_id, entry, Alert::All, Some("plan")).await
    }

    pub async fn cancel(self: Arc<Self>, group_id: String, appt: String) -> Result<(), CoreError> {
        self.send(group_id, Entry::Cancel { appt }, Alert::All, Some("plan")).await
    }

    /// Shares this member's availability with the group (sealed with the epoch's key on send).
    pub async fn set_availability(
        self: Arc<Self>,
        group_id: String,
        window_start: String,
        days: u16,
        bits: Vec<u8>,
    ) -> Result<(), CoreError> {
        let this = self.clone();
        self.run(async move {
            this.engine(Box::new(|e| {
                Box::pin(async move {
                    e.set_availability(&group_id, &Availability { window_start, days, bits })?;
                    e.flush_group(&group_id).await
                })
            }))
            .await
        })
        .await
    }

    pub fn groups(&self) -> Result<Vec<GroupInfo>, CoreError> {
        let state = self.state.blocking_lock();
        let State::SignedIn(engine) = &*state else { return Err(CoreError::NotSignedIn) };
        engine
            .store()
            .groups()?
            .into_iter()
            .map(|g| Ok(GroupInfo { epoch: engine.epoch(&g.id)?, group_id: g.id, name: g.name, cursor: g.cursor }))
            .collect()
    }

    /// Current MLS roster, as user ids.
    pub fn members(&self, group_id: String) -> Result<Vec<String>, CoreError> {
        let state = self.state.blocking_lock();
        let State::SignedIn(engine) = &*state else { return Err(CoreError::NotSignedIn) };
        let mut users: Vec<String> = engine.roster(&group_id)?.into_iter().map(|i| i.user_id).collect();
        users.sort();
        users.dedup();
        Ok(users)
    }

    /// The group's appointments, derived from the stored log (kickoff §3 principle 3).
    pub fn group_state(&self, group_id: String) -> Result<GroupStateView, CoreError> {
        let state = self.state.blocking_lock();
        let State::SignedIn(engine) = &*state else { return Err(CoreError::NotSignedIn) };
        Ok(view_of(&engine.group_state(&group_id)?))
    }

    /// Chat texts in log order.
    pub fn messages(&self, group_id: String) -> Result<Vec<ChatMessage>, CoreError> {
        let state = self.state.blocking_lock();
        let State::SignedIn(engine) = &*state else { return Err(CoreError::NotSignedIn) };
        Ok(engine
            .log(&group_id)?
            .into_iter()
            .filter_map(|e| match e.entry {
                Entry::Text { body, .. } => Some(ChatMessage {
                    seq: e.seq,
                    author_id: e.author_id,
                    body,
                    received_at: e.received_at.to_rfc3339(),
                }),
                _ => None,
            })
            .collect())
    }

    /// Members' availability as the group calendar sees it; a member without a readable blob is absent.
    pub fn group_availability(&self, group_id: String) -> Result<Vec<MemberAvailability>, CoreError> {
        let state = self.state.blocking_lock();
        let State::SignedIn(engine) = &*state else { return Err(CoreError::NotSignedIn) };
        Ok(engine
            .group_availability(&group_id)?
            .into_iter()
            .map(|a| MemberAvailability {
                user_id: a.user_id,
                window_start: a.window_start,
                days: a.days as u16,
                bits: a.bits,
            })
            .collect())
    }

    /// Connects to the gateway's device topic (protocol §7). Events are read with `next_realtime_event`.
    pub async fn connect_realtime(self: Arc<Self>, gateway_base: String) -> Result<(), CoreError> {
        let this = self.clone();
        self.run(async move {
            let session = saved_session(this.secrets.as_ref()).ok_or(CoreError::NotSignedIn)?;
            let realtime = Realtime::connect(&gateway_base, &session.access_token, &session.device_id).await?;
            *this.realtime.lock().await = Some(realtime);
            Ok(())
        })
        .await
    }

    /// Waits up to `timeout_ms` for the next gateway event, applies it (fetching what it names) and returns it.
    pub async fn next_realtime_event(self: Arc<Self>, timeout_ms: u64) -> Result<Option<RealtimeEvent>, CoreError> {
        let this = self.clone();
        self.run(async move {
            let event = {
                let mut realtime = this.realtime.lock().await;
                let Some(realtime) = realtime.as_mut() else {
                    return Err(CoreError::Realtime { reason: "not connected".into() });
                };
                realtime.next_within(Duration::from_millis(timeout_ms)).await
            };
            let Some(event) = event else { return Ok(None) };
            let view = match &event {
                Event::Entry { g, s, .. } => RealtimeEvent::Entry { group_id: g.clone(), seq: *s },
                Event::State { g, .. } => RealtimeEvent::State { group_id: g.clone() },
                Event::Membership { g } => RealtimeEvent::Membership { group_id: g.clone() },
                Event::Welcome {} => RealtimeEvent::Welcome,
                Event::Revoke {} => RealtimeEvent::Revoke,
                Event::Joined => RealtimeEvent::Joined,
                Event::Closed(reason) => RealtimeEvent::Closed { reason: reason.clone() },
            };
            this.engine(Box::new(|e| Box::pin(async move { e.handle_event(event).await }))).await?;
            Ok(Some(view))
        })
        .await
    }

    pub async fn disconnect_realtime(self: Arc<Self>) {
        *self.realtime.lock().await = None;
    }
}

impl Core {
    /// Fixes the clock the calendar reads ("today", the next-up bar, alerts) for tests. Not part of the FFI.
    #[doc(hidden)]
    pub fn set_clock(&self, now: Option<chrono::DateTime<chrono::Utc>>) {
        *self.clock.lock().unwrap_or_else(|p| p.into_inner()) = now;
    }

    pub(crate) fn now(&self) -> chrono::DateTime<chrono::Utc> {
        self.clock.lock().unwrap_or_else(|p| p.into_inner()).unwrap_or_else(chrono::Utc::now)
    }

    /// Tells the app the calendar screens and the widget snapshot are out of date.
    pub(crate) fn calendar_changed(&self) {
        self.listener.on_change(Change::Calendar);
    }

    /// Runs network work on the core's runtime; the app's executor only awaits the handle.
    async fn run<F, R>(&self, fut: F) -> Result<R, CoreError>
    where
        F: std::future::Future<Output = Result<R, CoreError>> + Send + 'static,
        R: Send + 'static,
    {
        self.runtime.spawn(fut).await.map_err(|e| CoreError::Sync { reason: format!("task failed: {e}") })?
    }

    /// One engine operation at a time, under the state lock.
    async fn engine<R>(&self, f: EngineOp<'_, R>) -> Result<R, CoreError> {
        let mut state = self.state.lock().await;
        let State::SignedIn(engine) = &mut *state else { return Err(CoreError::NotSignedIn) };
        f(engine).await.map_err(CoreError::from)
    }

    async fn send(
        self: Arc<Self>,
        group_id: String,
        entry: Entry,
        alert: Alert,
        cat: Option<&'static str>,
    ) -> Result<(), CoreError> {
        let this = self.clone();
        self.run(async move {
            this.engine(Box::new(|e| Box::pin(async move { e.send(&group_id, &entry, alert, cat).await }))).await
        })
        .await
    }

    async fn leader_of(&self, group_id: &str, appt: &str) -> Option<String> {
        let state = self.state.lock().await;
        let State::SignedIn(engine) = &*state else { return None };
        engine.group_state(group_id).ok()?.appointments.get(appt).map(|a| a.leader.clone())
    }
}

type EngineFuture<'b, R> = std::pin::Pin<Box<dyn std::future::Future<Output = Result<R, SyncError>> + Send + 'b>>;
type EngineOp<'a, R> = Box<dyn for<'b> FnOnce(&'b mut Engine<Arc<HttpTransport>>) -> EngineFuture<'b, R> + Send + 'a>;

fn view_of(state: &GroupState) -> GroupStateView {
    let appointments = state
        .appointments
        .values()
        .map(|a| AppointmentView {
            appt: a.appt.clone(),
            leader: a.leader.clone(),
            day: a.day.to_string(),
            mode: match a.mode {
                geunal_domain::entry::Mode::Vote => Mode::Vote,
                geunal_domain::entry::Mode::Dictator => Mode::Dictator,
            },
            start: a.start,
            end: a.end,
            activity: a.activity.clone(),
            note: a.note.clone(),
            participants: a.participants().into_iter().collect(),
            tentative: a.is_tentative(),
            cancelled: a.cancelled,
            polls: a
                .polls
                .iter()
                .map(|(stage, p)| PollView {
                    stage: match stage {
                        geunal_domain::entry::Stage::Time => Stage::Time,
                        geunal_domain::entry::Stage::Activity => Stage::Activity,
                    },
                    options: p.options.clone(),
                    voters: p.votes.keys().cloned().collect(),
                    closed: p.closed,
                    result: p.result.clone(),
                })
                .collect(),
        })
        .collect();
    GroupStateView { appointments, digest: format!("{state:?}") }
}

fn build_engine(
    store_dir: &Path,
    store: &Store,
    db_key: &[u8; 32],
    session: &SyncSession,
    secrets: &dyn SecretStore,
    api: Arc<HttpTransport>,
    listener: Arc<dyn CoreListener>,
) -> Result<Engine<Arc<HttpTransport>>, CoreError> {
    let db = store_dir.join(DB_FILE);
    let secret = signing_secret(secrets)?;
    let identity = Identity::new(session.user_id.clone(), session.device_id.clone());
    let mls = MlsClient::open(&db, db_key, identity, &secret)?;
    Ok(Engine::new(store.clone(), mls, api, Arc::new(Forward(listener))))
}

fn saved_session(secrets: &dyn SecretStore) -> Option<SyncSession> {
    secrets.get(SESSION.to_owned()).and_then(|bytes| serde_json::from_slice(&bytes).ok())
}

fn database_key(secrets: &dyn SecretStore) -> Result<[u8; 32], CoreError> {
    if let Some(stored) = secrets.get(DB_KEY.to_owned()) {
        return stored.try_into().map_err(|_| CoreError::SecretStore { reason: format!("{DB_KEY} is not 32 bytes") });
    }
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    secrets.put(DB_KEY.to_owned(), key.to_vec());
    Ok(key)
}

/// The device's Ed25519 signing secret, made once and kept in the secret store (kickoff §6).
fn signing_secret(secrets: &dyn SecretStore) -> Result<Vec<u8>, CoreError> {
    if let Some(stored) = secrets.get(SIGNING_KEY.to_owned()) {
        return Ok(stored);
    }
    let (secret, _) = MlsClient::generate_signing_key()?;
    secrets.put(SIGNING_KEY.to_owned(), secret.clone());
    Ok(secret)
}

/// UUIDv7 for appointment and message ids (protocol §1).
fn uuid_v7() -> String {
    uuid::Uuid::now_v7().to_string()
}

//! The transport the core talks through: the HTTP client, or for this crate's tests the in-process fake server of
//! geunal-sync, which keeps the M1 account rules the server will have. The fake is behind the `fake-server` feature,
//! which only the tests turn on, so the apps' libraries never carry it.

use geunal_sync::api::*;
use geunal_sync::{ApiError, HttpTransport, Transport};

pub(crate) enum Api {
    Http(HttpTransport),
    #[cfg(feature = "fake-server")]
    Fake(geunal_sync::fake::FakeClient),
}

macro_rules! each {
    ($self:ident, $t:ident => $call:expr) => {
        match $self {
            Api::Http($t) => $call,
            #[cfg(feature = "fake-server")]
            Api::Fake($t) => $call,
        }
    };
}

impl Transport for Api {
    async fn nonce(&self) -> Result<String, ApiError> {
        each!(self, t => t.nonce().await)
    }
    async fn sign_in(&self, req: StubSignIn) -> Result<AuthOutcome, ApiError> {
        each!(self, t => t.sign_in(req).await)
    }
    async fn sign_in_provider(&self, provider: &str, req: ProviderSignIn) -> Result<AuthOutcome, ApiError> {
        each!(self, t => t.sign_in_provider(provider, req).await)
    }
    async fn sign_in_guest(&self, req: GuestSignIn) -> Result<AuthOutcome, ApiError> {
        each!(self, t => t.sign_in_guest(req).await)
    }
    async fn recover(&self, req: RecoverSignIn) -> Result<AuthOutcome, ApiError> {
        each!(self, t => t.recover(req).await)
    }
    async fn replace(&self, req: ReplaceRequest) -> Result<AuthOutcome, ApiError> {
        each!(self, t => t.replace(req).await)
    }
    async fn link(&self, req: LinkRequest) -> Result<Vec<String>, ApiError> {
        each!(self, t => t.link(req).await)
    }
    async fn me(&self) -> Result<MeInfo, ApiError> {
        each!(self, t => t.me().await)
    }
    async fn put_recovery(&self, req: RecoveryUpload) -> Result<(), ApiError> {
        each!(self, t => t.put_recovery(req).await)
    }
    async fn get_recovery(&self, ticket: Option<&str>) -> Result<StoredRecovery, ApiError> {
        each!(self, t => t.get_recovery(ticket).await)
    }
    async fn start_backup(&self, size: u64) -> Result<BackupUpload, ApiError> {
        each!(self, t => t.start_backup(size).await)
    }
    async fn put_object(&self, url: &str, body: Vec<u8>) -> Result<(), ApiError> {
        each!(self, t => t.put_object(url, body).await)
    }
    async fn complete_backup(&self, id: i64) -> Result<BackupInfo, ApiError> {
        each!(self, t => t.complete_backup(id).await)
    }
    async fn backups(&self, ticket: Option<&str>) -> Result<Vec<BackupInfo>, ApiError> {
        each!(self, t => t.backups(ticket).await)
    }
    async fn latest_backup(&self, ticket: Option<&str>) -> Result<Option<BackupDownload>, ApiError> {
        each!(self, t => t.latest_backup(ticket).await)
    }
    async fn get_object(&self, url: &str, progress: Progress<'_>) -> Result<Vec<u8>, ApiError> {
        each!(self, t => t.get_object(url, progress).await)
    }
    fn restore_session(&self, access_token: &str, refresh_token: &str) {
        each!(self, t => t.restore_session(access_token, refresh_token))
    }
    fn clear_session(&self) {
        each!(self, t => t.clear_session())
    }
    fn set_token_listener(&self, listener: TokenListener) {
        each!(self, t => t.set_token_listener(listener))
    }
    async fn upload_key_packages(&self, packages: Vec<Vec<u8>>) -> Result<(), ApiError> {
        each!(self, t => t.upload_key_packages(packages).await)
    }
    async fn devices(&self, user_id: &str) -> Result<Vec<DeviceInfo>, ApiError> {
        each!(self, t => t.devices(user_id).await)
    }
    async fn claim_key_package(&self, device_id: &str) -> Result<Vec<u8>, ApiError> {
        each!(self, t => t.claim_key_package(device_id).await)
    }
    async fn my_groups(&self) -> Result<Vec<GroupSummary>, ApiError> {
        each!(self, t => t.my_groups().await)
    }
    async fn create_group(&self) -> Result<GroupSummary, ApiError> {
        each!(self, t => t.create_group().await)
    }
    async fn put_group_info(&self, group_id: &str, info: GroupInfo) -> Result<(), ApiError> {
        each!(self, t => t.put_group_info(group_id, info).await)
    }
    async fn members(&self, group_id: &str) -> Result<Vec<MemberInfo>, ApiError> {
        each!(self, t => t.members(group_id).await)
    }
    async fn remove_member(&self, group_id: &str, user_id: &str) -> Result<(), ApiError> {
        each!(self, t => t.remove_member(group_id, user_id).await)
    }
    async fn post_commit(&self, group_id: &str, req: CommitRequest) -> Result<CommitAccepted, ApiError> {
        each!(self, t => t.post_commit(group_id, req).await)
    }
    async fn post_message(&self, group_id: &str, req: MessageRequest) -> Result<MessageAccepted, ApiError> {
        each!(self, t => t.post_message(group_id, req).await)
    }
    async fn log(&self, group_id: &str, after: i64, limit: u32) -> Result<LogPage, ApiError> {
        each!(self, t => t.log(group_id, after, limit).await)
    }
    async fn group_info(&self, group_id: &str) -> Result<GroupInfo, ApiError> {
        each!(self, t => t.group_info(group_id).await)
    }
    async fn welcomes(&self) -> Result<Vec<WelcomeItem>, ApiError> {
        each!(self, t => t.welcomes().await)
    }
    async fn put_state(&self, group_id: &str, kind: &str, req: PutState) -> Result<i64, ApiError> {
        each!(self, t => t.put_state(group_id, kind, req).await)
    }
    async fn state(&self, group_id: &str) -> Result<Vec<StateBlob>, ApiError> {
        each!(self, t => t.state(group_id).await)
    }
}

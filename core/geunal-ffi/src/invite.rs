//! Invites (protocol §15): making a code, reading what a person pasted or opened, the preview before joining, and the
//! request to join. The code and link rules are `geunal_domain::invite`, shared by both apps.

use std::sync::Arc;
use std::time::SystemTime;

use geunal_domain::invite;

use crate::local::{parse_stored, system_time};
use crate::{Core, CoreError};

/// A code to share, as `create_invite` made it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct InviteCode {
    pub code: String,
    /// "GN7KQ-4XZM2", as screens show it.
    pub display: String,
    pub expires_at: SystemTime,
}

/// What a pasted text or an opened link says. `name` and `color` come from the link's fragment, which anyone can write:
/// show them before joining only, as the link's claim (design m2 §3).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct InviteText {
    pub code: String,
    pub display: String,
    pub name: Option<String>,
    /// One of the twelve `color.group.*` names.
    pub color: Option<String>,
}

/// The server's preview for a code: never the members' names, as the holder is not a member yet.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct InvitePreview {
    pub group_id: String,
    pub inviter_name: String,
    pub member_count: u32,
    pub expires_at: SystemTime,
    /// The group has twenty members already.
    pub full: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum JoinStatus {
    /// Waiting for a member's device to add this one; `Change::Group` follows when it has.
    Pending,
    /// Already in the group.
    Active,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct InviteJoin {
    pub group_id: String,
    pub status: JoinStatus,
}

/// Reads a code, an invite link, or a message holding one. `None` when there is no code in it; the screen says so
/// without asking the server.
#[uniffi::export]
pub fn parse_invite(text: String) -> Option<InviteText> {
    let invite::Invite { code, name, color } = invite::parse_invite(&text)?;
    Some(InviteText { display: invite::display_code(&code), code, name, color })
}

/// The link to share: `https://{host}/i/{code}` with the group's name and colour in the fragment, which never reaches
/// the server. `host` is the invite domain the apps' Universal Links and App Links answer.
#[uniffi::export]
pub fn invite_link(host: String, code: String, name: Option<String>, color: Option<String>) -> String {
    invite::invite_link(&host, &code, name.as_deref(), color.as_deref())
}

#[uniffi::export]
impl Core {
    /// A new code for the group, valid seven days. API errors: 403 `not_member`, 409 `group_full`.
    pub async fn create_invite(self: Arc<Self>, group_id: String) -> Result<InviteCode, CoreError> {
        let this = self.clone();
        let code =
            self.run(async move {
                this.engine(Box::new(|e| Box::pin(async move { e.create_invite(&group_id).await }))).await
            })
            .await?;
        Ok(InviteCode {
            display: invite::display_code(&code.code),
            expires_at: system_time(parse_stored(&code.expires_at)?),
            code: code.code,
        })
    }

    /// What the server shows the holder of `code` (typed or pasted as is). API errors: 404 `invite_invalid` (also for
    /// a string that is no code), 429 `rate_limited`.
    pub async fn invite_preview(self: Arc<Self>, code: String) -> Result<InvitePreview, CoreError> {
        let this = self.clone();
        let preview = self
            .run(async move { this.engine(Box::new(|e| Box::pin(async move { e.invite_preview(&code).await }))).await })
            .await?;
        Ok(InvitePreview {
            expires_at: system_time(parse_stored(&preview.expires_at)?),
            group_id: preview.group_id,
            inviter_name: preview.inviter_name,
            member_count: preview.member_count,
            full: preview.full,
        })
    }

    /// Asks to join the group behind `code`. This device then takes that group's Welcome, and only a group joined this
    /// way: a member's device adds it, and `sync` (or the realtime `welcome`) brings it in. API errors: 404
    /// `invite_invalid`, 409 `group_full`, 409 `group_limit`, 429 `rate_limited`.
    pub async fn join_invite(self: Arc<Self>, code: String) -> Result<InviteJoin, CoreError> {
        let this = self.clone();
        let joined = self
            .run(async move { this.engine(Box::new(|e| Box::pin(async move { e.join_invite(&code).await }))).await })
            .await?;
        let status = if joined.status == "active" { JoinStatus::Active } else { JoinStatus::Pending };
        Ok(InviteJoin { group_id: joined.group_id, status })
    }
}

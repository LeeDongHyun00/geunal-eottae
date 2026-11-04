//! The group's shared settings (`group_meta`, protocol §5): any member may change them, and a change that lost a race
//! to another member is laid over theirs without an error (design m2 decision 9). The cover waits for media uploads.

use std::sync::Arc;

use geunal_domain::group_meta::{MetaChange, OptionsBy};
use geunal_domain::invite::{GROUP_COLORS, MAX_LINK_NAME_CHARS};

use crate::{Core, CoreError, Mode, State};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PollOptionsBy {
    Anyone,
    Leader,
}

/// What the group settings screen shows under "그룹 공통".
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupSettings {
    pub name: String,
    /// One of the twelve `color.group.*` names.
    pub color: String,
    /// Date-setting defaults a new request starts from.
    pub mode: Mode,
    /// 30-minute slots of the day, [from, to): 18–42 is 09:00–21:00.
    pub window_from: u16,
    pub window_to: u16,
    pub options_by: PollOptionsBy,
    /// "마지막으로 바꾼 사람": a user id, and when on that member's clock (RFC 3339).
    pub changed_by: String,
    pub changed_at: String,
}

/// The fields a member changed; the others stay as the group has them.
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
pub struct GroupSettingsChange {
    pub name: Option<String>,
    pub color: Option<String>,
    pub mode: Option<Mode>,
    pub window_from: Option<u16>,
    pub window_to: Option<u16>,
    pub options_by: Option<PollOptionsBy>,
}

#[uniffi::export]
impl Core {
    /// The group's shared settings as this device last read them; `None` until a member has set them.
    pub fn group_settings(&self, group_id: String) -> Result<Option<GroupSettings>, CoreError> {
        let state = self.state.blocking_lock();
        let State::SignedIn(engine) = &*state else { return Err(CoreError::NotSignedIn) };
        Ok(engine.group_meta(&group_id)?.map(|m| GroupSettings {
            name: m.name,
            color: m.color,
            mode: match m.plan.mode {
                geunal_domain::entry::Mode::Vote => Mode::Vote,
                geunal_domain::entry::Mode::Dictator => Mode::Dictator,
            },
            window_from: m.plan.window[0],
            window_to: m.plan.window[1],
            options_by: match m.plan.options_by {
                OptionsBy::Anyone => PollOptionsBy::Anyone,
                OptionsBy::Leader => PollOptionsBy::Leader,
            },
            changed_by: m.changed_by,
            changed_at: m.changed_at,
        }))
    }

    /// Changes the group's shared settings and sends the change. Invalid: an empty or over-20-character name, a
    /// colour outside the twelve, a window outside the day or with its end before its start, or both window ends
    /// not given together.
    pub async fn change_group_settings(
        self: Arc<Self>,
        group_id: String,
        change: GroupSettingsChange,
    ) -> Result<(), CoreError> {
        let invalid = |reason: &str| CoreError::Invalid { reason: reason.to_owned() };
        let name = change.name.map(|n| n.trim().to_owned());
        if name.as_ref().is_some_and(|n| n.is_empty() || n.chars().count() > MAX_LINK_NAME_CHARS) {
            return Err(invalid("group name"));
        }
        if change.color.as_deref().is_some_and(|c| !GROUP_COLORS.contains(&c)) {
            return Err(invalid("group colour"));
        }
        let window = match (change.window_from, change.window_to) {
            (None, None) => None,
            (Some(from), Some(to)) if from < to && to <= 48 => Some([from, to]),
            _ => return Err(invalid("time window")),
        };
        let meta = MetaChange {
            name,
            color: change.color,
            cover: None,
            mode: change.mode.map(Into::into),
            window,
            options_by: change.options_by.map(|o| match o {
                PollOptionsBy::Anyone => OptionsBy::Anyone,
                PollOptionsBy::Leader => OptionsBy::Leader,
            }),
        };
        if meta.is_empty() {
            return Ok(());
        }
        let this = self.clone();
        self.run(async move {
            this.engine(Box::new(|e| {
                Box::pin(async move {
                    e.change_group_meta(&group_id, &meta)?;
                    e.flush_group(&group_id).await
                })
            }))
            .await
        })
        .await
    }
}

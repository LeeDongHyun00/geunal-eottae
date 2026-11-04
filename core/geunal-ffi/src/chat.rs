//! Chat (M3), on hold by the user's decision: kept for the end-to-end tests, out of the apps' libraries. Built only
//! with the `chat` feature, which the app builds leave off.

use std::sync::Arc;

use geunal_domain::entry::Entry;

use crate::{Alert, Core, CoreError, State, uuid_v7};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatMessage {
    pub seq: i64,
    pub author_id: String,
    pub body: String,
    pub received_at: String,
}

#[uniffi::export]
impl Core {
    pub async fn send_text(self: Arc<Self>, group_id: String, body: String) -> Result<String, CoreError> {
        let id = uuid_v7();
        let entry = Entry::Text { id: id.clone(), body, reply_to: None };
        self.send(group_id, entry, Alert::All, Some("chat")).await?;
        Ok(id)
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
}

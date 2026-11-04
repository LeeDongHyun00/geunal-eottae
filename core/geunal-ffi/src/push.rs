//! The device's push token (`PUT /me/push-token`). The app hands over whatever APNs or FCM gives it; the core keeps it
//! and sends it whenever the server may not have it: right away, after every sign-in (a new device row starts without
//! one), and at the next `sync()` when the network was down.

use std::sync::Arc;

use geunal_sync::SyncError;

use crate::{Core, CoreError, State};

/// The token the app last handed over; absent once it cleared it.
const PUSH_TOKEN: &str = "push_token";
/// Set while the server may not hold [`PUSH_TOKEN`] (or its clearing) yet.
pub(crate) const PUSH_TOKEN_DUE: &str = "push_token_due";

#[uniffi::export]
impl Core {
    /// Registers the token APNs or FCM gave this app, or `None` to stop pushes to this device (the person turned
    /// notifications off). Call it at every launch and whenever the OS hands out a new token. Before a sign-in, and
    /// while offline, the token waits for the next sign-in or `sync()`; neither is an error here.
    pub async fn set_push_token(self: Arc<Self>, token: Option<String>) -> Result<(), CoreError> {
        self.store.write(|db| {
            match &token {
                Some(token) => db.set_setting(PUSH_TOKEN, token)?,
                None => db.delete_setting(PUSH_TOKEN)?,
            }
            Ok::<_, CoreError>(db.set_setting(PUSH_TOKEN_DUE, "1")?)
        })?;
        let this = self.clone();
        self.run(async move { this.send_due_push_token().await }).await
    }
}

impl Core {
    /// Sends the token the server may not have (`PUSH_TOKEN_DUE`). A network error waits for the next `sync()`.
    ///
    /// Reading the token, sending it and clearing the flag all happen under the engine's lock, so two uploads never
    /// overlap and an older token can never land after a newer one. A token handed over while one is on its way keeps
    /// the flag set and goes next.
    pub(crate) async fn send_due_push_token(&self) -> Result<(), CoreError> {
        if !matches!(*self.state.lock().await, State::SignedIn(_)) {
            return Ok(());
        }
        let store = self.store.clone();
        let sent = self
            .engine(Box::new(move |e| {
                Box::pin(async move {
                    let due = store.read(|db| {
                        Ok::<_, SyncError>(match db.setting(PUSH_TOKEN_DUE)? {
                            Some(_) => Some(db.setting(PUSH_TOKEN)?),
                            None => None,
                        })
                    })?;
                    let Some(token) = due else { return Ok(()) };
                    e.set_push_token(token.clone()).await?;
                    store.write(|db| {
                        if db.setting(PUSH_TOKEN)? == token {
                            db.delete_setting(PUSH_TOKEN_DUE)?;
                        }
                        Ok::<_, SyncError>(())
                    })
                })
            }))
            .await;
        match sent {
            Ok(()) | Err(CoreError::Network { .. }) => Ok(()),
            Err(e) => Err(e),
        }
    }
}

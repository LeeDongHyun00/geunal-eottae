//! The device's push token (`PUT /me/push-token`). The app hands over whatever APNs or FCM gives it; the core keeps it
//! and sends it whenever the server may not have it: right away, after every sign-in (a new device row starts without
//! one), and at the next `sync()` when the network was down.

use std::sync::Arc;

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
    pub(crate) async fn send_due_push_token(&self) -> Result<(), CoreError> {
        let token = self.store.read(|db| {
            Ok::<_, CoreError>(match db.setting(PUSH_TOKEN_DUE)? {
                Some(_) => Some(db.setting(PUSH_TOKEN)?),
                None => None,
            })
        })?;
        let Some(token) = token else { return Ok(()) };
        if !matches!(*self.state.lock().await, State::SignedIn(_)) {
            return Ok(());
        }
        let sent = token.clone();
        match self.engine(Box::new(|e| Box::pin(async move { e.set_push_token(sent).await }))).await {
            Ok(()) => self.store.write(|db| {
                // A newer token handed over while this one was on its way is still due.
                if db.setting(PUSH_TOKEN)? == token {
                    db.delete_setting(PUSH_TOKEN_DUE)?;
                }
                Ok::<_, CoreError>(())
            }),
            Err(CoreError::Network { .. }) => Ok(()),
            Err(e) => Err(e),
        }
    }
}

//! The gateway's Phoenix channel (protocol §7): one socket, one topic `device:{device_id}`, heartbeats every 30
//! seconds, and events that only say what to fetch. Nothing is written over it.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::{Connector, connect_async_tls_with_config};

use crate::SyncError;

pub const HEARTBEAT: Duration = Duration::from_secs(30);

/// A server → device event (protocol §7).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Event {
    /// A new log item: fetch from the cursor, or process `c` directly when it is the next seq.
    Entry {
        g: String,
        s: i64,
        kind: String,
        #[serde(default, with = "crate::api::b64::option")]
        c: Option<Vec<u8>>,
    },
    State {
        g: String,
        kind: String,
        owner: String,
        epoch: u64,
    },
    Membership {
        g: String,
    },
    Welcome {},
    Revoke {},
    /// The channel was joined; useful for tests that wait for the connection.
    Joined,
    /// The socket closed; the caller reconnects with backoff and catches up from the cursors.
    Closed(String),
}

pub struct Realtime {
    events: mpsc::Receiver<Event>,
    task: tokio::task::JoinHandle<()>,
}

impl Realtime {
    /// Connects to `ws(s)://host/socket/websocket?vsn=2.0.0&token=...` and joins the device topic. Must be called from
    /// within a tokio runtime; the socket is driven by a spawned task.
    pub async fn connect(gateway_base: &str, token: &str, device_id: &str) -> Result<Self, SyncError> {
        // The gateway is configured by its HTTP origin (the local stack publishes http://127.0.0.1:4000).
        let base = gateway_base.trim_end_matches('/');
        let base = match base.strip_prefix("https://") {
            Some(rest) => format!("wss://{rest}"),
            None => format!("ws://{}", base.strip_prefix("http://").unwrap_or(base).trim_start_matches("ws://")),
        };
        let url = format!("{base}/socket/websocket?vsn=2.0.0&token={token}");
        let request = url.into_client_request().map_err(|e| SyncError::Realtime(e.to_string()))?;
        let connector = Connector::Rustls(crate::tls::client_config());
        let (socket, _) = connect_async_tls_with_config(request, None, false, Some(connector))
            .await
            .map_err(|e| SyncError::Realtime(e.to_string()))?;
        let (tx, events) = mpsc::channel(256);
        let topic = format!("device:{device_id}");
        let task = tokio::spawn(run(socket, topic, tx));
        Ok(Self { events, task })
    }

    /// The next event, or `None` once the socket is gone.
    pub async fn next(&mut self) -> Option<Event> {
        self.events.recv().await
    }

    pub async fn next_within(&mut self, timeout: Duration) -> Option<Event> {
        tokio::time::timeout(timeout, self.events.recv()).await.ok().flatten()
    }
}

impl Drop for Realtime {
    fn drop(&mut self) {
        self.task.abort();
    }
}

type Socket = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

/// Phoenix V2 JSON frames are `[join_ref, ref, topic, event, payload]`.
async fn run(mut socket: Socket, topic: String, tx: mpsc::Sender<Event>) {
    let join = json!(["1", "1", topic, "phx_join", {}]).to_string();
    if socket.send(Message::Text(join.into())).await.is_err() {
        let _ = tx.send(Event::Closed("join failed".into())).await;
        return;
    }
    let mut heartbeat = tokio::time::interval(HEARTBEAT);
    heartbeat.tick().await;
    let mut next_ref: u64 = 2;
    loop {
        tokio::select! {
            _ = heartbeat.tick() => {
                let frame = json!([Value::Null, next_ref.to_string(), "phoenix", "heartbeat", {}]).to_string();
                next_ref += 1;
                if socket.send(Message::Text(frame.into())).await.is_err() {
                    let _ = tx.send(Event::Closed("heartbeat failed".into())).await;
                    return;
                }
            }
            frame = socket.next() => {
                match frame {
                    Some(Ok(Message::Text(text))) => {
                        for event in parse_frame(&text, &topic) {
                            if tx.send(event).await.is_err() {
                                return;
                            }
                        }
                    }
                    Some(Ok(Message::Ping(data))) => {
                        let _ = socket.send(Message::Pong(data)).await;
                    }
                    Some(Ok(Message::Close(frame))) => {
                        let reason = frame.map(|f| f.reason.to_string()).unwrap_or_default();
                        let _ = tx.send(Event::Closed(reason)).await;
                        return;
                    }
                    Some(Ok(_)) => {}
                    Some(Err(e)) => {
                        let _ = tx.send(Event::Closed(e.to_string())).await;
                        return;
                    }
                    None => {
                        let _ = tx.send(Event::Closed("socket ended".into())).await;
                        return;
                    }
                }
            }
        }
    }
}

/// Turns one frame into events. Replies to the join become `Joined`; heartbeat replies and unknown events are
/// dropped. The payload of a gateway event carries the same JSON the API published, so `t` names the event.
pub fn parse_frame(text: &str, topic: &str) -> Vec<Event> {
    let Ok(Value::Array(frame)) = serde_json::from_str::<Value>(text) else { return Vec::new() };
    let (Some(Value::String(frame_topic)), Some(Value::String(event)), Some(payload)) =
        (frame.get(2), frame.get(3), frame.get(4))
    else {
        return Vec::new();
    };
    if frame_topic != topic {
        return Vec::new();
    }
    match event.as_str() {
        "phx_reply" => {
            let is_join =
                frame.first().and_then(Value::as_str) == Some("1") && frame.get(1).and_then(Value::as_str) == Some("1");
            let ok = payload.get("status").and_then(Value::as_str) == Some("ok");
            if is_join {
                return vec![if ok { Event::Joined } else { Event::Closed("join refused".into()) }];
            }
            Vec::new()
        }
        "phx_error" | "phx_close" => vec![Event::Closed(event.clone())],
        name => {
            let mut object = payload.clone();
            if let Value::Object(map) = &mut object {
                map.insert("t".into(), Value::String(name.to_owned()));
            }
            serde_json::from_value::<Event>(object).ok().into_iter().collect()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_gateway_events_and_the_join_reply() {
        let topic = "device:d1";
        assert_eq!(
            parse_frame(r#"["1","1","device:d1","phx_reply",{"status":"ok","response":{}}]"#, topic),
            vec![Event::Joined]
        );
        assert_eq!(
            parse_frame(r#"[null,"7","device:d1","entry",{"g":"g1","s":4,"kind":"app","c":"AQID"}]"#, topic),
            vec![Event::Entry { g: "g1".into(), s: 4, kind: "app".into(), c: Some(vec![1, 2, 3]) }]
        );
        assert_eq!(
            parse_frame(r#"[null,"8","device:d1","entry",{"g":"g1","s":5,"kind":"commit"}]"#, topic),
            vec![Event::Entry { g: "g1".into(), s: 5, kind: "commit".into(), c: None }]
        );
        assert_eq!(
            parse_frame(r#"[null,"9","device:d1","membership",{"g":"g1"}]"#, topic),
            vec![Event::Membership { g: "g1".into() }]
        );
        assert_eq!(parse_frame(r#"[null,"9","device:d1","welcome",{}]"#, topic), vec![Event::Welcome {}]);
        assert!(parse_frame(r#"[null,"2","phoenix","phx_reply",{"status":"ok","response":{}}]"#, topic).is_empty());
        assert!(parse_frame(r#"[null,"3","device:other","entry",{"g":"g","s":1,"kind":"app"}]"#, topic).is_empty());
        assert!(parse_frame("not json", topic).is_empty());
    }
}

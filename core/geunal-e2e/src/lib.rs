//! Shared pieces for the headless end-to-end tests in `tests/`, which run against the local stack that `make e2e`
//! starts (infra/docker-compose.yml): where the services listen, a minimal HTTP client, and in-memory stand-ins for
//! the app's callbacks.
//!
//! The client speaks HTTP over `std::net` because the stack serves plain HTTP on loopback and the core has not chosen
//! its HTTP stack yet; the tests should not choose one for it.

#![forbid(unsafe_code)]

use std::collections::HashMap;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::sync::Mutex;
use std::time::Duration;

use geunal_ffi::{Change, CoreListener, SecretStore, SecretStoreError};

/// The API's base URL: `GEUNAL_API_URL`, or the port infra/docker-compose.yml publishes.
pub fn api_url() -> String {
    std::env::var("GEUNAL_API_URL").unwrap_or_else(|_| "http://127.0.0.1:8080".to_owned())
}

/// The gateway's base URL: `GEUNAL_GATEWAY_URL`, or the port infra/docker-compose.yml publishes.
pub fn gateway_url() -> String {
    std::env::var("GEUNAL_GATEWAY_URL").unwrap_or_else(|_| "http://127.0.0.1:4000".to_owned())
}

/// A response's status code and body.
#[derive(Debug)]
pub struct Response {
    pub status: u16,
    pub body: String,
}

/// `GET` on `base` + `path`. It speaks HTTP/1.0 so the server sends the body unchunked and closes the connection
/// after it.
pub fn get(base: &str, path: &str) -> io::Result<Response> {
    let mut stream = connect(base)?;
    write!(stream, "GET {path} HTTP/1.0\r\nHost: {}\r\n\r\n", authority(base))?;
    let mut raw = String::new();
    stream.read_to_string(&mut raw)?;
    let (head, body) = raw.split_once("\r\n\r\n").unwrap_or((&raw, ""));
    Ok(Response { status: status_code(head)?, body: body.to_owned() })
}

/// Asks to upgrade `base` + `path` to a WebSocket and returns the answer's status, 101 if the server accepts. Only the
/// status line is read, since an accepted connection stays open.
pub fn websocket_status(base: &str, path: &str) -> io::Result<u16> {
    let mut stream = connect(base)?;
    write!(
        stream,
        "GET {path} HTTP/1.1\r\nHost: {}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\
         Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n",
        authority(base)
    )?;
    let mut status_line = String::new();
    BufReader::new(stream).read_line(&mut status_line)?;
    status_code(&status_line)
}

fn authority(base: &str) -> &str {
    base.strip_prefix("http://").expect("the local stack serves plain http:// URLs").trim_end_matches('/')
}

fn connect(base: &str) -> io::Result<TcpStream> {
    let stream = TcpStream::connect(authority(base))?;
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    Ok(stream)
}

/// The code in a status line such as `HTTP/1.1 200 OK`.
fn status_code(head: &str) -> io::Result<u16> {
    head.split_whitespace()
        .nth(1)
        .and_then(|code| code.parse().ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("no HTTP status line in {head:?}")))
}

/// Keeps the core's secrets in memory, where the app would use the keychain or the keystore.
#[derive(Default)]
pub struct MemorySecrets(Mutex<HashMap<String, Vec<u8>>>);

impl SecretStore for MemorySecrets {
    fn get(&self, name: String) -> Result<Option<Vec<u8>>, SecretStoreError> {
        Ok(self.0.lock().unwrap().get(&name).cloned())
    }
    fn put(&self, name: String, value: Vec<u8>) -> Result<(), SecretStoreError> {
        self.0.lock().unwrap().insert(name, value);
        Ok(())
    }
    fn delete(&self, name: String) -> Result<(), SecretStoreError> {
        self.0.lock().unwrap().remove(&name);
        Ok(())
    }
}

/// Ignores change notifications.
pub struct Quiet;

impl CoreListener for Quiet {
    fn on_change(&self, _change: Change) {}
}

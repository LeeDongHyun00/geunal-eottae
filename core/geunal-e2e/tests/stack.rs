//! The local stack answers the way the apps and the core expect at M0. `make e2e` starts the stack and runs these.

use std::sync::Arc;

use geunal_e2e::{MemorySecrets, Quiet, api_url, gateway_url, get, websocket_status};
use geunal_ffi::Core;

#[test]
fn api_reports_up_with_its_database_and_redis() {
    let response = get(&api_url(), "/actuator/health").unwrap();

    assert_eq!(response.status, 200, "{response:?}");
    assert!(response.body.contains(r#""status":"UP""#), "{response:?}");
}

#[test]
fn gateway_reports_healthy_with_redis() {
    let response = get(&gateway_url(), "/healthz").unwrap();

    assert_eq!((response.status, response.body.as_str()), (200, "ok"));
}

#[test]
fn gateway_refuses_a_socket_whose_token_it_cannot_verify() {
    let status = websocket_status(&gateway_url(), "/socket/websocket?vsn=2.0.0&token=unverified").unwrap();

    assert_eq!(status, 403);
}

/// `sync` has nothing to do yet; once the sync engine lands, this runs it against the stack's API.
#[test]
fn core_opens_its_store_and_syncs_with_the_stack() {
    let dir = tempfile::tempdir().unwrap();
    let core = Core::open(
        dir.path().to_str().unwrap().to_owned(),
        api_url(),
        Arc::new(Quiet),
        Arc::new(MemorySecrets::default()),
    )
    .unwrap();

    pollster::block_on(core.sync()).unwrap();
}

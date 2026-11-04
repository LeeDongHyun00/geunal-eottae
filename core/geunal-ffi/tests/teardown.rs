//! Letting go of the core while its background work still runs. The panic hook is process-wide, so this has its own
//! test binary.

mod phones;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

use geunal_ffi::Core;
use geunal_sync::fake::FakeServer;
use phones::{new_account, phone};

static PANICKED: AtomicBool = AtomicBool::new(false);

#[test]
fn releasing_the_core_during_the_background_recovery_upload_shuts_it_down_quietly() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        PANICKED.store(true, Ordering::SeqCst);
        previous(info);
    }));
    let server = FakeServer::new();
    let a = phone(&server);
    let session = pollster::block_on(a.core.clone().sign_in_guest(new_account("게스트"))).unwrap();
    let core: Weak<Core> = Arc::downgrade(&a.core);
    drop(a);

    // The upload task holds the last reference; the core goes when it ends, on one of the core's own workers.
    let deadline = Instant::now() + Duration::from_secs(10);
    while core.upgrade().is_some() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(core.upgrade().is_none(), "the core was released");
    std::thread::sleep(Duration::from_millis(200));
    assert!(server.recovery_of(&session.user_id).is_some(), "the upload finished first");
    assert!(!PANICKED.load(Ordering::SeqCst), "no panic while the core shut down");
}

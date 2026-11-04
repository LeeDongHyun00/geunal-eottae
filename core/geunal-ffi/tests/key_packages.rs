//! Members can keep adding this device: `sync()` puts up more key packages when few are left on the server.

mod phones;

use chrono::{TimeZone, Utc};
use geunal_sync::fake::FakeServer;
use phones::{kakao, phone, user_id};

#[test]
fn tops_up_key_packages_when_few_are_left_at_most_once_an_hour() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        a.core.set_clock(Some(Utc.with_ymd_and_hms(2026, 11, 2, 0, 0, 0).unwrap()));
        let user = user_id(&kakao(&a.core, "a", "하린").await.unwrap());
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.key_packages_of(&user), 10);

        // Members' adds used most of them; within the hour the device does not ask again.
        server.use_key_packages(&user, 2);
        a.core.set_clock(Some(Utc.with_ymd_and_hms(2026, 11, 2, 0, 30, 0).unwrap()));
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.key_packages_of(&user), 2);

        a.core.set_clock(Some(Utc.with_ymd_and_hms(2026, 11, 2, 1, 1, 0).unwrap()));
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.key_packages_of(&user), 10);
    });
}

//! The push token reaches the server whenever it may not have it: right away, after a sign-in, at the next sync.

mod phones;

use geunal_sync::fake::FakeServer;
use phones::{kakao, phone, user_id};

#[test]
fn a_token_handed_over_before_sign_in_goes_up_with_the_sign_in() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        a.core.clone().set_push_token(Some("apns-1".into())).await.unwrap();
        let user = user_id(&kakao(&a.core, "a", "하린").await.unwrap());
        assert_eq!(server.push_token_of(&user).as_deref(), Some("apns-1"));
    });
}

#[test]
fn a_new_token_replaces_the_old_and_none_clears_it() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "a", "하린").await.unwrap());
        a.core.clone().set_push_token(Some("fcm-1".into())).await.unwrap();
        a.core.clone().set_push_token(Some("fcm-2".into())).await.unwrap();
        assert_eq!(server.push_token_of(&user).as_deref(), Some("fcm-2"));
        a.core.clone().set_push_token(None).await.unwrap();
        assert_eq!(server.push_token_of(&user), None);
    });
}

#[test]
fn a_token_that_could_not_go_up_goes_at_the_next_sync() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        let user = user_id(&kakao(&a.core, "a", "하린").await.unwrap());
        server.fail_next("set_push_token");
        a.core.clone().set_push_token(Some("fcm-1".into())).await.unwrap();
        assert_eq!(server.push_token_of(&user), None);
        a.core.clone().sync().await.unwrap();
        assert_eq!(server.push_token_of(&user).as_deref(), Some("fcm-1"));
    });
}

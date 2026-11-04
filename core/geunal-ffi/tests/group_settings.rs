//! The group's shared settings through `Core`: the name a group is made with, a member's change, and what a member
//! who joins later reads.

mod phones;

use geunal_ffi::group_settings::{GroupSettingsChange, PollOptionsBy};
use geunal_ffi::*;
use geunal_sync::fake::FakeServer;
use phones::{kakao, phone, user_id};

#[test]
fn a_member_who_joins_reads_the_settings_another_member_changed() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        let owner = user_id(&kakao(&a.core, "a", "하린").await.unwrap());
        kakao(&b.core, "b", "지영").await.unwrap();
        let group = a.core.clone().create_group(Some("주말모임".into())).await.unwrap();
        let change = GroupSettingsChange {
            color: Some("mint".into()),
            mode: Some(Mode::Dictator),
            window_from: Some(20),
            window_to: Some(44),
            options_by: Some(PollOptionsBy::Leader),
            ..Default::default()
        };
        a.core.clone().change_group_settings(group.clone(), change).await.unwrap();

        let code = a.core.clone().create_invite(group.clone()).await.unwrap().code;
        b.core.clone().join_invite(code).await.unwrap();
        a.core.clone().sync().await.unwrap();
        b.core.clone().sync().await.unwrap();

        let seen = b.core.group_settings(group.clone()).unwrap().expect("read after joining");
        assert_eq!((seen.name.as_str(), seen.color.as_str()), ("주말모임", "mint"));
        assert_eq!((seen.mode, seen.window_from, seen.window_to), (Mode::Dictator, 20, 44));
        assert_eq!((seen.options_by, seen.changed_by.as_str()), (PollOptionsBy::Leader, owner.as_str()));
        assert_eq!(b.core.groups().unwrap()[0].name.as_deref(), Some("주말모임"));
    });
}

#[test]
fn refuses_settings_the_screen_should_not_allow() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let a = phone(&server);
        kakao(&a.core, "a", "하린").await.unwrap();
        let group = a.core.clone().create_group(Some("모임".into())).await.unwrap();
        for change in [
            GroupSettingsChange { name: Some("  ".into()), ..Default::default() },
            GroupSettingsChange { color: Some("neon".into()), ..Default::default() },
            GroupSettingsChange { window_from: Some(30), window_to: Some(20), ..Default::default() },
            GroupSettingsChange { window_from: Some(30), ..Default::default() },
        ] {
            assert!(matches!(
                a.core.clone().change_group_settings(group.clone(), change).await,
                Err(CoreError::Invalid { .. })
            ));
        }
    });
}

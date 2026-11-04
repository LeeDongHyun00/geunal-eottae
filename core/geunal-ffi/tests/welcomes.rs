//! The apps join a group from a Welcome only after the person asked to join it with an invite code (protocol §15). Its
//! own test binary, so the process switch the end-to-end tests turn on for the stub invite stays off here.

mod phones;

use geunal_ffi::invite::{JoinStatus, invite_link, parse_invite};
use geunal_sync::fake::FakeServer;
use phones::{kakao, phone, user_id};

#[test]
fn joins_no_group_from_a_welcome_nobody_asked_for() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        kakao(&a.core, "owner", "주인").await.unwrap();
        let invited = user_id(&kakao(&b.core, "invited", "초대받은 사람").await.unwrap());

        // B is made pending without asking and A's device adds it; the server holds a Welcome for B.
        let group = a.core.clone().create_group(Some("모임".into())).await.unwrap();
        server.invite(&group, &invited);
        a.core.clone().add_members(group.clone(), vec![invited]).await.unwrap();

        // B syncs and stays out: nothing joined, nothing read from that group.
        b.core.clone().sync().await.unwrap();
        assert!(b.core.groups().unwrap().is_empty());
        assert_eq!(a.core.groups().unwrap().len(), 1);
    });
}

#[test]
fn joins_a_group_through_a_shared_link() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        kakao(&a.core, "owner", "하린").await.unwrap();
        kakao(&b.core, "friend", "친구").await.unwrap();
        let group = a.core.clone().create_group(Some("주말모임".into())).await.unwrap();

        // A shares a link; B opens it, sees the preview, and asks to join.
        let code = a.core.clone().create_invite(group.clone()).await.unwrap();
        let link = invite_link(
            "geunal.app".into(),
            code.code.clone(),
            Some("주말모임".into()),
            Some("mint".into()),
            Some("하린".into()),
        );
        let opened = parse_invite(link).unwrap();
        assert_eq!((opened.display, opened.name.as_deref()), (code.display.clone(), Some("주말모임")));
        assert_eq!(opened.inviter.as_deref(), Some("하린"));
        let preview = b.core.clone().invite_preview(opened.code.clone()).await.unwrap();
        assert_eq!((preview.inviter_name.as_str(), preview.member_count), ("하린", 1));
        let joined = b.core.clone().join_invite(opened.code).await.unwrap();
        assert_eq!((joined.group_id.as_str(), joined.status), (group.as_str(), JoinStatus::Pending));

        // A's device adds the pending member at its next sync; B's next sync takes the Welcome.
        a.core.clone().sync().await.unwrap();
        b.core.clone().sync().await.unwrap();
        let groups = b.core.groups().unwrap();
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].group_id, group);
        assert_eq!(b.core.members(group.clone()).unwrap(), a.core.members(group).unwrap());
    });
}

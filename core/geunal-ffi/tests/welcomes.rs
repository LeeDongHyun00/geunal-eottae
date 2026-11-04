//! Until members accept invitations (M2), the apps join no group from a Welcome. Its own test binary, so the process
//! switch the end-to-end tests turn on for the group flow stays off here.

mod phones;

use geunal_sync::fake::FakeServer;
use phones::{kakao, phone, user_id};

#[test]
fn joins_no_group_from_a_welcome_before_invitations() {
    pollster::block_on(async {
        let server = FakeServer::new();
        let (a, b) = (phone(&server), phone(&server));
        kakao(&a.core, "owner", "주인").await.unwrap();
        let invited = user_id(&kakao(&b.core, "invited", "초대받은 사람").await.unwrap());

        // Someone puts B's device into a group; the server holds a Welcome for it.
        let group = a.core.clone().create_group(Some("모임".into())).await.unwrap();
        a.core.clone().add_members(group.clone(), vec![invited]).await.unwrap();

        // B syncs and stays out: nothing joined, nothing read from that group.
        b.core.clone().sync().await.unwrap();
        assert!(b.core.groups().unwrap().is_empty());
        assert_eq!(a.core.groups().unwrap().len(), 1);
    });
}

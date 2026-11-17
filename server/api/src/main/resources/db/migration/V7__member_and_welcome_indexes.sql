-- group_members' primary key leads with group_id, so every read that starts from an account (GET /me/groups, the
-- groups a replaced device's membership signal goes to, whether a caller may claim someone's key packages) scanned
-- the whole table. status and group_id follow user_id because those reads filter on status and need only the group.
CREATE INDEX ON group_members (user_id, status, group_id);

-- GET /me/welcomes takes a device's Welcomes by device_id on every sync, and deleting a device cascades by it.
CREATE INDEX ON welcomes (device_id);

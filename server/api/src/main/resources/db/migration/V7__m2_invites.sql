-- Deleting a group cascades to its invites, and the join path reads a group's invites by code only; this index serves
-- the cascade.
CREATE INDEX group_invites_group ON group_invites (group_id);

-- The hourly expiry of pending invitations looks only at pending rows, by when they were invited.
CREATE INDEX group_members_pending ON group_members (joined_at) WHERE status = 'pending';

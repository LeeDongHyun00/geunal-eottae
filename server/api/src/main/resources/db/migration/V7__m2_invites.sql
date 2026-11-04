-- Deleting a group cascades to its invites, and the join path reads a group's invites by code only; this index serves
-- the cascade.
CREATE INDEX group_invites_group ON group_invites (group_id);

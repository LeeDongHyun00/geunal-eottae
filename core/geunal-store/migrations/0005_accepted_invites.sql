-- core/geunal-store/migrations/0005_accepted_invites.sql — the groups this person asked to join with an invite code
-- (protocol §15). A Welcome is taken only for one of these; the row goes once the group is joined, or after 30 days.
CREATE TABLE accepted_invites (
  group_id    TEXT PRIMARY KEY,
  accepted_at TEXT NOT NULL
);

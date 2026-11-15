-- core/geunal-store/migrations/0005_rejoin.sql — a group this device still has to come back to by external commit
-- (protocol §6). The MLS state is dropped before the external commit is built, so until the server takes it nothing
-- else says that the group is not simply one this device left: the flag keeps its queued writes and makes the next
-- sync try again.
ALTER TABLE groups ADD COLUMN rejoin INTEGER NOT NULL DEFAULT 0;

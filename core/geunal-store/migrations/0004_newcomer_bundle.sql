-- core/geunal-store/migrations/0004_newcomer_bundle.sql — the newcomer bundle a group still owes this device after a
-- Welcome (protocol §5): who added it, the epoch it joined in, and the seq of the Add commit (NULL when the Welcome named none). The bundle is taken
-- once, from that member and that epoch, and only for items up to that seq; the columns are cleared once it is.
ALTER TABLE groups ADD COLUMN bundle_from TEXT;
ALTER TABLE groups ADD COLUMN bundle_epoch INTEGER;
ALTER TABLE groups ADD COLUMN bundle_through INTEGER;

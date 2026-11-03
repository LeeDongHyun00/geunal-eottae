-- The years whose public holidays the server knows whole (protocol §14). A year's rows are replaced only from a
-- complete fetch of it, and GET /holidays answers only for these years, so a device never swaps a full year of its own
-- for part of one.
CREATE TABLE holiday_years (
  year       integer PRIMARY KEY,
  synced_at  timestamptz NOT NULL DEFAULT now()
);

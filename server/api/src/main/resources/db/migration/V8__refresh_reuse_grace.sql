-- The session a refresh rotated this one from. A spent refresh token presented again shortly after its rotation is
-- the device retrying a refresh whose answer it lost, as long as the token minted from it has not been used in turn;
-- the retry is answered with that token again, and its session is found by this column (api-server.md §4). Not a foreign key: the daily cleanup
-- deletes expired sessions in bulk, and a dangling id only means the parent is long past its grace.
ALTER TABLE sessions ADD COLUMN rotated_from uuid;
CREATE INDEX ON sessions (rotated_from) WHERE rotated_from IS NOT NULL;

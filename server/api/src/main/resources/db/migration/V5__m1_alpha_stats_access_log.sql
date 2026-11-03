-- M1 gate counts and the access log.

-- An opted-in tester's day gains the two counts the crash-free sessions rate needs (protocol §14). The table has had
-- no writer before this migration, so the defaults only satisfy NOT NULL while the columns are added.
ALTER TABLE alpha_stats
  ADD COLUMN sessions   integer NOT NULL DEFAULT 0 CHECK (sessions >= 0),
  ADD COLUMN crashes    integer NOT NULL DEFAULT 0 CHECK (crashes >= 0),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE alpha_stats ALTER COLUMN sessions DROP DEFAULT, ALTER COLUMN crashes DROP DEFAULT;

-- Sign-ins and token refreshes on their way to the access-logs bucket (api-server.md §10, infra.md §9), written in
-- the transaction that issues the tokens and moved out within minutes. No foreign keys: the law keeps an access log
-- for three months whatever happens to the account, so a deletion leaves these rows alone.
CREATE TABLE access_log_outbox (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at         timestamptz NOT NULL DEFAULT now(),
  event      text NOT NULL CHECK (event IN ('sign_in', 'refresh')),
  user_id    uuid NOT NULL,
  device_id  uuid NOT NULL,
  ip         text NOT NULL
);

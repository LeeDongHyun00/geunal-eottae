-- A Welcome now records the seq of the Add commit that brought the device in, so the new member reads the log from
-- there instead of from seq 0 and skipping every earlier epoch. No deployment holds Welcome rows yet, so the default
-- only satisfies NOT NULL for the migration and is dropped at once.
ALTER TABLE welcomes ADD COLUMN seq bigint NOT NULL DEFAULT 0;
ALTER TABLE welcomes ALTER COLUMN seq DROP DEFAULT;

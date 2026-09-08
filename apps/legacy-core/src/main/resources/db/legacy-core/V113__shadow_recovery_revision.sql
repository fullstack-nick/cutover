-- This revision fences explicit recovery actions. Relay attempts remain transport evidence.
ALTER TABLE shadow_observation_outbox ADD COLUMN recovery_version bigint NOT NULL DEFAULT 0 CHECK(recovery_version>=0);

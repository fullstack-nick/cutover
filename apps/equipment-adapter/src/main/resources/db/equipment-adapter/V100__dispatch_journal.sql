CREATE TABLE zone_routes (
  site_id text NOT NULL, zone_id text NOT NULL, owner text NOT NULL,
  epoch bigint NOT NULL DEFAULT 0, state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','DRAINING','RECONCILIATION_REQUIRED')),
  version bigint NOT NULL DEFAULT 0, PRIMARY KEY(site_id,zone_id)
);
INSERT INTO zone_routes(site_id,zone_id,owner)
SELECT site,zone,owner FROM (VALUES ('site-a'),('site-b')) s(site)
CROSS JOIN (VALUES ('ambient','legacy-core'),('chilled','legacy-core'),('returns','returns-service')) r(zone,owner);
CREATE TABLE equipment_observation (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  pinned_world_id uuid, pinned_journal_generation uuid,
  observation jsonb, observed_at timestamptz, world_mismatch boolean NOT NULL DEFAULT false
);
INSERT INTO equipment_observation DEFAULT VALUES;
CREATE TABLE movement_allocations (
  allocation_id uuid PRIMARY KEY, movement_id uuid NOT NULL, site_id text NOT NULL, zone_id text NOT NULL,
  source text NOT NULL, movement jsonb NOT NULL, payload_hash text NOT NULL,
  owner text, epoch bigint, state text NOT NULL CHECK(state IN ('PENDING','ASSIGNED','COMPLETED','CANCELLED')),
  version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
  UNIQUE(site_id,movement_id), FOREIGN KEY(site_id,zone_id) REFERENCES zone_routes(site_id,zone_id)
);
CREATE TABLE command_journal (
  command_id uuid PRIMARY KEY, allocation_id uuid NOT NULL UNIQUE REFERENCES movement_allocations(allocation_id),
  site_id text NOT NULL, movement_id uuid NOT NULL, owner text NOT NULL, epoch bigint NOT NULL,
  payload jsonb NOT NULL, payload_hash text NOT NULL,
  state text NOT NULL CHECK(state IN ('RECORDED','SEND_PENDING','ACCEPTED_BY_SIMULATOR','EXECUTING','COMPLETED','REJECTED_BEFORE_EXECUTION','OUTCOME_UNKNOWN','QUARANTINED')),
  version bigint NOT NULL DEFAULT 1, attempts integer NOT NULL DEFAULT 0, failure_attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_until timestamptz,
  last_error text, evidence jsonb, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
  UNIQUE(site_id,movement_id)
);
CREATE INDEX command_recovery ON command_journal(next_attempt_at) WHERE state NOT IN ('COMPLETED','QUARANTINED','REJECTED_BEFORE_EXECUTION');

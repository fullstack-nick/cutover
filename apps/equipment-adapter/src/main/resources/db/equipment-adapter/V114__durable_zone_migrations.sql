ALTER TABLE movement_allocations ADD COLUMN assigned_at timestamptz;
UPDATE movement_allocations SET assigned_at=created_at WHERE owner IS NOT NULL;
CREATE INDEX cancellation_movement_lookup ON cancellation_certificates
  USING gin ((certificate->'movements') jsonb_path_ops);

CREATE TABLE migration_sessions (
  session_id uuid PRIMARY KEY,
  site_id text NOT NULL,
  zone_id text NOT NULL CHECK(zone_id IN ('ambient','chilled')),
  source_owner text NOT NULL CHECK(source_owner IN ('legacy-core','execution-service')),
  target_owner text NOT NULL CHECK(target_owner IN ('legacy-core','execution-service')),
  source_epoch bigint NOT NULL CHECK(source_epoch>=0),
  target_epoch bigint,
  phase text NOT NULL CHECK(phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','COMPLETED','REVERSING','REVERSED','CANCELLED')),
  version bigint NOT NULL DEFAULT 1,
  actor text NOT NULL,
  reason text NOT NULL,
  reverses_session_id uuid REFERENCES migration_sessions(session_id),
  inventory jsonb,
  inventory_hash text,
  inventory_count integer NOT NULL DEFAULT 0 CHECK(inventory_count BETWEEN 0 AND 20000),
  verified_count integer NOT NULL DEFAULT 0 CHECK(verified_count BETWEEN 0 AND inventory_count),
  checkpoint jsonb,
  checkpoint_hash text,
  blockers jsonb NOT NULL DEFAULT '{"count":0,"items":[]}',
  observation jsonb,
  attempts integer NOT NULL DEFAULT 0,
  transport_paused boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_id uuid,
  lease_until timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  switched_at timestamptz,
  finished_at timestamptz,
  CHECK(source_owner<>target_owner),
  FOREIGN KEY(site_id,zone_id) REFERENCES zone_routes(site_id,zone_id)
);
CREATE UNIQUE INDEX one_active_migration_per_zone ON migration_sessions(site_id,zone_id)
  WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING');
ALTER TABLE zone_routes ADD COLUMN migration_session_id uuid REFERENCES migration_sessions(session_id);

CREATE TABLE migration_proof_chunks (
  session_id uuid NOT NULL REFERENCES migration_sessions(session_id),
  first_index integer NOT NULL CHECK(first_index>=0),
  item_count integer NOT NULL CHECK(item_count BETWEEN 1 AND 64),
  proof_hash text NOT NULL,
  proof jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(session_id,first_index)
);
CREATE TABLE migration_storage (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  retained_sessions integer NOT NULL DEFAULT 0 CHECK(retained_sessions>=0),
  retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes>=0)
);
INSERT INTO migration_storage(singleton) VALUES(true);

CREATE TABLE migration_process_faults (
  fault_id uuid PRIMARY KEY,site_id text NOT NULL,
  phase text NOT NULL CHECK(phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','COMPLETED')),
  session_selector uuid,remaining integer NOT NULL DEFAULT 1 CHECK(remaining IN (0,1)),
  actor text NOT NULL,reason text NOT NULL,version bigint NOT NULL DEFAULT 1,
  armed_at timestamptz NOT NULL, fired_at timestamptz,fired_session_id uuid,cleared_at timestamptz
);
CREATE UNIQUE INDEX one_armed_migration_phase_fault ON migration_process_faults(site_id,phase) WHERE remaining=1;

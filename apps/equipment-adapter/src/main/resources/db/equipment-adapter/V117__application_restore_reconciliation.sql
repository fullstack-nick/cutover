-- Recovery metadata belongs to the adapter. The independent simulator database is never restored here.
ALTER TABLE service_control ADD COLUMN restoration_required boolean NOT NULL DEFAULT false;

CREATE TABLE restore_sessions (
  restore_id uuid PRIMARY KEY,
  checkpoint_name text NOT NULL CHECK(length(checkpoint_name) BETWEEN 3 AND 64),
  manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
  request_hash text NOT NULL,
  world_id uuid NOT NULL,
  journal_generation uuid NOT NULL,
  checkpoint_at timestamptz NOT NULL,
  checkpoint_high_water bigint NOT NULL CHECK(checkpoint_high_water>=0),
  observed_high_water bigint NOT NULL CHECK(observed_high_water>=0),
  scan_cursor bigint NOT NULL DEFAULT 0 CHECK(scan_cursor>=0),
  scanned_count integer NOT NULL DEFAULT 0 CHECK(scanned_count BETWEEN 0 AND 20000),
  unresolved_count integer NOT NULL DEFAULT 0 CHECK(unresolved_count BETWEEN 0 AND 20000),
  inventory_cursor uuid,
  inventory_total integer CHECK(inventory_total BETWEEN 0 AND 20000),
  inventory_count integer NOT NULL DEFAULT 0 CHECK(inventory_count BETWEEN 0 AND 20000),
  inventory_unresolved integer NOT NULL DEFAULT 0 CHECK(inventory_unresolved BETWEEN 0 AND 20000),
  inventory_complete boolean NOT NULL DEFAULT false,
  retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes BETWEEN 0 AND 33554432),
  state text NOT NULL DEFAULT 'HELD' CHECK(state IN ('HELD','SCANNING','QUARANTINED','VERIFIED','RELEASED')),
  version bigint NOT NULL DEFAULT 1,
  actor text NOT NULL,
  reason text NOT NULL CHECK(length(reason) BETWEEN 8 AND 500),
  routes_hash text NOT NULL,
  verification_hash text,
  release_request_hash text,
  last_error text,
  started_at timestamptz NOT NULL,
  verified_at timestamptz,
  released_at timestamptz,
  CHECK(scan_cursor<=observed_high_water),
  CHECK(unresolved_count<=scanned_count)
);
CREATE UNIQUE INDEX one_held_application_restore ON restore_sessions((true)) WHERE state<>'RELEASED';

CREATE TABLE restore_physical_findings (
  restore_id uuid NOT NULL REFERENCES restore_sessions(restore_id),
  command_id uuid NOT NULL,
  site_id text NOT NULL,
  execution_sequence bigint NOT NULL CHECK(execution_sequence>0),
  state text NOT NULL CHECK(state IN ('MATCHED','MISSING_BUSINESS_CONTEXT','INTENT_CONFLICT')),
  physical_evidence jsonb NOT NULL,
  evidence_hash text NOT NULL,
  observed_at timestamptz NOT NULL,
  PRIMARY KEY(restore_id,command_id),
  UNIQUE(restore_id,execution_sequence)
);

CREATE TABLE restore_absence_proofs (
  restore_id uuid NOT NULL REFERENCES restore_sessions(restore_id),
  command_id uuid NOT NULL REFERENCES command_journal(command_id),
  evidence jsonb NOT NULL,
  evidence_hash text NOT NULL,
  observed_at timestamptz NOT NULL,
  PRIMARY KEY(restore_id,command_id)
);

CREATE TABLE restore_inventory_findings (
  restore_id uuid NOT NULL REFERENCES restore_sessions(restore_id),
  command_id uuid NOT NULL,
  site_id text NOT NULL,
  state text NOT NULL CHECK(state IN ('MATCHED','MISSING_BUSINESS_CONTEXT','INTENT_CONFLICT')),
  simulator_state text NOT NULL CHECK(simulator_state IN ('ACCEPTED','EXECUTING','COMPLETED','REJECTED_BEFORE_EXECUTION')),
  evidence jsonb NOT NULL,
  evidence_hash text NOT NULL,
  observed_at timestamptz NOT NULL,
  PRIMARY KEY(restore_id,command_id)
);

CREATE TABLE execution_tasks (
  task_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  site_id text NOT NULL, movement_id uuid NOT NULL, allocation_id uuid NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0), zone_id text NOT NULL CHECK(zone_id IN ('ambient','chilled')),
  movement jsonb NOT NULL, payload_hash text NOT NULL, priority integer NOT NULL,
  eligible_at timestamptz NOT NULL, state text NOT NULL DEFAULT 'READY'
    CHECK(state IN ('READY','BLOCKED','DISPATCH_REQUESTED','IN_PROGRESS','COMPLETED','RECONCILIATION_REQUIRED','CANCELLED')),
  version bigint NOT NULL DEFAULT 1, transport_failures integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_id uuid, lease_until timestamptz,
  last_error text, dispatch_accepted_at timestamptz, completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(site_id,movement_id), UNIQUE(site_id,allocation_id)
);
CREATE INDEX execution_work ON execution_tasks(next_attempt_at,priority DESC,eligible_at,movement_id)
  WHERE state NOT IN ('COMPLETED','CANCELLED');
CREATE TABLE decision_rounds (
  round_id uuid PRIMARY KEY, site_id text NOT NULL, input_hash text NOT NULL,
  input jsonb NOT NULL, proposal jsonb NOT NULL, rule_version integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE shadow_comparisons (
  round_id uuid PRIMARY KEY, site_id text NOT NULL, input_hash text NOT NULL,
  input jsonb NOT NULL, legacy_proposal jsonb NOT NULL, execution_proposal jsonb NOT NULL,
  matches boolean NOT NULL, rule_version integer NOT NULL,
  compared_at timestamptz NOT NULL DEFAULT now()
);

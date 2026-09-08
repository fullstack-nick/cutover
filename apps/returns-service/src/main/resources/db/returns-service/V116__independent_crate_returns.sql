CREATE TABLE return_sites(site_id text PRIMARY KEY);
INSERT INTO return_sites VALUES ('site-a'),('site-b');
CREATE TABLE receipts (
  receipt_id uuid PRIMARY KEY, site_id text NOT NULL REFERENCES return_sites,
  source_system text NOT NULL, external_ref text NOT NULL, payload_hash text NOT NULL,
  state text NOT NULL CHECK(state IN ('REGISTERED','SORTING','COMPLETED','RECONCILIATION_REQUIRED')),
  version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL, completed_at timestamptz,
  UNIQUE(site_id,source_system,external_ref), UNIQUE(site_id,receipt_id)
);
CREATE TABLE receipt_counts (
  site_id text NOT NULL,receipt_id uuid NOT NULL,
  classification text NOT NULL CHECK(classification IN ('REUSABLE','NEEDS_CLEANING','DAMAGED')),
  received integer NOT NULL CHECK(received BETWEEN 0 AND 10000),sorted integer NOT NULL DEFAULT 0,
  PRIMARY KEY(receipt_id,classification), FOREIGN KEY(site_id,receipt_id) REFERENCES receipts(site_id,receipt_id),
  CHECK(sorted BETWEEN 0 AND received)
);
CREATE TABLE crate_counters (
  site_id text NOT NULL REFERENCES return_sites, classification text NOT NULL,
  received bigint NOT NULL DEFAULT 0,sorted bigint NOT NULL DEFAULT 0,
  PRIMARY KEY(site_id,classification),CHECK(classification IN ('REUSABLE','NEEDS_CLEANING','DAMAGED')),
  CHECK(received>=0 AND sorted BETWEEN 0 AND received)
);
INSERT INTO crate_counters(site_id,classification)
  SELECT site_id,classification FROM return_sites CROSS JOIN unnest(ARRAY['REUSABLE','NEEDS_CLEANING','DAMAGED']) classification;
CREATE TABLE return_movements (
  movement_id uuid PRIMARY KEY,site_id text NOT NULL,receipt_id uuid NOT NULL,classification text NOT NULL,
  movement jsonb NOT NULL,payload_hash text NOT NULL,state text NOT NULL DEFAULT 'REQUESTED',
  FOREIGN KEY(site_id,receipt_id) REFERENCES receipts(site_id,receipt_id),
  FOREIGN KEY(receipt_id,classification) REFERENCES receipt_counts(receipt_id,classification),
  UNIQUE(receipt_id,classification),UNIQUE(site_id,movement_id),CHECK(state IN ('REQUESTED','ASSIGNED','COMPLETED'))
);
CREATE TABLE return_tasks (
  task_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),site_id text NOT NULL,movement_id uuid NOT NULL UNIQUE,
  allocation_id uuid NOT NULL,epoch bigint NOT NULL CHECK(epoch>=0),
  state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','BLOCKED','DISPATCH_REQUESTED','IN_PROGRESS','COMPLETED','RECONCILIATION_REQUIRED')),
  version bigint NOT NULL DEFAULT 1,transport_failures integer NOT NULL DEFAULT 0,transport_paused boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),lease_id uuid,lease_until timestamptz,last_error text,
  dispatch_accepted_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
  FOREIGN KEY(site_id,movement_id) REFERENCES return_movements(site_id,movement_id)
);
CREATE INDEX return_task_work ON return_tasks(next_attempt_at,movement_id) WHERE state<>'COMPLETED' AND NOT transport_paused;
CREATE TABLE sorting_ledger (
  movement_id uuid PRIMARY KEY REFERENCES return_movements,site_id text NOT NULL,receipt_id uuid NOT NULL,
  classification text NOT NULL,quantity integer NOT NULL CHECK(quantity>0),command_id uuid NOT NULL UNIQUE,
  world_id uuid NOT NULL,journal_generation uuid NOT NULL,execution_sequence bigint NOT NULL CHECK(execution_sequence>0),
  evidence_hash text NOT NULL,completed_at timestamptz NOT NULL,
  UNIQUE(world_id,journal_generation,execution_sequence),
  FOREIGN KEY(site_id,receipt_id) REFERENCES receipts(site_id,receipt_id)
);

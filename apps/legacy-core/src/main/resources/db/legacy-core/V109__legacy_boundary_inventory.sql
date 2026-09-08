CREATE TABLE legacy_boundary_checkpoints (
  registration_id uuid PRIMARY KEY, site_id text NOT NULL,
  request_hash text NOT NULL, inventory_hash text NOT NULL,
  inventory jsonb NOT NULL, adapter_receipts jsonb NOT NULL,
  task_count integer NOT NULL CHECK(task_count BETWEEN 0 AND 20000),
  core_control_version bigint NOT NULL, adapter_control_version bigint NOT NULL,
  reason text NOT NULL, response jsonb NOT NULL,
  verified_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE legacy_tasks ADD CONSTRAINT legacy_task_identity UNIQUE(site_id,task_id,movement_id);
CREATE TABLE legacy_task_registration (
  site_id text NOT NULL, task_id uuid NOT NULL,
  movement_id uuid NOT NULL, registration_id uuid NOT NULL REFERENCES legacy_boundary_checkpoints(registration_id),
  allocation_id uuid NOT NULL, epoch bigint NOT NULL, payload_hash text NOT NULL,
  receipt jsonb NOT NULL, PRIMARY KEY(site_id,task_id),
  FOREIGN KEY(site_id,task_id,movement_id) REFERENCES legacy_tasks(site_id,task_id,movement_id),
  FOREIGN KEY(site_id,movement_id) REFERENCES movement_intents(site_id,movement_id)
);

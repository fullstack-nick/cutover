CREATE TABLE legacy_registration_receipts (
  registration_id uuid NOT NULL, site_id text NOT NULL, task_id uuid NOT NULL,
  movement_id uuid NOT NULL, request_hash text NOT NULL, receipt jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(registration_id,site_id,task_id),
  UNIQUE(registration_id,site_id,movement_id),
  FOREIGN KEY(site_id,movement_id) REFERENCES movement_allocations(site_id,movement_id)
);

CREATE TABLE cancellation_certificates (
  cancellation_id uuid PRIMARY KEY, site_id text NOT NULL, order_id uuid NOT NULL,
  request_hash text NOT NULL, request jsonb NOT NULL, certificate jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(site_id,order_id)
);

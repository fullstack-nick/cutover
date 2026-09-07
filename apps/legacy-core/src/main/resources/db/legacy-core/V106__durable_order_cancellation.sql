CREATE TABLE order_cancellations (
  cancellation_id uuid PRIMARY KEY, site_id text NOT NULL, order_id uuid NOT NULL,
  actor text NOT NULL, reason text NOT NULL, request jsonb NOT NULL,
  state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PAUSED','CANCELLED','DENIED')),
  version bigint NOT NULL DEFAULT 1, attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_id uuid, lease_until timestamptz,
  last_error text, response jsonb, created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
  FOREIGN KEY(site_id,order_id) REFERENCES orders(site_id,order_id)
);
CREATE UNIQUE INDEX one_pending_order_cancellation ON order_cancellations(site_id,order_id) WHERE state IN ('PENDING','PAUSED');
CREATE INDEX order_cancellation_retry ON order_cancellations(next_attempt_at) WHERE state='PENDING';
CREATE TABLE reservation_releases (
  reservation_id uuid PRIMARY KEY, site_id text NOT NULL, cancellation_id uuid NOT NULL REFERENCES order_cancellations(cancellation_id),
  quantity integer NOT NULL CHECK(quantity>0), released_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(site_id,reservation_id) REFERENCES reservations(site_id,reservation_id)
);

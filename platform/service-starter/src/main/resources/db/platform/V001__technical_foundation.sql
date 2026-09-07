CREATE TABLE idempotency (
  caller text NOT NULL, site_id text NOT NULL, operation text NOT NULL, request_key text NOT NULL,
  payload_hash text NOT NULL, response jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (caller, site_id, operation, request_key)
);
CREATE TABLE service_control (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  intake_paused boolean NOT NULL DEFAULT false,
  dispatch_paused boolean NOT NULL DEFAULT false,
  workers_paused boolean NOT NULL DEFAULT false,
  critical_storage boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 0
);
INSERT INTO service_control DEFAULT VALUES;
CREATE TABLE admission (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  active_requests integer NOT NULL DEFAULT 0 CHECK (active_requests >= 0),
  unpublished_events integer NOT NULL DEFAULT 0 CHECK (unpublished_events >= 0),
  unpublished_bytes bigint NOT NULL DEFAULT 0 CHECK (unpublished_bytes >= 0)
);
INSERT INTO admission DEFAULT VALUES;
CREATE TABLE outbox (
  event_id uuid PRIMARY KEY, site_id text NOT NULL, source text NOT NULL,
  aggregate_type text NOT NULL, aggregate_id uuid NOT NULL, aggregate_version bigint NOT NULL,
  event_type text NOT NULL, envelope jsonb NOT NULL, payload_bytes integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
  next_attempt_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0,
  lease_until timestamptz, lease_id uuid, last_error text,
  UNIQUE (source, site_id, aggregate_type, aggregate_id, aggregate_version)
);
CREATE INDEX outbox_pending ON outbox(next_attempt_at, created_at) WHERE published_at IS NULL;
CREATE TABLE inbox (
  event_id uuid PRIMARY KEY, envelope jsonb NOT NULL, payload_hash text NOT NULL,
  state text NOT NULL CHECK (state IN ('RECEIVED','APPLIED','PENDING','QUARANTINED')),
  attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
  received_at timestamptz NOT NULL DEFAULT now(), applied_at timestamptz, last_error text
);
CREATE INDEX inbox_pending ON inbox(next_attempt_at) WHERE state IN ('RECEIVED','PENDING');
CREATE TABLE stream_cursor (
  source text NOT NULL, site_id text NOT NULL, aggregate_type text NOT NULL, aggregate_id uuid NOT NULL,
  last_version bigint NOT NULL DEFAULT 0, PRIMARY KEY (source, site_id, aggregate_type, aggregate_id)
);
CREATE TABLE audit (
  audit_id uuid PRIMARY KEY, site_id text NOT NULL, actor text NOT NULL, action text NOT NULL,
  resource_id text NOT NULL, reason text NOT NULL, before_version bigint, after_version bigint,
  outcome text NOT NULL, detail jsonb NOT NULL DEFAULT '{}', occurred_at timestamptz NOT NULL DEFAULT now()
);

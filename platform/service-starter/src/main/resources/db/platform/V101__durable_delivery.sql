-- Technical migrations share the version sequence with owner migrations (baseline V100).
ALTER TABLE outbox ADD COLUMN paused boolean NOT NULL DEFAULT false;
ALTER TABLE outbox ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE inbox ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE inbox ADD COLUMN received_exchange text NOT NULL DEFAULT '';
ALTER TABLE inbox ADD COLUMN payload_bytes integer NOT NULL DEFAULT 0;
ALTER TABLE inbox ADD COLUMN raw_body bytea;
ALTER TABLE inbox ADD COLUMN deliveries integer NOT NULL DEFAULT 1;
ALTER TABLE inbox ADD COLUMN site_id text;

-- Keep semantic stream tombstones independently of retained payloads.
CREATE TABLE stream_entry (
  source text NOT NULL, site_id text NOT NULL, aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL, aggregate_version bigint NOT NULL,
  semantic_hash text NOT NULL, event_id uuid NOT NULL,
  PRIMARY KEY (source, site_id, aggregate_type, aggregate_id, aggregate_version)
);
CREATE TABLE delivery_quarantine (
  delivery_id uuid PRIMARY KEY, transport_message_id text, received_exchange text NOT NULL,
  site_id text, raw_body bytea NOT NULL, payload_bytes integer NOT NULL,
  reason text NOT NULL, received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE message_storage (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  active_messages integer NOT NULL DEFAULT 0 CHECK (active_messages >= 0),
  active_bytes bigint NOT NULL DEFAULT 0 CHECK (active_bytes >= 0),
  retained_bytes bigint NOT NULL DEFAULT 0 CHECK (retained_bytes >= 0)
);
INSERT INTO message_storage DEFAULT VALUES;
CREATE INDEX inbox_site_state ON inbox(site_id, state, received_at);
CREATE INDEX quarantine_site ON delivery_quarantine(site_id, received_at);

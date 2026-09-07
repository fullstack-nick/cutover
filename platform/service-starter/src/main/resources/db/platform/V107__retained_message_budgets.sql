-- Reserve retained outbox history independently of its unpublished backlog.
ALTER TABLE admission ADD COLUMN retained_outbox_bytes bigint NOT NULL DEFAULT 0 CHECK (retained_outbox_bytes >= 0);
ALTER TABLE admission ADD COLUMN retained_outbox_limit bigint NOT NULL DEFAULT 268435456 CHECK (retained_outbox_limit >= 65536);
ALTER TABLE admission ADD COLUMN database_budget_bytes bigint NOT NULL DEFAULT 536870912 CHECK (database_budget_bytes >= 16777216);
UPDATE admission SET retained_outbox_bytes=(SELECT COALESCE(sum(payload_bytes),0) FROM outbox);
ALTER TABLE inbox ALTER COLUMN envelope DROP NOT NULL;
ALTER TABLE inbox ADD COLUMN compacted_at timestamptz;
CREATE INDEX outbox_retention ON outbox(published_at,event_id) WHERE published_at IS NOT NULL;
CREATE INDEX inbox_retention ON inbox(applied_at,event_id) WHERE state='APPLIED' AND compacted_at IS NULL;

ALTER TABLE delivery_quarantine ADD COLUMN version bigint NOT NULL DEFAULT 1;
ALTER TABLE delivery_quarantine ADD COLUMN reprocess_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE delivery_quarantine ADD COLUMN state text NOT NULL DEFAULT 'QUARANTINED' CHECK (state IN ('QUARANTINED','TRANSFERRED'));
ALTER TABLE delivery_quarantine ADD COLUMN transferred_event_id uuid;
ALTER TABLE delivery_quarantine ADD COLUMN transferred_at timestamptz;
ALTER TABLE delivery_quarantine ADD COLUMN compacted_at timestamptz;
ALTER TABLE delivery_quarantine ADD COLUMN body_hash text;
UPDATE delivery_quarantine SET body_hash=encode(sha256(raw_body),'hex');
ALTER TABLE delivery_quarantine ALTER COLUMN body_hash SET NOT NULL;
ALTER TABLE delivery_quarantine ALTER COLUMN raw_body DROP NOT NULL;

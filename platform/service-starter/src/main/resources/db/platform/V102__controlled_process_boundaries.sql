ALTER TABLE service_control ADD COLUMN relay_paused boolean NOT NULL DEFAULT false;
ALTER TABLE service_control ADD COLUMN consumer_paused boolean NOT NULL DEFAULT false;
CREATE TABLE process_faults (
  fault_id uuid PRIMARY KEY, site_id text NOT NULL,
  checkpoint text NOT NULL CHECK(checkpoint IN ('AFTER_BUSINESS_COMMIT','AFTER_BROKER_CONFIRM','AFTER_EFFECT_BEFORE_ACK')),
  event_selector uuid, event_type text, remaining integer NOT NULL DEFAULT 1 CHECK(remaining IN (0,1)),
  actor text NOT NULL, reason text NOT NULL, version bigint NOT NULL DEFAULT 1,
  armed_at timestamptz NOT NULL DEFAULT now(), fired_at timestamptz, fired_event_id uuid, cleared_at timestamptz
);
CREATE UNIQUE INDEX process_faults_one_armed ON process_faults(site_id,checkpoint) WHERE remaining=1;

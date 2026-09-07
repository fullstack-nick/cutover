CREATE TABLE simulation_world (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  world_id uuid NOT NULL DEFAULT gen_random_uuid(),
  journal_generation uuid NOT NULL DEFAULT gen_random_uuid(),
  complete_history boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO simulation_world DEFAULT VALUES;
CREATE TABLE lanes (
  site_id text NOT NULL, lane_id text NOT NULL, zone_id text NOT NULL,
  blocked boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (site_id, lane_id)
);
INSERT INTO lanes(site_id,lane_id,zone_id)
SELECT site, lane, zone FROM (VALUES ('site-a'),('site-b')) s(site)
CROSS JOIN (VALUES ('ambient-a','ambient'),('ambient-b','ambient'),('chilled-a','chilled'),('chilled-b','chilled'),('returns-a','returns')) l(lane,zone);
CREATE TABLE loads (
  site_id text NOT NULL, load_id uuid NOT NULL, position text NOT NULL,
  version bigint NOT NULL DEFAULT 0, PRIMARY KEY(site_id,load_id)
);
CREATE TABLE simulator_commands (
  command_id uuid PRIMARY KEY, site_id text NOT NULL, movement_id uuid NOT NULL,
  load_id uuid NOT NULL, lane_id text NOT NULL, world_id uuid NOT NULL,
  payload jsonb NOT NULL, payload_hash text NOT NULL,
  state text NOT NULL CHECK (state IN ('ACCEPTED','EXECUTING','COMPLETED','REJECTED_BEFORE_EXECUTION')),
  version bigint NOT NULL DEFAULT 1, accepted_at timestamptz NOT NULL,
  execute_after timestamptz NOT NULL, completed_at timestamptz,
  response_delay_ms integer NOT NULL DEFAULT 0,
  UNIQUE(site_id,movement_id), FOREIGN KEY(site_id,lane_id) REFERENCES lanes(site_id,lane_id)
);
CREATE INDEX simulator_pending ON simulator_commands(execute_after) WHERE state IN ('ACCEPTED','EXECUTING');
CREATE TABLE execution_ledger (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  command_id uuid NOT NULL UNIQUE REFERENCES simulator_commands(command_id),
  site_id text NOT NULL, movement_id uuid NOT NULL, load_id uuid NOT NULL,
  source text NOT NULL, destination text NOT NULL, quantity integer NOT NULL CHECK(quantity>0),
  before_version bigint NOT NULL, after_version bigint NOT NULL,
  completed_at timestamptz NOT NULL,
  UNIQUE(site_id,movement_id)
);
CREATE TABLE simulation_faults (
  fault_id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind text NOT NULL,
  command_selector uuid, remaining integer NOT NULL CHECK(remaining>=0),
  delay_ms integer NOT NULL DEFAULT 5000 CHECK(delay_ms BETWEEN 0 AND 10000),
  created_at timestamptz NOT NULL DEFAULT now()
);

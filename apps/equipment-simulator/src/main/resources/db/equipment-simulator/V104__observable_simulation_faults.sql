ALTER TABLE simulation_faults ADD COLUMN snapshot jsonb;
ALTER TABLE simulation_faults ADD COLUMN last_activated_at timestamptz;
ALTER TABLE simulation_faults ADD COLUMN cleared_at timestamptz;
ALTER TABLE simulation_faults ADD COLUMN activate_after timestamptz NOT NULL DEFAULT now();
ALTER TABLE simulation_faults DROP CONSTRAINT simulation_faults_delay_ms_check;
ALTER TABLE simulation_faults ADD CONSTRAINT simulation_faults_delay_ms_check CHECK(delay_ms BETWEEN 0 AND 60000);
CREATE INDEX simulation_faults_active ON simulation_faults(kind,created_at) WHERE remaining>0;

-- A later successful reversal settles the route without rewriting earlier failed samples.
ALTER TABLE migration_sessions DROP CONSTRAINT migration_sessions_phase_check;
ALTER TABLE migration_sessions ADD CONSTRAINT migration_sessions_phase_check
  CHECK(phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','COMPLETED','REVERSING','REVERSED','SUPERSEDED','CANCELLED'));

-- The previous adapter writer predates assigned_at. Preserve its supported writes
-- while retaining an explicit timestamp from the current clock-aware writer.
CREATE FUNCTION record_compatibility_assignment_time() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.owner IS NOT NULL AND NEW.assigned_at IS NULL THEN
    NEW.assigned_at := clock_timestamp();
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER allocation_assignment_time BEFORE INSERT OR UPDATE ON movement_allocations
  FOR EACH ROW EXECUTE FUNCTION record_compatibility_assignment_time();

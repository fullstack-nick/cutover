-- Keep valid command evidence separate from a transport/history diagnostic.
-- Existing evidence remains available; the extra fields are additive for image rollback.
ALTER TABLE command_journal ADD COLUMN last_observation jsonb;
ALTER TABLE command_journal ADD COLUMN evidence_version bigint NOT NULL DEFAULT 0 CHECK(evidence_version>=0);
ALTER TABLE command_journal ADD COLUMN accepted_ever boolean NOT NULL DEFAULT false;
UPDATE command_journal SET last_observation=evidence,
  accepted_ever=COALESCE(evidence->>'state' IN ('ACCEPTED','EXECUTING','COMPLETED','REJECTED_BEFORE_EXECUTION'),false),
  evidence_version=CASE WHEN jsonb_typeof(evidence->'version')='number'
    THEN GREATEST((evidence->>'version')::bigint,0) ELSE 0 END;

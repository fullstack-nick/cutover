-- The first routing transition requires retained, settled baseline evidence.
-- This migration deliberately fails instead of silently registering existing work.
LOCK TABLE reservations, movement_intents, legacy_tasks IN ACCESS EXCLUSIVE MODE;
DO $$
DECLARE control service_control%ROWTYPE;
BEGIN
  SELECT * INTO STRICT control FROM service_control WHERE singleton FOR SHARE;
  IF EXISTS (SELECT 1 FROM movement_intents) OR EXISTS (SELECT 1 FROM legacy_tasks) THEN
    IF NOT control.intake_paused OR NOT control.dispatch_paused OR control.critical_storage THEN
      RAISE EXCEPTION 'BASELINE_REGISTRATION_REQUIRED: keep core intake and dispatch paused through the verified boundary';
    END IF;
    IF EXISTS (SELECT 1 FROM movement_intents m LEFT JOIN legacy_tasks t USING(site_id,movement_id) WHERE t.task_id IS NULL)
      OR EXISTS (
        SELECT 1 FROM legacy_tasks t JOIN movement_intents m USING(site_id,movement_id)
        WHERE t.state NOT IN ('COMPLETED','CANCELLED') OR t.owner<>'legacy-core'
          OR NOT EXISTS (
            SELECT 1 FROM legacy_task_registration r
            JOIN legacy_boundary_checkpoints c ON c.registration_id=r.registration_id AND c.site_id=r.site_id
            CROSS JOIN LATERAL jsonb_array_elements(c.inventory) i
            WHERE r.site_id=t.site_id AND r.task_id=t.task_id AND r.movement_id=t.movement_id
              AND r.allocation_id=t.allocation_id AND r.epoch=t.epoch
              AND c.core_control_version=control.version
              AND c.task_count=(SELECT count(*) FROM legacy_tasks site_tasks WHERE site_tasks.site_id=t.site_id)
              AND jsonb_array_length(c.inventory)=c.task_count
              AND i->>'taskId'=t.task_id::text AND i->>'movementId'=t.movement_id::text
              AND i->>'siteId'=t.site_id AND i->>'state'=t.state AND i->'movement'=m.movement
              AND r.receipt->>'allocationId'=t.allocation_id::text AND (r.receipt->>'epoch')::bigint=t.epoch
              AND r.receipt->>'payloadHash'=r.payload_hash AND r.receipt->>'owner'='legacy-core'
          )
      ) THEN
      RAISE EXCEPTION 'BASELINE_REGISTRATION_REQUIRED: every original task needs matching evidence at the current paused control version';
    END IF;
  END IF;
END $$;

CREATE TABLE legacy_task_boundary (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  original_task_count integer NOT NULL,
  registration_ids jsonb NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO legacy_task_boundary(original_task_count,registration_ids)
SELECT (SELECT count(*) FROM legacy_tasks),
  COALESCE((SELECT jsonb_agg(id ORDER BY id) FROM (SELECT DISTINCT registration_id AS id FROM legacy_task_registration) r),'[]'::jsonb);

CREATE FUNCTION create_movement_intent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p products%ROWTYPE; o orders%ROWTYPE; intent jsonb;
BEGIN
  SELECT * INTO STRICT p FROM products WHERE site_id=NEW.site_id AND sku=NEW.sku;
  SELECT * INTO STRICT o FROM orders WHERE order_id=NEW.order_id;
  intent:=jsonb_build_object('movementId',NEW.reservation_id,'reservationId',NEW.reservation_id,'siteId',NEW.site_id,
    'product','fulfilment','zoneId',p.temperature_class,'loadId',gen_random_uuid(),'source',p.source_bin,
    'destination','outbound-staging','quantity',NEW.quantity,'priority',o.priority,'eligibleAt',o.created_at);
  INSERT INTO movement_intents(movement_id,site_id,reservation_id,order_id,movement)
    VALUES(NEW.reservation_id,NEW.site_id,NEW.reservation_id,NEW.order_id,intent);
  RETURN NEW;
END $$;
DROP TRIGGER reservation_creates_legacy_task ON reservations;
CREATE TRIGGER reservation_creates_movement_intent AFTER INSERT ON reservations
  FOR EACH ROW EXECUTE FUNCTION create_movement_intent();

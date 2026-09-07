-- The characterized legacy priority routine remains the SQL scheduling authority.
ALTER TABLE legacy_tasks ADD COLUMN lease_id uuid;
ALTER TABLE legacy_tasks ADD COLUMN transport_failures integer NOT NULL DEFAULT 0;
ALTER TABLE legacy_tasks ADD COLUMN transport_paused boolean NOT NULL DEFAULT false;
ALTER TABLE legacy_tasks ADD COLUMN dispatch_accepted_at timestamptz;
CREATE FUNCTION legacy_schedule_snapshot(input jsonb) RETURNS jsonb LANGUAGE sql STABLE STRICT AS $$
  WITH candidates AS (
    SELECT item, (item->>'movementId')::uuid AS movement_id,
      legacy_priority((item->>'priority')::integer / 100) AS priority,
      (item->>'eligibleAt')::timestamptz AS eligible_at
    FROM jsonb_array_elements(input->'candidates') AS candidate(item)
  ), evaluated AS (
    SELECT c.*, row_number() OVER (ORDER BY priority DESC,eligible_at,movement_id) AS rank,
      (SELECT min((lane->>'laneId') COLLATE "C") FROM jsonb_array_elements(input->'lanes') AS lanes(lane)
       WHERE lane->>'siteId'=input->>'siteId' AND lane->>'zoneId'=item->>'zoneId'
         AND NOT (lane->>'blocked')::boolean) AS lane_id,
      CASE
        WHEN (input->>'worldMismatch')::boolean THEN 'WORLD_MISMATCH'
        WHEN (input->>'observedAt')::timestamptz + interval '5 seconds' < (input->>'decisionAt')::timestamptz
          OR (input->>'observedAt')::timestamptz > (input->>'decisionAt')::timestamptz THEN 'OBSERVATION_STALE'
        WHEN input->'route'->>'state' NOT IN ('ACTIVE','DRAINING') THEN 'ROUTE_UNCERTAIN'
        WHEN item->>'zoneId'<>input->>'zoneId' THEN 'ZONE_MISMATCH'
        WHEN item->>'owner' IS NULL OR item->>'epoch' IS NULL
          OR item->>'owner'<>input->'route'->>'owner'
          OR (item->>'epoch')::bigint<>(input->'route'->>'epoch')::bigint THEN 'OWNER_MISMATCH'
        WHEN item->>'allocationState'<>'ASSIGNED' THEN 'UNASSIGNED'
        WHEN item->>'commandState' IS NOT NULL THEN 'COMMAND_RECORDED'
        WHEN eligible_at>(input->>'decisionAt')::timestamptz THEN 'NOT_ELIGIBLE'
        ELSE 'READY' END AS initial_reason
    FROM candidates c
  ), ranked AS (
    SELECT *, CASE WHEN initial_reason='READY' AND lane_id IS NULL THEN 'LANE_BLOCKED' ELSE initial_reason END AS reason
    FROM evaluated
  )
  SELECT jsonb_build_object('ruleVersion',1,
    'selectedMovementId',(SELECT movement_id FROM ranked WHERE reason='READY' ORDER BY rank LIMIT 1),
    'selectedLaneId',(SELECT lane_id FROM ranked WHERE reason='READY' ORDER BY rank LIMIT 1),
    'ranking',COALESCE((SELECT jsonb_agg(jsonb_build_object('movementId',movement_id,'reason',reason,
      'laneId',CASE WHEN reason='READY' THEN lane_id ELSE NULL END) ORDER BY rank) FROM ranked),'[]'::jsonb))
$$;

CREATE TABLE legacy_decision_rounds (
  round_id uuid PRIMARY KEY, site_id text NOT NULL, zone_id text NOT NULL,
  input_hash text NOT NULL, input jsonb NOT NULL, proposal jsonb NOT NULL,
  rule_version integer NOT NULL, decision_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE shadow_observation_outbox (
  event_id uuid PRIMARY KEY REFERENCES legacy_decision_rounds(round_id), envelope jsonb NOT NULL,
  payload_bytes integer NOT NULL, attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_id uuid, lease_until timestamptz,
  published_at timestamptz, paused boolean NOT NULL DEFAULT false, last_error text
);
CREATE TABLE shadow_observation_capacity (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  pending_count integer NOT NULL DEFAULT 0 CHECK(pending_count>=0),
  pending_bytes bigint NOT NULL DEFAULT 0 CHECK(pending_bytes>=0),
  retained_count integer NOT NULL DEFAULT 0 CHECK(retained_count>=0),
  retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes>=0),
  omitted_rounds bigint NOT NULL DEFAULT 0 CHECK(omitted_rounds>=0)
);
INSERT INTO shadow_observation_capacity DEFAULT VALUES;

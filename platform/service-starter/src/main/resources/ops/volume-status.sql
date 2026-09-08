-- Installed by the local PostgreSQL administrator, separately from business migrations.
-- One fixed, bounded file read; callers cannot choose paths or execute other server-file functions.
CREATE SCHEMA IF NOT EXISTS cutover_ops;
REVOKE ALL ON SCHEMA cutover_ops FROM PUBLIC;
DO $$ BEGIN
  IF (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname='cutover_ops') <> current_user THEN
    RAISE EXCEPTION 'Unexpected local operational schema owner';
  END IF;
END $$;
CREATE OR REPLACE FUNCTION cutover_ops.volume_status() RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE raw text; observation jsonb; total bigint; available bigint; observed bigint;
        age numeric; reserve bigint; state text;
BEGIN
  raw := pg_read_file('/run/cutover-volume/observation.json',0,4097,true);
  IF raw IS NULL OR octet_length(raw)>4096 THEN RETURN jsonb_build_object('state','UNAVAILABLE'); END IF;
  observation := raw::jsonb;
  IF observation->>'protocol' IS DISTINCT FROM '1'
    OR NOT coalesce(observation->>'totalBytes' ~ '^[0-9]{1,18}$',false)
    OR NOT coalesce(observation->>'availableBytes' ~ '^[0-9]{1,18}$',false)
    OR NOT coalesce(observation->>'observedEpoch' ~ '^[0-9]{1,13}$',false)
  THEN RETURN jsonb_build_object('state','UNAVAILABLE'); END IF;
  total := (observation->>'totalBytes')::bigint;
  available := (observation->>'availableBytes')::bigint;
  observed := (observation->>'observedEpoch')::bigint;
  IF total<=0 OR available<0 OR available>total THEN RETURN jsonb_build_object('state','UNAVAILABLE'); END IF;
  age := extract(epoch FROM clock_timestamp())-observed;
  reserve := greatest(total/5,67108864);
  state := CASE WHEN age < -1 OR age > 15 THEN 'STALE' WHEN available < reserve THEN 'CRITICAL' ELSE 'HEALTHY' END;
  RETURN jsonb_build_object('state',state,'observedAt',to_timestamp(observed),'ageSeconds',age,
    'totalBytes',total,'availableBytes',available,'reserveBytes',reserve,'maximumAgeSeconds',15);
EXCEPTION WHEN OTHERS THEN
  RETURN jsonb_build_object('state','UNAVAILABLE');
END $$;
REVOKE ALL ON FUNCTION cutover_ops.volume_status() FROM PUBLIC;

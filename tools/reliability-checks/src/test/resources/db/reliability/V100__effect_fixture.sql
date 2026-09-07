CREATE TABLE effects (
  event_id uuid PRIMARY KEY, aggregate_id uuid NOT NULL, version bigint NOT NULL,
  value integer NOT NULL, UNIQUE(aggregate_id,version)
);

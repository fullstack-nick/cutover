CREATE TABLE shadow_capacity (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  retained_count integer NOT NULL DEFAULT 0 CHECK(retained_count>=0),
  retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes>=0)
);
INSERT INTO shadow_capacity DEFAULT VALUES;

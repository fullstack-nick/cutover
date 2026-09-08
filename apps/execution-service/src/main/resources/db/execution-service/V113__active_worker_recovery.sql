ALTER TABLE execution_tasks ADD COLUMN transport_paused boolean NOT NULL DEFAULT false;
CREATE TABLE execution_decision_capacity (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  retained_rounds integer NOT NULL DEFAULT 0 CHECK(retained_rounds>=0),
  retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes>=0),
  omitted_rounds bigint NOT NULL DEFAULT 0 CHECK(omitted_rounds>=0)
);
INSERT INTO execution_decision_capacity(singleton) VALUES(true);

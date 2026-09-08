CREATE TABLE service_identity (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  service_name text NOT NULL CHECK(service_name='returns-service'),
  scaffold_version integer NOT NULL DEFAULT 1
);
INSERT INTO service_identity(service_name) VALUES ('returns-service');

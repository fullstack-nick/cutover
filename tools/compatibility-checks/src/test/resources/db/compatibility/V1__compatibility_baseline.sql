CREATE TABLE compatibility_stock (
  sku text PRIMARY KEY,
  on_hand integer NOT NULL CHECK (on_hand >= 0),
  reserved integer NOT NULL DEFAULT 0 CHECK (reserved BETWEEN 0 AND on_hand)
);
INSERT INTO compatibility_stock(sku, on_hand) VALUES ('synthetic-sku', 10);

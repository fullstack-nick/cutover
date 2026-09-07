CREATE TABLE products (
  site_id text NOT NULL, sku text NOT NULL, display_name text NOT NULL,
  temperature_class text NOT NULL CHECK(temperature_class IN ('ambient','chilled')),
  source_bin text NOT NULL, PRIMARY KEY(site_id,sku)
);
CREATE TABLE stores (site_id text NOT NULL,store_id text NOT NULL,display_name text NOT NULL,PRIMARY KEY(site_id,store_id));
CREATE TABLE stock (
  site_id text NOT NULL,sku text NOT NULL,on_hand integer NOT NULL CHECK(on_hand>=0),
  reserved integer NOT NULL DEFAULT 0 CHECK(reserved>=0 AND reserved<=on_hand),
  version bigint NOT NULL DEFAULT 0,PRIMARY KEY(site_id,sku),FOREIGN KEY(site_id,sku) REFERENCES products(site_id,sku)
);
INSERT INTO products(site_id,sku,display_name,temperature_class,source_bin)
SELECT site,'SKU-'||lpad(n::text,3,'0'),'Market product '||n,
  CASE WHEN n%2=0 THEN 'chilled' ELSE 'ambient' END,'bin-SKU-'||lpad(n::text,3,'0')
FROM (VALUES ('site-a'),('site-b')) s(site) CROSS JOIN generate_series(1,100) n;
INSERT INTO stock(site_id,sku,on_hand)
SELECT site_id,sku,CASE WHEN sku='SKU-099' THEN 0 WHEN sku='SKU-100' THEN 2 ELSE 100 END FROM products;
INSERT INTO stores(site_id,store_id,display_name)
SELECT site,'store-'||lpad(n::text,2,'0'),'Community store '||n FROM (VALUES ('site-a'),('site-b')) s(site) CROSS JOIN generate_series(1,10) n;

CREATE TABLE orders (
  order_id uuid PRIMARY KEY,site_id text NOT NULL,source_system text NOT NULL,external_ref text NOT NULL,
  payload_hash text NOT NULL,store_id text NOT NULL,priority integer NOT NULL CHECK(priority BETWEEN 0 AND 9),
  state text NOT NULL CHECK(state IN ('ACCEPTED','RESERVED','IN_PROGRESS','COMPLETED','COMPLETED_WITH_SHORTAGE','SHORTAGE','CANCELLED')),
  version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
  cancellation_pending boolean NOT NULL DEFAULT false,
  UNIQUE(site_id,source_system,external_ref),UNIQUE(site_id,order_id),FOREIGN KEY(site_id,store_id) REFERENCES stores(site_id,store_id)
);
CREATE TABLE order_lines (
  order_id uuid NOT NULL,site_id text NOT NULL,sku text NOT NULL,requested integer NOT NULL CHECK(requested BETWEEN 1 AND 1000),
  reserved_quantity integer NOT NULL DEFAULT 0,shortage integer NOT NULL DEFAULT 0,
  PRIMARY KEY(order_id,sku),FOREIGN KEY(site_id,order_id) REFERENCES orders(site_id,order_id),FOREIGN KEY(site_id,sku) REFERENCES products(site_id,sku),
  CHECK(reserved_quantity>=0 AND shortage>=0 AND reserved_quantity+shortage<=requested)
);
CREATE TABLE reservations (
  reservation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),order_id uuid NOT NULL,site_id text NOT NULL,sku text NOT NULL,
  quantity integer NOT NULL CHECK(quantity>0),state text NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','CONSUMED','RELEASED')),
  UNIQUE(order_id,sku),UNIQUE(site_id,reservation_id),FOREIGN KEY(order_id,sku) REFERENCES order_lines(order_id,sku),FOREIGN KEY(site_id,sku) REFERENCES stock(site_id,sku)
);
CREATE TABLE movement_intents (
  movement_id uuid PRIMARY KEY,site_id text NOT NULL,reservation_id uuid NOT NULL UNIQUE,order_id uuid NOT NULL,
  movement jsonb NOT NULL,state text NOT NULL DEFAULT 'REQUESTED' CHECK(state IN ('REQUESTED','ASSIGNED','COMPLETED','CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(site_id,movement_id),
  FOREIGN KEY(site_id,reservation_id) REFERENCES reservations(site_id,reservation_id)
);
CREATE TABLE legacy_tasks (
  task_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),site_id text NOT NULL,movement_id uuid NOT NULL UNIQUE,order_id uuid NOT NULL,
  zone_id text NOT NULL,priority integer NOT NULL,eligible_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'READY' CHECK(state IN ('PLANNED','READY','BLOCKED','DISPATCH_REQUESTED','IN_PROGRESS','COMPLETED','RECONCILIATION_REQUIRED','CANCELLED')),
  allocation_id uuid,epoch bigint,owner text NOT NULL DEFAULT 'legacy-core',version bigint NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),lease_until timestamptz,last_error text,
  FOREIGN KEY(site_id,movement_id) REFERENCES movement_intents(site_id,movement_id)
);
CREATE INDEX legacy_poll ON legacy_tasks(next_attempt_at,eligible_at) WHERE state NOT IN ('COMPLETED','CANCELLED');
CREATE TABLE inventory_ledger (
  movement_id uuid PRIMARY KEY,site_id text NOT NULL,reservation_id uuid NOT NULL UNIQUE,sku text NOT NULL,
  quantity integer NOT NULL CHECK(quantity>0),command_id uuid NOT NULL,simulator_world_id uuid NOT NULL,
  execution_sequence bigint NOT NULL,consumed_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(site_id,reservation_id) REFERENCES reservations(site_id,reservation_id)
);

CREATE FUNCTION legacy_priority(p_priority integer) RETURNS integer
LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT p_priority*100 $$;

CREATE FUNCTION create_legacy_task() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p products%ROWTYPE; o orders%ROWTYPE; intent jsonb;
BEGIN
  SELECT * INTO STRICT p FROM products WHERE site_id=NEW.site_id AND sku=NEW.sku;
  SELECT * INTO STRICT o FROM orders WHERE order_id=NEW.order_id;
  intent:=jsonb_build_object('movementId',NEW.reservation_id,'reservationId',NEW.reservation_id,'siteId',NEW.site_id,
    'product','fulfilment','zoneId',p.temperature_class,'loadId',gen_random_uuid(),'source',p.source_bin,
    'destination','outbound-staging','quantity',NEW.quantity,'priority',o.priority,'eligibleAt',o.created_at);
  INSERT INTO movement_intents(movement_id,site_id,reservation_id,order_id,movement)
    VALUES(NEW.reservation_id,NEW.site_id,NEW.reservation_id,NEW.order_id,intent);
  INSERT INTO legacy_tasks(site_id,movement_id,order_id,zone_id,priority,eligible_at,next_attempt_at)
    VALUES(NEW.site_id,NEW.reservation_id,NEW.order_id,p.temperature_class,legacy_priority(o.priority),o.created_at,o.created_at);
  RETURN NEW;
END $$;
CREATE TRIGGER reservation_creates_legacy_task AFTER INSERT ON reservations FOR EACH ROW EXECUTE FUNCTION create_legacy_task();

CREATE FUNCTION reserve_order(p_order uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE line record; available integer; units integer; any_reserved boolean:=false;
BEGIN
  PERFORM 1 FROM orders WHERE order_id=p_order AND state='ACCEPTED' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Order is not reservable'; END IF;
  FOR line IN SELECT * FROM order_lines WHERE order_id=p_order ORDER BY sku LOOP
    SELECT on_hand-reserved INTO STRICT available FROM stock WHERE site_id=line.site_id AND sku=line.sku FOR UPDATE;
    units:=LEAST(available,line.requested);
    UPDATE order_lines SET reserved_quantity=units,shortage=requested-units WHERE order_id=p_order AND sku=line.sku;
    IF units>0 THEN
      UPDATE stock SET reserved=reserved+units,version=version+1 WHERE site_id=line.site_id AND sku=line.sku;
      INSERT INTO reservations(order_id,site_id,sku,quantity) VALUES(p_order,line.site_id,line.sku,units);
      any_reserved:=true;
    END IF;
  END LOOP;
  UPDATE orders SET state=CASE WHEN any_reserved THEN 'RESERVED' ELSE 'SHORTAGE' END,version=2,
    completed_at=CASE WHEN any_reserved THEN NULL ELSE now() END WHERE order_id=p_order;
END $$;

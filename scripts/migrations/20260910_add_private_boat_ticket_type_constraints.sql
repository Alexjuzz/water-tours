-- Safe to run before or after Hibernate ddl-auto=update and safe to run again.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_type varchar(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS boat_duration_minutes integer;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS boat_guest_count integer;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS boat_route_type varchar(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS boat_route_note varchar(300);

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_order_type_check_v2;
ALTER TABLE orders ADD CONSTRAINT orders_order_type_check_v2
    CHECK (order_type IN ('PASSENGER', 'PRIVATE_BOAT')) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT orders_order_type_check_v2;
DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'orders'::regclass AND contype = 'c'
          AND conname <> 'orders_order_type_check_v2'
          AND pg_get_constraintdef(oid) ILIKE '%order_type%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;
ALTER TABLE orders RENAME CONSTRAINT orders_order_type_check_v2 TO orders_order_type_check;

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_boat_route_type_check_v2;
ALTER TABLE orders ADD CONSTRAINT orders_boat_route_type_check_v2
    CHECK (boat_route_type IN ('CUSTOM', 'ASSISTED')) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT orders_boat_route_type_check_v2;
DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'orders'::regclass AND contype = 'c'
          AND conname <> 'orders_boat_route_type_check_v2'
          AND pg_get_constraintdef(oid) ILIKE '%boat_route_type%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;
ALTER TABLE orders RENAME CONSTRAINT orders_boat_route_type_check_v2 TO orders_boat_route_type_check;

ALTER TABLE order_item DROP CONSTRAINT IF EXISTS order_item_ticket_type_check_v2;
ALTER TABLE order_item ADD CONSTRAINT order_item_ticket_type_check_v2
    CHECK (ticket_type IN ('CHILD', 'ADULT', 'BENEFIT', 'PRIVATE_BOAT')) NOT VALID;
ALTER TABLE order_item VALIDATE CONSTRAINT order_item_ticket_type_check_v2;
DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'order_item'::regclass AND contype = 'c'
          AND conname <> 'order_item_ticket_type_check_v2'
          AND pg_get_constraintdef(oid) ILIKE '%ticket_type%'
    LOOP
        EXECUTE format('ALTER TABLE order_item DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;
ALTER TABLE order_item RENAME CONSTRAINT order_item_ticket_type_check_v2 TO order_item_ticket_type_check;

ALTER TABLE tickets DROP CONSTRAINT IF EXISTS tickets_ticket_type_check_v2;
ALTER TABLE tickets ADD CONSTRAINT tickets_ticket_type_check_v2
    CHECK (ticket_type IN ('CHILD', 'ADULT', 'BENEFIT', 'PRIVATE_BOAT')) NOT VALID;
ALTER TABLE tickets VALIDATE CONSTRAINT tickets_ticket_type_check_v2;
DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'tickets'::regclass AND contype = 'c'
          AND conname <> 'tickets_ticket_type_check_v2'
          AND pg_get_constraintdef(oid) ILIKE '%ticket_type%'
    LOOP
        EXECUTE format('ALTER TABLE tickets DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;
ALTER TABLE tickets RENAME CONSTRAINT tickets_ticket_type_check_v2 TO tickets_ticket_type_check;

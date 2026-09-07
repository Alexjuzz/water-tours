BEGIN;

ALTER TABLE orders ADD CONSTRAINT orders_order_status_check_v2
    CHECK (order_status IN ('DRAFT', 'PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED', 'REFUNDED')) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT orders_order_status_check_v2;
ALTER TABLE orders DROP CONSTRAINT orders_order_status_check;
ALTER TABLE orders RENAME CONSTRAINT orders_order_status_check_v2 TO orders_order_status_check;

ALTER TABLE payment ADD CONSTRAINT payment_payment_status_check_v2
    CHECK (payment_status IN ('NEW', 'PENDING', 'SUCCEEDED', 'CANCELED', 'REFUNDED')) NOT VALID;
ALTER TABLE payment VALIDATE CONSTRAINT payment_payment_status_check_v2;
ALTER TABLE payment DROP CONSTRAINT payment_payment_status_check;
ALTER TABLE payment RENAME CONSTRAINT payment_payment_status_check_v2 TO payment_payment_status_check;

ALTER TABLE tickets ADD CONSTRAINT tickets_ticket_status_check_v2
    CHECK (ticket_status IN ('ISSUED', 'USED', 'EXPIRED', 'REVOKED')) NOT VALID;
ALTER TABLE tickets VALIDATE CONSTRAINT tickets_ticket_status_check_v2;
ALTER TABLE tickets DROP CONSTRAINT tickets_ticket_status_check;
ALTER TABLE tickets RENAME CONSTRAINT tickets_ticket_status_check_v2 TO tickets_ticket_status_check;

COMMIT;

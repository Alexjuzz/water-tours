-- Additive only: a new table, no change to `orders` or `tickets` and no rewrite of any existing
-- customer record. It is the durable audit trail behind the staff action at /staff/order-support,
-- which corrects a mistyped ticket delivery address and re-sends the ALREADY ISSUED ticket PDF.
--
-- Both the old and the new address are kept deliberately: an audit trail that drops the wrong
-- address cannot answer "where did the first letter go". Addresses live here and are never
-- written to the application log.
--
-- `outcome` is PENDING while the send is in flight, then ACCEPTED or FAILED. ACCEPTED means the
-- mail server took the message (JavaMailSender returned) - never that anyone received it.
--
-- Safe to run before or after Hibernate ddl-auto=update and safe to run again.
-- Run inside a transaction with ON_ERROR_STOP.

CREATE TABLE IF NOT EXISTS order_email_corrections (
    id              uuid PRIMARY KEY,
    order_id        uuid NOT NULL,
    old_email       varchar(255),
    new_email       varchar(255) NOT NULL,
    staff_principal varchar(120) NOT NULL,
    reason          varchar(300) NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    settled_at      timestamp(6) with time zone,
    outcome         varchar(20) NOT NULL,
    failure_type    varchar(120)
);

CREATE INDEX IF NOT EXISTS idx_order_email_corrections_order_id
    ON order_email_corrections (order_id, created_at);

-- Rollback (only while no correction has been made; dropping it discards the audit trail):
--   DROP TABLE IF EXISTS order_email_corrections;

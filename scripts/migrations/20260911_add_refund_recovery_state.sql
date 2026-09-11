-- Additive only: new nullable columns for refund recovery state, plus one partial index.
-- No existing row is read or rewritten, no constraint is added or dropped, and no column is
-- widened or narrowed, so this is safe to run before or after Hibernate ddl-auto=update and safe
-- to run again. Run inside a transaction with ON_ERROR_STOP.

-- The marker written before the provider is called, plus the schedule the reconciliation job
-- uses to resolve a refund whose outcome never reached this database.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS refund_requested_at timestamp(6) with time zone;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS refund_failed_at timestamp(6) with time zone;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS refund_failure_reason varchar(300);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS refund_next_check_at timestamp(6) with time zone;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS refund_check_attempts integer;

-- Order-side hold: set while a refund is in flight, so redemption refuses the ticket.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_pending_at timestamp(6) with time zone;

-- Reconciliation scans only refunds that are still unresolved; this keeps that scan off a
-- sequential read of the whole payment table as history grows.
CREATE INDEX IF NOT EXISTS payment_refund_next_check_idx
    ON payment (refund_next_check_at)
    WHERE refund_next_check_at IS NOT NULL
      AND refunded_at IS NULL
      AND refund_failed_at IS NULL;

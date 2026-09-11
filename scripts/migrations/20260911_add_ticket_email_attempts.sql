-- Additive only: nullable columns that bound ticket email delivery. The last attempt drives the
-- resend cooldown, the counter caps customer resends, and the claim keeps two delivery runs from
-- sending the same order twice. Safe to run before or after Hibernate ddl-auto=update and safe to
-- run again. Run inside a transaction with ON_ERROR_STOP.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS tickets_email_attempt_at timestamp(6) with time zone;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tickets_email_attempts integer;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tickets_email_claimed_at timestamp(6) with time zone;

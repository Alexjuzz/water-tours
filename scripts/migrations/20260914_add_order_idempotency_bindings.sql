-- Additive only: two nullable columns on `orders`. No existing row is rewritten, nothing is
-- dropped or narrowed, and no other table is read or referenced.
--
-- Why: an Idempotency-Key used to resolve to nothing but an order id, so replaying somebody
-- else's key returned their order - access token, e-mail and phone included. The key is a request
-- header, not a secret, so that made it a bearer credential by accident.
--
-- `idempotency_request_hash` is a SHA-256 of the canonical order request the key stands for. A
-- replay carrying a different payload is refused instead of being answered with the stored order.
--
-- `idempotency_caller_hash` is a SHA-256 of the caller's own `Idempotency-Secret` header. This is
-- the ownership check: only the caller that created the order gets its credentials back on a
-- retry, which is what keeps a genuine "my first response was lost" retry working without letting
-- a stranger use the same route.
--
-- Both are NULL for every order created before this change, and for any created by a storefront
-- build that sends no secret. A NULL caller hash means "nobody can claim this record": the retry
-- is still honoured, but the reply carries no token, e-mail or phone. That is the transition path,
-- not a permanent mode.
--
-- Storing them on the row (rather than only in the Redis cache entry, which lives 10 minutes) is
-- also what stops a late retry of the same key from either creating a second order or failing on
-- the `idempotency_code` unique constraint.
--
-- Safe to run before or after Hibernate ddl-auto=update, and safe to run again.
-- Run inside a transaction with ON_ERROR_STOP.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_request_hash varchar(64);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_caller_hash  varchar(64);

-- Rollback (only before the new application build is running; afterwards it would break order
-- creation):
--   ALTER TABLE orders DROP COLUMN IF EXISTS idempotency_caller_hash;
--   ALTER TABLE orders DROP COLUMN IF EXISTS idempotency_request_hash;

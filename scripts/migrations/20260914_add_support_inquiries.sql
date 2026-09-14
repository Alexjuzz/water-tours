-- Additive only: one new table plus its indexes. Nothing in `orders`, `tickets`, `payments` or
-- `order_email_corrections` is read, changed or referenced, and no customer row is rewritten.
--
-- Backs the customer question form on the site and /question in the Telegram bot. A question is
-- stored here BEFORE anything is sent anywhere, which is what lets the site say "we have it"
-- honestly even while Telegram is unreachable.
--
-- Deliberately minimal personal data: the reply contact the customer typed, the question text and,
-- for a Telegram inquiry, the private chat id that asked. No name, no IP address. `order_reference`
-- is free text the customer optionally typed and is NEVER matched against the order table, so this
-- form cannot be used to learn anything about somebody else's purchase.
--
-- `reference` is the public handle (random, e.g. WT-K7M2QRTP): it is what the customer is shown
-- and what the owner types into /reply. The uuid primary key is never exposed.
--
-- `status`: NEW (stored, owner not notified yet) -> NOTIFIED (Telegram accepted the notification,
-- NOT that the owner read it) or UNDELIVERED (bounded retries exhausted; recoverable at
-- /staff/support-inquiries) -> ANSWERED (the owner's /reply was accepted for the asking chat).
--
-- Safe to run before or after Hibernate ddl-auto=update, and safe to run again.
-- Run inside a transaction with ON_ERROR_STOP.

CREATE TABLE IF NOT EXISTS support_inquiries (
    id                uuid PRIMARY KEY,
    reference         varchar(20) NOT NULL,
    source            varchar(20) NOT NULL,
    status            varchar(20) NOT NULL,
    message           varchar(2000) NOT NULL,
    contact           varchar(160),
    contact_kind      varchar(20) NOT NULL,
    order_reference   varchar(64),
    telegram_chat_id  bigint,
    dedupe_hash       varchar(64),
    created_at        timestamp(6) with time zone NOT NULL,
    notified_at       timestamp(6) with time zone,
    notify_attempts   integer NOT NULL DEFAULT 0,
    next_notify_at    timestamp(6) with time zone,
    last_notify_error varchar(120),
    answered_at       timestamp(6) with time zone
);

-- Unique: a reference is the only handle the owner types into /reply, so two inquiries must never
-- share one.
CREATE UNIQUE INDEX IF NOT EXISTS idx_support_inquiries_reference
    ON support_inquiries (reference);

-- Drives the notification job's "what is still waiting" query.
CREATE INDEX IF NOT EXISTS idx_support_inquiries_status
    ON support_inquiries (status, created_at);

-- Rollback (discards every stored question with it):
--   DROP TABLE IF EXISTS support_inquiries;

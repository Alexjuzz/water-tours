# Drill: moving `spring.jpa.hibernate.ddl-auto` from `update` to `validate`

`application.yml` points here. Read it before touching `SPRING_JPA_HIBERNATE_DDL_AUTO`.

## Why this is not a one-line change

`update` lets Hibernate add whatever the entities need at startup. It has never failed loudly, so
nobody knows whether the deployed schema still matches the entities — only that Hibernate has been
patching the difference on every boot. `validate` does the opposite: it compares and **refuses to
start** on any mismatch. Flipping it blind converts an unknown into an outage during a deploy,
which is the worst possible moment to discover a missing column.

So the switch is allowed only after a drill proves the comparison passes against a copy of the
schema the switch will actually meet.

`ddl-auto` is already env-overridable, so the switch is a restart, not a rebuild — and so is the
rollback.

## What has been drilled so far (2026-09-15, synthetic)

Recorded honestly, because it is **not** a production drill:

1. The build immediately before the idempotency change (`d493a46`) was compiled and started
   against an empty throwaway PostgreSQL 16 with `ddl-auto=update`. That produced a **synthetic
   old schema** — the shape a pre-migration deployment has — and one order row was created through
   the API so the table was not empty.
2. `orders` was confirmed to have neither `idempotency_request_hash` nor `idempotency_caller_hash`.
3. `scripts/migrations/20260914_add_order_idempotency_bindings.sql` was applied with
   `psql -v ON_ERROR_STOP=1 --single-transaction`. Both `ALTER TABLE`s succeeded.
4. The current build was started against that database with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
   and `show-sql=true`. It **started clean**, health `200`, and issued **no DDL at all**.
5. The pre-migration order row was then replayed through the new build: the same order came back
   with `accessToken`, `email` and `phone` all `null`, no second order was created, and both hash
   columns stayed `NULL` — the caller's secret was not adopted.

**What this proves:** the entities match the migrated schema, and the migration composes with a
schema built by the previous build.

**What this does not prove:** anything about the production database. Production has years of
`ddl-auto=update` history behind it and may carry drift this synthetic schema does not — a column
left behind by a removed field, a type widened by hand, an index created during an incident.
Only the drill below can answer that.

## The production drill (still outstanding)

Do not skip a step, and do not run any of this against the live database.

1. **Take a copy.** Use the existing backup path (`ops/disaster-recovery/backup.sh`) or a fresh
   `pg_dump` of the production database. Never restore onto production; restore into a throwaway
   container with its own name, port and volume.
2. **Restore it** into that container and confirm the row counts are non-zero for `orders`,
   `tickets` and `payments`, so you know you are validating against real shape and not an empty
   database.
3. **Apply every migration in `scripts/migrations/` that the production database has not yet had**,
   each with `-v ON_ERROR_STOP=1 --single-transaction`. At the time of writing those are
   `20260914_add_order_email_corrections.sql`, `20260914_add_support_inquiries.sql` and
   `20260914_add_order_idempotency_bindings.sql`; check which are already applied before running
   them — all three are additive and re-runnable, but knowing the starting point matters.
4. **Start the build you intend to deploy** against that restored copy with
   `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.
   - **It starts:** the switch is safe. Record the build's commit — the result is about that
     commit, not about "the app".
   - **It fails:** read the `SchemaManagementException`. It names the table and column that
     differ. That is real drift. Write a migration for it, re-run from step 2 with a fresh
     restore, and only then consider the switch. Do not "fix" it by putting `update` back and
     forgetting.
5. **Throw the container away.** Remove it, its volume and the dump. Never leave a copy of the
   production database on a workstation.

## Switching it on, and back off

```
# on the production host, in the environment file
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
# then restart the application only - no migration, no rebuild
```

Rollback is the same line set back to `update` and one more restart. Because `validate` never
writes, a failed switch leaves the schema exactly as it was: the cost of getting this wrong is a
refused startup, not a damaged database. That is the whole reason the drill is worth doing before
the deploy rather than during it.

## Flyway

Deliberately not adopted. Flyway would want a baseline against a schema whose history nobody has,
and taking that baseline wrongly is a worse failure than the one being fixed. Revisit it after the
production drill above has succeeded — at that point the schema is known, and a baseline can be
taken honestly.

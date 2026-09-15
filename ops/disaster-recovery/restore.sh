#!/bin/bash
# Full disaster recovery: brings up backend + WordPress from scratch and restores data
# from a backup folder produced by backup.sh next to this file (appdb.sql,
# wordpress-db.sql, wp-content.tar.gz, SHA256SUMS), or synced to this machine via
# WATER_BACKUP/sync-from-server.ps1.
#
# Usage:
#   ./restore.sh /path/to/backup/20260907-091959 [http://localhost]
#
# Run from this directory (ops/disaster-recovery). Requires Docker and a .env file
# here (copy .env.example first).
#
# This stack is DELIBERATELY separate from production: its own container names (dr_*), its
# own volumes and its own loopback-only ports. It never reads or writes the production
# database, and there is no code path here that could.

set -euo pipefail
cd "$(dirname "$0")"

BACKUP_DIR="${1:?Usage: ./restore.sh /path/to/backup/folder [site-url]}"
SITE_URL="${2:-http://localhost}"

fail() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------- what we were handed

for f in appdb.sql wordpress-db.sql wp-content.tar.gz; do
    [ -f "$BACKUP_DIR/$f" ] || fail "$BACKUP_DIR must contain appdb.sql, wordpress-db.sql and wp-content.tar.gz (missing $f)"
    [ -s "$BACKUP_DIR/$f" ] || fail "$BACKUP_DIR/$f is empty. Restoring from it would produce an empty site."
done

# Integrity before anything is started. A truncated dump restores "successfully" into a
# half-populated database, which is the worst outcome available here: it looks like a
# recovery and it is not one.
if [ -f "$BACKUP_DIR/SHA256SUMS" ]; then
    echo "== Verifying backup checksums =="
    ( cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS ) || fail "checksum mismatch - this backup is damaged. Use another one."
else
    echo "WARNING: $BACKUP_DIR has no SHA256SUMS (written by backup.sh since 2026-09-15)."
    echo "         Integrity cannot be verified; checking the dumps' completion markers instead."
    tail -n 5 "$BACKUP_DIR/appdb.sql" | grep -q "PostgreSQL database dump complete" \
        || fail "appdb.sql has no completion marker - the dump is truncated."
    tail -n 5 "$BACKUP_DIR/wordpress-db.sql" | grep -qi "Dump completed" \
        || fail "wordpress-db.sql has no completion marker - the dump is truncated."
fi
tar --force-local -tzf "$BACKUP_DIR/wp-content.tar.gz" > /dev/null || fail "wp-content.tar.gz is not a readable archive."

[ -f .env ] || fail ".env not found here. Copy .env.example to .env and fill it in first."

# ---------------------------------------------------------------- where we are restoring TO
#
# Everything below addresses dr_* containers only. This check exists so that a future edit
# that points a variable somewhere else fails loudly instead of quietly writing into a
# database somebody still depends on.
for name in dr_postgres dr_mariadb dr_wordpress; do
    case "$name" in
        dr_*) ;;
        *) fail "refusing to restore into '$name': this script only ever writes to dr_* containers." ;;
    esac
done

# Read the WordPress password with the whole value after the first '=' - a password may
# legitimately contain '=', and `cut -d= -f2` silently truncates it to the first field,
# which then fails as a wrong password halfway through the restore.
WP_DB_PASS=$(sed -n 's/^WORDPRESS_DB_PASSWORD=//p' .env | head -n1)
[ -n "$WP_DB_PASS" ] || fail "WORDPRESS_DB_PASSWORD is not set in .env"

echo "== Building and starting the stack (empty databases) =="
docker compose up -d --build

echo "== Waiting for Postgres and MariaDB to be healthy =="
PG_OK=starting
DB_OK=starting
for i in $(seq 1 60); do
    PG_OK=$(docker inspect -f '{{.State.Health.Status}}' dr_postgres 2>/dev/null || echo starting)
    DB_OK=$(docker inspect -f '{{.State.Health.Status}}' dr_mariadb 2>/dev/null || echo starting)
    if [ "$PG_OK" = "healthy" ] && [ "$DB_OK" = "healthy" ]; then break; fi
    sleep 2
done
[ "$PG_OK" = "healthy" ] && [ "$DB_OK" = "healthy" ] \
    || fail "databases did not become healthy in time (postgres=$PG_OK mariadb=$DB_OK). Check: docker compose logs"

# A restore into a database that already holds rows is a merge, not a restore, and it ends in
# duplicate-key errors halfway through. Refuse rather than produce a half-and-half database.
EXISTING_ORDERS=$(docker exec -i dr_postgres psql -U water_admin -d appdb -tAc \
    "select count(*) from information_schema.tables where table_schema='public'" 2>/dev/null || echo 0)
if [ "${EXISTING_ORDERS:-0}" -gt 0 ]; then
    fail "dr_postgres already has $EXISTING_ORDERS tables in appdb. This stack has been restored into before.
       Wipe it first:  docker compose down -v
       (that destroys only the dr_* volumes - production is untouched)"
fi

echo "== Restoring app database (orders, tickets, payments) =="
# ON_ERROR_STOP is what makes the exit code mean something. Without it psql prints errors,
# carries on, and exits 0 - so a failed restore reported success.
docker exec -i dr_postgres psql -v ON_ERROR_STOP=1 -U water_admin -d appdb < "$BACKUP_DIR/appdb.sql" \
    || fail "the app database restore failed. The stack is left running for inspection: docker compose logs dr_postgres"

echo "== Restoring WordPress database =="
docker exec -i dr_mariadb mysql -u wp_user -p"$WP_DB_PASS" wordpress < "$BACKUP_DIR/wordpress-db.sql" \
    || fail "the WordPress database restore failed."

echo "== Restoring uploads (theme/plugin come from this git checkout, not the backup) =="
RESTORE_TMP=$(mktemp -d)
trap 'rm -rf "$RESTORE_TMP"' EXIT
# --force-local: without it, GNU tar treats a "D:/..." Windows path as a remote
# "host:path" spec (colon before the first slash) and tries to shell out over ssh.
tar --force-local -xzf "$BACKUP_DIR/wp-content.tar.gz" -C "$RESTORE_TMP"
if [ -d "$RESTORE_TMP/wp-content/uploads" ]; then
    # Streamed in rather than copied with `docker cp`. docker cp resolves the host path through
    # the Docker daemon, so a Git Bash temp directory arrives at a Windows daemon as a drive path
    # that does not exist, and the restore dies right at the end with a cryptic
    # GetFileAttributesEx error. Piping a tar keeps every path inside the filesystem that owns it,
    # so the same command works on the server and on the owner's Windows machine - which is the
    # documented fallback location for the backup.
    ( cd "$RESTORE_TMP/wp-content" && tar -cf - uploads ) \
        | docker exec -i dr_wordpress tar -xf - -C /var/www/html/wp-content \
        || fail "copying uploads into dr_wordpress failed."
else
    echo "NOTE: the archive contains no wp-content/uploads - nothing to copy."
fi

echo "== Pointing WordPress at $SITE_URL =="
# The password goes in on stdin, not on the command line, so it does not show up in `ps`.
docker exec -i dr_mariadb mysql -u wp_user -p"$WP_DB_PASS" wordpress <<SQL
UPDATE wp_options SET option_value='${SITE_URL}' WHERE option_name IN ('siteurl','home');
SQL

echo "== Restarting the app so it picks up the restored data cleanly =="
docker compose restart app

# ---------------------------------------------------------------- did it actually work
#
# Up to this point every step reported on itself. This section asks the restored system
# instead, because "the commands ran" and "the data is there" are different claims.
echo
echo "== Verifying the restore =="
ORDERS=$(docker exec -i dr_postgres psql -U water_admin -d appdb -tAc "select count(*) from orders")
TICKETS=$(docker exec -i dr_postgres psql -U water_admin -d appdb -tAc "select count(*) from tickets")
PAYMENTS=$(docker exec -i dr_postgres psql -U water_admin -d appdb -tAc "select count(*) from payment" 2>/dev/null || echo "n/a")
WP_OPTIONS=$(docker exec -i dr_mariadb mysql -N -B -u wp_user -p"$WP_DB_PASS" wordpress -e "select count(*) from wp_options" 2>/dev/null || echo 0)
echo "   appdb: orders=$ORDERS tickets=$TICKETS payments=$PAYMENTS"
echo "   wordpress: wp_options rows=$WP_OPTIONS"

# docker compose reads .env by itself; this shell does not, so the port to poll has to be read
# the same way the password was. Polling the default while the stack published a different port
# would report a healthy recovery as a failed one.
APP_PORT=$(sed -n 's/^DR_APP_PORT=//p' .env | head -n1)
APP_PORT="${APP_PORT:-8080}"
# "Serving" and "every dependency healthy" are different questions, and a recovery instance
# legitimately fails the second one: SMTP and YooKassa keys are deliberately not restored, so
# /actuator/health answers 503 on a perfectly good recovery. Treat any HTTP answer as serving,
# and report the body separately.
APP_SERVING=no
HEALTH_BODY=
for i in $(seq 1 45); do
    # curl already prints 000 on a connection failure, so an `|| echo 000` here would append a
    # second one and make the code look like a success.
    HEALTH_CODE=$(curl -s -o /tmp/dr-health.$$ -w '%{http_code}' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null) || true
    if [ -n "$HEALTH_CODE" ] && [ "$HEALTH_CODE" != "000" ]; then
        APP_SERVING=yes
        HEALTH_BODY=$(head -c 300 /tmp/dr-health.$$ 2>/dev/null || true)
        break
    fi
    sleep 2
done
rm -f /tmp/dr-health.$$
echo "   app serving: $APP_SERVING (/actuator/health HTTP ${HEALTH_CODE:-000} ${HEALTH_BODY})"

# The question a recovery actually has to answer: is a ticket somebody bought still a ticket,
# and are the prices the ones the owner published? Read through the API, not the database, so
# the application's own mapping is exercised too.
CATALOG=$(curl -s "http://127.0.0.1:${APP_PORT}/api/v1/prices" 2>/dev/null || true)
VALID_TICKETS=$(docker exec -i dr_postgres psql -U water_admin -d appdb -tAc \
    "select count(*) from tickets where ticket_status = 'ISSUED' and valid_to > now()" 2>/dev/null || echo "n/a")
PRICE_VERSIONS=$(docker exec -i dr_postgres psql -U water_admin -d appdb -tAc \
    "select count(*) from price_versions" 2>/dev/null || echo "n/a")
echo "   tickets still valid for entry: $VALID_TICKETS"
echo "   published price versions: $PRICE_VERSIONS"
echo "   catalog served by the restored app: ${CATALOG:-<no answer>}"

echo
if [ "$APP_SERVING" != "yes" ]; then
    echo "WARNING: the application did not answer on 127.0.0.1:${APP_PORT} within 90s."
    echo "         The data is restored; the app is not serving. Check: docker compose logs app"
elif [ "${HEALTH_CODE}" != "200" ]; then
    echo "NOTE: the app is serving, but /actuator/health is not 200. On a recovery instance that is"
    echo "      usually the mail check: SMTP credentials are deliberately not part of a backup."
    echo "      Confirm with: docker compose logs app | tail -30  — and check the site itself."
fi
if [ "${WP_OPTIONS:-0}" -eq 0 ]; then
    echo "WARNING: wp_options is empty - WordPress will not have a working configuration."
fi

echo "== Done =="
echo "Site should be reachable at: $SITE_URL"
echo "Staff login: $SITE_URL/login (username/password from .env in this folder)"
echo "If this is a different domain than before, re-check DNS, TLS and any hardcoded"
echo "URLs (WORDPRESS_DB_* aside, wp-config itself is managed by the wordpress image)."
echo
echo "To take this stack down again, including its data:  docker compose down -v"
echo "That removes only the dr_* volumes. Production is not reachable from this file."

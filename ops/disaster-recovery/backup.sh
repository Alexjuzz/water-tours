#!/bin/bash
# Daily backup for Water Tours: dumps appdb (Postgres, from this repo's compose.yml),
# the WordPress database and wp-content/uploads, into a dated folder matching what
# restore.sh expects (appdb.sql, wordpress-db.sql, wp-content.tar.gz).
#
# Meant to run on the production server via cron, e.g.:
#   0 3 * * * /opt/water-tours/ops/disaster-recovery/backup.sh >> /var/log/water-tours-backup.log 2>&1
#
# CONFIRM BEFORE FIRST USE: APP_POSTGRES_CONTAINER matches this repo's compose.yml
# (container_name: tickets_postgres). WP_DB_CONTAINER/WP_CONTENT_PATH are placeholders —
# the WordPress site (water-tours-wordpress/) is deployed separately from this repo and
# its actual container name / DB credentials on the server were not verified from here
# (no server access in this session). Set them from the real server config before relying
# on this script, and remove this comment once confirmed.

set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/root/backups/daily}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

APP_POSTGRES_CONTAINER="${APP_POSTGRES_CONTAINER:-tickets_postgres}"
APP_DB_NAME="${APP_DB_NAME:-appdb}"
APP_DB_USER="${APP_DB_USER:-water_admin}"

WP_DB_CONTAINER="${WP_DB_CONTAINER:?set WP_DB_CONTAINER to the real WordPress DB container/host name}"
WP_DB_NAME="${WP_DB_NAME:?set WP_DB_NAME}"
WP_DB_USER="${WP_DB_USER:?set WP_DB_USER}"
WP_DB_PASSWORD="${WP_DB_PASSWORD:?set WP_DB_PASSWORD}"
WP_CONTENT_PATH="${WP_CONTENT_PATH:?set WP_CONTENT_PATH to the real wp-content path on the server}"

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="$BACKUP_ROOT/$STAMP"
mkdir -p "$OUT_DIR"

echo "== Dumping app database ($APP_DB_NAME from $APP_POSTGRES_CONTAINER) =="
# --no-owner --no-acl: a recovery restores into a fresh database whose role names are set by
# ops/disaster-recovery/docker-compose.yml, not by production. Without these, every OWNER TO
# and GRANT in the dump refers to a role that does not exist there, and now that restore.sh
# runs with ON_ERROR_STOP the restore stops on the first one.
docker exec -i "$APP_POSTGRES_CONTAINER" pg_dump --no-owner --no-acl -U "$APP_DB_USER" "$APP_DB_NAME" > "$OUT_DIR/appdb.sql"

echo "== Dumping WordPress database ($WP_DB_NAME from $WP_DB_CONTAINER) =="
docker exec -i "$WP_DB_CONTAINER" mysqldump -u "$WP_DB_USER" -p"$WP_DB_PASSWORD" "$WP_DB_NAME" > "$OUT_DIR/wordpress-db.sql"

echo "== Archiving wp-content/uploads =="
tar --force-local -czf "$OUT_DIR/wp-content.tar.gz" -C "$(dirname "$WP_CONTENT_PATH")" "$(basename "$WP_CONTENT_PATH")"

echo "== Verifying backup is non-empty and restorable-looking =="
for f in appdb.sql wordpress-db.sql wp-content.tar.gz; do
    if [ ! -s "$OUT_DIR/$f" ]; then
        echo "ERROR: $OUT_DIR/$f is missing or empty - backup considered failed, not rotating retention."
        exit 1
    fi
done
tar -tzf "$OUT_DIR/wp-content.tar.gz" > /dev/null

# A dump that ends mid-statement is still a large non-empty file, and pg_dump/mysqldump both
# write a recognisable terminator. Checking for it here is the difference between "a file
# exists" and "a file that can be restored".
if ! tail -n 5 "$OUT_DIR/appdb.sql" | grep -q "PostgreSQL database dump complete"; then
    echo "ERROR: appdb.sql has no completion marker - the dump was truncated. Not rotating retention."
    exit 1
fi
if ! tail -n 5 "$OUT_DIR/wordpress-db.sql" | grep -qi "Dump completed"; then
    echo "ERROR: wordpress-db.sql has no completion marker - the dump was truncated. Not rotating retention."
    exit 1
fi

echo "== Recording checksums =="
# Written last, so its presence also means every step above succeeded. restore.sh verifies it.
( cd "$OUT_DIR" && sha256sum appdb.sql wordpress-db.sql wp-content.tar.gz > SHA256SUMS )

echo "== Applying retention ($RETENTION_DAYS days) =="
# Only ever inside BACKUP_ROOT, only directories that look like a backup stamp, and never the
# one just written. An unset or mistyped BACKUP_ROOT would otherwise hand `rm -rf` a surprise.
if [ -z "${BACKUP_ROOT:-}" ] || [ "$BACKUP_ROOT" = "/" ]; then
    echo "ERROR: refusing to apply retention with BACKUP_ROOT='$BACKUP_ROOT'."
    exit 1
fi
find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d -regextype posix-extended -regex '.*/[0-9]{8}-[0-9]{6}$' ! -path "$OUT_DIR" -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} \;

echo "== Done: $OUT_DIR =="
echo "This copy lives on the same server as production. Off-site copy (e.g. rsync to"
echo "D:/WATER_BACKUP from the owner's machine, or paid off-site storage) is a separate"
echo "step — see ops/security note in README: paid storage needs an owner decision."

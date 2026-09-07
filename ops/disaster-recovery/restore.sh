#!/bin/bash
# Full disaster recovery: brings up backend + WordPress from scratch and restores data
# from a backup folder produced by the server's /root/backup-water-tours.sh script
# (appdb.sql, wordpress-db.sql, wp-content.tar.gz), or synced to this machine via
# WATER_BACKUP/sync-from-server.ps1.
#
# Usage:
#   ./restore.sh /path/to/backup/20260907-091959 [http://localhost]
#
# Run from this directory (ops/disaster-recovery). Requires Docker and a .env file
# here (copy .env.example first).

set -euo pipefail
cd "$(dirname "$0")"

BACKUP_DIR="${1:?Usage: ./restore.sh /path/to/backup/folder [site-url]}"
SITE_URL="${2:-http://localhost}"

if [ ! -f "$BACKUP_DIR/appdb.sql" ] || [ ! -f "$BACKUP_DIR/wordpress-db.sql" ] || [ ! -f "$BACKUP_DIR/wp-content.tar.gz" ]; then
    echo "ERROR: $BACKUP_DIR must contain appdb.sql, wordpress-db.sql and wp-content.tar.gz"
    exit 1
fi

if [ ! -f .env ]; then
    echo "ERROR: .env not found here. Copy .env.example to .env and fill it in first."
    exit 1
fi

echo "== Building and starting the stack (empty databases) =="
docker compose up -d --build

echo "== Waiting for Postgres and MariaDB to be healthy =="
for i in $(seq 1 30); do
    PG_OK=$(docker inspect -f '{{.State.Health.Status}}' dr_postgres 2>/dev/null || echo starting)
    DB_OK=$(docker inspect -f '{{.State.Health.Status}}' dr_mariadb 2>/dev/null || echo starting)
    if [ "$PG_OK" = "healthy" ] && [ "$DB_OK" = "healthy" ]; then break; fi
    sleep 2
done
if [ "$PG_OK" != "healthy" ] || [ "$DB_OK" != "healthy" ]; then
    echo "ERROR: databases did not become healthy in time. Check: docker compose logs"
    exit 1
fi

echo "== Restoring app database (orders, tickets, payments) =="
docker exec -i dr_postgres psql -U water_admin -d appdb < "$BACKUP_DIR/appdb.sql"

echo "== Restoring WordPress database =="
WP_DB_PASS=$(grep '^WORDPRESS_DB_PASSWORD=' .env | cut -d= -f2)
docker exec -i dr_mariadb mysql -u wp_user -p"$WP_DB_PASS" wordpress < "$BACKUP_DIR/wordpress-db.sql"

echo "== Restoring uploads (theme/plugin come from this git checkout, not the backup) =="
RESTORE_TMP=$(mktemp -d)
# --force-local: without it, GNU tar treats a "D:/..." Windows path as a remote
# "host:path" spec (colon before the first slash) and tries to shell out over ssh.
tar --force-local -xzf "$BACKUP_DIR/wp-content.tar.gz" -C "$RESTORE_TMP"
if [ -d "$RESTORE_TMP/wp-content/uploads" ]; then
    docker cp "$RESTORE_TMP/wp-content/uploads/." dr_wordpress:/var/www/html/wp-content/uploads/
fi
rm -rf "$RESTORE_TMP"

echo "== Pointing WordPress at $SITE_URL =="
docker exec -i dr_mariadb mysql -u wp_user -p"$WP_DB_PASS" wordpress -e \
    "UPDATE wp_options SET option_value='${SITE_URL}' WHERE option_name IN ('siteurl','home');"

echo "== Restarting the app so it picks up the restored data cleanly =="
docker compose restart app

echo
echo "== Done =="
echo "Site should be reachable at: $SITE_URL"
echo "Staff login: $SITE_URL/login (username/password from .env in this folder)"
echo "If this is a different domain than before, re-check DNS, TLS and any hardcoded"
echo "URLs (WORDPRESS_DB_* aside, wp-config itself is managed by the wordpress image)."

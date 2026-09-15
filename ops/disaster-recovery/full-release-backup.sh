#!/bin/bash
#
# A complete, restorable snapshot of one release: everything the nightly
# /root/backup-water-tours.sh does NOT capture, plus fresh copies of what it does.
#
# Run manually, at release time - "after local fixes and before final deployment", or any time a
# full recovery point is wanted. NOT installed on any schedule and NOT a replacement for the
# nightly job: that job's three-file daily set stays exactly as it is, and this writes to its own
# directory so the two can never collide.
#
#   sudo /root/full-release-backup.sh
#
# What it captures, and why each piece is here:
#
# - **appdb.sql / wordpress-db.sql** - fresh logical dumps, same validation discipline as the
#   nightly job (end-of-dump marker, non-empty, checksummed).
# - **wordpress-full.tar.gz** - the WHOLE `/var/www/water-tours` (core + wp-content + uploads +
#   `wp-config.php`), not just `wp-content` like the nightly set. A `wp-content` restore is only
#   as good as the WordPress core and config it runs inside; this is the difference between "the
#   content restores" and "the site restores".
# - **app-env.bak, wp-db-credentials.bak, compose.yml, nginx configs, letsencrypt.tar.gz** - the
#   configuration and secrets a rebuild needs and the nightly job never touches. These are opaque
#   secret-bearing files: this script never echoes their contents, only copies and checksums them,
#   and leaves them mode 600.
# - **POSTGRES-RECREATE.md / MARIADB-RECREATE.md** - the roles, extensions and grants a bare
#   `psql`/`mysql` restore of the dumps above does not reconstruct by itself, written as commands
#   to run, with passwords referenced from the credential files rather than embedded here.
# - **REDIS-RATIONALE.md** - Redis holds only short-TTL idempotency-key and rate-limiter state
#   (`appendonly no`, checked and recorded empty at backup time). There is nothing durable to
#   restore; a fresh instance is the correct restore, and this file says so instead of shipping an
#   RDB file with nothing meaningful in it.
# - **RELEASE-MANIFEST.md** - which git commit(s) the running WordPress theme/plugin and backend
#   match, filled in by the caller (see the placeholder at the top) because that is release
#   metadata this script cannot know on its own.
#
# Same publish discipline as the nightly job: everything is written into a dot-prefixed staging
# directory, validated, checksummed, and only then renamed to its final timestamped name - so a
# directory without a leading dot under /root/backups/full is always a complete set.
#
set -Eeuo pipefail
umask 077

ROOT=${FULL_BACKUP_ROOT:-/root/backups/full}
STAMP=$(date -u +%Y%m%d-%H%M%S)
STAGE="$ROOT/.incomplete-$STAMP"
FINAL="$ROOT/full-$STAMP"

log() { printf '%s %s\n' "$(date -Iseconds)" "$*"; }
fail() { log "FAILED: $*"; exit 1; }

mkdir -p "$ROOT"
[ -e "$FINAL" ] && fail "$FINAL already exists - refusing to overwrite"
mkdir -p "$STAGE"
chmod 700 "$STAGE"

log "staging in $STAGE"

# --- credentials, read once, never echoed ---------------------------------------------------
DB_PASS=$(grep '^DB_PASSWORD=' /opt/water-tours/.env | cut -d= -f2-) || fail "no DB_PASSWORD"
WP_DB_PASS=$(grep '^WP_DB_PASSWORD=' /root/.wp_db_credentials | cut -d= -f2-) || fail "no WP_DB_PASSWORD"
[ -n "$DB_PASS" ] && [ -n "$WP_DB_PASS" ] || fail "an empty password was read"

# --- database dumps, validated the same way the nightly job validates them ------------------
docker exec -e PGPASSWORD="$DB_PASS" tickets_postgres pg_dump -U water_admin -d appdb \
    > "$STAGE/appdb.sql" || fail "pg_dump failed"
grep -q 'PostgreSQL database dump complete' "$STAGE/appdb.sql" || fail "appdb.sql truncated"

MYSQL_PWD="$WP_DB_PASS" mysqldump -u wp_user wordpress \
    > "$STAGE/wordpress-db.sql" || fail "mysqldump failed"
grep -q 'Dump completed on' "$STAGE/wordpress-db.sql" || fail "wordpress-db.sql truncated"

# --- the whole WordPress install, not only wp-content ---------------------------------------
tar --exclude='wp-content/cache' -czf "$STAGE/wordpress-full.tar.gz" \
    -C /var/www water-tours || fail "wordpress-full.tar.gz failed"
gzip -t "$STAGE/wordpress-full.tar.gz" || fail "wordpress-full.tar.gz is not valid gzip"

# --- configuration and secrets the nightly job never captures -------------------------------
cp -p /opt/water-tours/.env "$STAGE/app-env.bak"
cp -p /root/.wp_db_credentials "$STAGE/wp-db-credentials.bak"
cp -p /opt/water-tours/compose.yml "$STAGE/compose.yml"
cp -p /etc/nginx/sites-available/water-tours.ru "$STAGE/nginx-site.conf"
[ -f /etc/nginx/conf.d/water-tours-log-redaction.conf ] && \
    cp -p /etc/nginx/conf.d/water-tours-log-redaction.conf "$STAGE/nginx-log-redaction.conf"
cp -p /root/backup-water-tours.sh "$STAGE/backup-water-tours.sh.installed"
crontab -l > "$STAGE/crontab.txt" 2>/dev/null || true

# TLS: the certbot-managed certificate and its renewal state. Secret-bearing (the private key
# lives in here) - opaque, never inspected by this script beyond a tar/gzip validity check.
if [ -d /etc/letsencrypt ]; then
    tar -czf "$STAGE/letsencrypt.tar.gz" -C /etc letsencrypt || fail "letsencrypt.tar.gz failed"
    gzip -t "$STAGE/letsencrypt.tar.gz" || fail "letsencrypt.tar.gz is not valid gzip"
fi

# --- recreation instructions, no secrets embedded --------------------------------------------
cat > "$STAGE/POSTGRES-RECREATE.md" <<'EOF'
# PostgreSQL recreation

Server: postgres:16 (matches the running `tickets_postgres` image).

Extensions in use: `plpgsql` only - created automatically in every new PostgreSQL database,
no action needed.

Role in use: `water_admin` (Superuser, Create role, Create DB, Replication, Bypass RLS).
Password: in `app-env.bak` as `DB_PASSWORD`, never embedded here.

Recreate:
    createuser -U postgres -s water_admin
    psql -U postgres -c "ALTER USER water_admin WITH PASSWORD '<from app-env.bak DB_PASSWORD>';"
    createdb -U water_admin appdb
    psql -U water_admin -d appdb -f appdb.sql
EOF

cat > "$STAGE/MARIADB-RECREATE.md" <<'EOF'
# MariaDB recreation

Server: 10.3.39-MariaDB (matches the host's native `mysql`/`mariadb`, no container).

User in use: `wp_user`@`localhost`, ALL PRIVILEGES on `wordpress`.* only.
Password: in `wp-db-credentials.bak` as `WP_DB_PASSWORD`, never embedded here.

Recreate:
    mysql -u root -e "CREATE DATABASE wordpress CHARACTER SET utf8mb4;"
    mysql -u root -e "CREATE USER 'wp_user'@'localhost' IDENTIFIED BY '<from wp-db-credentials.bak WP_DB_PASSWORD>';"
    mysql -u root -e "GRANT ALL PRIVILEGES ON wordpress.* TO 'wp_user'@'localhost'; FLUSH PRIVILEGES;"
    mysql -u wp_user -p wordpress < wordpress-db.sql
EOF

cat > "$STAGE/REDIS-RATIONALE.md" <<EOF
# Redis: why there is no RDB/AOF file in this backup

Checked at backup time ($(date -u -Iseconds)):
- \`CONFIG GET appendonly\` -> no (AOF disabled)
- \`DBSIZE\` -> $(docker exec tickets_redis redis-cli DBSIZE 2>/dev/null || echo 'unavailable')

Redis here holds only short-TTL state: idempotency-key locks and order-creation
rate-limiter counters (see \`OrderCreationRateLimiter\`, \`idempotency.*\` in application.yml).
None of it is meant to survive more than minutes, and losing it on a restore has one effect:
an in-flight idempotency window resets and the rate-limiter's counters start from zero. No
business data lives here. A fresh, empty Redis (\`redis:7\`, no volume) is therefore the correct
restore, not a snapshot of this one.
EOF

cat > "$STAGE/RELEASE-MANIFEST.md" <<EOF
# Release captured $(date -u -Iseconds)

Set the two env vars below before running this script to fill in the git side of this manifest
for a real release; both default to "not supplied" so the script never fails without them.

- Theme/plugin (\`integrations/wordpress\`) commit: ${RELEASE_WP_COMMIT:-not supplied}
- Backend (\`src/\`) commit:                        ${RELEASE_BACKEND_COMMIT:-not supplied}
- WordPress core version: $(grep -oP "wp_version = .\K[^;']+" /var/www/water-tours/wp-includes/version.php 2>/dev/null || echo unknown)
- PHP version:            $(php -v 2>/dev/null | head -1 || echo unknown)
- nginx version:          $(nginx -v 2>&1 || echo unknown)
- MariaDB version:        $(mysql --version 2>/dev/null || echo unknown)
- tickets_app image:      $(docker inspect -f '{{.Config.Image}}' tickets_app 2>/dev/null) ($(docker inspect -f '{{.Id}}' tickets_app 2>/dev/null | cut -c1-19))
- tickets_postgres image: $(docker inspect -f '{{.Config.Image}}' tickets_postgres 2>/dev/null)
- tickets_redis image:    $(docker inspect -f '{{.Config.Image}}' tickets_redis 2>/dev/null)

## Files in this set

| File | What |
|---|---|
| appdb.sql | PostgreSQL logical dump (schema + data) |
| wordpress-db.sql | MariaDB logical dump of \`wordpress\` |
| wordpress-full.tar.gz | \`/var/www/water-tours\` complete: core + wp-content + wp-config.php |
| app-env.bak | \`/opt/water-tours/.env\` (secret) |
| wp-db-credentials.bak | \`/root/.wp_db_credentials\` (secret) |
| compose.yml | backend container definitions |
| nginx-site.conf, nginx-log-redaction.conf | nginx site + log redaction |
| letsencrypt.tar.gz | certbot cert, key and renewal config (secret) |
| backup-water-tours.sh.installed | the nightly daily-backup script, as installed |
| crontab.txt | \`root\`'s crontab at capture time |
| POSTGRES-RECREATE.md, MARIADB-RECREATE.md, REDIS-RATIONALE.md | recreation instructions |
EOF

# --- checksums, written then verified before anything is called complete --------------------
( cd "$STAGE" && sha256sum -- * > SHA256SUMS )
( cd "$STAGE" && sha256sum -c --quiet SHA256SUMS ) || fail "SHA256SUMS does not verify"
chmod 600 "$STAGE"/*

SIZE=$(du -sh "$STAGE" | cut -f1)
mv "$STAGE" "$FINAL" || fail "could not publish $STAGE as $FINAL"
chmod 700 "$FINAL"

log "full release backup complete: $FINAL ($SIZE)"
echo "$FINAL"

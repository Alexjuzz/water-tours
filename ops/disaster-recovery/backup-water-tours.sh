#!/bin/bash
#
# The nightly backup that actually runs on the production host.
#
# Installed as /root/backup-water-tours.sh, mode 700, started by the single cron entry
# `15 4 * * * /root/backup-water-tours.sh`. There is exactly one schedule and this is it.
#
# NOTE: ops/disaster-recovery/backup.sh in this repository is a DIFFERENT, uninstalled script
# written for the Docker recovery topology. Do not install it beside this one - two schedules
# writing the same directory is how you get half-written sets that look complete.
#
# What changed here, and why each change exists. Every one of these is a defect that was
# observed on this machine, not a hypothetical:
#
# 1. **A failed run used to leave something that looked like a backup.** `pg_dump > file`
#    creates the file before pg_dump can fail, and `set -e` then aborted with a 0-byte
#    appdb.sql sitting in a directory named like every good one. `20260909-041501` is exactly
#    that: an empty dump, the other two files never written, and not one line in the log.
#    Work now happens in `.incomplete-<stamp>`, which is published to its final name only
#    after every check has passed, so a directory with a timestamp name is a complete set by
#    construction. The leading dot also keeps it out of the Windows sync's `20??????-??????`
#    search.
# 2. **Nothing was validated.** Each artifact is now checked for existence, non-zero size and
#    the marker its own format ends with - `PostgreSQL database dump complete`,
#    `Dump completed on`, a passing `gzip -t`. A truncated dump fails the run instead of
#    being kept.
# 3. **No checksums were written**, so corruption after the fact was undetectable. SHA256SUMS
#    is written and immediately verified before publication. It is written with LF endings,
#    because the restore side reads it with `sha256sum -c`.
# 4. **Only success was logged.** Failures now log why, and the script exits non-zero so cron
#    mails the run. A run blocked by the lock logs SKIPPED and exits 0 - that is not a failure.
# 5. **No locking.** Two runs (cron plus a manual `backup.cmd`) could interleave into one
#    directory. flock makes the second one wait its turn or stand down.
# 6. **Retention could delete the last good set.** `find -mtime +7 -exec rm -rf` counted a
#    broken directory as a backup and deleted by age alone. Retention now deletes only sets it
#    has verified as complete, never the newest one, and never below MIN_COMPLETE_SETS. Sets it
#    cannot verify are never deleted - they are reported, and what to do with them is the
#    owner's decision, not a cron job's.
# 7. **Passwords were cut at the first `=`** and mysqldump got its password on the command
#    line, where `ps` can read it. Split on the first `=` only, and pass it in the environment.
#
set -Eeuo pipefail
umask 077

# BACKUP_ROOT exists so this exact script can be exercised against a scratch tree - the
# retention block is the one part that deletes things, and it is not worth trusting
# untested. cron passes nothing, so cron writes the real directory.
ROOT=${BACKUP_ROOT:-/root/backups/daily}
LOG=$ROOT/backup.log
LOCK=${BACKUP_LOCK:-/root/backups/.backup.lock}
# Overridable so a verification run can be made without deleting anything, and so the
# policy can be changed without editing the script. cron passes neither, so cron gets 7/3.
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-7}
MIN_COMPLETE_SETS=${BACKUP_MIN_COMPLETE_SETS:-3}
ARTIFACTS=(appdb.sql wordpress-db.sql wp-content.tar.gz)

STAMP=$(date -u +%Y%m%d-%H%M%S)
STAGE=$ROOT/.incomplete-$STAMP
FINAL=$ROOT/$STAMP

mkdir -p "$ROOT"

log() { printf '%s %s\n' "$(date -Iseconds)" "$*" >> "$LOG"; }

fail() {
    log "backup FAILED ($STAMP): $*"
    # The staging directory is kept for diagnosis. It can never be mistaken for a backup:
    # nothing reads a name beginning with a dot as a set.
    exit 1
}
trap 'fail "unexpected error on line $LINENO"' ERR

# One run at a time. A second run stands down rather than writing into the same place.
exec 9>"$LOCK"
if ! flock -n 9; then
    log "backup SKIPPED ($STAMP): another run holds the lock"
    exit 0
fi

# --- credentials ---------------------------------------------------------------------------
# -f2- not -f2: a password containing '=' was being silently truncated.
DB_PASS=$(grep '^DB_PASSWORD=' /opt/water-tours/.env | cut -d= -f2-) || fail "no DB_PASSWORD"
WP_DB_PASS=$(grep '^WP_DB_PASSWORD=' /root/.wp_db_credentials | cut -d= -f2-) || fail "no WP_DB_PASSWORD"
[ -n "$DB_PASS" ] && [ -n "$WP_DB_PASS" ] || fail "an empty password was read"

mkdir -p "$STAGE"
chmod 700 "$STAGE"

# --- capture -------------------------------------------------------------------------------
docker exec -e PGPASSWORD="$DB_PASS" tickets_postgres pg_dump -U water_admin -d appdb \
    > "$STAGE/appdb.sql" || fail "pg_dump failed"

MYSQL_PWD="$WP_DB_PASS" mysqldump -u wp_user wordpress \
    > "$STAGE/wordpress-db.sql" || fail "mysqldump failed"

tar --exclude='wp-content/cache' -czf "$STAGE/wp-content.tar.gz" \
    -C /var/www/water-tours wp-content || fail "tar of wp-content failed"

# --- validate before this is allowed to be called a backup ----------------------------------
for f in "${ARTIFACTS[@]}"; do
    [ -s "$STAGE/$f" ] || fail "$f is missing or empty"
done
grep -q 'PostgreSQL database dump complete' "$STAGE/appdb.sql" \
    || fail "appdb.sql has no end-of-dump marker - truncated"
grep -q 'Dump completed on' "$STAGE/wordpress-db.sql" \
    || fail "wordpress-db.sql has no end-of-dump marker - truncated"
gzip -t "$STAGE/wp-content.tar.gz" || fail "wp-content.tar.gz is not a valid gzip"
tar -tzf "$STAGE/wp-content.tar.gz" > /dev/null || fail "wp-content.tar.gz is not a readable tar"

# --- checksums, written then immediately verified -------------------------------------------
( cd "$STAGE" && sha256sum "${ARTIFACTS[@]}" > SHA256SUMS ) || fail "could not write SHA256SUMS"
( cd "$STAGE" && sha256sum -c --quiet SHA256SUMS ) || fail "SHA256SUMS does not verify"
chmod 600 "$STAGE"/*

# --- publish atomically ---------------------------------------------------------------------
[ -e "$FINAL" ] && fail "$FINAL already exists - refusing to overwrite"
mv "$STAGE" "$FINAL" || fail "could not publish $STAGE as $FINAL"
chmod 700 "$FINAL"
SIZE=$(du -sh "$FINAL" | cut -f1)
log "backup complete: $FINAL ($SIZE, 3 artifacts, SHA256SUMS verified)"

# --- retention: only ever deletes something it has verified ----------------------------------
# A set counts as complete if the three artifacts are present, non-empty and well-formed, and
# - when it carries a manifest - that manifest verifies. Legacy sets predating SHA256SUMS are
# judged on the first three tests alone, so retention keeps working on them.
is_complete() {
    local d=$1 f
    for f in "${ARTIFACTS[@]}"; do [ -s "$d/$f" ] || return 1; done
    grep -q 'PostgreSQL database dump complete' "$d/appdb.sql" 2>/dev/null || return 1
    grep -q 'Dump completed on' "$d/wordpress-db.sql" 2>/dev/null || return 1
    gzip -t "$d/wp-content.tar.gz" 2>/dev/null || return 1
    [ -e "$d/SHA256SUMS" ] && { ( cd "$d" && sha256sum -c --quiet SHA256SUMS ) >/dev/null 2>&1 || return 1; }
    return 0
}

mapfile -t SETS < <(find "$ROOT" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' | sort)
complete=() incomplete=()
for d in "${SETS[@]}"; do
    if is_complete "$d"; then complete+=("$d"); else incomplete+=("$d"); fi
done

if [ "${#incomplete[@]}" -gt 0 ]; then
    # Never deleted here. A directory that cannot be verified is either a failure worth looking
    # at or a set this script does not understand; either way a cron job should not destroy it.
    log "backup NOTE: ${#incomplete[@]} set(s) do not verify and were left untouched: $(printf '%s ' "${incomplete[@]##*/}")"
fi

cutoff=$(date -u -d "-$RETENTION_DAYS days" +%Y%m%d-%H%M%S)
removed=0
remaining=${#complete[@]}
for d in "${complete[@]}"; do
    [ "$remaining" -gt "$MIN_COMPLETE_SETS" ] || break
    name=${d##*/}
    [[ "$name" < "$cutoff" ]] || break          # sorted ascending, so nothing older follows
    [ "$d" != "${complete[-1]}" ] || break      # never the newest complete set
    rm -rf "$d" && removed=$((removed + 1)) && remaining=$((remaining - 1))
done
[ "$removed" -gt 0 ] && log "backup retention: removed $removed verified set(s) older than $RETENTION_DAYS days; $remaining complete set(s) kept"

# Staging directories from failed runs are not backups; clear the ones old enough to have been
# looked at already.
find "$ROOT" -mindepth 1 -maxdepth 1 -type d -name '.incomplete-*' -mtime +2 -exec rm -rf {} + 2>/dev/null || true

trap - ERR
exit 0

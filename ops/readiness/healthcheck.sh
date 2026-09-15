#!/bin/bash
# Operational readiness check. Looks at the application, the disk, and - added 2026-09-15 - at
# whether the backup that is supposed to be running actually ran and is intact.
#
# It writes one line per run and, when something CHANGES state, calls a notifier. The notifier
# is OFF until it is configured: no destination is guessed here, and nothing is sent anywhere
# by default.
#
# Suggested cron (production server):
#   */15 * * * * /opt/water-tours/ops/readiness/healthcheck.sh >> /var/log/water-tours-health.log 2>&1
#
# A backup that silently stopped running is the failure this cannot afford to miss: it looks
# exactly like a healthy system right up until the moment somebody needs to restore.

set -uo pipefail

APP_HEALTH_URL="${APP_HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
DISK_WARN_PERCENT="${DISK_WARN_PERCENT:-85}"
BACKUP_ROOT="${BACKUP_ROOT:-/root/backups/daily}"
# How old the newest backup may be before this is a problem. The policy is daily at 03:00, so
# 30 hours leaves room for one late run without crying wolf.
BACKUP_MAX_AGE_HOURS="${BACKUP_MAX_AGE_HOURS:-30}"
# Where the last reported state is remembered, so a notification fires on a CHANGE rather than
# every fifteen minutes for as long as the problem lasts.
STATE_FILE="${STATE_FILE:-/var/lib/water-tours/healthcheck.state}"

STAMP=$(date --iso-8601=seconds)
# PROBLEMS carries what to tell a human; KINDS carries what CHANGED. They are separate because
# the human message contains numbers that move every run (a disk percentage, a backup age), and
# keying the change detection on those would make every single run look like a new problem.
PROBLEMS=()
KINDS=()

# ---------------------------------------------------------------- application
if curl -fsS --max-time 10 "$APP_HEALTH_URL" > /dev/null 2>&1; then
    APP="health=OK"
else
    # Distinguish "not answering at all" from "answering but a dependency is down": the second
    # is usually SMTP and is not a reason to wake anybody at 3am, the first is.
    CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$APP_HEALTH_URL" 2>/dev/null)
    if [ -n "$CODE" ] && [ "$CODE" != "000" ]; then
        APP="health=DEGRADED(http=$CODE)"
        PROBLEMS+=("application answers but reports a dependency down (HTTP $CODE)"); KINDS+=("app-degraded")
    else
        APP="health=UNREACHABLE"
        PROBLEMS+=("application is not answering on $APP_HEALTH_URL"); KINDS+=("app-unreachable")
    fi
fi

# ---------------------------------------------------------------- disk
DISK_USE=$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')
# The range check is not paranoia about df: it is what stops a shell whose df prints a different
# column layout (Git Bash on Windows, for one) from raising a permanent false alarm.
if ! printf '%s' "${DISK_USE:-}" | grep -Eq '^[0-9]{1,3}$' || [ "${DISK_USE:-101}" -gt 100 ]; then
    DISK_USE=""
fi
if [ -n "$DISK_USE" ] && [ "$DISK_USE" -ge "$DISK_WARN_PERCENT" ]; then
    DISK="disk=${DISK_USE}%(WARN)"
    PROBLEMS+=("disk is ${DISK_USE}% full"); KINDS+=("disk")
else
    DISK="disk=${DISK_USE:-?}%"
fi

# ---------------------------------------------------------------- backups
#
# Three separate questions, because they fail separately: is there a backup at all, is the
# newest one recent enough, and is it complete. A cron job that dies halfway leaves a fresh
# directory with a truncated dump in it, which passes an age check and fails a real restore.
if [ ! -d "$BACKUP_ROOT" ]; then
    BACKUP="backup=NO_ROOT"
    PROBLEMS+=("backup directory $BACKUP_ROOT does not exist"); KINDS+=("backup-no-root")
else
    NEWEST=$(find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d -printf '%T@ %p\n' 2>/dev/null \
             | sort -rn | head -n1 | cut -d' ' -f2-)
    if [ -z "$NEWEST" ]; then
        BACKUP="backup=NONE"
        PROBLEMS+=("no backup has ever been written to $BACKUP_ROOT"); KINDS+=("backup-none")
    else
        AGE_HOURS=$(( ( $(date +%s) - $(stat -c %Y "$NEWEST") ) / 3600 ))
        BACKUP="backup=$(basename "$NEWEST") age=${AGE_HOURS}h"
        if [ "$AGE_HOURS" -gt "$BACKUP_MAX_AGE_HOURS" ]; then
            BACKUP="$BACKUP(STALE)"
            PROBLEMS+=("newest backup is ${AGE_HOURS}h old (limit ${BACKUP_MAX_AGE_HOURS}h) - the backup job may have stopped"); KINDS+=("backup-stale")
        fi
        if [ -f "$NEWEST/SHA256SUMS" ]; then
            if ( cd "$NEWEST" && sha256sum -c --status SHA256SUMS ); then
                BACKUP="$BACKUP checksums=OK"
            else
                BACKUP="$BACKUP checksums=FAILED"
                PROBLEMS+=("newest backup $(basename "$NEWEST") fails its own checksums - it is damaged"); KINDS+=("backup-corrupt")
            fi
        else
            BACKUP="$BACKUP checksums=MISSING"
            PROBLEMS+=("newest backup $(basename "$NEWEST") has no SHA256SUMS - backup.sh did not finish"); KINDS+=("backup-incomplete")
        fi
    fi
fi

STATUS_LINE="$STAMP $APP $DISK $BACKUP"
echo "$STATUS_LINE"

# ---------------------------------------------------------------- notify, on change only
#
# NOT ENABLED. Turning this on needs two owner decisions that must not be guessed here: which
# Telegram chat (or which mailbox) is the destination, and whether a bot token may be read by
# a cron job. Until HEALTH_NOTIFY_COMMAND is set, this block records state and sends nothing.
#
# To enable, set both in the cron environment or in a file this script sources:
#   HEALTH_NOTIFY_COMMAND='/opt/water-tours/ops/readiness/notify-telegram.sh'
# and give that script the destination. It receives the message on stdin.
CURRENT_STATE="ok"
if [ ${#KINDS[@]} -gt 0 ]; then
    CURRENT_STATE="problem: $(printf '%s\n' "${KINDS[@]}" | sort | paste -sd, -)"
fi

PREVIOUS_STATE=""
if [ -f "$STATE_FILE" ]; then PREVIOUS_STATE=$(cat "$STATE_FILE" 2>/dev/null); fi

if [ "$CURRENT_STATE" != "$PREVIOUS_STATE" ]; then
    mkdir -p "$(dirname "$STATE_FILE")" 2>/dev/null
    printf '%s' "$CURRENT_STATE" > "$STATE_FILE" 2>/dev/null
    if [ -n "${HEALTH_NOTIFY_COMMAND:-}" ]; then
        if [ ${#PROBLEMS[@]} -gt 0 ]; then
            printf 'Water Tours: %s\n\n%s\n' "$(printf '%s\n' "${PROBLEMS[@]}" | head -n1)" "$STATUS_LINE" \
                | "$HEALTH_NOTIFY_COMMAND" || echo "$STAMP notify=FAILED"
        else
            printf 'Water Tours: recovered.\n\n%s\n' "$STATUS_LINE" \
                | "$HEALTH_NOTIFY_COMMAND" || echo "$STAMP notify=FAILED"
        fi
    else
        echo "$STAMP state-changed notify=NOT_CONFIGURED (set HEALTH_NOTIFY_COMMAND to enable)"
    fi
fi

# Exit non-zero on a problem so that a cron wrapper, a systemd timer or an external uptime
# check can also act on it without parsing the line above.
[ ${#PROBLEMS[@]} -eq 0 ]

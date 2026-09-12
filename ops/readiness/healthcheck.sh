#!/bin/bash
# Operational readiness check (task 9.4). Checks app health and disk space, writes a
# local log line per run. Does NOT send any external notification (Telegram/email) by
# default — that needs a separate owner decision (see "Включение уведомлений" below).
#
# Suggested cron (production server):
#   */15 * * * * /opt/water-tours/ops/readiness/healthcheck.sh >> /var/log/water-tours-health.log 2>&1

set -euo pipefail

APP_HEALTH_URL="${APP_HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
DISK_WARN_PERCENT="${DISK_WARN_PERCENT:-85}"

STAMP=$(date --iso-8601=seconds)
STATUS_LINE="$STAMP"

HEALTH_JSON=$(curl -fsS "$APP_HEALTH_URL" 2>&1) && HEALTH_OK=1 || HEALTH_OK=0
if [ "$HEALTH_OK" = "1" ]; then
    STATUS_LINE="$STATUS_LINE health=OK"
else
    STATUS_LINE="$STATUS_LINE health=UNREACHABLE"
fi

DISK_USE=$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')
if [ "$DISK_USE" -ge "$DISK_WARN_PERCENT" ]; then
    STATUS_LINE="$STATUS_LINE disk=${DISK_USE}%(WARN)"
else
    STATUS_LINE="$STATUS_LINE disk=${DISK_USE}%"
fi

echo "$STATUS_LINE"

# Включение уведомлений (не сделано автоматически):
# после отдельного разрешения владельца и настройки TELEGRAM_BOT_TOKEN/адресата —
# добавить сюда вызов существующего Telegram API при health=UNREACHABLE или disk warn,
# с ограничением частоты (не слать при каждом запуске, только на изменение статуса).

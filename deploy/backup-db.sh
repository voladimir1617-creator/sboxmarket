#!/bin/bash
# Automated Postgres backup for skinbox.market
# Schedule via cron or Windows Task Scheduler:
#   - Linux: 0 */6 * * * /opt/skinbox/backup-db.sh
#   - Windows: schtasks /create /tn "SkinBox DB Backup" /sc DAILY /st 04:00 /tr "bash deploy/backup-db.sh"
#
# Keeps the last 14 daily backups. Older ones are deleted automatically.

BACKUP_DIR="${BACKUP_DIR:-$HOME/skinbox-backups}"
CONTAINER="${DB_CONTAINER:-sbox-pg}"
DB_USER="${DB_USER:-skinbox}"
DB_NAME="${DB_NAME:-skinbox}"
RETAIN_DAYS=14

mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/skinbox_${TIMESTAMP}.sql.gz"

echo "[$(date)] Starting backup..."
docker exec "$CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_FILE"

if [ $? -eq 0 ] && [ -s "$BACKUP_FILE" ]; then
    SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
    echo "[$(date)] Backup OK: $BACKUP_FILE ($SIZE)"
else
    echo "[$(date)] BACKUP FAILED" >&2
    rm -f "$BACKUP_FILE"
    exit 1
fi

# Prune old backups
find "$BACKUP_DIR" -name "skinbox_*.sql.gz" -mtime +$RETAIN_DAYS -delete
REMAINING=$(ls "$BACKUP_DIR"/skinbox_*.sql.gz 2>/dev/null | wc -l)
echo "[$(date)] Retained $REMAINING backups (pruned >$RETAIN_DAYS days)"

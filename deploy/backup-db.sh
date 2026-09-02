#!/bin/bash
# Automated Postgres backup for skinbox.market
# Schedule via cron or Windows Task Scheduler:
#   - Linux: 0 */6 * * * /opt/skinbox/backup-db.sh
#   - Windows: see deploy/backup-db-hidden.vbs (absolute path, no console window)
#
# Keeps the last 14 daily backups. Older ones are deleted automatically -- but ONLY
# after a new backup has been verified good. See deploy/test-backup-db.sh for why
# that sentence is load-bearing: with Docker stopped this script used to report
# "Backup OK" for a zero-byte file and then prune every real backup on the machine.
# `$?` after `a | b` is b's status, so a dead pg_dump was invisible behind a
# perfectly successful gzip.

BACKUP_DIR="${BACKUP_DIR:-$HOME/skinbox-backups}"
CONTAINER="${DB_CONTAINER:-sbox-pg}"
DB_USER="${DB_USER:-skinbox}"
DB_NAME="${DB_NAME:-skinbox}"
RETAIN_DAYS=14
# A pg_dump of even an empty database emits ~2KB of headers. Anything under this
# is a failure wearing a backup's name.
MIN_UNCOMPRESSED_BYTES=1024

mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/skinbox_${TIMESTAMP}.sql.gz"

fail() {
    echo "[$(date)] BACKUP FAILED: $1" >&2
    echo "[$(date)] retention prune SKIPPED -- existing backups left untouched" >&2
    rm -f "$BACKUP_FILE"
    exit 1
}

echo "[$(date)] Starting backup..."
docker exec "$CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_FILE"
# PIPESTATUS[0] is pg_dump. $? would be gzip, which succeeds even with no input.
DUMP_RC=${PIPESTATUS[0]}

[ "$DUMP_RC" -eq 0 ] || fail "pg_dump exited $DUMP_RC (is the '$CONTAINER' container running?)"
[ -s "$BACKUP_FILE" ] || fail "no output file"

# Read it back. A file that cannot be decompressed is not a backup, and the only
# way to know is to try -- the same reason this repo verifies a service by asking
# it a question rather than by checking that a process exists.
ACTUAL=$(gzip -dc "$BACKUP_FILE" 2>/dev/null | wc -c)
[ "$ACTUAL" -ge "$MIN_UNCOMPRESSED_BYTES" ] \
    || fail "dump is ${ACTUAL} bytes uncompressed, under the ${MIN_UNCOMPRESSED_BYTES}-byte floor"

SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "[$(date)] Backup OK: $BACKUP_FILE ($SIZE, ${ACTUAL} bytes uncompressed)"

# Only now, with a verified backup on disk, is it safe to delete anything.
find "$BACKUP_DIR" -name "skinbox_*.sql.gz" -mtime +$RETAIN_DAYS -delete
REMAINING=$(ls "$BACKUP_DIR"/skinbox_*.sql.gz 2>/dev/null | wc -l)
echo "[$(date)] Retained $REMAINING backups (pruned >$RETAIN_DAYS days)"

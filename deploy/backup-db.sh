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

# A deployment that does not run Postgres at all is a DIFFERENT condition from a dump
# that failed, and until now both looked the same: a bare non-zero and a line in a log
# nobody read. This deployment currently stores its money in H2, so with Docker stopped
# this script had nothing to do and no way to say so -- it failed 150 times from April
# to September while the H2 database it was assumed to be protecting had no backup at
# all. Exit 3 is still non-zero, because a Postgres backup that is not running must
# never read as success; it is simply a code the operator can tell apart from exit 1.
not_applicable() {
    echo "[$(date)] POSTGRES BACKUP NOT APPLICABLE: $1" >&2
    echo "[$(date)] This deployment's live data is H2 at data/sboxmarket.mv.db, not Postgres." >&2
    echo "[$(date)] The scheduled job for that is deploy/h2-backup.ps1, launched via" >&2
    echo "[$(date)]   deploy/h2-backup-hidden.vbs. See deploy/RUNBOOK.md." >&2
    echo "[$(date)] retention prune SKIPPED -- existing backups left untouched" >&2
    exit 3
}

command -v docker >/dev/null 2>&1 || not_applicable "docker is not on PATH"
docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -qx true \
    || not_applicable "the '$CONTAINER' container is not running"

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

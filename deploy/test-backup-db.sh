#!/bin/bash
# Tests for backup-db.sh.
#
# WHY THIS FILE EXISTS. On 2026-09-01 this script was run with Docker stopped.
# `docker exec ... | gzip > file` failed at the FIRST stage, but `$?` reports the
# exit status of the LAST command in a pipeline -- gzip, which happily succeeded
# writing zero bytes. So the script printed "Backup OK", kept a 20-byte file, and
# then ran its 14-day retention prune, which deleted every real backup on the
# machine. Four April dumps were lost.
#
# The scheduled task had been failing with exit 127 since April (empty
# WorkingDirectory, relative path), which is the only reason this had not already
# happened unattended at 04:00 every day. The broken path was protecting the data.
#
# This is the house failure in its most expensive form: a missing signal (a dead
# daemon) rendered as a healthy one (Backup OK), and then acted on destructively.
set -u
SCRIPT="${1:-$(dirname "$0")/backup-db.sh}"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   - $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL - $1"; }

newsandbox() {
  SANDBOX=$(mktemp -d); STUB="$SANDBOX/bin"; mkdir -p "$STUB"
  OLD="$SANDBOX/skinbox_20200101_000000.sql.gz"
  printf 'pretend this is a real old backup' | gzip > "$OLD"
  touch -d '30 days ago' "$OLD"
}

run() { PATH="$STUB:$PATH" BACKUP_DIR="$SANDBOX" bash "$SCRIPT" >"$SANDBOX/out" 2>&1; echo $?; }

# ---------------------------------------------------------------- the incident
echo "when the dump fails (docker down):"
newsandbox
cat > "$STUB/docker" <<'STUB'
#!/bin/bash
echo "cannot connect to the docker daemon" >&2
exit 1
STUB
chmod +x "$STUB/docker"
RC=$(run)

[ "$RC" != "0" ] && ok "exits non-zero so the scheduler records a failure" \
                 || bad "exited 0 on a failed dump (rc=$RC)"
grep -qi "backup ok" "$SANDBOX/out" && bad "printed 'Backup OK' after a failed dump" \
                                    || ok "does not claim OK after a failed dump"
[ -f "$OLD" ] && ok "the pre-existing backup SURVIVED (no prune after failure)" \
              || bad "THE INCIDENT: pruned real backups after a failed dump"
[ -z "$(find "$SANDBOX" -name 'skinbox_2026*.sql.gz' 2>/dev/null)" ] \
    && ok "kept no empty artefact" || bad "kept an empty backup file"
rm -rf "$SANDBOX"

# --------------------------------------------------- an empty but 'successful' dump
echo "when the dump succeeds but produces nothing:"
newsandbox
cat > "$STUB/docker" <<'STUB'
#!/bin/bash
exit 0
STUB
chmod +x "$STUB/docker"
RC=$(run)
[ "$RC" != "0" ] && ok "an empty dump is a failure, not a backup" \
                 || bad "accepted a zero-byte dump as a backup"
[ -f "$OLD" ] && ok "the pre-existing backup SURVIVED" || bad "pruned on an empty dump"
rm -rf "$SANDBOX"

# ------------------------------------------------------------------- happy path
echo "when the dump genuinely works:"
newsandbox
cat > "$STUB/docker" <<'STUB'
#!/bin/bash
# a plausible pg_dump: needs to exceed the minimum-size floor
echo "--"
echo "-- PostgreSQL database dump"
echo "--"
for i in $(seq 1 400); do echo "INSERT INTO listings VALUES ($i, 'skin', 1234);"; done
STUB
chmod +x "$STUB/docker"
RC=$(run)
[ "$RC" = "0" ] && ok "succeeds on a real dump" || bad "rejected a valid dump (rc=$RC)"
NEW=$(find "$SANDBOX" -name 'skinbox_2*.sql.gz' ! -name 'skinbox_2020*' 2>/dev/null | head -1)
[ -n "$NEW" ] && ok "wrote a backup" || bad "wrote no backup on the happy path"
[ -n "$NEW" ] && [ "$(zcat "$NEW" 2>/dev/null | wc -c)" -gt 100 ] \
    && ok "the backup has real content" || bad "backup is empty"
grep -qi "backup ok" "$SANDBOX/out" && ok "reports success" || bad "did not report success"
[ ! -f "$OLD" ] && ok "prunes old backups once a good one exists" \
                || bad "retention never runs, so backups accumulate forever"
rm -rf "$SANDBOX"

echo ""
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]

# sboxmarket Operator Runbook

One page. Paste-ready commands. If you are reading this during an outage, scroll to the matching section, run the bullets in order, then come back and update the runbook with what worked.

The deploy script (`deploy/run-local.sh`) ships a **cookie-state gate** that replays poisoned-cookie shapes after each deploy and aborts on any 5xx (added after the four NUL-byte outages on 2026-05-03). **Do not bypass this gate.** If it fails, the build is broken — revert, do not push past it.

Cloudflare uptime monitor should target `GET /api/health/cookie-aware` on the public hostname — that endpoint runs synthetic SELECTs on `SPRING_SESSION` with both a known-good UUID and a known-poisoned UUID and returns 503 the moment either throws.

---

## Site is 500

Symptom: every page (or every authed page) returns HTTP 500. Tomcat may be falling through to its raw stub error page.

```bash
# 1. What just shipped?
git log --oneline -5

# 2. Look for the tell-tale exception classes from the last 5 minutes.
docker logs sbox-app --since 5m | grep -E '0x00|PSQLException|DataIntegrityViolationException|OUTAGE-SIGNAL'

# 3. Probe the cookie-aware healthcheck. 503 = session/DB poisoned, 200 = it's something else.
curl -sS -o /dev/stdout -w '\nHTTP %{http_code}\n' http://localhost:8082/api/health/cookie-aware

# 4. Revert the most recent commit and redeploy.
git revert <SHA> --no-edit && git push
docker build -t sbox-app:latest . && bash deploy/run-local.sh

# 5. If revert isn't an option, full container restart wipes Tomcat in-memory sessions
#    (acceptable — every user re-logs in via Steam OpenID).
docker stop sbox-app && docker start sbox-app
```

---

## Site is 502

Symptom: Cloudflare returns 502 Bad Gateway. The container is down or not responding to the tunnel.

```bash
# 1. Is the container running?
docker ps --filter name=sbox-app --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

# 2. Did it crash? Tail the last 200 lines, looking for OOM / startup failure / port bind errors.
docker logs sbox-app --tail 200

# 3. Bring it back up (graceful shutdown takes up to 25s on prod profile).
docker stop sbox-app && docker start sbox-app

# 4. If `docker start` immediately exits, the image itself is broken — rebuild from last known good.
git log --oneline -10
git checkout <last-known-good-SHA>
docker build -t sbox-app:latest . && bash deploy/run-local.sh

# 5. Verify the cookie-state gate at the end of run-local.sh passed before reopening traffic.
```

---

## Database is full

Symptom: writes start failing with `PSQLException` mentioning `disk full` or `could not extend file`. The OUTAGE-SIGNAL filter will be hot.

```bash
# 1. Check Postgres disk usage + the largest tables.
docker exec sbox-pg psql -U skinbox -d skinbox -c "SELECT pg_size_pretty(pg_database_size('skinbox'));"
docker exec sbox-pg psql -U skinbox -d skinbox -c "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;"

# 2. The classic culprit: SPRING_SESSION orphans. The cleanup-cron should keep this tidy
#    but a stuck cron leaves rows. Drop expired rows by hand:
docker exec sbox-pg psql -U skinbox -d skinbox -c "DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME < EXTRACT(EPOCH FROM NOW()) * 1000;"

# 3. VACUUM FULL recovers the space (locks the table — schedule a 30s window).
docker exec sbox-pg psql -U skinbox -d skinbox -c "VACUUM FULL SPRING_SESSION;"

# 4. Take a backup BEFORE doing anything destructive on app tables.
bash deploy/backup-db.sh
```

---

## Disk is full

Symptom: `docker logs sbox-app` won't read, `docker build` fails with `no space left on device`, log rotation stops.

```bash
# 1. What's eating the disk?
df -h /
du -sh /var/lib/docker /var/log/skinbox 2>/dev/null

# 2. Logback rotates skinbox.log to ~/var/log/skinbox/. If a prod incident hammered
#    OUTAGE-SIGNAL the .gz files can balloon — the policy caps total at 2GB but only
#    after rotation. Check + nuke old rotated logs by hand if needed.
ls -lhS /var/log/skinbox/ | head -20

# 3. Prune dead Docker artifacts (images, containers, build cache).
docker system prune -af --volumes

# 4. If still full, the most aggressive safe step is dropping the build cache.
docker builder prune -af
```

---

## Worker shipped a regression

Symptom: design / behaviour change in the last commit broke something. The cookie-state gate may or may not have caught it (gate covers cookie shapes, not visual regressions).

```bash
# 1. Confirm the last commit and read the diff.
git log --oneline -5
git show HEAD --stat

# 2. Single-commit revert is the standard recovery — creates a NEW commit that undoes the
#    regression. Don't amend, don't force-push.
git revert HEAD --no-edit

# 3. Redeploy. The cookie-state gate at the end of run-local.sh will replay poisoned-cookie
#    shapes against the rebuilt image and abort if any of them produces a 5xx.
docker build -t sbox-app:latest . && bash deploy/run-local.sh

# 4. Confirm the cookie-aware healthcheck is green AFTER the gate passes.
curl -sS http://localhost:8082/api/health/cookie-aware

# 5. If the regression is older than HEAD, walk back through the commits and revert the
#    actual culprit (the revert is per-commit, not per-merge):
git log --oneline -20
git revert <bad-SHA> --no-edit
```

---

## Reference: the cookie-state deploy gate

`deploy/run-local.sh` runs a post-boot probe sequence that hits the live container with the cookie shapes that historically detonated `JdbcIndexedSessionRepository.findById`'s SELECT on `SPRING_SESSION`. Every probe must return < 500 or the script exits non-zero — meaning the deploy fails in the operator's terminal and traffic never sees the broken image.

The probes:
- `SBOX_SESSION=garbage` (random non-UUID)
- `SBOX_SESSION=11111111-2222-3333-4444-555555555555` (stale but well-formed UUID)
- `SBOX_SESSION=` (empty)
- 512-char overlong cookie
- NUL-byte cookie sent via raw TCP (the original killer)

Probed paths: `/`, `/market`, `/wallet`. If a future operator adds a new authed-only page that 500s on a poisoned cookie, add it to the `for path in ...` loop in `run-local.sh`.

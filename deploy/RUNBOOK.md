# sboxmarket Operator Runbook

One page. Paste-ready commands. If you are reading this during an outage, scroll to the matching section, run the bullets in order, then come back and update the runbook with what worked.

The deploy script (`deploy/run-local.sh`) ships a **cookie-state gate** that replays poisoned-cookie shapes after each deploy and aborts on any 5xx (added after the four NUL-byte outages on 2026-05-03). **Do not bypass this gate.** If it fails, the build is broken — revert, do not push past it. The gate now runs against the staging container BEFORE the upstream swap (added 2026-05-03 zero-downtime work) — a failed gate never reaches public traffic.

Cloudflare uptime monitor should target `GET /api/health/cookie-aware` on the public hostname — that endpoint runs synthetic SELECTs on `SPRING_SESSION` with both a known-good UUID and a known-poisoned UUID and returns 503 the moment either throws.

## Topology (zero-downtime, post-2026-05-03)

```
Cloudflare tunnel (cloudflared on host)
      |
      v
  localhost:8082  --->  sbox-edge   (nginx, never restarted across deploys)
                            |
                            v   (Docker DNS over the sbox-net network)
                       sbox_upstream
                            |
                            +--> sbox-app          (canonical, currently active)
                            and during a deploy:
                            +--> sbox-app-blue     (the staging target)
```

- **sbox-edge** is an `nginx:alpine` container that owns host port 8082 and forwards to whichever app container is currently in its upstream block. It survives every deploy — only its `edge-upstream.conf` (volume-mounted from `deploy/edge-upstream.conf`) is rewritten and `nginx -s reload`'d.
- **sbox-app** is the always-canonical name of the active app container. Deploys boot a parallel `sbox-app-blue` container, validate it (cookie-state gate against the blue container, before any traffic reaches it), then swap the upstream and rename blue to sbox-app.
- During the swap window the upstream block lists BOTH containers (`sbox-app-blue` primary, `sbox-app` backup) so requests in flight have a fallback. Verified zero non-200 responses across two consecutive deploys (2026-05-03 instrumentation: `while true; do curl -w "%{http_code}\n"; sleep 1; done`).
- `deploy/nginx.conf` (the OLD reference config, now corrected to the app's real port 8082 — it had proxied to 8080, which nothing has ever listened on) is now historical — it documents how the app fronted directly with nginx on a single host. It is NOT in the live tunnel path. The live edge config is `deploy/edge-nginx.conf` mounted into the `sbox-edge` container.

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

Symptom: Cloudflare returns 502 Bad Gateway. The container is down, the edge can't reach it, or the edge itself is down.

```bash
# 1. Is the edge running? (nginx that owns host :8082)
docker ps --filter name=sbox-edge --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

# 2. Is the app container running on sbox-net?
docker ps --filter name=sbox-app --format 'table {{.Names}}\t{{.Status}}\t{{.Networks}}'

# 3. Probe the edge directly — bypasses Cloudflare so you can isolate edge vs app.
curl -sS -o /dev/null -w "edge=%{http_code}\n" http://localhost:8082/__edge_health   # 200 if edge up
curl -sS -o /dev/null -w "app=%{http_code}\n"  http://localhost:8082/api/health      # 200 if edge -> app works

# 4. Did the app crash? Tail the last 200 lines, looking for OOM / startup failure / port bind errors.
docker logs sbox-app --tail 200

# 5. If only the app is down: re-run the deploy script to bring it back up zero-downtime.
docker build -t sbox-app:latest . && bash deploy/run-local.sh

# 6. If the edge itself is down: just restart it. Edge state is config-only (no app data).
docker start sbox-edge   # if it exists but is stopped
# OR — if it's gone — re-run run-local.sh; the script idempotently recreates the edge.

# 7. Verify the cookie-state gate at the end of run-local.sh passed before reopening traffic.
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

## Reference: edge gzip + static-asset caching (added 2026-05-03)

`deploy/edge-nginx.conf` enables gzip and static-asset cache headers at the edge. Behaviour:

- `gzip on; gzip_comp_level 5; gzip_min_length 1024;` over text/css, text/javascript, application/javascript, application/json, image/svg+xml, fonts (no `text/html` — that's gzipped by default and explicit listing trips a duplicate-MIME warning at config-test).
- `location ~* ^/(css|js|img|font)/` block strips Spring Boot's `Cache-Control: no-cache, must-revalidate` (default for static resources) and replaces with `public, max-age=300, must-revalidate`.

**Why 5 minutes, not 1 year immutable.** The app does NOT fingerprint static URLs — `index.html` references `/css/design.css` directly, and the operator iterates aggressively. A 1-year `immutable` would force a hard reload to see CSS changes; a 5-minute cache + revalidate trades a ~5min staleness window for instant deploy visibility. Conditional revalidations return 304 via Spring's Last-Modified handling — they're cheap.

If you want longer caching, change the `add_header Cache-Control` value in `edge-nginx.conf`. If you want true zero-staleness rollouts, the right move is to add a fingerprint query (`?v=<git-sha>`) to the script tags in `index.html` (out of scope for this audit — would require touching app code).

**Headline win (2026-05-03 baseline -> after):**
```
                       BEFORE        AFTER (gzip)   reduction
/css/design.css        3,376,350 B   507,397 B      85.0%
/js/app.js               461,747 B   124,819 B      73.0%
/js/modals.js            770,016 B   196,748 B      74.4%
```

Cloudflare in front already gzipped public responses, so end-users saw the compressed size before this change. The win is on the **nginx → Cloudflare** hop and on **direct probes** (monitoring, localhost, future workers that bypass CF).

**Known dead-CSS:** `design.css` defines ~10,585 unique class selectors; only ~1,176 (~11%) appear as whole-word tokens in any `.html` or `.js` source under `src/main/resources/static/`. ~9,409 candidate-dead classes. **DO NOT mass-delete without sign-off** — the heuristic doesn't account for dynamic class assembly (`'btn-' + variant`) and would false-positive on those.

---

## Reference: the cookie-state deploy gate

`deploy/run-local.sh` runs a probe sequence against the BLUE container (the deploy target, before the upstream swap) with the cookie shapes that historically detonated `JdbcIndexedSessionRepository.findById`'s SELECT on `SPRING_SESSION`. Every probe must return < 500 or the script exits non-zero — meaning the deploy fails in the operator's terminal and traffic NEVER sees the broken image (the old container keeps serving).

The probes:
- `SBOX_SESSION=garbage` (random non-UUID)
- `SBOX_SESSION=11111111-2222-3333-4444-555555555555` (stale but well-formed UUID)
- `SBOX_SESSION=` (empty)
- 512-char overlong cookie
- NUL-byte cookie sent via raw TCP (the original killer)

Probed paths: `/`, `/market`, `/wallet`. If a future operator adds a new authed-only page that 500s on a poisoned cookie, add it to the `for path in ...` loop in `run-local.sh`.

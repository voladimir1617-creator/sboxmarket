# sboxmarket Operator Runbook

One page. Paste-ready commands. If you are reading this during an outage, scroll to the matching section, run the bullets in order, then come back and update the runbook with what worked.

---

# GO LIVE — bringing skinbox.market up safely

**The domain is deliberately DARK and must stay dark until step 6 passes. Do not start cloudflared before then.**

## Why it is dark

Not the tunnel. The JVM that serves port 8082 runs the **default** profile with no Stripe
env, so `stripe.secret-key` falls back to `sk_test_replace_me`, `StripeService.isLive()`
is false, and `LiveMoneyGuard.isRealMoney()` is false. That unlocks the dev scaffolding:

- `GET /api/auth/steam/dev-login` mints a valid `SBOX_SESSION` for any user id, **with no
  credential of any kind** — total account takeover of every account, admins included.
- `devModeDeposit` credits up to **$5,000 / 24h against no payment**.

Both were reachable on the public domain on 2026-08-31 and closed by taking the domain down.
Publishing the tunnel again without the prod profile re-opens both.

> **A 404 from dev-login is NOT proof the door is shut.** The controller has more than one
> 404. On 2026-09-01 the live endpoint answered 404 **through the post-guard branch** — the
> guard had already been passed, and the only reason nothing was minted is that no user
> carried the id asked for. Always read the body. Pinned by `ProdScaffoldingUnreachableSpec`.
>
> As of 2026-09-01 every guard 404 carries a body beginning `dev-login disabled:`, so a shut
> door emits a signal of its own. `{"error":"no seed users"}` still means the guard was
> passed; an **empty** body means neither branch answered (wrong path, wrong host, dead
> server) and proves nothing.

> **The door is now closed by default, and that changes what "unlocks the dev scaffolding"
> means above.** SIMULATED mode no longer authorises `dev-login` on its own — it never was
> an authorisation, only a statement about money, and the state described above is what that
> confusion looked like in production. The endpoint now needs BOTH: no real money AND an
> affirmative `SBOX_DEV_LOGIN_ENABLED=true` in the server's **process environment**. See
> `config/DevLoginGate.groovy`.
>
> That variable is read from the process environment and nowhere else — not `application.yml`,
> not a profile, not a `-D` flag — precisely so it cannot be committed once and then inherited
> by a deployment that never decided to have it. Do not add it to any config file, the
> compose file, the Dockerfile or `skinbox.env.example`; a spec fails if you do.
>
> The refusals are tellable apart: `{"error":"dev-login disabled: real-money deployment"}`
> means the money classification shut it (LIVE or INDETERMINATE), and
> `{"error":"dev-login disabled: no SBOX_DEV_LOGIN_ENABLED=true opt-in in the process
> environment"}` means nobody asked. Only the first is a statement about production.
>
> **The free-credit door behind it is now closed the same way.** Closing `dev-login` shut the
> shortcut *into* an account. It did not touch `devModeDeposit`, which credits up to **$5,000
> per wallet per rolling 24 hours against no payment** whenever the deployment is SIMULATED —
> and Steam sign-in is not a shortcut, it is the real front door, open to everyone. So on a
> published SIMULATED origin a stranger signed in normally and credited themselves, with every
> takeover door correctly shut behind them.
>
> As of this change the fabricated credit needs BOTH: affirmatively SIMULATED AND
> `SBOX_DEV_CREDIT_ENABLED=true` in the server's **process environment**. Same channel, same
> fail-closed default, same "cannot be committed" property — see `config/DevCreditGate.groovy`.
> It is a **different variable** from `SBOX_DEV_LOGIN_ENABLED` on purpose: the e2e suite and
> every QA harness set the login one, and a harness that needs a session has not thereby asked
> for a money printer.
>
> The same gate covers the simulated Stripe Connect onboarding, which fabricates a `dev_acct_`
> payout reference on the same "nobody configured Stripe here" reasoning.
>
> Both refusals are tellable apart and both carry the `dev-credit disabled:` prefix:
> `dev-credit disabled: LIVE deployment — only SIMULATED may fabricate money-path state`
> (the mode shut it) versus `dev-credit disabled: no SBOX_DEV_CREDIT_ENABLED=true opt-in in
> the process environment` (nobody asked). On the wire the deposit endpoint answers `400`
> with code `DEV_CREDIT_NOT_AUTHORIZED`.
>
> **A fresh checkout, run with no special environment, credits nothing and mints no session.**

## What actually runs today (measured 2026-09-01, not assumed)

| Thing | Reality |
|---|---|
| Serving `localhost:8082` | A **host-native JVM**, PID varies, started by hand. Not Docker. |
| Its profile | **default** — the vulnerable one. |
| Its database | **H2 file** `./data/sboxmarket.mv.db` — actively written. |
| `sbox-app` container | Ghost. **No host port binding.** Runs a jar baked 2026-05-05 (~4 months stale). |
| `sbox-edge` container | Ghost. Listens on 8082 *inside* the container; Docker never published it. |
| `sbox-pg` container | Real, publishes `0.0.0.0:5433`. DB `skinbox` is **26 migrations behind** (v61, last touched 2026-05-05). |
| Public domain | Down. `cloudflared` service runs with bare argv and reads a config containing only `logDirectory:`, so the named tunnel never runs. |

## Docker: which runtime is real? (verdict — read before deploying)

There are **three** deployment stories in this repo and only one of them has ever served a
request. Running two at once is how the four-month-stale-image confusion happened.

1. **`docker-compose.yml`** — builds the app from the Dockerfile, bakes
   `SPRING_PROFILES_ACTIVE=prod`, owns its own Postgres, and **fails closed** if
   `STRIPE_SECRET_KEY` is unset. Actively maintained (fixed 2026-08-31).
2. **`deploy/run-local.sh`** — a blue-green `docker run` stack (`sbox-app` / `sbox-edge` /
   `sbox-pg`) with an nginx edge. **This is what created the ghosts.** The running
   containers carry **no compose labels**, so they came from this script, not compose.
3. **A host-native JVM** started by hand. **This is what actually serves port 8082.**

**Verdict: compose is the intended runtime; the `run-local.sh` blue-green stack is a dead
end and its containers are ghosts.** Evidence for calling them ghosts:

- `sbox-app` — `NetworkSettings.Ports` is `{"8080/tcp":null}`. No host binding. It runs a
  jar baked into the image on **2026-05-05**, so it cannot pick up any change on disk.
- `sbox-edge` — declares 8082 but `NetworkSettings.Ports` is `{"8082/tcp":[]}`, i.e.
  never published. Inside it, stock `default.conf` still serves the nginx welcome page on
  :80, and the mounted `edge-upstream.conf` points at `sbox-app:8082` — which nothing
  reaches from the host anyway.
- Only `sbox-pg` binds a host port (`0.0.0.0:5433`), and its `skinbox` database is the
  abandoned 2026-05-05 snapshot.

**Consequence you must handle before step 1:** compose's `db` service also publishes
`5433:5432`, and its `app` publishes `8082`. Both collide with the ghosts and with the host
JVM. Remove the ghosts first:

```bash
docker rm -f sbox-app sbox-edge          # keep sbox-pg only if you still want its data
```

If you go with compose, remember it must be started as
`docker compose --env-file deploy/skinbox.env up -d` — a bare `docker compose up` does
**not** substitute `${VAR}` from an `env_file:` entry and will fail on `STRIPE_SECRET_KEY`.

Everything below the "GO LIVE" block describing an `sbox-edge` → `sbox-app` zero-downtime
topology is **historical**. It is not the live path and has not been for months.

## Does the prod profile boot? Yes.

Verified 2026-09-01 against a throwaway Postgres database: **all 87 Flyway migrations
applied, JPA validated, `Started SboxMarketApplication in 14.9s`**, and the only error in
the entire boot was `ProdConfigValidator` correctly refusing the three placeholder Stripe
values. **Nothing else blocks prod.** What remains is the three real Stripe keys and a
Postgres database with the current schema.

---

## The deploy sequence

### 1. Provision Postgres and pick the database

The prod profile **requires** Postgres — `SPRING_DATASOURCE_URL` has no default and the 87
migrations are Postgres-specific SQL. The live data is currently in H2 and **does not move
by itself.**

Decide explicitly, because this is a data decision, not a config one:

- **Start clean** (recommended if the H2 contents are demo/test data): create a fresh
  database and let Flyway build all 87 migrations into it.
  ```bash
  docker exec sbox-pg psql -U skinbox -d postgres -c "CREATE DATABASE skinbox_prod;"
  ```
- **Carry the H2 data over**: that is an export/import job (H2 → Postgres) and is **not**
  covered here. Do not assume pointing at the existing `skinbox` database preserves
  anything — it holds an unrelated 2026-05-05 snapshot (2 users, 42 listings, 3 wallets)
  and is 26 migrations behind.

Do **not** reuse the old `skinbox` database without deciding what happens to those rows.

### 2. Fill `deploy/skinbox.env`

Copy the template and fill it in. **This file is never committed and is not created for you.**

```bash
cp deploy/skinbox.env.example deploy/skinbox.env
```

Every `CHANGE_ME` must be replaced. The ones that block boot, and what each must look like:

| Variable | Must be |
|---|---|
| `STRIPE_SECRET_KEY` | a real `sk_live_…` or `rk_live_…` (restricted keys are accepted and preferred) |
| `STRIPE_PUBLISHABLE_KEY` | a real `pk_live_…` |
| `STRIPE_WEBHOOK_SECRET` | a real `whsec_…` from the Stripe **webhook endpoint** page |
| `SPRING_DATASOURCE_URL` | the database from step 1, e.g. `jdbc:postgresql://localhost:5433/skinbox_prod` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | real credentials |
| `APP_UNSUBSCRIBE_SECRET` | `openssl rand -base64 48` — never the committed placeholder |
| `APP_PUBLIC_URL` | `https://skinbox.market` — **must not contain `localhost`** |
| `CORS_ALLOWED_ORIGINS`, `ADMIN_BOOTSTRAP_STEAM_IDS`, `STRIPE_SUCCESS_URL`, `STRIPE_CANCEL_URL`, `STEAM_REALM`, `STEAM_RETURN_URL` | already correct in the template |

**Do not invent a placeholder to "get past" a startup error.** A plausible `sk_live_` value
flips the service into live mode, where it behaves as though payments work right up until
the first real charge. The validator refuses every shape it can detect locally — including
a truncated key, the publishable key pasted into the secret slot, `whsec_dummy`, and any
value containing `replace_me`. Those refusals are the control working. The only fix is the
real key.

`chmod 600 deploy/skinbox.env` — it holds live payment credentials.

### 3. Stop the current default-profile JVM

`pkill -f` does nothing on Windows. Use the PID that owns the port:

```powershell
$pid = (Get-NetTCPConnection -LocalPort 8082 -State Listen).OwningProcess
Get-CimInstance Win32_Process -Filter "ProcessId=$pid" | Select-Object ProcessId, CommandLine
Stop-Process -Id $pid -Force
```

Confirm nothing is left listening before continuing — two JVMs on one port is how the
"restart changed nothing" confusion starts:

```powershell
netstat -ano | Select-String ":8082"   # expect no output
```

### 4. Build

```bash
./gradlew.bat clean build -x test    # or `build` to run the suite too
```

### 5. Start under the prod profile

Load the env file into the session, then launch:

```powershell
Get-Content deploy\skinbox.env | Where-Object { $_ -match '^\s*[^#\s].*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim()
}
$env:SPRING_PROFILES_ACTIVE = 'prod'
java -jar build\libs\sboxmarket-1.0.0.jar
```

**Note the launch shape change.** The JVM serving today runs

```
java -Dloader.path=build/classes/groovy/main,build/resources/main -cp build/libs/sboxmarket-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

which puts `build/classes/groovy/main` and `build/resources/main` **first** on the
classpath — so static assets are read live off disk and edits appear without a restart,
while **classes are still frozen at JVM start**. That split is what made a fix look shipped
when it wasn't. Under plain `java -jar` neither is live: everything comes from inside the
jar, and any change — static or class — needs step 4 and a restart.

**Changing the launch line means changing TWO lines, not one.** `skinbox-watchdog.ps1`
decides whether the server is already up with

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*PropertiesLauncher*' }
```

and a `java -jar` process does not contain that string. Measured 2026-09-01 with BOTH
shapes running at once - the current instance on 8082 and a jar-only instance on 8099 -
that predicate matched only the PropertiesLauncher one. So switching the launch line
alone leaves the watchdog permanently blind to the server it just started: every tick
where the health probe is slow or briefly fails, it concludes "no process, not healthy"
and starts ANOTHER one. That is the duplicate-daemon failure this repo has already paid
for once. Match on the artefact instead of the launcher -

```powershell
Where-Object { $_.CommandLine -like '*sboxmarket-1.0.0.jar*' }
```

- which matched both shapes in the same measurement, so it is also correct DURING the
switch rather than only after it.

**One more consequence, in the other direction.** `-Dloader.path=build/classes/groovy/main`
makes that directory part of the running JVM's classpath, and `./gradlew` rewrites it.
So today any build - a test run included - edits the live process's classpath underneath
it. Under `java -jar` the running JVM holds only the jar it started from, and a build
touches nothing it is using.

**The bind address.** `application.yml` now sets `server.address: ${SERVER_ADDRESS:127.0.0.1}`,
so the default profile binds **loopback only**. Before that it bound the wildcard, and on
2026-09-01 the running instance answered `200` — and `/api/auth/steam/dev-login` answered
`302` with a live session — on `192.168.68.70` (house LAN), `100.82.162.44` (Tailscale, i.e.
every device the operator owns) and `172.29.80.1` (WSL vSwitch). There is no inbound firewall
rule for 8082, so nothing else was stopping it.

That 302 was *correct by the rules of the day*: with no Stripe key the deployment is
`MoneyMode.SIMULATED` and dev-login was supposed to work. The whole SIMULATED contract just
assumed nobody but the developer could reach the port, and the wildcard bind quietly broke
that assumption. The socket fix restores reachability — but it was the *only* layer, and a
Cloudflare tunnel connects **from** loopback, so `skinbox.market -> http://localhost:8082`
walks past it. So the door itself is now closed by default too: it takes
`SBOX_DEV_LOGIN_ENABLED=true` in the process environment. A fresh checkout, run with no
special environment, serves no credential-free login on any address.

`prod` overrides to `0.0.0.0` — a container MUST bind the wildcard or Docker's published
`${APP_PORT:-8082}:8082` has nothing to forward to. For a deliberate LAN demo on the default
profile, set `SERVER_ADDRESS=0.0.0.0` for that run only, and know what you are publishing.

The tunnel is unaffected: cloudflared runs on this same host and its ingress rule already
targets `http://localhost:8082`, so loopback is reachable to it and it stays the only public
path — exposure becomes something the tunnel grants, not something the socket leaks. The
`SkinBox Watchdog` scheduled task probes `http://localhost:8082/api/health`, also loopback.

### 6. VERIFY prod is really active — the gate

Do not proceed to step 7 until all four pass.

```bash
# a) The validator announces itself. This line only exists under the prod profile.
grep "Prod config validation passed" /var/log/skinbox/skinbox.log
#    -> "Prod config validation passed: all 14 required secrets present..."
#    If instead the process EXITED with "FATAL: production config validation failed",
#    read the list — it names every bad variable at once. Fix and restart.

# b) dev-login is dead — and check the BODY, not just the status.
curl -s -i http://localhost:8082/api/auth/steam/dev-login | head -1
curl -s    http://localhost:8082/api/auth/steam/dev-login
#    -> {"error":"dev-login disabled: real-money deployment"}  = PASS. The guard
#       answered, and it answered on MONEY, which is what prod must report.
#    -> {"error":"dev-login disabled: no SBOX_DEV_LOGIN_ENABLED=true opt-in ..."}
#       = the door is shut, but NOT because this box knows it is production.
#       Safe, and still a FAIL for step 6: on a prod box the money classifier
#       should be the thing refusing. Check STRIPE_SECRET_KEY and the profile.
#    -> {"error":"no seed users"}  = FAIL. The guard was PASSED and the only
#       reason nothing was minted is that no user carried that id. Stop.
#    -> an EMPTY 404 body = FAIL, or at least "unproven". It used to be the pass
#       condition, and it is the same response a missing route, a typo'd path or
#       a dead server gives. Never accept an absence as proof.

# c) The app is up.
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/api/health   # 200

# c2) The socket is bound where you think it is. Read the ADDRESS, not the port.
#     PowerShell:
#       Get-NetTCPConnection -LocalPort 8082 -State Listen | Select LocalAddress,OwningProcess
#     -> LocalAddress 127.0.0.1  = loopback only (the default-profile expectation)
#     -> LocalAddress ::         = EVERY interface, including the LAN and the tailnet
#
#     Then prove it against a real interface, and PAIR the two probes — a refusal on
#     its own is indistinguishable from a dead server, which is the trap this whole
#     runbook exists to avoid:
#       curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8082/api/health    # 200
#       curl -s -m 5 http://<your-lan-ip>:8082/api/health                            # refused
#     A 200 on loopback in the same breath as a refusal off-box is the positive signal.

# d) Deposits go to Stripe, not to the dev credit path.
#    In the Wallet UI, a deposit must redirect to a checkout.stripe.com URL.
#    A response with "live": false and a "dev_…" reference means devModeDeposit ran.
#    A 400 with code STRIPE_MODE_INDETERMINATE means the secret key is set to
#    something the app cannot classify as either live or unconfigured — it is
#    REFUSING rather than falling back to the free-credit path. Fix the key.
```

### 7. Only then: fix the cloudflared service

**Requires an administrator shell.** The service currently runs with bare argv — no
`tunnel run`, no `--config` — and reads a config containing only `logDirectory:`, so the
named tunnel never registers. The operator's own `~/.cloudflared/config.yml` is correct and
nothing consumes it. **Nothing is needed in the Cloudflare dashboard.**

```
cloudflared service uninstall
cloudflared --config C:\Users\WW\.cloudflared\config.yml service install
```

Then confirm the tunnel registered connections, and only then check the public hostname.
If the domain returns 530 / `error code: 1033`, that is "no tunnel connection registered" —
the service, not the app.

---


The deploy script (`deploy/run-local.sh`) ships a **cookie-state gate** that replays poisoned-cookie shapes after each deploy and aborts on any 5xx (added after the four NUL-byte outages on 2026-05-03). **Do not bypass this gate.** If it fails, the build is broken — revert, do not push past it. The gate now runs against the staging container BEFORE the upstream swap (added 2026-05-03 zero-downtime work) — a failed gate never reaches public traffic.

Cloudflare uptime monitor should target `GET /api/health/cookie-aware` on the public hostname — that endpoint runs synthetic SELECTs on `SPRING_SESSION` with both a known-good UUID and a known-poisoned UUID and returns 503 the moment either throws.

## Topology (HISTORICAL — not the live path)

> **This section describes the `run-local.sh` blue-green stack, which no longer serves
> anything.** Verified 2026-09-01: `sbox-app` and `sbox-edge` hold no host ports, and a
> host-native JVM answers `localhost:8082`. It also assumes Postgres, while the live app
> runs H2. Kept for reference only — see the GO LIVE block above for what is real. The
> `docker logs sbox-app` / `docker exec sbox-pg` commands in the incident sections below
> will not reflect the running app.

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

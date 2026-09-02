# sboxmarket Operator Runbook

One page. Paste-ready commands. If you are reading this during an outage, scroll to the matching section, run the bullets in order, then come back and update the runbook with what worked.

> **Pending right now:** three committed controls — the money reset, the H2
> credential guard, and the unconfigured-admin reporter — are **not in the
> running jar**. They ship together in one ~30-second restart. See
> **"ONE RESTART: shipping all three pending controls together"** below.
> Rehearsed end to end on a restored copy, 2026-09-02.

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

## Revoking a persisted admin

### What is on the box right now (measured 2026-09-02, live database)

`STEAM_USERS` holds **7 rows, exactly one with `ROLE <> 'USER'`**:

| id | role | steamId64 | note |
|----|------|-----------|------|
| 1–6 | USER | `76561199000000001`–`…006` | the fabricated `SeedService` sellers |
| 33 | **ADMIN** | `76561199839805014` | the only real account in the database |

`admin.bootstrap-steam-ids` resolves **empty**, and `ADMIN_BOOTSTRAP_STEAM_IDS` is set
in neither the User nor the Machine environment. So the grant has **outlived the
configuration that created it** — exactly the limitation
`AdminBootstrapIsNotCommittedSpec` states in its own last test ("removing the default
does NOT demote an existing admin"). It is now reported rather than assumed: see
`UnconfiguredAdminReporter`, which logs a WARN banner on every boot and answers
`GET /api/admin/security/admin-grants`.

**Whose account is it.** `76561199839805014` was committed to `application.yml` as the
bootstrap admin default in `05577b6`, the **initial commit**, authored by
`voladimir1617-creator <voladimir1617@gmail.com>` on 2026-04-15 — the operator, naming
his own Steam ID. The live Steam profile is public, established (level 55, 15 badges,
1,086 items), and its persona matches the `DISPLAY_NAME` on the row; the persona
recorded on this user's earliest sign-in audit row was `Chib skinbox.market`, carrying
this project's own domain. **This is the operator's own account, not an intruder.**
The residual limit is worth stating: none of that proves the Steam account is still
under his control today, only that he designated it.

**It was, however, invisible.** The audit log holds **zero** `ADMIN_GRANTED` rows for
user 33 — only four `USER_SIGN_IN` rows — because the promotion predates the audit
write added on 2026-09-01. Until now the most privileged row in the database was
attested by nothing but itself.

### Revoke is BLOCKED right now — read this before trying

With a single ADMIN row, `AdminService.revokeAdmin` refuses **twice over**:

- `CANT_REVOKE_SELF` — the only admin is necessarily the target, and self-revoke is refused.
- `LAST_ADMIN` — `countByRole('ADMIN') <= 1`, and the lockout guard refuses to demote
  the final admin.

There is no caller who can run it. `POST /api/admin/users/33/revoke-admin` returns a
400 whoever sends it.

### The three paths, in the order they should be considered

**1. Leave it (the default, and almost certainly right).**
It is the operator's own account and the only admin on the deployment. Revoking it
would leave **zero** admins and make the entire `/api/admin` surface — withdrawals,
disputes, refunds, role grants — unreachable. Nothing needs to change; the situation
is now visible, which was the actual defect.

**2. Rotate to a different account (the safe sequence).**
Needed if he wants admin on a different Steam account. The new account must sign in
through Steam once first, so a `STEAM_USERS` row exists to grant against.

```bash
# Signed in as user 33 in a browser, copy the SBOX_SESSION cookie value.
# <NEW_ID> is the STEAM_USERS.id of the new account, NOT its steamId64.

curl -s -X POST http://localhost:8082/api/admin/users/<NEW_ID>/grant-admin \
  -H "Cookie: SBOX_SESSION=<session-value>"

# Now sign in as the NEW admin, take ITS cookie, and only then:
curl -s -X POST http://localhost:8082/api/admin/users/33/revoke-admin \
  -H "Cookie: SBOX_SESSION=<new-admin-session-value>"
```

Order matters: grant first. Reversing it hits `LAST_ADMIN` and nothing happens.

**3. Emergency, account compromised, cannot log in.**
Only if the Steam account is believed to be in someone else's hands. This leaves the
deployment with **zero** admins, so set up the recovery net in the same sitting.

```bash
# a) Back up first. There is no other safety net.
#    (See "H2 backup and the auto-server" below — take it, then VERIFY it.)

# b) Demote through the auto-server, while the app is running:
H2JAR="$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar"
java -cp $H2JAR org.h2.tools.Shell \
  -url "jdbc:h2:file:C:/Users/WW/Desktop/sboxmarket/data/sboxmarket;AUTO_SERVER=TRUE;MODE=PostgreSQL" \
  -user sa -password "$SPRING_DATASOURCE_PASSWORD" \
  -sql "UPDATE STEAM_USERS SET ROLE='USER' WHERE ID=33;"

# c) Recovery net — WITHOUT this you have locked yourself out.
#    Set the bootstrap var to a Steam ID you control and restart. That account is
#    promoted to ADMIN on its next login. This is the documented no-database-access
#    recovery path (see AdminService.promoteBootstrapAdmin).
[Environment]::SetEnvironmentVariable('ADMIN_BOOTSTRAP_STEAM_IDS','<your-steam-id64>','User')
```

Remember what the bootstrap list is while it is set: a **standing** grant, not a
one-time bootstrap. `promoteBootstrapAdmin` runs on every login, so a revoke is undone
at the next sign-in until the ID is removed from the variable again. Clear it once the
new admin is established.

### Confirming the state afterwards

```bash
curl -s http://localhost:8082/api/admin/security/admin-grants \
  -H "Cookie: SBOX_SESSION=<session-value>"
```

`unconfigured` lists admins no config explains, `unaudited` lists admins with no
`ADMIN_GRANTED` row, and `revocableViaApi: false` means the two refusals above are
still in force. Pinned by `PersistedAdminIsReportedSpec`.

---

## H2 backup and the auto-server

`deploy/backup-db.sh` above is the **Postgres** path (`pg_dump` against the `sbox-pg`
container). It does not touch the H2 file database the app actually runs on today.
This section is that path, and it is the reason `AUTO_SERVER=TRUE` is still in the
datasource URL.

**Why an external process is needed at all.** The running app holds an exclusive OS
handle on `data/sboxmarket.mv.db`. Copying the file underneath a live writer gives a
torn copy, and an embedded open from a second process is refused outright — measured:

```
90020  Database may be already in use: ".../data/sboxmarket.mv.db".
       Possible solutions: close all other connection(s); use the server mode
```

H2 names the remedy itself: *use the server mode*. `AUTO_SERVER=TRUE` is that server
mode. Removing it would make a hot backup impossible without stopping the app.

### Taking a backup (verified 2026-09-02)

A client opens the **same path** the app opened. The embedded open fails with
`DATABASE_ALREADY_OPEN_1`, H2 hands the client the server key, and the driver
transparently reconnects over the loopback TCP server. `BACKUP TO` then runs
server-side and writes a zip.

```bash
H2JAR="$HOME/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar"
TS=$(date -u +%Y%m%dT%H%M%SZ)

java -cp $H2JAR org.h2.tools.Shell \
  -url "jdbc:h2:file:C:/Users/WW/Desktop/sboxmarket/data/sboxmarket;AUTO_SERVER=TRUE;MODE=PostgreSQL" \
  -user sa -password "$SPRING_DATASOURCE_PASSWORD" \
  -sql "BACKUP TO 'C:/Users/WW/skinbox-backups/h2-sboxmarket-$TS.zip'"
```

**Verify it, do not trust the exit code.** The lesson from `deploy/test-backup-db.sh`
applies here too — a command that returns 0 is not a backup. Read the archive back:

```bash
unzip -l "C:/Users/WW/skinbox-backups/h2-sboxmarket-$TS.zip"    # expect sboxmarket.mv.db
mkdir -p /tmp/restore && unzip -o ".../h2-sboxmarket-$TS.zip" -d /tmp/restore
java -cp $H2JAR org.h2.tools.Shell \
  -url "jdbc:h2:file:/tmp/restore/sboxmarket;MODE=PostgreSQL" -user sa -password "..." \
  -sql "SELECT COUNT(*) FROM STEAM_USERS;"
```

A verified run on 2026-09-02 produced a 112,413-byte zip holding `sboxmarket.mv.db`
at 368,640 bytes — byte-identical in size to the live file — and the restored copy
answered the same row counts as the live database.

Note the archive contains **only** `sboxmarket.mv.db`. It does *not* contain
`sboxmarket.lock.db`, which is what makes it safe to keep: see below.

### The credential, and why the bind is not enough

Two independent controls now stand in front of this server:

1. **Where it listens.** `SboxMarketApplication.hardenEmbeddedH2Bind()` sets
   `h2.bindAddress=127.0.0.1` programmatically before Spring starts. Verified in the
   running process: PID owns `127.0.0.1:50494` (H2) and `127.0.0.1:8082` (Tomcat),
   both refused from the LAN and Tailscale addresses.
2. **Who may connect.** `config/H2CredentialGuard` refuses to start the application
   when the datasource opens an H2 listener and `spring.datasource.password` is blank
   or under 16 characters.

They do not share a switch — one is a system property, the other an environment
variable — so no single lost variable disables both.

**Do not mistake H2's server key for a credential.** `TcpServer.checkKeyAndGetDatabaseName`
does refuse a connection whose database name is not the registered key; five
path-shaped guesses were each refused with `28000`. But the key is the `id` property
inside `data/sboxmarket.lock.db`, a cleartext file in the same directory as the
database. Connecting with that value as the database name authenticated as `SA`
**with an empty password** and read every wallet row. The key stops a blind peer who
has only the port. It stops nobody who can read one file.

### Activating the credential (requires one restart — read fully first)

The guard is configuration-only. It does **not** change any existing database's
password: H2 stores users inside the database, so setting the env var alone makes the
app fail to *connect* (`28000`), not migrate. Both halves must move together.

```powershell
# 0. Take and VERIFY a backup first (above). There is no other safety net.

# 1. Choose a secret >= 16 chars. Set it where the watchdog will inherit it:
#    the task launches `Start-Process java` and inherits the USER environment.
[Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD','<secret>','User')

# 2. Pause the watchdog so it cannot relaunch mid-migration.
Disable-ScheduledTask -TaskName 'SkinBox Watchdog'

# 3. Migrate the live database THROUGH the auto-server, while the app still runs:
#    ALTER USER SA SET PASSWORD '<secret>';
#    (existing pooled connections keep working; new ones would not, hence step 4)

# 4. Rebuild and restart the app so it picks up both the new jar and the env var.
#    Stop the java process, ./gradlew bootJar, relaunch from the repo root:
#      java -jar build/libs/sboxmarket-1.0.0.jar
#    -WorkingDirectory MUST be the repo root — the URL is relative and launching
#    elsewhere silently creates a NEW EMPTY DATABASE.

# 5. Re-enable the watchdog. Do not skip this.
Enable-ScheduledTask -TaskName 'SkinBox Watchdog'
```

**If you would rather not run a password at all**, the other exit is equally safe and
is one edit: remove `AUTO_SERVER=TRUE` from `SPRING_DATASOURCE_URL`. The guard goes
silent because there is no longer a listener to guard. The cost is this section's
backup procedure — with no server, a copy requires stopping the app.

### REHEARSED end to end on a restored copy (2026-09-02)

The sequence above was previously prose. It has now been walked start to finish
against a **restored copy** of the live database — never the live one — using a boot
jar built to a separate filename (`sboxmarket-rehearsal.jar`) so the live launch path
and the running JVM's file handle were never touched. Restored copy verified identical
to live before starting: 7 users / 1 admin / 37 audit rows / 9 wallets.

First, the premise, confirmed rather than assumed: `H2CredentialGuard.class` is present
in the rehearsal jar and **absent from the live `sboxmarket-1.0.0.jar`** (built
2026-09-01 14:56). The guard is written, registered, unit-tested — and not enforcing
anything on the running process.

| # | Configuration | Result |
|---|---------------|--------|
| 1 | rebuilt jar, `AUTO_SERVER=TRUE`, **blank** password | **REFUSED.** `IllegalStateException: REFUSING TO START…` thrown from `H2CredentialGuard.postProcessEnvironment`, during `prepareEnvironment` — before Hikari opens a connection, exactly as designed. |
| 2 | env var set, `ALTER USER` **not** run | Guard passes, driver refuses: `Wrong user name or password [28000-224]`. This is the documented trap, and it behaves as documented. |
| 3 | `ALTER USER SA SET PASSWORD` on the copy | Blank credential then refused `28000`; new credential accepted. |
| 4 | rebuilt jar + migrated database + password | **BOOTED** — `Started SboxMarketApplication in 10.3 seconds`. |
| 5 | rollback: `ALTER USER SA SET PASSWORD ''` | Blank accepted again, secret then refused `28000`. Reversible in both directions. |
| 6 | rebuilt jar, rolled back to blank | **REFUSED** again. Fail-closed in both directions; no fail-open branch. |

This is the step the previous pass could not do: the guard was known to *parse* (Spring's
own `SpringFactoriesLoader` resolves the registration, pinned by
`H2CredentialNotEmptySpec`), but had never been observed **firing in a real boot of a
real jar**. It fires.

**Rollback, both routes, both verified:**

- *Undo the migration.* `ALTER USER SA SET PASSWORD ''` restores the blank credential,
  and the **existing** `sboxmarket-1.0.0.jar` — which contains no guard — then boots
  against it (measured: started in 16.2 s). So reverting is: put the password back,
  relaunch the old jar. No rebuild needed on the way back.
- *Lost the secret.* Re-restoring `h2-sboxmarket-20260902T075102Z.zip` into a fresh
  directory read clean with a **blank** password. The backup is a genuine way home.

**Measured downtime: roughly 30 seconds.** `bootJar` took 3 s incremental (the classes
are already compiled) and startup measured 10–16 s; the rest is stop and relaunch. The
app must be stopped *before* the build, because the running JVM holds
`build/libs/sboxmarket-1.0.0.jar` open.

**Two limits worth knowing before you decide.**

1. A password on the live database does **not** protect the backups. Both zips in
   `C:\Users\WW\skinbox-backups` are copies of the whole database taken while the
   credential was blank, and they read with a blank password — proven above, since that
   is exactly what makes them a working rollback. Anyone who can read that directory
   gets the wallet rows regardless of what SA's password becomes. Only backups taken
   *after* the migration carry the new credential.
2. The exposure this closes is already loopback-only (`h2.bindAddress=127.0.0.1`,
   verified in the running process). The attacker this stops is one who is already on
   the box but cannot read `C:\Users\WW\skinbox-backups`. That is a real gap and worth
   closing — it is not an internet-facing hole.

### The scheduled H2 backup (built 2026-09-02)

Everything above was a procedure a human ran by hand. Until now **nothing backed the
H2 database up on a schedule** — the daily `SkinBox DB Backup` task ran the *Postgres*
script against a container that is not running, `LastTaskResult` was **127**, and the
three archives that existed were all taken by hand on one night.

| file | role |
|------|------|
| `deploy/H2Backup.java` | takes the archive through the auto-server, then extracts it, opens the restored copy and compares **every table's** row count against live |
| `deploy/h2-backup.ps1` | orchestrates, prunes only after a verified backup, writes the status file, exits non-zero on failure |
| `deploy/h2-backup-hidden.vbs` | the launcher Task Scheduler points at — GUI-subsystem, so no console window, and it **waits** so the exit code is real |
| `deploy/test-h2-backup.ps1` | 26 assertions; every mutation below was verified RED |

**What proves a backup here is the read-back, not the exit code.** The archive is
extracted, the restored copy is opened as a real database, and each table's count must
land inside the live count taken immediately before and immediately after `BACKUP TO`.
On a quiet machine that window is zero-width, so it is strict equality; under
concurrent writes it stays correct instead of failing a good backup.

**Retention runs only after a verified backup**, and two floors sit under it: the
newest `-MinimumKeep` (default 7) archives are never eligible whatever their age, and
the archive from the current run is never eligible at all. With four archives on disk
the prune is arithmetically a no-op today — which is the correct behaviour.

**Verified 2026-09-02**, live, app running: a 112,004-byte archive holding
`sboxmarket.mv.db` at 380,928 bytes — the same size as the live file — restored and
answering **35 tables matched**, 7 users / 9 wallets / 44 transactions / 37 audit rows.
Whole run: 1.8 s. All three pre-existing archives untouched.

Mutations verified RED (a green test that survives the bug is decoration):

| mutation | result |
|----------|--------|
| retention allowed to run on a failure path | **3 of 4 archives destroyed** — the 2026-09-01 incident, reproduced |
| the read-back stops comparing | an archive of a *different* database was ACCEPTED |
| the Postgres preflight removed | "not applicable" collapses back into a bare failure |

One assertion had to be sharpened to catch the first of those: at the default
`MinimumKeep` of 7, the floor alone protected a population of four, so "the archives
survived" passed even with the skip-on-failure guard deleted. The test now lowers the
floor so only the guard stands between a failed run and four destroyed archives. **An
assertion that cannot fail is not protecting anything.**

**Two things that are not obvious and are load-bearing:**

- The password reaches Java through the `H2_PASSWORD` **environment variable**, never
  as an argument. PowerShell 5.1 silently drops an empty-string argument to a native
  executable, so `-password "" -sql "…"` shifts the SQL into the password slot — and
  the live SA password is empty today, so that trap is live on this machine. Both
  phases print the password *length* they actually used, because a length is the only
  way to see an argument that vanished. This also means the job keeps working
  unchanged once the credential above is activated.
- `IFEXISTS=TRUE` is on both URLs. A wrong path must fail loudly rather than be
  answered by silently creating a **new empty database** whose zero rows then verify
  against themselves.

**How you know it ran.** Every run overwrites `data\h2-backup-status.json` with
`last_run_at`, the outcome, the four money counts, and whether retention ran and why
not. A stale timestamp there means the **scheduler** stopped — the one failure no
amount of logging inside the script can report, and the reason the old job could fail
150 times unnoticed. Same pattern as cs2bot's `data\keepalive-status.json`.

### Who READS the status file (built 2026-09-02, second half)

Writing that file closed half the gap. **Nothing read it**, which is the same shape as
the defect it was built to end: a signal produced faithfully and consumed by nobody.
`config/BackupFreshnessReporter` is the consumer.

It matters more than it sounds, and the reason is worth stating once plainly: **no
amount of logging inside a scheduled job can report that the job stopped being
scheduled.** The log simply stops. A stopped scheduler and a quiet machine produce
byte-identical evidence. The only thing that can see it is something *else* that is
running, noticing the timestamp has not moved — which is why the check is on an
`@Scheduled` hourly tick and not only on `ApplicationReadyEvent`. The app runs for
days; a backup goes stale *while it is running*.

**Three states, deliberately never collapsed:**

| state | means | what to go look at |
|-------|-------|--------------------|
| `ok` | ran inside the window and the read-back verified every table | nothing |
| `failed` | ran inside the window and did **not** verify | `skinbox-backups\h2-backup.log`. Retention is skipped on failure, so the archives on disk are intact |
| `stale` | **we cannot establish that it ran at all** | Task Scheduler. The scheduler stopped, the machine slept through 04:00, the task was deleted, or the file was never written |

**A missing status file is `stale`, not an error.** "No backup has ever run here" and
"no backup has run since Tuesday" are the same fact about the data. An unreadable file
is also `stale` — we cannot establish a run — but carries its own `reason`
(`unreadable-status-file` vs `no-status-file`), because an absent file means the job
was never wired up and a corrupt one means a write was interrupted. When a run
*failed* **and** nothing has run since, `stale` is the reported state and `ranFailed`
is reported alongside it; the banner says both.

**Window: 26 hours** (`sbox.backup.max-age-minutes` to override). One full daily
period plus two hours. Exactly 24h would flag an ordinary day the run drifted by a
minute and would cry wolf on every DST shift, and a reporter that cries wolf is one
the operator learns to close.

**Where it surfaces, and why it is split:**

- **`GET /api/health/backup` — public, 200 / 503, one word.** The body is
  `{"status":"UP|DOWN","backup":"ok|failed|stale"}` and nothing else: no archive path,
  no database path, no row counts, no machine name, no timestamp. An uptime monitor
  that reads only status codes is the sole consumer that alerts with nobody logged in,
  and this project's entire documented failure history is of signals no human looked
  at. **Point Cloudflare / uptime-kuma at this URL** alongside
  `/api/health/cookie-aware`.
  - The leak is deliberately one bit — "this deployment's backups are not current". It
    is mild uplift to an attacker already on the box (it makes destroying data look
    more damaging) and it is stated here rather than engineered around, because the
    alternative is a signal only a logged-in human can see. Everything that actually
    names something worth taking is on the admin surface instead.
  - It is a **separate path** from `/api/health` and `/api/ready` on purpose. Those
    are the Docker HEALTHCHECK, the `run-local.sh` deploy gate and the LB targets, and
    both nginx configs match them with `location = ` (exact), so a 503 here reaches
    none of that machinery. **A stale backup must never evict a healthy app from
    rotation** — that would turn a data-protection warning into the outage it exists
    to prevent. There is a test that calls the liveness probe while the backup probe
    is 503 and asserts it still answers 200.
- **`GET /api/admin/security/backup-status`** — the full record: archive path,
  database path, machine, per-table counts, retention decision, the resolved path it
  looked at. Admin-authed for the same reason `UnconfiguredAdminReporter` keeps the
  ADMIN Steam IDs off the anonymous surface.
- **The admin Health tile** (`/api/admin/health`, the staff panel's Health tab) — an
  "H2 backup" card beside Uptime / Heap / Stripe webhook, amber on `stale`, red on
  `failed`, naming the `reason`. This is the one that matters day to day: a report
  behind a URL the operator has to remember exists is most of the way back to a status
  file nobody opens.
- **The log** — a banner on boot and on every hourly tick while the state is not `ok`.
  Kept because it is free, and explicitly *not* relied on. The 150-line exit-127 run
  is what a log-only signal is worth on this machine.

It is a report, not a gate: it never refuses a boot, a request or a trade, and the
call site on the Health tile is wrapped so a throwing reporter cannot take the staff
panel down with it.

**Verified live 2026-09-02**, compiled class run from the app's own working directory
against the real `data\h2-backup-status.json`: derived the path from the datasource
URL to `C:\Users\WW\Desktop\sboxmarket\data\h2-backup-status.json`, found the file,
and reported `state=ok`, `ageMinutes=37`, 35 tables, 7 / 9 / 44 / 37.

**It is NOT in the running process.** The money app (PID as of writing started
2026-09-01 22:10) is running `build/libs/sboxmarket-1.0.0.jar` built before any of
this. `/api/health/backup` will 404 and the Health tile will show "Reporter not wired"
until the next rebuild + restart. Ship it with the coordinated restart below rather
than on its own — a reporter is not worth a separate outage.

Mutations verified RED, each attributed to the test-case name that failed, each
reverted with the file hash compared against the original afterwards:

| mutation | failing test |
|----------|--------------|
| staleness comparison deleted (`stale = false`) | *a VERIFIED backup older than the window is STALE* (+5) |
| a missing status file returns `ok` | *a MISSING status file is STALE — the third state, not an error* (+5) |
| a missing status file throws instead | same test — a missing file must not be an error either |
| `ok` read with Groovy truth instead of strict-true | *the string 'false' in the ok field produces FAILED, not ok* (+3) |
| `ranFailed` dropped on the stale path | *a run that FAILED and then never ran again reports BOTH facts* |
| `unreadable` reason collapsed into `no-status-file` | *an UNREADABLE status file is STALE, with its own reason* (+2) |
| window widened to exactly 24h | *the window clears a daily job's full period* |
| `@Component` removed | *the reporter is a @Component* |
| `@Scheduled` tick removed | *the freshness check runs on a SCHEDULE, not only at boot* |
| `@EventListener` boot hook removed | *the boot banner is an @EventListener on ApplicationReadyEvent* |
| derivation ignores the database path | *the status path FOLLOWS the database — it is not a hard-coded guess* (+3) |
| public probe returns 200 on every state | *the public probe answers 503 when the backup is …* (+3) |
| a missing reporter bean reads as 200 | *the public probe answers 503 when the reporter bean is MISSING* |
| public probe body carries the full report | *the public probe body carries the state word and NOTHING else* |
| probe path moved onto a matched health path | *the public probe is a SEPARATE path from the load-balancer and Docker targets* |
| `backup` dropped from `systemHealth()` | *the admin Health tile is fed the report* (+1) |
| the Health tile's guard rethrows | *a throwing reporter does not take down the admin Health tile* |
| `publicState()`'s catch returns `ok` | *publicState reports STALE when the report itself throws* |

**TWO of those started out STILL GREEN, and both were real holes in the tests:**

1. *the status path is derived from the database* passed with the derivation deleted,
   because the fallback constant is `data/h2-backup-status.json` — so "a file named
   h2-backup-status.json in a directory named data" was satisfied by the fallback just
   as well as by the derivation. The test now points the database at
   `./var/moneydb/`, a directory the fallback can never name. **An assertion a
   different rule happens to satisfy is decoration.**
2. `publicState()`'s exception fallback had no test at all: flipping its
   `return STATE_STALE` to `return STATE_OK` left all 59 tests green. A branch whose
   whole job is to refuse "an unhandled surprise means backups are fine" was itself
   unprotected. It has its own test now.

### About `deploy/backup-db.sh` and the old task

Kept, not deleted — it is a correct Postgres script and this repo still ships a
Postgres `docker-compose.yml`. What changed is that it now says which case it is in:
a preflight checks for `docker` and for a running `sbox-pg` before dumping, and
refuses with **exit 3** and a message naming the H2 job instead of failing with a bare
non-zero forever. Exit 3 is still non-zero — a Postgres backup that is not running
must never read as success — it is simply distinguishable from exit 1, a dump that
genuinely failed.

`deploy/backup-db-hidden.vbs` had a second defect worth knowing about, now fixed: it
called `Run(cmd, 0, False)`, which returns immediately with 0, so **wscript exited 0
no matter what the backup did**. Task Scheduler would have recorded success for a
backup that never happened. Both launchers now wait and propagate the real exit code.

### OPERATOR: the task is still not repointed, and two commands are yours to run

Measured 2026-09-02 02:20, read-only:

```
TaskName : SkinBox DB Backup      State    : Ready
Action   : wscript.exe "C:\Users\WW\Desktop\sboxmarket\deploy\backup-db-hidden.vbs"
Trigger  : daily 04:00 (-07:00)   LogonType: Interactive   RunLevel: Limited
LastRun  : 09/01/2026 04:00:01    LastTaskResult: 127      NextRun: 09/02/2026 04:00
```

So **the H2 job is on no schedule at all** — the task still runs the Postgres script,
which now refuses with exit 3 instead of 127. The manual archive taken at 02:09 is the
last one; once this reporter ships it will go `stale` about 26 hours after that
timestamp unless the task is repointed. *That is the signal working, not a bug.*

Agents do not touch scheduled tasks — a task principal is standing machine
configuration, and there is no way to change one without asserting something about who
the machine runs jobs as. Both commands below are yours. Run them in an **elevated**
PowerShell.

**1. Repoint the action at the H2 job.** Preserves the trigger, principal and
settings; only the action changes.

```powershell
$t = Get-ScheduledTask -TaskName 'SkinBox DB Backup'
$t.Actions = (New-ScheduledTaskAction -Execute 'wscript.exe' `
    -Argument '"C:\Users\WW\Desktop\sboxmarket\deploy\h2-backup-hidden.vbs"')
Set-ScheduledTask -TaskName 'SkinBox DB Backup' -Action $t.Actions

# prove it took, then force one run and read the real verdict
Get-ScheduledTask -TaskName 'SkinBox DB Backup' | ForEach-Object { $_.Actions }
Start-ScheduledTask -TaskName 'SkinBox DB Backup'
Get-ScheduledTaskInfo -TaskName 'SkinBox DB Backup' | Select-Object LastRunTime, LastTaskResult
Get-Content .\data\h2-backup-status.json
```

`LastTaskResult` 0 **and** `"ok": true` in the status file. Either one alone is the
mistake this whole area exists to stop.

**2. `LogonType: Interactive` — yes, this should change, and here is why.**

An Interactive task only fires when that user has an interactive session. At 04:00
with the operator logged out — or at the lock screen, or after a reboot nobody has
signed into — **it simply never runs.** No error, no log line, nothing: exactly the
silence a stopped scheduler produces. This is pre-existing (it was Interactive when it
was the Postgres job too) and it is the single most likely way the new `stale` state
will fire in practice.

The fix is `S4U` — run whether or not the user is logged on, without storing a
password. It cannot reach network shares (no outbound credential), which is fine here:
the job reads a local file and writes to a local directory.

```powershell
# elevated. -LogonType S4U needs no password; -RunLevel Highest is optional
# but keeps behaviour identical if the backup dir is ever ACL'd.
$p = New-ScheduledTaskPrincipal -UserId "$env:COMPUTERNAME\WW" `
        -LogonType S4U -RunLevel Limited
Set-ScheduledTask -TaskName 'SkinBox DB Backup' -Principal $p

Get-ScheduledTask -TaskName 'SkinBox DB Backup' |
    Select-Object -ExpandProperty Principal    # expect LogonType : S4U
```

Also worth setting, for the same reason (a laptop asleep at 04:00 never runs the job
and the timestamp goes stale with nothing wrong):

```powershell
$s = New-ScheduledTaskSettingsSet -StartWhenAvailable `
        -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries
Set-ScheduledTask -TaskName 'SkinBox DB Backup' -Settings $s
```

`-StartWhenAvailable` runs a missed daily trigger at the next opportunity instead of
skipping the day.

**What was NOT determined:** whether this account can actually hold an S4U principal.
S4U requires the "Log on as a batch job" right, which a local admin normally has, but
it is Group-Policy-controlled and was not read. If `Set-ScheduledTask` refuses, the
fallback is `-LogonType Password` (which does store a credential) — and at that point
the honest alternative is to leave it Interactive and rely on the `stale` signal you
now have.

### CLOSED: the archives are no longer on the same disk as the database

**Was:** `C:\Users\WW\skinbox-backups` and `data\sboxmarket.mv.db` were both on **C:**.
One drive failure lost the database and every archive of it in the same event. Four
verified archives on a dying disk is one copy, not four. The previous note here
recommended buying a ~$10 USB stick.

**Now:** no purchase was needed — this machine already has three drives, and two of
them are separate physical devices. Measured 2026-09-02:

| Letter | Physical disk | Device | Free / total |
|---|---|---|---|
| `C:` | disk 0 | Samsung SSD 990 PRO **1TB**, NVMe, boot+system, serial `0025_384C_41A0_F708` | 211.3 GB / 930.5 |
| `D:` | disk 1 (via a *Simple* Storage Space) | Samsung SSD 990 PRO **2TB**, NVMe, serial `0025_3842_5143_2D4F` | 203.2 GB / 1853.4 |
| `E:` | disk 3 | Seagate Portable, **USB** | 1325.2 GB / 4657.5 |

`h2-backup.ps1` now writes every **verified** archive to `D:\skinbox-backups` as well.
At ~112 KB a day that is ~40 MB a decade against 203 GB free.

**Why D: and not E:.** Both are genuinely different physical devices from C: — the
Storage Space on D: was checked, not assumed: `Get-VirtualDisk | Get-PhysicalDisk`
reports disk 1 as its only backing device, so it is not a second name for C:. D: wins
the default because it is **internal**. The job fires unattended at 04:00, and a
portable drive that is unplugged half the time would make "no second copy" a daily
false alarm — which is how a report dies. E: is strictly better against fire, theft
and ransomware *while it is unplugged*, and it is one parameter away:

```powershell
# rotate to the portable drive instead
-OffsiteDir 'E:\skinbox-backups' -OffsiteVolumeLabel 'Seagate Portable Drive'
```

**What still is not covered.** Both defaults are inside the same box. Fire, theft, and
ransomware that walks every mounted volume take both. The only fix for that is
off-site storage (B2 / R2 / S3 — ~112 KB/day is a rounding error on any free tier),
which costs a credential this project has repeatedly decided not to spend. Unplugging
E: and rotating it by hand is the free approximation.

#### How the copy cannot lie, and cannot break the backup

- **Only a VERIFIED archive is copied.** The copy step is called once, after the
  read-back matched live — the same gate retention already sits behind. Copying an
  unproven archive propagates a bad one.
- **The copy is read back AT THE DESTINATION**, by length *and* SHA-256, and the
  bytes are written through to the device (`FILE_FLAG_WRITE_THROUGH` +
  `FlushFileBuffers`) before anything is claimed. `Copy-Item` returning without an
  error only means the API accepted the request — the same class of evidence as
  `gzip` exiting 0 on an empty pipe. It lands as `.part` and is renamed only after it
  matches, so a rejected copy never wears an archive's name.
- **A missing, wrong, full or unwritable destination is REPORTED, never fatal.** The
  run still exits 0, the primary archive is untouched, and the reason lands in
  `data\h2-backup-status.json` under `offsite.reason`. A backup that fails because a
  secondary location is unavailable is worse than having no secondary location.
- **A drive letter is checked for identity, not just presence.** `D:` is matched
  against the volume label `Storage space` before a byte is written. If the letter is
  ever reassigned the copy is skipped and reported, rather than writing the money
  database onto whatever device answered to `D:` that morning. Override with
  `-OffsiteVolumeLabel`, or disable with `-NoOffsiteVolumeCheck`. **If the label
  changes** (a reformat, a rename), every run reports `ok-no-offsite` with a reason
  naming both labels, and the fix is to pass the new label — nothing is lost and
  nothing is written to the wrong place in the meantime.
- **Retention there is its own, longer decision.** 365 days and a floor of 30, both
  clamped *up* to the primary's if ever configured lower — the point of a second copy
  is surviving the loss of the first, so it must never be the shorter-lived of the
  two. And it is pruned only when the primary verified **and** this run placed a
  verified copy: deleting old copies on a run that could not make a new one is
  deleting the only surviving copy.

#### The new state on the admin tile and the probe

`BackupFreshnessReporter` now has **four** states, not three:

| state | meaning | probe |
|---|---|---|
| `ok` | verified inside the window, **and** a verified copy is on the second disk | 200 |
| `ok-no-offsite` | verified inside the window, **one copy only** | 503 |
| `failed` | ran inside the window and did not verify | 503 |
| `stale` | cannot establish that a run happened at all | 503 |

`ok-no-offsite` is deliberately neither of its neighbours. Folding it into `ok`
restores the one-disk hole; folding it into `stale` cries wolf over an archive that
verified an hour ago. The staff Health tile shows it as **ONE COPY** in warn colour
with the job's own reason; the log banner points at the destination rather than at
Task Scheduler.

**Expected on first deploy:** until the scheduled task next runs the new script, the
status file has no `offsite` block, so the state is `ok-no-offsite` with reason
`offsite-not-reported` and `/api/health/backup` answers 503. That is correct — there
is no second copy yet. It clears on the first run of the new job.

#### One-time operator step

None for `D:` — the job creates `D:\skinbox-backups` itself on a volume whose label
matches. Nothing about the scheduled task changes; the repoint command is already
above. To confirm after the first run:

```powershell
Get-Content data\h2-backup-status.json | ConvertFrom-Json |
    Select-Object -ExpandProperty offsite

Get-ChildItem D:\skinbox-backups -Filter '*.zip' | Select-Object Name, Length, LastWriteTime

# and prove the two copies are the same file, independently of the job's own claim
$a = Get-ChildItem C:\Users\WW\skinbox-backups -Filter '*.zip' | Sort-Object LastWriteTime | Select-Object -Last 1
$b = Join-Path 'D:\skinbox-backups' $a.Name
(Get-FileHash $a.FullName).Hash -eq (Get-FileHash $b).Hash    # expect True
```

---

# ONE RESTART: shipping all three pending controls together

Three separate pieces of work are committed and **none of them is in the running
process**. Each on its own would need a rebuild and a restart of the money app.
Doing that three times is three outages and three chances to get it wrong. This
section is the single coordinated sequence, rehearsed end to end on a restored
copy on 2026-09-02 against `HEAD = 2f7343c`.

| # | Piece | What it does once shipped |
|---|-------|---------------------------|
| 1 | `service/MoneyResetService` + `config/MoneyResetGate` | Makes the fabricated-money reset *available*. It stays inert until asked with two separate environment variables. |
| 2 | `config/H2CredentialGuard` | Refuses to start when a listening H2 has a blank/short password. |
| 3 | `config/UnconfiguredAdminReporter` | Boot banner + `GET /api/admin/security/admin-grants` naming any persisted ADMIN no config explains. |
| 4 | `config/BackupFreshnessReporter` | Reads `data\h2-backup-status.json`. Boot + hourly banner, an "H2 backup" card on the admin Health tile, `GET /api/admin/security/backup-status`, and a public `GET /api/health/backup` that 503s when the backup is stale or failed. Added 2026-09-02, after this section was written — it ships in the same restart, and like the other three it is a report and never a gate. |

### The premise, measured and not assumed

The live `build/libs/sboxmarket-1.0.0.jar` was built **2026-09-01 14:56**. All
three commits are later (`28e6882` 15:31, `b51cf4f` 2026-09-02 00:58, `2f7343c`
01:22). Reading the live jar's entry list directly:

```
sboxmarket-1.0.0.jar     H2CredentialGuard 0   MoneyResetGate 0
                         MoneyResetService 0   UnconfiguredAdminReporter 0
                         META-INF/spring.factories 0
sboxmarket-rehearsal.jar H2CredentialGuard 1   MoneyResetGate 1
                         MoneyResetService 15  UnconfiguredAdminReporter 8
                         META-INF/spring.factories 1
```

Zero, not "probably stale". Nothing below is enforcing anything today.

### Live baseline at rehearsal time (re-measure before you start)

`7` STEAM_USERS · `1` ADMIN (id 33) · `9` WALLETS totalling `30797.70` ·
`44` TRANSACTIONS · `37` AUDIT_LOG · `254` LISTINGS · `39` ITEMS · `8` TRADES.

### Two numbers that are both right, so neither surprises you

`$30,575.00` is the **DEPOSIT** total — 9 rows, every one carrying a `dev_`
reference, **0** carrying anything else (so no real Stripe charge exists to
destroy). `$30,797.70` is the **wallet balance** total, which is what gets
zeroed. The report prints both. They are different figures for different things.

### Before you touch anything

- **Do not skip step 1.** The `SkinBox Watchdog` task fires every 2 minutes and
  relaunches `java -jar` whenever health is not 200 and no matching process
  exists. Mid-restart it will either steal port 8082 or attach a second writer
  to H2.
- Step 5 must come before step 6: the running JVM holds the jar open. Measured,
  on this box, attempting to open it for write while the app runs:
  `The process cannot access the file '…\sboxmarket-1.0.0.jar' because it is
  being used by another process.` A build into that path fails, it does not
  silently win.
- Steps 3 and 4 are the only *irreversible-ish* ones and both have a proven way
  back (see "Rollback" below).

---

## The sequence

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 1. STOP THE WATCHDOG FIRST. Nothing else is safe until this returns.
Disable-ScheduledTask -TaskName 'SkinBox Watchdog'
#   PRINTS: a table row -> TaskPath \  TaskName SkinBox Watchdog  State Disabled
#   VERIFY, do not assume:
Get-ScheduledTask -TaskName 'SkinBox Watchdog' | Select-Object TaskName,State
#   MUST read State = Disabled. If it still reads Ready, STOP — a relaunch
#   mid-sequence is exactly the failure this step exists to prevent.
```

```bash
# ─────────────────────────────────────────────────────────────────────────────
# 2. BACK UP, AND VERIFY THE BACKUP BY READING IT BACK.
#    The app is still running here; this goes through the auto-server, which is
#    the only way to copy the database while it is held open.
H2JAR=$(ls ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar)
TS=$(date -u +%Y%m%dT%H%M%SZ)
ZIP="C:/Users/WW/skinbox-backups/h2-sboxmarket-$TS.zip"

java -cp "$H2JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:C:/Users/WW/Desktop/sboxmarket/data/sboxmarket;AUTO_SERVER=TRUE;MODE=PostgreSQL" \
  -user sa -password "" -sql "BACKUP TO '$ZIP'"
#   PRINTS: nothing at all on success. That silence is why the next two commands
#   are not optional — exit code 0 is not a backup.

unzip -l "$ZIP"
#   PRINTS: one entry, sboxmarket.mv.db, whose size matches data/sboxmarket.mv.db.
#   Rehearsed: a 112,332-byte zip holding sboxmarket.mv.db at 380,928 bytes,
#   the same size as the live file.

unzip -o "$ZIP" -d /tmp/verify-$TS
java -cp "$H2JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:/tmp/verify-$TS/sboxmarket;MODE=PostgreSQL" -user sa -password "" \
  -sql "SELECT COUNT(*) FROM STEAM_USERS; SELECT COUNT(*) FROM TRANSACTIONS;"
#   PRINTS: the row counts. They MUST equal the live baseline above (7 and 44).
#   This is the whole check: extracted AND re-queried, never exit code.
#   If this prints an error or different counts, STOP. You have no safety net.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 3. SET THE DATABASE PASSWORD (>= 16 chars) WHERE THE WATCHDOG INHERITS IT.
#    User scope, because the watchdog relaunches via Start-Process and inherits
#    the USER environment. This is a persistent variable holding a credential —
#    it is your decision, and it is the reason this runbook stops short of doing
#    it for you.
[Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD','<secret>','User')
#   PRINTS: nothing. Verify presence WITHOUT echoing the value:
([Environment]::GetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD','User')).Length
#   MUST print a number >= 16. If it prints nothing or 0, the guard will refuse
#   to boot at step 8 — which is correct behaviour, not a bug.
```

```bash
# ─────────────────────────────────────────────────────────────────────────────
# 4. MIGRATE THE DATABASE TO MATCH — while the app is STILL RUNNING.
#    H2 stores users inside the database, so step 3 alone changes what the app
#    SENDS, not what the database EXPECTS. Both halves must move together.
#    Existing pooled connections keep working; that is why this is safe here.
java -cp "$H2JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:C:/Users/WW/Desktop/sboxmarket/data/sboxmarket;AUTO_SERVER=TRUE;MODE=PostgreSQL" \
  -user sa -password "" -sql "ALTER USER SA SET PASSWORD '<secret>';"
#   PRINTS: nothing on success.
#   VERIFY both directions — a refusal alone is indistinguishable from a dead
#   server, so you need the paired result:
java -cp "$H2JAR" org.h2.tools.Shell -url "<same url>" -user sa -password "" -sql "SELECT 1;"
#     -> MUST fail with:  Wrong user name or password [28000-224]
java -cp "$H2JAR" org.h2.tools.Shell -url "<same url>" -user sa -password "<secret>" -sql "SELECT 1;"
#     -> MUST print 1.
#   Blank refused AND secret accepted, in the same breath, is the pass condition.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 5. STOP THE APP. From here the clock is running (~30 s).
$p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
     Where-Object { $_.CommandLine -like '*sboxmarket-1.0.0.jar*' }
$p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
#   Then PROVE it died. A kill that hit the wrong thing is a silent no-op:
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*sboxmarket-1.0.0.jar*' } | Measure-Object |
  Select-Object -ExpandProperty Count
#   MUST print 0. If it prints 1, the jar is still locked and step 6 WILL fail.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 6. REBUILD.
cd C:\Users\WW\Desktop\sboxmarket
.\gradlew.bat bootJar --console=plain
#   PRINTS: "BUILD SUCCESSFUL in Ns". Rehearsed at 2.5 s — the classes are
#   already compiled, so this is a repackage, not a full build.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 7. PROVE THE NEW JAR ACTUALLY CONTAINS THE THREE PIECES.
#    Do not skip this. "A committed fix is not a shipped fix" has cost this
#    project twice; this is the ten-second check that makes it impossible.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$za = [IO.Compression.ZipFile]::OpenRead('C:\Users\WW\Desktop\sboxmarket\build\libs\sboxmarket-1.0.0.jar')
'H2CredentialGuard','MoneyResetGate','MoneyResetService','UnconfiguredAdminReporter','spring.factories' |
  ForEach-Object { $n=$_; "$n : " + @($za.Entries | Where-Object { $_.FullName -like "*$n*" }).Count }
$za.Dispose()
#   PRINTS five lines, EVERY count >= 1:
#     H2CredentialGuard : 1     MoneyResetGate : 1        MoneyResetService : 15
#     UnconfiguredAdminReporter : 8            spring.factories : 1
#   Any ZERO means you are about to restart into the same jar you already have.
#   STOP and find out why before step 8.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 8. START — AND CAPTURE THE OUTPUT, or step 9 has nothing to read.
#    Working directory MUST be the repo root: the datasource URL is relative
#    (./data/sboxmarket) and launching elsewhere silently creates a NEW EMPTY
#    DATABASE.
#
#    THE REDIRECT IS NOT OPTIONAL. Under the default profile logback-spring.xml
#    binds the root logger to CONSOLE only — the file appender lives inside
#    <springProfile name="prod">. So this app writes NO log file, and the
#    watchdog's own hidden relaunch throws its console away. Every piece of
#    evidence step 9 asks for exists solely on this process's stdout.
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$boot  = "C:\Users\WW\skinbox-boot-$stamp.log"
Start-Process 'C:\Program Files\Java\jdk-17\bin\java.exe' `
  -ArgumentList '-jar','build/libs/sboxmarket-1.0.0.jar' `
  -WorkingDirectory 'C:\Users\WW\Desktop\sboxmarket' `
  -RedirectStandardOutput $boot -RedirectStandardError "$boot.err" -WindowStyle Hidden
"boot log -> $boot"
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 9. VERIFY ALL THREE, BY POSITIVE EVIDENCE. Give it ~15 s first.
#    Rehearsed startup: "Started SboxMarketApplication in 13.015 seconds".

# a) The app is up.
Invoke-WebRequest http://localhost:8082/api/health -UseBasicParsing | Select-Object -Exp StatusCode
#    -> 200

# b) H2CredentialGuard PASSED (rather than "did not appear"). The proof that it
#    ran and was satisfied is that Hikari opened a connection at all — the guard
#    throws during prepareEnvironment, before Hikari exists.
#    Search POSITIVELY. Never pipe this through a `-notmatch`/`grep -v`: a
#    filter written to tidy the output once removed the very line carrying the
#    28000 evidence, and the check then reported the opposite of the truth.
Select-String -Path $boot,"$boot.err" -Pattern 'Added connection|REFUSING TO START|28000|Started SboxMarketApplication'
#    WANT: "HikariPool-1 - Added connection conn1: url=jdbc:h2:file:./data/sboxmarket user=SA"
#          "Started SboxMarketApplication in N seconds"   (rehearsed: 13.015 s)
#    If instead the process EXITED with
#      IllegalStateException: REFUSING TO START: this datasource opens an H2
#      network listener (...) but spring.datasource.password is blank or shorter
#      than 16 characters
#    then step 3 did not reach this process. Re-check the User variable.
#    If it exited with "Wrong user name or password [28000-224]" instead, the
#    guard passed and step 4 is what did not take. Re-run step 4.

# c) UnconfiguredAdminReporter fired.
Select-String -Path $boot -Pattern 'ADMIN GRANT REPORT|Admin-grant check' -Context 0,8
#    Expect a WARN banner:
#      ==================================================================
#       ADMIN GRANT REPORT — a persisted ADMIN is not explained by config
#      ==================================================================
#       ADMIN rows in STEAM_USERS : 1
#       admin.bootstrap-steam-ids : (empty)
#       NOT IN BOOTSTRAP LIST     : user id=33 steamId64=76561199839805014 ...
#       NO ADMIN_GRANTED AUDIT    : user id=33 ...
#    A CLEAN deployment instead logs one INFO line: "Admin-grant check: N ADMIN
#    row(s), all explained by ...". Exactly one of those two ALWAYS prints — so
#    a silent boot means the reporter did not run, and that is a failure, not a
#    pass. That is the whole point of it saying something when clean.

# d) MoneyResetService is present and CORRECTLY SILENT. With neither variable
#    set it returns without logging, by design (a guard that cries wolf on every
#    boot is a guard nobody reads). Its presence was proven at step 7; do not
#    look for a log line here. It is exercised at steps 11-14.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 10. RE-ENABLE THE WATCHDOG. DO NOT SKIP THIS.
Enable-ScheduledTask -TaskName 'SkinBox Watchdog'
Get-ScheduledTask -TaskName 'SkinBox Watchdog' | Select-Object TaskName,State
#   MUST read State = Ready.
```

**Steps 1-10 are the whole coordinated activation. Measured downtime between
step 5 and a 200 at step 9: roughly 30 seconds** (2.5 s repackage + ~13 s
startup + stop/launch). Everything below is a separate decision you can take
later, on its own schedule.

---

## The money reset (steps 11-14) — a separate decision, deliberately

The restart above *ships* the reset tool. It does not run it, and nothing runs
it until you set two environment variables. Read `MoneyResetGate` if you want
the reasoning; the short version is that the dry run is the deliverable and the
execute flag alone does nothing.

**Set these in the SHELL you launch from, not with `SetEnvironmentVariable`.**
A User-scope `SBOX_MONEY_RESET_ENABLED` would make every watchdog relaunch dump
every wallet balance into the log file. Process scope, one launch, then gone.

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 11. Stop the app (step 5), then run ONE boot with the opt-in only.
$env:SBOX_MONEY_RESET_ENABLED = 'true'
& 'C:\Program Files\Java\jdk-17\bin\java.exe' -jar build/libs/sboxmarket-1.0.0.jar
#   (run it in the FOREGROUND for this one — the report is printed to stdout
#   precisely so you do not have to go find a log file.)
#
#   PRINTS a framed report. The lines that matter:
#     MONEY RESET — DRY RUN (nothing has been changed)
#     deployment            : SIMULATED
#     invoked by            : SBOX_MONEY_RESET_ENABLED=true
#     destructive execution : NOT requested — SBOX_MONEY_RESET_EXECUTE is unset
#     ... per-wallet table ...            TOTAL REMOVED   30797.70
#     ... transactions by type ...        TOTAL REMOVED   44    31101.90
#       of the DEPOSIT rows:
#         9 carry a 'dev_' reference (fabricated, no payment) totalling 30575.00
#         0 carry any other reference (a real Stripe charge would appear here)
#     Nothing was changed. To execute, re-run with BOTH: ...
#
#   READ THE '0 carry any other reference' LINE. If it is NON-ZERO, the report
#   says so in capitals and you should stop: real payment rows may exist.
#
#   If instead it prints "money-reset refused: TEST deployment — only SIMULATED
#   may have its money state reset", real Stripe keys are now configured and
#   this tool is closed for good. That is by design: do the reset BEFORE the
#   keys go in, not after.
#
#   The app continues booting normally after the report — the dry run does not
#   stop it. Ctrl-C when you have read it.
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 12. ONLY IF THE PLAN ABOVE IS WHAT YOU WANT: re-run with BOTH variables.
$env:SBOX_MONEY_RESET_ENABLED = 'true'
$env:SBOX_MONEY_RESET_EXECUTE = 'true'
& 'C:\Program Files\Java\jdk-17\bin\java.exe' -jar build/libs/sboxmarket-1.0.0.jar
#   PRINTS the same itemised plan first (always — a deletion whose only record
#   is a total is not acceptable), then:
#     MONEY RESET — EXECUTING (this run WILL delete)
#     destructive execution : REQUESTED (SBOX_MONEY_RESET_EXECUTE=true)
#     EXECUTING NOW.
#     MONEY RESET: deleted 44 transaction rows (9 fabricated dev_ deposits
#       totalling $30575.00) and zeroed $30797.70 across 9 wallet balances.
#       Accounts, listings, items, trades and audit history preserved.
#     MONEY RESET COMPLETE — audit row id=<n> eventType=MONEY_RESET
```

```powershell
# ─────────────────────────────────────────────────────────────────────────────
# 13. VERIFY THE RESULT MATCHES THE PLAN. Ctrl-C, then query.
#     WALLETS      -> 9 rows, SUM(BALANCE) = 0.00     (rows KEPT, not deleted)
#     TRANSACTIONS -> 0
#     AUDIT_LOG    -> 38  (37 + exactly one MONEY_RESET row)
#     STEAM_USERS  -> 7, ADMIN 1;  LISTINGS 254, ITEMS 39, TRADES 8 (untouched)
#
# 14. CLEAR THE VARIABLES and relaunch normally (step 8). Confirm they are gone:
Get-ChildItem Env: | Where-Object { $_.Name -like 'SBOX_MONEY_RESET*' }
#     MUST print nothing.
```

**Why the wallet ROWS are kept and only zeroed.** `SeedService.seed()`
re-creates the demo wallet at $250.00 whenever `walletRepository.count() == 0`.
That guard is an idempotency probe, not a gate — so a reset that DELETED wallet
rows would re-fabricate $250 on the next boot by a path nobody would look at.
Nine zeroed rows hold `count()` at 9 forever. **Rehearsed:** after executing the
reset, a clean reboot of the app left `9` wallet rows at `0.00` and `0`
transactions. The seeder did not re-mint.

---

## Rollback — each piece independently, and what it costs you

| Piece | How to undo | What you lose |
|---|---|---|
| **H2CredentialGuard** | `ALTER USER SA SET PASSWORD ''` through the auto-server, then relaunch. **No rebuild needed** — the *old* jar contains no guard and boots against a blank credential. Rehearsed: old jar started in 13.53 s with zero `REFUSING TO START` lines. | The auto-server goes back to accepting `SA` with an empty password from anything on the box that can read `data/sboxmarket.lock.db`. |
| **UnconfiguredAdminReporter** | Nothing to undo. It is read-only — a log banner and an admin-authed GET. To silence it, revert to the old jar. | The boot-time notice that admin id 33 is explained by nothing, and the `/api/admin/security/admin-grants` endpoint. No data changes either way. |
| **MoneyResetService (not run)** | Nothing to undo. Inert without both variables. | Nothing. |
| **MoneyResetService (executed)** | **Restore the backup from step 2.** There is no in-app undo and no compensating transaction — the ledger *is* the record. | Everything written to the database since that backup was taken. Take the backup immediately before, not the night before. |
| **All three at once** | Relaunch `sboxmarket-1.0.0.jar` rebuilt from a commit before `28e6882`, or restore the step-2 backup and put the blank password back. | All of the above together. |

**Verified both directions.** On the restored copy: blank → secret (`ALTER USER`)
made the blank credential fail with `28000` and the secret succeed; secret →
blank reversed it exactly, with the secret then failing `28000` and blank
succeeding. Neither direction is one-way.

---

## The two existing backup zips are BLANK-PASSWORD copies of the whole database

`C:\Users\WW\skinbox-backups\h2-sboxmarket-20260902T044625Z.zip` and
`…20260902T075102Z.zip` were both taken while `SA`'s password was empty. Measured
2026-09-02 — each extracts to a single `sboxmarket.mv.db` (368,640 and 380,928
bytes) and each opened with a **blank password** and read `9` wallets totalling
`30797.70`, `44` transactions and `7` users.

**That is precisely what makes them a working rollback.** If they demanded the
new secret, losing the secret would lose the database with it. They do not, so
the way home is always open.

**And it means the new password does not protect them.** Anyone who can read
that directory gets every wallet row regardless of what `SA`'s password becomes.
The migration protects the *live listening database*, not the archives beside it.
Only backups taken *after* step 4 carry the new credential — and those will need
the secret to read, so do not lose it once you start relying on them.

Treat `C:\Users\WW\skinbox-backups` as being as sensitive as the database itself,
because it is.

---

## Known gap: the H2 backup is BUILT but still not SCHEDULED

*(Superseded in part on 2026-09-02 — see "The scheduled H2 backup" above. What
remains true is the part that matters.)*

The job now exists and is proven: `deploy/h2-backup.ps1` takes the archive, extracts
it, opens the restored copy and compares every table's row count against live.
`config/BackupFreshnessReporter` reads the status file it writes and reports `ok` /
`failed` / `stale` on the admin Health tile and at `GET /api/health/backup`.

**But the daily `SkinBox DB Backup` task still runs `deploy/backup-db.sh`** — the
Postgres path, `pg_dump` against a `sbox-pg` container that is not running. As of
2026-09-02 02:20 its action is unchanged, `LastTaskResult` is **127** from the
09/01 04:00 run, and the next trigger is 09/02 04:00. It will now refuse with exit 3
naming the H2 job rather than failing at 127, which is more honest and still not a
backup.

**So the only archives of the money database are the four taken by hand, and nothing
takes a new one on a schedule.** Two commands repoint the task and fix its
`LogonType: Interactive` principal (which would skip 04:00 entirely with the operator
logged out); both are in "OPERATOR: the task is still not repointed" above, and both
are the operator's to run.

Until then the freshness reporter will read `stale` roughly 26 hours after the last
manual run — correctly. Do not read that as a bug in the reporter.

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

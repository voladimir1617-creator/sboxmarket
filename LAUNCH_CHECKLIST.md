# sboxmarket Launch Checklist

This document covers the env config + ops work needed to take sboxmarket
from local-dev (current state) to a live production deployment. The
backend code is already production-grade — this is about flipping
switches, not writing new code.

---

## 0. What's already done

The hard work — code architecture, security gates, audit trails — is already in place:

- **Steam OpenID auth** with session-fixation defense, `next=` redirect sanitization, audit logging, new-device email security alerts
- **Wallet** with real Stripe integration, 2FA gate on withdrawals, $5000/day deposit + withdraw caps, active-chargeback hold, wallet-freeze, email-verified gate
- **CSV exports** with OWASP CSV-injection defense (`CsvUtil.safeCell`)
- **Session timeout 365d** ("NEVER EXPIRE ACTUALLY" per operator)
- **Spring whitelabel disabled** — branded `GlobalErrorController` instead of leaking framework strings
- **2MB request body cap** — basic DDoS / OOM defense
- **Gzip** on JSON / JS / CSS / CSV / SVG
- **Flyway migrations** ready to run on the `prod` Spring profile
- **Catalogue seed** (25 curated s&box cosmetic items) added to `SeedService` so day-1 visitors see a real `/db` instead of an empty grid

---

## 1. CRITICAL — must be done before public launch

### 1.1 Stripe live keys

`StripeService.isLive()` currently returns `false` because we're on test keys.
Switch the three env vars in your prod environment:

```bash
STRIPE_SECRET_KEY=sk_live_...
STRIPE_PUBLISHABLE_KEY=pk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...   # from your Stripe webhook endpoint config
```

Verify: after restart, hit `GET /api/wallet` (signed in or anon — both return
`stripeLive` in the payload). It should report `true`.

Then in the Stripe dashboard:
- Add a webhook endpoint pointing at `https://YOUR_DOMAIN/api/stripe/webhook`
- Subscribe to: `checkout.session.completed`, `payment_intent.succeeded`,
  `charge.dispute.created`, `charge.refund.updated`
- Copy the signing secret into `STRIPE_WEBHOOK_SECRET`

### 1.2 Postgres

H2 file mode (`./data/sboxmarket.mv.db`) is fine for local dev but won't
survive a real deploy — single-node, no replication, no backup tooling.
Switch via env:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://YOUR_DB_HOST:5432/skinbox
SPRING_DATASOURCE_DRIVER=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=skinbox
SPRING_DATASOURCE_PASSWORD=...   # use a secret manager, not a plaintext env
SPRING_PROFILES_ACTIVE=prod      # enables Flyway migrations + validate ddl
SPRING_JPA_DDL=validate          # fail-loud on schema drift
```

Run order:
1. Create the empty Postgres database
2. Boot the jar — Flyway runs `V1`–`V19` migrations to create the schema
3. Verify: `SELECT count(*) FROM items;` should be 25 (the catalogue seed)
4. Set up nightly backups (`pg_dump` cron + offsite copy)

### 1.3 HTTPS + cookie hardening

Currently `COOKIE_SECURE=false` so the session cookie works over plain HTTP
in dev. In production:

```bash
COOKIE_SECURE=true
COOKIE_SAME_SITE=strict
```

Put a TLS terminator (Caddy, nginx, Cloudflare) in front of port 8082.
The app trusts `CF-Connecting-IP` and `X-Forwarded-For` for client-IP
detection, so any of those work.

### 1.4 Steam OpenID realm = production domain

Currently the OpenID return URL is `http://localhost:8082/api/auth/steam/return`.
Set the prod base URL via Spring's standard mechanism:

```bash
SERVER_PORT=8082                                     # if behind a TLS proxy
APP_BASE_URL=https://skinbox.market                  # used by SteamAuthService.buildLoginUrl
```

Then on Steam's side, register your application's domain at
https://steamcommunity.com/dev/managegameservers if you haven't already.

### 1.5 SMTP for transactional email

Sign-in alerts, withdrawal confirmations, dispute notifications all
depend on `EmailService`. Set:

```bash
SPRING_MAIL_HOST=smtp.your-provider.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=noreply@skinbox.market
SPRING_MAIL_PASSWORD=...                # secret manager
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
APP_MAIL_FROM="SkinBox <noreply@skinbox.market>"
```

Verify: sign in with a test account — within 30s you should receive a
"new sign-in location" email if your IP isn't already in the audit
log for that user (first-ever sign-in does not trigger; the second one
from a different IP does).

---

## 2. HIGH — should be done before public launch

### 2.1 Wallet daily caps — review default of $5000

Current default per `application.yml`:
```yaml
wallet.daily-withdrawal-cap: 5000
```

Confirm $5000 is the right ceiling for your launch — power sellers cashing
out weekly earnings shouldn't bump it; an attacker draining a compromised
account in one session should. Override via env if needed:
```bash
WALLET_DAILY_WITHDRAWAL_CAP=2500
```

### 2.2 Spring profile = `prod`

This is what flips the whole deployment from "dev with autoreload" to
"production with Flyway":

```bash
SPRING_PROFILES_ACTIVE=prod
```

Inspect `src/main/resources/application-prod.yml` to confirm what
this profile does (Flyway on, JPA `ddl-auto: validate`, etc.).

### 2.3 Error tracking

Wire Sentry (or equivalent). The app has a `GlobalErrorController` already
catching everything — adding Sentry just means importing the SDK and
calling `Sentry.captureException(e)` in the catch blocks.

### 2.4 Uptime monitoring

Point Pingdom / UptimeRobot at `GET /api/health` — it returns `{"status":"UP"}`
with a `no-store` Cache-Control. Do **NOT** use `/actuator/health` in prod: the
actuator is disabled there (`management.server.port=-1`, see skinbox.env.example)
so that path 404s. `/api/health` (HealthController) is the public, always-on
liveness probe; `/api/ready` layers a DB-connectivity gate for LB readiness.

### 2.5 Database backups

Document the restore procedure. Practice it once before launch.

---

## 3. MEDIUM — first 30 days post-launch

- Real legal review of `/legal/terms.html` and `/legal/privacy.html`
- Real KYC vendor for the `KYC Approved` badge (currently the badge is rendered but the verification flow may need a real provider)
- Rate limiting at the gateway level (Cloudflare / Caddy plugin)
- Per-IP login throttling (Steam OpenID is already free, but the brute-force surface on your own /api/auth endpoints should be rate-limited)
- Real CSP header — `GlobalErrorController` doesn't set one yet
- Review every `Untitled` / `TODO` / `FIXME` in `app.js` (~9k lines, run a grep)

---

## 4. Code-side gotchas to remember

- **CSS file is huge** (~190k lines) due to many appended override blocks from the parity sessions. Most are no-ops or redundant. Worth a deduplication pass eventually but not blocking launch.
- **CodexHandoff workflow is dormant.** The 8-parallel-agent CSS loop is deprecated. If anyone resumes it, they'll burn tokens fast.
- **Two reverts already landed** (recorded in the codebase as comments, not deletions):
  - `ship #128720` — buyer fee was unilaterally bumped from 0.5% → 2% to match csfloat. Reverted to 0.5% in 9 places in `app.js`.
  - `ships #128563-#128565` — homepage hero / trust strip / footer were hidden on `/` based on a misread of csfloat's home redirect. Hides removed in `design.css`.

---

## 5. Quick deploy smoke test (after env vars are set)

```bash
# 1. Site responds
curl -s -o /dev/null -w "HTTP %{http_code}\n" https://skinbox.market/
# Expect: HTTP 200

# 2. Anon /me works (no console spam from a stray 401)
curl -s https://skinbox.market/api/auth/steam/me
# Expect: {"signedIn":false}

# 3. Anon wallet returns zeroed snapshot, no demo wallet leak
curl -s https://skinbox.market/api/wallet | python -c "import sys,json;d=json.load(sys.stdin);print('balance:',d['balance'],'stripeLive:',d['stripeLive'])"
# Expect: balance: 0 stripeLive: True

# 4. Steam OAuth redirect points at production return URL
curl -s -o /dev/null -w "%{redirect_url}\n" https://skinbox.market/api/auth/steam/login
# Expect: https://steamcommunity.com/openid/login?...openid.return_to=https%3A%2F%2Fskinbox.market%2Fapi%2Fauth%2Fsteam%2Freturn...

# 5. Catalogue is seeded
curl -s "https://skinbox.market/api/items?limit=5" | python -c "import sys,json;d=json.load(sys.stdin);print('items:',len(d.get('items',[])))"
# Expect: items: 5  (the catalogue seed populated)

# 6. Health check passes (NOT /actuator/health — actuator is disabled in prod)
curl -s https://skinbox.market/api/health
# Expect: {"status":"UP"}

# 7. STATIC ASSETS ACTUALLY SERVE — the SPA is dead without them.
#    A 200 on `/` only proves the HTML shell + inline skeleton CSS loaded; the
#    React bundle and stylesheet are SEPARATE requests. Regression f6f7548
#    shipped a resource-handler misconfig that 404'd EVERY /css, /js, /img while
#    `/` still returned 200 — so steps 1-6 all passed against a site that
#    rendered a blank loading skeleton to every visitor for days. Never trust a
#    `/` 200 alone; verify a real asset AND a rendered route:
curl -s -o /dev/null -w "design.css  %{http_code}  %{content_type}\n" https://skinbox.market/css/design.css
# Expect: design.css  200  text/css
curl -s -o /dev/null -w "app bundle   %{http_code}\n" https://skinbox.market/js/main.js
# Expect: app bundle   200
#    Then open https://skinbox.market/ in a browser (or headless) and confirm
#    the marketplace grid actually renders — NOT a bare skeleton — with the
#    console free of 404s for /css, /js, /img. StaticAssetServingSpec pins this
#    at the test layer; this step is its deploy-time mirror.
```

If all seven pass — including a visually-rendered page, not just 2xx status
codes — you're live.

# sboxmarket Steam Bot Sidecar

A small Node.js service that logs in a **dedicated Steam bot account** and sends /
receives / verifies **trade offers** for s&box (Steam **app id 590830**) on behalf of
the sboxmarket marketplace. It replaces the old honor-system "Mark Sent" button with a
bot-escrow model: the bot actually sends the Steam trade offer and the platform verifies
acceptance via the trade-offer state instead of trusting a click.

The Spring Boot backend talks to this sidecar over a tiny token-authenticated HTTP API
(`SteamTradeBotService.groovy` → here). The sidecar is the **only** component that holds
Steam credentials.

---

## What it does

| Endpoint | Method | Purpose |
|---|---|---|
| `/health` | GET | Liveness + login/ready status (no auth). |
| `/offers/send` | POST | Create + send a trade offer giving app-590830 assets to a buyer's trade URL, then auto-confirm via identity secret. |
| `/offers/:id` | GET | Normalized offer status: `active` / `accepted` / `declined` / `expired` / `canceled` / `in_escrow` / `needs_confirmation` / ... |
| `/offers/incoming/:id/accept` | POST | Accept an incoming offer (e.g. a seller depositing an item into bot escrow) and confirm it. |
| `/inventory` | GET | The bot's own app-590830 inventory. |

All endpoints except `/health` require `Authorization: Bearer <BOT_API_TOKEN>`.

---

## Operator setup (go-live checklist)

### 1. Create a dedicated Steam bot account
- Make a **brand-new Steam account** used only for the marketplace bot. Do **not** use a
  personal account.
- Set up an email, verify it, and (recommended) buy/redeem ~$5 of anything so the account
  is not "limited" (limited accounts cannot trade).
- Log into the s&box client at least once on this account if your inventory/escrow rules
  require ownership, and make sure the account can hold app-590830 items.

### 2. Enable the Steam Guard **Mobile** Authenticator
Trade-offer auto-confirmation **requires** the mobile authenticator (not email Steam Guard).
- Install the Steam Mobile app, sign in as the bot, and enable the Mobile Authenticator.
- Keep the recovery code somewhere safe.
- After enabling, there is a mandatory **trade hold (escrow) period** (typically up to 7–15
  days) before the account can send hold-free trades. Plan the go-live date around this, or
  the sidecar will report `in_escrow` offers (handled gracefully — see below).

### 3. Extract `shared_secret` and `identity_secret`
These two secrets drive login 2FA and trade confirmation. Extract them when you enable the
authenticator:
- **Android (rooted)**: read `/data/data/com.valvesoftware.android.steam.community/files/Steamguard-<steamid>`.
- **iOS / no root**: use a desktop authenticator tool such as
  [Steam Desktop Authenticator (SDA)](https://github.com/Jessecar96/SteamDesktopAuthenticator).
  When SDA adds the authenticator it writes a `maFile` (`<steamid>.maFile`, JSON) containing
  both `shared_secret` and `identity_secret`.
- Copy the **base64** `shared_secret` and `identity_secret` values into your `.env`.

> Treat these like passwords. Anyone with them can log in as the bot and approve trades.

### 4. Get a Steam Web API key (optional)
- Visit <https://steamcommunity.com/dev/apikey> while logged in as the bot and create a key.
- Put it in `STEAM_BOT_API_KEY`. If you leave it blank, `steam-tradeoffer-manager` will
  obtain/derive one automatically after the first web login.

### 5. Configure environment
```bash
cd steam-bot
cp .env.example .env
# edit .env and fill in every value
```
- `BOT_API_TOKEN` must **exactly match** the `BOT_API_TOKEN` the backend uses for
  `SteamTradeBotService` (see backend section below). Generate a strong value:
  `openssl rand -hex 32`.

### 6. Install + run
```bash
npm install
npm start
# -> [bot] Logging in as <user> ...
# -> [bot] Logged on. SteamID=...
# -> [bot] Trade manager ready (cookies set).
# -> [bot] HTTP API listening on http://127.0.0.1:4000 (app 590830)
```
Verify:
```bash
curl -s http://127.0.0.1:4000/health | jq
```
You should see `"ready": true`.

### 7. Wire the backend
Set these on the Spring Boot app (env or `application.yml`):
```
STEAM_BOT_BASE_URL=http://127.0.0.1:4000
BOT_API_TOKEN=<same long random token as the sidecar>
```
When `STEAM_BOT_BASE_URL` is **unset**, the backend's `SteamTradeBotService` runs in
**disabled mode** (no calls, safe no-ops) so dev/test environments need no bot.

---

## How it loads `.env`

This sidecar reads only `process.env` and does **not** auto-load `.env` (to avoid a
dependency). Use one of:
- `node --env-file=.env index.js` (Node 20+), or
- a process manager that injects env (systemd `EnvironmentFile=`, Docker `--env-file`,
  pm2 ecosystem file), or
- `export $(grep -v '^#' .env | xargs)` before `npm start` (POSIX shells).

The provided `npm start` runs `node index.js`; pass `--env-file` yourself if you rely on a
`.env` file:
```bash
node --env-file=.env index.js
```

---

## Error / escrow / rate-limit handling

The sidecar normalizes failures into a stable JSON shape so the backend can react without
parsing Steam's prose:
```json
{ "ok": false, "error": "RATE_LIMITED", "message": "..." }
```
Codes: `RATE_LIMITED` (HTTP 429), `ESCROW_HOLD`, `BAD_TRADE_URL`, `INVENTORY_ERROR`,
`NOT_READY` (HTTP 503 — not logged in / cookies not set yet), `BAD_REQUEST` (400),
`ERROR` (generic 502).

- **Trade holds / escrow**: a sent offer the buyer accepts but that Steam puts on hold
  reports status `in_escrow` from `GET /offers/:id`. The backend should treat `in_escrow`
  as *not yet delivered* and keep polling; it becomes `accepted` when the hold clears.
- **Mobile confirmation**: outgoing offers that come back `pending` are auto-confirmed with
  `identity_secret`; `/offers/send` returns `confirmed: true/false` and `status: sent` once
  confirmed.
- **Rate limits**: Steam throttles trade APIs. The backend's poller uses a modest interval;
  on `RATE_LIMITED` it should back off and retry on the next poll tick (it does not crash a
  trade on a transient throttle).

---

## Security notes
- Bind to `127.0.0.1` (default) or a private network only. Do **not** expose this port to
  the internet.
- Every credential is env-only. `.env` is gitignored; only `.env.example` is tracked.
- Rotate `BOT_API_TOKEN` and the Steam secrets if you suspect compromise (rotating the
  mobile authenticator re-triggers the trade-hold period).

---

## Files
- `index.js` — the sidecar (Steam login + HTTP API).
- `package.json` — dependencies (`steam-user`, `steamcommunity`, `steam-tradeoffer-manager`,
  `steam-totp`, `express`).
- `.env.example` — environment template (copy to `.env`).

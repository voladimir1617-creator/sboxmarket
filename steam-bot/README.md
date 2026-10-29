# sboxmarket Steam Bot Sidecar

A small Node.js service that logs in a **dedicated Steam bot account** and sends /
receives / verifies **trade offers** for s&box (Steam **app id 590830**) on behalf of
the sboxmarket marketplace. It replaces the old honor-system "Mark Sent" button with a
full **bot-escrow** model with two legs:

1. **Deposit** (`/offers/request`) — when a seller lists a Steam item, the bot
   requests that specific asset *from* the seller into its own inventory. The
   listing only becomes buyable once the bot genuinely holds the item.
2. **Delivery** (`/offers/send`) — when the item sells, the bot sends the held
   asset to the buyer and the platform verifies acceptance via the trade-offer
   state instead of trusting a click. (If the listing is cancelled / expires
   unsold, the bot sends the held asset back to the seller.)

The Spring Boot backend talks to this sidecar over a tiny token-authenticated HTTP API
(`SteamTradeBotService.groovy` → here). The sidecar is the **only** component that holds
Steam credentials.

---

## What it does

| Endpoint | Method | Purpose |
|---|---|---|
| `/health` | GET | Liveness + login/ready status (no auth). |
| `/offers/send` | POST | Create + send a trade offer **giving** app-590830 assets to a buyer's trade URL, then auto-confirm via identity secret. (Delivery leg.) |
| `/offers/request` | POST | Create + send a trade offer that **requests** (receives, gives nothing) app-590830 assets **from** a seller's trade URL. Returns the deposit `offerId`. (Deposit / escrow leg — uses `addTheirItem`.) |
| `/offers/:id` | GET | Normalized offer status: `active` / `accepted` / `declined` / `expired` / `canceled` / `in_escrow` / `needs_confirmation` / ... Used to poll BOTH delivery and deposit offers. |
| `/offers/:id/cancel` | POST | Cancel an offer the bot sent. Idempotent: `200 {cancelled:true}` when it is dead now (cancelled here, or already canceled / declined / expired), `409 ALREADY_ACCEPTED` (`status` `accepted` or `in_escrow`) when the partner took the item, anything else means not cancelled, retry. The backend calls it before it gives up on a deposit, cancels a held listing or refunds a buyer. |
| `/offers/incoming/:id/accept` | POST | Accept an incoming offer (alternative deposit path — if a seller initiates the trade) and confirm it. |
| `/inventory` | GET | The bot's own app-590830 inventory — used to confirm a deposited asset is now genuinely held before the listing is made buyable. |

### Deposit (escrow) request body / response

`POST /offers/request`
```json
{ "partnerTradeUrl": "https://steamcommunity.com/tradeoffer/new/?partner=...&token=...",
  "assetIds": ["12345678901"], "message": "sboxmarket escrow" }
```
```json
{ "ok": true, "offerId": "44556677", "status": "sent", "confirmed": true }
```
The seller accepts the offer in their own Steam client (their mobile confirmation,
not the bot's — the bot gives nothing). The backend then polls `GET /offers/:id`
until `accepted` and confirms via `GET /inventory` that the asset is held before
flipping the listing buyable.

All endpoints except `/health` require `Authorization: Bearer <BOT_API_TOKEN>`.

---

## Local test run, no Steam account (fake bot)

`fake-bot.js` serves the same API against made-up inventories, so the whole
loop (list, deposit into the bot, buy, delivery, return of an unsold skin) runs
on one machine with nothing real moving. It needs no `npm install`.

```bash
cd steam-bot
BOT_API_TOKEN=local-test npm run fake        # http://127.0.0.1:4000
```
Start SkinBox with:
```
STEAM_BOT_BASE_URL=http://127.0.0.1:4000
BOT_API_TOKEN=local-test
STEAM_INVENTORY_BASE_URL=http://127.0.0.1:4000   # steam.inventory.base-url
```
Every user starts with three fake skins. Partners accept offers by themselves
after `FAKE_ACCEPT_AFTER_MS` (default 2000; `-1` means never), and
`POST /fake/offers/:id/accept` or `/decline` settles one by hand. The inventory
override only takes a loopback address, so it can never point real sellers at
anything but Steam. `npm test` runs the sidecar's and the fake's tests.

### Trade Protection on a local run

SkinBox holds a deposited skin off sale for `steam.escrow.trade-protection-hours`
(default 192, eight days, the hold Skin.Club uses) before its listing goes live.
On a fake-bot run that means a listing waits eight days, so set
`STEAM_ESCROW_TRADEPROTECTIONHOURS=0` (Spring's env form of
`steam.escrow.trade-protection-hours=0`)
to see a deposit go live straight away. To play Steam's protection itself,
start the fake with `FAKE_PROTECT_MS=60000`: skins that reach the bot read
`tradable: false` for a minute and the bot cannot send them.
`POST /fake/bot-items/:assetId/unlock` ends that early, and
`POST /fake/bot-items/:assetId/reverse` plays the seller reversing the deposit.

## The go-live gate

`index.js` refuses to start unless `STEAM_BOT_LIVE=yes`. It is the one process
that logs in to a real Steam account and moves real skins, so it stays off until
the owner says go live.

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
- Not needed: the sidecar ignores `STEAM_BOT_API_KEY` (index.js logs that it is unused).
  `steam-tradeoffer-manager` runs with `useAccessToken: true` and authenticates its Web API
  calls with the login session's access token, so no key is looked up or created. Leave it blank.

### 5. Configure environment
```bash
cd steam-bot
cp .env.example .env
# edit .env and fill in every value
```
- `BOT_API_TOKEN` must **exactly match** the `BOT_API_TOKEN` the backend uses for
  `SteamTradeBotService` (see backend section below). Generate a strong value:
  `openssl rand -hex 32`. Re-running `setup-steam-bot.ps1` keeps the token already in `.env`.
- **Quote the password if it contains `#`** (or spaces): `STEAM_BOT_PASSWORD='pa#ss'`.
  Node's `--env-file` reads an unquoted `#` as the start of a comment and silently cuts the
  password there; Steam then answers `InvalidPassword` and the bot stops retrying. Use
  double quotes instead if the password contains a `'`.
- `BOT_HOST` must be `127.0.0.1`, `::1` or `localhost`. The sidecar refuses to start on any
  other address unless `BOT_ALLOW_REMOTE=yes` is also set (only for a private network).
- `STEAM_BOT_STATE_FILE` (optional, default `steam-bot/data/state.json`, gitignored) is where
  the bot keeps the offers it sent and the ones still awaiting its mobile confirmation, so a
  restart does not strand them. It holds offer, asset and Steam ids only.

### 6. Sync the clock, install + run
**Keep the bot PC's clock synced** (Windows: Settings > Time & language > Date & time >
"Set time automatically" on, then **Sync now**; Linux: `timedatectl set-ntp true`). Steam
Guard codes are time-based. The bot reads Steam's own clock at login
(`SteamTotp.getTimeOffset`) and logs a warning if the PC is 10 s or more off, and when Steam
rejects a code it waits for the next 30 s window instead of re-sending it. It never prompts
on the console for a code; an email-code request or repeated rejections stop the login with
the reason in `/health` `lastError`.

Requires **Node.js 20.6 or newer** (`--env-file` first appeared in 20.6; current LTS is fine).
```bash
npm install
node --env-file=.env index.js   # Node 20.6+; plain `npm start` does NOT read .env
# -> [bot] Logging in as <user> ...
# -> [bot] Logged on. SteamID=...
# -> [bot] Trade manager ready (cookies set).
# -> [bot] HTTP API listening on http://127.0.0.1:4000 (app 590830)
```
Verify:
```bash
curl -s http://127.0.0.1:4000/health | jq
```
You should see `"ready": true`. That does **not** prove the token: `/health` takes none.
Check it with any token-protected GET, using the exact `BOT_API_TOKEN` the backend has:
```bash
curl -s -H "Authorization: Bearer $BOT_API_TOKEN" http://127.0.0.1:4000/inventory | jq '.ok, .count'
# Windows PowerShell:
#   curl.exe -s -H "Authorization: Bearer <token>" http://127.0.0.1:4000/inventory
```
`"ok": true` means the token matches. `{"ok":false,"error":"unauthorized","retryable":false,...}`
(HTTP 401) means the two `BOT_API_TOKEN` values differ; the backend sees the same 401 on every
call and, because it is marked `retryable:false`, will not keep retrying it.

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
- `node --env-file=.env index.js` (Node 20.6+), or
- a process manager that injects env (systemd `EnvironmentFile=`, Docker `--env-file`,
  pm2 ecosystem file), or
- `export $(grep -v '^#' .env | xargs)` before `npm start` (POSIX shells).

The provided `npm start` runs `node index.js`; pass `--env-file` yourself if you rely on a
`.env` file:
```bash
node --env-file=.env index.js
```

---

## Steam Trade Protection

Steam lets the sender of a trade reverse it for 7 days, and the receiver cannot
move the item on during that window. It is confirmed for CS2; for s&box (590830)
it is not confirmed either way. `GET /inventory` therefore returns every skin
the bot holds, with Steam's own `tradable` flag, rather than tradable skins only
(asking for tradable only hid every protected deposit from the backend).

The backend then:
- keeps a newly deposited skin's listing off sale (custody `IN_TRADE_PROTECTION`)
  until both `trade-protection-hours` have passed since the trade completed and
  Steam reports the skin tradable;
- calls the deposit `REVERSED` and cancels the listing if the skin leaves the bot
  during that window (two inventory reads, `missing-confirm-ms` apart);
- once an hour compares the bot inventory with custody, takes off sale any live
  listing whose skin the bot no longer holds, and lists skins nobody expects.
  Staff read the result at `GET /api/admin/steam-bot/inventory-check` and can run
  it now with `POST` to the same path. It never sends or moves a skin.

After the first real deposit, if the skin arrives tradable, s&box is not under
Trade Protection and `trade-protection-hours` can go to 0.

## Error / escrow / rate-limit handling

The sidecar normalizes failures into a stable JSON shape so the backend can react without
parsing Steam's prose:
```json
{ "ok": false, "error": "RATE_LIMITED", "message": "..." }
```
Codes: `RATE_LIMITED` (HTTP 429, eresult 84 or a throttle message), `LIMIT_EXCEEDED`
(502, eresult 25: too many offers outstanding or the partner's inventory is full; retryable),
`ESCROW_HOLD`, `BAD_TRADE_URL` (400), `INVENTORY_ERROR` (final only for eresult 26, the asset
is gone; retryable when known only from the message text),
`NOT_READY` (HTTP 503 — not logged in / cookies not set yet), `STEAM_UNAVAILABLE` (503),
`TRADE_BANNED_OR_UNTRADABLE`, `ASSET_COMMITTED` (409), `GIVES_ITEMS` (409),
`OFFER_NOT_ACTIVE` (409), `NOTHING_TO_RECEIVE` (409), `BAD_REQUEST` (400), `ERROR` (502),
and `unauthorized` (401, `retryable:false`, `configError:true`: the bearer token is wrong).
Failures also carry `retryable: true/false`. steam-tradeoffer-manager's `err.cause`
(`TradeBan`, `TargetCannotTrade`, `OfferLimitExceeded`, `ItemServerUnavailable`) decides
first, then Steam's numeric `eresult`, then the message text.

- **Trade holds / escrow**: a sent offer the buyer accepts but that Steam puts on hold
  reports status `in_escrow` from `GET /offers/:id`. The backend should treat `in_escrow`
  as *not yet delivered* and keep polling; it becomes `accepted` when the hold clears.
- **Mobile confirmation**: outgoing offers that come back `pending` are confirmed with
  `identity_secret`; `/offers/send` returns `confirmed: true/false` and `status: sent` once
  confirmed. A failed confirmation is retried every `STEAM_BOT_CONFIRM_RETRY_MS` for that
  offer only for the first hour, then on a doubling delay (1 min up to 30 min) until the offer
  leaves `needs_confirmation`, and given up only after a week (`/health` shows
  `pendingConfirmations`). The list is saved to `STEAM_BOT_STATE_FILE` on every change and
  reloaded at boot, so a restart neither forgets an offer awaiting confirmation nor sends a
  second offer for it. The bot
  never runs steamcommunity's confirmation poller with the secret, because that confirms
  **every** pending trade on the account, including one created with a stolen web session.
- **Retried sends**: the backend times out at 15 s and re-sends, and Steam plus a
  confirmation can take longer. Before sending, the bot looks for a live (or, for the same
  partner and items, accepted) offer it already sent for those items, in its own memory and
  in Steam's active-offer list so it survives a restart. Same partner and items: the existing
  `offerId` comes back with `duplicate: true` and nothing new is sent. Any other live offer
  holding one of those items: `409 ASSET_COMMITTED`, so one skin is never offered to two
  people. If Steam cannot be asked, the send is refused (`503`, retryable) rather than risked.
- **Incoming offers**: `/offers/incoming/:id/accept` only accepts an active offer that gives
  the bot items and asks for none of its own (`GIVES_ITEMS` otherwise).
- **Offer message**: Steam keeps 128 characters. The backend's `... · code ABC123` is shortened
  from the front so the security code always survives; line breaks and control characters
  are removed. `GET /offers/:id` returns the `message` Steam holds.
- **Re-login**: after a fatal Steam error the bot logs in again after 30 s, doubling up to
  15 min (30 min on Steam's login throttle). A wrong password is never retried, so the account
  is not locked; fix `STEAM_BOT_PASSWORD` and restart. An expired web session is renewed. If
  setting up the trade session fails (`/health` `lastError: setCookies: ...`), the bot asks
  for a new web session after 60 s, doubling up to 15 min, instead of staying `NOT_READY`.
- **Rate limits**: Steam throttles trade APIs. The backend's poller uses a modest interval;
  on `RATE_LIMITED` it should back off and retry on the next poll tick (it does not crash a
  trade on a transient throttle).

---

## Security notes
- Bind to `127.0.0.1` (default). The sidecar refuses any non-loopback `BOT_HOST` unless
  `BOT_ALLOW_REMOTE=yes`. Do **not** expose this port to the internet.
- Every credential is env-only. `.env` is gitignored; only `.env.example` is tracked.
- Requests are checked before Steam is called: trade URLs must be
  `https://steamcommunity.com/tradeoffer/new/?partner=N&token=T`, asset and offer ids must be
  digits, at most 20 assets per offer, no repeats. Trade tokens are blanked in logs. The API
  token is compared in constant time.
- Rotate `BOT_API_TOKEN` and the Steam secrets if you suspect compromise (rotating the
  mobile authenticator re-triggers the trade-hold period).

### npm advisories (checked 2026-10-16: `npm audit` reports 0)
`package.json` pins patched versions of the Steam libraries' sub-dependencies through
`overrides` (protobufjs, adm-zip, qs, proxy-addr, ip-address). Four old packages inside
`steamcommunity` have no patched release that keeps their API, so they are replaced:
- `request` -> `@cypress/request` 4, the maintained drop-in fork (redirect SSRF fixed, no
  `uuid` 3, current `form-data` and `tough-cookie`).
- `lodash.pick`, `nth-check`, `image-size` -> tiny wrappers in `shims/` that keep the call
  shape `cheerio` 0.22 / `css-select` 1 / `steamcommunity` expect and forward to patched
  `lodash` 4.18, `nth-check` 2 and `image-size` 2. They are root dependencies so the
  `"$name"` overrides can point at them; keep the `shims/` folder next to `package.json`.

Re-run `npm audit` before going live and whenever a Steam library releases.

---

## Files
- `index.js` — the sidecar (Steam login + HTTP API).
- `lib/bot-core.js` — the trade logic behind every route, tested against a stand-in Steam.
- `lib/guards.js` — request checks, message fitting, error codes (shared with the fake).
- `lib/reconnect.js` — the re-login policy.
- `fake-bot.js` — the fake Steam for local runs; `npm test` runs every test without `npm install`.
- `shims/` — wrappers that swap three unpatched sub-dependencies for patched ones (see above).
- `package.json` — dependencies (`steam-user`, `steamcommunity`, `steam-tradeoffer-manager`,
  `steam-totp`, `express`).
- `.env.example` — environment template (copy to `.env`).

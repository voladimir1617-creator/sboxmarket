# sboxmarket e2e (Playwright)

Headless end-to-end customer-flow tests that run against a **live server**.
They are the release gate for "safe for real customers."

## Run

```bash
cd e2e
npm install                 # once
npx playwright install chromium webkit   # once (downloads the browsers)
npx playwright test         # run all (against http://localhost:8082)
npx playwright test --headed # watch it drive a real browser
npx playwright show-report  # open the last HTML report
```

Point it at another environment:

```bash
E2E_BASE_URL=https://skinbox.market npx playwright test   # smoke prod
```

## The server must be started with the dev-login opt-in

Auth uses the dev-login endpoint (`auth.setup.js`), so this targets a dev build
— not a real Steam login. **That endpoint is closed by default and has to be
asked for.** `GET /api/auth/steam/dev-login` mints a one-year session for any
user id with no credential of any kind, so "this is not production" is not by
itself authorisation to serve it — that was the state of the running app on
2026-09-01, and the loopback bind was its only control while a Cloudflare
tunnel sat beside that lock connecting *from* loopback.

So the app under test must be launched with `SBOX_DEV_LOGIN_ENABLED=true` in
its **process environment**. It is read from there and from nowhere else — not
from `application.yml`, not from a profile, not from a `-D` flag — so it cannot
be inherited by a deployment that never decided to have it.

```bash
# bash — from the repo root
SBOX_DEV_LOGIN_ENABLED=true \
  java -Dloader.path=build/classes/groovy/main,build/resources/main \
       -cp build/libs/sboxmarket-1.0.0.jar \
       org.springframework.boot.loader.launch.PropertiesLauncher
```

```powershell
# PowerShell — from the repo root
$env:SBOX_DEV_LOGIN_ENABLED = 'true'
java -Dloader.path=build/classes/groovy/main,build/resources/main `
     -cp build/libs/sboxmarket-1.0.0.jar `
     org.springframework.boot.loader.launch.PropertiesLauncher
```

**That is the only variable this suite needs, and it is not the money one.**
There is a second, separate opt-in — `SBOX_DEV_CREDIT_ENABLED` — which
authorises the in-process fabricated wallet credit (`devModeDeposit`, up to
$5,000 per wallet per 24h against no payment) and the simulated Stripe Connect
onboarding. See `config/DevCreditGate.groovy`.

**Leave it shut unless you run the money flows.** `wallet.spec.js` only renders
the deposit *form*, and against a long-lived dev database where user 1 already
has money and items the `anon` and `chromium-auth` projects pass with the credit
door shut. Two things need it open, and only on a throwaway database:

- **A fresh database.** User 1 starts with $0 and an empty Platform Inventory,
  so `auth.setup.js` tops the wallet up to $100 and buys one cheap SkinBox-held
  item. With the door shut it says so in one line and the money specs fail on
  the empty wallet.
- **The `multi-user` project** (`*.multi.spec.js`). Three users at once pay with
  real dev-mode deposits and are checked to the cent after every step.

CI runs all three projects on an in-memory database with both doors open. Two names exist precisely so that a harness needing a session
does not silently also get a money printer — one variable answering both
questions would be the same conflation `MoneyMode` was written to delete.

Check it took, before blaming the tests:

```bash
curl -s http://localhost:8082/api/auth/steam/dev-login
#  (nothing / a redirect)                       -> the door is open, run the suite
#  {"error":"dev-login disabled: ..."}          -> the reason is in the body
```

`auth.setup.js` reads that same body and fails with it, so a shut door shows up
as one line naming the variable rather than a 15-second timeout.

**Never set it on a server that handles real money.** The gate refuses anyway —
a live Stripe key or the `prod` profile shuts the door regardless of the
variable — but the rule is worth stating: this is a QA affordance for a box
whose accounts are worthless.

Any environment you point `E2E_BASE_URL` at needs the same thing, which is why
`E2E_BASE_URL=https://skinbox.market` cannot run the signed-in projects — and
should not be able to.

## What's covered (20 specs)

| Project | Browser | Specs |
|---|---|---|
| `anon` | Chromium | home, market grid, item detail, /db, wallet auth-gate centered, search, nav chrome, deep-route OG shell |
| `chromium-auth` | Chromium | buy-now confirm (fee math + total), add-to-cart → checkout, bargain validation, sell list 2% fee math, wallet deposit + withdraw |
| `multi-user` | Chromium | three users (2, 3, 4) in their own browsers at once: deposits, trade URL, buy → accept → send → confirm → seller paid, two buyers racing one listing, no resale before delivery, resale through the cart, offer → counter → accept, auction bid + Buy Now, buy order auto-fill, email verify + payout from the payouts card, notifications, take-down. Every balance checked to the cent |
| `mobile-auth` | **WebKit / iPhone 13** | home / market / item — zero horizontal overflow + bottom-nav present |

`auth.setup.js` dev-logs-in once and saves `storageState` to `.auth/user.json`,
reused by the signed-in projects. Workers are serial (the dev money flows share
one user's session/cart).

A CI box with no downloaded browsers can point at an installed Chromium:
`E2E_CHROMIUM_PATH=/path/to/chromium npx playwright test --project=multi-user`.

## Conventions

- `*.anon.spec.js` → runs signed-out (no auth dependency).
- `*.mobile.spec.js` → runs on the WebKit iPhone project.
- `*.multi.spec.js` → runs in the `multi-user` project, one browser context per user.
- everything else → runs signed-in on Chromium desktop.
- `chromium-auth` money flows mostly stop at the dialog (opened + cancelled,
  forms filled + asserted); `buy-roundtrip` and `offer-roundtrip` really submit,
  and `multi-user` runs every flow to the end. Cart state resets via `DELETE /api/cart`.

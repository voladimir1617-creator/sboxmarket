# SkinBox — Go-Live Checklist (accept real customers, real sell + withdraw)

The marketplace **code** for the full money path is built, tested green, and
committed. What remains to actually take real customers is **operator
provisioning** — accounts and secrets only you can create. This doc is the exact
sequence. Nothing here changes the fee model (2% trade fee, 2% optional Trade
Protection, 12% Steam reference is display-only — all frozen).

The product is feasible: s&box cosmetics are real, tradeable Steam Community
items on app **590830** with a live Steam market — so the csfloat model (sell →
deliver via Steam trade → get paid → withdraw cash) maps onto s&box 1:1.

---

## 1. Database (Postgres + Flyway) — required for prod

Dev uses H2 (auto-schema). Prod uses Postgres with Flyway migrations. On first
prod boot these run automatically when `FLYWAY_ENABLED=true`:

- `V72`  — wallet Stripe Connect columns (`stripe_connect_account_id`, `payouts_enabled`)
- `V200` — `steam_delivery_attempts` (per-trade Steam offer tracking)
- `V210` — `escrowed_items` (seller→bot custody state)
- `V220` — item `image_url` safety column (renders)

**Action:** provision Postgres, set the prod DB env vars (see `application-prod.yml` /
`deploy/skinbox.env.example`), and `FLYWAY_ENABLED=true`.

---

## 2. Money-OUT — Stripe Connect Express (real payouts + KYC)

Without this, withdrawals are **rejected** with `CONNECT_ONBOARDING_REQUIRED`
(by design — no more silent stub). To enable real cash-out:

1. **Stripe Dashboard → Connect**: enable Connect, choose **Express**, fill the
   platform business profile/branding, enable the **transfers** capability.
2. **Fund the platform balance** (deposits feed it; or top up) so `Transfer`s
   have a source.
3. **Webhook**: Dashboard → Developers → Webhooks → add endpoint
   `https://<your-domain>/api/stripe/webhook`, subscribe to your existing deposit
   /dispute events **plus `account.updated`**. Put its signing secret in
   `STRIPE_WEBHOOK_SECRET`.
4. **Env** (prod): `STRIPE_SECRET_KEY` (live `sk_live_…`), `STRIPE_PUBLISHABLE_KEY`,
   `STRIPE_WEBHOOK_SECRET`, `STRIPE_SUCCESS_URL`, `STRIPE_CANCEL_URL`,
   `APP_PUBLIC_URL`. Optional Connect overrides (default off `APP_PUBLIC_URL`/US):
   `STRIPE_CONNECT_RETURN_URL`, `STRIPE_CONNECT_REFRESH_URL`, `STRIPE_CONNECT_COUNTRY`.

**Seller experience once live:** Wallet → Withdraw shows a **"Set up cash-out"**
card → Stripe-hosted onboarding/KYC → `account.updated` flips `payouts_enabled`
→ withdrawals create a real `Transfer` to the seller's connected account, which
settles to their bank/card on Stripe's payout schedule. KYC is handled by Stripe.

> Until `STRIPE_SECRET_KEY` is a real live key, the app is in dev/sim mode:
> onboarding + withdrawals are clearly simulated and move **no real money**.

---

## 3. Item delivery — Steam trade bot (automated, bot-escrow)

Without a bot configured (`STEAM_BOT_BASE_URL` unset) the escrow/delivery path
is **inert** and listings fall back to the legacy manual flow. To enable real
automated delivery:

1. Create a **dedicated Steam bot account**; add ~$5 of value to lift the Steam
   "limited account" restriction (required to trade).
2. Enable the **Steam Guard Mobile Authenticator** on it. Wait out the mandatory
   trade hold (7–15 days) before go-live, or early offers report `in_escrow`
   (handled gracefully, but nothing delivers until the hold clears).
3. Extract the **`shared_secret`** + **`identity_secret`** (via Steam Desktop
   Authenticator `maFile`, or a rooted-Android Steamguard file).
4. (Optional) create a Steam Web API key at steamcommunity.com/dev/apikey.
5. Configure the sidecar: `cd steam-bot && cp .env.example .env`, fill
   `STEAM_BOT_USERNAME/PASSWORD/SHARED_SECRET/IDENTITY_SECRET`, optional
   `STEAM_BOT_API_KEY`, and a long random `BOT_API_TOKEN`. **Never commit `.env`.**
6. Run it: `npm install && node --env-file=.env index.js`; verify
   `curl 127.0.0.1:4000/health` → `"ready": true`. Bind to localhost/private only.
7. On the Spring app set `STEAM_BOT_BASE_URL=http://127.0.0.1:4000` and the
   **matching** `BOT_API_TOKEN`.

**Flow once live:** seller lists a Steam item → bot requests it into custody
(`PENDING_DEPOSIT → IN_CUSTODY`, listing becomes buyable) → on sale the bot
sends it to the buyer and polls Steam until `accepted` → the existing escrow
release credits the seller's wallet (frozen fee math) → unsold/cancelled items
are auto-returned to the seller.

**Seller requirement:** each seller must set their **Steam trade URL** in Profile,
or their Steam listing is held `PENDING_ESCROW` (not buyable) with a clear
"add a trade URL" prompt — never listed un-escrowed.

---

## 4. Steam auth / inventory / pricing — already real, just needs keys

Steam OpenID login, public-inventory ownership checks (app 590830), and Steam
market price sync are real and production-quality. Set `STEAM_API_KEY` (and the
OpenID realm/return-url) in prod env.

---

## 5. Pre-launch verification (after the above)

- [ ] Prod boots, Flyway applied V72/V200/V210/V220 (check startup log).
- [ ] Steam login works end-to-end on the public domain.
- [ ] A test seller: set trade URL → list a real s&box item → confirm it goes
      `IN_CUSTODY` and becomes buyable.
- [ ] A test buyer: deposit (real Stripe test→live), buy → bot delivers →
      seller wallet credited.
- [ ] Seller: complete Stripe Connect onboarding → withdraw → real `Transfer`
      lands.
- [ ] Legal/compliance: ToS, AML/KYC posture (Stripe Identity covers payee KYC),
      tax reporting (1099-K etc.), and confirm your stance vs Steam's Subscriber
      Agreement on third-party real-money trading (the P2P/bot model rides Steam
      trade offers — Valve can throttle; plan for it).

---

## What is NOT done (honest)

- **No real bot account / no Stripe Connect activation** — operator-gated above.
- **Tax-form generation (1099-K)** is not built — add before scaling payouts.
- The fee model, escrow state machine, fraud caps, ban/freeze/dispute/chargeback
  handling, Steam auth, and the suite (4166 tests green) are production-grade; the
  two operator steps (§2, §3) are the gate between "demo" and "live".

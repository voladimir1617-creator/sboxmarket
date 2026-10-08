# Putting SkinBox online (free demo copy)

This puts a **demo copy** of SkinBox on the internet at an address like
`https://skinbox-demo.onrender.com` that anyone can open.

What visitors can do: browse the market, open items, see price charts and
recent sales, look at seller stalls, and sign in with their own Steam account.

What is switched off on purpose: putting money in, taking money out, seller
payouts, the admin and support panels, and the developer "sign in as user 1"
shortcut. The demo has no Stripe account at all, so it cannot charge a card or
pay anyone. The items and sellers on it are the same made-up demo stock you
see on your PC.

Real money stays off until you say "go live". That is a separate setup with
your real Stripe keys.

## The host: Render

- Free plan, no credit card needed to sign up.
- The site falls asleep after 15 minutes with no visitors. The next visitor
  waits while it wakes up: up to about 5 minutes on the free plan's small
  share of a processor (measured on the same image held to that size).
- The free database stops working 30 days after it is made, and Render deletes it 14 days after that.
  Nothing real is in it, so when it goes you just press the deploy button
  again and the demo stock comes back.
- If you later want it always awake and keeping its data, Render's paid
  plans start at about $7 a month for the site and about $6 a month for the
  database. You do not need either for the demo.

## What you press

1. Open **https://render.com/deploy?repo=https://github.com/voladimir1617-creator/sboxmarket**
2. Click **Sign in with GitHub** and allow Render to see the sboxmarket repo.
   (This creates your free Render account. No card.)
3. You land on a page called **Blueprint** that already lists
   `skinbox-demo` and `skinbox-demo-db`, both marked **Free**.
   Type any name in **Blueprint Name** (for example `skinbox`).
4. Click **Deploy Blueprint**.
5. Wait. The first build takes around 10 minutes. When the `skinbox-demo` box
   turns green and says **Live**, click the link under its name. That is your
   site.

After that, every change merged into `main` on GitHub updates the site by
itself.

## If something looks wrong

- **Page says "Service unavailable" or spins**: it is waking up. Wait a
  few minutes and refresh.
- **Build failed**: open `skinbox-demo`, click **Logs**, copy the last
  screen of red text and paste it to Claude.
- **Want it gone**: in Render open `skinbox-demo`, then **Settings**, then
  **Delete Web Service**. Do the same for `skinbox-demo-db`.

## For whoever maintains this

- `render.yaml` is the Blueprint. It builds `Dockerfile` and runs it with
  `SPRING_PROFILES_ACTIVE=demo`.
- `application-demo.yml` is the demo profile: Postgres through Flyway, port
  from `$PORT`, public URL from `RENDER_EXTERNAL_URL`, demo seed on, no
  Stripe, Steam price polling off, Steam delivery and escrow off.
- `PublicDemoFilter` answers 404 for deposits, withdrawals, payout
  onboarding, the Stripe webhook, admin, CSR, `/api/database`, dev-login,
  the H2 console and actuator.
- `PublicDemoGuard` stops boot if `prod` is also active, a non-test Stripe
  key is set, or `SBOX_DEV_LOGIN_ENABLED`, `SBOX_DEV_CREDIT_ENABLED`,
  `SBOX_MONEY_RESET_ENABLED` or `SBOX_AGENTATION` is set.
- Health check: `GET /api/health` (process up), `GET /api/ready` (database
  reachable).
- Local smoke test of the same image:
  `docker build -t skinbox-demo .`, start `postgres:16-alpine`, then run the
  image with `SPRING_PROFILES_ACTIVE=demo PORT=10000 COOKIE_SECURE=false`
  and `DB_HOST/DB_USER/DB_PASSWORD` pointing at it.

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

## The hosts: Render (site) and Neon (database), both free

- Render free plan for the site. No card. The site falls asleep after 15
  minutes with no visitors; the next visitor waits while it wakes up (up to
  about 5 minutes on the free plan's small share of a processor).
- Neon free plan for the database: 1 GB, no expiry, no card. (Render's own
  free database is deleted 30 days after it is made, which is why it is not
  used any more.) Neon pauses the database when idle and wakes it on the
  next request in about a second.
- Neither can carry real money: the free site sleeps, so the Steam bot and
  timers would stop. Real money is a separate setup after "go live".

## What you press

If `skinbox-demo` already exists on Render (it does since 2026-10-13):

1. Open **https://neon.tech**, click **Sign up**, choose **Continue with
   GitHub**. No card.
2. Create a project: any name (for example `skinbox`), Postgres 16, region
   **AWS US West (Oregon)** (same place as the Render site).
3. On the project page click **Connect**, pick **Java** in the list, and keep
   that window open. It shows the host, database, user and password.
4. In Render open `skinbox-demo`, then **Environment**, then add:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME` = the user
   - `SPRING_DATASOURCE_PASSWORD` = the password
   Delete `DB_HOST`, `DB_PORT`, `DB_NAME` and `DB_USER` there if they are
   listed. Click **Save, rebuild, and deploy**.
5. Wait about 10 minutes. When `skinbox-demo` says **Live**, open
   https://skinbox-demo.onrender.com. The demo stock is filled in by itself.

Starting from nothing instead: do steps 1-3, then open
**https://render.com/deploy?repo=https://github.com/voladimir1617-creator/sboxmarket**,
sign in with GitHub, click **Deploy Blueprint**, and paste the three values
when Render asks for them.

After that, every change merged into `main` on GitHub updates the site by
itself.

## If something looks wrong

- **Page says "Service unavailable" or spins**: it is waking up. Wait a
  few minutes and refresh.
- **Build failed**: open `skinbox-demo`, click **Logs**, copy the last
  screen of red text and paste it to Claude.
- **Want it gone**: in Render open `skinbox-demo`, then **Settings**, then
  **Delete Web Service**. In Neon, delete the project.

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
  and `DB_HOST/DB_USER/DB_PASSWORD` (or the three `SPRING_DATASOURCE_*`)
  pointing at it.

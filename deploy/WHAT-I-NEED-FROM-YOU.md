# The six values that turn skinbox.market back on

Written 2026-09-01. Everything else is done; this is the whole list.

The site is deliberately offline right now. Not because it's broken — because with no real
Stripe key it falls back to a test key, and that fallback is what opened a password-free login
and a $5,000/day free-credit door. Both are fixed in code now, but the site stays dark until
these are set, because a deployment that can't tell whether it's handling real money is the
condition that caused every one of those holes.

---

## Part 1 — Stripe (three values)

The app reads exactly three. Nothing else about Stripe is missing.

| env var | what it looks like | where |
|---|---|---|
| `STRIPE_SECRET_KEY` | `sk_live_…` or `rk_live_…` | Stripe Dashboard → Developers → API keys |
| `STRIPE_PUBLISHABLE_KEY` | `pk_live_…` | same page |
| `STRIPE_WEBHOOK_SECRET` | `whsec_…` | Developers → Webhooks → your endpoint → *Signing secret* |

**The webhook secret is not on the API keys page** and is the one people miss. You get it after
creating a webhook endpoint pointing at `https://skinbox.market/api/stripe/webhook`.

`rk_live_` (a restricted key) works and is genuinely safer than `sk_live_` — the code accepts
both, and until tonight it accepted `rk_live_` in one place and not another, which was a live
hole. If you use a restricted key, it needs write access to Checkout Sessions, PaymentIntents,
Refunds, Transfers and Connect accounts.

### The thing worth checking before you fetch them

An account existing, keys working, and Stripe's **restricted-business review** having approved
this vertical are three different states, and only the third one actually clears you. Their
policy names *"sale of in-game currency or game items, unless the business is the operator of
the virtual world"* — and Facepunch operates s&box, not us.

So: log in and check whether your account has any restriction or review notice on it, before
wiring live keys into a public site. If it's under review, that is worth knowing now rather
than on the first real charge. I can't check this for you — it's inside your account.

---

## Part 2 — the database (three values, and it costs nothing)

You said local-host everything, and this is local-hostable. You do **not** need a paid database.

| env var | value |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/sboxmarket` |
| `SPRING_DATASOURCE_USERNAME` | whatever you set |
| `SPRING_DATASOURCE_PASSWORD` | whatever you set |

Postgres runs on this machine for free. Docker is already installed here (launch it via
`start-docker.ps1` — it has a socket bug that stops it starting any other way), or you can
install Postgres natively.

**One caution from tonight.** Three leftover containers from an old experiment were found
publishing a database on every network interface with an unchanged default password. They were
stopped. When you set this up, bind Postgres to `127.0.0.1` only and give it a real password —
the app reaches it over loopback and nothing else should.

---

## What happens after

Set the six, then start the app. It refuses to boot on a bad configuration rather than starting
in a half-live state, so a mistake here shows up immediately and loudly instead of quietly.

Two things that are **not** blocking, in case they're on your list:

- **A Steam bot account is not needed.** You've been told it is, for weeks, because of a 7–15
  day authenticator hold. The hold is real but it applies to the bot, and the bot is optional —
  with it unset, escrow switches off, listings go straight to active, and delivery runs the
  manual path, which is fully built and walked end to end by 22 tests to a $98.00 payout on a
  $100 sale. Turning the bot on would actually *create* a problem: your own trade-safety page
  tells sellers "never accept a trade offer from a SkinBox bot — we don't have one", and the
  moment it's enabled it sends every seller exactly that offer.
- **Nothing here costs money.** No hosting, no server, no subscription.

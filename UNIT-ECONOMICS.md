# Unit economics — what the 2% actually has to cover

Computed 2026-08-21 against the code, not against a business plan.
Supersedes the 2026-08-19 version, which measured the **absorbed-cost** pricing
this replaced.

## The pricing decision

> "We charge a 2% selling fee, and all the deposit fees are Stripe fees, and
> withdrawal fees too — I'll pass Stripe fees on. So we only charge 2% for
> selling."

Payment processing is **passed through at cost**. The platform's own revenue is
the 2% selling fee and nothing else.

| leg | who pays the processor | platform margin |
|---|---|---|
| deposit | the depositor — credited net | **$0.00** |
| withdrawal | the withdrawer — paid out net | **$0.00** |
| a trade | the **seller**, 2% of price | **2% of price** |

## The three facts that set this

1. **`FEE_RATE = 0.02`** — `service/TradeService.groovy:46`. Charged to the
   seller on release: `TradeService` credits them `price - feeAmount`. The buyer
   pays the sticker price. **Unchanged by this work.**
2. **Deposits credit NET** — `service/StripeService.groovy:2788` credits
   `tx.amount - tx.feeAmount`. The card is charged the gross (Stripe's
   `session.amount_total` has to match something, and that something is the
   gross), and the processor's cut is withheld from the wallet credit.
3. **Payouts send NET** — `service/StripeService.groovy:1494` transfers
   `netPayout`, not `amount`. The wallet is debited the gross the user asked
   for; the retained difference is what pays Stripe for the payout.

Rates live in `application.yml` under `platform:` — `processing-fee-percent`
(2.9), `processing-fee-fixed` (0.30), `payout-fee-percent` (0.25),
`payout-fee-fixed` (0.25). **These are what the user is charged**, so an
operator on a negotiated rate must set them or be overcharging real people.

## The arithmetic

| deposit | processing fee | credited | 2% per trade | payout fee | user finally receives | user round-trip cost |
|---|---|---|---|---|---|---|
| $10 | $0.59 | $9.41 | $0.19 | $0.27 | $9.14 | $0.86 (**8.60%**) |
| $20 | $0.88 | $19.12 | $0.38 | $0.29 | $18.83 | $1.17 (**5.85%**) |
| $50 | $1.75 | $48.25 | $0.97 | $0.37 | $47.88 | $2.12 (**4.24%**) |
| $100 | $3.20 | $96.80 | $1.94 | $0.49 | $96.31 | $3.69 (**3.69%**) |
| $250 | $7.55 | $242.45 | $4.85 | $0.85 | $241.60 | $8.40 (**3.36%**) |
| $500 | $14.80 | $485.20 | $9.70 | $1.46 | $483.74 | $16.26 (**3.25%**) |

"User round-trip cost" is deposit-then-withdraw with **zero** trades — the pure
cost of moving money in and out. The 2% column is what the platform earns each
time the credited balance changes hands.

## Break-even, recomputed

**There is no break-even trade count any more.** That table existed because the
platform was paying $3.70 per $100 round trip and had to earn it back at 2% a
trade. It no longer pays it.

| deposit | platform P&L at 0 trades | 1 trade | 2 trades | break-even |
|---|---|---|---|---|
| $10 | ~$0.00 | +$0.19 | +$0.38 | **0 trades** |
| $20 | ~$0.00 | +$0.38 | +$0.76 | **0 trades** |
| $50 | ~$0.00 | +$0.97 | +$1.93 | **0 trades** |
| $100 | ~$0.00 | +$1.94 | +$3.87 | **0 trades** |
| $250 | ~$0.00 | +$4.85 | +$9.70 | **0 trades** |
| $500 | ~$0.00 | +$9.70 | +$19.41 | **0 trades** |

Compare the version this replaces, where the same rows read −$0.67 to −$6.30
after one trade and needed **1.63 to 4.33 trades** just to stop losing money.
The fixed $0.30 no longer makes small deposits structurally worse *for the
platform* — it makes them worse **for the user**, which the $10 row shows at
8.60%, and which is now a product problem (set a sensible minimum) rather than
a margin problem.

### So what is the real break-even?

It moved from *per deposit* to *per month*. With variable margin at zero on both
money legs, the only thing 2% has to cover is **fixed cost** — servers, Stripe
disputes, support, fraud losses:

| monthly fixed cost | GMV needed at 2% | e.g. trades of $100 |
|---|---|---|
| $250 | $12,500 | 125 |
| $500 | $25,000 | 250 |
| $1,000 | $50,000 | 500 |
| $2,500 | $125,000 | 1,250 |
| $5,000 | $250,000 | 2,500 |

That is the question worth tracking now, and `platformMargin` on the admin
dashboard answers it directly: it is no longer dragged down by deposit volume,
so a rising number means trading activity and nothing else.

## Rounding: which way it goes, and why

**The user's fee is rounded DOWN. The platform's cost estimate is rounded
HALF_UP. The gap is deliberate and the platform eats it.**

`PlatformLedgerService.feeCharged` uses `RoundingMode.FLOOR`;
`estimateProcessingCost` / `estimatePayoutCost` use `HALF_UP`. On a $33.33
deposit the true fee is $1.266570 — FLOOR charges the user $1.26 where HALF_UP
would charge $1.27.

Half a cent is nothing. The **direction** is not nothing: a fee that rounds
toward the house on every single transaction is the shape of a defect that goes
unnoticed until a regulator or a forum thread finds it. Measured across every
whole-cent amount from $1.00 to $500.00, the mean residual is exactly
**−$0.005 per leg** — the platform gives away half a cent per deposit and half a
cent per payout, on average. At 10,000 round trips a month that is about **$100**.
It is a real cost, it is priced in, and it is the correct side to be wrong on.

`PassThroughFeePricingSpec` pins this as an invariant, not a preference: for
every amount tested, `feeCharged <= estimatedCost`. If that can ever invert, the
platform is earning undisclosed fractions of a cent.

## The user is told before they commit

A deposit screen that says "$100" and credits $96.80 does not have a rounding
problem, it has a chargeback problem. The net is surfaced in **three** places
before the card is charged:

1. **The wallet form** — `/api/wallet/summary` returns `feeSchedule`
   (`WalletController.groovy:228`) and the modal renders a live
   "Payment processing fee −$3.20 / Wallet credit $96.80" breakdown as the user
   types (`static/js/modals.js`). The rates come from server config, never a
   hardcoded client-side copy — a client carrying its own "2.9%" silently stops
   matching the day an operator negotiates a rate.
2. **The API response** — `createDepositSession` returns `amount`,
   `processingFee` and `netCredit`. `/withdraw` returns `amount`,
   `processingFee` and `netPayout`.
3. **Stripe's own Checkout page** — the line-item description reads
   "Deposit into @user — $96.80 credited after the $3.20 payment-processing
   fee". This is the last surface before the charge, and it catches the user who
   arrived from a stale tab or a client that ignored `netCredit`.

`feeSchedule.active` is **false** in dev mode, where no Stripe charge happens and
deposits credit gross. Advertising a deduction that never happens is the same
lie in the other direction.

## The treasury accounting

Under pass-through the user bears the processor's cost, so the obvious question
is what the treasury should book. Both naive answers are wrong:

- **Keep only the `PROCESSING_COST` debit** (what the code did while the
  platform absorbed the fee) and the cost is counted **twice** — once in the
  user's reduced credit, again against margin. An economically break-even $100
  deposit would read as a $3.20 loss and this document's table would never
  converge no matter how the business ran.
- **Book nothing at all** and the arithmetic nets right but becomes
  unobservable. An operator on a negotiated rate who never set
  `platform.processing-fee-percent` would be over- or under-charging every user
  with nothing in the books to show it.

So both legs are booked, as a pair:

| type | direction | booked at |
|---|---|---|
| `FEE` | credit | `TradeService.release` — the 2% on a VERIFIED trade |
| `PROTECTION_FEE` | credit | `TradeProtectionService.purchase` — the premium |
| `PROTECTION_REVERSAL` | credit | a reversed claim, for the amount actually recovered |
| **`PROCESSING_RECOVERY`** | **credit** | **the processor fee reimbursed by the user** |
| `PROCESSING_COST` | debit | the processor's cut on a deposit **or a payout** |
| `PROTECTION_PAYOUT` | debit | a protection claim paid to a buyer |

`PROCESSING_RECOVERY` is **not profit** and must never be read as such. It is
the offsetting leg of `PROCESSING_COST`. The pair nets to ~zero by design, and
**the residual is the signal**: it is the sub-cent the platform gives away, plus
any gap between the configured rate and what Stripe really charged. If
`sum(PROCESSING_RECOVERY) - sum(PROCESSING_COST)` drifts away from roughly
`-$0.005 × transactions`, one of the four rate properties is wrong.

Both rows are written inside **one** deferred transaction
(`PlatformLedgerService.postPassThroughProcessing`), so a debit can never land
without its recovery — a half-posted pair would understate margin by the entire
processor cut, which is the exact failure the pairing exists to prevent.

**Reversals.** When a payout is reversed (`handleTransferReversed`) the user is
re-credited the **gross** they were debited — the withdrawal did not happen, so
they should not be left holding a fee for it. That hands back the reimbursement,
so the recovery credit becomes fiction and is debited away
(`postPassThroughRecoveryReversal`), leaving the bare `PROCESSING_COST` standing
alone. That is the true outcome: Stripe does not refund the payout cost, so the
platform ate that one. Left unbooked it would overstate margin on every reversed
payout, in the flattering direction.

`treasury.balance == lifetime revenue - lifetime cost == margin` still holds,
and now means what it says on the money legs too.

## Refusals

At 2.9% + $0.30 the fee exceeds the deposit below **$0.31**; at 0.25% + $0.25
the payout fee exceeds the withdrawal below **$0.26**. Both paths refuse
(`DEPOSIT_BELOW_FEE`, `WITHDRAWAL_BELOW_FEE`) rather than credit zero-or-negative.
`@DecimalMin("1.00")` on the DTOs makes this unreachable from the API at today's
rates — which is exactly why the check lives where the *rate* lives, instead of
being assumed from an annotation three layers away. A misconfigured fixed leg
moves that threshold arbitrarily high.

If a bad fee somehow reaches `completeDeposit`, the credit is **clamped to the
gross** and logged loudly rather than thrown: the card has already been charged
by then, so refusing would strand the user's money at Stripe behind a PENDING
row. Errors resolve in the user's favour and surface to a human.

## Solvency is per-account, not per-trade (implemented 2026-09-19)

Every figure above treats the platform's cost as a *rate* - a percentage of
each trade. The payout rail does not work that way. **Stripe Connect bills
USD 2.00 per monthly active account**, charged once a month for each connected
account that moves money, regardless of how much it moved.

That is a fixed cost attached to a *seller*, not to a *trade*, and the 2% take
rate cannot absorb it at small volumes:

- A seller who withdraws in a given month costs USD 2.00 on its own.
- At a 2% take rate, covering that requires **USD 100.00 of GMV from that
  seller in that month** before the platform breaks even on them.
- The shipped minimum withdrawal was **USD 1.00**. A seller who withdrew
  USD 1.00 yielded USD 0.02 of revenue against USD 2.00 of cost - a **100x**
  loss on that account, and no take-rate change fixes it, because the problem
  is not the rate.

> **Correction.** An earlier revision of this section put the break-even at
> USD 101.95. No combination of the shipped constants produces that figure.
> `2.00 / 0.02` is exactly **100.00**, and the withdrawal-side twin - a
> balance that is pure sale proceeds implies `GMV = balance / (1 - 0.02)`, so
> the break-even on the *withdrawn* amount is `2.00 * 0.98 / 0.02` = **98.00**
> - is not it either. The code uses 100.00, the conservative of the two, and
> derives it rather than storing it.

### What is now in the code

Two knobs in `application.yml`, and nothing downstream hardcodes what they
imply:

| Config | Default | What it is |
| --- | --- | --- |
| `platform.payout-account-monthly-fee` | `2.00` | The per-monthly-active-account charge. Set `0.00` for a rail that has none. |
| `platform.max-fee-share` | `0.10` | Largest share of a deposit or payout that may be processor fee. The only judgement call. |

Everything else is solved from those and the four published rates
(`PlatformLedgerService`):

```
take rate r              = TradeService.FEE_RATE              = 0.02
per-account charge F     = payout-account-monthly-fee         = $2.00
max fee share s          = max-fee-share                      = 0.10

per-account waiver line  = F / r         = 2.00 / 0.02        = $100.00
minimum deposit          = 0.30 / (0.10 - 0.029)              = $4.23
minimum withdrawal, 1st of month
                         = (0.25 + 2.00) / (0.10 - 0.0025)    = $23.08
minimum withdrawal, after
                         =  0.25 / (0.10 - 0.0025)            = $2.57
```

`TradeService.FEE_RATE` stopped being `private` for this: the break-even is
that constant's divisor, and a second copy of the take rate would have left
the threshold silently wrong the day the rate moved.

The closed form solves on the *unrounded* fee, so it is conservative by a cent
or two - the charged fee is FLOORed in the user's favour, which puts the true
$4.23 boundary at $4.21. Conservative is the right direction for a minimum.

### What the charge does on the money path

`StripeService.requestWithdrawal` now has three outcomes, and only one of them
charges the user:

1. **Already billed this month** - nothing. Stripe bills the *account* once a
   calendar month, so the second payout of a month rides the charge the first
   one paid. Billing it again would be over-recovery dressed as a fee.
2. **Withdrawal >= the waiver line** - booked as a platform `PROCESSING_COST`
   with **no recovery**. Commission on that much volume already paid for it.
   This is the first time the charge has appeared in the ledger at all; before
   today the treasury residual read ~zero on a month the platform genuinely
   paid USD 2.00 per active seller.
3. **Withdrawal below the waiver line** - passed through, exactly like the
   percentage legs, and folded into `payoutFeeCharged` so that every
   downstream path (net payout, the fee-swallow refusal, `tx.feeAmount`, the
   cancel and reversal re-credits) carries it without a second field to keep
   in step.

### A raised minimum must not strand money

It does not. **A full-balance withdrawal is exempt from the minimum**, at any
amount the fees still leave something of. A seller sitting on USD 12 with a
USD 23.08 minimum withdraws the whole USD 12 and receives USD 9.72
(USD 0.28 payout fee + USD 2.00 per-account charge). A *partial* withdrawal
below the minimum is refused, and the refusal names the sweep.

Two alternatives were considered and rejected:

- **"The platform absorbs it on a sweep."** That is a faucet: deposit USD 10,
  sweep it, and the platform is out USD 2.00 having earned nothing - repeatable
  once per throwaway account. The sweep pays the charge like any other payout,
  which is precisely what stops the exemption being farmed.
- **A hard USD 100 minimum with no exemption.** That is the platform keeping a
  seller's money to solve the platform's own cost problem.

The one balance that still cannot leave is one smaller than the fees on it -
under about USD 2.26 on the first payout of a month. That is arithmetic, not
policy: no rail sends USD 2.00 when sending costs USD 2.25. It stays the
user's, spendable on the platform, and withdrawable the moment the balance
grows or the operator moves to a rail with no per-account charge. Set
`payout-account-monthly-fee: 0.00` and the same balance walks straight out.

### Other fees with a fixed leg that is not modelled

Audited 2026-09-19. Two remain, both recorded and neither implemented:

1. **Stripe's dispute fee (USD 15.00 flat, US).** `charge.dispute.created`
   flips the deposit to `DISPUTED` and notifies admins, but books **no**
   `PROCESSING_COST`. The caveat below has always said disputes are excluded
   from every figure; what is new is that the *ledger* excludes them too, so
   the treasury residual understates cost by USD 15.00 per dispute. It is a
   fixed charge with no percentage leg, the same shape as the Connect fee.
2. **`TradeService.FEE_RATE` has no floor.** A USD 0.50 trade yields USD 0.01
   of commission. Its sibling `TradeProtectionService` applies the *same* 2%
   with a `MIN_FEE` of USD 0.25 for exactly this reason, and the two have
   disagreed since protection shipped. Not a processor charge, so nothing is
   being under-recovered against Stripe - but it is the same blindness on the
   revenue side, and it bites hardest exactly where this product is aimed:
   sub-dollar items, where a 2% commission rounds to a cent or to nothing.

The four pass-through rates themselves are clean: `processing-fee-*` and
`payout-fee-*` each carry both a percentage and a fixed leg.

## Caveats

- **Stripe rates are list prices.** Negotiated rates, non-card methods, and
  international cards all differ, and under pass-through a wrong rate is now
  charged to *users*, not just mis-recorded.
- **`PROCESSING_COST` is still an estimate**, not Stripe's reported per-charge
  fee. The recovery is the exact amount withheld; the cost is modelled.
- **Chargebacks got worse, not better.** A disputed deposit costs the platform
  the gross plus Stripe's dispute fee, and the user's fee reimbursement goes
  back with it. Excluded from every figure above; it pushes one way.
- **The trade table assumes the full balance trades each time.** Partial-balance
  trades reduce revenue per hop, never increase it. The 2% also *decays* the
  balance: a $96.80 item resold nets its seller $94.86, so "N trades" is an
  upper bound on the amount at risk, not a compounding series.
- **Dev mode charges nothing** on either leg. Any margin figure from a
  keyless environment is fiction by construction.

# Unit economics — can the 2% fee pay for itself?

Computed 2026-08-19 against the code, not against a business plan.

## The two facts that set this

1. **`FEE_RATE = 0.02`** — `service/TradeService.groovy:46`. The platform charges 2% of the
   trade price, credited to the seller as `price - feeAmount`.
2. **Deposits credit the GROSS amount** — `service/StripeService.groovy`,
   `wallet.balance = wallet.balance + amount`. Stripe's processing fee is deducted from
   what the platform *receives*, and nowhere from what the user is *credited*. There is no
   processing-fee accounting anywhere in the repo.

So the platform absorbs Stripe's cut on the way in and earns it back only through trade
volume. Whether that works is arithmetic.

## The arithmetic

Stripe US standard: **2.9% + $0.30** on a card deposit, roughly **0.25% + $0.25** on a
Connect payout. Platform take: **2% per trade**.

| deposit | 1 trade | 2 trades | 3 trades | break-even |
|---|---|---|---|---|
| $10 | **−$0.67** | −$0.47 | −$0.27 | **4.33 trades** |
| $20 | **−$0.78** | −$0.38 | +$0.02 | **2.95 trades** |
| $50 | **−$1.13** | −$0.13 | +$0.87 | **2.12 trades** |
| $100 | **−$1.70** | +$0.30 | +$2.30 | **1.85 trades** |
| $250 | **−$3.42** | +$1.58 | +$6.58 | **1.69 trades** |
| $500 | **−$6.30** | +$3.70 | +$13.70 | **1.63 trades** |

**A dollar deposited, traded once, and withdrawn loses money at every deposit size.**
Every deposited dollar must change hands roughly **2 to 4 times** before the platform
earns anything, and the fixed $0.30 makes small deposits structurally worse — a $10 user
must trade more than four times just to stop costing money.

## Why this was invisible — and what now measures it

**Was:** the 2% was charged and **credited to nobody**. `TradeService.release` paid the
seller `price - feeAmount` and the fee was not posted anywhere — no platform wallet, no
treasury account, no `FEE` transaction type. Platform revenue existed only as a residual
in the Stripe balance, reconstructable by summing `Trade.feeAmount` after the fact. The
Trade Protection premium had the same shape: it debited the buyer with no counterparty.
And the processing cost above was recorded nowhere at all, so even a reconstructed
revenue figure could not be netted against what it cost to earn.

**Now:** `service/PlatformLedgerService.groovy` owns a reserved wallet row
(`__platform_treasury__`). Every revenue event credits it, every cost event debits it,
and each posting writes a `Transaction` row in the same shape as every user-facing ledger
line:

| type | direction | booked at |
|---|---|---|
| `FEE` | credit | `TradeService.release` — the 2% on a VERIFIED trade |
| `PROTECTION_FEE` | credit | `TradeProtectionService.purchase` — the premium |
| `PROTECTION_REVERSAL` | credit | a reversed claim, for the amount actually recovered |
| `PROCESSING_COST` | debit | `StripeService.completeDeposit` — the processor's cut |
| `PROTECTION_PAYOUT` | debit | a protection claim paid to a buyer |

So `treasury.balance == lifetime revenue - lifetime cost == margin`, and the
`transactions` table carries the itemised breakdown by type. The admin dashboard exposes
`platformMargin` alongside the 30-day component figures (`feeRevenue30d`,
`processingCost30d`, `netMargin30d`).

Two things to read honestly:

- **`PROCESSING_COST` is an ESTIMATE**, computed from `platform.processing-fee-percent`
  (default 2.9) and `platform.processing-fee-fixed` (default 0.30), not from Stripe's
  reported per-charge fee. An operator on a negotiated rate must set those properties or
  the margin is wrong in the optimistic direction.
- **The figure is expected to start negative.** That is the signal, not a fault — it is
  precisely what was previously unobservable. The question the number answers is not
  "are we profitable today" but "is the trend heading toward the break-even column in the
  table above".

The rate decision itself is still open. What has changed is that the code can now tell
you which side of it you are on.

## The three ways out

1. **Credit net of processing.** Deposit $100 -> credit $96.80. This is what most
   marketplaces do, and it moves break-even to roughly one trade. Costs a conversion-rate
   hit and needs clear disclosure at the deposit step.
2. **Raise the take.** At 5% the $100 case breaks even inside one trade. Costs
   competitiveness against CSFloat-style venues, which run 2%.
3. **Accept it and rely on churn.** Defensible ONLY if measured. Requires booking the fee
   to a real account first, then watching actual velocity per deposited dollar.

Nothing here is a bug. It is a pricing decision that has not been made, and the code
currently cannot tell you which side of it you are on.

## Caveats

- Stripe rates are list prices; negotiated rates and non-card methods differ.
- Assumes the full balance trades each time. Partial-balance trades push break-even higher,
  never lower.
- Chargebacks, refunds and disputes are excluded and all push the same direction.

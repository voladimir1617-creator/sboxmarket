"""Who actually pays the payment rail, and which rail survives a $0.48 trade.

THE CORRECTION THIS STARTS FROM. The 2026-09-19 pass concluded "the effective
take of 1.777% sits BELOW Stripe's ~3.15% of every deposited dollar -- the
platform loses money at every volume, including infinite volume." That
compares a platform REVENUE rate against a cost the platform does not pay.
The shipped code passes both money legs through at cost:

    src/main/resources/application.yml:369-372
        "Payment-processor rates. These are PASS-THROUGH: the user is credited
         net of the deposit fee and paid out net of the payout fee, and the
         platform's own revenue is the 2% selling fee alone"
    service/StripeService.groovy:3059-3061   credited = tx.amount - tx.feeAmount
    service/StripeService.groovy:1723        netPayout = amount - payoutFeeCharged
    UNIT-ECONOMICS.md                        "deposit ... platform margin $0.00
                                              withdrawal ... platform margin $0.00"

So the 2.9% + $0.30 is not a platform cost and cannot make the platform
insolvent at any volume. It is a USER cost, and what it decides is not
solvency but whether a market whose median trade is under a dollar has anybody
in it. Two different questions were collapsed into one; they separate here.

THE COST THE PASS-THROUGH DOES NOT COVER, AND NOBODY HAS PRICED.
Stripe Connect is quoted per payout AND per account
(stripe.com/connect/pricing, read 2026-09-19; stripe.com/robots.txt read
first -- "Allow: /docs", disallowing only /bitcoin/refund, /sources/refund,
/sources/sepa_mandate, /sources/test_source, /sources/test_klarna,
/unsupported-browser, /handoff, /handoff-healthcheck):

        "$2 per monthly active account"
        "0.25% + 25c per payout sent"
        "An account is active in any month payouts are sent to its bank
         account or debit card."

`application.yml` has four knobs -- processing percent, processing fixed,
payout percent, payout fixed -- and no fifth for this one. UNIT-ECONOMICS.md's
"platform P&L at 0 trades: ~$0.00" is short by $2.00 for every seller who
withdraws in a month, and that $2 is fixed per PERSON, which is the shape a
sub-dollar market is least able to carry.

STRIPE MICROPAYMENTS ARE NOT AN OPTION ON PAPER. stripe.com/pricing publishes
only "2.9% + 30c per successful transaction for domestic cards"; the help page
"Accepting microtransactions on Stripe" says only "Microtransaction support
varies from market to market. Reach out to Stripe Support with questions about
availability", and recommends batching charges instead. The often-quoted
5% + $0.05 is NOT PUBLISHED and is gated behind sales, so it is not priced here.

CRYPTO RAIL -- the PUBLISHED schedule, current version.
(nowpayments.io/robots.txt read first: it disallows /embeds, /?, /blog/page/,
/feed/, /tag/, /category/, /help/search, /amp, /gb and some blog admin paths;
/help/, /pricing, /custody and /blog/<article> are allowed. Nothing disallowed
was fetched. api.steampowered.com is Disallow: / and was never called; SteamDB
forbids scraping in its terms and was never fetched.)

  IN   "1% for payments without exchange" / "1.5% for multi-currency payments,
       Fixed rate payments and 'fee paid by user' payments"
       -- nowpayments.io/help/about-nowpayments/about/what-are-your-fees
       confirmed by the 2026 pricing-update blog: "1% for single-currency
       payments", "1.5% for multi-currency payments", "as low as 0.3% ...
       available only through a custom commercial offer".
  OUT  "there are no service fees for integration, support, further account
       optimization, and even mass payouts" -- nowpayments.io/custody
       "No service fees: NOWPayments does not charge any service fees for
       making a lot of payments" -- blog/nowpayments-guide-to-mass-payouts
       So the payout SERVICE fee is 0%, and each payout costs exactly one
       network fee.
  NET  "Every blockchain transaction requires a network fee (gas fee) ... The
       amount of the network fee is not fixed, and unlike the service fee, it
       does not represent a specific percentage of the transaction amount."

TWO TRAPS IN THEIR OWN DOCUMENTATION, BOTH CAUGHT:
  * blog/crypto-fees-explained still publishes "0.5% fee for single-currency
    payments". That is PRE-UPDATE pricing and is contradicted by the pricing-
    update blog and by /custody. Pricing a round trip on 0.5% would understate
    the crypto rail by 2x, in its favour.
  * Which rate applies to the SHIPPED integration is not the headline one.
    `NowPaymentsDepositProvider` defaults `is-fixed-rate` to true, and the help
    page puts fixed-rate payments in the 1.5% band. 1.5% is used below; 1.0%
    is shown beside it as the floating-rate alternative the operator could
    choose.

THE NETWORK FEE IS THE WHOLE ARGUMENT, and NOWPayments publishes only three
numbers for it. They are used as reference points, not as a schedule:
  $3.50   "Regular network fee USD -- default estimate: $3.50 per USDT TRC20
          payout" -- calculator.nowpayments.io (their own calculator; no
          robots.txt on that host, HTTP 404)
  $5.00   "Network fees ... from $0.05 on BSC to $5 on Ethereum"
  $0.05   same sentence, the cheap end
  $0.00   ChangeNOW PRO email payouts: "$0 network and service fees" -- but
          "This rule applies specifically to this payout flow within the NOW
          ecosystem", access is by request to partners@nowpayments.io, and the
          RECIPIENT must hold a ChangeNOW PRO account. It is not a general
          payout rail and is not treated as one here.
"""
import io
import sys
from decimal import Decimal, ROUND_FLOOR, ROUND_HALF_UP

# Idempotent: re-wrapping an already-UTF-8 stdout creates a second
# TextIOWrapper over the same buffer, and the first one closes that buffer
# when it is collected -- which turns an import into a dead stdout.
if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
D = chr(36)
C = Decimal("0.01")

# ---- shipped, transcribed from source ----------------------------------
FEE_RATE = Decimal("0.02")              # TradeService.groovy:46
PROC_PCT = Decimal("2.9")               # application.yml:383
PROC_FIX = Decimal("0.30")              # application.yml:384
PAY_PCT = Decimal("0.25")               # application.yml:388
PAY_FIX = Decimal("0.25")               # application.yml:389
MIN_DEPOSIT = Decimal("1.00")           # dto/request/DepositRequest.groovy:9
MIN_WITHDRAW = Decimal("1.00")          # dto/request/WithdrawRequest.groovy:11
CONNECT_MONTHLY = Decimal("2.00")       # stripe.com/connect/pricing, NOT in config

# ---- NOWPayments, published --------------------------------------------
NP_IN_FIXEDRATE = Decimal("1.5")        # what the shipped default actually buys
NP_IN_PLAIN = Decimal("1.0")            # single currency, floating rate
NP_OUT_PCT = Decimal("0.0")             # "no service fees for ... mass payouts"
NP_MIN_PAYMENT_LO = Decimal("2.00")
NP_MIN_PAYMENT_HI = Decimal("5.00")

# published reference points for the per-transfer network fee
NET_REFS = [("ChangeNOW PRO (off-chain, gated)", Decimal("0.00")),
            ("BSC, published cheap end", Decimal("0.05")),
            ("TRC20 hop in their own optimisation example", Decimal("0.60")),
            ("USDT-TRC20, their calculator's default", Decimal("3.50")),
            ("Ethereum, published expensive end", Decimal("5.00"))]


def _d(x):
    return x if isinstance(x, Decimal) else Decimal(str(x))


def stripe_in(amount):
    """What the depositor is charged (PlatformLedgerService.feeCharged: FLOOR)."""
    return ((_d(amount) * PROC_PCT / 100) + PROC_FIX).quantize(C, ROUND_FLOOR)


def stripe_out(gross):
    return ((_d(gross) * PAY_PCT / 100) + PAY_FIX).quantize(C, ROUND_FLOOR)


def np_in(amount, pct, network):
    """NOWPayments has NO published fixed per-transaction leg. The chain does.
    With Custody the merchant pays ONE inbound network fee (two without), and
    the customer separately pays their own send fee -- which is a real cost of
    the round trip to the person even though it never reaches our invoice."""
    return ((_d(amount) * _d(pct) / 100)).quantize(C, ROUND_HALF_UP) + _d(network)


def np_out(gross, network):
    """0% service fee, exactly one network fee per payout."""
    return ((_d(gross) * NP_OUT_PCT / 100)).quantize(C, ROUND_HALF_UP) + _d(network)


def band(pct):
    return ("     ok" if pct < 5 else "   HIGH" if pct < 15 else "  BRUTAL")


def section(t):
    print()
    print("=" * 78)
    print(t)
    print("=" * 78)


# ======================================================================== 1
section("1. WHO PAYS THE RAIL — the shipped answer, not the assumed one")
print("""  Deposit leg  StripeService:3059  credited = tx.amount - tx.feeAmount
  Payout leg   StripeService:1723  netPayout = amount - payoutFeeCharged
  => platform variable margin on BOTH money legs is exactly $0.00, and the
     2.9%+$0.30 cannot make it insolvent at any volume. The prior pass's
     "1.777% < 3.15%, the platform loses money at every volume" compares a
     revenue rate against somebody else's cost.

  What the pass-through does NOT cover, because it is priced per ACCOUNT and
  application.yml has no knob for it:""")
print("     Stripe Connect  %s2.00 per monthly ACTIVE ACCOUNT  (published)" % D)
print("     -> a seller who withdraws once in a month costs the platform %s2.00" % D)
print("        REGARDLESS of how little they withdrew. It is the only cost in")
print("        the model that does not shrink with the trade size, and it is the")
print("        one a sub-dollar market cannot carry. Priced in takerate.py.")

# ======================================================================== 2
section("2. THE USER'S ROUND TRIP ON STRIPE, at the SHIPPED rates")
print("  Deposit, then withdraw the whole credited balance. No trade, so this")
print("  is the pure cost of moving money in and out.")
print()
print("  %8s %9s %10s %9s %10s %11s" %
      ("deposit", "fee in", "credited", "fee out", "net out", "round-trip"))
for amt in ("1.00", "2.00", "5.00", "10.00", "20.00", "50.00", "100.00"):
    a = Decimal(amt)
    fi = stripe_in(a)
    cr = a - fi
    fo = stripe_out(cr)
    net = cr - fo
    pct = float((a - net) / a * 100)
    print("  %8s %9s %10s %9s %10s %10s %s"
          % (D + amt, D + str(fi), D + str(cr), D + str(fo), D + str(net),
             "%.2f%%" % pct, band(pct)))
print()
print("  MIN_DEPOSIT is %s%s and MIN_WITHDRAW is %s%s (DepositRequest.groovy:9,"
      % (D, MIN_DEPOSIT, D, MIN_WITHDRAW))
print("  WithdrawRequest.groovy:11). At the shipped minimum the round trip takes")
print("  57% of the money before a single trade happens. There is no")
print("  configuration in the repo that raises that floor.")

# ======================================================================== 3
section("3. THE SAME ROUND TRIP ON THE CRYPTO RAIL, at the PUBLISHED schedule")
print("  1.5% in (the shipped is-fixed-rate=true band), 0% out, plus ONE network")
print("  fee on each leg. The network fee is the only term with no schedule, so")
print("  the published reference points are used as columns rather than a guess.")
print()
hdr = "  %-10s" % "deposit"
for lab, n in NET_REFS:
    hdr += "%12s" % (D + str(n))
print(hdr)
print("  %-10s" % "" + "".join("%12s" % lab.split(",")[0][:11] for lab, _ in NET_REFS))
for amt in ("2.00", "5.00", "10.00", "20.00", "50.00", "100.00"):
    a = Decimal(amt)
    line = "  %-10s" % (D + amt)
    for _, nf in NET_REFS:
        fi = np_in(a, NP_IN_FIXEDRATE, nf)
        cr = a - fi
        if cr <= 0:
            line += "%12s" % "n/a"
            continue
        fo = np_out(cr, nf)
        line += "%11.1f%%" % float((a - (cr - fo)) / a * 100)
    print(line)
print()
print("  Stripe, same deposits, for comparison:")
line = "  %-10s" % "round trip"
for amt in ("2.00", "5.00", "10.00", "20.00", "50.00", "100.00"):
    a = Decimal(amt)
    fi = stripe_in(a)
    cr = a - fi
    line += "  %s %.1f%%" % (D + amt, float((a - (cr - stripe_out(cr))) / a * 100))
print(line)

# ======================================================================== 4
section("4. WHERE THE TWO RAILS CROSS — solved, not asserted")
print("  Crypto beats Stripe on a deposit D when the per-transfer network fee N")
print("  satisfies   1.5%%D + N + 0%%(D-fee) + N  <  2.9%%D + %s0.30 + 0.25%%C + %s0.25"
      % (D, D))
print()
print("  %10s %20s %20s   %s" % ("deposit", "break-even N/leg", "at 1.0% (floating)",
                                 "verdict against the published numbers"))
for amt in ("1.00", "2.00", "5.00", "10.00", "20.00", "50.00", "100.00", "500.00"):
    a = Decimal(amt)
    fi = stripe_in(a)
    cr = a - fi
    scost = fi + stripe_out(cr)
    def be(pct):
        ci = np_in(a, pct, Decimal("0"))
        cc = a - ci
        return (scost - (ci + np_out(cc, Decimal("0")))) / 2
    n15, n10 = be(NP_IN_FIXEDRATE), be(NP_IN_PLAIN)
    if n15 >= Decimal("3.50"):
        v = "crypto wins even on USDT-TRC20"
    elif n15 >= Decimal("0.60"):
        v = "crypto wins on an optimised TRC20 hop, not on their default"
    elif n15 >= Decimal("0.05"):
        v = "crypto wins ONLY on a cheap chain (BSC-class, %s0.05)" % D
    elif n15 > 0:
        v = "crypto wins only at effectively zero chain cost"
    else:
        v = "STRIPE WINS OUTRIGHT"
    print("  %10s %20s %20s   %s"
          % (D + amt, D + ("%.4f" % n15), D + ("%.4f" % n10), v))
print()
print("  Read against NOWPayments' OWN published default of %s3.50 per USDT-TRC20"
      % D)
print("  payout, the crypto rail is DEARER than Stripe on every deposit below")
print("  about %s500. The crypto rail only wins on a cheap chain, and their own" % D)
print("  cheap-chain figure (%s0.05, BSC) wins at every deposit size in the table."
      % D)
print("  So the question is not 'crypto or card' -- it is WHICH CHAIN, and that")
print("  is a term nobody has picked. `rustyroyale.payout.nowpayments.currency`")
print("  has NO DEFAULT for exactly this reason, and the comment in")
print("  NowPaymentsPayoutGateway says so: 'a guess here sends real money over")
print("  the wrong chain'.")

# ======================================================================== 5
section("5. THE FLOOR — what each rail refuses to move at all")
print("  Stripe        MIN_DEPOSIT %s1.00 (ours, not Stripe's). Round trip 57%%."
      % D)
print("  NOWPayments   \"about %s2 for one half of the coins that we support and"
      % D)
print("                about %s3-5 for another\" -- and the minimum itself moves"
      % D)
print("                with the network fee, so the floor rises exactly when the")
print("                rail gets expensive. A crypto rail RAISES the shipped")
print("                minimum deposit from %s1.00 to %s2.00-%s5.00." % (D, D, D))
print("  NOWPayments payout minimum: NOT PUBLISHED. The only adjacent published")
print("                figure is \"The minimum conversion amount is %s1\"." % D)
print()
print("  What the crypto rail actually buys, stated exactly: it removes the")
print("  %s0.30 + %s0.25 FIXED legs and the %s2.00/month Connect account fee, and"
      % (D, D, D))
print("  replaces them with one network fee per transfer whose size is chosen by")
print("  the chain. On a cheap chain that is a 6-to-30x reduction in the fixed")
print("  cost of moving a small balance. On the chain their own calculator")
print("  defaults to, it is 7x WORSE. The rail is not the decision; the chain is.")
print()
print("  And the cost neither table shows: the seller must hold a crypto wallet")
print("  and an address to be paid to. That is a product barrier, not a fee, and")
print("  it is being asked of somebody cashing out a few dollars of idle-game")
print("  loot.")

"""What take rate solvency requires — computed on real transactions.

WHY NOT FROM THE MEDIAN. Every prior statement of the effective take was
derived from ONE number, the volume-weighted median trade, and then rounded:
"$0.09 median, so 2% is $0.00, so 83.6% of trades earn nothing." That is the
right instinct and the wrong instrument. HALF_UP acts on the whole
distribution, not on its middle, and the revenue that survives it comes from
the tail. This computes the effective take by applying
`TradeService.groovy:539` to EVERY MEASURED TRANSACTION in the capture --
price by price, count by count.

WHAT VELOCITY DOES AND DOES NOT DO. The prior pass proposed "velocity > 1.77x"
as an alternative to a higher take. Under the shipped PASS-THROUGH pricing
that lever does not exist, because the rail cost it was meant to out-run is
not a platform cost at all (see rail.py section 1). Velocity re-enters in one
place only, and it is a per-PERSON place: Stripe Connect bills the platform
$2.00 per monthly ACTIVE ACCOUNT -- every seller who withdraws, whatever they
withdrew. That fee is not in `application.yml`, is not passed through, and
does not shrink with the trade size. The bar it sets is computed below.

BIAS. Transactions come from the prior pass's systematic sample over PRICE
RANK, so the mix of prices is representative and the mix of VOLUMES is not
exactly. The effective take is a volume-weighted quantity, so it inherits
whatever volume skew the price-rank sample carries. Direction is not known a
priori, so it is not corrected -- it is reported alongside the same figure
computed on the median alone, and the two bracket it.
"""
import datetime
import io
import json
import os
import sys
from decimal import Decimal, ROUND_HALF_UP

# Idempotent: re-wrapping an already-UTF-8 stdout creates a second
# TextIOWrapper over the same buffer, and the first one closes that buffer
# when it is collected -- which turns an import into a dead stdout.
if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from curve import CANDS, load, daily, vwmedian, TODAY, D   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CENT = Decimal("0.01")
CONNECT_MONTHLY = Decimal("2.00")       # stripe.com/connect/pricing
WINDOW_DAYS = 14


def take(price, rate):
    """TradeService.groovy:539 verbatim: (price * rate).setScale(2, HALF_UP)."""
    return (Decimal(str(price)) * Decimal(str(rate))).quantize(CENT, ROUND_HALF_UP)


def txns(appid, total, days=WINDOW_DAYS):
    """[(price, qty)] over the last `days` COMPLETE days, plus the scale."""
    rows, never, unread = load(appid)
    if not rows:
        return None
    scale = total / float(len(rows) + never)
    dd = daily(rows)
    comp = sorted(d for d in dd if d < TODAY)
    if not comp:
        return None
    lo = comp[-1] - datetime.timedelta(days=days - 1)
    pairs = []
    for d in comp:
        if d >= lo:
            pairs += dd[d][2]
    nd = len([d for d in comp if d >= lo])
    return {"pairs": pairs, "scale": scale, "days": nd,
            "n_items": len(rows), "never": never, "unread": unread}


RATES = [0.02, 0.03, 0.05, 0.075, 0.10, 0.15]

print("MEASURED %s UTC — effective take applied to every captured transaction."
      % datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%d %H:%M"))
print()
summary = {}
for ap, lab, total, debias in CANDS:
    t = txns(ap, total)
    if not t:
        print("=== %s: NOTHING CAPTURED ===\n" % lab)
        continue
    pairs = t["pairs"]
    gmv = sum(p * q for p, q in pairs)
    n = sum(q for _, q in pairs)
    if not n:
        continue
    vwm = vwmedian(pairs)
    print("=" * 78)
    print("%s (%d)  —  last %d complete days, %s transactions in the sample"
          % (lab, ap, t["days"], format(int(n), ",")))
    print("=" * 78)
    print("  sample %d items read + %d never-sold of %d in book (scale x%.2f); "
          "unread %s" % (t["n_items"], t["never"], total, t["scale"],
                         t["unread"] or "none"))
    print("  sampled GMV %s over %d days; vol-weighted MEDIAN trade %s; mean %s"
          % (D + format(round(gmv, 2), ","), t["days"], D + "%.4f" % (vwm or 0),
             D + "%.4f" % (gmv / n)))
    print()
    print("  %6s %14s %14s %13s %13s"
          % ("rate", "eff. take", "from median", "% trades @ $0", "% GMV @ $0"))
    row = {}
    for r in RATES:
        rev = sum(float(take(p, r)) * q for p, q in pairs)
        zq = sum(q for p, q in pairs if take(p, r) == 0)
        zg = sum(p * q for p, q in pairs if take(p, r) == 0)
        eff = rev / gmv if gmv else 0
        med_eff = float(take(vwm, r)) / vwm if vwm else 0
        row[r] = {"eff": eff, "zero_trades": zq / n, "zero_gmv": zg / gmv}
        print("  %5.1f%% %13.3f%% %13.3f%% %12.1f%% %12.1f%%"
              % (100 * r, 100 * eff, 100 * med_eff, 100 * zq / n, 100 * zg / gmv))
    print()
    print("  -> the median-derived figure is the %s of the two here."
          % ("HIGHER" if row[0.02]["eff"] < float(take(vwm, 0.02)) / (vwm or 1)
             else "LOWER"))
    eff2 = row[0.02]["eff"]

    # ---------------- what the platform must capture -------------------
    gmv_yr = gmv / t["days"] * 365 * t["scale"]
    gmv_yr_db = gmv_yr / debias if debias else gmv_yr
    print()
    print("  WHOLE-MARKET GMV at this window's rate: %s%s/yr%s"
          % (D, format(int(gmv_yr), ","),
             ("  (de-biased %s%s)" % (D, format(int(gmv_yr_db), ",")))
             if debias else ""))
    print("  %10s %14s %14s %14s" % ("monthly", "GMV needed", "share of market",
                                     "share (de-biased)"))
    for fixed in (250, 500, 1000, 2500):
        for r in (0.02, 0.05, 0.15):
            need = fixed / row[r]["eff"] if row[r]["eff"] else float("inf")
            sh = 100 * need * 12 / gmv_yr if gmv_yr else float("nan")
            shd = 100 * need * 12 / gmv_yr_db if gmv_yr_db else float("nan")
            print("  %s%-9d %2.0f%% take  %12s   %11.2f%%    %11.2f%%"
                  % (D, fixed, 100 * r, D + format(int(need), ","), sh, shd))
    print()

    # ---------------- the per-ACTIVE-ACCOUNT bar -----------------------
    print("  THE BAR NOBODY HAS PRICED — Stripe Connect %s2.00 per monthly" % D)
    print("  ACTIVE ACCOUNT. A seller who withdraws costs that whatever they")
    print("  withdrew, so the platform only breaks even on a withdrawing seller")
    print("  who has sold enough for the take to cover it:")
    print("  %6s %20s %24s" % ("rate", "GMV/seller/month", "items at this median"))
    for r in (0.02, 0.05, 0.10, 0.15):
        need = float(CONNECT_MONTHLY) / row[r]["eff"] if row[r]["eff"] else float("inf")
        print("  %5.1f%% %19s %24s"
              % (100 * r, D + format(round(need, 2), ","),
                 format(int(need / vwm), ",") if vwm else "?"))
    print("  A seller below that line is a NET LOSS to the platform on the month")
    print("  they cash out, and no take rate fixes it -- only a withdrawal rail")
    print("  with no per-account fee, or a minimum withdrawal that forces the")
    print("  seller to accumulate past the line before cashing out.")
    print("  Break-even MINIMUM WITHDRAWAL, if the platform will not lose money")
    print("  on any single cash-out at a %.3f%% effective take: the seller must"
          % (100 * eff2))
    print("  have generated %s%.2f of GMV, i.e. accumulated a balance of about"
          % (D, float(CONNECT_MONTHLY) / eff2 if eff2 else float("inf")))
    print("  %s%.2f before withdrawing (shipped MIN_WITHDRAW is %s1.00)."
          % (D, (float(CONNECT_MONTHLY) / eff2) * (1 - eff2) if eff2 else float("inf"), D))
    print()
    summary[str(ap)] = {"label": lab, "eff": row, "gmv_yr": gmv_yr,
                        "gmv_yr_debiased": gmv_yr_db, "vwmed": vwm,
                        "days": t["days"], "n_tx_sampled": int(n)}

json.dump(summary, open(os.path.join(HERE, "books", "takerate.json"), "w"), indent=1)
print("takerate.json written")

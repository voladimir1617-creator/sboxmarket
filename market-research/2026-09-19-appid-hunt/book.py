"""Book arithmetic through the SHIPPED fee code (fees.py), identical to the
s&box pass so the columns are comparable.

'Revenue if the whole book trades once' = sum over items of
platform_take(lowest_ask) * standing_listings, with platform_take being
TradeService.groovy:539 (2%, HALF_UP -> anything under $0.25 earns $0.00).
"""
import json, statistics, sys, io
from fees import platform_take, stripe_deposit_cost, payout_fee_charged

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")


def load(path):
    rows, aborts = {}, 0
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            aborts += 1
            continue
        if r.get("h") and r.get("p") is not None:
            rows[r["h"]] = r
    return list(rows.values()), aborts


def report(path, label):
    v, aborts = load(path)
    n = len(v)
    listings = sum(r["n"] or 0 for r in v)
    book = sum((r["p"] / 100.0) * (r["n"] or 0) for r in v)
    once = sum(platform_take(r["p"] / 100.0) * (r["n"] or 0) for r in v)
    px = sorted(r["p"] / 100.0 for r in v)
    med = statistics.median(px)
    zero_listings = sum((r["n"] or 0) for r in v if platform_take(r["p"] / 100.0) == 0)
    print(f"=== {label} (MEASURED, full census, n={n} items, aborts={aborts}) ===")
    print(f"  distinct items              {n:>14,}")
    print(f"  standing listings           {listings:>14,}")
    print(f"  book value (lowest ask)     ${book:>13,.2f}")
    print(f"  WHOLE BOOK TRADES ONCE @2%  ${once:>13,.2f}")
    print(f"  ... at Steam's own 15%      ${sum((r['p']/100.0)*0.15*(r['n'] or 0) for r in v):>13,.2f}")
    print(f"  avg take per standing list  ${once/listings if listings else 0:>13,.4f}")
    print(f"  MEDIAN item price           ${med:>13,.2f}")
    print(f"  price p10 / p90             ${px[int(.1*n)]:,.2f} / ${px[int(.9*n)]:,.2f}")
    print(f"  listings earning $0.00 @2%  {100.0*zero_listings/listings if listings else 0:>13,.1f}%")
    # rail test on the median item
    m = med
    buyer = m + stripe_deposit_cost(m)
    take = platform_take(m)
    gross = m - take
    payout = payout_fee_charged(gross)
    print(f"  RAIL TEST on median ${m:,.2f} item:")
    print(f"     buyer pays        ${buyer:,.2f}")
    print(f"     Stripe takes      ${stripe_deposit_cost(m)+payout:,.2f}  (deposit ${stripe_deposit_cost(m):,.2f} + payout ${payout:,.2f})")
    print(f"     platform takes    ${take:,.2f}")
    print(f"     seller banks      ${gross-payout:,.2f}")
    r = (stripe_deposit_cost(m) + payout) / take if take else float("inf")
    print(f"     rail / platform   {r:,.1f}x")
    return {"label": label, "n": n, "listings": listings, "book": book,
            "once2": once, "median": med, "aborts": aborts}


if __name__ == "__main__":
    report(sys.argv[1], sys.argv[2])

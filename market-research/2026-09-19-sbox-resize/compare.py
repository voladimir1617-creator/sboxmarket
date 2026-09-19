"""Re-run of the 2026-09-01 s&box sizing on a 2026-09-19 capture.
Identical arithmetic: combine.combined() full-census path + fees.py (shipped
TradeService 2% HALF_UP / PlatformLedgerService Stripe rates)."""
import json, statistics
from fees import platform_take, seller_net, stripe_deposit_cost, stripe_payout_cost, payout_fee_charged

def load(path):
    seen, rows = set(), []
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r["p"] is None or r["n"] is None: continue
        if r["h"] in seen: continue
        seen.add(r["h"]); rows.append(r)
    return rows

def stats(rows, label):
    items = len(rows)
    listings = sum(r["n"] for r in rows)
    book = sum(r["n"]*r["p"] for r in rows)/100.0
    rev  = sum(r["n"]*platform_take(r["p"]/100.0) for r in rows)
    zero_items = [r for r in rows if platform_take(r["p"]/100.0) == 0.0]
    dead_items = [r for r in rows if seller_net(r["p"]/100.0) <= 0.0]
    zero_n = sum(r["n"] for r in zero_items)
    dead_n = sum(r["n"] for r in dead_items)
    item_prices = sorted(r["p"]/100.0 for r in rows)
    lp = []
    for r in rows: lp.extend([r["p"]/100.0]*min(r["n"],2000))
    lp.sort()
    med_item = statistics.median(item_prices)
    q = lambda xs,f: xs[min(len(xs)-1,int(len(xs)*f))]
    # fat tail
    srt = sorted(rows, key=lambda r: -(r["n"]*r["p"]))
    top6_val = sum(r["n"]*r["p"] for r in srt[:6])/100.0
    top6_n   = sum(r["n"] for r in srt[:6])
    # take <= $0.25 share of listings
    le25 = sum(r["n"] for r in rows if platform_take(r["p"]/100.0) <= 0.25)
    print(f"\n=== {label} ===")
    print(f"  distinct items                  {items:>12,}")
    print(f"  standing listings               {listings:>12,}")
    print(f"  book value (lowest ask)        ${book:>12,.2f}")
    print(f"  REV IF ENTIRE BOOK TRADES ONCE ${rev:>12,.2f}")
    print(f"  avg take per transaction       ${rev/listings if listings else 0:>12,.4f}")
    print(f"  median ITEM price              ${med_item:>12,.2f}")
    print(f"  median LISTING price           ${q(lp,.5):>12,.2f}")
    print(f"  items earning $0.00      {len(zero_items):>5,} items / {zero_n:>7,} listings ({100*zero_n/listings:.1f}%)")
    print(f"  seller nets <= $0        {len(dead_items):>5,} items / {dead_n:>7,} listings ({100*dead_n/listings:.1f}%)")
    print(f"  top-6 items by value: ${top6_val:,.0f} ({100*top6_val/book:.0f}% of book) on {top6_n:,} listings")
    print(f"  listings with take <= $0.25: {100*le25/listings:.1f}%")
    # median-item friction anchor
    p = med_item
    take = platform_take(p); dep = stripe_deposit_cost(p); gross = p-take
    pay = payout_fee_charged(gross)
    print(f"  median-item anchor: price ${p:.2f} -> buyer pays ${p+dep:.2f}, seller banks ${gross-pay:.2f};"
          f" platform ${take:.2f}, rail ${dep+stripe_payout_cost(gross):.2f}")
    return dict(items=items, listings=listings, book=book, rev=rev,
                avg=rev/listings if listings else 0, med_item=med_item)

old = stats(load("C:/Users/WW/Desktop/sboxmarket/market-research/2026-09-01-steam-market-sizing/books/rows_590830_pd.jsonl"), "s&box  2026-09-01 (archived capture)")
new = stats(load("rows_590830_pd.jsonl"), "s&box  2026-09-19 (fresh capture)")
print("\n=== GROWTH MULTIPLE (new / old) ===")
for k in ("items","listings","book","rev","avg","med_item"):
    print(f"  {k:<10} {old[k]:>14,.4f} -> {new[k]:>14,.4f}   x{new[k]/old[k] if old[k] else float('nan'):.3f}   ({100*(new[k]/old[k]-1):+.1f}%)")
json.dump({"old":old,"new":new}, open("compare_out.json","w"), indent=1)

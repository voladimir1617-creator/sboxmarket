"""The bar, on ACTUAL transactions rather than the standing book.

'Revenue if every listing traded once' is the wrong denominator: the standing
book is clogged with penny items that never trade. What matters is revenue per
trade that actually happens, and how many of those the market produces."""
def row(name, rev_yr_100pct, txn_day, gmv_yr, standing_take):
    txn_yr = txn_day * 365.0
    per_txn = rev_yr_100pct / txn_yr if txn_yr else 0
    print(f"\n  {name}")
    print(f"    market: {txn_day:>12,.0f} trades/day   GMV ${gmv_yr:>15,.0f}/yr")
    print(f"    platform take per ACTUAL trade ${per_txn:.4f}"
          f"   (per standing listing ${standing_take:.4f})")
    print(f"    revenue at 100% share ${rev_yr_100pct:,.0f}/yr")
    for lbl, cost in (("$500/mo", 6000), ("$2,500/mo", 30000), ("$10,000/mo", 120000)):
        need_day = (cost / per_txn) / 365.0 if per_txn else float("inf")
        share = 100.0 * need_day / txn_day if txn_day else float("inf")
        v = "IMPOSSIBLE — exceeds the whole market" if share > 100 else ""
        print(f"      {lbl:<11} needs {need_day:>11,.0f} trades/day = "
              f"{share:>9.2f}% of the market  {v}")

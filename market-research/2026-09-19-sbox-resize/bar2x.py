"""Turnover on ACTUAL trades + the cost-base table, 2026-09-19 vs 2026-09-01.
Take is per ACTUAL trade (gmv2), NOT per standing listing -- mixing those two
denominators is the error FINDINGS.md:55-58 warns about."""
import json, random
import gmv2
from fees import platform_take

OLD = r"C:/Users/WW/Desktop/sboxmarket/market-research/2026-09-01-steam-market-sizing/books/vol_590830.jsonl"
random.seed(20260919)

def measure(path, total, label, boot=5000):
    ok, miss, ab = gmv2.load(path)
    n = len(ok) + len(miss)
    scale = total / float(n)
    pool = ok + [dict(r, px=0.0, vol=0) for r in miss]
    txn_day = sum(r["vol"] for r in ok) * scale
    gmv_day = sum(r["vol"] * r["px"] for r in ok) * scale
    rev_day = sum(r["vol"] * platform_take(r["px"]) for r in ok) * scale
    bt, bg = [], []
    for _ in range(boot):
        s = [pool[random.randrange(len(pool))] for _ in range(len(pool))]
        bt.append(sum(r["vol"] for r in s) * scale)
        bg.append(sum(r["vol"] * r["px"] for r in s) * scale)
    bt.sort(); bg.sort()
    lo, hi = bt[int(.05*boot)], bt[int(.95*boot)]
    glo, ghi = bg[int(.05*boot)], bg[int(.95*boot)]
    print(f"\n=== {label} ===   n={n} sampled ({len(ok)} sold in 24h, {len(miss)} silent, aborts={ab or '{}'})")
    print(f"  trades/day        {txn_day:>10,.0f}   90% CI {lo:,.0f} - {hi:,.0f}")
    print(f"  Steam GMV/yr      ${gmv_day*365:>10,.0f}   90% CI ${glo*365:,.0f} - ${ghi*365:,.0f}")
    print(f"  take per ACTUAL trade  ${rev_day/txn_day if txn_day else 0:.4f}")
    print(f"  platform rev at 2%, 100% SHARE of the whole market:  ${rev_day*365:,.0f}/yr  = ${rev_day*30.4375:,.2f}/mo")
    print(f"  same at Steam's own 15% rate (upper bound on ANY take): ${gmv_day*0.15*365:,.0f}/yr = ${gmv_day*0.15*30.4375:,.2f}/mo")
    return dict(txn_day=txn_day, ci=(lo,hi), gmv_yr=gmv_day*365, rev_yr=rev_day*365,
                rev_mo=rev_day*30.4375, per_txn=rev_day/txn_day if txn_day else 0,
                rev15_mo=gmv_day*0.15*30.4375)

o = measure(OLD, 203, "s&box  2026-09-01 (archived)")
n = measure("vol_590830.jsonl", 214, "s&box  2026-09-19 (fresh)")

print("\n=== GROWTH (new/old) ===")
print(f"  trades/day   {o['txn_day']:,.0f} -> {n['txn_day']:,.0f}   x{n['txn_day']/o['txn_day']:.3f}"
      f"   -- CIs {o['ci'][0]:,.0f}-{o['ci'][1]:,.0f} vs {n['ci'][0]:,.0f}-{n['ci'][1]:,.0f}: "
      f"{'OVERLAP -> not distinguishable' if n['ci'][0] < o['ci'][1] and o['ci'][0] < n['ci'][1] else 'DISJOINT'}")
print(f"  rev/mo 100%  ${o['rev_mo']:,.2f} -> ${n['rev_mo']:,.2f}   x{n['rev_mo']/o['rev_mo']:.3f}")

print("\n=== THE COST BASE (FINDINGS.md method) — 2026-09-19 ===")
for lbl, cost_mo in (("$20/mo hosting", 20), ("$500/mo", 500), ("$2,500/mo", 2500), ("$10,000/mo", 10000)):
    need = cost_mo / n["per_txn"] / 30.4375 if n["per_txn"] else float("inf")
    share = 100.0 * need / n["txn_day"]
    verdict = "IMPOSSIBLE — exceeds the whole market" if share > 100 else f"{share:.1f}% of the market"
    print(f"  {lbl:<16} needs {need:>10,.0f} trades/day = {verdict}")

print("\n=== THE BAR: 0.0100 $/mo per $ of capital ===")
print(f"  Ceiling revenue (100% capture of EVERY real s&box trade, shipped 2%): ${n['rev_mo']:,.2f}/mo")
print(f"  Ceiling revenue at Steam's own 15% (no take rate is higher):          ${n['rev15_mo']:,.2f}/mo")
for cap in (1000, 5000, 10000):
    print(f"  capital ${cap:>6,}: bar needs ${0.01*cap:>8,.2f}/mo   "
          f"gross ceiling gives {n['rev_mo']/cap:.5f} $/mo per $  "
          f"| NET of a $500/mo cost base: {(n['rev_mo']-500)/cap:+.5f}  -> FAILS")

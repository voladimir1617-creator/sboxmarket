"""Estimate whole-book totals from a systematic sample over PRICE RANK.

Two estimators are reported side by side so the reader sees the spread:
  STEP      each sampled page of 10 stands for `stride` pages  -- badly
            overweights the expensive head of a power law
  TRAPEZOID linear interpolation of per-item value between adjacent sampled
            ranks -- correct for a monotone-in-price sequence
The exact-census path uses neither.
"""
import json, random, sys
from fees import platform_take, seller_net

def load_pages(path):
    """-> [(start, [rows...])] ordered by start; rows keep sort order."""
    pages = {}
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r["p"] is None or r["n"] is None: continue
        pages.setdefault(r["_start"], []).append(r)
    return sorted(pages.items())

def page_stats(rows):
    k = len(rows)
    return {
        "k": k,
        "n_per_item":   sum(r["n"] for r in rows) / k,
        "val_per_item": sum(r["n"] * r["p"] for r in rows) / 100.0 / k,
        "rev_per_item": sum(r["n"] * platform_take(r["p"] / 100.0) for r in rows) / k,
        "zero_n_per_item": sum(r["n"] for r in rows
                               if platform_take(r["p"] / 100.0) == 0.0) / k,
        "dead_n_per_item": sum(r["n"] for r in rows
                               if seller_net(r["p"] / 100.0) <= 0.0) / k,
    }

def trapezoid(pages, total_items):
    st = [(s, page_stats(rs)) for s, rs in pages]
    keys = ["n_per_item", "val_per_item", "rev_per_item",
            "zero_n_per_item", "dead_n_per_item"]
    acc = {k: 0.0 for k in keys}
    for i in range(len(st)):
        s0, a = st[i]
        if i + 1 < len(st):
            s1, b = st[i + 1]
        else:
            s1, b = total_items, a          # tail carries the last page's level
        span = max(0, s1 - s0)
        for k in keys:
            acc[k] += span * (a[k] + b[k]) / 2.0
    return acc

def step(pages, total_items):
    st = [(s, page_stats(rs)) for s, rs in pages]
    keys = ["n_per_item", "val_per_item", "rev_per_item",
            "zero_n_per_item", "dead_n_per_item"]
    acc = {k: 0.0 for k in keys}
    for i, (s0, a) in enumerate(st):
        s1 = st[i + 1][0] if i + 1 < len(st) else total_items
        for k in keys:
            acc[k] += max(0, s1 - s0) * a[k]
    return acc

def quantiles(pages):
    """Price quantiles are EXACT-ish: the sample is uniform over price rank,
    so the i-th sampled page sits at a known rank fraction."""
    allp = sorted(r["p"] / 100.0 for _, rs in pages for r in rs)
    lp = []
    for _, rs in pages:
        for r in rs: lp.extend([r["p"] / 100.0] * min(r["n"], 2000))
    lp.sort()
    q = lambda xs, f: xs[min(len(xs) - 1, int(len(xs) * f))] if xs else 0.0
    return {"item_p10": q(allp,.1), "item_med": q(allp,.5), "item_p90": q(allp,.9),
            "item_max": allp[-1] if allp else 0,
            "lst_p10": q(lp,.1), "lst_med": q(lp,.5), "lst_p90": q(lp,.9)}

def report(path, label, total_items):
    pages = load_pages(path)
    if not pages:
        print(f"{label}: NO-PAGES-CAPTURED"); return None
    sampled_items = sum(len(rs) for _, rs in pages)
    tr, sp = trapezoid(pages, total_items), step(pages, total_items)
    qs = quantiles(pages)
    print(f"\n=== {label} ===")
    print(f"  distinct items (Steam total_count)   {total_items:>14,}")
    print(f"  sampled                              {sampled_items:>14,}  "
          f"({100.0*sampled_items/total_items:.1f}% of items, {len(pages)} pages)")
    for nm, e in (("TRAPEZOID", tr), ("step     ", sp)):
        print(f"  [{nm}] listings {e['n_per_item']:>12,.0f}   "
              f"book ${e['val_per_item']:>14,.0f}   "
              f"rev-if-all-trade-once ${e['rev_per_item']:>11,.0f}   "
              f"avg take ${e['rev_per_item']/e['n_per_item'] if e['n_per_item'] else 0:.4f}")
    print(f"  item price   median ${qs['item_med']:>9,.2f}  p10 ${qs['item_p10']:.2f}  "
          f"p90 ${qs['item_p90']:,.2f}  max ${qs['item_max']:,.2f}")
    print(f"  LISTING price median ${qs['lst_med']:>9,.2f}  p10 ${qs['lst_p10']:.2f}  "
          f"p90 ${qs['lst_p90']:,.2f}")
    print(f"  [TRAPEZOID] listings earning EXACTLY $0.00: {tr['zero_n_per_item']:>12,.0f}"
          f"  ({100*tr['zero_n_per_item']/tr['n_per_item'] if tr['n_per_item'] else 0:.1f}%)")
    print(f"  [TRAPEZOID] listings where seller nets <=$0: {tr['dead_n_per_item']:>11,.0f}"
          f"  ({100*tr['dead_n_per_item']/tr['n_per_item'] if tr['n_per_item'] else 0:.1f}%)")
    return {"label": label, "total_items": total_items, "trap": tr, "step": sp, "q": qs}

if __name__ == "__main__":
    report(sys.argv[1], sys.argv[2], int(sys.argv[3]))

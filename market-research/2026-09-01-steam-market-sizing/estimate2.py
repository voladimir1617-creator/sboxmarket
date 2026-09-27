"""Adds a GEOMETRIC interpolator: price rank curves are power-law-ish, so the
arithmetic trapezoid overestimates every gap. Validated against a known truth
before use."""
import json, math
from fees import platform_take, seller_net

KEYS = ["n_per_item", "val_per_item", "rev_per_item",
        "zero_n_per_item", "dead_n_per_item"]

def load_pages(path):
    pages = {}
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r["p"] is None or r["n"] is None: continue
        pages.setdefault(r["_start"], []).append(r)
    return sorted(pages.items())

def page_stats(rows):
    k = len(rows)
    return {"k": k,
        "n_per_item":   sum(r["n"] for r in rows) / k,
        "val_per_item": sum(r["n"] * r["p"] for r in rows) / 100.0 / k,
        "rev_per_item": sum(r["n"] * platform_take(r["p"] / 100.0) for r in rows) / k,
        "zero_n_per_item": sum(r["n"] for r in rows if platform_take(r["p"]/100.0) == 0.0)/k,
        "dead_n_per_item": sum(r["n"] for r in rows if seller_net(r["p"]/100.0) <= 0.0)/k}

def integrate(pages, total_items, mode):
    st = [(s, page_stats(rs)) for s, rs in pages]
    acc = {k: 0.0 for k in KEYS}
    for i in range(len(st)):
        s0, a = st[i]
        s1, b = (st[i+1][0], st[i+1][1]) if i + 1 < len(st) else (total_items, a)
        span = max(0, s1 - s0)
        if span == 0: continue
        for k in KEYS:
            x, y = a[k], b[k]
            if mode == "step":  m = x
            elif mode == "trap": m = (x + y) / 2.0
            else:  # geometric: exact for an exponential decay across the gap
                if x > 0 and y > 0 and abs(math.log(x) - math.log(y)) > 1e-9:
                    m = (x - y) / (math.log(x) - math.log(y))   # log-mean
                else:
                    m = (x + y) / 2.0
            acc[k] += span * m
    return acc

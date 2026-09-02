"""Total listings = EXACT listings of the K deepest-book items (captured by
quantity-desc) + the price-rank sample's estimate for everything else.

A rank-systematic sample over PRICE estimates price well but listing COUNT
badly, because listings concentrate in a handful of penny commodity items that
the sample only hits by luck. TF2's price-rank sample said 366,424 listings
while a single item holds 2.3M."""
import json, os
import estimate2
from estimate import quantiles, load_pages
from fees import platform_take

def head(appid):
    p = f"rows_{appid}_qty.jsonl"
    if not os.path.exists(p): return {}
    out = {}
    for line in open(p, encoding="utf-8"):
        r = json.loads(line)
        if r["p"] is None or r["n"] is None: continue
        out[r["h"]] = r          # dedup by name, keep last
    return out

def combined(appid, total, stride):
    pages = load_pages(f"rows_{appid}_pd.jsonl")
    H = head(appid)
    head_n   = sum(r["n"] for r in H.values())
    head_val = sum(r["n"] * r["p"] for r in H.values()) / 100.0
    head_rev = sum(r["n"] * platform_take(r["p"]/100.0) for r in H.values())
    head_zero= sum(r["n"] for r in H.values() if platform_take(r["p"]/100.0) == 0.0)
    # tail: same pages, but any item already counted exactly in the head is zeroed
    masked = []
    for s, rs in pages:
        m = []
        for r in rs:
            m.append(dict(r, n=0) if r["h"] in H else r)
        masked.append((s, m))
    if stride == 1:
        seen, rows = set(), []
        for _, rs in masked:
            for r in rs:
                if r["h"] in seen: continue
                seen.add(r["h"]); rows.append(r)
        tail = {"n_per_item": sum(r["n"] for r in rows),
                "val_per_item": sum(r["n"]*r["p"] for r in rows)/100.0,
                "rev_per_item": sum(r["n"]*platform_take(r["p"]/100.0) for r in rows),
                "zero_n_per_item": sum(r["n"] for r in rows
                                       if platform_take(r["p"]/100.0)==0.0)}
    else:
        tail = estimate2.integrate(masked, total, "geom")
    return {"listings": head_n + tail["n_per_item"],
            "book":     head_val + tail["val_per_item"],
            "rev":      head_rev + tail["rev_per_item"],
            "zero":     head_zero + tail["zero_n_per_item"],
            "head_items": len(H), "head_listings": head_n,
            "q": quantiles(pages)}

"""GMV from measured 24h volume, priced at Steam's 24h MEDIAN SALE price.

Why the median and not the search endpoint's sell_price: sell_price is the
current LOWEST ASK. On ultra-high-volume commodity items the two diverge
enormously -- Dreams & Nightmares Case measured lowest $0.13 against a 24h
median of $1.68 (13x) with 93,036 sales/day. Using the ask would understate
GMV by an order of magnitude exactly where the volume is. On ordinary items
the two agree (s&box median ratio 0.999, Rust 1.000)."""
import json, random, re, sys
from fees import platform_take

def num(s):
    if not s: return None
    s = re.sub(r"[^0-9.,]", "", s).replace(",", "")
    try: return float(s)
    except Exception: return None

def load(path):
    ok, miss, abort = [], [], {}
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            abort[r["abort"]] = abort.get(r["abort"], 0) + 1; continue
        if r.get("vol") is None:
            miss.append(r); continue
        px = num(r.get("med")) or (r["p"] / 100.0)     # median, ask as fallback
        r["px"] = px; ok.append(r)
    return ok, miss, abort

def report(path, label, total_items, boot=3000):
    ok, miss, abort = load(path)
    n = len(ok) + len(miss)
    if n == 0: print(f"{label}: NO-SAMPLE"); return None
    scale = total_items / float(n)
    pool = ok + [dict(r, px=0.0, vol=0) for r in miss]
    daily = sum(r["vol"] * r["px"] for r in ok) * scale
    revd  = sum(r["vol"] * platform_take(r["px"]) for r in ok) * scale
    bs = []
    for _ in range(boot):
        s = [pool[random.randrange(len(pool))] for _ in range(len(pool))]
        bs.append(sum(r["vol"] * r["px"] for r in s) * scale)
    bs.sort(); lo, hi = bs[int(.05*boot)], bs[int(.95*boot)]
    vols = sorted(r["vol"] for r in ok)
    print(f"\n=== TURNOVER {label} ===")
    print(f"  sampled {n} items ({len(ok)} sold in 24h, {len(miss)} sold NOTHING, "
          f"aborts {abort or '{}'}) scale x{scale:,.1f}")
    print(f"  est. sales/day {sum(r['vol'] for r in ok)*scale:>14,.0f}"
          f"   median vol/item(sellers) {vols[len(vols)//2] if vols else 0}")
    print(f"  est. GMV/day   ${daily:>14,.0f}   90% CI ${lo:,.0f} - ${hi:,.0f}")
    print(f"  est. GMV/YEAR  ${daily*365:>14,.0f}   90% CI ${lo*365:,.0f} - ${hi*365:,.0f}")
    print(f"  platform rev/YEAR at 2%, 100% share  ${revd*365:>12,.0f}")
    return {"label": label, "gmv_yr": daily*365, "ci_yr": (lo*365, hi*365),
            "rev_yr": revd*365, "n": n, "silent": len(miss), "aborts": abort}

if __name__ == "__main__":
    report(sys.argv[1], sys.argv[2], int(sys.argv[3]))

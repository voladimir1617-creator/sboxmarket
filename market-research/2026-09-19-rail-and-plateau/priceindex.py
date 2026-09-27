"""Is the median trade falling because PRICES fall, or because the MIX shifts?

A volume-weighted median trade of $4.95 on day 1 and $0.50 on day 9 has two
completely different explanations and they lead to opposite conclusions:

  PRICE COLLAPSE   the same items are worth less. The market is deflating, and
                   the $0.48 median the candidate was picked on is a launch
                   artefact that will keep falling.
  MIX SHIFT        item prices hold, and volume simply moves to cheap items as
                   supply arrives. The expensive tail is intact, and a fee
                   model that lives in the tail is unharmed.

The volume-weighted median cannot tell them apart. A FIXED-BASKET index can:
track each item against ITS OWN first-week price and take the median across
items, so the composition of the basket cannot move the number.

METHOD. For every sampled item, take its median transaction price in its first
complete 3 days (`base`) and in each later 3-day window. The index at day k is
the MEDIAN over items of (price_k / base). An item absent from a window is
dropped from that window rather than carried forward: a price we did not
observe is not a price that stayed the same.

This is deliberately the median of ratios, not the ratio of medians. The ratio
of medians is a mix-shift statistic again -- the very thing being controlled
for.
"""
import datetime
import io
import os
import statistics
import sys

if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from curve import CANDS, load, TODAY, D   # noqa: E402

BASE_DAYS = 3
STEP = 3


def per_item_qty(rows, origin):
    """{hash: {window_index: units sold}} over the same windows as the price
    index, so price and quantity can be read against each other.

    Price alone says the market is deflating. Price AND quantity say WHY: if
    units rise while price falls, supply is shifting out -- the game is
    minting loot faster than players absorb it -- and that is a mechanism,
    not just an outcome. If both fall, demand is leaving. The two have
    different futures and the price index cannot tell them apart."""
    out = {}
    for r in rows:
        b = {}
        for t, p, q in r["pts"]:
            d = datetime.datetime.fromtimestamp(t, datetime.UTC).date()
            if d >= TODAY:
                continue
            w = (d - origin).days // STEP
            b[w] = b.get(w, 0) + q
        out[r["h"]] = b
    return out


def per_item_windows(rows):
    """{hash: {window_index: volume-weighted median price}}"""
    firsts = {}
    for r in rows:
        if r["pts"]:
            firsts[r["h"]] = min(datetime.datetime.fromtimestamp(t, datetime.UTC).date()
                                 for t, _, _ in r["pts"])
    if not firsts:
        return {}, None
    origin = min(firsts.values())
    out = {}
    for r in rows:
        buckets = {}
        for t, p, q in r["pts"]:
            d = datetime.datetime.fromtimestamp(t, datetime.UTC).date()
            if d >= TODAY:
                continue
            w = (d - origin).days // STEP
            buckets.setdefault(w, []).append((p, q))
        med = {}
        for w, pairs in buckets.items():
            pairs.sort()
            tot = sum(q for _, q in pairs)
            c = 0
            for p, q in pairs:
                c += q
                if c >= tot / 2.0:
                    med[w] = p
                    break
        out[r["h"]] = med
    return out, origin


for ap, lab, total, _ in CANDS:
    rows, never, unread = load(ap)
    if not rows:
        continue
    per, origin = per_item_windows(rows)
    if not per:
        continue
    maxw = max((max(m) for m in per.values() if m), default=0)
    # baseline window: the item's own window 0 (first 3 days of the MARKET)
    base = {h: m[0] for h, m in per.items() if 0 in m}
    print("=" * 78)
    print("%s (%d) — fixed-basket price index, base = market days 1-%d"
          % (lab, ap, BASE_DAYS))
    print("=" * 78)
    print("  basket: %d of %d sampled items traded in the base window"
          % (len(base), len(rows) + never))
    if len(base) < 5:
        print("  too few items in the base window to index; skipping\n")
        continue
    qty = per_item_qty(rows, origin)
    qbase = {h: qty[h][0] for h in base if qty.get(h, {}).get(0)}
    print("  %10s %8s %12s %9s %9s %14s"
          % ("market day", "n items", "PRICE index", "p25", "p75", "QUANTITY index"))
    for w in range(0, maxw + 1):
        rs = [per[h][w] / base[h] for h in base if w in per[h] and base[h]]
        if len(rs) < 5:
            continue
        rs.sort()
        qn = sum(qty[h].get(w, 0) for h in qbase)
        qd = sum(qbase[h] for h in qbase)
        print("  %4d-%-5d %8d %11.3f %9.3f %9.3f %14s"
              % (w * STEP + 1, w * STEP + STEP, len(rs), statistics.median(rs),
                 rs[int(.25 * len(rs))], rs[int(.75 * len(rs))],
                 ("x%.2f" % (qn / qd)) if qd else "-"))
    last = [per[h][maxw] / base[h] for h in base if maxw in per[h] and base[h]]
    if len(last) >= 5:
        print()
        print("  LATEST INDEX %.3f  ->  the SAME items are worth %.0f%% %s than"
              % (statistics.median(last), abs(1 - statistics.median(last)) * 100,
                 "LESS" if statistics.median(last) < 1 else "MORE"))
        print("  they were in the market's first %d days (n=%d items). That is a"
              % (BASE_DAYS, len(last)))
        print("  %s, not a change in which items people buy."
              % ("PRICE COLLAPSE" if statistics.median(last) < 0.7 else
                 "flat price level" if statistics.median(last) > 0.9 else
                 "moderate price decline"))
    print()

"""Controls that run BEFORE any number from this capture is believed.

The 2026-09-01 METHOD.md put its controls first for a reason, and this pass
introduces a NEW endpoint -- the sales series embedded in the market listing
page -- so it inherits none of the old ones. Three things have to be true
before the curve means anything.

1. CURRENCY. The listing page takes no `currency` parameter. If it renders in
   anything but USD, every GMV figure here is wrong by an exchange rate and
   would still look completely plausible. The check is direct: the prior pass
   fetched `priceoverview` for the SAME items with `currency=1` pinned, and
   its `median_price` strings are dollar-denominated. Comparing the last
   bucket's `price_median` against that string is a currency test with an
   answer, not an assumption.

2. VOLUME. Summing `purchases` over the last 24 hours must reproduce
   `priceoverview.volume` for the same items. Two endpoints, one quantity.
   This is the check that decides whether WoG's 95,286 trades/day is real,
   and an outlier is a bug until proven otherwise.

3. ABORT STATES ARE DISTINCT AND NONE OF THEM LOOKS LIKE AN EMPTY MARKET.
   The reader has ITEM-NOT-FOUND, NO-HISTORY-BLOCK, HISTORY-UNPARSEABLE,
   HTTP-<code>, HTTP-429-EXHAUSTED, EMPTY-BODY and RETRIES-EXHAUSTED-*, and
   exactly one of them (NO-HISTORY-BLOCK, where the item's own page rendered
   and carried no series) is read as a measured zero. This prints the tally so
   a silent reclassification would be visible.
"""
import datetime
import io
import json
import os
import re
import statistics
import sys

if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from curve import CANDS, load, D, NOW   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PRIOR = os.path.abspath(os.path.join(HERE, "..", "2026-09-19-appid-hunt", "books"))
VOLFILE = {4891320: "cand_vol_4891320.jsonl", 4892010: "cand_vol_4892010.jsonl",
           3678970: "vol_3678970.jsonl",     3419430: "cand_vol_3419430.jsonl"}


def num(s):
    if not s:
        return None
    s = re.sub(r"[^0-9.,]", "", s).replace(",", "")
    try:
        return float(s)
    except Exception:
        return None


def prior(appid):
    out = {}
    p = os.path.join(PRIOR, VOLFILE[appid])
    if not os.path.exists(p):
        return out
    for line in open(p, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            continue
        out[r["h"]] = r
    return out


print("CONTROLS — %s UTC" % NOW.strftime("%Y-%m-%d %H:%M"))
print()
allok = True
for ap, lab, total, _ in CANDS:
    rows, never, unread = load(ap)
    if not rows:
        continue
    pv = prior(ap)
    print("=" * 78)
    print("%s (%d)" % (lab, ap))
    print("=" * 78)

    # ---- 1. currency -------------------------------------------------
    ratios = []
    for r in rows:
        p = pv.get(r["h"])
        if not p:
            continue
        m = num(p.get("med"))
        if not m or not r["pts"]:
            continue
        ratios.append(r["pts"][-1][1] / m)
    if ratios:
        med = statistics.median(ratios)
        ok = 0.6 <= med <= 1.7
        allok &= ok
        print("  1. CURRENCY  last listing-page price_median / priceoverview "
              "median_price(currency=1)")
        print("     n=%d  median ratio %.4f   p10 %.3f  p90 %.3f   -> %s"
              % (len(ratios), med, sorted(ratios)[int(.1 * len(ratios))],
                 sorted(ratios)[int(.9 * len(ratios))],
                 "USD, same scale" if ok else "*** NOT USD / SCALE MISMATCH ***"))
        print("     (a non-USD render would show a ratio near a exchange rate —")
        print("      ~0.0007 for KRW, ~1.08 for EUR, ~0.0067 for JPY — not near 1.")
        print("      The spread around 1 is the gap between a LAST bucket and a")
        print("      24-hour median, which is real dispersion, not a unit error.)")
    else:
        print("  1. CURRENCY  NOT CHECKABLE — no overlapping items")
        allok = False

    # ---- 2. volume ---------------------------------------------------
    cut = NOW - datetime.timedelta(hours=24)
    sa = sb = 0
    n = 0
    for r in rows:
        p = pv.get(r["h"])
        if not p or p.get("vol") is None:
            continue
        sa += sum(q for t, _, q in r["pts"]
                  if datetime.datetime.fromtimestamp(t, datetime.UTC) >= cut)
        sb += p["vol"]
        n += 1
    if sb:
        ratio = sa / sb
        ok = 0.7 <= ratio <= 1.4
        allok &= ok
        print("  2. VOLUME    listing-page purchases in 24h vs priceoverview.volume")
        print("     %s vs %s over n=%d items  ->  ratio %.3f  %s"
              % (format(sa, ","), format(sb, ","), n, ratio,
                 "AGREE" if ok else "*** DISAGREE — do not use these counts ***"))
    else:
        print("  2. VOLUME    NOT CHECKABLE")
        allok = False

    # ---- 3. aborts ---------------------------------------------------
    print("  3. ABORTS    read %d items, %d NEVER-SOLD (measured zero, in the"
          % (len(rows), never))
    print("               denominator), %s unread (excluded from both)"
          % (unread or "0"))

    # ---- 4. falsify the one abort state read as a zero ---------------
    # NO-HISTORY-BLOCK is the only state this pass converts into "never
    # sold". If Steam simply omits the series on some pages, that conversion
    # invents zeros -- so test it against the one independent witness we
    # have: an item the prior pass measured selling in the last 24h CANNOT
    # be an item that has never sold.
    contra = []
    seen = set()
    path = os.path.join(HERE, "books", "hist_%d.jsonl" % ap)
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort") != "NO-HISTORY-BLOCK" or r["h"] in seen:
            continue
        seen.add(r["h"])
        p = pv.get(r["h"])
        if p and p.get("vol"):
            contra.append((r["h"], p["vol"]))
    print("  4. FALSIFY   items called NEVER-SOLD that the prior pass measured")
    print("               selling in 24h: %d of %d  ->  %s"
          % (len(contra), len(seen),
             "the zero-classification holds" if not contra
             else "*** IT DOES NOT — %s ***" % contra[:3]))
    allok &= not contra
    print()

print("=" * 78)
print("ALL CONTROLS PASS" if allok else "*** A CONTROL FAILED — see above ***")
print("=" * 78)

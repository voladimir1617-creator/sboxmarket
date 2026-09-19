"""Days of inventory and top-3 concentration, recomputed on measured sales.

BOTH OF THESE INVERTED A READING ONCE ALREADY. TBH's 14,431,626 standing
listings looked like depth and were 266 days of unsold book -- an unsold
listing is an item already refused at that price, so a deep book is evidence
of rejection, not of reserve. And a market that is three items is a queue of
one, whatever its GMV says.

WHAT IS DIFFERENT HERE. The prior pass divided the standing book by a
trades/day taken from a single 24-hour `priceoverview` snapshot. A snapshot
picks up whatever the last day happened to be, and on a market nine days old
and still climbing 16%/day that is the wrong denominator by a lot. This
divides by a TRAILING 14-DAY MEAN of measured transactions, and reports both
so the difference is visible rather than silent.

Standing listings come from the prior pass's book walk (`cand_rows_*.jsonl` /
`rows_3678970_pd.jsonl`), which is the same capture the 2026-09-19 numbers
were computed from.
"""
import datetime
import io
import json
import os
import statistics
import sys

if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from curve import CANDS, load, daily, TODAY, D   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PRIOR = os.path.abspath(os.path.join(HERE, "..", "2026-09-19-appid-hunt", "books"))
BOOKFILE = {4891320: "cand_rows_4891320.jsonl", 4892010: "cand_rows_4892010.jsonl",
            3678970: "rows_3678970_pd.jsonl",  3419430: "cand_rows_3419430.jsonl"}
WIN = 14


def book(appid):
    rows, unread = {}, 0
    p = os.path.join(PRIOR, BOOKFILE[appid])
    if not os.path.exists(p):
        return rows, unread
    for line in open(p, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            unread += 1
            continue
        if r.get("h") and r.get("p") is not None:
            rows[r["h"]] = r
    return rows, unread


print("MEASURED %s UTC."
      % datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%d %H:%M"))
print()
for ap, lab, total, debias in CANDS:
    hrows, never, hunread = load(ap)
    if not hrows:
        print("=== %s: no history captured ===\n" % lab)
        continue
    bk, bunread = book(ap)
    scale = total / float(len(hrows) + never)
    dd = daily(hrows)
    comp = sorted(d for d in dd if d < TODAY)
    tail = [d for d in comp if d >= comp[-1] - datetime.timedelta(days=WIN - 1)]
    tday = sum(dd[d][1] for d in tail) / len(tail) * scale
    lastday = dd[comp[-1]][1] * scale
    listings = sum(r["n"] or 0 for r in bk.values())

    print("=" * 78)
    print("%s (%d)" % (lab, ap))
    print("=" * 78)
    print("  standing listings %14s  (%d of %d items in the book walk, %d unread)"
          % (format(listings, ","), len(bk), total, bunread))
    print("  trades/day, trailing %2d-day mean %s   last complete day %s"
          % (len(tail), format(int(tday), ","), format(int(lastday), ",")))
    di_t = listings / tday if tday else float("inf")
    di_l = listings / lastday if lastday else float("inf")
    print("  DAYS OF INVENTORY  %8.1f (trailing mean)   %8.1f (last day only)"
          % (di_t, di_l))
    print("    %s" % ("LIQUID" if di_t < 14 else
                      "SATURATED — an unsold book is a population already "
                      "rejected at that price"))
    if abs(di_t - di_l) / max(di_t, di_l) > 0.2:
        print("    NOTE: the two denominators disagree by %.0f%%. A single-day"
              % (100 * abs(di_t - di_l) / max(di_t, di_l)))
        print("    snapshot is the wrong denominator on a market that is moving.")

    # per-item days to clear, over items we measured BOTH ways
    per = []
    for r in hrows:
        n = bk.get(r["h"], {}).get("n")
        if not n:
            continue
        v = sum(q for t, _, q in r["pts"]
                if datetime.datetime.fromtimestamp(t, datetime.UTC).date() in tail)
        v = v / float(len(tail))
        if v > 0:
            per.append((n / v, r["h"], n, v))
    per.sort()
    if per:
        ds = [x[0] for x in per]
        print("  per-item days-to-clear: median %.2f  p25 %.2f  p75 %.2f  (n=%d selling)"
              % (statistics.median(ds), ds[int(.25 * len(ds))],
                 ds[int(.75 * len(ds))], len(ds)))
        print("    fastest %-34s %8s listings / %8.1f per day = %.2f d"
              % (per[0][1][:34], format(per[0][2], ","), per[0][3], per[0][0]))
        print("    slowest %-34s %8s listings / %8.1f per day = %.1f d"
              % (per[-1][1][:34], format(per[-1][2], ","), per[-1][3], per[-1][0]))

    # concentration, on measured GMV over the trailing window
    g = {}
    for r in hrows:
        tot = 0.0
        for t, p, q in r["pts"]:
            if datetime.datetime.fromtimestamp(t, datetime.UTC).date() in tail:
                tot += p * q
        if tot:
            g[r["h"]] = tot
    tot = sum(g.values())
    top = sorted(g.items(), key=lambda kv: -kv[1])
    print("  CONCENTRATION over %d days of measured GMV (%d selling rows of %d sampled):"
          % (len(tail), len(g), len(hrows) + never))
    for k in (1, 3, 10):
        if len(top) >= k:
            print("    top-%-2d %5.1f%%   %s"
                  % (k, 100 * sum(v for _, v in top[:k]) / tot,
                     "  |  ".join(n[:22] for n, _ in top[:k])
                     if k <= 3 else ""))
    print("  items that sold NOTHING in the window: %d of %d sampled (%.0f%%)"
          % (len(hrows) + never - len(g), len(hrows) + never,
             100.0 * (len(hrows) + never - len(g)) / (len(hrows) + never)))
    print("  unread history rows (NOT counted as zeros): %s" % (hunread or "none"))
    print()

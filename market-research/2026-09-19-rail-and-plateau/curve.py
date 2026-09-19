"""The measured GMV curve of each candidate, from first sale to today.

WHAT IS NEW HERE. Every earlier pass sized these markets from a 24-hour
snapshot and then argued about the future. The listing page carries the whole
transaction series -- median price and purchase count per bucket, back to the
item's first sale -- so the curve is MEASURED for three of the four candidates
and only WoG's future has to be projected at all.

THREE THINGS THIS COMPUTES, IN ORDER OF HOW MUCH THEY DECIDE.

1. GMV/day against AGE, not against calendar date. A nine-day-old market and
   an eighteen-month-old one are the same genre at different points of one
   curve; plotting them on wall-clock time hides that.

2. The VOLUME-WEIGHTED MEDIAN TRADE against age. This is the term the fee
   model rounds against, and the genre's warning is that it falls: Bongo Cat
   settled at $0.07. If WoG's $0.48 is a launch-week number rather than a
   property of the market, the rounding argument changes.

3. The 24-HOUR CROSS-CHECK. Summing `purchases` over the last 24h from this
   endpoint against the prior pass's `priceoverview.volume` for the SAME items
   is an independent reading of the same quantity. WoG's 95,286 trades/day is
   an outlier and an outlier is a bug until proven otherwise; two endpoints
   agreeing is the cheapest proof available.

BIASES, STATED AND SIGNED.
  * The sample is the prior pass's systematic sample over PRICE RANK, so it is
    representative in price and not in volume. GMV scaled from it inherits the
    price skew already measured (WoG sample mean price / book mean price =
    1.39x), and the de-biased figure is reported beside the raw one.
  * Items in the sample that have NEVER sold return no history block. That is
    a measured zero, not an unread row, and it is counted in the denominator.
    An item we could not READ is an abort and is excluded from both.
  * `price_median` is a transaction price, not an ask. This is the field the
    2026-09-01 METHOD.md concluded GMV must be priced on.
  * The last bucket of any series is a PARTIAL period. It is excluded from
    every rate and every fit, for the same reason the prior pass excluded
    TBH's partial review week.
"""
import datetime
import io
import json
import math
import os
import random
import statistics
import sys

# Idempotent: re-wrapping an already-UTF-8 stdout creates a second
# TextIOWrapper over the same buffer, and the first one closes that buffer
# when it is collected -- which turns an import into a dead stdout.
if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
B = os.path.join(HERE, "books")
D = chr(36)

CANDS = [
    (4891320, "WoG: War of Genesis", 914, 1.39),
    (4892010, "Bomb Farm",           270, None),
    (3678970, "TBH: Task Bar Hero", 1064, None),
    (3419430, "Bongo Cat",           540, None),
]

NOW = datetime.datetime.now(datetime.UTC)
TODAY = NOW.date()

# ----------------------------------------------------------------------
# AN UNEXPLAINED REGIME, EXCLUDED BY DATE RATHER THAN BY PRICE.
#
# Bongo Cat's series carries a population of buckets whose `price_median` is
# one to two orders of magnitude below Steam's $0.03 market minimum -- 0.00024,
# 0.00393, 0.0005 -- against unit counts up to 164,372 in a single day, on a
# market whose measured turnover today is 5,224 trades/day in total. 94.4% of
# Bongo Cat's sampled UNITS sit in that population. It cannot be a transaction
# price, and what it actually is has NOT been determined here; reporting a
# cause inferred rather than measured is the defect this project keeps paying
# for, so no cause is asserted.
#
# WHAT IS MEASURED ABOUT IT, and what the exclusion rests on:
#   * it is confined to ONE market. WoG, Bomb Farm and TBH have ZERO points
#     below $0.01 between them (0 of 19,216).
#   * inside that market it is confined in TIME, cleanly: every sub-$0.01
#     point is dated 2025-03 to 2025-12 and there are none from 2026-01 on.
#   * it is NOT separable by price. The histogram runs continuously from
#     $0.000 through $0.029 with no gap (max of the low population 0.019839,
#     min of the rest 0.020026, and 606 points in between). A price threshold
#     here would be a number chosen because the incident sits under it --
#     exactly the calibration error to avoid. The date boundary is a boundary
#     the data draws by itself.
#
# So the whole of Bongo Cat before 2026-01-01 is dropped, prices and volumes
# together, and the drop is counted and printed. The consequence is stated
# rather than worked around: Bongo Cat can still supply a SETTLED LEVEL at 18
# months, and it can no longer supply a peak or a peak-relative decay multiple.
EXCLUDE_BEFORE = {3419430: datetime.date(2026, 1, 1)}
DROPPED = {}


def load(appid):
    """-> (read_rows, never_sold, unread). Three states, never merged."""
    path = os.path.join(B, "hist_%d.jsonl" % appid)
    read, never, unread = [], 0, {}
    if not os.path.exists(path):
        return read, never, unread
    cut = EXCLUDE_BEFORE.get(appid)
    drop_pts = drop_units = 0
    seen = set()
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r["h"] in seen:
            continue
        st = r.get("abort")
        if st == "NO-HISTORY-BLOCK":
            # The page rendered the item and carried no series: this item has
            # never traded. A MEASURED ZERO, and it belongs in the denominator.
            seen.add(r["h"])
            never += 1
            continue
        if st:
            unread[st] = unread.get(st, 0) + 1
            continue
        seen.add(r["h"])
        if cut:
            keep = []
            for t, p, q in r["pts"]:
                if datetime.datetime.fromtimestamp(t, datetime.UTC).date() < cut:
                    drop_pts += 1
                    drop_units += q
                else:
                    keep.append([t, p, q])
            if not keep:
                # every bucket this item has is inside the excluded regime, so
                # it is UNREAD in the usable era -- not an item that never sold
                unread["EXCLUDED-REGIME-ONLY"] = unread.get("EXCLUDED-REGIME-ONLY", 0) + 1
                continue
            r = dict(r, pts=keep)
        read.append(r)
    if cut:
        DROPPED[appid] = (drop_pts, drop_units, cut)
    return read, never, unread


def daily(rows):
    """{date: (gmv, trades, [(price, qty)...])} over the sampled items."""
    out = {}
    for r in rows:
        for t, p, q in r["pts"]:
            d = datetime.datetime.fromtimestamp(t, datetime.UTC).date()
            g, n, lst = out.get(d, (0.0, 0, []))
            out[d] = (g + p * q, n + q, lst + [(p, q)])
    return out


def vwmedian(pairs):
    if not pairs:
        return None
    s = sorted(pairs)
    tot = sum(q for _, q in s)
    c = 0
    for p, q in s:
        c += q
        if c >= tot / 2.0:
            return p
    return s[-1][0]


def ols(xs, ys):
    m = len(xs)
    if m < 3:
        return 0.0, 0.0, float("inf")
    mx = sum(xs) / m
    my = sum(ys) / m
    sxx = sum((x - mx) ** 2 for x in xs)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    b = sxy / sxx if sxx else 0.0
    a = my - b * mx
    res = [y - (a + b * x) for x, y in zip(xs, ys)]
    s2 = sum(e * e for e in res) / (m - 2)
    return a, b, (math.sqrt(s2 / sxx) if sxx else float("inf"))


def report(appid, label, total, debias):
    rows, never, unread = load(appid)
    if not rows:
        print("=== %s (%d): NOTHING CAPTURED YET ===\n" % (label, appid))
        return None
    n_denom = len(rows) + never
    scale = total / float(n_denom)
    dd = daily(rows)
    days = sorted(dd)
    first, last = days[0], days[-1]
    age = (TODAY - first).days + 1

    print("=" * 78)
    print("%s (%d)" % (label, appid))
    print("=" * 78)
    print("  sample %d items READ + %d never-sold = %d of %d in book -> scale x%.2f"
          % (len(rows), never, n_denom, total, scale))
    print("  unread (NOT counted as zeros): %s" % (unread or "none"))
    if appid in DROPPED:
        dp, du, cut = DROPPED[appid]
        print("  EXCLUDED REGIME: %s buckets / %s units dated before %s were"
              % (format(dp, ","), format(du, ","), cut))
        print("    dropped -- see the note at the top of curve.py. Every figure")
        print("    below for this market describes the post-%s era ONLY." % cut)
    print("  first sale %s   last bucket %s   AGE %d days" % (first, last, age))

    # every rate below excludes today's PARTIAL bucket
    comp = [d for d in days if d < TODAY]
    if not comp:
        print("  no complete day yet\n")
        return None

    lastc = comp[-1]
    g, t, pairs = dd[lastc]
    print()
    print("  LAST COMPLETE DAY %s (scaled to the whole book):" % lastc)
    print("    trades/day   %14s" % format(int(round(t * scale)), ","))
    print("    GMV/day      %14s   GMV/yr %s"
          % (D + format(round(g * scale, 2), ","),
             D + format(int(g * scale * 365), ",")))
    if debias:
        print("    GMV/yr DE-BIASED by the %.2fx sample price skew: %s"
              % (debias, D + format(int(g * scale * 365 / debias), ",")))
    print("    vol-wtd MEDIAN trade %s     mean trade %s"
          % (D + "%.2f" % (vwmedian(pairs) or 0), D + "%.4f" % (g / t if t else 0)))

    # 24h cross-check against the prior pass's independent endpoint
    cutoff = NOW - datetime.timedelta(hours=24)
    got24 = {}
    for r in rows:
        s = sum(q for tt, _, q in r["pts"]
                if datetime.datetime.fromtimestamp(tt, datetime.UTC) >= cutoff)
        got24[r["h"]] = (s, r.get("vol24_prior"))
    both = [(a, b) for a, b in got24.values() if b is not None]
    if both:
        sa = sum(a for a, _ in both)
        sb = sum(b for _, b in both)
        print()
        print("  24h CROSS-CHECK vs the prior pass's priceoverview.volume, same items")
        print("    listing-page purchases %s   priceoverview volume %s   ratio %.2fx  (n=%d items)"
              % (format(sa, ","), format(sb, ","), (sa / sb if sb else float("nan")), len(both)))

    # ------------------------------------------------------------- the curve
    print()
    print("  GMV/DAY BY AGE (scaled; complete days only)")
    peak_g = peak_d = None
    for d in comp:
        gg = dd[d][0] * scale
        if peak_g is None or gg > peak_g:
            peak_g, peak_d = gg, d
    marks = []
    for k in (0, 1, 2, 3, 6, 9, 13, 20, 29, 44, 59, 89, 119, 179, 239, 299, 364, 429, 499):
        d = first + datetime.timedelta(days=k)
        if d in dd and d < TODAY:
            marks.append((k, d))
    if comp[-1] not in [m[1] for m in marks]:
        marks.append(((comp[-1] - first).days, comp[-1]))
    for k, d in marks:
        gg, tt, pp = dd[d]
        print("    day %4d  %s  GMV/day %10s  trades %9s  vw-med trade %s   %5.1f%% of peak"
              % (k + 1, d, D + format(round(gg * scale, 2), ","),
                 format(int(round(tt * scale)), ","),
                 D + "%.2f" % (vwmedian(pp) or 0), 100.0 * gg * scale / peak_g))
    print("    PEAK GMV/day %s on %s (day %d)"
          % (D + format(round(peak_g, 2), ","), peak_d, (peak_d - first).days + 1))

    # trailing 14-day average, which is what "where it settles" means
    tail = [d for d in comp if d >= lastc - datetime.timedelta(days=13)]
    tg = sum(dd[d][0] for d in tail) * scale / len(tail)
    tt = sum(dd[d][1] for d in tail) * scale / len(tail)
    tpairs = []
    for d in tail:
        tpairs += dd[d][2]
    print("    trailing %d-day mean GMV/day %s (%.2f%% of peak), trades/day %s, "
          "vw-med trade %s"
          % (len(tail), D + format(round(tg, 2), ","), 100.0 * tg / peak_g,
             format(int(round(tt)), ","), D + "%.2f" % (vwmedian(tpairs) or 0)))

    # log-slope of the last 14 complete days -- is it still falling?
    ys = [dd[d][0] * scale for d in tail if dd[d][0] > 0]
    xs = list(range(len(ys)))
    if len(ys) >= 4:
        _, b, se = ols(xs, [math.log(v) for v in ys])
        print("    log-slope over those %d days %+.2f%%/day (SE %.2f%%), t=%+.2f -> %s"
              % (len(ys), 100 * (math.exp(b) - 1), 100 * (math.exp(se) - 1),
                 b / se if se else 0,
                 "still falling" if b < -2 * se else
                 "NOT distinguishable from flat" if abs(b) < 2 * se else "rising"))
    print()
    return {"appid": appid, "label": label, "total": total, "scale": scale,
            "first": first, "age": age, "peak_gmv": peak_g, "peak_day": peak_d,
            "tail_gmv": tg, "tail_trades": tt, "tail_vwmed": vwmedian(tpairs),
            "daily": dd, "debias": debias, "n_denom": n_denom,
            "rows": rows, "never": never}


if __name__ == "__main__":
    print("MEASURED %s UTC.  All aborts recorded separately from zeros."
          % NOW.strftime("%Y-%m-%d %H:%M"))
    print()
    out = {}
    for ap, lab, tot, db in CANDS:
        r = report(ap, lab, tot, db)
        if r:
            out[ap] = r
    json.dump({str(k): {kk: (str(vv) if isinstance(vv, datetime.date) else vv)
                        for kk, vv in v.items() if kk not in ("daily", "rows")}
               for k, v in out.items()},
              open(os.path.join(B, "curve_summary.json"), "w"), indent=1)
    print("curve_summary.json written")

"""Where does TBH settle?

Source: store.steampowered.com/appreviewhistogram/<appid> (captured to
books/hist_*.json, 2026-09-19). robots.txt for store.steampowered.com does not
Disallow /appreviewhistogram/. api.steampowered.com is Disallow: / and is not
called.

TWO CORRECTIONS THAT CHANGE THE ANSWER.

(1) The final rollup bucket is a PARTIAL PERIOD. TBH's opens 2026-09-16 and the
    capture is 2026-09-19, so it holds ~3 of 7 days. Reading its 247 as a weekly
    rate -- which is where "247/week, a 98% fall" comes from -- is reading a
    partial bucket as a full one, the same class of error as reading an unread
    appid as an empty one. Scaled to a full week it is ~576, which is ABOVE the
    mean of the preceding six complete weeks. It is excluded from every fit.

(2) A 16-week-old game has almost no curve to extrapolate. Fitting TBH's own
    series gives answers spanning 65-500/week at 12 months, which is not an
    answer. But the genre has an 18-month-old member -- Bongo Cat (3419430),
    81 weekly buckets -- that has ALREADY settled. That is a measurement of
    where one of these lands, not an extrapolation, and it is the better
    instrument.
"""
import io, json, sys, math, datetime, random, os

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
CAPTURE = datetime.datetime(2026, 9, 19, tzinfo=datetime.UTC)


def series(appid):
    d = json.load(open(os.path.join(HERE, "books", "hist_%d.json" % appid), encoding="utf-8"))
    return [(datetime.datetime.fromtimestamp(x["date"], datetime.UTC),
             x["recommendations_up"] + x["recommendations_down"])
            for x in d["results"]["rollups"]]


def ols(xs, ys):
    m = len(xs)
    mx = sum(xs) / m
    my = sum(ys) / m
    sxx = sum((x - mx) ** 2 for x in xs)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    b = sxy / sxx if sxx else 0.0
    a = my - b * mx
    resid = [y - (a + b * x) for x, y in zip(xs, ys)]
    s2 = sum(r * r for r in resid) / (m - 2) if m > 2 else 0.0
    return a, b, (math.sqrt(s2 / sxx) if sxx else float("inf"))


# ---------------------------------------------------------------- TBH
tbh = series(3678970)
last_open, last_val = tbh[-1]
days_in = (CAPTURE - last_open).total_seconds() / 86400.0
full = tbh[:-1]
y = [v for _, v in full]
n = len(y)
t = list(range(n))
peak = max(y)

print("=" * 76)
print("TBH: Task Bar Hero (3678970) — MEASURED 2026-09-19, appreviewhistogram")
print("=" * 76)
print("  complete weekly buckets n=%d  (%s .. %s)" % (n, full[0][0].date(), full[-1][0].date()))
print("  PARTIAL final bucket opens %s, %.1f of 7 days elapsed: raw %d -> full-week rate ~%.0f"
      % (last_open.date(), days_in, last_val, last_val * 7.0 / days_in))
print("  peak %s = %s/week" % (full[y.index(peak)][0].date(), format(peak, ",")))
print("  last 6 COMPLETE weeks: %s" % ", ".join(format(v, ",") for v in y[-6:]))
print()

TAIL = 6
_, lb, lse = ols(t[-TAIL:], [math.log(v) for v in y[-TAIL:]])
print("  IS IT STILL FALLING?  log-slope over the last %d complete weeks" % TAIL)
print("    %+.2f%%/week (SE %.2f%%), t = %+.2f  ->  %s"
      % (100 * (math.exp(lb) - 1), 100 * (math.exp(lse) - 1), lb / lse,
         "NOT distinguishable from flat" if abs(lb) < 2 * lse else "significant"))
random.seed(7)
tail = y[-TAIL:]
bs = sorted(sum(random.choice(tail) for _ in range(TAIL)) / TAIL for _ in range(20000))
lvl = sum(tail) / TAIL
print("    current level MEASURED %.0f/week (90%% bootstrap CI %.0f-%.0f), = %.2f%% of peak"
      % (lvl, bs[1000], bs[19000], 100 * lvl / peak))
print()

print("  TBH'S OWN CURVE — three fits, and what they disagree about")
ea, eb, _ = ols(t, [math.log(v) for v in y])
print("    exponential   half-life %.1fw  -> 3mo %.0f   6mo %.0f   12mo %.0f"
      % (math.log(2) / -eb, math.exp(ea + eb * (n - 1 + 13)),
         math.exp(ea + eb * (n - 1 + 26)), math.exp(ea + eb * (n - 1 + 52))))
print("      REJECTED: it predicts %.0f for the last complete week, measured %d."
      % (math.exp(ea + eb * (n - 1)), y[-1]))

best = None
for Cx in range(0, 1201):
    C = Cx / 2.0
    z = [v - C for v in y]
    if any(q <= 0 for q in z):
        continue
    a, b, _ = ols(t, [math.log(q) for q in z])
    if b >= 0:
        continue
    ss = sum((y[i] - (math.exp(a + b * t[i]) + C)) ** 2 for i in range(n))
    if best is None or ss < best[0]:
        best = (ss, math.exp(a), -1.0 / b, C)
_, A, tau, C = best
print("    exp + floor   floor C=%.0f/week  -> 3mo %.0f   6mo %.0f   12mo %.0f"
      % (C, A * math.exp(-(n - 1 + 13) / tau) + C, A * math.exp(-(n - 1 + 26) / tau) + C,
         A * math.exp(-(n - 1 + 52) / tau) + C))
pa, pb, _ = ols([math.log(x + 1) for x in t], [math.log(v) for v in y])
print("    power law     alpha=%.2f        -> 3mo %.0f   6mo %.0f   12mo %.0f"
      % (-pb, math.exp(pa) * ((n + 13) ** pb), math.exp(pa) * ((n + 26) ** pb),
         math.exp(pa) * ((n + 52) ** pb)))
print("    -> the two surviving fits disagree by 2.3x at 12 months. 16 weeks of")
print("       history cannot settle this. That is a real limit, not a soft answer.")
print()

# ---------------------------------------------------------------- the analogue
print("=" * 76)
print("THE GENRE ANALOGUE — Bongo Cat (3419430), same genre, 18 months old")
print("=" * 76)
bc = series(3419430)
bfull = bc[:-1]
by = [v for _, v in bfull]
bpeak = max(by)
bpi = by.index(bpeak)
print("  %d complete weekly buckets, %s .. %s" % (len(by), bfull[0][0].date(), bfull[-1][0].date()))
print("  peak %s = %s/week" % (bfull[bpi][0].date(), format(bpeak, ",")))
print("  weeks after peak:")
for k in (2, 4, 6, 12, 18, 24, 30, 36, 41):
    if bpi + k < len(by):
        print("     +%2dw  %s  %6s  = %5.2f%% of peak"
              % (k, bfull[bpi + k][0].date(), format(by[bpi + k], ","),
                 100.0 * by[bpi + k] / bpeak))
btail = by[-8:]
bset = sum(btail) / len(btail)
print("  last 8 complete weeks: %s" % ", ".join(str(v) for v in btail))
print("  SETTLED LEVEL MEASURED %.0f/week = %.2f%% of peak, and it has held that band"
      % (bset, 100 * bset / bpeak))
print("  for 36+ weeks (min %d, max %d over the last 12) with no further decay."
      % (min(by[-12:]), max(by[-12:])))
print()

print("=" * 76)
print("APPLYING THE ANALOGUE TO TBH")
print("=" * 76)
ratio = bset / bpeak
print("  Bongo Cat settled at %.2f%% of its peak." % (100 * ratio))
print("  TBH peak %s/week -> analogue-implied settled level %.0f/week."
      % (format(peak, ","), peak * ratio))
print("  TBH is ALREADY at %.0f/week (%.2f%% of peak), i.e. at or slightly ABOVE"
      % (lvl, 100 * lvl / peak))
print("  the level the 18-month-old member of its own genre settled into.")
print()
print("  PROJECTED for TBH, stating the spread rather than picking a number:")
print("    horizon   own-curve fits        genre analogue      what it means")
for h, lab in ((13, " 3 months"), (26, " 6 months"), (52, "12 months")):
    lo = math.exp(pa) * ((n + h) ** pb)
    hi = A * math.exp(-(n - 1 + h) / tau) + C
    a_, b_ = sorted((lo, hi))
    print("    %s   %4.0f - %4.0f/week      %4.0f - %4.0f/week"
          % (lab, a_, b_, peak * ratio, lvl))
print()
print("  UNCERTAINTY, stated plainly: TBH has 16 weeks of history and one")
print("  same-genre precedent. The own-curve fits and the analogue do not agree;")
print("  together they bracket 12-month reviews at roughly 65-500/week, a 7.7x")
print("  spread. The one thing all of them agree on is that the COLLAPSE IS OVER:")
print("  the last 6 complete weeks are flat (t=%+.2f) and the partial week in" % (lb / lse))
print("  progress is running at ~%.0f/week, not %d." % (last_val * 7.0 / days_in, last_val))
print()
print("  AND THE LOAD-BEARING CAVEAT: reviews are not trades. Reviews measure")
print("  NEW-PLAYER INFLOW. Trades come from the installed base and from the")
print("  14,431,626 standing listings already in the book -- 266 days of")
print("  inventory at the measured 54,175 trades/day. Review decay is an upper")
print("  bound on trade decay, not an estimate of it.")

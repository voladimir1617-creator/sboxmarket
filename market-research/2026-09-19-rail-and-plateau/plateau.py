"""What is WoG worth once it settles?

THE INSTRUMENT. Not reviews. The prior pass answered this question with Steam
review histograms and said, correctly, that 16 weeks of them could not narrow
a 12-month projection below a 7.7x bracket. Reviews measure NEW-PLAYER INFLOW;
the thing being projected is GMV. The market listing page turns out to carry
the transactions themselves -- `{"time","price_median","purchases"}` per
bucket, back to each item's first sale -- so the decay curve of GMV is now
MEASURED on three markets rather than inferred from a proxy on one.

WHY THE ANCHOR IS THE PEAK AND NOT A FIXED AGE.
The obvious design is to anchor every market at the same age (say day 7) and
carry the analogue's day7 -> day365 multiple across. That is wrong here, and
the data says why: at day 7 Bongo Cat's whole market was 315 trades/day at a
$9.07 median, because its item catalogue did not exist yet. WoG at day 7 was
already 70,543 trades/day across 914 items. A day7->day365 multiple taken off
Bongo Cat measures its CATALOGUE FILLING UP at least as much as it measures
decay, and applying it to a market that arrived fully stocked would import the
wrong mechanism entirely.

The peak is the anchor the genre actually shares. Every one of these markets
climbs to a top and falls away from it, and "3-6% of peak" is the shape the
prior pass already found in the review series. So:

    WoG_GMV(peak + k days)  =  WoG_PEAK  x  [ analogue_GMV(peak+k) / analogue_PEAK ]

and the peak is taken over a window, never a single day, so one spike cannot
define it.

WHAT IS STILL PROJECTED, STATED PLAINLY. WoG has not peaked -- it is 9 days
old and rising +17.5%/day (t=+5.11). So its peak is UNKNOWN and everything
below is expressed against a LOWER BOUND on it: the level it has already
reached. Every plateau figure here is therefore a floor with a ratio applied,
not a forecast, and the ratio's own spread across three analogues is reported
rather than averaged away.

THE CAVEAT THAT WEAKENS THE 12-MONTH END, AND IT IS VISIBLE IN THE CATALOGUES.
The prior pass filed all six candidates as one genre: "desktop/idle toys
monetised through the Steam Market". Their ITEM ECONOMIES are not one genre:

  WoG        Legendary Gale Ring (Tier 6) ... Great Sword (Tier 3)
  TBH        Eclipse Amulet (Cosmic) A ... Abyss Bracer (Legendary) C
  Bomb Farm  Glacier Leggings Lv 60 (Legendary) ... Clay Ring Lv 40 (Rare)
  Bongo Cat  Trumpet, Mouse Burrow, Sun Glow ... Acorn, Alien Antenna

Three are TIERED LOOT with a rarity ladder. One -- Bongo Cat -- is COSMETICS,
and it is the only member old enough to supply a 12-month answer. The single
analogue carrying the far half of the projection is the one whose item economy
does not match WoG's. That is a model risk no confidence interval covers, and
it is why the far horizon is given as a range whose width is stated rather
than as a number.

UNCERTAINTY THAT IS COVERED: a bootstrap over ITEMS gives the sampling CI on
each level. UNCERTAINTY THAT IS NOT: borrowing one market's shape for another.
"""
import datetime
import io
import json
import math
import os
import random
import sys

# Idempotent: re-wrapping an already-UTF-8 stdout creates a second
# TextIOWrapper over the same buffer, and the first one closes that buffer
# when it is collected -- which turns an import into a dead stdout.
if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from curve import (CANDS, load, daily, vwmedian, TODAY, D, DROPPED,
                   EXCLUDE_BEFORE)   # noqa: E402

# Bongo Cat cannot supply a peak or a peak-relative multiple: 94.4% of its
# sampled units sit in the pre-2026 regime curve.py excludes, so the highest
# level visible in what remains is not its peak, it is the top of a window
# that starts 10 months after launch. Using it as a peak would make the
# decay look far shallower than it was. It is kept as a LEVEL at 18 months,
# which is the one thing the clean part of its data does say.
LEVEL_ONLY = {3419430}

HERE = os.path.dirname(os.path.abspath(__file__))
PEAKWIN = 3          # +/- days averaged when locating the peak
MINWIN = 3           # floor on every measurement window


def series(appid, total):
    rows, never, unread = load(appid)
    if not rows:
        return None
    scale = total / float(len(rows) + never)
    dd = daily(rows)
    first = min(dd)
    out = {}
    for d, (g, n, pairs) in dd.items():
        if d >= TODAY:          # the current day is a PARTIAL bucket
            continue
        out[(d - first).days + 1] = (g * scale, n * scale, pairs)
    return {"first": first, "scale": scale, "d": out, "rows": rows,
            "never": never, "unread": unread, "total": total,
            "maxage": max(out) if out else 0}


def win_for(age):
    """+/- 20% of the age, floored at MINWIN.

    A fixed narrow window is right at day 7 and far too narrow at day 365:
    these samples are 22-36 items, so one far-horizon day can read 59 trades
    and a $96 "median" purely because the sampled items happened not to trade
    that day. A plateau is a LEVEL over a period, not a reading on a date.
    """
    return max(MINWIN, int(0.2 * age))


def at(s, age, win=None):
    """(GMV/day, trades/day, vw-median trade, n days) averaged over the window,
    or None when the series does not reach that age. None, never zero: a
    market we have not watched long enough is not a market that died."""
    if win is None:
        win = win_for(age)
    if age > s["maxage"]:
        return None
    ks = [k for k in s["d"] if abs(k - age) <= win]
    if not ks:
        return None
    g = sum(s["d"][k][0] for k in ks) / len(ks)
    n = sum(s["d"][k][1] for k in ks) / len(ks)
    pairs = []
    for k in ks:
        pairs += s["d"][k][2]
    return g, n, vwmedian(pairs), len(ks)


def peak(s):
    """The highest WINDOWED GMV/day and the day at its centre."""
    best = None
    for k in sorted(s["d"]):
        v = at(s, k, PEAKWIN)
        if v and (best is None or v[0] > best[1][0]):
            best = (k, v)
    return best


def boot(s, age, win=None, reps=2000, seed=13):
    if win is None:
        win = win_for(age)
    lo = s["first"] + datetime.timedelta(days=age - win - 1)
    hi = s["first"] + datetime.timedelta(days=age + win - 1)
    per = []
    for r in s["rows"]:
        tot = 0.0
        for t, p, q in r["pts"]:
            dt = datetime.datetime.fromtimestamp(t, datetime.UTC).date()
            if lo <= dt <= hi and dt < TODAY:
                tot += p * q
        per.append(tot)
    per += [0.0] * s["never"]
    nd = len([k for k in s["d"] if abs(k - age) <= win])
    if not nd:
        return None
    scale = s["total"] / float(len(per))
    rnd = random.Random(seed)
    outs = sorted(sum(per[rnd.randrange(len(per))] for _ in range(len(per)))
                  * scale / nd for _ in range(reps))
    return outs[int(.05 * reps)], outs[int(.95 * reps)]


print("MEASURED %s UTC. Partial (current) day excluded everywhere."
      % datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%d %H:%M"))
print()

S = {}
for ap, lab, tot, db in CANDS:
    s = series(ap, tot)
    if s:
        s["label"], s["debias"], s["appid"] = lab, db, ap
        S[ap] = s

# ---------------------------------------------------------------- 1. peaks
print("=" * 78)
print("1. THE PEAK EACH MARKET REACHED, and how far it has fallen since")
print("=" * 78)
PK = {}
for ap in (4891320, 4892010, 3678970, 3419430):
    s = S.get(ap)
    if not s:
        continue
    if ap in LEVEL_ONLY:
        v = at(s, s["maxage"], win_for(min(s["maxage"], 70)))
        print("  %-22s sample %d read + %d never-sold of %d, unread %s"
              % (s["label"], len(s["rows"]), s["never"], s["total"],
                 s["unread"] or "none"))
        dp, du, cutd = DROPPED.get(ap, (0, 0, None))
        print("     LEVEL ONLY — %s buckets / %s units before %s excluded as an"
              % (format(dp, ","), format(du, ","), cutd))
        print("     unexplained regime (curve.py). No peak is claimed for it.")
        print("     age today          %4d days since launch (2025-03-05)"
              % ((TODAY - datetime.date(2025, 3, 5)).days))
        if v:
            print("     SETTLED GMV/day    %14s  = %s%s/yr"
                  % (D + format(round(v[0], 2), ","), D,
                     format(int(v[0] * 365), ",")))
            print("     settled trades/day %14s   vw-median trade %s"
                  % (format(int(v[1]), ","), D + "%.2f" % (v[2] or 0)))
        print()
        continue
    pk = peak(s)
    if not pk:
        continue
    day, (pg, pn, pm, _) = pk
    PK[ap] = (day, pg, pn, pm)
    cur = at(s, s["maxage"], win_for(min(s["maxage"], 70)))
    print("  %-22s sample %d read + %d never-sold of %d, unread %s"
          % (s["label"], len(s["rows"]), s["never"], s["total"],
             s["unread"] or "none"))
    print("     age today          %4d days" % s["maxage"])
    print("     PEAK GMV/day       %14s  on day %d (%s)"
          % (D + format(round(pg, 2), ","), day,
             (s["first"] + datetime.timedelta(days=day - 1)).isoformat()))
    print("     peak trades/day    %14s   peak vw-median trade %s"
          % (format(int(pn), ","), D + "%.2f" % (pm or 0)))
    if cur:
        print("     NOW  GMV/day       %14s  = %.1f%% of peak"
              % (D + format(round(cur[0], 2), ","), 100 * cur[0] / pg))
        print("     NOW  trades/day    %14s  = %.0f%% of peak   vw-median %s (%.0f%% of peak)"
              % (format(int(cur[1]), ","), 100 * cur[1] / pn if pn else float("nan"),
                 D + "%.2f" % (cur[2] or 0),
                 100 * (cur[2] or 0) / pm if pm else float("nan")))
    print()

# ------------------------------------------------- 2. decay from the peak
print("=" * 78)
print("2. THE DECAY CURVE FROM THE PEAK — measured, one row per analogue")
print("=" * 78)
OFFS = [0, 10, 20, 30, 60, 90, 120, 180, 270, 365, 450]
RATIO = {}
for ap in (4892010, 3678970):
    s = S.get(ap)
    if not s or ap not in PK:
        continue
    pday, pg, pn, pm = PK[ap]
    print("  %s — peak day %d" % (s["label"], pday))
    print("     %8s %14s %11s %12s %10s"
          % ("peak+d", "GMV/day", "% of peak", "trades/day", "vw-median"))
    rr = {}
    for k in OFFS:
        v = at(s, pday + k)
        if v is None:
            continue
        rr[k] = (v[0] / pg, v[1] / pn if pn else float("nan"),
                 (v[2] / pm) if (pm and v[2]) else float("nan"))
        print("     %8d %14s %10.1f%% %12s %10s"
              % (k, D + format(round(v[0], 2), ","), 100 * v[0] / pg,
                 format(int(v[1]), ","), D + "%.2f" % (v[2] or 0)))
    RATIO[ap] = rr
    print()

# ------------------------------------------------------- 3. WoG's position
print("=" * 78)
print("3. WHERE WoG IS ON THAT CURVE — and what is NOT known")
print("=" * 78)
w = S.get(4891320)
if not w:
    print("  WoG not captured")
    sys.exit(0)
wlast = w["maxage"]
wv = at(w, wlast, MINWIN)
wci = boot(w, wlast, MINWIN)
print("  WoG is %d days old and has NOT peaked." % wlast)
ys = [w["d"][k][0] for k in sorted(w["d"]) if w["d"][k][0] > 0]
xs = list(range(len(ys)))
mx = sum(xs) / len(xs)
my = sum(math.log(v) for v in ys) / len(ys)
sxx = sum((x - mx) ** 2 for x in xs)
b = sum((x - mx) * (math.log(v) - my) for x, v in zip(xs, ys)) / sxx
res = [math.log(v) - (my + b * (x - mx)) for x, v in zip(xs, ys)]
se = math.sqrt(sum(e * e for e in res) / (len(ys) - 2) / sxx)
print("     GMV/day log-slope over its %d complete days: %+.1f%%/day (SE %.1f%%),"
      % (len(ys), 100 * (math.exp(b) - 1), 100 * (math.exp(se) - 1)))
print("     t=%+.2f -> still climbing." % (b / se))
print("     LAST %d-day mean GMV/day %s   90%% CI %s - %s"
      % (2 * MINWIN + 1, D + format(round(wv[0], 2), ","),
         D + format(round(wci[0], 2), ",") if wci else "?",
         D + format(round(wci[1], 2), ",") if wci else "?"))
print("     -> GMV/yr at that rate %s%s   DE-BIASED by the measured %.2fx sample"
      % (D, format(int(wv[0] * 365), ","), w["debias"]))
print("        price skew: %s%s" % (D, format(int(wv[0] * 365 / w["debias"]), ",")))
print("     vw-median trade %s   trades/day %s"
      % (D + "%.2f" % (wv[2] or 0), format(int(wv[1]), ",")))
print()
print("  PEAK TIMING in the genre, measured: %s"
      % ", ".join("%s day %d" % (S[a]["label"].split(":")[0], PK[a][0])
                  for a in (4892010, 3678970) if a in PK))
print("  WoG's own peak is therefore ahead of it, and its size is UNKNOWN.")
print("  Everything below applies the genre's peak-relative decay to WoG's")
print("  CURRENT level, which is a LOWER BOUND on its peak. Read every figure")
print("  as a floor, not a forecast.")
print()

# ------------------------------------------------------------ 4. plateau
print("=" * 78)
print("4. WoG AT THE PLATEAU — floor estimates and the width of the bracket")
print("=" * 78)
db = w["debias"] or 1.0
print("  applied to WoG's current GMV/day of %s (= %s%s/yr, de-biased %s%s/yr)"
      % (D + format(round(wv[0], 2), ","), D, format(int(wv[0] * 365), ","),
         D, format(int(wv[0] * 365 / db), ",")))
print()
print("  %10s %26s %16s %14s"
      % ("peak+days", "analogue", "GMV/yr floor", "median trade"))
for k in (30, 60, 90, 180, 365):
    rows = []
    for ap in (4892010, 3678970):
        rr = RATIO.get(ap, {})
        if k in rr:
            rows.append((S[ap]["label"], rr[k]))
    if not rows:
        continue
    for lab, (rg, rt, rm) in rows:
        print("  %10d %26s %16s %14s"
              % (k, lab, D + format(int(wv[0] * rg * 365), ","),
                 D + "%.3f" % ((wv[2] or 0) * rm) if rm == rm else "  n/a"))
    gs = sorted(wv[0] * r[1][0] * 365 for r in rows)
    ms = sorted((wv[2] or 0) * r[1][2] for r in rows if r[1][2] == r[1][2])
    print("  %10s %26s %16s %14s"
          % ("", "RANGE (spread %.1fx)" % (gs[-1] / gs[0] if gs[0] else float("inf")),
             "%s%s - %s%s" % (D, format(int(gs[0]), ","), D, format(int(gs[-1]), ",")),
             ("%s%.3f-%s%.3f" % (D, ms[0], D, ms[-1])) if ms else "n/a"))
    print("  %10s %26s %16s"
          % ("", "  de-biased", "%s%s - %s%s"
             % (D, format(int(gs[0] / db), ","), D, format(int(gs[-1] / db), ","))))
    print()

# ---- the 12-month question, and the honest shape of its answer ----------
print("=" * 78)
print("4b. TWELVE MONTHS — two anchors, and they are 30x apart")
print("=" * 78)
print("  NOTHING in this genre has been measured past peak+83 days. TBH, the")
print("  oldest market whose series is usable, is 115 days old. So a 12-month")
print("  figure cannot be measured and is not pretended to be. Two independent")
print("  anchors bound it, and the distance between them IS the answer.")
print()

# (i) extrapolate TBH's own post-peak decay, with the fit's own error
t = S.get(3678970)
proj = None
if t and 3678970 in PK:
    pday = PK[3678970][0]
    ks = [k for k in sorted(t["d"]) if k >= pday and t["d"][k][0] > 0]
    ys = [math.log(t["d"][k][0]) for k in ks]
    xs = [float(k) for k in ks]
    n_ = len(xs)
    mx = sum(xs) / n_
    my = sum(ys) / n_
    sxx = sum((x - mx) ** 2 for x in xs)
    bb = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    res = [y - (my + bb * (x - mx)) for x, y in zip(xs, ys)]
    sse = math.sqrt(sum(e * e for e in res) / (n_ - 2) / sxx)
    PROJ_BAND = []
    print("  (i) PROJECTED — TBH's measured post-peak decay, extended.")
    print("      log-slope over its %d post-peak days: %+.2f%%/day (SE %.2f%%), t=%+.1f"
          % (n_, 100 * (math.exp(bb) - 1), 100 * (math.exp(sse) - 1), bb / sse))
    print("      half-life %.0f days. Carried from WoG's current level to day 365:"
          % (math.log(2) / -bb if bb < 0 else float("inf")))
    for lab_, slope in (("central", bb), ("slow end (+1.64 SE)", bb + 1.64 * sse),
                        ("fast end (-1.64 SE)", bb - 1.64 * sse)):
        f = math.exp(slope * (365 - wlast))
        proj = proj or f
        PROJ_BAND.append(wv[0] * f * 365)
        print("        %-22s x%.5f of today  ->  %s%s/yr  (de-biased %s%s)"
              % (lab_, f, D, format(int(wv[0] * f * 365), ","),
                 D, format(int(wv[0] * f * 365 / db), ",")))
    print("      This extends a 83-day fit by 250 days. It is the weakest number")
    print("      on this page and the SE band does not cover that.")
print()

# (ii) the only measured 18-month economy in the genre
bc = S.get(3419430)
if bc:
    v = at(bc, bc["maxage"], win_for(min(bc["maxage"], 70)))
    lastc = max(bc["d"])
    vlast = bc["d"][lastc]
    print("  (ii) MEASURED — Bongo Cat, 563 days after launch, post-2026 era only.")
    print("       trailing GMV/day %s  = %s%s/yr"
          % (D + format(round(v[0], 2), ","), D, format(int(v[0] * 365), ",")))
    print("       last complete day %s = %s%s/yr"
          % (D + format(round(vlast[0], 2), ","), D,
             format(int(vlast[0] * 365), ",")))
    print("       vw-median trade %s   trades/day %s"
          % (D + "%.2f" % (v[2] or 0), format(int(v[1]), ",")))
    print("       This is an ABSOLUTE endpoint, not a multiple: it says what one")
    print("       settled member of this genre is worth in total, whatever it")
    print("       peaked at. It is also the COSMETIC one, so it is the weaker")
    print("       structural match for WoG and the stronger measurement.")
print()
print("  THE BRACKET, stated with its width rather than averaged:")
if t and bc and PROJ_BAND:
    # the bracket spans BOTH anchors AND the projection's own error band.
    # Taking only the two central numbers would report a 2x spread for a
    # quantity whose single best fit already carries a 17x band at 90%, which
    # is a point estimate wearing a range's clothes.
    bc_hi = at(bc, bc["maxage"], win_for(70))[0] * 365
    bc_lo = bc["d"][max(bc["d"])][0] * 365
    cands = PROJ_BAND + [bc_hi, bc_lo]
    lo_, hi_ = min(cands), max(cands)
    print("     TBH-decay projection, 90%% band  %s%s - %s%s /yr"
          % (D, format(int(min(PROJ_BAND)), ","), D,
             format(int(max(PROJ_BAND)), ",")))
    print("     Bongo Cat measured endpoint      %s%s - %s%s /yr"
          % (D, format(int(bc_lo), ","), D, format(int(bc_hi), ",")))
    print("     ----------------------------------------------------------")
    print("     WoG whole-market GMV at 12 months: %s%s - %s%s per year"
          % (D, format(int(lo_), ","), D, format(int(hi_), ",")))
    print("     de-biased:                          %s%s - %s%s per year"
          % (D, format(int(lo_ / db), ","), D, format(int(hi_ / db), ",")))
    print("     WIDTH %.0fx. The prior pass got a 7.7x bracket from 16 weeks of"
          % (hi_ / lo_ if lo_ else float("inf")))
    print("     reviews and said so; this is wider on better data, because the")
    print("     data now covers the right quantity and shows how little of the")
    print("     path anyone has actually watched.")
    print("     At a 2%% take on 100%% of that market: %s%s - %s%s a MONTH."
          % (D, format(int(lo_ * 0.02 / 12), ","),
             D, format(int(hi_ * 0.02 / 12), ",")))
    print("     At 15%% on 100%%:                      %s%s - %s%s a MONTH."
          % (D, format(int(lo_ * 0.15 / 12), ","),
             D, format(int(hi_ * 0.15 / 12), ",")))
    print("     Those are WHOLE-MARKET figures. A new venue with no incumbent")
    print("     still has to take share off Steam itself, which charges 15%% and")
    print("     is where the items already are.")
print()

# --------------------------------------------- 5. the term that decides (b)
print("=" * 78)
print("5. THE MEDIAN TRADE — the term the fee model rounds against")
print("=" * 78)
print("  WoG's own %d days, no analogue involved:" % wlast)
for k in sorted(w["d"]):
    g, n, pairs = w["d"][k]
    print("     day %2d  GMV/day %11s  trades %9s  vw-median trade %s"
          % (k, D + format(round(g, 2), ","), format(int(n), ","),
             D + "%.2f" % (vwmedian(pairs) or 0)))
meds = [(k, vwmedian(w["d"][k][2])) for k in sorted(w["d"]) if vwmedian(w["d"][k][2])]
if len(meds) >= 4:
    xs = [k for k, _ in meds]
    ys = [math.log(v) for _, v in meds]
    n_ = len(xs)
    mx = sum(xs) / n_
    my = sum(ys) / n_
    sxx = sum((x - mx) ** 2 for x in xs)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    res = [y - (my + b * (x - mx)) for x, y in zip(xs, ys)]
    se = math.sqrt(sum(e * e for e in res) / (n_ - 2) / sxx)
    print()
    print("     log-slope %+.1f%%/day (SE %.1f%%), t=%+.1f; half-life %.1f days"
          % (100 * (math.exp(b) - 1), 100 * (math.exp(se) - 1), b / se,
             math.log(2) / -b if b < 0 else float("inf")))
    if b < 0:
        for target in (0.25, 0.10, 0.07):
            d_ = (math.log(target) - my) / b + mx
            print("     reaches %s%.2f on about day %.0f (%s)"
                  % (D, target, d_,
                     (w["first"] + datetime.timedelta(days=int(d_))).isoformat()))
        print("     %s0.25 is the price below which a 2%% fee rounds HALF_UP to %s0.00,"
              % (D, D))
        print("     so that first line is the date the shipped fee model stops")
        print("     collecting anything on the median trade.")
print()
print("  And where the analogues' medians ended up, MEASURED:")
for ap in (4892010, 3678970, 3419430):
    s = S.get(ap)
    if not s:
        continue
    v = at(s, s["maxage"], win_for(min(s["maxage"], 70)))
    if not v:
        continue
    if ap in PK:
        print("     %-22s age %4d days   vw-median trade %s   (at its peak %s)"
              % (s["label"], s["maxage"], D + "%.2f" % (v[2] or 0),
                 D + "%.2f" % (PK[ap][3] or 0)))
    else:
        print("     %-22s %4d days since launch   vw-median trade %s   (no peak"
              % (s["label"], (TODAY - datetime.date(2025, 3, 5)).days,
                 D + "%.2f" % (v[2] or 0)))
        print("     %-22s  claimed: see LEVEL ONLY above)" % "")

json.dump({"peaks": {str(k): v for k, v in PK.items()},
           "ratios": {str(k): {str(kk): vv for kk, vv in r.items()}
                      for k, r in RATIO.items()},
           "wog_now_gmv_day": wv[0], "wog_now_ci90": wci,
           "wog_now_trades_day": wv[1], "wog_now_vwmed": wv[2],
           "wog_age_days": wlast},
          open(os.path.join(HERE, "books", "plateau.json"), "w"), indent=1)
print()
print("plateau.json written")

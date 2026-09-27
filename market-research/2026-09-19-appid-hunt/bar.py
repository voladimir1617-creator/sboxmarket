"""THE BAR, applied to every candidate, with the payment rail inside it.

The prior pass put TBH at "$7,510/mo at a 2% take, clears across the entire
CI". That figure is GROSS OF THE RAIL. Stripe's deposit leg is 2.9% + $0.30
and the payout leg 0.25% + $0.25, so the rail costs ~3.15% of every deposited
dollar while the shipped 2% take collects an EFFECTIVE 1.78% of GMV after
HALF_UP rounding wipes out every trade under $0.25. Below the rail, more
volume loses more money.

The rail is charged per DEPOSIT, not per trade, so the honest statement is a
velocity condition: the take must beat 3.15%/velocity, where velocity is
GMV / dollars deposited. Velocity is NOT MEASURED -- the market does not exist
yet -- so it is carried as a variable rather than assumed away.
"""
import io, json, os, re, statistics, sys
from decimal import Decimal, ROUND_HALF_UP

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
B = os.path.join(HERE, "books")
RAIL = 0.029 + 0.0025
COST_MO = 500.0


def num(s):
    if not s:
        return None
    s = re.sub(r"[^0-9.,]", "", s).replace(",", "")
    try:
        return float(s)
    except Exception:
        return None


def eff_rate(sold, rate):
    q = Decimal(str(rate))
    g = sum(p * v for p, v, _ in sold)
    t = sum(float((Decimal(str(p)) * q).quantize(Decimal("0.01"), ROUND_HALF_UP)) * v
            for p, v, _ in sold)
    return t / g if g else 0.0


def load(appid, bookfile, volfile, total):
    book = {}
    for line in open(os.path.join(B, bookfile), encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort") or r.get("p") is None:
            continue
        book[r["h"]] = r
    sold, silent = [], 0
    for line in open(os.path.join(B, volfile), encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            continue
        if r.get("vol") is None:
            silent += 1
            continue
        sold.append((num(r.get("med")) or r["p"] / 100.0, r["vol"], r["h"]))
    n = len(sold) + silent
    scale = total / float(n)
    bp = [r["p"] / 100.0 for r in book.values()]
    sp = [r["p"] / 100.0 for r in book.values()]  # placeholder, replaced below
    return book, sold, silent, scale, bp


def report(appid, label, bookfile, volfile, total, incumbent):
    book, sold, silent, scale, bp = load(appid, bookfile, volfile, total)
    gmv_yr = sum(p * v for p, v, _ in sold) * scale * 365
    tday = sum(v for _, v, _ in sold) * scale
    tv = sum(v for _, v, _ in sold)
    s = sorted(sold)
    c = 0
    vwm = 0.0
    for p, v, _ in s:
        c += v
        if c >= tv / 2:
            vwm = p
            break
    print("=" * 74)
    print("%s  (appid %d)" % (label, appid))
    print("=" * 74)
    print("  trades/day        %14s   MEASURED (n=%d sampled of %d items)"
          % (format(int(tday), ","), len(sold) + silent, total))
    print("  MEDIAN item price %14s   MEASURED (n=%d distinct book rows)"
          % ("$%.2f" % statistics.median(bp), len(bp)))
    print("  median TRADE      %14s   MEASURED, volume-weighted" % ("$%.2f" % vwm))
    print("  book value        %14s   MEASURED, LOWER BOUND"
          % ("$%s" % format(int(sum((r["p"] / 100.0) * (r["n"] or 0)
                                    for r in book.values())), ",")))
    print("  incumbent         %s" % incumbent)
    print("  GMV/yr            %14s   MEASURED" % ("$%s" % format(int(gmv_yr), ",")))
    print()
    print("  take   effective   gross/yr      needs velocity   net/mo @ velocity 1x / 2x / 3x")
    for rate in (0.02, 0.05, 0.10, 0.15):
        e = eff_rate(sold, rate)
        gross = gmv_yr * e
        need = RAIL / e if e else float("inf")
        nets = [(gross - (RAIL / vel) * gmv_yr) / 12.0 for vel in (1, 2, 3)]
        print("  %4.0f%%   %7.3f%%   $%10s      > %5.2fx        $%+8s / $%+8s / $%+8s"
              % (rate * 100, 100 * e, format(int(gross), ","), need,
                 format(int(nets[0]), ","), format(int(nets[1]), ","),
                 format(int(nets[2]), ",")))
    print()
    print("  share of the whole market needed to clear $%.0f/mo:" % COST_MO)
    for rate in (0.02, 0.05, 0.10):
        e = eff_rate(sold, rate)
        for vel in (1, 2):
            per = e - RAIL / vel
            if per <= 0:
                print("    %4.0f%% @ velocity %dx  IMPOSSIBLE at any share (net take negative)"
                      % (rate * 100, vel))
            else:
                print("    %4.0f%% @ velocity %dx  %6.2f%% of the market"
                      % (rate * 100, vel, 100 * (COST_MO * 12) / (per * gmv_yr)))
    print()


if __name__ == "__main__":
    report(3678970, "TBH: Task Bar Hero", "rows_3678970_pd.jsonl", "vol_3678970.jsonl",
           1064, "none found (trackers only: tbhindex.com, tbh.city)")
    report(4891320, "WoG: War of Genesis: Idle Loot", "cand_rows_4891320.jsonl",
           "cand_vol_4891320.jsonl", 914, "none found")
    report(3419430, "Bongo Cat  [the SETTLED genre analogue, 18 months old]",
           "cand_rows_3419430.jsonl", "cand_vol_3419430.jsonl", 540, "none found")
    for ap, lab, tot in ((4892010, "Bomb Farm", 270), (4952980, "My Party Is Grinding", None),
                         (5033210, "Desk Top Racer", None)):
        bf, vf = "cand_rows_%d.jsonl" % ap, "cand_vol_%d.jsonl" % ap
        if tot and os.path.exists(os.path.join(B, vf)):
            try:
                report(ap, lab, bf, vf, tot, "none found")
            except Exception as e:
                print("%s: not enough captured yet (%s)" % (lab, e))

"""Scrutinise the WoG turnover estimate before believing it.

A 9-day-old game measuring 95,287 trades/day -- more than TBH, which had 10x
its launch-week review count -- is an outlier, and an outlier is a bug until
proven otherwise. Three things get checked:
  1. is the volume sample price-representative of the book it scales to?
  2. is the total driven by one or two rows?
  3. what is the bootstrap CI, and does the conclusion survive its low end?
"""
import io, json, os, random, re, statistics, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
B = os.path.join(HERE, "books")


def num(s):
    if not s:
        return None
    s = re.sub(r"[^0-9.,]", "", s).replace(",", "")
    try:
        return float(s)
    except Exception:
        return None


def check(appid, label, total_count):
    book = {}
    for line in open(os.path.join(B, "cand_rows_%d.jsonl" % appid), encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort") or r.get("p") is None:
            continue
        book[r["h"]] = r
    sample, unread = [], 0
    for line in open(os.path.join(B, "cand_vol_%d.jsonl" % appid), encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            unread += 1
            continue
        sample.append(r)

    bp = sorted(r["p"] / 100.0 for r in book.values())
    sp = sorted(r["p"] / 100.0 for r in sample)
    print("=== %s (%d) ===" % (label, appid))
    print("  book distinct %d of total_count %s ; volume sample %d rows, %d unread"
          % (len(book), total_count, len(sample), unread))
    print("  PRICE REPRESENTATIVENESS (this is what the scale factor assumes)")
    print("    book   median $%.2f  mean $%.2f  p90 $%.2f"
          % (statistics.median(bp), sum(bp) / len(bp), bp[int(.9 * len(bp))]))
    print("    sample median $%.2f  mean $%.2f  p90 $%.2f"
          % (statistics.median(sp), sum(sp) / len(sp), sp[int(.9 * len(sp))]))
    bias = (sum(sp) / len(sp)) / (sum(bp) / len(bp))
    print("    sample mean price / book mean price = %.2fx" % bias)
    print("    -> the sample is priced %.0f%% %s than the book it is scaled to."
          % (abs(bias - 1) * 100, "HIGHER" if bias > 1 else "LOWER"))
    print("       GMV scaled from it inherits that bias in the SAME direction, so the")
    print("       point estimate should be read as $%s/yr once divided out."
          % "…")
    print("       (No pass/fail threshold is applied here: picking a cutoff that the")
    print("        measured bias happens to sit under is calibrating the guard to the")
    print("        incident. The bias is reported as a number and carried forward.)")
    print()

    sold = [(num(r.get("med")) or r["p"] / 100.0, r["vol"], r["h"])
            for r in sample if r.get("vol") is not None]
    silent = sum(1 for r in sample if r.get("vol") is None)
    sold.sort(key=lambda t: -t[0] * t[1])
    gmv_s = sum(p * v for p, v, _ in sold)
    vol_s = sum(v for _, v, _ in sold)
    print("  TOP SAMPLED ROWS BY GMV/day (is it one row?)")
    cum = 0.0
    for p, v, h in sold[:6]:
        cum += p * v
        print("    %-38s %7s trades/day @ $%8.2f = $%9.2f  cum %5.1f%%"
              % (h[:38], format(v, ","), p, p * v, 100 * cum / gmv_s))
    print("    top1 %.1f%%  top3 %.1f%%  top10 %.1f%% of sampled GMV"
          % (100 * sold[0][0] * sold[0][1] / gmv_s,
             100 * sum(p * v for p, v, _ in sold[:3]) / gmv_s,
             100 * sum(p * v for p, v, _ in sold[:10]) / gmv_s))
    print()

    n = len(sold) + silent
    scale = total_count / float(n)
    pool = sold + [(0.0, 0, "silent")] * silent
    random.seed(11)
    bs_g, bs_t = [], []
    for _ in range(4000):
        s = [pool[random.randrange(len(pool))] for _ in range(len(pool))]
        bs_g.append(sum(p * v for p, v, _ in s) * scale * 365)
        bs_t.append(sum(v for _, v, _ in s) * scale)
    bs_g.sort()
    bs_t.sort()
    print("  SCALED, n=%d sampled of %s items (x%.1f), %d silent" % (n, total_count, scale, silent))
    print("    trades/day  %12s   90%% CI %s - %s"
          % (format(int(vol_s * scale), ","), format(int(bs_t[200]), ","),
             format(int(bs_t[3800]), ",")))
    print("    GMV/yr      $%11s   90%% CI $%s - $%s"
          % (format(int(gmv_s * scale * 365), ","), format(int(bs_g[200]), ","),
             format(int(bs_g[3800]), ",")))
    vw = sorted(sold)
    c = 0
    vwm = 0.0
    for p, v, _ in vw:
        c += v
        if c >= vol_s / 2:
            vwm = p
            break
    print("    vol-weighted MEDIAN trade $%.2f   mean trade $%.4f" % (vwm, gmv_s / vol_s))
    print("    GMV/yr DE-BIASED by the %.2fx price skew above: $%s"
          % (bias, format(int(gmv_s * scale * 365 / bias), ",")))
    print()
    return bs_g[200], gmv_s * scale * 365, bs_g[3800]


# total_count measured from the book walk; hard-coded where cand_meta.json has
# not been written yet because that candidate's turnover phase is still running
KNOWN_TOTAL = {4891320: 914, 3419430: 540, 4892010: None, 4952980: None, 5033210: None}

if __name__ == "__main__":
    meta = dict(KNOWN_TOTAL)
    p = os.path.join(B, "cand_meta.json")
    if os.path.exists(p):
        for m in json.load(open(p, encoding="utf-8")):
            if m.get("total_count"):
                meta[m["appid"]] = m["total_count"]
    for ap, lab in ((4891320, "WoG: War of Genesis"), (3419430, "Bongo Cat"),
                    (4892010, "Bomb Farm"), (4952980, "My Party Is Grinding"),
                    (5033210, "Desk Top Racer")):
        if os.path.exists(os.path.join(B, "cand_vol_%d.jsonl" % ap)) and meta.get(ap):
            check(ap, lab, meta[ap])

"""Days of inventory = standing listings / trades per day.

This is the check that separates a liquid book from a graveyard, and it cuts
against the reading I first gave TBH. 14.4M standing listings sounds like a
reserve that will keep trading after the players leave. It is the opposite: an
unsold book is a population of items ALREADY REJECTED at that price. A book
that takes 266 days to clear is not inventory, it is a queue nobody is in.

Per-item, not just in aggregate, because an aggregate hides the split between
a handful of liquid commodities and a long tail that never moves.
"""
import io, json, os, re, statistics, sys

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


def run(appid, label, bookfile, volfile, total):
    book = {}
    for line in open(os.path.join(B, bookfile), encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort") or r.get("p") is None:
            continue
        book[r["h"]] = r
    sold, silent = [], []
    for line in open(os.path.join(B, volfile), encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            continue
        (silent if r.get("vol") is None else sold).append(r)

    listings = sum(r["n"] or 0 for r in book.values())
    n = len(sold) + len(silent)
    scale = total / float(n)
    tday = sum(r["vol"] for r in sold) * scale

    print("=== %s (%d) ===" % (label, appid))
    print("  standing listings %14s   trades/day %12s"
          % (format(listings, ","), format(int(tday), ",")))
    print("  DAYS OF INVENTORY %14.1f   %s"
          % (listings / tday if tday else float("inf"),
             "LIQUID" if listings / tday < 14 else "SATURATED — an unsold book is a "
             "population already rejected at that price"))
    per = []
    for r in sold:
        if r["vol"]:
            per.append(((r["n"] or 0) / float(r["vol"]), r["h"], r["n"] or 0, r["vol"]))
    per.sort()
    if per:
        d = [x[0] for x in per]
        print("  per-item days-to-clear: median %.2f  p25 %.2f  p75 %.2f  (n=%d selling rows)"
              % (statistics.median(d), d[int(.25 * len(d))], d[int(.75 * len(d))], len(d)))
        print("    fastest: %-34s %6s listings / %6s per day = %.2f d"
              % (per[0][1][:34], format(per[0][2], ","), format(per[0][3], ","), per[0][0]))
        print("    slowest: %-34s %6s listings / %6s per day = %.1f d"
              % (per[-1][1][:34], format(per[-1][2], ","), format(per[-1][3], ","), per[-1][0]))
    print("  items that sold NOTHING in 24h: %d of %d sampled (%.0f%%)"
          % (len(silent), n, 100.0 * len(silent) / n))
    print()


if __name__ == "__main__":
    run(3678970, "TBH: Task Bar Hero", "rows_3678970_pd.jsonl", "vol_3678970.jsonl", 1064)
    run(4891320, "WoG: War of Genesis", "cand_rows_4891320.jsonl",
        "cand_vol_4891320.jsonl", 914)
    run(3419430, "Bongo Cat (settled, 18mo)", "cand_rows_3419430.jsonl",
        "cand_vol_3419430.jsonl", 540)
    run(4892010, "Bomb Farm", "cand_rows_4892010.jsonl", "cand_vol_4892010.jsonl", 270)

"""The four decision terms for every candidate, side by side.

  trades/day | MEDIAN item price | book value | incumbent marketplace

Kill terms already paid for by earlier passes:
  s&box      dies on volume        (171 trades/day, whole book once = $617.57)
  Crab Game  dies on price AND volume
  Unturned   dies on an incumbent  (Unskins.com, median item $0.03)

DIRECTION OF EVERY BIAS IS STATED. Where a book is short of total_count the
missing rows cluster at the CHEAP end (ties reorder between requests), so book
values are LOWER BOUNDS and medians can only FALL.
"""
import io, json, os, re, statistics, sys
from decimal import Decimal, ROUND_HALF_UP

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
B = os.path.join(HERE, "books")
D = chr(36)

INCUMBENT = {
    3678970: "none found (trackers only: tbhindex.com, tbh.city)",
    4891320: "none found",
    4892010: "none found",
    4952980: "none found",
    5033210: "none found",
    3419430: "none found",
    578080:  "YES - dmarket, skinwallet, many",
    252490:  "YES - 7 venues",
    304930:  "YES - Unskins.com",
    1782210: "none found",
    590830:  "none (sboxmarket, ours)",
}


def num(s):
    if not s:
        return None
    s = re.sub(r"[^0-9.,]", "", s).replace(",", "")
    try:
        return float(s)
    except Exception:
        return None


def take(p, rate):
    return float((Decimal(str(p)) * Decimal(str(rate))).quantize(Decimal("0.01"),
                                                                 ROUND_HALF_UP))


def load_book(path):
    rows, unread = {}, 0
    if not os.path.exists(path):
        return None, 0
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            unread += 1
            continue
        if r.get("h") and r.get("p") is not None:
            rows[r["h"]] = r
    return list(rows.values()), unread


def load_vol(path):
    ok, silent, unread = [], 0, 0
    if not path or not os.path.exists(path):
        return None, 0, 0
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort"):
            unread += 1
            continue
        if r.get("vol") is None:
            silent += 1
            continue
        ok.append((num(r.get("med")) or (r["p"] / 100.0), r["vol"], r["h"]))
    return ok, silent, unread


def row(appid, label, bookpath, volpath, total_count):
    v, bunread = load_book(bookpath)
    if not v:
        print("  %-26s NO BOOK CAPTURED" % label)
        return None
    n = len(v)
    listings = sum(r["n"] or 0 for r in v)
    bookval = sum((r["p"] / 100.0) * (r["n"] or 0) for r in v)
    px = sorted(r["p"] / 100.0 for r in v)
    med = statistics.median(px)

    ok, silent, vunread = load_vol(volpath)
    if ok:
        nsamp = len(ok) + silent
        scale = (total_count or n) / float(nsamp)
        tday = sum(x[1] for x in ok) * scale
        gmvday = sum(p * vv for p, vv, _ in ok) * scale
        tv = sum(x[1] for x in ok)
        s = sorted(ok)
        c = 0
        vwmed = 0.0
        for p, vv, _ in s:
            c += vv
            if c >= tv / 2:
                vwmed = p
                break
        eff = (sum(take(p, 0.02) * vv for p, vv, _ in ok)
               / sum(p * vv for p, vv, _ in ok)) if tv else 0
        byg = sorted(((p * vv, h) for p, vv, h in ok), reverse=True)
        tot = sum(x[0] for x in byg)
        top3 = 100 * sum(x[0] for x in byg[:3]) / tot if tot else 0
    else:
        nsamp = scale = tday = gmvday = vwmed = eff = top3 = 0
        silent = vunread = 0

    cov = (100.0 * n / total_count) if total_count else float("nan")
    print("  %-26s appid %-8d" % (label, appid))
    print("     book       %5d of %5s distinct items captured (%.0f%%), %s unread pages"
          % (n, total_count, cov, bunread))
    print("     standing listings   %14s" % format(listings, ","))
    print("     BOOK VALUE          %14s   (LOWER BOUND)" % (D + format(round(bookval, 2), ",")))
    print("     MEDIAN item price   %14s   (can only FALL with fuller coverage)"
          % (D + "%.2f" % med))
    print("     price p10 / p90     %s / %s" % (D + "%.2f" % px[int(.1 * n)],
                                                D + "%.2f" % px[int(.9 * n)]))
    if ok:
        print("     TRADES/DAY          %14s   (n=%d sampled items, %d sold, %d silent, "
              "%d unread; scale x%.1f)"
              % (format(int(round(tday)), ","), nsamp, len(ok), silent, vunread, scale))
        print("     GMV/day / GMV/yr    %s / %s"
              % (D + format(round(gmvday, 0), ","), D + format(round(gmvday * 365, 0), ",")))
        print("     VOL-WTD MEDIAN TRADE %13s   -> 2%% take = %s"
              % (D + "%.2f" % vwmed, D + "%.2f" % take(vwmed, 0.02)))
        print("     effective take @2%%  %13.3f%%" % (100 * eff))
        print("     top-3 concentration %13.1f%% of sampled GMV" % top3)
    else:
        print("     TRADES/DAY          NOT MEASURED (no turnover sample)")
    print("     INCUMBENT           %s" % INCUMBENT.get(appid, "unknown"))
    print()
    return {"appid": appid, "label": label, "n": n, "total": total_count,
            "listings": listings, "book": bookval, "median": med,
            "tday": tday, "gmv_yr": gmvday * 365, "vwmed": vwmed, "top3": top3}


if __name__ == "__main__":
    meta = {}
    p = os.path.join(B, "cand_meta.json")
    if os.path.exists(p):
        for m in json.load(open(p, encoding="utf-8")):
            meta[m["appid"]] = m["total_count"]

    print("=" * 78)
    print("CANDIDATE VERDICT TABLE — all figures MEASURED 2026-09-19 unless marked")
    print("=" * 78)
    print()
    out = []
    out.append(row(3678970, "TBH: Task Bar Hero", os.path.join(B, "rows_3678970_pd.jsonl"),
                   os.path.join(B, "vol_3678970.jsonl"), 1064))
    out.append(row(1782210, "Crab Game", os.path.join(B, "rows_1782210_pd.jsonl"),
                   None, 1026))
    for ap, lab in [(4891320, "WoG: War of Genesis"), (4892010, "Bomb Farm"),
                    (4952980, "My Party Is Grinding"), (5033210, "Desk Top Racer"),
                    (3419430, "Bongo Cat"), (578080, "PUBG: BATTLEGROUNDS")]:
        out.append(row(ap, lab, os.path.join(B, "cand_rows_%d.jsonl" % ap),
                       os.path.join(B, "cand_vol_%d.jsonl" % ap), meta.get(ap)))

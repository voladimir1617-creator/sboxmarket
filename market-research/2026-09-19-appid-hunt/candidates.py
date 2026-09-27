"""Measure the four decision terms for every candidate the head walk surfaced.

The head walk over the top 1,240 items of the whole 529,456-item Steam market
found only 15 appids, and six of them are the same NEW genre -- desktop/idle
toys monetised through the Steam Community Market:

  3678970  TBH: Task Bar Hero          rel. 2026-05-27   (measured by prior pass)
  4891320  [WoG] War of Genesis        rel. 2026-09-10
  4892010  Bomb Farm                   rel. 2026-08-28
  4952980  My Party Is Grinding        rel. 2026-08-27
  5033210  Desk Top Racer              rel. 2026-09-08
  3419430  Bongo Cat                   rel. 2025-03-05

Two of those appids (4892010, 5033210) are NOT in market_apps.json, so the
10,101-app list is already stale and a per-appid census over it could never
have found them. The head walk is the only instrument that can.

For each: book (distinct items, standing listings, book value, MEDIAN price)
and turnover (trades/day, volume-weighted trade price, concentration).

SINGLE-THREADED, one process, shared pace. Same discipline as sweep.py:
a read that fails is recorded as UNREAD and never as zero.
"""
import json, os, sys, time, urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sweep                     # reuse the one rate-disciplined fetch()
from steamlib import Abort

# Ordered by what each one DECIDES, because Steam's budget will run out before
# the list does:
#   WoG        the largest new candidate the head walk found
#   Bongo Cat  the same genre 18 months old -- the only MEASURED answer to
#              "where does one of these settle", which is the TBH question
#   the three August/September launches
# PUBG is dropped: it already has incumbents (dmarket, skinwallet and others),
# so its verdict does not depend on any number we could still measure.
CANDIDATES = [
    (4891320, "WoG: War of Genesis"),
    (3419430, "Bongo Cat"),
    (4892010, "Bomb Farm"),
    (4952980, "My Party Is Grinding"),
    (5033210, "Desk Top Racer"),
]

VOL_SAMPLE = 40                  # priceoverview calls per candidate


def book(appid, label):
    """Walk the whole book. count=100 is REQUESTED but Steam is currently
    serving 10 rows per page regardless (MEASURED 2026-09-19, market-wide and
    per-appid alike), so the stride is taken from the page actually served --
    striding by the REQUESTED size would silently turn a census into a 1-in-10
    sample and report it as complete."""
    path = "cand_rows_%d.jsonl" % appid
    seen = set()
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            try:
                seen.add(json.loads(line)["_start"])
            except Exception:
                pass
    f = open(path, "a", encoding="utf-8")
    total = None
    start = 0
    step = None
    aborts = {}
    while True:
        if start in seen:
            start += (step or 10)
            if total is not None and start >= total:
                break
            continue
        url = ("https://steamcommunity.com/market/search/render/"
               "?appid=%d&norender=1&count=100&start=%d&currency=1" % (appid, start))
        try:
            j = sweep.fetch(url)
        except Abort as e:
            aborts[e.state] = aborts.get(e.state, 0) + 1
            f.write(json.dumps({"_start": start, "abort": e.state}) + "\n")
            f.flush()
            print("  [%s] start=%d UNREAD %s" % (label, start, e.state), flush=True)
            start += (step or 10)
            if total is not None and start >= total:
                break
            time.sleep(10)
            continue
        total = j.get("total_count")
        res = j.get("results") or []
        if step is None and res:
            step = len(res)
            print("  [%s] PAGESIZE SERVED = %d (count=100 requested), total=%s"
                  % (label, step, total), flush=True)
        for r in res:
            f.write(json.dumps({"_start": start, "h": r.get("hash_name"),
                                "p": r.get("sell_price"), "n": r.get("sell_listings")},
                               ensure_ascii=False) + "\n")
        f.flush()
        if (start // max(1, step or 10)) % 20 == 0:
            print("  [%s] start=%d got=%d total=%s" % (label, start, len(res), total),
                  flush=True)
        start += (step or 10)
        if not res or (total is not None and start >= total):
            break
        time.sleep(sweep.S["sleep"])
    f.close()
    return {"appid": appid, "label": label, "total_count": total, "aborts": aborts}


def volume(appid, label, k=VOL_SAMPLE):
    """Systematic sample over price rank, so the cheap end is represented in
    proportion rather than by convenience."""
    src = "cand_rows_%d.jsonl" % appid
    rows, seen = [], set()
    for line in open(src, encoding="utf-8"):
        r = json.loads(line)
        if r.get("abort") or r.get("p") is None or r["h"] in seen:
            continue
        seen.add(r["h"])
        rows.append(r)
    rows.sort(key=lambda r: -r["p"])
    picks = rows if len(rows) <= k else [rows[min(len(rows) - 1, int(i * len(rows) / float(k)))]
                                         for i in range(k)]
    out = "cand_vol_%d.jsonl" % appid
    done = set()
    if os.path.exists(out):
        for line in open(out, encoding="utf-8"):
            try:
                done.add(json.loads(line)["h"])
            except Exception:
                pass
    f = open(out, "a", encoding="utf-8")
    for i, r in enumerate(picks):
        if r["h"] in done:
            continue
        q = urllib.parse.quote(r["h"], safe="")
        u = ("https://steamcommunity.com/market/priceoverview/"
             "?country=US&currency=1&appid=%d&market_hash_name=%s" % (appid, q))
        try:
            j = sweep.fetch(u, tries=4)
        except Abort as e:
            f.write(json.dumps({"h": r["h"], "p": r["p"], "n": r["n"],
                                "abort": e.state}, ensure_ascii=False) + "\n")
            f.flush()
            time.sleep(5)
            continue
        if j.get("success") is not True:
            f.write(json.dumps({"h": r["h"], "p": r["p"], "n": r["n"],
                                "abort": "API-SUCCESS-FALSE"}, ensure_ascii=False) + "\n")
            f.flush()
            time.sleep(sweep.S["sleep"])
            continue
        vol = j.get("volume")
        # volume ABSENT means Steam reports no 24h sales -> null, NOT 0, so
        # "missing" and "measured zero" stay separable
        f.write(json.dumps({"h": r["h"], "p": r["p"], "n": r["n"], "vol_raw": vol,
                            "vol": int(str(vol).replace(",", "")) if vol is not None else None,
                            "lo": j.get("lowest_price"), "med": j.get("median_price")},
                           ensure_ascii=False) + "\n")
        f.flush()
        if i % 15 == 0:
            print("  [%s vol] %d/%d vol=%s" % (label, i, len(picks), vol), flush=True)
        time.sleep(sweep.S["sleep"])
    f.close()


if __name__ == "__main__":
    metas = []
    for ap, lab in CANDIDATES:
        print("=== %s (%d) ===" % (lab, ap), flush=True)
        m = book(ap, lab)
        print("  book done total_count=%s aborts=%s" % (m["total_count"], m["aborts"]),
              flush=True)
        volume(ap, lab)
        metas.append(m)
        json.dump(metas, open("cand_meta.json", "w"), indent=1)
    print("CANDIDATES-DONE", flush=True)

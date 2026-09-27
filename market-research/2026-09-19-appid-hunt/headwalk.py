"""Walk the MARKET-WIDE popularity ranking and tally which appids own the
liquid head of the entire Steam market.

Why this and not a per-appid probe: there are 10,101 appids with a market.
Probing each is 10,101 requests. The market-wide default ordering IS
popularity (verified: head rows are the highest-volume CS2 cases + the TF2
key), so the head of this ranking is by construction where turnover lives.
An appid absent from the liquid head does not have volume.

Abort states stay DISTINCT (steamlib) -- a read failure is never a tally of
zero. Pages that abort are recorded and reported separately.
"""
import json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from steamlib import get_json, Abort

OUT = "headrows.jsonl"
META = "headmeta.json"


def page(start, count=100):
    url = ("https://steamcommunity.com/market/search/render/"
           f"?norender=1&count={count}&start={start}&currency=1")
    j = get_json(url)
    if j.get("success") is not True:
        raise Abort("API-SUCCESS-FALSE")
    if "total_count" not in j:
        raise Abort("NO-TOTAL-COUNT")
    return j


def main(npages, sleep=4.0):
    seen_starts = set()
    if os.path.exists(OUT):
        for line in open(OUT, encoding="utf-8"):
            try:
                seen_starts.add(json.loads(line)["_start"])
            except Exception:
                pass
    f = open(OUT, "a", encoding="utf-8")
    aborted = []
    total_count = None
    for i in range(npages):
        start = i * 10  # pagesize is 10 regardless of count
        if start in seen_starts:
            continue
        try:
            j = page(start)
        except Abort as e:
            aborted.append({"start": start, "state": e.state, "detail": e.detail})
            print(f"  start={start} ABORT {e.state}", flush=True)
            time.sleep(30)
            continue
        total_count = j["total_count"]
        res = j.get("results") or []
        if not res and start < total_count:
            aborted.append({"start": start, "state": "EMPTY-PAGE-INSIDE-RANGE"})
            print(f"  start={start} EMPTY-PAGE-INSIDE-RANGE", flush=True)
            time.sleep(20)
            continue
        for rank, r in enumerate(res):
            ad = r.get("asset_description") or {}
            f.write(json.dumps({
                "_start": start, "_rank": start + rank,
                "appid": ad.get("appid"),
                "h": r.get("hash_name"),
                "p": r.get("sell_price"),
                "n": r.get("sell_listings"),
                "t": r.get("sell_price_text"),
            }) + "\n")
        f.flush()
        if i % 10 == 0:
            print(f"  start={start} got={len(res)} total={total_count}", flush=True)
        time.sleep(sleep)
    f.close()
    meta = {"pages_requested": npages, "total_count": total_count,
            "aborted": aborted, "n_aborted": len(aborted)}
    json.dump(meta, open(META, "w"), indent=1)
    print("META", json.dumps(meta)[:500], flush=True)


if __name__ == "__main__":
    main(int(sys.argv[1]), float(sys.argv[2]) if len(sys.argv) > 2 else 4.0)

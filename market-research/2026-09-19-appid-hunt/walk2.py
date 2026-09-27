"""Market-wide walk under an ARBITRARY sort, so 'which appids have a real
economy' is answered from three independent directions rather than one:

  popular  (default)                -> where TURNOVER is        (headrows.jsonl)
  quantity desc                     -> where STANDING DEPTH is  (qtyrows.jsonl)
  price desc                        -> where VALUE is           (pricerows.jsonl)

An appid absent from all three has neither volume, depth, nor value and
cannot be a destination. Abort states stay DISTINCT -- a read failure is
never tallied as an absent app.
"""
import json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from steamlib import get_json, Abort


def page(start, extra):
    url = ("https://steamcommunity.com/market/search/render/"
           f"?norender=1&count=100&start={start}&currency=1{extra}")
    j = get_json(url)
    if j.get("success") is not True:
        raise Abort("API-SUCCESS-FALSE")
    if "total_count" not in j:
        raise Abort("NO-TOTAL-COUNT")
    return j


def main(tag, extra, npages, sleep=4.0):
    out = f"{tag}rows.jsonl"
    seen = set()
    if os.path.exists(out):
        for line in open(out, encoding="utf-8"):
            try:
                seen.add(json.loads(line)["_start"])
            except Exception:
                pass
    f = open(out, "a", encoding="utf-8")
    aborted = []
    total_count = None
    for i in range(npages):
        start = i * 10
        if start in seen:
            continue
        try:
            j = page(start, extra)
        except Abort as e:
            aborted.append({"start": start, "state": e.state, "detail": e.detail})
            print(f"  [{tag}] start={start} ABORT {e.state}", flush=True)
            time.sleep(30)
            continue
        total_count = j["total_count"]
        res = j.get("results") or []
        if not res and start < total_count:
            aborted.append({"start": start, "state": "EMPTY-PAGE-INSIDE-RANGE"})
            print(f"  [{tag}] start={start} EMPTY-PAGE-INSIDE-RANGE", flush=True)
            time.sleep(20)
            continue
        for rank, r in enumerate(res):
            ad = r.get("asset_description") or {}
            f.write(json.dumps({
                "_start": start, "_rank": start + rank,
                "appid": ad.get("appid"), "h": r.get("hash_name"),
                "p": r.get("sell_price"), "n": r.get("sell_listings"),
                "t": r.get("sell_price_text"),
            }) + "\n")
        f.flush()
        if i % 20 == 0:
            print(f"  [{tag}] start={start} got={len(res)} total={total_count}", flush=True)
        time.sleep(sleep)
    f.close()
    meta = {"tag": tag, "extra": extra, "pages_requested": npages,
            "total_count": total_count, "aborted": aborted, "n_aborted": len(aborted)}
    json.dump(meta, open(f"{tag}meta.json", "w"), indent=1)
    print("META", json.dumps(meta)[:400], flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]),
         float(sys.argv[4]) if len(sys.argv) > 4 else 4.0)

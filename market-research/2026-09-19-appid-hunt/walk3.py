"""STRIDED market-wide walk. The contiguous popularity head is ~85% CS2, so
walking it further mostly re-enumerates CS2. A strided sample over the
popularity ranking maps where EVERY OTHER app enters the volume distribution,
for the same number of requests.

usage: walk3.py <tag> <extra> <first_rank> <last_rank> <stride_pages> [sleep]
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


def main(tag, extra, first, last, stride_pages, sleep=4.0):
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
    starts = list(range(first, last, 10 * stride_pages))
    print(f"[{tag}] {len(starts)} pages, ranks {first}..{last} stride {stride_pages}", flush=True)
    for i, start in enumerate(starts):
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
            print(f"  [{tag}] start={start} got={len(res)}", flush=True)
        time.sleep(sleep)
    f.close()
    meta = {"tag": tag, "extra": extra, "first": first, "last": last,
            "stride_pages": stride_pages, "pages": len(starts),
            "total_count": total_count, "aborted": aborted, "n_aborted": len(aborted)}
    json.dump(meta, open(f"{tag}meta.json", "w"), indent=1)
    print("META", json.dumps(meta)[:400], flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]),
         int(sys.argv[5]), float(sys.argv[6]) if len(sys.argv) > 6 else 4.0)

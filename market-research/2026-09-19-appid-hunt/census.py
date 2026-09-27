"""Census / systematic-sample of a Steam appid market book.
Writes a checkpointed JSONL of every item row so a partial read is
never silently rendered as a complete one."""
import json, os, sys, time
from steamlib import search_page, Abort

def walk(appid, name, pages, per_page=100, sleep=5.0, extra="", tag=""):
    path = f"rows_{appid}{tag}.jsonl"
    seen_starts = set()
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            try: seen_starts.add(json.loads(line)["_start"])
            except Exception: pass
    f = open(path, "a", encoding="utf-8")
    total_count = None; aborted = []
    for start in pages:
        if start in seen_starts:
            continue
        try:
            j = search_page(appid, start, count=per_page, extra=extra)
        except Abort as e:
            aborted.append({"start": start, "state": e.state, "detail": e.detail})
            print(f"  [{name}] start={start} ABORT {e.state}", flush=True)
            time.sleep(30); continue
        total_count = j["total_count"]
        res = j.get("results") or []
        if not res and start < total_count:
            aborted.append({"start": start, "state": "EMPTY-PAGE-INSIDE-RANGE"})
            print(f"  [{name}] start={start} EMPTY-PAGE-INSIDE-RANGE", flush=True)
            time.sleep(20); continue
        for r in res:
            f.write(json.dumps({"_start": start, "h": r.get("hash_name"),
                                "p": r.get("sell_price"), "n": r.get("sell_listings"),
                                "t": r.get("sell_price_text")}) + "\n")
        f.flush()
        print(f"  [{name}] start={start} got={len(res)} total={total_count}", flush=True)
        time.sleep(sleep)
    f.close()
    return {"appid": appid, "name": name, "total_count": total_count,
            "aborted": aborted, "pages_requested": len(pages)}

if __name__ == "__main__":
    appid = int(sys.argv[1]); name = sys.argv[2]
    total = int(sys.argv[3]); per = int(sys.argv[4]); sleep = float(sys.argv[5])
    stride = int(sys.argv[6]) if len(sys.argv) > 6 else 1
    extra = sys.argv[7] if len(sys.argv) > 7 else ""
    tag = sys.argv[8] if len(sys.argv) > 8 else ""
    pages = list(range(0, total, per * stride))
    print(f"{name} appid={appid} total={total} pages={len(pages)} stride={stride}", flush=True)
    meta = walk(appid, name, pages, per, sleep, extra, tag)
    json.dump(meta, open(f"meta_{appid}{tag}.json", "w"), indent=1)
    print("META", json.dumps(meta)[:400], flush=True)

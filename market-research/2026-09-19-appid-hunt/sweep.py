"""Finish the census. ONE process, ONE thread, two phases in priority order.

Why one process: the prior pass was blocked by 3 concurrent workers, and on
2026-09-19 I reproduced it exactly -- 8 side-test requests issued while this
sweep was running 429'd on every single one and ratcheted the sweep's pace from
0.55s to 4.59s. Steam's sustained budget on /market/search/render/ is ~20
req/min for ONE client; a second requester does not add throughput, it destroys
it. So every Steam read in this pass goes through this one loop.

PHASE 1  market-wide popularity head walk.
  The market-wide default ordering is popularity (verified: the head is the
  highest-volume CS2 cases + the TF2 key). An appid absent from the liquid head
  does not have volume. One request tallies a whole page of appids, versus one
  request per appid in phase 2, so this is far and away the higher information
  per unit of Steam's budget and it runs FIRST.

PHASE 2  per-appid census over all 10,101 appids Steam lists with a market.
  The complete instrument. Slow (~8h at Steam's ceiling) but resumable.

Two output states, NEVER merged:
  {...,"total":N}   READ. N may legitimately be 0.
  {...,"abort":S}   UNREAD. Never rendered as zero.
"""
import json, os, sys, time, random, urllib.request, urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from steamlib import Abort

UA = "Mozilla/5.0 (compatible; sboxmarket-sizing/1.0; market sizing research)"
OUT = "appcensus_full.jsonl"
PRIOR = "appcensus.jsonl"
HEAD = "headrows2.jsonl"

SLEEP_MIN, SLEEP_MAX = 0.55, 12.0
S = {"sleep": SLEEP_MIN, "n429": 0, "clean": 0}


def fetch(url, tries=5):
    last = None
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                   "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                status, body = r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise Abort("HTTP-%d" % e.code)
            status, body = 429, ""
        except Exception as e:
            last = "NET-ERROR:%s" % type(e).__name__
            time.sleep(15 + random.uniform(0, 5))
            continue
        if status == 429:
            S["n429"] += 1
            S["clean"] = 0
            S["sleep"] = min(SLEEP_MAX, S["sleep"] * 1.7)
            w = 25.0 * (2 ** attempt) + random.uniform(0, 5)
            print("  !! 429 (#%d) pace->%.2fs cooling %.0fs" % (S["n429"], S["sleep"], w),
                  flush=True)
            time.sleep(w)
            last = "HTTP-429"
            continue
        if status != 200:
            raise Abort("HTTP-%d" % status)
        if not body.strip():
            raise Abort("EMPTY-BODY")
        try:
            j = json.loads(body)
        except Exception:
            raise Abort("UNPARSEABLE-JSON", body[:100].replace("\n", " "))
        S["clean"] += 1
        # decay the ratchet on sustained success, fast enough that one stray
        # 429 does not cost the rest of the run (40 reqs, not 200)
        if S["clean"] >= 40 and S["sleep"] > SLEEP_MIN:
            S["sleep"] = max(SLEEP_MIN, S["sleep"] * 0.8)
            S["clean"] = 0
            print("  .. clean, pace->%.2fs" % S["sleep"], flush=True)
        return j
    raise Abort("HTTP-429-EXHAUSTED" if last and last.startswith("HTTP-429")
                else "RETRIES-EXHAUSTED-%s" % last)


# ---------------------------------------------------------------- phase 1
def head_walk(max_pages):
    seen = set()
    if os.path.exists(HEAD):
        for line in open(HEAD, encoding="utf-8"):
            try:
                seen.add(json.loads(line)["_start"])
            except Exception:
                pass
    f = open(HEAD, "a", encoding="utf-8")
    step = None
    start = 0
    pages = 0
    aborts = {}
    t0 = time.time()
    while pages < max_pages:
        if start in seen:
            start += (step or 100)
            continue
        url = ("https://steamcommunity.com/market/search/render/"
               "?norender=1&count=100&start=%d&currency=1" % start)
        try:
            j = fetch(url)
        except Abort as e:
            aborts[e.state] = aborts.get(e.state, 0) + 1
            f.write(json.dumps({"_start": start, "abort": e.state}) + "\n")
            f.flush()
            print("  [head] start=%d UNREAD %s" % (start, e.state), flush=True)
            time.sleep(10)
            start += (step or 100)
            pages += 1
            continue
        res = j.get("results") or []
        if step is None:
            step = len(res) or 10
            print("  [head] PAGESIZE MEASURED = %d (requested count=100), market total_count=%s"
                  % (step, format(j.get("total_count") or 0, ",")), flush=True)
        if not res:
            print("  [head] start=%d empty -> end of ranking" % start, flush=True)
            break
        for rank, r in enumerate(res):
            ad = r.get("asset_description") or {}
            f.write(json.dumps({"_start": start, "_rank": start + rank,
                                "appid": ad.get("appid"), "h": r.get("hash_name"),
                                "p": r.get("sell_price"), "n": r.get("sell_listings")},
                               ensure_ascii=False) + "\n")
        f.flush()
        if pages % 20 == 0:
            el = time.time() - t0
            print("  [head] start=%d got=%d pages=%d %.2fs/req n429=%d"
                  % (start, len(res), pages, el / max(1, pages), S["n429"]), flush=True)
        start += step
        pages += 1
        time.sleep(S["sleep"])
    f.close()
    print("PHASE1-DONE pages=%d depth=%d aborts=%s" % (pages, start, aborts), flush=True)


# ---------------------------------------------------------------- phase 2
def load_read():
    done = set()
    for p in (PRIOR, OUT):
        if not os.path.exists(p):
            continue
        for line in open(p, encoding="utf-8"):
            try:
                r = json.loads(line)
                if not r.get("abort"):
                    done.add(str(r["appid"]))
            except Exception:
                pass
    return done


def appid_census():
    apps = json.load(open("market_apps.json", encoding="utf-8"))
    done = load_read()
    keys = sorted(apps, key=lambda x: int(x))
    todo = [a for a in keys if a not in done]
    t0 = time.time()
    print("PHASE2 appids=%d already-read=%d todo=%d" % (len(keys), len(done), len(todo)),
          flush=True)
    f = open(OUT, "a", encoding="utf-8")
    nz = 0
    for i, ap in enumerate(todo):
        url = ("https://steamcommunity.com/market/search/render/"
               "?appid=%s&norender=1&count=1&start=0&currency=1" % ap)
        try:
            j = fetch(url)
        except Abort as e:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": e.state, "detail": e.detail},
                               ensure_ascii=False) + "\n")
            f.flush()
            print("  appid=%s UNREAD %s" % (ap, e.state), flush=True)
            time.sleep(10)
            continue
        if j.get("success") is not True or j.get("total_count") is None:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": "API-SUCCESS-FALSE-OR-NO-COUNT"},
                               ensure_ascii=False) + "\n")
            f.flush()
            time.sleep(S["sleep"])
            continue
        tc = j["total_count"]
        f.write(json.dumps({"appid": int(ap), "name": apps[ap], "total": tc},
                           ensure_ascii=False) + "\n")
        f.flush()
        if tc > 0:
            nz += 1
        if i % 100 == 0:
            el = time.time() - t0
            rate = (i + 1) / el if el else 0
            print("  [%d/%d] appid=%s total=%d nonzero=%d %.2fs/req n429=%d eta=%.2fh"
                  % (i, len(todo), ap, tc, nz, (1 / rate if rate else 0), S["n429"],
                     ((len(todo) - i) / rate / 3600.0 if rate else 0)), flush=True)
        time.sleep(S["sleep"])
    f.close()
    print("PHASE2-DONE nonzero=%d n429=%d" % (nz, S["n429"]), flush=True)


if __name__ == "__main__":
    head_walk(int(sys.argv[1]) if len(sys.argv) > 1 else 400)
    appid_census()

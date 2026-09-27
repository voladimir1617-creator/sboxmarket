"""Sharded per-appid census. usage: appcensus2.py <shard> <nshards> [sleep]

Each shard writes its OWN file so there is no interleaved-write corruption.
Resume-safe: re-reads its own file and any already-complete appcensus.jsonl.

Rate discipline: a 429 is LOGGED (steamlib backs off internally and, if it
cannot recover, raises HTTP-429-EXHAUSTED which is written as an abort, never
as total=0). If 429s appear, reduce shards -- back off, do not retry harder.
"""
import json, os, sys, time, urllib.request, urllib.error, random

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from steamlib import Abort

UA = "Mozilla/5.0 (compatible; sboxmarket-sizing/1.0; market sizing research)"
RL = {"n429": 0}


def get_json_logged(url, tries=5, base_sleep=20.0):
    last = None
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                   "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                status, body = r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 429:
                RL["n429"] += 1
                last = "HTTP-429"
                print(f"  !! HTTP-429 (total {RL['n429']}) backing off", flush=True)
                time.sleep(base_sleep * (2 ** attempt) + random.uniform(0, 5))
                continue
            raise Abort(f"HTTP-{e.code}")
        except Exception as e:
            last = f"NET-ERROR:{type(e).__name__}"
            time.sleep(base_sleep + random.uniform(0, 5))
            continue
        if status == 429:
            RL["n429"] += 1
            last = "HTTP-429"
            print(f"  !! HTTP-429 (total {RL['n429']}) backing off", flush=True)
            time.sleep(base_sleep * (2 ** attempt) + random.uniform(0, 5))
            continue
        if status != 200:
            raise Abort(f"HTTP-{status}")
        if not body.strip():
            raise Abort("EMPTY-BODY")
        try:
            return json.loads(body)
        except Exception:
            raise Abort("UNPARSEABLE-JSON", body[:100].replace("\n", " "))
    raise Abort("HTTP-429-EXHAUSTED" if last and last.startswith("HTTP-429")
                else f"RETRIES-EXHAUSTED-{last}")


def load_done():
    done = set()
    for p in ["appcensus.jsonl"] + [f"appcensus_s{i}.jsonl" for i in range(8)]:
        if os.path.exists(p):
            for line in open(p, encoding="utf-8"):
                try:
                    r = json.loads(line)
                    if not r.get("abort"):
                        done.add(str(r["appid"]))
                except Exception:
                    pass
    return done


def main(shard, nshards, sleep=1.0):
    apps = json.load(open("market_apps.json", encoding="utf-8"))
    done = load_done()
    keys = sorted(apps, key=lambda x: int(x))
    mine = [a for i, a in enumerate(keys) if i % nshards == shard and a not in done]
    out = f"appcensus_s{shard}.jsonl"
    print(f"[s{shard}] todo={len(mine)} (already done globally {len(done)})", flush=True)
    f = open(out, "a", encoding="utf-8")
    nz = 0
    for i, ap in enumerate(mine):
        url = ("https://steamcommunity.com/market/search/render/"
               f"?appid={ap}&norender=1&count=1&start=0&currency=1")
        try:
            j = get_json_logged(url)
        except Abort as e:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": e.state, "detail": e.detail}) + "\n")
            f.flush()
            print(f"  [s{shard}] appid={ap} ABORT {e.state}", flush=True)
            time.sleep(15)
            continue
        if j.get("success") is not True or j.get("total_count") is None:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": "API-SUCCESS-FALSE-OR-NO-COUNT"}) + "\n")
            f.flush()
            time.sleep(sleep)
            continue
        tc = j["total_count"]
        f.write(json.dumps({"appid": int(ap), "name": apps[ap], "total": tc}) + "\n")
        f.flush()
        if tc > 0:
            nz += 1
        if i % 250 == 0:
            print(f"  [s{shard}] {i}/{len(mine)} nonzero={nz} n429={RL['n429']}", flush=True)
        time.sleep(sleep)
    f.close()
    print(f"[s{shard}] DONE nonzero={nz} n429={RL['n429']}", flush=True)


if __name__ == "__main__":
    main(int(sys.argv[1]), int(sys.argv[2]),
         float(sys.argv[3]) if len(sys.argv) > 3 else 1.0)

"""Measure 24h turnover via priceoverview `volume` -- the field
SteamMarketPriceService.groovy:398-399 already fetches and DISCARDS.
Sampled systematically over price rank so the cheap/commodity end is
represented in proportion, not by convenience."""
import json, os, sys, time, urllib.parse
from steamlib import get_json, Abort

def sample_names(path, k):
    rows, seen = [], set()
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r["h"] in seen or r["p"] is None: continue
        seen.add(r["h"]); rows.append(r)
    rows.sort(key=lambda r: -r["p"])                 # price rank, descending
    if len(rows) <= k: return rows
    step = len(rows) / float(k)
    return [rows[min(len(rows) - 1, int(i * step))] for i in range(k)]

def run(appid, label, path, k, sleep=4.0):
    out_path = f"vol_{appid}.jsonl"
    done = set()
    if os.path.exists(out_path):
        for line in open(out_path, encoding="utf-8"):
            try: done.add(json.loads(line)["h"])
            except Exception: pass
    f = open(out_path, "a", encoding="utf-8")
    picks = sample_names(path, k)
    aborts = {}
    for i, r in enumerate(picks):
        if r["h"] in done: continue
        q = urllib.parse.quote(r["h"], safe="")
        u = ("https://steamcommunity.com/market/priceoverview/"
             f"?country=US&currency=1&appid={appid}&market_hash_name={q}")
        try:
            j = get_json(u, tries=4, base_sleep=25.0)
        except Abort as e:
            aborts[e.state] = aborts.get(e.state, 0) + 1
            f.write(json.dumps({"h": r["h"], "p": r["p"], "n": r["n"],
                                "abort": e.state}) + "\n"); f.flush()
            time.sleep(sleep); continue
        if j.get("success") is not True:
            # item exists in search but priceoverview refuses -> DISTINCT state,
            # never folded into "zero volume"
            f.write(json.dumps({"h": r["h"], "p": r["p"], "n": r["n"],
                                "abort": "API-SUCCESS-FALSE"}) + "\n"); f.flush()
            aborts["API-SUCCESS-FALSE"] = aborts.get("API-SUCCESS-FALSE", 0) + 1
            time.sleep(sleep); continue
        vol = j.get("volume")
        # volume ABSENT means Steam reports no 24h sales -- record as
        # null, NOT as 0, so "missing" and "measured zero" stay separable
        rec = {"h": r["h"], "p": r["p"], "n": r["n"],
               "vol_raw": vol,
               "vol": int(str(vol).replace(",", "")) if vol is not None else None,
               "lo": j.get("lowest_price"), "med": j.get("median_price")}
        f.write(json.dumps(rec) + "\n"); f.flush()
        if i % 10 == 0:
            print(f"  [{label}] {i}/{len(picks)} {r['h'][:34]!r} vol={vol}", flush=True)
        time.sleep(sleep)
    f.close()
    print(f"[{label}] DONE aborts={aborts}", flush=True)

if __name__ == "__main__":
    run(int(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4]))

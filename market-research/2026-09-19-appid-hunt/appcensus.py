"""ONE request per appid -> total_count of that app's OWN market items.

Why this is the right instrument (VERIFIED 2026-09-19):
  appid=220 (Half-Life 2, which has Steam trading cards) returns total_count 0.
  Trading cards / backgrounds / emoticons live ONLY under appid 753. So a
  per-appid probe counts a game's NATIVE in-game item economy and nothing else.

This replaces the prior pass's 52 hand-recalled appids with the complete set
of 10,101 apps Steam itself lists as having a market.

Abort states stay DISTINCT. A probe that fails is written with "abort" set and
is NEVER written as total=0 -- "could not read" and "nothing there" must stay
separable, which is this operation's signature defect.
"""
import json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from steamlib import get_json, Abort

OUT = "appcensus.jsonl"


def main(sleep=0.8):
    apps = json.load(open("market_apps.json", encoding="utf-8"))
    done = set()
    if os.path.exists(OUT):
        for line in open(OUT, encoding="utf-8"):
            try:
                done.add(str(json.loads(line)["appid"]))
            except Exception:
                pass
    todo = [a for a in apps if a not in done]
    todo.sort(key=lambda x: int(x))
    print(f"apps total={len(apps)} done={len(done)} todo={len(todo)}", flush=True)
    f = open(OUT, "a", encoding="utf-8")
    nz = 0
    for i, ap in enumerate(todo):
        url = ("https://steamcommunity.com/market/search/render/"
               f"?appid={ap}&norender=1&count=1&start=0&currency=1")
        try:
            j = get_json(url, tries=5, base_sleep=20.0)
        except Abort as e:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": e.state, "detail": e.detail}) + "\n")
            f.flush()
            print(f"  appid={ap} ABORT {e.state}", flush=True)
            time.sleep(15)
            continue
        if j.get("success") is not True:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": "API-SUCCESS-FALSE"}) + "\n")
            f.flush()
            time.sleep(sleep)
            continue
        tc = j.get("total_count")
        if tc is None:
            f.write(json.dumps({"appid": int(ap), "name": apps[ap],
                                "abort": "NO-TOTAL-COUNT"}) + "\n")
            f.flush()
            time.sleep(sleep)
            continue
        f.write(json.dumps({"appid": int(ap), "name": apps[ap], "total": tc}) + "\n")
        f.flush()
        if tc > 0:
            nz += 1
        if i % 200 == 0:
            print(f"  [{i}/{len(todo)}] appid={ap} total={tc} (nonzero so far {nz})",
                  flush=True)
        time.sleep(sleep)
    f.close()
    print("DONE", flush=True)


if __name__ == "__main__":
    main(float(sys.argv[1]) if len(sys.argv) > 1 else 0.8)

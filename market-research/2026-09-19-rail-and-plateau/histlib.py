"""Read Steam's per-item SALES history out of the public market listing page.

WHY THIS EXISTS. Every prior pass sized these markets from two 24-hour
snapshots (`priceoverview.volume` and the standing book) and then argued about
what they would look like in a year. The listing page turns out to carry the
whole series: `steamcommunity.com/market/listings/<appid>/<hash_name>` embeds a
dehydrated react-query cache containing

    {"time": <unix>, "price_median": <float>, "purchases": <int>}

back to the item's first sale. That is MEASURED transaction history — price and
count per bucket — not an extrapolation, and it is the instrument the plateau
question actually needs.

ROBOTS, quoted, read 2026-09-19 before any fetch:
  steamcommunity.com/robots.txt  ->  "Disallow: /actions/  /linkfilter/
  /tradeoffer/  /trade/  /email/".  /market/listings/ is NOT disallowed.
  store.steampowered.com/robots.txt -> "/share/ /news/externalpost/
  /account/... /login/... /join/... /email/ /widget/".  /appreviewhistogram/
  is NOT disallowed.
  api.steampowered.com is Disallow: / and is NEVER called.
  SteamDB forbids scraping in its terms and is NEVER fetched.

RATE DISCIPLINE. Single process, single thread, one shared pace, exactly as
2026-09-19's sweep measured it: Steam serves ~20 req/min sustained and
collapses under parallelism. These pages are ~300 KB, an order of magnitude
heavier than a search page, so the floor here is 3.0s, not 0.5s.

ABORTS ARE NOT ZEROS. Every failure is written with a distinct `abort` state.
An item we could not read is never recorded as an item that never sold.
"""
import json
import os
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (compatible; sboxmarket-sizing/1.0; market sizing research)")

# The blob is JSON-inside-JSON-inside-HTML, so the quotes arrive backslash
# escaped an unpredictable number of times. Match "any run of backslashes".
POINT = re.compile(
    r'\{[\\]*"time[\\]*":(\d+),'
    r'[\\]*"price_median[\\]*":([0-9.eE+-]+),'
    r'[\\]*"purchases[\\]*":(\d+)\}')


class Abort(Exception):
    def __init__(self, state, detail=""):
        super().__init__(state + ((": " + detail) if detail else ""))
        self.state = state
        self.detail = detail


class Pace(object):
    """One shared pace for the whole process. Backs off on 429 and DECAYS the
    backoff on sustained success -- a backoff that only ever grows is a
    ratchet, and a ratchet pins the crawler at its worst observed minute."""

    def __init__(self, sleep=3.0, floor=3.0, ceil=60.0):
        self.sleep = sleep
        self.floor = floor
        self.ceil = ceil
        self.ok_run = 0
        self.n429 = 0

    def good(self):
        self.ok_run += 1
        if self.ok_run >= 20 and self.sleep > self.floor:
            self.sleep = max(self.floor, self.sleep * 0.8)
            self.ok_run = 0

    def bad(self):
        self.ok_run = 0
        self.n429 += 1
        self.sleep = min(self.ceil, max(self.floor, self.sleep * 2.0))


def get_text(url, pace, tries=5):
    last = None
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        })
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, body = r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 429:
                last = "HTTP-429"
                pace.bad()
                time.sleep(pace.sleep * (2 ** attempt) + random.uniform(0, 5))
                continue
            raise Abort("HTTP-%d" % e.code)
        except Exception as e:
            last = "NET-ERROR:%s" % type(e).__name__
            time.sleep(10 + random.uniform(0, 5))
            continue
        if status == 429:
            last = "HTTP-429"
            pace.bad()
            time.sleep(pace.sleep * (2 ** attempt) + random.uniform(0, 5))
            continue
        if status != 200:
            raise Abort("HTTP-%d" % status)
        if not body.strip():
            raise Abort("EMPTY-BODY")          # NOT an item with no sales
        pace.good()
        return body
    raise Abort("HTTP-429-EXHAUSTED" if last and last.startswith("HTTP-429")
                else "RETRIES-EXHAUSTED-%s" % last)


def history(appid, hash_name, pace):
    """[(unix, price_median, purchases)] sorted by time, or raise Abort.

    An item page that renders but carries no series raises NO-HISTORY-BLOCK
    rather than returning [] -- "the page did not contain the data" and "the
    item never sold" are different facts and must not share a representation.
    """
    url = ("https://steamcommunity.com/market/listings/%d/%s"
           % (appid, urllib.parse.quote(hash_name, safe="")))
    body = get_text(url, pace)
    pts = POINT.findall(body)
    if not pts:
        # Three different facts, three different states. Steam's not-found page
        # is a 200 with the generic title "Market Item"; an item that exists but
        # has never traded renders with its own title and no series; and a page
        # that contains the field but does not parse is a code defect here.
        if "price_median" in body:
            raise Abort("HISTORY-UNPARSEABLE")
        if "<title>Market Item - Steam Community Market</title>" in body:
            raise Abort("ITEM-NOT-FOUND")
        raise Abort("NO-HISTORY-BLOCK")
    rows = {}
    for t, p, q in pts:
        rows[int(t)] = (float(p), int(q))       # de-dup if embedded twice
    return sorted((t, v[0], v[1]) for t, v in rows.items())


def load_jsonl(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out

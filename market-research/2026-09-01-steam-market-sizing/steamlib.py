"""Shared Steam market reader. Every failure mode gets a DISTINCT abort state;
none of them may ever be rendered as an empty/zero market."""
import json, time, urllib.request, urllib.error, random

UA = "Mozilla/5.0 (compatible; sboxmarket-sizing/1.0; market sizing research)"

class Abort(Exception):
    def __init__(self, state, detail=""):
        super().__init__(f"{state}{(': '+detail) if detail else ''}")
        self.state = state; self.detail = detail

def get_json(url, tries=6, base_sleep=20.0):
    """Returns parsed JSON or raises Abort with a distinct state.
    Distinct states: HTTP-429-EXHAUSTED, HTTP-<code>, NET-ERROR, EMPTY-BODY,
    UNPARSEABLE-JSON, API-SUCCESS-FALSE."""
    last = None
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                   "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                status, body = r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 429:
                last = "HTTP-429"
                time.sleep(base_sleep * (2 ** attempt) + random.uniform(0, 5))
                continue
            raise Abort(f"HTTP-{e.code}")
        except Exception as e:
            last = f"NET-ERROR:{type(e).__name__}"
            time.sleep(base_sleep + random.uniform(0, 5)); continue
        if status == 429:
            last = "HTTP-429"
            time.sleep(base_sleep * (2 ** attempt) + random.uniform(0, 5)); continue
        if status != 200:
            raise Abort(f"HTTP-{status}")
        if not body.strip():
            raise Abort("EMPTY-BODY")          # NOT an empty market
        try:
            return json.loads(body)
        except Exception:
            raise Abort("UNPARSEABLE-JSON", body[:100].replace("\n", " "))
    raise Abort("HTTP-429-EXHAUSTED" if last and last.startswith("HTTP-429")
                else f"RETRIES-EXHAUSTED-{last}")

def search_page(appid, start, count=100, extra=""):
    url = ("https://steamcommunity.com/market/search/render/"
           f"?appid={appid}&norender=1&count={count}&start={start}&currency=1{extra}")
    j = get_json(url)
    if j.get("success") is not True:
        raise Abort("API-SUCCESS-FALSE")
    if "total_count" not in j:
        raise Abort("NO-TOTAL-COUNT")
    return j

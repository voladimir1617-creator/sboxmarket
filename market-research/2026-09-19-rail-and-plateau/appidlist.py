"""Re-extract the appid population, and check whether re-extracting fixes it.

THE PRIOR PASS'S DIAGNOSIS, AND WHY IT IS WRONG.
Commit 8edf54a ends: "two of the six genre members found in the head walk
(4892010, 5033210) are absent from market_apps.json, so the 10,101-app list is
already stale ... The app list needs re-extracting (extract_apps.py) before the
next pass." That reads the absence as STALENESS -- a list captured before those
games launched.

It is not staleness. Re-running the same extractor against a page fetched
eight hours later returns the IDENTICAL 10,101 appids: zero added, zero
removed, and 4892010 and 5033210 still absent. Bomb Farm (4892010) was
measured in this very pass at 20,310 trades/day across 270 items, so a live
market with twenty thousand daily trades is missing from the list that is
supposed to enumerate markets.

So the list is not out of date, it is NOT A POPULATION. The game facet on
`/market/search` is a curated or truncated set, and no amount of re-extracting
turns it into a census frame. The remedy the prior pass prescribed is inert,
and a successor pass that ran it and declared the frame fixed would have been
wrong in exactly the way it was trying to avoid.

What this means for the search for candidates: the HEAD WALK over the whole
market's top items is the only instrument that has ever found these games, and
it remains the only one. A per-appid census over this list, even completed to
all 10,101, cannot reach them.

ROBOTS: steamcommunity.com/robots.txt disallows /actions/, /linkfilter/,
/tradeoffer/, /trade/, /email/. /market/search is not disallowed and is the
only path fetched here.

TO REPRODUCE: fetch https://steamcommunity.com/market/search into
books/market_search.html and run the prior pass's extract_apps.py from that
directory. The 3.9 MB raw page is deliberately NOT committed -- its extract
(books/market_apps.json) is, and the extract is what the comparison uses.
"""
import io
import json
import os
import subprocess
import sys

if (sys.stdout.encoding or "").lower().replace("-", "") != "utf8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
B = os.path.join(HERE, "books")
PRIOR = os.path.abspath(os.path.join(HERE, "..", "2026-09-19-appid-hunt", "books"))

# appids this pass or the prior one MEASURED as having a live market
MEASURED_LIVE = {
    4891320: "WoG: War of Genesis   914 items, 96,910 trades/day",
    4892010: "Bomb Farm             270 items, 20,310 trades/day",
    3678970: "TBH: Task Bar Hero  1,064 items, 70,046 trades/day",
    3419430: "Bongo Cat             540 items,  1,521 trades/day",
    5033210: "Desk Top Racer        found in the head walk, not sized",
    4952980: "My Party Is Grinding  found in the head walk, not sized",
}

new = json.load(open(os.path.join(B, "market_apps.json"), encoding="utf-8"))
old = json.load(open(os.path.join(PRIOR, "market_apps.json"), encoding="utf-8"))

print("=" * 78)
print("DOES RE-EXTRACTING THE APPID LIST FIX IT?")
print("=" * 78)
print("  list captured 2026-09-19 14:08 : %s appids" % format(len(old), ","))
print("  list captured 2026-09-19 22:2x : %s appids" % format(len(new), ","))
print("  ADDED by the re-extract        : %d" % len(set(new) - set(old)))
print("  REMOVED by the re-extract      : %d" % len(set(old) - set(new)))
print()
print("  MARKETS MEASURED LIVE, AND WHETHER THE LIST CONTAINS THEM:")
missing = 0
for ap, note in sorted(MEASURED_LIVE.items()):
    inold, innew = str(ap) in old, str(ap) in new
    if not innew:
        missing += 1
    print("     %-8d old %-5s new %-5s   %s" % (ap, inold, innew, note))
print()
print("  %d of %d measured-live markets are ABSENT from a freshly extracted list."
      % (missing, len(MEASURED_LIVE)))
print()
print("  VERDICT: the list is not stale, it is not a population. Re-extraction")
print("  is inert -- it returns the same %s appids. The head walk over the whole"
      % format(len(new), ","))
print("  market's top items is the only instrument that has ever surfaced these")
print("  games, and completing a per-appid census over this frame could not.")

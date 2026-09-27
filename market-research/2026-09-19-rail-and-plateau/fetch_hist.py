"""Capture the full sales history of every item in the prior pass's samples.

THE SAMPLE IS DELIBERATELY THE PRIOR ONE. Each candidate's items are taken
verbatim from the 2026-09-19 turnover files (`cand_vol_<appid>.jsonl`,
`vol_3678970.jsonl`) -- the same systematic sample over price rank, the same
40 rows, the same scale factor. Re-picking a "better" sample would make the
new GMV curve incomparable with the number it is meant to check, and the first
thing this capture has to do is check that number: the prior pass read WoG at
95,286 trades/day off a 24-hour snapshot, which is an outlier, and an outlier
is a bug until proven otherwise. Summing `purchases` over the same 24 hours
from an independent endpoint either reproduces it or does not.

FOUR CANDIDATES, ORDERED BY AGE, because the question is what a nine-day-old
market is worth once it settles:

    4891320  WoG: War of Genesis      released 2026-09-10      9 days
    4892010  Bomb Farm                released 2026-08-28     22 days
    3678970  TBH: Task Bar Hero       released 2026-05-27     16 weeks
    3419430  Bongo Cat                released 2025-03-05     18 months

Single-threaded, one shared pace, aborts recorded distinctly from zeros.
Resumable: an item already written with a non-abort row is skipped.
"""
import io
import json
import os
import sys
import time

# Item names here are Korean, Cyrillic and CJK. Windows' default cp1252 stdout
# raises UnicodeEncodeError on the first one and kills the capture mid-market,
# which is how a harness manufactures a finding: the run stops and the partial
# file looks like a complete one. Force UTF-8 with replacement.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from histlib import Abort, Pace, history, load_jsonl   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "books")
PRIOR = os.path.abspath(os.path.join(HERE, "..", "2026-09-19-appid-hunt", "books"))

# (appid, label, prior turnover file, total_count of the book, items to keep)
# `keep` is None where the whole prior sample was affordable and a number
# where Steam's latency throttle forced a stride -- see thin().
TARGETS = [
    (4891320, "WoG: War of Genesis", "cand_vol_4891320.jsonl", 914,  None),
    (4892010, "Bomb Farm",           "cand_vol_4892010.jsonl", 270,  None),
    (3678970, "TBH: Task Bar Hero",  "vol_3678970.jsonl",     1064,  22),
    (3419430, "Bongo Cat",           "cand_vol_3419430.jsonl", 540,  22),
]


def sample_for(volfile):
    """The exact rows the prior pass sampled, aborts included as UNREAD.

    An aborted turnover row is still a legitimate member of the sample -- it
    was selected, it just was not read. Carrying it here keeps the denominator
    honest: dropping it would silently shrink the sample and inflate every
    per-item figure scaled from it.
    """
    rows, seen = [], set()
    for r in load_jsonl(os.path.join(PRIOR, volfile)):
        h = r.get("h")
        if not h or h in seen:
            continue
        seen.add(h)
        rows.append({"h": h, "p": r.get("p"), "n": r.get("n"),
                     "vol24_prior": r.get("vol"),
                     "prior_abort": r.get("abort")})
    return rows


def thin(picks, keep):
    """Take every k-th pick rather than the first `keep`.

    MEASURED MID-RUN: Steam soft-throttles these pages by LATENCY, not by 429.
    WoG's 103 items came back at ~3s each with zero 429s; TBH's pages then
    slowed to ~200s each with the pace still reading 3.00s and the 429 counter
    still reading 0. A backoff that only watches for 429 sees a healthy client
    while throughput has fallen 60x -- absence read as success.

    The response is to shrink the sample, not to push harder, and to shrink it
    by STRIDE. The pick list is ordered by descending price, so keeping the
    first N would keep only the expensive end and would bias every ratio
    computed from it. Every k-th keeps the price spread.

    Sample size costs precision and not validity here: the analogue's GMV
    multiple is a RATIO of the same items at two ages, so the scale factor
    cancels entirely.
    """
    if keep is None or len(picks) <= keep:
        return picks
    step = len(picks) / float(keep)
    return [picks[min(len(picks) - 1, int(i * step))] for i in range(keep)]


def run(appid, label, volfile, total, pace, keep=None):
    picks = thin(sample_for(volfile), keep)
    path = os.path.join(OUT, "hist_%d.jsonl" % appid)
    done = set()
    for r in load_jsonl(path):
        if not r.get("abort"):
            done.add(r["h"])
    f = open(path, "a", encoding="utf-8")
    print("=== %s (%d): %d sampled items of %d in book, %d already captured ==="
          % (label, appid, len(picks), total, len(done)), flush=True)
    aborts = {}
    for i, r in enumerate(picks):
        if r["h"] in done:
            continue
        try:
            pts = history(appid, r["h"], pace)
        except Abort as e:
            aborts[e.state] = aborts.get(e.state, 0) + 1
            f.write(json.dumps({"h": r["h"], "abort": e.state}, ensure_ascii=False) + "\n")
            f.flush()
            print("  [%s] %-40s UNREAD %s" % (label, r["h"][:40], e.state), flush=True)
            time.sleep(pace.sleep + 5)
            continue
        f.write(json.dumps({"h": r["h"], "p": r["p"], "n": r["n"],
                            "vol24_prior": r["vol24_prior"],
                            "pts": pts}, ensure_ascii=False) + "\n")
        f.flush()
        if i % 5 == 0:
            print("  [%s] %d/%d %-34s %5d pts  pace %.2fs  429s %d"
                  % (label, i, len(picks), r["h"][:34], len(pts), pace.sleep,
                     pace.n429), flush=True)
        time.sleep(pace.sleep)
    f.close()
    print("  [%s] done. aborts=%s" % (label, aborts or "none"), flush=True)
    return {"appid": appid, "label": label, "total_count": total,
            "sampled": len(picks), "aborts": aborts}


if __name__ == "__main__":
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    pace = Pace()
    metas = []
    only = set(int(a) for a in sys.argv[1:]) if len(sys.argv) > 1 else None
    for ap, lab, vf, tot, keep in TARGETS:
        if only and ap not in only:
            continue
        metas.append(run(ap, lab, vf, tot, pace, keep))
        json.dump(metas, open(os.path.join(OUT, "hist_meta.json"), "w"), indent=1)
    print("FETCH-HIST-DONE", flush=True)

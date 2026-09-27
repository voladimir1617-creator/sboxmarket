"""TBH take-rate sensitivity against the payment rail.

The shipped 2% is a constant in TradeService.groovy, not a law of the market.
Steam itself charges 15%. The question the bar has to answer is not "does 2%
clear $500/mo" but "is there ANY take rate that is both above the rail and
below what Steam charges" -- because the rail cost is a PERCENTAGE (2.9% of
every deposited dollar), not just the $0.30 fixed leg that the per-trade
framing makes visible.
"""
import io, json, re, sys, os
from decimal import Decimal, ROUND_HALF_UP

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))


def num(s):
    if not s:
        return None
    s = re.sub(r"[^0-9.,]", "", s).replace(",", "")
    try:
        return float(s)
    except Exception:
        return None


ok = []
silent = 0
for line in open(os.path.join(HERE, "books", "vol_3678970.jsonl"), encoding="utf-8"):
    r = json.loads(line)
    if r.get("abort"):
        continue
    if r.get("vol") is None:
        silent += 1
        continue
    ok.append((num(r.get("med")) or (r["p"] / 100.0), r["vol"], r["h"]))

gmv_day_sample = sum(p * v for p, v, _ in ok)
N_SAMPLED = len(ok) + silent
TOTAL_ITEMS = 1064                      # MEASURED total_count for appid 3678970
SCALE = TOTAL_ITEMS / float(N_SAMPLED) * 365.0
gmv_yr = gmv_day_sample * SCALE

print("TBH: Task Bar Hero (3678970) — take-rate vs the payment rail")
print("  MEASURED n={} sampled items ({} sold in 24h, {} sold nothing), scaled x{:.1f} to "
      "{} items".format(N_SAMPLED, len(ok), silent, TOTAL_ITEMS / float(N_SAMPLED), TOTAL_ITEMS))
print("  GMV/yr MEASURED ${:,.0f}".format(gmv_yr))
print()
print("  rate    gross take/yr   effective     net after 2.9% deposit rail")
for rate in (0.02, 0.03, 0.05, 0.07, 0.10, 0.15):
    q = Decimal(str(rate))
    eff_day = sum(float((Decimal(str(p)) * q).quantize(Decimal("0.01"), ROUND_HALF_UP)) * v
                  for p, v, _ in ok)
    gross = eff_day * SCALE
    net = gross - 0.029 * gmv_yr
    print("  {:>4.0f}%   ${:>12,.0f}   {:>6.3f}%     ${:>+11,.0f}/yr = ${:>+9,.0f}/mo"
          .format(rate * 100, gross, 100 * eff_day / gmv_day_sample, net, net / 12))
print()
print("  The shipped 2% yields an EFFECTIVE 1.78% after HALF_UP rounding wipes out")
print("  every trade under $0.25. That is BELOW Stripe's 2.9% deposit leg, so at the")
print("  shipped rate the platform pays the rail more than it collects — at every")
print("  volume, including infinite volume. Solvency starts around 4%.")
print()
print("  CAVEAT, and it is the one that decides this: the rail is charged per DEPOSIT,")
print("  not per trade. One deposited dollar can fund several trades before it is")
print("  withdrawn. So the rail cost per dollar of GMV is (2.9% + 0.25%) / velocity,")
print("  where velocity = GMV / dollars deposited. Velocity is NOT MEASURED here —")
print("  it is a property of a market that does not exist yet. What can be stated is")
print("  the velocity each take rate REQUIRES to break even on the rail alone:")
RAIL = 0.029 + 0.0025
for rate in (0.02, 0.03, 0.05, 0.07, 0.10, 0.15):
    q = Decimal(str(rate))
    eff = sum(float((Decimal(str(p)) * q).quantize(Decimal("0.01"), ROUND_HALF_UP)) * v
              for p, v, _ in ok) / gmv_day_sample
    print("    {:>4.0f}%  effective {:>6.3f}%  -> needs velocity > {:>5.2f}x"
          .format(rate * 100, 100 * eff, RAIL / eff))
print()
print("  Against a $500/mo cost base AND the rail, at velocity 1x (every deposited")
print("  dollar funds exactly one trade — the pessimistic end):")
for rate in (0.02, 0.05, 0.07, 0.10):
    q = Decimal(str(rate))
    eff_day = sum(float((Decimal(str(p)) * q).quantize(Decimal("0.01"), ROUND_HALF_UP)) * v
                  for p, v, _ in ok)
    per_gmv = (eff_day * SCALE - RAIL * gmv_yr) / gmv_yr
    if per_gmv <= 0:
        print("    {:>4.0f}%  IMPOSSIBLE at any share — net take is negative"
              .format(rate * 100))
    else:
        need = 6000.0 / (per_gmv * gmv_yr)
        print("    {:>4.0f}%  needs {:>6.2f}% of the whole market to clear $500/mo"
              .format(rate * 100, 100 * need))

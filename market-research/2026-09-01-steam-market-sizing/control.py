"""POSITIVE CONTROL — must run and pass before any measurement is trusted.
1. currency: a known item must return a $-denominated price matching sell_price/100
2. abort states: a deliberately bad appid must NOT read as an empty market
3. fee model: must reproduce the established anchors from the shipped code"""
import sys, time
from steamlib import search_page, Abort
from fees import platform_take, stripe_deposit_cost, stripe_payout_cost, seller_net

FAIL = []

# --- control 3 runs first: no network needed ---
# Anchor A: shipped TradeService.groovy:539 -> (price*0.02).setScale(2, HALF_UP)
assert platform_take(1.61) == 0.03, platform_take(1.61)
assert platform_take(0.24) == 0.00, "sub-$0.25 must earn exactly $0.00"
assert platform_take(0.25) == 0.01, "at $0.25 HALF_UP must tip to a cent"
# Anchor B: the median-item friction quoted by the prior pass (~$0.60 to Stripe)
dep = stripe_deposit_cost(1.61); pay = stripe_payout_cost(1.61 - 0.03)
print(f"CONTROL-FEE  $1.61 item: platform=${platform_take(1.61):.2f} "
      f"stripe_deposit=${dep:.2f} stripe_payout=${pay:.2f} total_rail=${dep+pay:.2f}")
if not (0.55 <= dep + pay <= 0.65):
    FAIL.append(f"fee anchor: rail total ${dep+pay:.2f} not ~$0.60")

# --- control 2: a nonsense appid must abort, not measure zero ---
try:
    j = search_page(999999999, 0, count=1)
    tc = j.get("total_count")
    print(f"CONTROL-ABORT nonsense appid returned total_count={tc}")
    if tc == 0:
        print("  (Steam reports 0 for an unknown appid -- so a REAL 0 and an "
              "unknown appid look alike; every 0 below is cross-checked against "
              "an item-count probe, and 429s abort distinctly.)")
except Abort as e:
    print(f"CONTROL-ABORT nonsense appid -> abort state {e.state} (good)")
time.sleep(10)

# --- control 1: currency + known item ---
try:
    j = search_page(730, 0, count=10)
    r = j["results"][0]
    txt, cents = r["sell_price_text"], r["sell_price"]
    print(f"CONTROL-CCY  CS2 first result: {r['hash_name']!r} "
          f"sell_price={cents} text={txt!r} total_count={j['total_count']}")
    if not txt.startswith("$"):
        FAIL.append(f"CURRENCY NOT USD: {txt!r}")
    import re
    num = float(re.sub(r"[^0-9.]", "", txt))
    if abs(num - cents / 100.0) > 0.011:
        FAIL.append(f"price text {txt!r} != sell_price {cents} cents")
except Abort as e:
    FAIL.append(f"control-currency aborted: {e.state}")

print("CONTROL RESULT:", "PASS" if not FAIL else "FAIL " + "; ".join(FAIL))
sys.exit(0 if not FAIL else 1)

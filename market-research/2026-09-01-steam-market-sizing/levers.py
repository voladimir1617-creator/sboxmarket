"""What the reachable levers actually do to revenue, measured on a real book."""
import json, sys
from decimal import Decimal, ROUND_HALF_UP
from fees import platform_take

def load(path):
    rows, seen = [], set()
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if r["h"] in seen or r["p"] is None or r["n"] is None: continue
        seen.add(r["h"]); rows.append(r)
    return rows

def take_at(price, rate):
    return float((Decimal(str(price)) * Decimal(str(rate)))
                 .quantize(Decimal("0.01"), ROUND_HALF_UP))

def report(path, label):
    rows = load(path)
    L = sum(r["n"] for r in rows)
    print(f"\n=== LEVERS {label} ({L:,} listings) ===")
    print("  TAKE RATE (revenue if the whole book traded once):")
    for rate in (0.02, 0.05, 0.10, 0.15):
        rev = sum(r["n"] * take_at(r["p"]/100.0, rate) for r in rows)
        print(f"    {rate*100:>5.0f}%  ${rev:>12,.2f}   avg/txn ${rev/L:.4f}")
    print("  PRICE FLOOR (share of the 2% revenue that survives, and of listings):")
    base = sum(r["n"] * platform_take(r["p"]/100.0) for r in rows)
    for floor in (0.0, 1.0, 5.0, 10.0, 25.0, 100.0):
        keep = [r for r in rows if r["p"]/100.0 >= floor]
        rev = sum(r["n"] * platform_take(r["p"]/100.0) for r in keep)
        kl  = sum(r["n"] for r in keep)
        print(f"    >= ${floor:>6,.0f}  ${rev:>10,.2f} ({100*rev/base if base else 0:>5.1f}% of rev)"
              f"   {kl:>10,} listings ({100*kl/L:>5.1f}% of book)")
    print("  A $0.30 FIXED card fee against the listing price:")
    under = sum(r["n"] for r in rows if r["p"]/100.0 < 0.30)
    print(f"    listings priced BELOW the $0.30 Stripe fixed fee: {under:,} ({100*under/L:.1f}%)")

if __name__ == "__main__":
    report(sys.argv[1], sys.argv[2])

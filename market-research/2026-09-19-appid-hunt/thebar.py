"""The bar applied to TBH, at the shipped 2% and at Steam's own 15%,
with the 90% bootstrap CI carried through so the verdict is not read off a
point estimate. All inputs MEASURED 2026-09-19 (n=60 turnover sample,
n=902 book census, zero aborts).
"""
import io, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

TXN_DAY = 54175.0            # MEASURED, scaled from 3,055 sampled trades/day
GMV = 5071270.0              # MEASURED point estimate, $/yr
CI = (2168796.0, 8530651.0)  # 90% bootstrap CI
EFF2 = 0.01777               # effective take at nominal 2% after HALF_UP loss


def block(label, rev_yr):
    per = rev_yr / (TXN_DAY * 365.0)
    print(f"  {label}")
    print(f"    revenue at 100% share  ${rev_yr:>12,.0f}/yr = ${rev_yr/12:>9,.0f}/mo")
    print(f"    platform take per trade ${per:.5f}")
    for lbl, cost in (("$500/mo", 6000), ("$2,500/mo", 30000), ("$10,000/mo", 120000)):
        need = (cost / per) / 365.0
        share = 100.0 * need / TXN_DAY
        flag = "  IMPOSSIBLE — exceeds the whole market" if share > 100 else ""
        print(f"      {lbl:<11} needs {need:>10,.0f} trades/day = {share:>7.2f}% of the market{flag}")
    print()


print(f"TBH: Task Bar Hero (3678970) — MEASURED 2026-09-19")
print(f"  {TXN_DAY:,.0f} trades/day   GMV ${GMV:,.0f}/yr (90% CI ${CI[0]:,.0f}-${CI[1]:,.0f})")
print()
block("SHIPPED 2% take (effective 1.777% after HALF_UP rounds sub-$0.25 to $0.00)",
      GMV * EFF2)
block("Steam's own 15% — an upper bound on any take rate", GMV * 0.15)

print("  90% CI sensitivity, shipped 2%:")
for nm, g in (("CI low", CI[0]), ("point", GMV), ("CI high", CI[1])):
    r = g * EFF2
    print(f"    {nm:<8} GMV ${g:>12,.0f} -> ${r:>9,.0f}/yr = ${r/12:>8,.0f}/mo at 100% share"
          f"   ; $500/mo needs {100*6000/r:>6.2f}% share")
print()
print("  => the $500/mo bar is cleared at 100% share across the ENTIRE CI.")

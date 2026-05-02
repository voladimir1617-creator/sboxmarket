/loop /grind

BOSS QA — CYCLE 6/7. Verified-closed at commit 5ec34d5 (huge batch, nice work):

✅ G1+G2+G3 / G6-G10 / H1-H5 / M3+M4 / I1+I7+I8 / S1-S5 / D1-D5 / F1+F2 / B1 — all visually verified post-5ec34d5.

═══════════════════════════════════════════════════
P0 — STILL OPEN (stop ignoring these)
═══════════════════════════════════════════════════

[M1m — /item/1 MOBILE PRICE BLOCK STILL MISSING]
This is the FIFTH lap I'm flagging this. Mobile shot of /item/1 still shows ONLY image → breadcrumb → "Out of Stock" / "Sign in to make offer" / Steam button. NO price card, NO Floor Price label, NO Steam reference, NO 30D delta, NO Supply, NO chart, NO listings, NO similar-items. Without those the mobile item page is a dead picture viewer.

ROOT-CAUSE THIS NOW:
```bash
cd /c/Users/WW/Desktop/sboxmarket
# Check if the modal-stats block is wrapped in a media query or conditional
grep -B2 -A5 "modal-stats" src/main/resources/static/css/design.css | grep -B5 -A2 "display:\s*none\|display:none" | head -40
grep -B2 -A5 "modal-stats\|modal-stat-box" src/main/resources/static/js/modals.js | grep -B2 -A2 "matchMedia\|innerWidth\|isMobile" | head -40
# Look for visibility: hidden or any height:0 trick
grep -B2 -A5 "@media.*max-width.*600\|@media.*max-width.*768" src/main/resources/static/css/design.css | head -80
```

Likely culprit: a media-query rule somewhere setting `display:none` on `.modal-stats` or its parent at narrow widths. Find it, kill it. The mobile layout MUST stack: image → STAT BLOCK → tabs/chart → listings → similar items → action bar.

After fix: `chrome --headless --window-size=390,852 --user-agent='iPhone' --virtual-time-budget=5000 --dump-dom http://localhost:8082/item/1 | grep -c 'FLOOR PRICE\|Listing price\|Floor Price'` — must be > 0.

[M3m — /db MOBILE TABLE — STILL UNFIXED]
Mobile shot still shows `#1 / #2 / #3 / #4 (Standard)` — names + prices off-screen right. Switch to stacked card layout when `<= 600px` (CSS in BOSS_PROMPT_5.md).

[M2m — /stall/1 MOBILE H1 OVERFLOW]
"Chib skinbox.market's Stall" still touches right edge. Add `clamp() + word-break + overflow-wrap` per cycle 5.

[N1 — FOOTER "Discord — coming soon" + "X / Twitter — coming soon"]
Just DELETE them. They've been there 5 cycles.

═══════════════════════════════════════════════════
NEW FINDINGS (BOSS WIDER SWEEP)
═══════════════════════════════════════════════════

[N2 — /changelog.html lacks SkinBox top-nav + footer]
Standalone server-rendered page. Currently shows just "← BACK TO SKINBOX" + Changelog content + nothing else. Premium sites have CONSISTENT chrome on EVERY page. Either:
  (a) Wrap in the same site-header + site-footer markup as the SPA (server-render the chrome).
  (b) Convert to an SPA route `/changelog` that mounts inside the App with full chrome.
File: `src/main/resources/static/changelog.html`. Pick (a) — copy the `<nav>` + `<footer>` markup from the index served HTML and inline-render it. Verify shot.

[N3 — /affiliate "Requirements" section: improve hierarchy]
The 5-platform requirements list (YouTube 5,000 / X 5,000 / TikTok 5,000 / Instagram 5,000 / Website 5,000) is a flat row of icons + numbers. A premium build would use a stat-card grid like /stall/1 stat-grid: each platform as its own card with icon + bold number + label.

[N4 — Top-right user picker: USD / EN / 🔔 / 🛒 / Sign in]
Still 5 controls in one cluster on every page. Check spacing per BOSS_PROMPT_3 G2 — 12px between groups, 4px within. Verify the LIVE pill DOES sit 12px clear.

═══════════════════════════════════════════════════
P0 — DEEP QA (Q1, Q2, Q4 — third lap I'm asking)
═══════════════════════════════════════════════════

[Q1 — Click-scan via puppeteer]
Detailed JS in BOSS_PROMPT_5. Output to `_qa_boss/cycle_6/click-scan.json`. ZERO no-op buttons + ZERO console errors expected.

[Q2 — Console / network errors zero-tolerance]
Same routes. 0 console.error, 0 console.warn, 0 4xx/5xx responses (except `/api/users/me` 401 for anon). Save to `_qa_boss/cycle_6/console-network.txt`.

[Q4 — Focus rings]
`:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }` site-wide.

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Order: M1m → M3m → M2m → N1 → N2 → Q1 → Q2 → Q4 → N3 → N4.
2. Build + restart after each fix. `bash deploy/run-local.sh`.
3. After-shot to `_qa_boss/cycle_6/<id>.png`.
4. Commit per logical batch. `Boss QA cycle 6: M1m mobile item price block fixed`.
5. NO ScheduleWakeup. NO ending. Boss is asleep.
6. After this list, fresh QA walk again — find the next 10 issues yourself.

GO.

/loop /grind

BOSS QA — CYCLE 4. Verified-closed (boss confirmed via fresh DOM + screenshots in `_qa_boss/cycle_3/`):

✅ G1 cookie banner / G2 LIVE badge / G3 footer breathing / H1 float bar / H2 parallax stack-back / H4 discount chips / H5 ghost button / M1 stat strip / M2 lowest-mileage gone / M3 toolbar hierarchy / M4 results-meta container / D1 dup pill / D2 sort labels / D3 OFF-Market→Scarce / D4 row spacing / D5 Watch column / G6 settings gated / G7 Test→Send test notification / G8 wallet gated / G9 wallet copy / G10 watchlist big icon / F1 faq slim banner / F2 help inline FAQs / S1 stall void filled / S2 stall stat-card grid / S3 (?) gone

NICE WORK. Bar raised — boss says it must look BETTER THAN AMAZON now. Open list:

═══════════════════════════════════════════════════
P0 — STILL OPEN (B1, mobile, deep QA)
═══════════════════════════════════════════════════

[B1 — /loadout/1 STILL RETURNS "Loadout not found" — 4TH LAP UNFIXED]
Boss is annoyed. DOM dump at `_qa_boss/cycle_2/dom_loadout_1.html` STILL has `<h2>Loadout not found</h2>`. This is the LAST P0 boss-blocker. Step-by-step (no excuses):

```bash
cd /c/Users/WW/Desktop/sboxmarket
# 1. Inspect what's seeded
grep -nE "Loadout|loadout" src/main/groovy/com/sboxmarket/service/SeedService.groovy | head -40
# 2. Inspect controller's lookup
grep -nE "id|public" src/main/groovy/com/sboxmarket/controller/LoadoutController.groovy | head -40
# 3. Inspect repository
cat src/main/groovy/com/sboxmarket/repository/LoadoutRepository.groovy
# 4. Hit the API
curl -i http://localhost:8082/api/loadouts/1
curl -i http://localhost:8082/api/loadouts
```

Then either:
  (a) Add 5 PUBLIC seeded loadouts in SeedService (id = 1..5, owner = first seeded user, public=true, items = 4 items each from existing seeded items).
  (b) Fix LoadoutController if the route handler is rejecting public loadouts.

Verify by:
```
curl -sS http://localhost:8082/api/loadouts/1 | jq '.'  # must return 200 + body
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --window-size=1920,1080 --virtual-time-budget=5000 --screenshot=/c/Users/WW/Desktop/sboxmarket/_qa_boss/after/B1.png http://localhost:8082/loadout/1
```
After-shot must show populated loadout with items, owner, like count — NOT "Loadout not found".

[M1m — /item/1 MOBILE: PRICE BLOCK + LISTINGS COMPLETELY MISSING]
Mobile shot at `_qa_boss/cycle_2/m04-item.png` shows ONLY: image → breadcrumb → "Out of Stock" + "Sign in to make offer" + Steam button. The entire price card ($17.45 / Floor Price / Steam Price / 30D Change / Supply), price-history chart, active-listings section, and "You might also like" rail are GONE on mobile. This is the actual product page, not a "look at the picture" page. Investigate the responsive CSS — likely a `@media (max-width: 600px) { display: none }` that's too aggressive. Open `src/main/resources/static/css/design.css`, search for the modal-stats / modal-stat-box class with mobile breakpoint hiding it. Either show in stacked layout below image OR confirm the JSX is conditionally not rendering it for mobile.

[M2m — /stall/1 MOBILE: H1 overflows the right edge]
"Chib skinbox.market's Stall" gets cut at "...'s Stall" past the 390px viewport. Add `word-break: break-word; hyphens: auto;` to .stall-page-h1, OR truncate the username past N chars with ellipsis, OR scale font-size down at <= 420px.

[M3m — /db MOBILE: table only shows row numbers, item names cut off]
Mobile shot `m03-database.png` shows just `#1 / #2 / #3 / #4 (Standard)`. Item name + category + supply + price are off-screen right (need horizontal scroll to see them). Switch to a stacked card layout for `<= 600px`: each row = card with image left (48px), name + meta stacked right, price bottom-right.

[N1 — Footer "Discord — coming soon" + "X / Twitter — coming soon" — NOT FIXED]
DOM still has these on every page (footer is global). Either DELETE them OR wire them up. Pick one — not both. If wiring: Discord invite is `https://discord.gg/SPAwYX7bmP` (from F.E.A.R. project memory; if unrelated, just delete). For X/Twitter, delete since you don't have a real account.

═══════════════════════════════════════════════════
P0 — DEEP QA (Q1-Q4)
═══════════════════════════════════════════════════

[Q1 — CLICK SCAN — 0 dead handlers expected]
Use the existing script:
```bash
node /c/Users/WW/Documents/s\&box\ projects/labyrinth/.claude/watchdog/qa-click-scan.js \
  --base http://localhost:8082 \
  --routes /,/market,/db,/help,/faq,/cart,/watchlist,/wallet,/sell,/settings,/profile/personal,/item/1,/stall/1,/loadout/1,/item/missing,/stall/missing,/loadout/missing \
  --out /c/Users/WW/Desktop/sboxmarket/_qa_boss/cycle_4/click-scan.json
```
If that script doesn't exist or doesn't accept those args, write a new one in `_qa_boss/cycle_4/click-scan.js` using puppeteer (already installed in the project per node_modules check). Visit each route, click every `<button>`, `<a>`, `[role="button"]`, capture: did it navigate, modal-open, no-op, or throw a console error. Report any "no-op" or "console error" rows as P0 follow-up bugs.

[Q2 — CONSOLE / NETWORK ERRORS — 0 expected]
Same routes as Q1. Capture every `console.error`, `console.warn`, and HTTP 4xx/5xx response. Save to `_qa_boss/cycle_4/console-network.txt`. Acceptance: 0 console errors, 0 console warnings, 0 4xx/5xx network responses on those 17 routes.

[Q3 — MOBILE DEEP-CHECK]
Beyond M1m/M2m/M3m, scroll each mobile route to the bottom and:
  (a) Confirm no horizontal overflow (`document.documentElement.scrollWidth === window.innerWidth`).
  (b) Confirm the burger nav opens + closes cleanly.
  (c) Confirm the top-bar currency/lang switchers don't blow out the right edge.
  (d) Confirm the cookie pill 🍪 doesn't overlap the bottom CTA on /cart, /wallet, /sell, /settings.

[Q4 — KEYBOARD FOCUS RINGS]
Tab through `/market` and `/item/1`. Every interactive element must show a visible focus ring (`outline` or `box-shadow` ≥2px). Add `:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }` site-wide if absent. Verify with:
```js
// in headless browser
document.querySelectorAll('button, a, [role="button"], input, select, textarea')
  .forEach(el => { el.focus(); console.log(el.tagName, getComputedStyle(el).outlineWidth); });
```
No `0px` outlines on focused interactive elements.

═══════════════════════════════════════════════════
P1 — POLISH NEW FINDINGS (boss eyeballed cycle-3 shots)
═══════════════════════════════════════════════════

[P1.1 — /home rail tabs MAY NOT have segmented-control treatment]
Boss's cycle-3 home shot still shows "Top Deals · Newest Items · Unique Items" as plain text with thin underline on active. If the segmented-control rule landed (per agent 2's report), it's not visible. Verify in browser dev tools that `.csfloat-home-rail-tab.active` has `background: var(--ink)` and `color: var(--bg)`. If not, the CSS specificity is being overridden — promote with selector specificity or `!important` in the new rule.

[P1.2 — /home hero stack-back may not be visible enough]
Cycle-3 home shot only shows the front Cardboard King hero card. The 80%/60%/40% opacity stack should be visible behind it with rotation + drop shadow. If invisible at 1920×1080, consider increasing back-card opacity to 50%/35%/20% with `filter: blur(2px)` so they read as deliberate parallax cards rather than render bugs.

[P1.3 — /cart "Trending right now" rail uses old card design]
Cart cards in mobile shot `m08-cart.png` show items but don't follow the new design rules — verify the cart trending rail uses the same card component as /market.

[P1.4 — /faq inline FAQs land but search-input contrast is weak]
The "Search FAQs..." placeholder is barely visible against the dark background. Bump placeholder color to `var(--ink-3)` minimum (vs current near-`var(--ink-4)`).

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Order: B1 → M1m → M2m → M3m → N1 → P1.1 → P1.2 → P1.3 → P1.4 → Q1 → Q2 → Q3 → Q4.
2. Build + restart after each batch. `bash deploy/run-local.sh`. Check `/api/health`.
3. Re-shot to `_qa_boss/cycle_4/<id>.png`. DOM-grep verify pattern returns 0 hits.
4. Commit per logical batch. `Boss QA cycle 4: B1 seed + LoadoutController fix`, etc.
5. NO ScheduleWakeup. Boss is asleep. Watchdog re-pokes if you idle past 120s.
6. After this list is empty, do a complete fresh QA walk yourself — desktop AND mobile — find the next 10 issues. Boss bar is "better than Amazon".

GO.

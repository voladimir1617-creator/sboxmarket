/loop /grind

BOSS QA — CYCLE 11. Don't stop. Site is GOOD but not yet ELITE.

Boss verified at HEAD 061de41:
✅ All cycles 1-10 IDs shipped. Q1/Q2/Q3 scans clean. 11 commits / 117k insertions / ~63 IDs.

Bar = "better than Amazon, $10M / 10 years built". This still has a polish ceiling. Walk every route ONE MORE time and find the next 5 polish gaps. Anywhere.

═══════════════════════════════════════════════════
MICRO-POLISH HUNT (find at least 5)
═══════════════════════════════════════════════════

For each route, ask: would Apple ship this exact pixel? If no, identify the gap. Examples of micro-polish gaps to search for:

- **Loading states** — what does each route show in the first 50ms before content paints? Are skeletons / shimmers in place? On slow 3G the first paint should NOT be a blank dark screen.
- **Empty-state illustrations** — every empty-state ("Nothing on your watchlist", "0 active listings", "No reviews yet", "No completed sales yet") should have a custom branded illustration, not just a Material icon. Even a simple SVG line-drawing tied to the page topic feels $10M.
- **Hover states** — every clickable should have a hover state distinct from focus. Cards should subtly lift (translateY -1px + shadow). Buttons should brighten. Test by hovering with the mouse and confirming visible feedback.
- **Active states** — pressed state should depress (translateY +1px + shadow tighter).
- **Transitions** — every state change should be animated 100-200ms ease-out. Find ANY snap transition (instant color/scale change with no transition) and add one.
- **Typographic hierarchy** — headers, sub-headers, body. Are weights, sizes, and tracking consistent? Use the existing scale tokens.
- **Number formatting** — every price uses `$X,XXX.XX`. Every count uses `1,234`. Every percent uses `+X.X%` with sign. Check for naked numbers.
- **Icon alignment** — Material Symbols Rounded icons should baseline-align with surrounding text. If any sit too high or low, fix the `vertical-align` or wrap in flex.
- **Image quality** — every Steam-CDN image has a fallback skeleton if the load fails. The SkinBox-served images should be sharp at 2x DPR.
- **Mobile tap targets** — minimum 44×44px per Apple HIG. Check the burger menu, currency dropdown, language dropdown, watch-star, cart-plus, sort-toggle. Any control under 44px on mobile is a fail.
- **Color tokens** — anywhere you find a hex literal in the CSS that's not in the design tokens, flag and replace.

═══════════════════════════════════════════════════
DEEP CHECKS
═══════════════════════════════════════════════════

[A11Y — axe-core scan]
Use puppeteer + `@axe-core/puppeteer` (npm install if needed):
```js
const { AxePuppeteer } = require('@axe-core/puppeteer');
// then: const results = await new AxePuppeteer(page).analyze();
```
Save violations to `_qa_boss/cycle_11/axe.json`. Fix any "critical" or "serious" rule violation. Don't worry about "minor" / "moderate".

[PERF — Lighthouse-like check]
Visit `/`, `/market`, `/item/1`, `/stall/1` and capture:
- DOM nodes (must be < 1500 per route)
- Total transferred bytes (compute via Performance API or response body sizes)
- Time to first contentful paint (`performance.getEntriesByType('paint')`)
Save to `_qa_boss/cycle_11/perf.json`. Flag anything > 2s FCP on localhost.

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Don't stop. The boss said NEVER STOP. Even if you think the site is done, find 5 more issues.
2. Build + restart per batch.
3. Commit per logical batch.
4. After-shot to `_qa_boss/cycle_11/<id>.png`.
5. NO ScheduleWakeup. Watchdog will re-poke if you idle past 120s.
6. After cycle 11 is empty, do cycle 12. Then 13. Until the boss explicitly says stop.

GO HARDER.

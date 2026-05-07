# CODEX HANDOFF — sboxmarket csfloat 1:1 parity

You're picking up an in-progress workflow. Read this in full before doing anything.

## What's running RIGHT NOW

- **Site is live** at `http://localhost:8082` (Spring Boot, NOT Docker — Docker is broken on this machine)
- Java process running `java -Dserver.port=8082 -jar build/libs/sboxmarket-1.0.0.jar`
- Logs at `/tmp/sbox.log` (or `C:\Users\WW\AppData\Local\Temp` equivalent)
- HEAD is at `b358aa7` or later — `cd C:/Users/WW/Desktop/sboxmarket && git log --oneline -1` to confirm
- ~245 csfloat-1:1 PARITY ships already landed in `src/main/resources/static/css/design.css` — confirm with `grep -c "CSFLOAT-1:1 PARITY ship" src/main/resources/static/css/design.css`

## Mission (one sentence)

Make `http://localhost:8082` a **PIXEL-TO-PIXEL exact copy of `https://csfloat.com/`** by measuring real values via `getComputedStyle` and overriding sboxmarket CSS with `!important`. **NO creative redesign.** **NO typography polish.** Pure measure-and-match.

## Operator's hard rules (verbatim quotes)

- "OLD DESIGN WAS FINE AGAIN WE ARE AIMING FOR CSFLOAT EXACT 1 to 1 COPY"
- "I WASN'T ASKING YOU TO GET NEW DESIGN I TOLD YOU WE NEED A COPY 1 TO 1 PIXEL TO PIXEL TECHNOLOGY TO TECHNOLOGY"
- "ONE THING I NOTICE YOU START REDESIGNING WHICH I DON'T LIKE MUCH" — therefore: NO `HIERARCHY` ships, NO `DESIGN-POLISH` ships, NO `RHYTHM` ships, NO creative font/size/color decisions
- "RUN AT LEAST 4 AGENTS AT THE TIME" — keep 4-7 sub-agents in flight always
- "NEVER STOP" — the loop continues until the operator interrupts
- "YOU HAVE FULL ACCESS TO MY ENTIRE PC" — drive directly via Bash + playwright

## The exact pattern per ship (use this verbatim)

1. Open csfloat target via playwright: `mcp__playwright__browser_navigate https://csfloat.com/<route>`
2. Measure target element:
   ```
   mcp__playwright__browser_evaluate({
     function: () => {
       const el = document.querySelector('<csfloat-selector>');
       const cs = getComputedStyle(el);
       const r = el.getBoundingClientRect();
       return { fs: cs.fontSize, fw: cs.fontWeight, lh: cs.lineHeight, ls: cs.letterSpacing,
                color: cs.color, bg: cs.backgroundColor, pad: cs.padding, br: cs.borderRadius,
                w: Math.round(r.width), h: Math.round(r.height) };
     }
   })
   ```
3. Open localhost equivalent: `mcp__playwright__browser_navigate http://localhost:8082/<route>`
4. Measure equivalent element with same script
5. Append CSS override at END of `C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css`:
   ```css
   /* CSFLOAT-1:1 PARITY ship #<N> (<surface>) — measured: csfloat <X>, sbox was <Y> */
   .selector {
     property: <csfloat-exact-value> !important;
     ...
   }
   /* END CSFLOAT-1:1 PARITY ship #<N> */
   ```
6. Build + restart cycle (REQUIRED — Spring Boot serves CSS from inside the jar, not from disk):
   ```
   cd /c/Users/WW/Desktop/sboxmarket && ./gradlew bootJar -x test
   powershell.exe -NoProfile -Command "Stop-Process -Name java -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2"
   nohup java -Dserver.port=8082 -jar build/libs/sboxmarket-1.0.0.jar > /tmp/sbox.log 2>&1 & disown
   ```
7. Wait until live: `until curl -sf http://localhost:8082/ -o /dev/null; do sleep 4; done`
8. Verify ship is in served CSS: `curl -s http://localhost:8082/css/design.css | grep "ship #<N>"`
9. Re-measure on localhost. Should now match csfloat.
10. Commit: `cd /c/Users/WW/Desktop/sboxmarket && git add -A && git commit -m "csfloat-1:1 ship #<N> — <surface>: <change> (csfloat exact)"`

## Canonical csfloat tokens already established (use these for new ships)

- Body BG: `rgb(21, 23, 28)`
- Panel BG: `rgb(27, 29, 36)`
- Brand accent: `rgb(35, 123, 255)`
- Secondary text: `rgb(158, 167, 177)`
- Hairline divider: `rgba(255, 255, 255, 0.06)`
- Body font: `Roboto, "Helvetica Neue", sans-serif`
- Mono font: `"Roboto Mono", monospace`
- Body padding (horizontal gutter): `0 60px`
- Body font-size: `16px`
- Hero title: `40px / 500 / normal lh / normal letter-spacing`
- Hero subtitle: `16px / 500 / 24px lh / rgb(158,167,177)`
- Primary button: `40px tall / pad 13px 30px / fw 700 / radius 8px / bg rgb(35,123,255)`
- Card: `rgb(27,29,36) bg / 12px radius / 250x387 default`
- Material elevation 2 shadow (cards, modals): `rgba(0,0,0,.2) 0 3px 1px -2px, rgba(0,0,0,.14) 0 2px 2px 0, rgba(0,0,0,.12) 0 1px 5px 0`
- Standard transition: `cubic-bezier(0.4, 0, 0.2, 1)` at 150-300ms
- Hairline border on chips: `rgba(193, 206, 255, 0.04-0.12)`

## Done lanes (don't re-do these — pick a fresh one)

Hero, Top nav, Footer, Item page, Home below-hero, Market grid card, Forms/inputs/modals, Watchlist/profile/wallet, DB/loadout, Sell/notif/buyorders, Mobile/tablet, Hover/focus/click, Admin/settings/changelog, Toasts/skeletons/empty states, Global typography + colors, Signed-in surfaces, Tooltips/popovers/drawers, Tabs/accordions/charts, Icons/chips/badges, Cart/help/legal/404, Shadows/elevations/dividers, Wallet flows, Scrollbar/scroll behaviors, Transitions/animations, Hero card preview, Trades/offers/inventory, Cookie banner/promo strips, Filter sidebar, Search autocomplete, Final visual sweeps, Stat panels.

## Open lanes (good candidates for next ships)

- Inventory item picker (sell page) — granular grid card detail
- Auction listings page (`/auction/<id>` if exists)
- Admin dashboard internals (when signed in as staff)
- /changelog content rows
- /faq accordion expand/collapse
- Onboarding modal (first-time user)
- Empty cart state styling
- Profile listings tab (sell-it grid)
- Profile reviews tab
- Trade-URL setup walkthrough modal
- Promo banner (if any)
- Maintenance / upgrade-required page

## How to spawn parallel sub-agents

Use the Agent tool with `subagent_type: general-purpose` and `run_in_background: true`. Always include the EXACT pattern above in the prompt. Always specify the lane scope (so agents don't collide). Always require 5+ ships per agent before reporting. Always require !important overrides appended at file END.

## Build broke / site down

If `curl http://localhost:8082/` returns 000:
1. `tail -50 /tmp/sbox.log` to see the error
2. If H2 schema corruption: `rm -f C:/Users/WW/Desktop/sboxmarket/data/sboxmarket.*` then restart
3. If port conflict: `powershell.exe -Command "netstat -ano | findstr :8082"` — kill the holder
4. Restart: `cd /c/Users/WW/Desktop/sboxmarket && nohup java -Dserver.port=8082 -jar build/libs/sboxmarket-1.0.0.jar > /tmp/sbox.log 2>&1 & disown`

## DO NOT

- Use Docker (broken — `dockerInference` socket file is corrupted at file-system level, needs reboot to fix)
- Make creative typography choices (no font-size bumps, no weight bumps, no color invention)
- Hide content (e.g. don't `display:none` real product features even if csfloat doesn't have them)
- Modify token definitions at top of design.css (--bg, --ink, etc.) — append override blocks at file END only
- Delete or rewrite anyone else's CSS — append-only via overrides

## DO

- Open both csfloat AND localhost in playwright, measure both, name the exact pixel/color difference, fix it
- Stack 5+ ships before reporting
- Commit each ship separately with `csfloat-1:1 ship #N — ...` message
- Maintain 4-7 sub-agents in flight at all times
- Keep going forever until the operator interrupts

## Last command before limits hit (so you know where the loop was)

The main thread had just spawned 7 fresh agents covering: stat panels, share/bargain modals (v2), dropdowns/selects (v2), pagination (v2), misc edges (v2), card details (v2), and keyboard-nav focus-rings. Verify which ones completed and which are still running, then keep the rotation going.

GOOD LUCK. The user is reading this looking for proof that you're going to keep working. Don't disappoint them.

# /grind master polish loop — sboxmarket non-stop ship grinder

You are the worker. Operator's directive: "WORK NON STOP". Stack ships back-to-back, never ScheduleWakeup, never end the response. The site goes live to a paying customer; the polish bar is "fully functional + sellable + premium feel".

## Three queues — work them in this priority order

1. **`_qa_boss/BOSS_PROMPT_LOADOUT_EMPTY_CARDS.md`** — P1, single-shot. Mobile/desktop loadout cards have ~200px of empty void. Fix the slot-thumbnail rendering + compact card chrome. Ship this if you haven't already.

2. **`_qa_boss/BOSS_PROMPT_CONTINUOUS_POLISH.md`** — design polish queue (focus rings, active-tab indicator, card hover dedupe, touch hover, reduced-motion, hairline border audit, font-size scale audit, padding scale audit, skeleton-loader sweep, LIVE pulse calm-down). Ships #1-#4 already landed (`836de99`, `0ffb272`, `07cb641`, `599873d`). Continue from #5.

3. **`_qa_boss/BOSS_PROMPT_UX_POLISH_QUEUE.md`** — UX/copy/behavior queue (mobile item CTA dedup, recently-viewed rail count, anon-empty-state copy variation, affiliate tile labels, db row click target, saved-search empty state, auction countdown, cookie banner second-visit, 404 footer lock, loadout-create CTA, mobile bottom-nav, signed-out price obfuscation toggle, number formatting, keyboard shortcuts overlay).

## Standing rules (do not violate)

- **Mono-primary palette**: blue is reserved for `.buy-btn`, live LEDs, auction countdown, active-tab underlines, Stripe badge. Hover/border tints use `--ink` / `--line-2`.
- **No `!important`** unless absolutely required for cascade-override of a stubborn earlier rule.
- **Strict scales**: font 10/11/12/13/14/15/18/22/28/40/60. Padding 8/10/12/14/16/18/20/22/24/28/32. Borders 1px hairline.
- **Body-text anchors keep underline** — already shipped at commit `5bc66f8` + `5affc95`. Don't regress.
- **Two `:root` blocks in design.css** at lines ~1 + ~31960 — edit BOTH if bumping tokens.

## After every ship

1. `docker build -t sbox-app:latest . && bash deploy/run-local.sh`
2. Wait until `/api/health` is 200.
3. Public probe: `curl -s -o /dev/null -w '%{http_code}\n' https://skinbox.market/` → 200.
4. `docker logs sbox-app --since 2m | grep -iE 'error|exception|0x00' | grep -v EmailService` → empty or noise only.
5. `git add` + `git commit -m "Boss QA cycle 31 ship #N: <one-line>"` with body explaining what + why.
6. **Immediately start ship N+1.** No "what should I do next?". No summary. Just commit and continue.

## Self-driving rule

When the queues run dry: take a screenshot of every route at desktop + mobile, compare against the $10M-marketplace bar (Apple-website polish, CSFloat-1:1 functional surface, no dead ends, no broken English, no off-scale typography), find the next gap, queue it, ship it. The polish ceiling is infinite. NEVER stop.

Operator verbatim: "MAKE SO I CAN SELL THIS WEBSITE TO THE CUSTOMER AS FULLY FUNCTIONAL". Until that bar is met for every route × viewport, you keep grinding.

GO.

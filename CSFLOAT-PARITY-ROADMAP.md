# csfloat parity roadmap (live diff, 2026-05 → updated 2026-06-02)

Captured by driving csfloat.com/search and localhost:8082/market side-by-side at
the same viewport. What actually differs, highest-impact first.

> See `production_checklist.md` for the full DONE / REMAINING ledger (incl. backend
> + ops). This file is the **visual/UX parity** view.

## STATUS @ 2026-06-02

✅ **All six 2026-06-01 P1s RESOLVED — re-verified live this session (1440 + 390):**
- `/db`, `/loadout`, `/help`, `/stall` are **full-bleed pages** (`.full-page-mode`,
  e.g. /db main = 1310px ≈ 91% of 1440, 0 overflow) — no longer centered modals.
- **Home hero is above-fold** (WAVE-INT2 padding/art-height pull-up; verified).
- **DB thumbnails** render real Steam item art (38/39 seed items, all surfaces).
- **Price-history chart** plots a solid csfloat-blue line (ad5ca14) + recent sales.
- **`/database`** → 302 redirect to `/db` (SEO alias in router.js) — not a 404.
- **Profile leads with a Listings tab.**

✅ **Also shipped earlier (still holding):**
- csfloat card anatomy (`gc-*`), card-signal dedup, 5-up grid @1440, Roboto,
  left filter rail @ desktop (350px — MEASURED from csfloat .advanced-search),
  mobile 2-up + bottom nav (Market/Database/Sell/Watchlist/Profile), cart layout.

✅ **2026-06-02 session adds:**
- **Auctions biddable on mixed listings** — bid panel binds to the AUCTION listing
  explicitly (was `listings[0]`, hidden by a cheaper BUY_NOW).
- **Coherent auction bid history** — `SeedService.seedAuctionBids` backfills n Bid
  rows per auction (bidCount == rows, max == currentBid, WINNING/OUTBID); read
  endpoint still anonymises third-party bidders to "Bidder #N" by design.
- **Single-item buy confirm step** (csfloat-style review dialog; frozen fee model,
  Trade-Protection line display-only, total == listing price).
- **Cold deep-link /item/:id guard** — seed `modalLoading` from the URL so the
  first paint is the spinner, not a one-frame "Item not found" flash.
- **Empty-state copy** — removed a literal `$X` placeholder.
- Home featured rail uses the canonical `GridCard`.

🔬 **Measured-value discipline (do NOT "fix" these — they're correct):**
Three audit/agent suggestions this session were caught as would-be REGRESSIONS by
cross-checking in-repo `measured:` ship comments before acting:
- **Card radius = 4px** (not 12px). wave-147c measured the inner `.item-card` div;
  the 12px belongs to the transparent mat-mdc host, not the visible surface.
- **Left rail = 350px** (ship #40: "csfloat measured .advanced-search 350px").
- **Card hover lift = `translateY(-15px)`** (ship #6900 measured; z-index:1 floats
  it above neighbours) — consistent with the `-15px` focus-visible state.
> Rule: trust in-repo `measured:` citations over roadmap/audit guesses; an audit is
> good at finding a *difference* but often wrong about which direction is csfloat.

🔧 **Remaining (low-impact polish / judgment calls — verify against csfloat live
before acting):** card "1 listing" supply fallback; sort-dropdown optgroups;
~20 lines of dead `.grid-card:hover` cascade (safe-removal plan in hand, deferred
— high-downside/low-upside in a 199k-line file). Money path audited SAFE end-to-end
(withdraw / escrow-delivery / buy-trade-release — Wave 148, all concerns SAFE).

(Backend/ops gaps now largely closed — escrow sweeper, error-path leak gate,
multi-pod webhook idempotency, structured JSON logging all shipped; remaining
go-live items are operator-gated: live Steam bot creds + Stripe Connect activation
+ prod-jar-behind-tunnel. Tracked in `production_checklist.md`.)

---

## 1. LEFT FILTER RAIL (the defining csfloat look) — ✅ FIXED (9c54f2a, 2026-05-30)
- The rail was BUILT all along (app.js:6657 `<aside class="sidebar">` with Price
  Range / Availability / Rarity / Quick Filters / Sort). The bug: CSS ships #4201
  (1280-1599px) and #4202 (1024-1279px) hid it with `display:none !important`,
  which killed it across EVERY common laptop width (1366/1440/1536). Fixed by a
  final EOF block (#5500) re-asserting the 2-col `[300px rail | feed]` layout at
  >=1100px, feed auto-fill minmax(220px,1fr). Verified live at 1440/1280/1024/390,
  0 overflow, 0 console errors. Original (now-historical) analysis below.

### (historical) ORIGINAL ANALYSIS — BIGGEST GAP
- **csfloat:** persistent left sidebar — Price (min/max + slider), Wear, Special
  (StatTrak/Souvenir), Patterns, Search — grid starts at the very top-right, ~10
  cards visible immediately.
- **ours:** all filters crammed into a horizontal top toolbar; no rail exists
  (despite code comments at app.js:7149-7151 referencing "the sidebar"). The grid
  starts ~625px down.
- **Fix (substantial JSX restructure of the market view, ~app.js:7000-7260):**
  wrap the results region in a 2-col grid `.market-layout { display:grid;
  grid-template-columns: 248px 1fr; gap:20px }`. Move into a left `<aside
  class="filter-rail">`: Price Range (min/max inputs already exist), Category
  (the All/Hats/Jackets… tabs → vertical list), Rarity (Limited/Off-market/
  Standard), Listing type (All/Buy Now/Auction), Min discount. Keep search +
  sort + view-toggle in a slim top bar above the grid. Collapse to a top sheet
  under ~900px (mobile). s&box has NO float/wear, so omit Wear/Patterns/Special.
- Partial step already shipped (0bbf426): compacted the market-stats strip so the
  grid rises ~30px (0→4 cards in first viewport). Real fix is the rail.

## 2. MARKET-STATS PANEL placement
- csfloat /search has NO market-wide stats panel above the grid. Ours shows a
  4-tile "Active Listings / 7D Volume / Lowest / Last Sale" panel that eats ~110px.
- Option: move it into the left rail footer, or behind a collapsible "Market
  stats" disclosure, so the grid is grid-first like csfloat.

## 3. Card structure — ALREADY CLOSE (faithful s&box adaptation)
- csfloat card: name · wear/phase · price · discount% · float bar · paint seed ·
  watcher · online · Buy now/Bargain on hover.
- ours: name · category · online · price · steam-ref · watcher · rarity · supply
  bar · Buy/cart on hover. Correct adaptation — s&box has no float, so the supply
  bar replaces the float bar. No action needed beyond the rail context.

## Verified-equivalent (no action)
Nav (Market/Database/Loadout(Lab)/Help vs Market/Database/Loadout/Tools), USD/lang
pickers, Sign-in-through-Steam CTA, category chips, sort control, grid density,
dark-premium palette, hover Buy/cart actions, watchlist ♥, rarity color stripe.

## Notes for the implementer
- The market view render is app.js ~7000-7260 (toolbar, active-filter chips,
  MarketStatsStrip at 7221, results-meta at 7236, grid at 7252+).
- All filter STATE (search/category/rarity/min/maxPrice/minDiscountPct/
  listingType/dealsOnly/newOnly/affordableOnly) already exists with setters and
  URL-sync — the rail is a re-layout of existing controls, not new logic.
- design.css market classes: .market-stats-strip (3194), .market-stat (3206),
  .toolbar, .csfloat-subnav-tab. Add .market-layout + .filter-rail there.
- Verify after: cards visible in first viewport at 1440×900, no horizontal
  overflow at 390px, 0 console errors, full suite green.

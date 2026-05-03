# /grind UX + copy + behavior polish queue (sboxmarket)

Operator wants this site fully sellable to a customer. The first polish queue (`BOSS_PROMPT_CONTINUOUS_POLISH.md`) is design-focused. This is the UX/copy/behavior queue. Same standing rules: never ScheduleWakeup, never end the response, ship → screenshot → ship next.

## Queue — work top-to-bottom

### 1. Mobile `/item/<id>` action stack — ONE primary CTA, not four

`_qa_boss/cycle_qa_after_2_ships/mobile_item_3.png` shows 4 sign-in CTAs on one screen:
- Top: blue full-width "Sign in to buy · $4.07"
- Mid-page: ANOTHER blue full-width "Sign in to buy · $4.07" (duplicate)
- "Sign in to make offer" outlined
- "Sign in to bargain" link

For the anon mobile path, render ONE blue primary CTA "Sign in to buy · $X" at top, then a single quiet "Sign in to bargain · make offer" link below. Hide the mid-page duplicate. Auth path keeps Buy / Make Offer / Bargain / Cart as discrete actions.

### 2. `/loadout` Discover-card empty-space fix

(Already covered in `BOSS_PROMPT_LOADOUT_EMPTY_CARDS.md`. If not shipped yet, ship it as #2 here.)

### 3. `/cart` "Recently Viewed" rail naming

Currently shows "27 RECENTLY VIEWED" then a single item card. The "27" is the COUNT of recently-viewed items but only one is rendered? Check `RecentlyViewedRail` mount on cart page — should render ALL ≤8 most-recent, not just one. If localStorage has 27 items, a 4-col mini grid is the right pattern.

### 4. Anon empty-state copy variation

Anon `/wallet`, `/profile`, `/sell`, `/support`, `/offers`, `/notifications`, `/me/stall` all show identical "Sign in required · Sign in with your Steam account to ..." pattern. Vary the second line to be feature-specific:
- `/wallet`: "... to view your balance, deposit history, and withdrawal queue."
- `/profile`: "... to access your transaction history, buy orders, auto-bids, trades, and offers."
- `/sell`: "... to list items from your Steam inventory at any price you choose."
- `/support`: "... to file a ticket. Average response: under 4 hours."
- `/offers`: "... to view incoming and outgoing offers on your listings."
- `/notifications`: "... to see escrow updates, sale alerts, and auction-end notices."
- `/me/stall`: "... to manage your active listings, sold-history, and analytics."

The Sign-in CTA stays the same. ONLY the descriptive line varies.

### 5. `/affiliate` Requirements tile values

`_qa_boss/cycle_qa_audit/desktop_affiliate.png` shows 6 stat tiles all displaying "1,000". Either each tile is a DIFFERENT threshold (with same value), or they're placeholder copy. Either way, label-only with "≥" prefix would clarify ("≥1,000 listings sold", "≥1,000 active followers", etc). Verify the icons match the labels — pageviews shouldn't have a flame icon; followers shouldn't have a coin icon.

### 6. `/db` row click target

Confirm clicking ANYWHERE on a /db row (not just the item name link) opens `/item/{id}`. Many users will tap the price column or the rarity badge expecting to navigate. Currently only the bare link is clickable. Wrap the whole row in `<a href="/item/{id}">` (semantic) or add a row-level click handler in the React renderer.

### 7. Saved-search empty state

On /market, "Save search" is a primary toolbar action, but if the user has 0 saved searches there's no surface inviting discovery. Add a "Saved searches" section to /profile with an empty state pointing to /market with a one-line walk-through.

### 8. Auction countdown UX

Verify auctions show a clear hh:mm:ss countdown that updates every second. "Auction ends in 2h 14m" is fine for >1h, but at <60min should be "1m 23s" with red emphasis at <5min. Check `AuctionEndingsoonRail` and item-detail auction display.

### 9. Cookie banner second-visit handling

`/?_qa=1` skip-cookie-banner is for screenshot rigs. For real returning visitors, after they accept/decline once, the banner should NOT reappear on next session. Verify localStorage / sessionStorage handles this and the banner isn't sticky-collapsed-but-still-visible.

### 10. /404-route footer lock

When a deep URL like `/foo/bar/baz` doesn't match any route, render the branded 404 (already shipped) BUT also render the standard footer below it. Currently the 404 page might be footer-less, which truncates ports of trust signals (legal links, Stripe badge).

### 11. Loadout Lab "Create your own" CTA

The Discover sub-tab is fine, but sign-in users have no obvious entry to CREATE a new loadout. There should be a "+ New loadout" floating button or page-header CTA on /loadout for authed users.

### 12. Mobile bottom-tab navigation

Premium marketplaces (Floatr, CSFloat) use a bottom-fixed nav bar on mobile (Market | Sell | Cart | Profile). Currently sboxmarket has top nav only on mobile. A bottom-fixed nav would match the customer's expectation for a mobile-first marketplace.

### 13. Signed-out price obfuscation toggle

Operator may want signed-out users to see prices as "$**.**" to encourage signup. Or NOT — this is a product call. Add a `?priceCloak` query flag for testing; let operator decide.

### 14. Number formatting consistency

Audit every `$0.00` rendering and verify thousand separators ($1,649.22 not $1649.22), correct currency on currency-switched view (USD/EUR/GBP via the picker), and consistent decimal places (always 2 for currency, 0 for whole counts).

### 15. Keyboard shortcuts visible affordance

`/help` says "Press / to jump straight into search". Also implement `?` (or Ctrl+/) to open a "keyboard shortcuts" overlay listing all shortcuts. Common ones: `/` search, `Esc` close, `g m` go market, `g d` go database, etc.

---

After 15, queue more. Suggested topics: error-banner urgency tiering, success-toast positioning consistency, loading-state micro-copy ("Loading..." → "Loading 41 listings..."), pagination URL state, deep-linkable filters, share buttons with proper OG tags, analytics opt-out checkbox, etc.

ALWAYS commit + redeploy + verify probes 200 + zero new errors in logs after each ship. NEVER stop. Operator's directive: "WORK NON STOP MAKE THIS SITE FULLY FUNCTIONAL".

GO.

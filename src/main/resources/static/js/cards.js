// Item card components: grid, table row, trending carousel.
import { h, useState, useEffect, fmt, timeAgo, discountPct, signInWithSteam, highlightMatch } from './utils.js';
// (2026-05-20) Dropped `FloatBar` from this import — its only call site
// in GridCard was removed (s&box has no float/wear; FloatBar is a no-op).
import { ItemImage, RarityBadge, SteamMarketLink, Avatar } from './primitives.js';

// ── Countdown — shared 1s ticker so cards + item modal stay in sync ──
function formatRemaining(ms) {
  if (ms <= 0) return 'Ended';
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

// ── Decorative float position (csfloat-style) ──
// s&box items have no float/wear value, but csfloat's card anatomy puts a
// gradient "float bar" with a thumb under every image. We reproduce the
// VISUAL by hashing a stable item identifier (id, else name) into a 0-100
// position so the same item always lands the thumb in the same spot across
// reloads. Purely cosmetic — never read as a real wear figure.
function floatPercent(seed) {
  const str = String(seed == null ? '' : seed);
  if (!str) return 50; // neutral midpoint when we have nothing to hash
  let hashAcc = 0;
  for (let i = 0; i < str.length; i++) {
    hashAcc = (hashAcc * 31 + str.charCodeAt(i)) >>> 0;
  }
  return hashAcc % 101; // 0-100 inclusive
}

export function AuctionCountdown({ expiresAt, className }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  if (!expiresAt) return null;
  const remaining = expiresAt - now;
  const ending = remaining > 0 && remaining < 60 * 60 * 1000; // < 1h
  return h('div', {
    className: `auction-countdown${ending ? ' ending' : ''}${remaining <= 0 ? ' ended' : ''} ${className || ''}`.trim(),
    title: remaining > 0 ? `Ends at ${new Date(expiresAt).toLocaleString()}` : 'Auction has ended'
  },
    h('span', { className: 'auction-countdown-dot' }),
    formatRemaining(remaining)
  );
}

export function GridCard({ listing, onClick, starred, onToggleStar, listingCount, onAddToCart, cartHas, meId, watcherCount, salesVelocity, searchQuery }) {
  const item = listing?.item;
  if (!item) return null;
  const isAuction = listing.listingType === 'AUCTION' && listing.expiresAt;
  // Discount must anchor against what the buyer would actually pay right
  // now. For an auction that's been bid up, the live top bid (currentBid)
  // is the real cost — using the static reserve `listing.price` made the
  // −N% chip claim a saving the buyer can no longer get (e.g. a $50
  // reserve bid up to $95 still flashed "−50%" next to the $95 figure).
  // Mirrors ListingRow's `effectivePrice` baseline.
  const effectivePrice = isAuction && listing.currentBid ? listing.currentBid : listing.price;
  const disc = discountPct(effectivePrice, item.steamPrice);
  const inCart = cartHas ? cartHas(listing.id) : false;
  // Auction participation chip — only shown when the viewer is signed in
  // AND is the current top bidder on this auction. Green "You're winning"
  // when `currentBidderId` matches the viewer. Computed per render;
  // `currentBidderId`+`bidCount` already come down in the listing payload
  // so no extra fetch is needed.
  // NOTE: an "Outbid" variant was removed — it depended on `listing.yourMaxBid`,
  // a field the Listing payload never carries (per-viewer bid status lives
  // only on the Bid entity / /profile/bids). The branch could never fire, so
  // it rendered nothing silently; the My Bids panel already surfaces "Outbid".
  let auctionBadge = null;
  if (isAuction && meId && listing.bidCount > 0 && listing.currentBidderId === meId) {
    auctionBadge = { label: "✓ You're winning", cls: 'win' };
  }
  // Anchor-based card (batch 422). Left click runs the SPA onClick
  // (state-based navigation); middle-click / Ctrl+click fall through to
  // browser default, opening /item/:id in a new tab. The `installAnchor
  // Interceptor` in app.js already wires same-tab anchor clicks to the
  // SPA router, so we let that run unless the caller's onClick wants
  // to intercept first (e.g. rails that also need to track the click
  // for recently-viewed). preventDefault inside onClick stops the
  // anchor's default, keeping the existing UX.
  const href = item?.id ? '/item/' + item.id : '#';
  const handleClick = (e) => {
    // Browser default for middle / modifier clicks: open in a new tab.
    // Let them through untouched.
    if (e.button === 1 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    if (onClick) {
      e.preventDefault();
      onClick(e);
    }
  };
  // Tooltip summary (batch 429). Surfaces the seller + key listing
  // facts on hover so shoppers don't have to open the modal just to
  // check "who's selling and how hot is this?".
  const parts = [];
  if (listing.sellerName) parts.push('by ' + listing.sellerName);
  if (disc > 0) parts.push('−' + disc + '% vs Steam');
  if (listingCount != null && listingCount > 1) parts.push(listingCount + ' listings');
  if (watcherCount > 0) parts.push(watcherCount + ' watching');
  if (salesVelocity > 0) parts.push(salesVelocity + ' sold 7d');
  const hoverTip = [item.name, parts.length ? parts.join(' · ') : null].filter(Boolean).join(' — ');
  // Screen-reader summary. Without an aria-label, focused cards announce the
  // raw textContent: a stew of rarity, listing-type, hex strings, online dot,
  // dollar amounts, and "Listed Nd ago" — hard to parse aurally. Building a
  // structured label gives blind users the same at-a-glance signal sighted
  // users get from the card layout: name, type, price, seller, and (if
  // present) the discount-vs-Steam tag.
  const ariaLabel = (() => {
    const segs = [item.name];
    if (isAuction) segs.push('auction');
    else if (listing.listingType === 'BUY_NOW') segs.push('buy now');
    // Announce the same figure the card shows — the live top bid for an
    // auction with bids, otherwise the listed price. Using `listing.price`
    // here made a bid-up auction read its stale reserve to screen readers
    // while the visible price + discount used the current bid.
    if (effectivePrice) segs.push(fmt(effectivePrice));
    if (listing.sellerName) segs.push('by ' + listing.sellerName);
    if (disc > 0) segs.push(disc + '% off Steam');
    return segs.join(', ');
  })();
  return h('a', {
    className: `grid-card${isAuction ? ' is-auction' : ''}`,
    href, onClick: handleClick,
    title: hoverTip,
    'aria-label': ariaLabel,
    style: {
      color: 'inherit', textDecoration: 'none', display: 'block',
      // FLIP-style animated reorder on sort change. Each card gets a
      // unique view-transition-name keyed by listing id; when the sort
      // updater wraps setState in document.startViewTransition() the
      // browser interpolates each card's old → new position. No JS
      // physics needed; degrades silently in browsers without support.
      viewTransitionName: 'card-' + (listing.id || 'x')
    }
  },
    h('div', {
      className: 'grid-thumb',
      // The thumbnail's rarity tier is signalled by a color gradient
      // overlay (yellow=Off-Market, pink=Limited, blue=Standard) — a
      // CSS pseudo-element that carries no tooltip of its own, so the
      // meaning is surfaced here on the parent. Phrasing names the tier
      // only (no "stripe at top"): ship #1390 replaced the old top
      // hairline with the gradient overlay, so the prior wording was
      // describing a visual that no longer renders.
      title: item.rarity ? `${item.rarity} rarity` : undefined
    },
      // CSFloat-1:1 — thin rarity-colored hairline along the TOP edge of the
      // image. csfloat caps each card image with a rarity stripe top+bottom;
      // we mirror that. Class carries the tier (e.g. gc-rarity-top--Limited)
      // so the stylesheet can color it; guarded so rarity-less payloads skip.
      item.rarity && h('div', {
        className: `gc-rarity-top gc-rarity-top--${item.rarity}`,
        'aria-hidden': 'true'
      }),
      h(ItemImage, { item, variant: 'card' }),
      h('div', { className: 'grid-rarity' }, h(RarityBadge, { rarity: item.rarity })),
      // CSFloat-1:1 — decorative magnifier-zoom cue at the bottom-right
      // of the thumbnail. Pure visual signal that the image is clickable
      // and zooms on the detail page; no extra interaction wired (the
      // whole card is the click target).
      h('div', { className: 'grid-zoom', 'aria-hidden': 'true' },
        h('svg', { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round' },
          h('circle', { cx: 11, cy: 11, r: 7 }),
          h('line', { x1: 21, y1: 21, x2: 16.65, y2: 16.65 })
        )
      ),
      // CSFloat-1:1 — view-count chip in the top-right of the thumb.
      // Mirrors csfloat's "👁 3" overlay. Hidden when no watchers so
      // empty state doesn't render a "0" chip.
      watcherCount > 0 && h('div', { className: 'grid-views', title: `${watcherCount} watching` },
        // a11y: the eye glyph is decorative — the watcher count is already
        // conveyed by the adjacent number + the chip's `title`. Without
        // aria-hidden the SVG announces as an unnamed graphic (matches the
        // grid-zoom / grid-status-verified SVG treatment).
        h('svg', { width: 12, height: 12, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true },
          h('path', { d: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z' }),
          h('circle', { cx: 12, cy: 12, r: 3 })
        ),
        ' ', watcherCount
      ),
      // Batch 1068 — editorial sweep: deleted the NEW / BEST PRICE /
      // 🔥 sales-velocity chips from the card body. The operator called
      // the colorful stack of chips noise ("we don't want all of that
      // fs"). Those freshness + best-price + demand signals are already
      // carried by the parent rail headers ("TOP DEALS TODAY", "JUST
      // LISTED", "MOST VIEWED RIGHT NOW"), so the per-card chip
      // duplication was redundant visual load. Rarity and discount stay
      // — they're primary info, not ornament.
      // (2026-05-20) Corrected this comment: it previously also claimed
      // the 👁 watcher-count chip was deleted. It was not — the
      // `grid-views` thumbnail overlay above still renders (and app.js
      // still feeds it `watcherCount`). It's the single corner overlay
      // csfloat itself shows in the thumb, so it was kept on purpose;
      // only the body-row chips went.
      isAuction && h(AuctionCountdown, { expiresAt: listing.expiresAt }),
      auctionBadge && h('div', {
        className: 'grid-auction-badge',
        style: {
          position: 'absolute', left: 8, bottom: 8,
          fontSize: 10, fontWeight: 800, padding: '3px 8px', borderRadius: 4,
          background: auctionBadge.cls === 'win' ? 'rgba(34,197,94,0.85)' : 'rgba(251,191,36,0.85)',
          color: '#0b1220', letterSpacing: 0.4
        }
      }, auctionBadge.label),
      // Quick-add-to-cart — only for BUY_NOW listings (auctions make no
      // sense in a cart). Stops click-propagation so clicking the button
      // doesn't also open the item modal. The cartHas prop lets the
      // parent tell us when it's already in the cart so the label flips.
      !isAuction && onAddToCart && h('button', {
        className: `grid-cart-btn ${inCart ? 'in' : ''}`,
        onClick: e => { e.preventDefault(); e.stopPropagation(); if (!inCart) onAddToCart(listing); },
        title: inCart ? 'Already in cart' : 'Add to cart',
        // Batch 950 — name the item in the aria-label so a SR user
        // skimming 30 cart buttons on the grid hears "Add Wizard Hat
        // to cart" instead of 30 identical "Add to cart" reads.
        // Matches the batch-931 watchlist ♥ pattern.
        'aria-label': inCart
          ? `${item?.name || 'Item'} already in cart`
          : `Add ${item?.name || 'item'} to cart`,
        disabled: inCart
      }, inCart ? '✓' : '+'),
      onToggleStar && h('button', {
        className: `grid-star ${starred ? 'on' : ''}`,
        onClick: e => { e.preventDefault(); e.stopPropagation(); onToggleStar(item.id); },
        title: starred ? 'Remove from watchlist' : 'Add to watchlist',
        'aria-label': starred ? `Remove ${item?.name || 'item'} from watchlist` : `Add ${item?.name || 'item'} to watchlist`,
        'aria-pressed': !!starred
      }, starred ? '♥' : '♡'),
      // CSFloat-1:1 — functional magnifier overlay, bottom-right of the
      // image (zoom cue). Unlike the decorative `grid-zoom` glyph above,
      // this is a real button: clicking it navigates to the item page via
      // the SAME target as the card body (the existing `handleClick`, which
      // runs the SPA onClick / falls through to the /item/:id anchor). We
      // stopPropagation so the click isn't double-handled by the parent
      // anchor, then invoke handleClick ourselves to keep one code path.
      item?.id && h('button', {
        className: 'gc-magnifier',
        type: 'button',
        title: 'View item',
        'aria-label': `View ${item?.name || 'item'}`,
        onClick: e => { e.stopPropagation(); handleClick(e); }
      },
        h('svg', { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': 'true' },
          h('circle', { cx: 11, cy: 11, r: 7 }),
          h('line', { x1: 21, y1: 21, x2: 16.65, y2: 16.65 })
        )
      ),
      // CSFloat-1:1 — decorative float bar under the image: a gradient track
      // with a white thumb positioned by a deterministic hash of the item
      // (id, else name). s&box has no float/wear, so this is purely the
      // csfloat-style visual; the inline `left:X%` is the only positioning.
      // aria-hidden — it carries no real value for assistive tech.
      h('div', { className: 'gc-float-bar', 'aria-hidden': 'true' },
        h('div', { className: 'gc-float-thumb', style: { left: floatPercent(item?.id ?? item?.name) + '%' } })
      ),
      // CSFloat-1:1 — matching rarity hairline along the BOTTOM edge of the
      // image (pairs with `gc-rarity-top`). Same tier-suffixed class + guard.
      item.rarity && h('div', {
        className: `gc-rarity-bottom gc-rarity-bottom--${item.rarity}`,
        'aria-hidden': 'true'
      })
    ),
    h('div', { className: 'grid-body' },
      h('div', { className: 'grid-name' },
        item.rarity === 'Limited' && h('span', { className: 'grid-name-star' }, '★ '),
        highlightMatch(item.name || '', searchQuery)
      ),
      h('div', { className: 'grid-cat' },
        isAuction && h('span', { className: 'grid-auction-tag' }, 'AUCTION '),
        item.category || '',
        // Inline seller-rating chip after the category. Compact, mono,
        // ink-3 — reads as a small trust signal next to the row meta
        // without inventing a whole "online status" row underneath.
        listing.sellerRating != null && listing.sellerReviewCount > 0 && h('span', {
          style: { marginLeft: 8, fontSize: 10, fontWeight: 700, color: 'var(--ink-3)', whiteSpace: 'nowrap' },
          title: `Seller rated ${listing.sellerRating.toFixed(1)}★ across ${listing.sellerReviewCount} review${listing.sellerReviewCount === 1 ? '' : 's'}`
        }, ' · ★ ', listing.sellerRating.toFixed(1))
      ),
      // Buy-Now ceiling chip on auction cards — small chip above price row.
      isAuction && listing.buyNowPrice && parseFloat(listing.buyNowPrice) > 0 && h('div', { style: { padding: '0 0 4px' } },
        h('span', {
          style: {
            fontSize: 9, fontWeight: 800, padding: '1px 6px', borderRadius: 3,
            background: 'rgba(34,197,94,0.15)', color: 'var(--green)',
            border: '1px solid rgba(34,197,94,0.35)', letterSpacing: 0.3
          },
          title: 'This auction has a Buy Now ceiling — skip the timer and settle instantly.'
        }, 'BIN ' + fmt(listing.buyNowPrice))
      ),
      // (2026-05-20) Removed the dead `h(FloatBar, …)` call. s&box items
      // have no float/wear mechanic, so FloatBar was already gutted to a
      // permanent `return null` in primitives.js — the call rendered
      // nothing while its comment still claimed a "gradient track with
      // thumb" was drawn. Dropping the no-op call + stale comment leaves
      // the card output byte-identical and removes a misleading breadcrumb.
      // CSFloat-1:1 seller status row — mirrors csfloat's "● Online ✓"
      // line on every card. V61 ship: real presence comes from
      // `listing.sellerLastSeenAt` (epoch ms), bumped by PresenceFilter
      // on every authenticated request — a seller who's actively
      // browsing in the last 15 minutes reads as Online. Null
      // (system seed listings, sellerUserId === null) falls back to
      // the deterministic-seed pattern so the row stays consistent
      // across reloads for those rows. The verified-check renders
      // when sellerReviewCount >= 5.
      (() => {
        const PRESENCE_WINDOW_MS = 15 * 60 * 1000;
        let isOnline;
        if (listing.sellerLastSeenAt) {
          isOnline = (Date.now() - Number(listing.sellerLastSeenAt)) < PRESENCE_WINDOW_MS;
        } else {
          const seed = listing.sellerUserId ? Number(String(listing.sellerUserId).slice(-6)) || 0 : (listing.id || 0);
          isOnline = (seed % 5) < 2;
        }
        const isVerified = (listing.sellerReviewCount || 0) >= 5;
        // Operator audit (batch 1149): clarify the Online/Offline pill —
        // it tracks SELLER PRESENCE (whether the lister is currently
        // active on the site), not item availability. Without a tooltip
        // shoppers were guessing whether "Offline" meant the item was
        // unavailable; it just means the seller may be slower to respond
        // to messages/offers.
        return h('div', {
          className: 'grid-status',
          title: isOnline
            ? 'Seller is online — likely to respond to offers/messages quickly'
            : 'Seller is offline — purchases still go through instantly; offers may take longer to answer'
        },
          h('span', { className: `grid-status-dot${isOnline ? ' online' : ''}` }),
          isOnline ? 'Online' : 'Offline',
          isVerified && h('span', { className: 'grid-status-verified', title: 'Verified seller (5+ reviews)' },
            h('svg', { width: 12, height: 12, viewBox: '0 0 24 24', fill: 'currentColor', 'aria-hidden': true },
              h('path', { d: 'M12 2L3 7v6c0 5 3.8 9.4 9 11 5.2-1.6 9-6 9-11V7l-9-5zm-1.4 14.6L7 13l1.4-1.4 2.2 2.2 4.6-4.6L16.6 11l-6 5.6z' })
            )
          )
        );
      })(),
      h('div', { className: 'grid-footer' },
        h('div', null,
          h('div', { className: 'grid-price' },
            isAuction && listing.currentBid
              ? fmt(listing.currentBid)
              : fmt(listing.price),
            // CSFloat-1:1 — small green USD chip after every price. Pure
            // visual signal that the listed price is in USD; mirrors
            // csfloat's "$675.00 [$]" badge pairing.
            h('span', { className: 'grid-price-usd', 'aria-hidden': 'true', title: 'Price is in US dollars (USD) — every listing on SkinBox uses one currency' }, '$'),
            // CSFloat-1:1 — decorative green USD marker chip immediately after
            // the price number (mirrors csfloat's "$" pill). aria-hidden — the
            // figure itself is already announced; this is a pure visual cue.
            h('span', { className: 'gc-usd-chip', 'aria-hidden': 'true', title: 'USD' }, '$'),
            h(SteamMarketLink, { item, compact: true }),
            // Boss QA cycle 2 N4 — bumped the discount-chip threshold
            // from 5% to 10%. With seed data sitting at 7-8% under
            // Steam, EVERY card was carrying the same green chip and
            // the chip stopped reading as a real deal signal. 10% is
            // the same threshold the marketplace's "Top Deals" filter
            // uses, so what's chipped here matches what's surfaced
            // there — visual credibility restored.
            disc >= 10 && h('span', {
              className: 'grid-discount',
              // Operator audit (batch 1149): make the −N% chip self-explanatory.
              // It compares this listing's price against the Steam Community
              // Market reference price for the same item; "−14%" means this
              // listing is 14% cheaper than Steam.
              title: `${disc}% cheaper than the Steam Community Market reference price for this item`
            }, `−${disc}%`)
          ),
          isAuction && listing.bidCount > 0
            ? h('div', { className: 'grid-bid-count' }, `${listing.bidCount} bid${listing.bidCount === 1 ? '' : 's'}`)
            : (() => {
                // Batch 637 — always surface a Steam reference when we
                // have one, even at parity. Previously the row was
                // hidden when `steamPrice <= listing.price`, which left
                // most cards with no Steam anchor at all (seed data +
                // Steam Market rate-limits conspire to give us lots of
                // items where steamPrice equals the floor). Showing
                // "Steam $X" muted + unstruck is a cleaner "no discount
                // to brag about, but here's the reference" signal vs
                // the silent hide.
                const sp = parseFloat(item?.steamPrice ?? 0);
                const lp = parseFloat(listing?.price ?? 0);
                if (!(sp > 0) || !(lp > 0)) return null;
                if (sp > lp) {
                  // Real discount — struck-through to anchor the saving.
                  return h('div', { className: 'grid-steam-price' }, fmt(sp));
                }
                // Parity / premium — show as a plain reference, no strike,
                // slightly dimmer so it doesn't compete with a real deal.
                return h('div', {
                  className: 'grid-steam-price',
                  style: { textDecoration: 'none', opacity: 0.6 },
                  title: 'Steam Market reference price for this item'
                }, 'Steam ', fmt(sp));
              })()
        ),
        listingCount > 1 && h('div', { className: 'grid-supply' }, listingCount + ' listings'),
        // CSFloat-1:1: per-card listing-time row. CSFloat shows "Expires in
        // 03:05:46:04" on every card; we show "Listed Xh ago" / "Listed Xd
        // ago" so every card has a freshness signal in the same slot.
        // Auctions still get the dedicated countdown above; this is just
        // for BUY_NOW listings.
        /* M2 (Boss QA): only render the "Listed X ago" subtitle when it
           carries genuine signal — fresh-in-5-min listings (pulsing
           "Just listed" dot) OR very recent (<24h) so the freshness
           is meaningful. Older "Listed 4d ago" reads as dead chrome
           cluttering every card and was the line the boss called
           "Lowest Mileage" — generic, repeated, meaningless. */
        !isAuction && listing.listedAt && (() => {
          const ageMs = Date.now() - new Date(listing.listedAt).getTime();
          if (ageMs < 0) return null;
          const fresh = ageMs < 5 * 60 * 1000;
          const recent = ageMs < 24 * 60 * 60 * 1000;
          if (!fresh && !recent) return null;
          return h('div', {
            className: 'grid-fresh' + (fresh ? ' is-new' : ''),
            title: fresh ? 'Listed within the last 5 minutes' : 'Listed ' + timeAgo(listing.listedAt)
          },
            fresh && h('span', { className: 'grid-fresh-dot' }),
            fresh ? 'Just listed' : 'Listed ' + timeAgo(listing.listedAt)
          );
        })(),
        // CSFloat-1:1 — seller-presence row under the price area: green dot
        // + Online/Offline + a count. csfloat surfaces seller presence here;
        // we reuse the SAME presence flag as the `grid-status` row above
        // (real `sellerLastSeenAt` within a 15-min window, else a stable
        // deterministic-seed fallback so it doesn't flicker across reloads).
        // The count is the watcher count when we have one; omitted otherwise
        // so a card with no watchers shows "● Online" with no trailing "0".
        (() => {
          const PRESENCE_WINDOW_MS = 15 * 60 * 1000;
          let isOnline;
          if (listing.sellerLastSeenAt) {
            isOnline = (Date.now() - Number(listing.sellerLastSeenAt)) < PRESENCE_WINDOW_MS;
          } else {
            const seed = listing.sellerUserId ? Number(String(listing.sellerUserId).slice(-6)) || 0 : (listing.id || 0);
            isOnline = (seed % 5) < 2;
          }
          return h('div', {
            className: `gc-online-row${isOnline ? ' is-online' : ''}`,
            title: isOnline ? 'Seller is online' : 'Seller is offline'
          },
            h('span', { className: 'gc-online-dot', 'aria-hidden': 'true' }),
            isOnline ? 'Online' : 'Offline',
            watcherCount > 0 && h('span', { className: 'gc-online-count' }, ' ' + watcherCount)
          );
        })()
      )
    ),
    // CSFloat-1:1 — bottom stripe spanning the card: "Listed {relativeTime}".
    // Direct child of the card (after grid-body) so it reads as a footer
    // strip. Uses the listing's listed/created time; omitted entirely when
    // we have no timestamp (and for auctions, which carry their own
    // countdown rather than a listed-age line).
    !isAuction && listing.listedAt && h('div', { className: 'gc-listed-stripe' },
      'Listed ' + timeAgo(listing.listedAt)
    )
  );
}

export function ListingRow({ listing, onClick, onBuy, meId, hasTradeUrl, sellerAvatarUrl, searchQuery }) {
  const item = listing?.item;
  if (!item) return null;
  // Normalize trendPercent: a null/undefined value (legacy rows, non-entity
  // payloads) otherwise fell through to the "down" branch and rendered the
  // broken "▼ NaN%" because Math.abs(undefined) is NaN.
  const trend = Number.isFinite(Number(item.trendPercent)) ? Number(item.trendPercent) : 0;
  const trendUp = trend > 0, trendFlat = trend === 0;
  // CSFloat-1:1 — the table/list view must distinguish AUCTION rows the
  // same way the grid card and modal listing row already do. Previously
  // ListingRow treated every row as BUY_NOW: it showed the static reserve
  // as the price, "Listed Xd ago" with no countdown, and a "Buy" button
  // that for an auction would bounce off the server (auctions take bids,
  // not instant purchase). Now: auctions get a live countdown in the
  // Listed column, the current top bid (or reserve) with a BID/START
  // label + bid count in the Price column, and a "Bid" action that opens
  // the detail modal where the bid panel lives.
  const isAuction = listing.listingType === 'AUCTION' && listing.expiresAt;
  const hasBids = isAuction && (listing.bidCount || 0) > 0;
  // For auctions the headline figure is the live top bid once bidding has
  // started, otherwise the seller's starting/reserve price. discountPct
  // anchors against whatever the buyer would actually pay right now.
  const effectivePrice = isAuction && listing.currentBid ? listing.currentBid : listing.price;
  const disc = discountPct(effectivePrice, item.steamPrice);
  // Batch 931 — keyboard-accessible list rows. The row is clickable
  // (opens the item detail modal) but `<tr onClick>` is pointer-only.
  // Adding role=button + tabIndex lets screen-reader / keyboard users
  // focus the row and press Enter/Space to open it. aria-label gives
  // the SR a concrete "open X detail" announce instead of "row 3".
  return h('tr', {
    onClick,
    role: 'button',
    tabIndex: 0,
    className: isAuction ? 'is-auction' : undefined,
    'aria-label': `Open ${item.name}${isAuction ? ' auction' : ''} detail`,
    onKeyDown: (e) => {
      // Don't intercept keys when focus is on an inline button inside
      // the row (Buy / Sign in). Those have their own handlers.
      const tag = (e.target?.tagName || '').toLowerCase();
      if (tag === 'button' || tag === 'a' || tag === 'input') return;
      if ((e.key === 'Enter' || e.key === ' ') && typeof onClick === 'function') {
        e.preventDefault();
        onClick(e);
      }
    }
  },
    h('td', null,
      h('div', { className: 'item-cell' },
        h('div', { className: 'item-thumb' }, h(ItemImage, { item, variant: 'thumb' })),
        h('div', { className: 'item-info' },
          h('div', { className: 'item-name' }, highlightMatch(item.name || '', searchQuery)),
          h('div', { className: 'item-sub' },
            // Inline AUCTION tag before the category — mirrors the grid
            // card's `grid-auction-tag` so list-view rows carry the same
            // listing-type signal as the grid.
            isAuction && h('span', { className: 'grid-auction-tag', style: { marginRight: 6 } }, 'AUCTION'),
            item.category
          )
        )
      )
    ),
    h('td', null, h(RarityBadge, { rarity: item.rarity })),
    h('td', { className: 'center' },
      disc > 0
        ? h('span', { style: { color: 'var(--green)', fontWeight: 700, fontSize: 12, fontFamily: 'JetBrains Mono, monospace' } }, `−${disc}%`)
        : h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, '—')
    ),
    h('td', { className: 'center' },
      h('span', { className: `trend ${trendFlat ? 'flat' : trendUp ? 'up' : 'down'}` },
        trendFlat ? '━' : trendUp ? `▲ ${trend}%` : `▼ ${Math.abs(trend)}%`
      )
    ),
    h('td', null,
      h('div', { className: 'seller-cell' },
        // Steam profile photo when we have it (bulk-fetched in the
        // marketplace page), else the historical monogram fallback.
        sellerAvatarUrl
          ? h(Avatar, {
              src: sellerAvatarUrl,
              name: listing.sellerName,
              alt: '',
              className: 'seller-avatar',
              style: { width: 28, height: 28, borderRadius: '50%', padding: 0, background: 'transparent', objectFit: 'cover', border: '1px solid var(--border)' }
            })
          : h('div', { className: 'seller-avatar' }, (listing.sellerAvatar || 'US').toUpperCase()),
        h('div', { style: { display: 'flex', flexDirection: 'column', minWidth: 0 } },
          h('span', { className: 'seller-name' }, listing.sellerName),
          // Inline seller rating chip on table-view rows — populated by
          // the same ListingController.decorateWithSellerRating GROUP BY
          // that powers the grid-card chip and modal-listing-row chip.
          // Hidden when the seller has zero reviews or is a system listing.
          listing.sellerRating != null && listing.sellerReviewCount > 0 && h('span', {
            style: { fontSize: 10, color: 'var(--ink-3)', fontWeight: 600, marginTop: 1 },
            title: `${listing.sellerReviewCount} review${listing.sellerReviewCount === 1 ? '' : 's'}`
          }, '★ ', listing.sellerRating.toFixed(1), ' (', listing.sellerReviewCount, ')')
        )
      )
    ),
    h('td', null,
      // Auctions show a live countdown in this column (csfloat surfaces
      // "Ends in …" right in the list view); BUY_NOW rows keep the
      // "Listed X ago" freshness signal.
      isAuction
        ? h(AuctionCountdown, { expiresAt: listing.expiresAt })
        : h('span', { style: { fontSize: 12, color: 'var(--text-muted)' } }, timeAgo(listing.listedAt))
    ),
    h('td', { className: 'right' },
      h('div', { className: 'price-cell' },
        h('div', { className: 'price-val' },
          // BID / START prefix on auction rows so the figure isn't
          // mistaken for an instant Buy-Now price — mirrors the modal
          // listing row's label.
          isAuction && h('span', {
            style: {
              fontSize: 9, fontWeight: 700, color: 'var(--text-muted)',
              letterSpacing: '0.06em', marginRight: 5, verticalAlign: 'middle'
            },
            title: hasBids ? 'Current top bid in this auction' : "Seller's starting bid — be the first to bid"
          }, hasBids ? 'BID' : 'START'),
          fmt(effectivePrice)
        ),
        // Auctions: bid count instead of supply (supply is meaningless
        // for a single-item auction). BUY_NOW: supply, with a finite
        // guard — `item.supply` is absent on some payloads and
        // Number(undefined) is NaN, which rendered "NaN supply".
        isAuction
          ? h('div', { className: 'price-supply' },
              `${listing.bidCount || 0} bid${(listing.bidCount || 0) === 1 ? '' : 's'}`)
          : h('div', { className: 'price-supply' },
              `${(Number.isFinite(Number(item.supply)) ? Number(item.supply) : 0).toLocaleString()} supply`)
      )
    ),
    h('td', { className: 'center' },
      // Auctions can't be instant-bought from the table — the bid panel
      // lives in the detail modal. A "Bid" button opens that modal (same
      // target as a row click) so the table action stays meaningful for
      // auction rows instead of showing a Buy button that the server
      // would reject. Falls through to the BUY_NOW button family below.
      isAuction
        ? h('button', {
            className: 'buy-btn',
            onClick: e => { e.preventDefault(); e.stopPropagation(); if (typeof onClick === 'function') onClick(e); },
            title: 'Open this auction to place a bid',
            'aria-label': `Bid on ${item.name}`
          }, 'Bid')
      // Anon viewers see a sign-in CTA rather than a Buy button that
      // would bounce off the auth filter with a generic error. Sellers
      // viewing their own listing get a disabled "Your listing" chip.
      // Batch 950 — aria-label on every row's action button names the
      // item + price so a screen-reader user scanning 30 rows hears
      // "Buy Wizard Hat for $12.50" instead of 30 identical "Buy" reads.
      : !meId
        ? h('button', {
            className: 'buy-btn',
            onClick: e => { e.preventDefault(); e.stopPropagation(); signInWithSteam(); },
            title: 'Sign in with Steam to buy',
            'aria-label': `Sign in to buy ${item.name}`
          }, 'Sign in')
        : meId === listing.sellerUserId
          ? h('button', {
              className: 'buy-btn',
              disabled: true,
              style: { opacity: 0.4, cursor: 'not-allowed' },
              title: "You can't buy your own listing",
              'aria-label': `Your listing of ${item.name} — can't buy yourself`
            }, 'Yours')
          : h('button', {
              className: 'buy-btn',
              // Batch 794 — trade-URL gate (same rationale as batches
              // 791-793). Table-row Buy button was the last un-gated
              // money-moving control. Tooltip explains the block so
              // the user doesn't confuse a disabled Buy with a broken
              // page.
              disabled: hasTradeUrl === false,
              style: hasTradeUrl === false ? { opacity: 0.55, cursor: 'not-allowed' } : undefined,
              title: hasTradeUrl === false
                ? 'Add your Steam trade URL in Profile before buying'
                : undefined,
              'aria-label': hasTradeUrl === false
                ? `Buy ${item.name} — add a Steam trade URL first`
                : `Buy ${item.name} for ${fmt(listing.price)}`,
              onClick: e => { e.preventDefault(); e.stopPropagation(); onBuy(listing.id, listing.price); }
            }, 'Buy')
    )
  );
}

// (2026-05-23) Removed dead `TrendCard` export — no remaining call sites
// in the SPA (used to power a now-deleted trending carousel). The CSS
// `.trend-card`/`.trend-thumb`/`.trend-name` classes in design.css are
// still referenced for legacy reasons but render nothing.

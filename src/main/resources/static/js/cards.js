// Item card components: grid, table row, trending carousel.
import { h, useState, useEffect, fmt, timeAgo, discountPct, signInWithSteam, highlightMatch, currencySymbol } from './utils.js';
// (2026-05-20) Dropped `FloatBar` from this import — its only call site
// in GridCard was removed (s&box has no float/wear; FloatBar is a no-op).
import { ItemImage, RarityBadge, SteamMarketLink, Avatar, Money, MaterialIcon } from './primitives.js';
import { navigate } from './router.js';

// The ✓ rule the server applies (ListingController.decorateWithSellerVerified,
// the stall and the Help Center say the same), shown wherever the mark is.
export const VERIFIED_SELLER_RULE = 'Verified seller: 10+ completed sales, rated 4★ or higher (or no reviews yet)';

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

// Auctions the signed-in viewer is currently winning, by listing id. The
// listing payload never carries the top bidder's id (redacted server-side),
// so the card chip reads the viewer's own live bids instead: one shared
// fetch of /api/bids/my-active per viewer per minute, for every card.
const winning = { meId: null, at: 0, ids: null, pending: null, subs: new Set() };
function loadWinning(meId) {
  const now = Date.now();
  if (winning.meId === meId && (winning.pending || now - winning.at < 60_000)) return;
  if (winning.meId !== meId) winning.ids = null;
  winning.meId = meId;
  winning.at = now;
  winning.pending = import('./api.js')
    .then(m => m.fetchActiveBids())
    .then(rows => {
      if (winning.meId !== meId) return;
      winning.ids = new Set((Array.isArray(rows) ? rows : [])
        .filter(b => b && b.status === 'WINNING' && b.listingId != null)
        .map(b => b.listingId));
      winning.subs.forEach(fn => fn());
    })
    .catch(() => {})
    .finally(() => { if (winning.meId === meId) winning.pending = null; });
}
function useWinningIds(meId, wanted) {
  const [, bump] = useState(0);
  useEffect(() => {
    if (!meId || !wanted) return;
    const fn = () => bump(n => n + 1);
    winning.subs.add(fn);
    loadWinning(meId);
    return () => { winning.subs.delete(fn); };
  }, [meId, wanted]);
  return winning.meId === meId ? winning.ids : null;
}

export function GridCard({ listing, onClick, starred, onToggleStar, listingCount, onAddToCart, cartHas, meId, watcherCount, salesVelocity, searchQuery, href: hrefOverride }) {
  // Before the early return below: hooks must run on every render.
  const winningIds = useWinningIds(meId,
    !!(listing && listing.listingType === 'AUCTION' && listing.bidCount > 0));
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
  // The watchlist reuses this market card for a WATCHED item that may have
  // no active listing — it comes through with price 0 (item.lowestPrice).
  // Without this guard the footer rendered a misleading "$0.00 $$" (price +
  // the two USD chips) on a perfectly valuable item. hasPrice gates the
  // price number + USD chips so a no-listing card reads "Not listed"
  // instead (same copy as the item-detail Listing-price stat). A real
  // market listing always has price > 0, so this never changes /market.
  const hasPrice = (isAuction && listing.currentBid) || (parseFloat(listing.price) > 0);
  const inCart = cartHas ? cartHas(listing.id) : false;
  // Auction participation chip — only shown when the viewer is signed in
  // AND is the current top bidder on this auction. It used to compare
  // `currentBidderId`, which the payload never carries, so it never showed;
  // it now reads the viewer's own WINNING bids (useWinningIds above).
  // NOTE: an "Outbid" variant was removed — it depended on `listing.yourMaxBid`,
  // a field the Listing payload never carries (per-viewer bid status lives
  // only on the Bid entity / /profile/bids). The branch could never fire, so
  // it rendered nothing silently; the My Bids panel already surfaces "Outbid".
  let auctionBadge = null;
  if (isAuction && meId && listing.bidCount > 0 && winningIds && winningIds.has(listing.id)) {
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
  // A caller that opens a specific listing passes its URL, so a new-tab
  // or copied link lands on the same place as a plain click.
  const href = hrefOverride || (item?.id ? '/item/' + item.id : '#');
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
  // The card is a plain box; its link is the item name, stretched over the
  // whole card by `.grid-card-link::after`. A card that was itself an <a>
  // held the cart, heart and magnifier buttons and the Steam link inside a
  // link, which is invalid and confuses keyboards and screen readers.
  return h('div', {
    className: `grid-card${isAuction ? ' is-auction' : ''}`,
    title: hoverTip,
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
      // the SAME target as the card link (the existing `handleClick`, which
      // runs the SPA onClick). A button has no default to fall back on, so
      // with no onClick it follows `href` itself.
      item?.id && h('button', {
        className: 'gc-magnifier',
        type: 'button',
        title: 'View item',
        'aria-label': `View ${item?.name || 'item'}`,
        onClick: e => {
          e.stopPropagation();
          if (onClick) handleClick(e);
          else if (href !== '#') navigate(href);
        }
      },
        h('svg', { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': 'true' },
          h('circle', { cx: 11, cy: 11, r: 7 }),
          h('line', { x1: 21, y1: 21, x2: 16.65, y2: 16.65 })
        )
      ),
      // CSFloat-1:1 — matching rarity hairline along the BOTTOM edge of the
      // image (pairs with `gc-rarity-top`). Same tier-suffixed class + guard.
      item.rarity && h('div', {
        className: `gc-rarity-bottom gc-rarity-bottom--${item.rarity}`,
        'aria-hidden': 'true'
      })
    ),
    // Quick-add-to-cart — only for BUY_NOW listings (auctions make no
    // sense in a cart). Stops click-propagation so clicking the button
    // doesn't also open the item modal. The cartHas prop lets the
    // parent tell us when it's already in the cart so the label flips.
    // It is a child of the card, not the picture, and sits between the
    // two so it keeps its place in tab order (after the heart and
    // magnifier, before the name link). On desktop it is still pinned
    // into the picture's top-right corner (the card's
    // padding box is the picture's, so the same top/right land in the
    // same place), and on phones it becomes a full-width "Add to cart"
    // bar under the price, which it could not do from inside the picture.
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
    },
      h('span', { className: 'grid-cart-glyph', 'aria-hidden': 'true' }, inCart ? '✓' : '+'),
      // Shown only on phones, where the button is a full-width bar.
      h('span', { className: 'grid-cart-label', 'aria-hidden': 'true' }, inCart ? 'In cart' : 'Add to cart')
    ),
    h('div', { className: 'grid-body' },
      h('div', { className: 'grid-name' },
        h('a', { className: 'grid-card-link', href, onClick: handleClick, 'aria-label': ariaLabel },
          item.rarity === 'Limited' && h('span', { className: 'grid-name-star' }, '★ '),
          highlightMatch(item.name || '', searchQuery)
        )
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
      isAuction && listing.buyNowPrice && parseFloat(listing.buyNowPrice) > 0 &&
        !(listing.currentBid != null && parseFloat(listing.currentBid) >= parseFloat(listing.buyNowPrice)) &&
        h('div', { style: { padding: '0 0 4px' } },
        h('span', {
          style: {
            fontSize: 9, fontWeight: 800, padding: '1px 6px', borderRadius: 4,
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
      h('div', { className: 'grid-footer' },
        h('div', null,
          h('div', { className: 'grid-price' },
            hasPrice
              ? h(Money, { value: isAuction && listing.currentBid ? listing.currentBid : listing.price })
              : h('span', { className: 'grid-price-unlisted', style: { color: 'var(--text-muted)', fontWeight: 600 }, title: 'No active listings right now — this item isn\'t for sale at the moment' }, 'Not listed'),
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
                  // Real discount: the Steam price struck through to anchor
                  // the saving, labelled so it can't read as this seller's
                  // own earlier price.
                  return h('div', { className: 'grid-steam-price', style: { textDecoration: 'none' }, title: 'Steam Market reference price for this item' }, 'Steam ', h('s', null, fmt(sp)));
                }
                // More than 3x the Steam reference: the card would advertise
                // a huge premium ("$230.00 · Steam $0.74"), which says more
                // about a seed price or a stale reference than about the
                // item. The item page still shows the reference in full.
                if (lp > sp * 3) return null;
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
        // Card footer: seller presence (real `sellerLastSeenAt` within 15
        // minutes, honestly Offline when there is none), the verified mark
        // at 5+ reviews, and on the right the listing's age or the auction's
        // bid count. One row replaces the old status row, presence row and
        // "Listed X ago" strip, which said the same things three times.
        (() => {
          const PRESENCE_WINDOW_MS = 15 * 60 * 1000;
          const isOnline = listing.sellerLastSeenAt
            ? (Date.now() - Number(listing.sellerLastSeenAt)) < PRESENCE_WINDOW_MS
            : false;
          // The server applies the stall's ✓ rule (10+ sales, rating 4+ or none).
          const isVerified = listing.sellerVerified === true;
          const listedAgo = !isAuction && listing.listedAt ? 'Listed ' + timeAgo(listing.listedAt).replace(/^Just now$/, 'just now') : null;
          // Presence is the signal, absence is silent: real markets never
          // label sellers offline on a card, and a grid where nearly every
          // card read "Offline" looked like a dead market. A listing SkinBox
          // holds itself (sellerUserId === null) has no seller to wait on,
          // so it carries the item page's lightning mark instead.
          const isHouse = listing.sellerUserId === null;
          const verifiedMark = isVerified && h('span', { className: 'grid-status-verified', title: VERIFIED_SELLER_RULE },
            h('svg', { width: 12, height: 12, viewBox: '0 0 24 24', fill: 'currentColor', 'aria-hidden': true },
              h('path', { d: 'M12 2L3 7v6c0 5 3.8 9.4 9 11 5.2-1.6 9-6 9-11V7l-9-5zm-1.4 14.6L7 13l1.4-1.4 2.2 2.2 4.6-4.6L16.6 11l-6 5.6z' })
            )
          );
          if (!isOnline && !isHouse && !listedAgo && !verifiedMark) return null;
          return h('div', { className: 'gc-card-meta' },
            isHouse
              ? h('div', {
                  className: 'gc-online-row gc-house-row',
                  title: 'Held by SkinBox: delivered in-platform, no seller to wait on'
                }, h(MaterialIcon, { name: 'bolt', size: 14 }), 'Instant')
              : isOnline
                ? h('div', {
                    className: 'gc-online-row is-online',
                    title: 'Seller is online and likely to answer offers quickly'
                  }, h('span', { className: 'gc-online-dot', 'aria-hidden': 'true' }), 'Online', verifiedMark)
                : h('div', { className: 'gc-online-row gc-presence-quiet' }, verifiedMark),
            listedAgo && h('span', { className: 'gc-card-age' }, listedAgo)
          );
        })()
      )
    ),
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
  // The row opens the item for a mouse click anywhere on it; the keyboard
  // and screen-reader way in is the item name, a real link. A
  // role="button" row hid the price, seller and discount cells behind one
  // "Open X detail" label and nested the Buy / Bid buttons inside a button.
  return h('tr', {
    onClick,
    className: isAuction ? 'is-auction' : undefined
  },
    h('td', null,
      h('div', { className: 'item-cell' },
        h('div', { className: 'item-thumb' }, h(ItemImage, { item, variant: 'thumb' })),
        h('div', { className: 'item-info' },
          h('div', { className: 'item-name' },
            h('a', {
              href: '/item/' + item.id,
              style: { color: 'inherit', textDecoration: 'none' },
              onClick: (e) => {
                e.stopPropagation();
                // New-tab and modifier clicks keep the browser default.
                if (e.button === 1 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
                if (typeof onClick === 'function') { e.preventDefault(); onClick(e); }
              }
            }, highlightMatch(item.name || '', searchQuery))),
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
        ? h('span', { style: { color: 'var(--green)', fontWeight: 700, fontSize: 12, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, `−${disc}%`)
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
          : (() => { const urlBlocked = hasTradeUrl === false && listing.sellerUserId != null; return h('button', {
              className: 'buy-btn',
              // Batch 794 — trade-URL gate (same rationale as batches
              // 791-793). Table-row Buy button was the last un-gated
              // money-moving control. Tooltip explains the block so
              // the user doesn't confuse a disabled Buy with a broken
              // page.
              disabled: urlBlocked,
              style: urlBlocked ? { opacity: 0.55, cursor: 'not-allowed' } : undefined,
              title: urlBlocked
                ? 'Add your Steam trade URL in Profile before buying'
                : undefined,
              'aria-label': urlBlocked
                ? `Buy ${item.name} — add a Steam trade URL first`
                : `Buy ${item.name} for ${fmt(listing.price)}`,
              onClick: e => { e.preventDefault(); e.stopPropagation(); onBuy(listing.id, listing.price); }
            }, 'Buy'); })()
    )
  );
}

// (2026-05-23) Removed dead `TrendCard` export — no remaining call sites
// in the SPA (used to power a now-deleted trending carousel). The CSS
// `.trend-card`/`.trend-thumb`/`.trend-name` classes in design.css are
// still referenced for legacy reasons but render nothing.

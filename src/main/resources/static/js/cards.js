// Item card components: grid, table row, trending carousel.
import { h, React, useState, useEffect, fmt, timeAgo, discountPct, signInWithSteam, highlightMatch } from './utils.js';
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
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  const disc = discountPct(listing.price, item.steamPrice);
  const isAuction = listing.listingType === 'AUCTION' && listing.expiresAt;
  const inCart = cartHas ? cartHas(listing.id) : false;
  // Auction participation chip — only shown when the viewer is signed in
  // AND is a known bidder on this auction. Green "You're winning" when
  // you're the top bidder, amber "Outbid" when someone's above you.
  // Computed per render; `currentBid`+`bidCount` already come down in
  // the listing payload so no extra fetch is needed.
  let auctionBadge = null;
  if (isAuction && meId && listing.bidCount > 0) {
    if (listing.currentBidderId === meId) {
      auctionBadge = { label: "✓ You're winning", cls: 'win' };
    } else if (listing.yourMaxBid && listing.currentBidderId !== meId) {
      auctionBadge = { label: '↑ Outbid',          cls: 'loss' };
    }
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
  return h('a', {
    className: `grid-card${isAuction ? ' is-auction' : ''}`,
    href, onClick: handleClick,
    title: hoverTip,
    style: { color: 'inherit', textDecoration: 'none', display: 'block' }
  },
    h('div', { className: 'grid-thumb' },
      h(ItemImage, { item, variant: 'card' }),
      h('div', { className: 'grid-rarity' }, h(RarityBadge, { rarity: item.rarity })),
      disc > 0 && h('div', { className: 'grid-discount' }, `−${disc}%`),
      // Batch 1068 — editorial sweep: deleted the NEW / BEST PRICE /
       // 👁 watcher-count / 🔥 sales-velocity chips from the card. The
       // operator called the colorful stack of chips noise ("we don't
       // want all of that fs"). The freshness + best-price + demand
       // signals are already carried by the parent rail headers ("TOP
       // DEALS TODAY", "JUST LISTED", "MOST VIEWED RIGHT NOW"), so the
       // per-card chip duplication was redundant visual load. Rarity
       // and discount stay — they're primary info, not ornament.
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
      }, starred ? '♥' : '♡')
    ),
    h('div', { className: 'grid-body' },
      h('div', { className: 'grid-name' }, highlightMatch(item.name || '', searchQuery)),
      h('div', { className: 'grid-cat' },
        isAuction && h('span', { className: 'grid-auction-tag' }, 'AUCTION'),
        // Buy-Now chip on auction cards (batch 372). Tells a browsing
        // buyer "you can skip the auction at $X" at a glance. Only
        // rendered on auction cards with buyNowPrice set.
        isAuction && listing.buyNowPrice && parseFloat(listing.buyNowPrice) > 0 && h('span', {
          style: {
            marginLeft: 6,
            fontSize: 9, fontWeight: 800, padding: '1px 6px', borderRadius: 3,
            background: 'rgba(34,197,94,0.15)', color: 'var(--green)',
            border: '1px solid rgba(34,197,94,0.35)', letterSpacing: 0.3
          },
          title: 'This auction has a Buy Now ceiling — skip the timer and settle instantly.'
        }, 'BIN ' + fmt(listing.buyNowPrice)),
        item.category
      ),
      h('div', { className: 'grid-footer' },
        h('div', null,
          h('div', { className: 'grid-price' },
            isAuction && listing.currentBid
              ? fmt(listing.currentBid)
              : fmt(listing.price),
            h(SteamMarketLink, { item, compact: true })
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
        listingCount > 1 && h('div', { className: 'grid-supply' }, listingCount + ' listings')
      )
    )
  );
}

export function ListingRow({ listing, onClick, onBuy, meId, hasTradeUrl, sellerAvatarUrl, searchQuery }) {
  const item = listing?.item;
  if (!item) return null;
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  const disc = discountPct(listing.price, item.steamPrice);
  // Batch 931 — keyboard-accessible list rows. The row is clickable
  // (opens the item detail modal) but `<tr onClick>` is pointer-only.
  // Adding role=button + tabIndex lets screen-reader / keyboard users
  // focus the row and press Enter/Space to open it. aria-label gives
  // the SR a concrete "open X detail" announce instead of "row 3".
  return h('tr', {
    onClick,
    role: 'button',
    tabIndex: 0,
    'aria-label': `Open ${item.name} detail`,
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
          h('div', { className: 'item-sub' }, item.category)
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
        trendFlat ? '━' : trendUp ? `▲ ${item.trendPercent}%` : `▼ ${Math.abs(item.trendPercent)}%`
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
        h('span', { className: 'seller-name' }, listing.sellerName)
      )
    ),
    h('td', null, h('span', { style: { fontSize: 12, color: 'var(--text-muted)' } }, timeAgo(listing.listedAt))),
    h('td', { className: 'right' },
      h('div', { className: 'price-cell' },
        h('div', { className: 'price-val' }, fmt(listing.price)),
        h('div', { className: 'price-supply' }, `${Number(item.supply).toLocaleString()} supply`)
      )
    ),
    h('td', { className: 'center' },
      // Anon viewers see a sign-in CTA rather than a Buy button that
      // would bounce off the auth filter with a generic error. Sellers
      // viewing their own listing get a disabled "Your listing" chip.
      // Batch 950 — aria-label on every row's action button names the
      // item + price so a screen-reader user scanning 30 rows hears
      // "Buy Wizard Hat for $12.50" instead of 30 identical "Buy" reads.
      !meId
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

export function TrendCard({ listing, onClick }) {
  const item = listing?.item;
  if (!item) return null;
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  // Batch 934 — keyboard-accessible trend card. Was a plain clickable
  // <div>; not in the tab order, not announced as a button. Add
  // role=button + tabIndex + Enter/Space keydown so keyboard users
  // can open the detail modal.
  return h('div', {
    className: 'trend-card',
    onClick,
    role: 'button',
    tabIndex: 0,
    'aria-label': `Open ${item.name} detail`,
    onKeyDown: (e) => {
      if ((e.key === 'Enter' || e.key === ' ') && typeof onClick === 'function') {
        e.preventDefault();
        onClick(e);
      }
    }
  },
    h('div', {
      className: 'trend-thumb',
      style: {
        background: 'radial-gradient(ellipse at 50% 30%, rgba(30,165,255,0.12) 0%, transparent 65%), ' +
                    'linear-gradient(180deg, #1a2236 0%, #0d1320 100%)'
      }
    }, h(ItemImage, { item, variant: 'card' })),
    h('div', { className: 'trend-name' }, item.name),
    h('div', { className: 'trend-meta' },
      h('div', { className: 'trend-price' }, fmt(item.lowestPrice)),
      h('div', { className: `trend-delta ${trendFlat ? 'flat' : trendUp ? 'up' : 'down'}` },
        trendFlat ? '━' : trendUp ? `+${item.trendPercent}%` : `${item.trendPercent}%`
      )
    )
  );
}

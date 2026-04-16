// Item card components: grid, table row, trending carousel.
import { h, fmt, timeAgo, discountPct } from './utils.js';
import { ItemImage, RarityBadge, SteamMarketLink } from './primitives.js';

export function GridCard({ listing, onClick, starred, onToggleStar, listingCount }) {
  const item = listing?.item;
  if (!item) return null;
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  const disc = discountPct(listing.price, item.steamPrice);
  return h('div', { className: 'grid-card', onClick },
    h('div', { className: 'grid-thumb' },
      h(ItemImage, { item, variant: 'card' }),
      h('div', { className: 'grid-rarity' }, h(RarityBadge, { rarity: item.rarity })),
      disc > 0 && h('div', { className: 'grid-discount' }, `−${disc}%`),
      onToggleStar && h('button', {
        className: `grid-star ${starred ? 'on' : ''}`,
        onClick: e => { e.stopPropagation(); onToggleStar(item.id); },
        title: starred ? 'Remove from watchlist' : 'Add to watchlist'
      }, starred ? '♥' : '♡')
    ),
    h('div', { className: 'grid-body' },
      h('div', { className: 'grid-name' }, item.name),
      h('div', { className: 'grid-cat' }, item.category),
      h('div', { className: 'grid-footer' },
        h('div', null,
          h('div', { className: 'grid-price' },
            fmt(listing.price),
            h(SteamMarketLink, { item, compact: true })
          ),
          item.steamPrice && parseFloat(item.steamPrice) > parseFloat(listing.price) &&
            h('div', { className: 'grid-steam-price' }, fmt(item.steamPrice))
        ),
        listingCount > 1 && h('div', { className: 'grid-supply' }, listingCount + ' listings')
      )
    )
  );
}

export function ListingRow({ listing, onClick, onBuy }) {
  const item = listing?.item;
  if (!item) return null;
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  const disc = discountPct(listing.price, item.steamPrice);
  return h('tr', { onClick },
    h('td', null,
      h('div', { className: 'item-cell' },
        h('div', { className: 'item-thumb' }, h(ItemImage, { item, variant: 'thumb' })),
        h('div', { className: 'item-info' },
          h('div', { className: 'item-name' }, item.name),
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
        h('div', { className: 'seller-avatar' }, (listing.sellerAvatar || 'US').toUpperCase()),
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
      h('button', { className: 'buy-btn', onClick: e => { e.stopPropagation(); onBuy(listing.id); } }, 'Buy')
    )
  );
}

export function TrendCard({ listing, onClick }) {
  const item = listing?.item;
  if (!item) return null;
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  return h('div', { className: 'trend-card', onClick },
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

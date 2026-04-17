// All modal dialogs. Each modal is a narrow component with a focused prop
// surface — none of them receive the full App state.
import { h, useState, useEffect, useCallback, useMemo, fmt, timeAgo, discountPct } from './utils.js';
import { ItemImage, RarityBadge, Sparkline, SteamMarketLink, MaterialIcon } from './primitives.js';
import { GridCard } from './cards.js';
import { InfoModal } from './info-modal.js';
import { AuctionBidPanel } from './csfloat-modals.js';
import {
  fetchInventory, fetchMyStall, fetchMyStallSold, fetchBestOfferPerListing, bulkAdjustStall, relistItem, cancelListing,
  fetchIncomingOffers, fetchOutgoingOffers, acceptOffer, rejectOffer, cancelOffer, counterOffer,
  fetchOfferThread, fetchSimilar, fetchItemVelocity, reportListing, fetchReportReasons,
  depositFunds, withdrawFunds, cancelPendingWithdrawal, updateStallListing, setAwayMode,
  fetchProfile, fetchSteamInventory, syncSteam, listFromSteam,
  fetchBuyOrders, deleteBuyOrder, fetchAutoBids, cancelAutoBid, cancelAllAutoBids, fetchApiKeys, createApiKey, revokeApiKey,
  fetchSupportTickets, fetchSupportTicket, createSupportTicket, replySupportTicket, resolveSupportTicket,
  fetchTrades, tradeAccept, tradeMarkSent, tradeConfirm, tradeDispute, tradeCancel,
  fetchTradeMessages, postTradeMessage,
  setEmail, verifyEmail, resendEmailVerification, setTradeUrl, enroll2fa, confirm2fa, disable2fa,
  fetchListings, fetchItem, leaveReview, fetchReviewSummary, fetchRecentSales,
  fetchReviewsForUser, replyToReview, fetchBuyOrderCountForItem,
  fetchWatchlistCountForItem
} from './api.js';

export { InfoModal };

// ── Item detail ──────────────────────────────────────────────────
export function ItemModal({ item, listings, history, onClose, onBuy, onMakeOffer, me, onRefresh, onCreateBuyOrder, onAddToCart, cartHas }) {
  const [offerOpen, setOfferOpen] = useState(false);
  const [thread, setThread] = useState(null);
  const [chartRange, setChartRange] = useState('30D');
  const [similar, setSimilar] = useState(null);
  // Seller rating cache — { sellerUserId → { count, average } }. Populated
  // in parallel when the modal opens so every row in the Active Listings
  // section can show an inline reputation chip next to the seller name.
  const [sellerRatings, setSellerRatings] = useState({});
  // Pull the offer thread for the cheapest listing so buyers can see any
  // existing counter conversation before they bargain themselves.
  useEffect(() => {
    if (!listings[0]) return;
    fetchOfferThread(listings[0].id).then(setThread);
  }, [listings[0]?.id]);
  useEffect(() => {
    if (!item) return;
    fetchSimilar(item.id).then(setSimilar);
  }, [item?.id]);
  // Report-listing drawer state. `reportTarget` holds the listing the user
  // clicked 🚩 on; null = drawer closed. Reasons whitelist is fetched once on
  // first-open and reused.
  const [reportTarget, setReportTarget] = useState(null);
  const [reportReasons, setReportReasons] = useState([]);
  useEffect(() => {
    if (reportTarget && reportReasons.length === 0) {
      fetchReportReasons().then(r => setReportReasons(r.length ? r : [
        'Suspicious pricing', 'Likely scam / duplicate',
        'Wrong description or photos', 'Prohibited item',
        'Offensive content', 'Other'
      ]));
    }
  }, [reportTarget, reportReasons.length]);

  // Recent sale rows — 10 most-recent SOLD listings for this item. Drives
  // the "Recent sales" strip just below the price history chart. Empty
  // array when no sales yet (freshly indexed item).
  const [recentSales, setRecentSales] = useState([]);
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetchRecentSales(item.id).then(rows => { if (alive) setRecentSales(rows || []); });
    return () => { alive = false; };
  }, [item?.id]);
  // Active buy-orders count — social-proof chip in the header. Tells
  // sellers there's live demand for this exact item at ≥ $X. Pure
  // aggregate, no counterparty identities exposed.
  const [buyOrderInfo, setBuyOrderInfo] = useState({ count: 0, bestBid: null });
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetchBuyOrderCountForItem(item.id).then(info => {
      if (alive) setBuyOrderInfo(info || { count: 0, bestBid: null });
    });
    return () => { alive = false; };
  }, [item?.id]);
  const buyOrderCount = buyOrderInfo.count;
  const bestBid = buyOrderInfo.bestBid;
  // Watcher count — number of users with an ACTIVE server-side price
  // alert on this item. Pure social-proof chip; aggregate only, no
  // watcher identities exposed.
  const [watcherCount, setWatcherCount] = useState(0);
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetchWatchlistCountForItem(item.id).then(n => { if (alive) setWatcherCount(n); });
    return () => { alive = false; };
  }, [item?.id]);
  // Trade velocity — "N sold · 7d" activity chip in the header. Social
  // proof via realised sales (complement to the buy-order count which
  // shows demand without transactions). Payload also includes the
  // most recent SOLD listing's price + timestamp for the "last sold"
  // chip — different data point from current floor.
  const [velocity, setVelocity] = useState({ soldLast7d: 0, soldLast30d: 0, volumeLast30d: 0, lastSoldPrice: null, lastSoldAt: null });
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetchItemVelocity(item.id).then(v => { if (alive) setVelocity(v || { soldLast7d: 0, soldLast30d: 0, volumeLast30d: 0, lastSoldPrice: null, lastSoldAt: null }); });
    return () => { alive = false; };
  }, [item?.id]);
  useEffect(() => {
    const ids = [...new Set(listings.map(l => l.sellerUserId).filter(Boolean))];
    if (ids.length === 0) return;
    let alive = true;
    Promise.all(ids.map(id =>
      fetchReviewSummary(id).then(s => ({ id, summary: s })).catch(() => ({ id, summary: null }))
    )).then(results => {
      if (!alive) return;
      const next = {};
      results.forEach(r => { if (r.summary) next[r.id] = r.summary; });
      setSellerRatings(next);
    });
    return () => { alive = false; };
  }, [listings.map(l => l.id).join(',')]);

  // Filter the history for the selected chart range. Our seed data is 30
  // days, so 7D = last 7 rows, 30D = all, ALL = all. Once we have longer
  // history this still works unchanged.
  const slicedHistory = useMemo(() => {
    if (!history || history.length === 0) return history;
    if (chartRange === '7D')  return history.slice(-7);
    if (chartRange === '30D') return history.slice(-30);
    return history;
  }, [history, chartRange]);
  const [offerAmt, setOfferAmt]   = useState('');
  const [offerErr, setOfferErr]   = useState('');
  const [offerBusy, setOfferBusy] = useState(false);
  const trendUp = item.trendPercent > 0, trendFlat = item.trendPercent === 0;
  const change30d = history.length > 1
    ? (parseFloat(item.lowestPrice) - parseFloat(history[0]?.price || item.lowestPrice)).toFixed(2)
    : '0.00';
  const changePct = history.length > 1 && parseFloat(history[0]?.price)
    ? ((change30d / parseFloat(history[0].price)) * 100).toFixed(1)
    : '0.0';
  // Price-range extremes across the full loaded history. Buyers anchor
  // fairness on "how low has this been?" — the ATL chip surfaces the
  // answer in one glance alongside the current floor. We also expose
  // the 30D high so shoppers can tell whether the current ask is near
  // the recent ceiling or the recent floor.
  const priceExtremes = useMemo(() => {
    if (!history || history.length < 2) return null;
    const nums = history.map(r => parseFloat(r.price)).filter(n => Number.isFinite(n) && n > 0);
    if (nums.length < 2) return null;
    const last30 = nums.slice(-30);
    return {
      allTimeLow:  Math.min.apply(null, nums),
      high30d:     Math.max.apply(null, last30),
      low30d:      Math.min.apply(null, last30)
    };
  }, [history]);

  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal', onClick: e => e.stopPropagation() },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close item details' }, '✕'),
      h('div', { className: 'modal-header' },
        h('div', { className: 'modal-preview' },
          h(ItemImage, { item, variant: 'hero' }),
          h(RarityBadge, { rarity: item.rarity })
        ),
        h('div', null,
          h('div', { className: 'modal-cat' }, item.category),
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' } },
            h('div', { className: 'modal-name' }, item.name),
            // Tiny share button — copies the canonical /item/:id URL so
            // sellers can drop it into Discord/Steam chat without leaving
            // the detail view. Silent success via the app's existing
            // toast channel if we had one here — falls back to a green
            // flash on the button itself.
            h('button', {
              className: 'btn btn-ghost',
              style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)', opacity: 0.75 },
              title: 'Copy link to this item',
              onClick: async (e) => {
                e.stopPropagation();
                const url = window.location.origin + '/item/' + item.id;
                try {
                  if (navigator.clipboard?.writeText) {
                    await navigator.clipboard.writeText(url);
                  } else {
                    window.prompt('Copy this link:', url);
                    return;
                  }
                  const btn = e.currentTarget;
                  const prev = btn.textContent;
                  btn.textContent = '✓ Copied';
                  btn.style.color = 'var(--green)';
                  setTimeout(() => { btn.textContent = prev; btn.style.color = ''; }, 1400);
                } catch (_) {
                  window.prompt('Copy this link:', url);
                }
              }
            }, '⎘ Copy link')
          ),
          h('div', { className: 'modal-stats' },
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Floor Price'),
              h('div', { className: 'modal-stat-val accent' }, fmt(item.lowestPrice))
            ),
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Steam Price'),
              item.steamPrice
                ? h('div', null,
                    h('div', { className: 'modal-stat-val', style: { textDecoration: 'line-through', color: 'var(--text-muted)' } }, fmt(item.steamPrice)),
                    discountPct(item.lowestPrice, item.steamPrice) > 0
                      ? h('div', { style: { fontSize: 11, fontWeight: 700, color: 'var(--green)', marginTop: 2 } },
                          `Save ${discountPct(item.lowestPrice, item.steamPrice)}%`
                        )
                      : null
                  )
                : h('div', { className: 'modal-stat-val' }, '—')
            ),
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, '30D Change'),
              h('div', { className: `modal-stat-val ${trendFlat ? '' : trendUp ? 'green' : 'red'}` },
                `${trendUp ? '+' : ''}${change30d} (${changePct}%)`
              )
            ),
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Supply'),
              h('div', { className: 'modal-stat-val' }, Number(item.supply).toLocaleString())
            )
          ),
          // Demand chip — number of standing buy orders pinned to this
          // item. Silent when 0 so new items don't look empty. Clicking
          // opens the buy orders page with this item preselected.
          buyOrderCount > 0 && h('div', { className: 'modal-demand-chip' },
            h('span', { className: 'modal-demand-chip-num' }, buyOrderCount),
            ' buyer', buyOrderCount === 1 ? '' : 's',
            ' want', buyOrderCount === 1 ? 's' : '', ' this right now'
          ),
          // Watcher chip — passive-demand signal complementing the
          // buyer-demand chip. "N watching" tells you who's waiting for
          // a price drop; the buy-order chip tells you who's committed
          // to paying up to $X right now. Different signals, both
          // aggregate-only. Silent when nobody is watching so new
          // items don't look empty.
          watcherCount > 0 && h('div', { className: 'modal-demand-chip', style: { background: 'rgba(236,72,153,0.12)', borderColor: 'rgba(236,72,153,0.4)', color: '#ec4899' } },
            h('span', { className: 'modal-demand-chip-num' }, watcherCount),
            ' watching'
          ),
          // Best-bid chip — top-of-book from the buy-order side. Pairs
          // with the demand count: "N want this" tells you whether
          // there's pressure, "Best bid $X" tells you the cheapest way
          // to auto-match. Silent when no standing bids.
          bestBid != null && bestBid > 0 &&
            h('div', { className: 'modal-demand-chip', style: { background: 'rgba(251,191,36,0.12)', borderColor: 'rgba(251,191,36,0.4)', color: '#fbbf24' } },
              'Best bid · ',
              h('span', { className: 'modal-demand-chip-num' }, fmt(bestBid))
            ),
          // Velocity chip — social proof via realised sales, complement
          // to the demand chip. Shows "N sold this week" when there's
          // any 7d activity; "N sold this month" fallback when slower.
          (velocity.soldLast7d > 0 || velocity.soldLast30d > 0) &&
            h('div', { className: 'modal-demand-chip', style: { background: 'rgba(34,197,94,0.15)', borderColor: 'rgba(34,197,94,0.4)', color: '#22c55e' } },
              h('span', { className: 'modal-demand-chip-num' },
                velocity.soldLast7d > 0 ? velocity.soldLast7d : velocity.soldLast30d),
              velocity.soldLast7d > 0 ? ' sold this week' : ' sold this month'
            ),
          // Last-sold chip — price at the most recent settlement. A
          // useful complement to floor because a listing can drop the
          // floor below any historic sale without any trades at that
          // price. Silent when the item has never sold.
          velocity.lastSoldPrice != null && velocity.lastSoldAt != null &&
            h('div', { className: 'modal-demand-chip', style: { background: 'var(--bg-elevated)', borderColor: 'var(--border)', color: 'var(--text-secondary)' } },
              'Last sold · ',
              h('span', { className: 'modal-demand-chip-num', style: { color: 'var(--text-primary)' } },
                fmt(velocity.lastSoldPrice)),
              ' · ', timeAgo(velocity.lastSoldAt)
            ),
          // 30d volume chip — total dollars of sold listings for this
          // item in the last 30 days. Complements the count chip
          // ("N sold this month") with the $ scale. Silent when zero.
          parseFloat(velocity.volumeLast30d) > 0 &&
            h('div', { className: 'modal-demand-chip', style: { background: 'rgba(96,165,250,0.12)', borderColor: 'rgba(96,165,250,0.35)', color: '#60a5fa' } },
              h('span', { className: 'modal-demand-chip-num' }, fmt(velocity.volumeLast30d)),
              ' volume · 30d'
            ),
          // 30D range chip — "is the current ask near the recent ceiling
          // or the recent floor?" A cheap anchor for buyers. Silent until
          // there are at least two price-history rows to compare.
          priceExtremes &&
            h('div', { className: 'modal-demand-chip', style: { background: 'var(--bg-elevated)', borderColor: 'var(--border)', color: 'var(--text-secondary)' } },
              '30D range · ',
              h('span', { className: 'modal-demand-chip-num', style: { color: 'var(--text-primary)' } },
                `${fmt(priceExtremes.low30d)} – ${fmt(priceExtremes.high30d)}`)
            ),
          // All-time low chip — standard buyer reference point: "is this
          // below the cheapest it's ever been?" We colour it purple to
          // distinguish from the neutral 30D range chip.
          priceExtremes && priceExtremes.allTimeLow < priceExtremes.low30d &&
            h('div', { className: 'modal-demand-chip', style: { background: 'rgba(168,85,247,0.12)', borderColor: 'rgba(168,85,247,0.4)', color: '#a855f7' } },
              'All-time low · ',
              h('span', { className: 'modal-demand-chip-num' }, fmt(priceExtremes.allTimeLow))
            )
        )
      ),

      h('div', { className: 'modal-body' },
        listings[0] && listings[0].listingType === 'AUCTION' && h(AuctionBidPanel, {
          listing: listings[0], me, onPlaced: onRefresh
        }),
        h('div', { className: 'modal-section-title' },
          h('div', { className: 'section-title-dot' }),
          'Price History',
          h('div', { className: 'chart-range' },
            ['7D','30D','ALL'].map(r => h('button', {
              key: r,
              className: `chart-range-btn ${chartRange === r ? 'active' : ''}`,
              onClick: () => setChartRange(r)
            }, r))
          )
        ),
        h('div', { className: 'chart-wrap' },
          slicedHistory && slicedHistory.length >= 2
            ? h(Sparkline, { data: slicedHistory, color: trendUp ? '#4ade80' : trendFlat ? '#60a5fa' : '#f87171', height: 150 })
            : h('div', { className: 'chart-empty', style: { height: 150, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)', fontSize: 13, border: '1px dashed var(--border)', borderRadius: 10 } },
                'Price history will appear here after the next market sync.')
        ),

        // Recent sales strip — buyers anchor fairness on the actual sale
        // ladder (floor price alone tells them what sellers ASK for, not
        // what the market PAID). Counterparties aren't surfaced — buyer
        // privacy is non-negotiable.
        recentSales && recentSales.length > 0 && h('div', null,
          h('div', { className: 'modal-section-title' },
            h('div', { className: 'section-title-dot' }),
            `Recent sales (${recentSales.length})`
          ),
          h('div', { className: 'recent-sales-list' },
            recentSales.map(s => h('div', { key: s.listingId, className: 'recent-sales-row' },
              h('span', { className: 'recent-sales-type' },
                s.listingType === 'AUCTION' ? 'Auction' : 'Buy now'),
              h('span', { className: 'recent-sales-price' }, fmt(s.price)),
              h('span', { className: 'recent-sales-time' }, timeAgo(s.soldAt))
            ))
          )
        ),

        thread && thread.length > 0 && h('div', null,
          h('div', { className: 'modal-section-title' }, h('div', { className: 'section-title-dot' }), `Offer thread (${thread.length})`),
          h('div', { className: 'item-offer-thread' },
            thread.slice(-8).map(o => h('div', {
              key: o.id,
              className: `item-offer-bubble ${o.author === 'SELLER' ? 'seller' : 'buyer'} ${o.status !== 'PENDING' ? 'past' : ''}`
            },
              h('div', { className: 'item-offer-head' },
                o.author === 'SELLER' ? 'Seller counter' : (o.buyerName || 'buyer'),
                ' · ', timeAgo(o.createdAt)
              ),
              h('div', { className: 'item-offer-amt' }, fmt(o.amount)),
              h('div', { className: 'item-offer-status' }, o.status)
            ))
          )
        ),
        h('div', { className: 'modal-section-title' }, h('div', { className: 'section-title-dot' }), `Active Listings (${listings.length})`),
        h('div', { className: 'modal-listings' },
          listings.length === 0
            ? h('div', { style: { color: 'var(--text-muted)', fontSize: 13, padding: '12px 0' } }, 'No active listings')
            : listings.slice(0, 6).map(l => {
                const rating = l.sellerUserId ? sellerRatings[l.sellerUserId] : null;
                return h('div', { key: l.id, className: 'modal-listing-row' },
                  h('div', { className: 'modal-seller-av' }, (l.sellerAvatar || l.sellerName?.substring(0,2) || 'US').toUpperCase()),
                  h('div', { className: 'modal-seller-info' },
                    l.sellerUserId
                      ? h('a', {
                          className: 'modal-seller-name',
                          href: '/stall/' + l.sellerUserId,
                          onClick: (e) => e.stopPropagation(),
                          style: { color: 'inherit', textDecoration: 'none' },
                          title: `View ${l.sellerName}'s stall`
                        }, l.sellerName)
                      : h('span', { className: 'modal-seller-name' }, l.sellerName),
                    rating && rating.count > 0 && h('span', { className: 'modal-seller-rating', title: `${rating.count} review${rating.count === 1 ? '' : 's'}` },
                      '★ ', rating.average.toFixed(1),
                      h('span', { className: 'modal-seller-rating-count' }, ` (${rating.count})`)
                    )
                  ),
                  h('span', { className: 'modal-listing-condition' }, '#' + l.id),
                  l.listedAt && h('span', {
                    className: 'modal-listing-condition',
                    title: 'Listed ' + new Date(l.listedAt).toLocaleString(),
                    style: { fontSize: 10, color: 'var(--text-muted)' }
                  },
                    (Date.now() - l.listedAt < 48 * 3600 * 1000) ? '🆕 ' : '',
                    'listed ', timeAgo(l.listedAt)
                  ),
                  h('div', { className: 'modal-listing-rarity-bar' }),
                  (() => {
                    const ref = parseFloat(item.steamPrice) || 0;
                    const p = parseFloat(l.price) || 0;
                    if (ref <= 0 || p <= 0 || p >= ref) return null;
                    const pct = Math.round((1 - p / ref) * 100);
                    if (pct < 5) return null;
                    return h('span', {
                      style: {
                        fontSize: 10, fontWeight: 800, padding: '2px 6px', borderRadius: 4,
                        background: pct >= 25 ? 'rgba(34,197,94,0.15)' : 'rgba(251,191,36,0.15)',
                        color:      pct >= 25 ? '#22c55e' : '#fbbf24',
                        marginRight: 6
                      },
                      title: `Listed at ${pct}% below Steam market (${fmt(ref)})`
                    }, '−' + pct + '%');
                  })(),
                  h('span', { className: 'modal-listing-price' }, fmt(l.price)),
                  h('button', { className: 'buy-btn', onClick: () => onBuy(l.id) }, 'Buy'),
                  me && me.id !== l.sellerUserId && h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '4px 8px', fontSize: 12, border: '1px solid var(--border)', opacity: 0.7 },
                    onClick: () => setReportTarget(l),
                    title: 'Report this listing to the moderation team',
                    'aria-label': `Report listing ${l.id}`
                  }, '🚩')
                );
              })
        ),

        // ── Similar items strip ────────────────────────────────────
        similar && similar.length > 0 && h('div', null,
          h('div', { className: 'modal-section-title', style: { marginTop: 22 } },
            h('div', { className: 'section-title-dot' }), 'You might also like'),
          h('div', { className: 'similar-strip' },
            similar.map(it => h('a', {
              key: it.id,
              className: 'similar-card',
              href: '/item/' + it.id,
              title: it.name
            },
              h('div', { className: 'similar-thumb' }, h(ItemImage, { item: it, variant: 'thumb' })),
              h('div', { className: 'similar-name' }, it.name),
              h('div', { className: 'similar-price' }, fmt(it.lowestPrice || 0))
            ))
          )
        )
      ),

      false && h('div', { style: { padding: '0 30px 14px' } },
        h('div', { className: 'wallet-input-label' }, 'Make an offer (must be below floor) [moved below]'),
        h('div', { style: { display: 'flex', gap: 10 } },
          h('input', {
            className: 'wallet-amount-input',
            type: 'number', min: '0.01', step: '0.01',
            placeholder: (parseFloat(item.lowestPrice) * 0.85).toFixed(2),
            value: offerAmt,
            onChange: e => setOfferAmt(e.target.value),
            style: { flex: 1 }
          }),
          h('button', {
            className: 'btn btn-accent',
            style: { padding: '0 22px', fontSize: 13 },
            disabled: offerBusy || !offerAmt,
            onClick: async () => {
              setOfferErr('');
              setOfferBusy(true);
              try {
                const res = await onMakeOffer(listings[0]?.id, parseFloat(offerAmt));
                if (res && res.error) { setOfferErr(res.message || res.error); return; }
                setOfferOpen(false);
                setOfferAmt('');
                onClose();
              } finally { setOfferBusy(false); }
            }
          }, offerBusy ? '...' : 'Send')
        ),
        offerErr && h('div', { className: 'wallet-error' }, offerErr)
      ),

      h('div', { className: 'modal-actions' },
        h('button', {
          className: 'btn btn-accent',
          disabled: !listings[0],
          onClick: () => listings[0] && onBuy(listings[0].id),
          'aria-label': listings[0] ? `Buy for ${fmt(listings[0].price)}` : 'Out of stock'
        },
          listings[0] ? `Buy Now · ${fmt(listings[0].price)}` : 'Out of Stock'
        ),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          onClick: () => setOfferOpen(o => !o),
          disabled: !listings[0]
        }, offerOpen ? 'Cancel Offer' : 'Make Offer'),
        onCreateBuyOrder && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          onClick: () => onCreateBuyOrder(item),
          title: 'Create a standing buy order for this item'
        }, h(MaterialIcon, { name: 'bolt', size: 16 }), ' Place Buy Order'),
        me && (() => {
          // When the item has zero active listings, the floor price is 0
          // and "drop to target" is meaningless. Swap to a restock-style
          // alert — uses the $100k cap as the target so any future
          // listing at any price trips the sweeper.
          const outOfStock = !listings || listings.length === 0;
          return h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            title: outOfStock
              ? 'Get notified when this item is listed again'
              : 'Get notified when the floor price drops to your target',
            onClick: async () => {
              let target;
              if (outOfStock) {
                if (!confirm(`Notify me when ${item.name} is listed again?`)) return;
                target = 100000;  // max cap — any future listing trips the alert
              } else {
                const suggest = (parseFloat(item.lowestPrice) * 0.85).toFixed(2);
                const raw = window.prompt(
                  `Notify me when ${item.name} drops to or below $:`,
                  suggest);
                if (!raw) return;
                target = parseFloat(raw);
                if (!target || target <= 0) { alert('Enter a positive dollar amount'); return; }
              }
              const { createWatchlistAlert } = await import('./api.js');
              const res = await createWatchlistAlert(item.id, target);
              if (res && (res.error || res.code)) {
                alert(res.message || res.error || 'Could not save alert');
              } else if (outOfStock) {
                alert(`✓ Restock alert set. You'll get a notification when ${item.name} is listed again.`);
              } else {
                alert(`✓ Price alert set. You'll get a notification when ${item.name} drops to $${target.toFixed(2)}.`);
              }
            }
          },
            h(MaterialIcon, { name: 'notifications_active', size: 16 }),
            outOfStock ? ' Notify When Listed' : ' Set Price Alert'
          );
        })(),
        onAddToCart && listings[0] && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          disabled: cartHas && cartHas(listings[0].id),
          onClick: () => onAddToCart(listings[0]),
          title: 'Add cheapest listing to cart'
        }, h(MaterialIcon, { name: 'shopping_cart', size: 16 }),
          cartHas && cartHas(listings[0].id) ? ' In Cart' : ' Add to Cart'),
        h(SteamMarketLink, { item }),
        h('button', { className: 'btn btn-ghost btn-wishlist', style: { border: '1px solid var(--border)' } }, '♡')
      ),

      offerOpen && h('div', { style: { padding: '14px 30px', background: 'var(--bg-secondary)', borderRadius: 8, margin: '10px 30px' } },
        h('div', { className: 'wallet-input-label', style: { marginBottom: 8, fontSize: 13, color: 'var(--text-secondary)' } }, 'Your offer (must be below asking price)'),
        h('div', { style: { display: 'flex', gap: 10 } },
          h('input', {
            className: 'wallet-amount-input',
            type: 'number', min: '0.01', step: '0.01',
            placeholder: (parseFloat(item.lowestPrice) * 0.85).toFixed(2),
            value: offerAmt,
            onChange: e => setOfferAmt(e.target.value),
            style: { flex: 1 },
            autoFocus: true
          }),
          h('button', {
            className: 'btn btn-accent',
            style: { padding: '0 22px', fontSize: 13 },
            disabled: offerBusy || !offerAmt,
            onClick: async () => {
              setOfferErr('');
              setOfferBusy(true);
              try {
                const res = await onMakeOffer(listings[0]?.id, parseFloat(offerAmt));
                if (res && res.error) { setOfferErr(res.message || res.error); return; }
                setOfferOpen(false);
                setOfferAmt('');
                onClose();
              } finally { setOfferBusy(false); }
            }
          }, offerBusy ? '...' : 'Send Offer')
        ),
        offerErr && h('div', { style: { color: 'var(--red)', fontSize: 12, marginTop: 6 } }, offerErr)
      ),
      reportTarget && h(ReportListingDrawer, {
        listing: reportTarget,
        reasons: reportReasons,
        onCancel: () => setReportTarget(null),
        onSubmitted: () => { setReportTarget(null); onRefresh && onRefresh(); }
      })
    )
  );
}

function ReportListingDrawer({ listing, reasons, onCancel, onSubmitted }) {
  const [reason, setReason] = useState('Suspicious pricing');
  const [note, setNote]     = useState('');
  const [busy, setBusy]     = useState(false);
  const [err, setErr]       = useState('');
  const [done, setDone]     = useState('');
  const submit = async () => {
    if (!reason) { setErr('Pick a reason first'); return; }
    setErr(''); setBusy(true);
    try {
      const res = await reportListing(listing.id, reason, note);
      if (res && (res.error || res.code)) {
        setErr(res.message || res.error);
        return;
      }
      setDone(res.thanks || 'Report received. Thanks — an admin will review it.');
      setTimeout(() => onSubmitted(), 1500);
    } catch (e) {
      setErr('Something went wrong. Try again.');
    } finally { setBusy(false); }
  };
  return h('div', {
    className: 'cart-confirm-backdrop',
    onClick: onCancel,
    style: { zIndex: 100 }
  },
    h('div', {
      className: 'cart-confirm-panel',
      style: { maxWidth: 420 },
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-modal': true,
      'aria-label': `Report listing ${listing.id}`
    },
      h('div', { className: 'cart-confirm-title' }, '🚩 Report listing'),
      h('div', { className: 'cart-confirm-sub', style: { marginBottom: 14 } },
        `Listing #${listing.id} · ${listing.sellerName || 'Seller'} · ${fmt(listing.price)}`),
      done
        ? h('div', { style: {
            background: 'rgba(34,197,94,0.1)',
            border: '1px solid rgba(34,197,94,0.4)',
            color: '#22c55e',
            padding: 12, borderRadius: 6, fontSize: 13, textAlign: 'center'
          } }, done)
        : h('div', null,
            h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Reason'),
            h('select', {
              className: 'price-input',
              style: { width: '100%', marginBottom: 12 },
              value: reason,
              onChange: e => setReason(e.target.value),
              disabled: busy
            }, reasons.map(r => h('option', { key: r, value: r }, r))),
            h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Details (optional)'),
            h('textarea', {
              className: 'price-input',
              style: { width: '100%', minHeight: 70, marginBottom: 12, resize: 'vertical' },
              placeholder: 'Add any extra context that would help moderators.',
              value: note,
              maxLength: 500,
              onChange: e => setNote(e.target.value),
              disabled: busy
            }),
            err && h('div', { className: 'wallet-error', style: { marginBottom: 10 } }, err),
            h('div', { style: { display: 'flex', gap: 10, justifyContent: 'flex-end' } },
              h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: busy, onClick: onCancel }, 'Cancel'),
              h('button', { className: 'btn btn-accent', disabled: busy, onClick: submit },
                busy ? 'Submitting…' : 'Submit report')
            )
          )
    )
  );
}

// ── FAQ / Support ───────────────────────────────────────────────
export function FaqModal({ onClose }) {
  const Q = (q, a) => h('div', { style: { marginBottom: 20 } },
    h('div', { style: { fontWeight: 700, color: 'var(--text-primary)', marginBottom: 6, fontSize: 14 } }, q),
    h('div', { style: { color: 'var(--text-secondary)', lineHeight: 1.6, fontSize: 13 } }, a)
  );
  return h(InfoModal, { title: 'Support & Help', onClose },
    h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 20, padding: '10px 14px', background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 8 } },
      'Need something not covered here? Drop a message in the Live Chat on the left and someone will pick it up.'),
    Q('What is SkinBox?',
      "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Every listing comes from a real seller who sets their own price — we're the middle layer that makes transactions safe, fast, and cheaper than going through the Steam store."),
    Q('How do I sign in?',
      "Click the blue Steam button in the top-right. You'll bounce to steamcommunity.com, approve the login, and land back here already authenticated. Your Steam password never touches our servers — everything goes through OpenID."),
    Q('How do I buy something?',
      "Top up your wallet first, then click any item and hit Buy. Funds are charged from your balance instantly — there's no bid-and-wait or 7-day trade hold like the Steam market."),
    Q('How does depositing work?',
      "Open your Wallet, pick Deposit, enter an amount (anything from $1 to $10,000), and you'll be handed to Stripe's checkout page. Once the payment clears, our webhook credits your balance automatically."),
    Q('How do withdrawals work?',
      "From your Wallet, pick Withdraw, enter a destination (Stripe Connect id, email, or a note) and the amount. Your balance is debited immediately and the payout is processed within 24 hours. A small network fee may apply depending on destination."),
    Q('Why is SkinBox cheaper than Steam?',
      "Steam charges 12% in platform fees on Workshop sales and forces sellers into their pricing ladder. On SkinBox, sellers set whatever price they like — usually 10-30% below what the Steam store asks. The green '−%' chip on each card shows exactly how much you save versus Steam."),
    Q('Do s&box items have wear levels?',
      "No. That's a Counter-Strike thing. s&box cosmetics are single items without Factory-New / Field-Tested / Battle-Scarred variants — closer to how Rust skins work. The item you pick is the exact item you receive."),
    Q('Can I sell the items I own?',
      "Yes. Anything you've bought on SkinBox appears under Sell Items. Pick an item, set a price, and it goes live in your stall under My Stall. When it sells, the buyer's payment (minus a 2% platform fee) drops straight into your wallet."),
    Q('What is a Stall?',
      "Your Stall is your personal storefront — the list of items you currently have up for sale. Other users can browse it via your profile. You can cancel any listing from My Stall and the item returns to your inventory."),
    Q('What are Offers?',
      "Offers are non-binding price suggestions. A buyer can propose less than your asking price; you get a notification and can accept or reject from the Offers tab."),
    Q('Is my money safe?',
      "Deposits go through Stripe, the same processor used by millions of websites. We never store card details — only the amount and a Stripe reference. Withdrawal requests are logged and reviewed before payout. All balances are held in USD."),
    Q('I found a bug / my purchase is stuck',
      "Open Live Chat on the left or the Support entry in the user menu and include the transaction id from your Trades tab. We'll refund or retry as needed.")
  );
}

// ── Settings ────────────────────────────────────────────────────
export function SettingsModal({ onClose }) {
  const [currency, setCurrency] = useState(localStorage.getItem('sb_currency') || 'USD');
  const [notifs, setNotifs]     = useState(localStorage.getItem('sb_notifs') !== 'false');
  const [sounds, setSounds]     = useState(localStorage.getItem('sb_sounds') !== 'false');
  // Muted notification buckets — values match the `typeOf(kind)` classifier
  // in csfloat-modals.js: TRADES / AUCTIONS / OFFERS / WALLET / OTHER.
  // Muted buckets stay hidden from the nav bell and the Notifications
  // page via a dedicated filter helper.
  const [muted, setMutedState] = useState(() => {
    try { return new Set(JSON.parse(localStorage.getItem('sb_mute_kinds') || '[]')); }
    catch { return new Set(); }
  });
  const toggleMute = (cat) => {
    const next = new Set(muted);
    if (next.has(cat)) next.delete(cat); else next.add(cat);
    setMutedState(next);
    try { localStorage.setItem('sb_mute_kinds', JSON.stringify([...next])); } catch (_) {}
  };
  const [reduceMotion, setRM]   = useState(localStorage.getItem('sb_reduce_motion') === '1');
  const [highContrast, setHC]   = useState(localStorage.getItem('sb_contrast') === '1');

  useEffect(() => {
    localStorage.setItem('sb_currency', currency);
    // Fire a storage event so other tabs update immediately; the fmt() helper
    // reads localStorage on every call so the next render already shows the
    // new currency in THIS tab.
  }, [currency]);
  useEffect(() => { localStorage.setItem('sb_notifs', notifs); }, [notifs]);
  useEffect(() => { localStorage.setItem('sb_sounds', sounds); }, [sounds]);
  useEffect(() => {
    localStorage.setItem('sb_reduce_motion', reduceMotion ? '1' : '0');
    document.documentElement.classList.toggle('reduce-motion', reduceMotion);
  }, [reduceMotion]);
  useEffect(() => {
    localStorage.setItem('sb_contrast', highContrast ? '1' : '0');
    document.documentElement.classList.toggle('high-contrast', highContrast);
  }, [highContrast]);

  const Row = (label, sublabel, control) => h('div', { className: 'settings-row' },
    h('div', null,
      h('div', { className: 'settings-label' }, label),
      sublabel && h('div', { className: 'settings-sublabel' }, sublabel)
    ),
    control
  );
  const Toggle = (on, onChange) => h('div', {
    className: `chat-toggle ${on ? '' : 'off'}`,
    onClick: onChange
  });

  const resetLocal = () => {
    if (!confirm('Reset UI preferences (currency, theme, reduce-motion, high-contrast, sounds)? Watchlist and cart are kept.')) return;
    ['sb_currency','sb_notifs','sb_sounds','sb_reduce_motion','sb_contrast','sb_theme','sb_privacy']
      .forEach(k => localStorage.removeItem(k));
    location.reload();
  };
  // Nuke-everything button — wipes every sb_* key (cart, watchlist,
  // alerts, snapshots, recently viewed, recent searches, dismissed
  // banners, pending email, dismissed nudges, etc). Account data
  // (wallet, listings, trades) lives server-side and is untouched.
  const wipeAllLocal = () => {
    if (!confirm('Wipe ALL local data — cart, watchlist, price alerts, recent searches, dismissed banners, everything in this browser? Your account data on the server is not affected.')) return;
    const keys = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith('sb_')) keys.push(k);
    }
    keys.forEach(k => localStorage.removeItem(k));
    location.reload();
  };

  return h(InfoModal, { title: 'Settings', onClose },
    Row('Currency', 'Prices shown in your chosen currency (stored as USD)', h('select', {
        className: 'sort-select', value: currency,
        onChange: e => setCurrency(e.target.value)
      },
      h('option', { value: 'USD' }, 'USD · $'),
      h('option', { value: 'EUR' }, 'EUR · €'),
      h('option', { value: 'GBP' }, 'GBP · £'),
      h('option', { value: 'CAD' }, 'CAD · CA$'),
      h('option', { value: 'AUD' }, 'AUD · A$'),
      h('option', { value: 'BRL' }, 'BRL · R$'),
      h('option', { value: 'JPY' }, 'JPY · ¥')
    )),
    Row('Sale notifications', 'Toast when someone buys', Toggle(notifs, () => setNotifs(v => !v))),
    Row('Sound effects',      'Play sounds on actions', Toggle(sounds, () => setSounds(v => !v))),
    // Mute per-category — hide alerts you don't care about from the bell
    // and the Notifications page without silencing everything else.
    Row('Mute notification types',
      'Hide these categories from the bell + /notifications. Applied client-side; the server still records the event.',
      h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: 6, justifyContent: 'flex-end', maxWidth: 320 } },
        [
          { id: 'TRADES',   label: '⇄ Trades' },
          { id: 'AUCTIONS', label: '🏆 Auctions' },
          { id: 'OFFERS',   label: '💬 Offers' },
          { id: 'WALLET',   label: '$ Wallet' },
          { id: 'OTHER',    label: '• Other' }
        ].map(opt => h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${muted.has(opt.id) ? '' : 'active'}`,
          onClick: () => toggleMute(opt.id),
          title: muted.has(opt.id) ? 'Muted — click to unmute' : 'Click to mute this category'
        }, muted.has(opt.id) ? `🔕 ${opt.label}` : opt.label))
      )
    ),
    Row('Reduce motion',      'Disable animations for card hover + ticker scroll',
      Toggle(reduceMotion, () => setRM(v => !v))),
    Row('High contrast',      'Boost text / border contrast for readability',
      Toggle(highContrast, () => setHC(v => !v))),
    Row('Accent colour',      'Cycle through theme presets in the top-nav 🎨 menu.',
      h('span', { style: { color: 'var(--text-muted)', fontSize: 12 } }, 'Dark theme')
    ),
    h('div', { style: { marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--border)' } },
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10 } },
        'These preferences live in your browser. Your account data (wallet, listings, trades) is stored server-side and is not affected by these buttons.'),
      h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 11 },
          onClick: resetLocal
        }, 'Reset UI preferences'),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '8px 14px', fontSize: 11 },
          onClick: wipeAllLocal,
          title: 'Clear every piece of local state this site has stored (cart, watchlist, alerts, dismissed banners, recent searches, …)'
        }, '✕ Clear all local data')
      )
    )
  );
}

// ── Profile (tabbed — mirrors the CSFloat profile screen) ───────
// Tiny fetch helper used by the profile badge effects — avoids double-
// catching per call site. Returns the parsed body on 2xx, null otherwise.
async function safeFetchJson(url) {
  try {
    const r = await fetch(url, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}

export function ProfileModal({ onClose, me, wallet, transactions, onRefresh, initialTab }) {
  const [tab, setTab]             = useState(initialTab || 'personal');
  const [profile, setProfile]     = useState(null);
  const [privacy, setPrivacy]     = useState(() => localStorage.getItem('sb_privacy') === '1');
  const [syncing, setSyncing]     = useState(false);

  useEffect(() => {
    localStorage.setItem('sb_privacy', privacy ? '1' : '0');
  }, [privacy]);

  useEffect(() => {
    if (!me) return;
    fetchProfile().then(setProfile);
  }, [me]);

  if (!me) return h(InfoModal, { title: 'Profile', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your profile.')));

  const maskAmount = (val) => privacy ? '$•••••' : fmt(val);

  const runSync = async () => {
    setSyncing(true);
    try { await syncSteam(); const fresh = await fetchProfile(); setProfile(fresh); }
    finally { setSyncing(false); }
  };

  // Actionable-trade count — trades where the signed-in user is the
  // blocking party. Drives the red pip on the Trades tab so users see
  // at-a-glance that a trade needs their input. Fetched once at profile-
  // modal open; refreshes when the tab becomes active. Offer count is
  // pulled from the existing /offers/counts endpoint so the tab
  // mirrors the nav badge.
  const [actionableTradeCount, setActionableTradeCount] = useState(0);
  const [pendingOfferCount, setPendingOfferCount]       = useState(0);
  useEffect(() => {
    if (!me) return;
    let alive = true;
    (async () => {
      try {
        const [tr, oc] = await Promise.all([
          fetch('/api/trades', { credentials: 'same-origin' }).then(r => r.ok ? r.json() : []),
          safeFetchJson('/api/offers/counts')
        ]);
        if (!alive) return;
        const tradeN = (Array.isArray(tr) ? tr : []).filter(t =>
          (t.sellerUserId === me.id && ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND'].includes(t.state)) ||
          (t.buyerUserId  === me.id && t.state === 'PENDING_BUYER_CONFIRM')
        ).length;
        setActionableTradeCount(tradeN);
        setPendingOfferCount(Number(oc?.incomingPending || 0));
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [me?.id, tab]);

  const TABS = [
    { id: 'personal',     label: 'Personal Info' },
    { id: 'transactions', label: 'Transactions' },
    { id: 'buyorders',    label: 'Buy Orders' },
    { id: 'autobids',     label: 'Auto-Bids' },
    { id: 'trades',       label: 'Trades', badge: actionableTradeCount },
    { id: 'offers',       label: 'Offers', badge: pendingOfferCount },
    { id: 'reviews',      label: 'Reviews' },
    { id: 'support',      label: 'Support' },
    { id: 'developers',   label: 'Developers' },
  ];

  return h(InfoModal, { title: 'Profile', onClose },
    /* Hero: avatar + name + earnings privacy toggle + account standing bar */
    h('div', { className: 'profile-hero' },
      h('div', { className: 'profile-avatar' },
        me.avatarUrl ? h('img', { src: me.avatarUrl, alt: me.displayName }) : (me.displayName || 'U').substring(0, 2).toUpperCase()
      ),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { className: 'profile-name' }, me.displayName || 'Player'),
        h('div', { className: 'profile-id' },
          'Steam ID · ',
          h('span', { style: { fontFamily: 'JetBrains Mono, monospace' } }, me.steamId64)
        ),
        me.profileUrl && h('a', { href: me.profileUrl, target: '_blank', rel: 'noopener noreferrer', className: 'profile-link' }, 'View Steam profile ↗')
      ),
      h('button', {
        className: 'profile-privacy',
        onClick: () => setPrivacy(p => !p),
        title: privacy ? 'Show amounts' : 'Hide amounts'
      }, privacy ? '👁  Show amounts' : '🙈  Hide amounts')
    ),

    /* Hero stats — amounts optionally masked. Portfolio value is new: the
       backend sums inventory + active-listings by current floor so the user
       sees total $ tied up at a glance. */
    h('div', { className: 'profile-stats' },
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Balance'),
        h('div', { className: 'profile-stat-val accent' }, maskAmount(wallet?.balance || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label', title: 'Value of items you own plus everything you have listed for sale, priced at the current market floor.' }, 'Portfolio'),
        h('div', { className: 'profile-stat-val' }, privacy ? '$•••••' : fmt(profile?.portfolio?.totalValue || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Total Sold'),
        h('div', { className: 'profile-stat-val' }, privacy ? '$•••••' : fmt(profile?.stats?.totalSold || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Total Purchased'),
        h('div', { className: 'profile-stat-val' }, privacy ? '$•••••' : fmt(profile?.stats?.totalPurchased || 0))
      ),
      h('div', { className: 'profile-stat' },
        h('div', { className: 'profile-stat-label' }, 'Net'),
        h('div', {
          className: `profile-stat-val ${parseFloat(profile?.stats?.net || 0) >= 0 ? 'green' : 'red'}`
        }, privacy ? '$•••••' : fmt(profile?.stats?.net || 0))
      )
    ),

    /* Tabs */
    h('div', { className: 'profile-tabs' },
      TABS.map(t => h('button', {
        key: t.id,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        onClick: () => setTab(t.id)
      },
        t.label,
        t.badge > 0 && h('span', { className: 'profile-tab-badge' }, t.badge > 99 ? '99+' : t.badge)
      ))
    ),

    tab === 'personal' && h(ProfilePersonalTab, { me, profile, syncing, onSync: runSync, transactions }),
    tab === 'transactions' && h(ProfileTransactionsTab, { transactions, privacy }),
    tab === 'buyorders'   && h(ProfileBuyOrdersTab, null),
    tab === 'autobids'    && h(ProfileAutoBidsTab, null),
    tab === 'trades'      && h(ProfileTradesTab, { me, privacy }),
    tab === 'offers'      && h(ProfileOffersTab, null),
    tab === 'reviews'     && h(ProfileReviewsTab, { me }),
    tab === 'support'     && h(ProfileSupportTab, null),
    tab === 'developers'  && h(ProfileDevelopersTab, null)
  );
}

// Email-notification toggle row. Lazy-loads the PUT helper so this
// file doesn't gain a new top-level dependency; state is local, with
// the server value seeded from ProfileService.buildProfile.
function EmailPrefToggle({ initial }) {
  const [on, setOn] = useState(initial !== false);
  const [busy, setBusy] = useState(false);
  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    const next = !on;
    setOn(next);  // optimistic
    try {
      const { setEmailNotifications } = await import('./api.js');
      const res = await setEmailNotifications(next);
      if (res && (res.error || res.code)) {
        alert(res.message || res.error);
        setOn(!next);  // revert
      }
    } finally { setBusy(false); }
  };
  return h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
    h('div', {
      className: `chat-toggle ${on ? '' : 'off'}`,
      onClick: toggle,
      role: 'switch',
      'aria-checked': on,
      title: on ? 'Email notifications ON — click to disable' : 'Email notifications OFF — click to enable'
    }),
    h('span', { style: { fontSize: 11, color: 'var(--text-muted)' } },
      busy ? 'Saving…' : (on ? 'On' : 'Off'))
  );
}

// "Sellers you follow" row inside ProfilePersonalTab. Compact list +
// per-row unfollow. Silently hidden when the user follows nobody.
// Lazy-imports the API helpers so this file doesn't gain a new
// top-level dependency.
function FollowingListRow() {
  const [rows, setRows] = useState(null);
  const [names, setNames] = useState({});
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const { fetchFollowing } = await import('./api.js');
        const r = await fetchFollowing();
        if (!alive) return;
        setRows(Array.isArray(r) ? r : []);
        // Enrich with display names — one fetchItem-style call per
        // seller. There's no /api/users/{id}, so we use the public
        // stall endpoint which returns { seller: { displayName } }.
        const ids = [...new Set((r || []).map(x => x.sellerUserId))];
        Promise.all(ids.map(id =>
          fetch(`/api/listings/stall/${id}`, { credentials: 'same-origin' })
            .then(res => res.ok ? res.json() : null)
            .then(data => ({ id, name: data?.seller?.displayName }))
            .catch(() => ({ id, name: null }))
        )).then(results => {
          if (!alive) return;
          const n = {};
          results.forEach(rr => { if (rr.name) n[rr.id] = rr.name; });
          setNames(n);
        });
      } catch (_) { if (alive) setRows([]); }
    })();
    return () => { alive = false; };
  }, []);
  const doUnfollow = async (sellerId) => {
    const { unfollowSeller } = await import('./api.js');
    const res = await unfollowSeller(sellerId);
    if (res && (res.error || res.code)) {
      alert(res.message || res.error || 'Could not unfollow');
      return;
    }
    setRows(rs => (rs || []).filter(r => r.sellerUserId !== sellerId));
  };
  if (!rows || rows.length === 0) return null;
  return h('div', { className: 'profile-row' },
    h('div', { className: 'profile-row-label' }, `Following ${rows.length}`),
    h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 4, maxWidth: '100%' } },
      rows.slice(0, 10).map(r => h('div', {
        key: r.id,
        style: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }
      },
        h('a', {
          href: '/stall/' + r.sellerUserId,
          style: { color: 'var(--accent)', textDecoration: 'none', flex: 1 }
        }, names[r.sellerUserId] || ('Seller #' + r.sellerUserId)),
        h('button', {
          className: 'btn btn-ghost',
          style: { padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)' },
          onClick: () => doUnfollow(r.sellerUserId),
          title: 'Unfollow this seller'
        }, '✕')
      )),
      rows.length > 10 && h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
        `+ ${rows.length - 10} more`)
    )
  );
}

function ProfilePersonalTab({ me, profile, syncing, onSync, transactions }) {
  const [editingEmail, setEditingEmail] = useState(false);
  const [emailDraft, setEmailDraft]     = useState('');
  const [emailToken, setEmailToken]     = useState('');
  const [emailResult, setEmailResult]   = useState(null);

  const [enrolling, setEnrolling]   = useState(false);
  const [twofaSecret, setTwofaSecret] = useState('');
  const [twofaUrl, setTwofaUrl]     = useState('');
  const [twofaCode, setTwofaCode]   = useState('');
  const [twofaErr, setTwofaErr]     = useState('');
  const [twofaBusy, setTwofaBusy]   = useState(false);

  const hasEmail = !!profile?.user?.email;
  const emailVerified = profile?.user?.emailVerified;
  const has2fa = !!profile?.twoFactorEnabled;

  // ── Steam trade URL ───────────────────────────────────────────
  // Surfaces on every counterparty's trade row during the active escrow
  // window. Without this the buyer↔seller pairing has no Steam offer
  // channel (non-custodial model) and the trade stays stuck in
  // PENDING_SELLER_SEND forever.
  const [editingTradeUrl, setEditingTradeUrl] = useState(false);
  const [tradeUrlDraft, setTradeUrlDraft]     = useState('');
  const [tradeUrlErr, setTradeUrlErr]         = useState('');
  const [tradeUrlBusy, setTradeUrlBusy]       = useState(false);
  const hasTradeUrl = !!profile?.user?.tradeUrl;
  const saveTradeUrl = async (raw) => {
    setTradeUrlBusy(true); setTradeUrlErr('');
    try {
      const res = await setTradeUrl(raw);
      if (res.code || res.error) { setTradeUrlErr(res.message || res.error); return; }
      setEditingTradeUrl(false); setTradeUrlDraft('');
      // ProfilePersonalTab receives `profile` as a prop so we can't mutate
      // it in place; the parent refetches on the next tab switch. Good
      // enough — the success path is visually terminal (exit edit mode).
    } finally { setTradeUrlBusy(false); }
  };

  const saveEmail = async () => {
    setEmailResult(null);
    if (!emailDraft.trim()) return;
    const res = await setEmail(emailDraft.trim());
    if (res.code || res.error) { setEmailResult({ err: res.message || res.error }); return; }
    setEmailResult({ ok: true, token: res.token });
    setEditingEmail(false);
  };
  const confirmEmail = async () => {
    if (!emailToken.trim()) return;
    const res = await verifyEmail(emailToken.trim());
    if (res.code || res.error) { setEmailResult({ err: res.message || res.error }); return; }
    setEmailResult({ ok: true, verified: true });
    setEmailToken('');
  };

  const startEnroll = async () => {
    setTwofaErr('');
    setTwofaBusy(true);
    try {
      const res = await enroll2fa();
      if (res.code || res.error) { setTwofaErr(res.message || res.error); return; }
      setTwofaSecret(res.secret);
      setTwofaUrl(res.otpauthUrl);
      setEnrolling(true);
    } finally { setTwofaBusy(false); }
  };
  const finishEnroll = async () => {
    setTwofaErr('');
    if (!/^\d{6}$/.test(twofaCode)) { setTwofaErr('Enter the 6-digit code'); return; }
    setTwofaBusy(true);
    try {
      const res = await confirm2fa(twofaCode);
      if (res.code || res.error) { setTwofaErr(res.message || res.error); return; }
      setEnrolling(false); setTwofaCode(''); setTwofaSecret(''); setTwofaUrl('');
      alert('✓ Two-factor authentication enabled. Your next withdrawal will ask for a code.');
    } finally { setTwofaBusy(false); }
  };
  const disableTwofa = async () => {
    const code = prompt('Enter a current 6-digit code to confirm disabling 2FA:');
    if (!code) return;
    const res = await disable2fa(code);
    if (res.code || res.error) { alert(res.message || res.error); return; }
    alert('2FA disabled.');
  };

  return h('div', { className: 'profile-panel' },
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Display Name'),
      h('div', { className: 'profile-row-value' }, me.displayName || '—')
    ),
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Steam ID 64'),
      h('div', { className: 'profile-row-value mono', style: { display: 'flex', alignItems: 'center', gap: 8 } },
        h('span', null, me.steamId64),
        h('button', {
          className: 'btn btn-ghost',
          style: { padding: '3px 8px', fontSize: 10, border: '1px solid var(--border)' },
          title: 'Copy Steam ID to clipboard',
          onClick: async (e) => {
            try {
              if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(me.steamId64);
                const btn = e.currentTarget;
                const prev = btn.textContent;
                btn.textContent = '✓';
                btn.style.color = 'var(--green)';
                setTimeout(() => { btn.textContent = prev; btn.style.color = ''; }, 1200);
              } else {
                window.prompt('Copy:', me.steamId64);
              }
            } catch (_) { window.prompt('Copy:', me.steamId64); }
          }
        }, '⎘')
      )
    ),

    // Public stall URL — one-click copy for sharing their own stall on
    // Discord / Steam groups / social. Uses the same canonical /stall/:id
    // the nav stall link points at.
    me?.id && h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'My public stall'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 6 } },
        h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' } },
          h('a', {
            className: 'mono',
            href: `/stall/${me.id}`,
            target: '_blank',
            rel: 'noopener noreferrer',
            style: { fontSize: 11, color: 'var(--accent)', wordBreak: 'break-all' }
          }, `${window.location.origin}/stall/${me.id}`),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
            onClick: async () => {
              const url = `${window.location.origin}/stall/${me.id}`;
              try {
                if (navigator.clipboard?.writeText) {
                  await navigator.clipboard.writeText(url);
                  alert('Stall link copied — paste into Discord, Steam groups, etc.');
                } else {
                  window.prompt('Copy this link:', url);
                }
              } catch (_) { window.prompt('Copy this link:', url); }
            }
          }, '⎘ Copy')
        )
      )
    ),

    // Sellers I follow — inline list with unfollow buttons. Only shown
    // when the user follows at least one. Pulls from /api/follows on
    // mount via the FollowingList helper below.
    me?.id && h(FollowingListRow, null),

    // ── Email ────────────────────────────────────────────────────
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Email'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8 } },
        !editingEmail && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
          h('span', { className: 'mono' }, profile?.user?.email || '(not set)'),
          hasEmail && h('span', {
            style: {
              fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
              background: emailVerified ? 'var(--green-dim)' : 'rgba(251,191,36,0.15)',
              color: emailVerified ? 'var(--green)' : '#fbbf24'
            }
          }, emailVerified ? '✓ Verified' : 'Unverified'),
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' },
            onClick: () => { setEditingEmail(true); setEmailDraft(profile?.user?.email || ''); }
          }, 'Edit')
        ),
        editingEmail && h('div', { style: { display: 'flex', gap: 6, width: '100%' } },
          h('input', {
            className: 'price-input', style: { flex: 1 },
            type: 'email', placeholder: 'you@example.com',
            value: emailDraft, onChange: e => setEmailDraft(e.target.value)
          }),
          h('button', { className: 'buy-btn', onClick: saveEmail }, 'Save'),
          h('button', { className: 'btn btn-ghost', style: { padding: '6px 10px', fontSize: 11 }, onClick: () => setEditingEmail(false) }, '✕')
        ),
        // If /email returned a token (dev-mode), show the verify input
        emailResult?.token && h('div', { style: { padding: 10, background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 6, width: '100%' } },
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6 } },
            'Dev mode: use this token to verify the email.'),
          h('div', { className: 'mono', style: { fontSize: 11, color: 'var(--accent)', marginBottom: 6 } }, emailResult.token),
          h('div', { style: { display: 'flex', gap: 6 } },
            h('input', { className: 'price-input', style: { flex: 1 }, placeholder: 'Paste token', value: emailToken, onChange: e => setEmailToken(e.target.value) }),
            h('button', { className: 'buy-btn', onClick: confirmEmail }, 'Verify')
          )
        ),
        emailResult?.verified && h('div', { style: { fontSize: 11, color: 'var(--green)' } }, '✓ Email verified'),
        emailResult?.err && h('div', { className: 'wallet-error' }, emailResult.err),
        // Resend verification link — only meaningful when email is set but
        // not yet verified. Regenerates the token and re-delivers via
        // EmailService (or logs it in dev mode).
        hasEmail && !emailVerified && !editingEmail && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '5px 12px', fontSize: 11, marginTop: 6 },
          onClick: async () => {
            const res = await resendEmailVerification();
            if (res && (res.error || res.code)) { setEmailResult({ err: res.message || res.error }); return; }
            setEmailResult({ ok: true, resent: true, token: res.token });
          }
        }, '↻ Resend verification email')
      )
    ),

    // ── Steam trade URL ──────────────────────────────────────────
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Steam trade URL'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8 } },
        !editingTradeUrl && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' } },
          hasTradeUrl
            ? h('a', {
                className: 'mono',
                href: profile.user.tradeUrl,
                target: '_blank',
                rel: 'noopener noreferrer',
                style: { fontSize: 11, color: 'var(--accent)', wordBreak: 'break-all' }
              }, profile.user.tradeUrl)
            : h('span', { className: 'mono', style: { color: 'var(--text-muted)' } }, '(not set)'),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
            onClick: () => { setTradeUrlDraft(profile?.user?.tradeUrl || ''); setEditingTradeUrl(true); setTradeUrlErr(''); }
          }, hasTradeUrl ? 'Edit' : 'Add'),
          hasTradeUrl && h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
            onClick: () => saveTradeUrl('')
          }, 'Remove')
        ),
        editingTradeUrl && h('div', { style: { display: 'flex', gap: 8, width: '100%', flexWrap: 'wrap' } },
          h('input', {
            type: 'url',
            className: 'wallet-amount-input',
            placeholder: 'https://steamcommunity.com/tradeoffer/new/?partner=…&token=…',
            value: tradeUrlDraft,
            style: { flex: 1, minWidth: 240, fontFamily: 'JetBrains Mono, monospace', fontSize: 12 },
            onChange: e => setTradeUrlDraft(e.target.value)
          }),
          h('button', { className: 'btn btn-accent', disabled: tradeUrlBusy, onClick: () => saveTradeUrl(tradeUrlDraft.trim()) }, tradeUrlBusy ? 'Saving…' : 'Save'),
          h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: tradeUrlBusy, onClick: () => { setEditingTradeUrl(false); setTradeUrlErr(''); } }, 'Cancel')
        ),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
          'Required for escrowed trades. Get it at ',
          h('a', {
            href: 'https://steamcommunity.com/my/tradeoffers/privacy',
            target: '_blank', rel: 'noopener noreferrer',
            style: { color: 'var(--accent)' }
          }, 'Steam → Trade offers → Who can send me offers'),
          '.'
        ),
        tradeUrlErr && h('div', { className: 'wallet-error' }, tradeUrlErr)
      )
    ),

    // ── 2FA ──────────────────────────────────────────────────────
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Two-Factor Auth'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8 } },
        !enrolling && !has2fa && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
          h('span', { style: { fontSize: 12, color: 'var(--text-muted)' } }, 'Disabled'),
          h('button', { className: 'btn btn-accent', style: { padding: '6px 14px', fontSize: 11 }, disabled: twofaBusy, onClick: startEnroll }, 'Enable 2FA')
        ),
        !enrolling && has2fa && h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
          h('span', { style: { fontSize: 12, color: 'var(--green)', fontWeight: 700 } }, '✓ Enabled'),
          h('button', { className: 'btn btn-ghost', style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 }, onClick: disableTwofa }, 'Disable')
        ),
        enrolling && h('div', { className: 'twofa-enroll' },
          h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 } },
            'Scan the QR below in Google Authenticator, Authy, or 1Password — OR paste the secret manually:'),
          h('div', { style: { display: 'flex', gap: 14, alignItems: 'center' } },
            h('img', {
              alt: '2FA QR',
              style: { width: 160, height: 160, background: 'white', borderRadius: 6, padding: 4 },
              src: 'https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=' + encodeURIComponent(twofaUrl)
            }),
            h('div', null,
              h('div', { style: { fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 } }, 'Secret'),
              h('div', { className: 'mono', style: { fontSize: 12, color: 'var(--accent)', wordBreak: 'break-all', userSelect: 'all' } }, twofaSecret)
            )
          ),
          h('div', { className: 'wallet-input-label', style: { marginTop: 14 } }, '6-digit code from your app'),
          h('input', {
            className: 'wallet-amount-input',
            type: 'text',
            inputMode: 'numeric',
            maxLength: 6,
            placeholder: '000000',
            value: twofaCode,
            onChange: e => setTwofaCode(e.target.value.replace(/\D/g, ''))
          }),
          twofaErr && h('div', { className: 'wallet-error' }, twofaErr),
          h('div', { style: { display: 'flex', gap: 10, marginTop: 10 } },
            h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, onClick: () => setEnrolling(false) }, 'Cancel'),
            h('button', { className: 'btn btn-accent', disabled: twofaBusy || twofaCode.length !== 6, onClick: finishEnroll }, 'Confirm & Enable')
          )
        )
      )
    ),

    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Steam Inventory Size'),
      h('div', { className: 'profile-row-value mono' }, (profile?.user?.steamInventorySize ?? 0) + ' items')
    ),
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Last Steam Sync'),
      h('div', { className: 'profile-row-value' },
        profile?.user?.lastSyncedAt ? timeAgo(profile.user.lastSyncedAt) : 'Never',
        h('button', { className: 'btn btn-ghost', style: { marginLeft: 12, padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)' }, disabled: syncing, onClick: onSync },
          syncing ? 'Syncing…' : 'Sync Now')
      )
    ),
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Account Created'),
      h('div', { className: 'profile-row-value' },
        profile?.user?.createdAt ? new Date(profile.user.createdAt).toLocaleDateString() : '—',
        profile?.user?.createdAt && (() => {
          const ageMs = Date.now() - profile.user.createdAt;
          const days = Math.floor(ageMs / 86400_000);
          let label;
          if (days < 1)         label = 'today';
          else if (days < 30)   label = `${days} day${days === 1 ? '' : 's'}`;
          else if (days < 365)  label = `${Math.floor(days / 30)} month${Math.floor(days / 30) === 1 ? '' : 's'}`;
          else                  label = `${Math.floor(days / 365)} year${Math.floor(days / 365) === 1 ? '' : 's'}`;
          return h('span', {
            style: {
              marginLeft: 8, fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
              background: days >= 90 ? 'rgba(34,197,94,0.15)' : 'rgba(96,165,250,0.15)',
              color:      days >= 90 ? '#22c55e' : '#60a5fa'
            },
            title: days >= 90 ? 'Established account — 3+ months old' : 'Newer account'
          }, days >= 90 ? '✓ ' + label : label);
        })()
      )
    ),

    // GDPR / right-to-copy: download a JSON blob of everything we
    // store about the user. Wallet, transactions, listings, trades,
    // offers, buy orders, auto-bids, reviews given, notifications.
    // Excludes secrets (totpSecret, emailVerificationToken) — those
    // are @JsonIgnore'd server-side.
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Download data'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 6 } },
        h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 11 },
          href: '/api/profile/export',
          title: 'Download every piece of your data we store as a JSON file'
        }, '⇣ Export my data (JSON)'),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
          'Includes wallet, transactions, listings, trades, offers, buy orders, auto-bids, reviews, notifications. No secrets.')
      )
    ),

    // Email-notification preference — toggle non-essential emails
    // (auction outbid, auction won, price drops). Verification +
    // account-state emails still send. Starts with the value from the
    // loaded profile; click optimistically and commits via PUT.
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Email notifications'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 6 } },
        h(EmailPrefToggle, { initial: profile?.user?.emailNotificationsEnabled !== false }),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
          'Auction outbid, auction won, follower activity. Security + account-state emails are always sent regardless of this toggle.')
      )
    ),

    // Account-deletion request — GDPR/DSAR entry point. Soft-flag: the
    // account isn't actually deleted until staff reviews. Shows a
    // pending-state banner + cancel button while the flag is set,
    // otherwise a scary red "Delete account" action.
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Delete account'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 6 } },
        profile?.user?.deletionRequestedAt
          ? h('div', null,
              h('div', {
                style: {
                  padding: '8px 12px', borderRadius: 6,
                  background: 'rgba(251,191,36,0.15)', border: '1px solid rgba(251,191,36,0.4)',
                  color: '#fbbf24', fontSize: 12, marginBottom: 6
                }
              },
                '⏳ Deletion requested ',
                timeAgo(profile.user.deletionRequestedAt),
                ' — staff will finalise within 1-2 business days.'
              ),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 11 },
                onClick: async () => {
                  if (!confirm('Cancel the pending deletion request?')) return;
                  const { cancelAccountDeletion } = await import('./api.js');
                  const res = await cancelAccountDeletion();
                  if (res && (res.error || res.code)) {
                    alert(res.message || res.error);
                    return;
                  }
                  location.reload();
                }
              }, 'Cancel deletion request')
            )
          : h('div', null,
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.4)', color: 'var(--red)', padding: '6px 14px', fontSize: 11 },
                onClick: async () => {
                  if (!confirm('Request account deletion?\n\nYour account will be reviewed by staff and permanently deleted within 1-2 business days. You can cancel the request any time before finalisation. Pending withdrawals and open trades must be resolved first — staff will contact you if anything is outstanding.')) return;
                  const { requestAccountDeletion } = await import('./api.js');
                  const res = await requestAccountDeletion();
                  if (res && (res.error || res.code)) {
                    alert(res.message || res.error);
                    return;
                  }
                  location.reload();
                },
                title: 'GDPR / data subject deletion request'
              }, '🗑 Delete account'),
              h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 4 } },
                'Soft request. Nothing is deleted until staff reviews and finalises.')
            )
      )
    ),

    // Recent purchases snapshot — the last 5 PURCHASE transactions so
    // the user sees their activity at a glance without switching tabs.
    // Derived from the transactions list the modal already has; if the
    // wallet hasn't been loaded yet (anon demo) we render nothing.
    (() => {
      if (!transactions || transactions.length === 0) return null;
      const purchases = transactions.filter(t => t.type === 'PURCHASE').slice(0, 5);
      if (purchases.length === 0) return null;
      return h('div', { style: { marginTop: 22, paddingTop: 20, borderTop: '1px solid var(--border)' } },
        h('div', { style: { fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', marginBottom: 10 } },
          `Recent purchases (last ${purchases.length})`),
        purchases.map(tx => h('div', { key: tx.id, className: 'wallet-tx', style: { marginBottom: 6 } },
          h('div', { className: 'wallet-tx-icon out' }, '↑'),
          h('div', { className: 'wallet-tx-main' },
            h('div', { className: 'wallet-tx-type' }, 'Purchase'),
            h('div', { className: 'wallet-tx-desc' }, tx.description || 'Listing #' + (tx.listingId || tx.stripeReference || '—'))
          ),
          h('div', { className: 'wallet-tx-right' },
            h('div', { className: 'wallet-tx-amt out' }, '−' + fmt(tx.amount)),
            h('div', { style: { fontSize: 10, color: 'var(--text-muted)' } }, timeAgo(tx.createdAt))
          )
        ))
      );
    })()
  );
}

function ProfileTransactionsTab({ transactions, privacy }) {
  const [txFilter, setTxFilter] = useState('ALL');
  if (!transactions || transactions.length === 0) {
    return h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '📋'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No transactions yet.'));
  }
  // 30-day window summary — lightweight ledger header so a power user
  // can see "I spent $X / earned $Y in the last month" without opening
  // the CSV. COMPLETED-only so pending withdrawals + failed refunds
  // don't skew the figures.
  const now = Date.now();
  const recent = transactions.filter(t => t.status === 'COMPLETED' && (now - (t.createdAt || 0)) < 30 * 86400_000);
  const CREDIT_TYPES = new Set(['DEPOSIT','SALE','REFUND','ADMIN_CREDIT','CSR_CREDIT','BUY_ORDER_REFUND']);
  const DEBIT_TYPES  = new Set(['PURCHASE','WITHDRAW','ADMIN_DEBIT','AUCTION_HOLD']);
  let credits = 0, debits = 0;
  recent.forEach(t => {
    const amt = Math.abs(parseFloat(t.amount) || 0);
    if (CREDIT_TYPES.has(t.type)) credits += amt;
    else if (DEBIT_TYPES.has(t.type)) debits += amt;
  });
  const net = credits - debits;
  // Type filter. ALL = no filtering, IN/OUT = every credit / debit type,
  // then the four common lines get their own chip for one-click slicing
  // (a dispute-investigation or a tax-year review is usually one of
  // those). Chip counts reflect the full history, not just the 30d
  // window above — the summary cards already surface the 30d view.
  const FILTER_TYPES = {
    ALL:      null,
    IN:       CREDIT_TYPES,
    OUT:      DEBIT_TYPES,
    PURCHASE: new Set(['PURCHASE']),
    SALE:     new Set(['SALE']),
    DEPOSIT:  new Set(['DEPOSIT']),
    WITHDRAW: new Set(['WITHDRAW'])
  };
  const countFor = (key) => {
    const set = FILTER_TYPES[key];
    return set == null ? transactions.length : transactions.filter(t => set.has(t.type)).length;
  };
  const visibleTx = (() => {
    const set = FILTER_TYPES[txFilter];
    return set == null ? transactions : transactions.filter(t => set.has(t.type));
  })();
  return h('div', null,
    recent.length > 0 && h('div', {
      style: {
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
        gap: 10, marginBottom: 16
      }
    },
      h('div', { className: 'admin-stat' },
        h('div', { className: 'admin-stat-label' }, '30d credits'),
        h('div', { className: 'admin-stat-val green' }, privacy ? '$•••••' : fmt(credits))
      ),
      h('div', { className: 'admin-stat' },
        h('div', { className: 'admin-stat-label' }, '30d debits'),
        h('div', { className: 'admin-stat-val red' }, privacy ? '$•••••' : fmt(debits))
      ),
      h('div', { className: 'admin-stat' },
        h('div', { className: 'admin-stat-label' }, '30d net'),
        h('div', { className: `admin-stat-val ${net >= 0 ? 'green' : 'red'}` }, privacy ? '$•••••' : (net >= 0 ? '+' : '') + fmt(net))
      ),
      h('div', { className: 'admin-stat' },
        h('div', { className: 'admin-stat-label' }, '30d tx'),
        h('div', { className: 'admin-stat-val' }, recent.length)
      )
    ),
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, flexWrap: 'wrap' } },
      h('div', { className: 'wallet-tx-filter-row', style: { flex: 1, minWidth: 0 } },
        [
          { id: 'ALL',      label: 'All' },
          { id: 'IN',       label: 'Credits' },
          { id: 'OUT',      label: 'Debits' },
          { id: 'PURCHASE', label: 'Purchases' },
          { id: 'SALE',     label: 'Sales' },
          { id: 'DEPOSIT',  label: 'Deposits' },
          { id: 'WITHDRAW', label: 'Withdrawals' }
        ].map(opt => {
          const n = countFor(opt.id);
          return h('button', {
            key: opt.id,
            className: `wallet-tx-filter-chip ${txFilter === opt.id ? 'active' : ''}`,
            disabled: n === 0 && opt.id !== 'ALL',
            onClick: () => setTxFilter(opt.id),
            title: `${n} row${n === 1 ? '' : 's'} match this filter`
          }, opt.label, ' ', h('span', { style: { opacity: 0.6, marginLeft: 4 } }, '· ', n));
        })
      ),
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: '/api/wallet/transactions.csv',
        title: 'Download every transaction as a CSV'
      }, '⇣ CSV')
    ),
  visibleTx.length === 0
    ? h('div', { className: 'empty-inline', style: { marginTop: 8 } },
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)' } }, 'No transactions match this filter.'))
    : h('table', { className: 'db-table' },
      h('thead', null, h('tr', null,
        h('th', null, 'ID'),
        h('th', null, 'Type'),
        h('th', null, 'Description'),
        h('th', { className: 'right' }, 'Amount'),
        h('th', { className: 'right' }, 'Status')
      )),
      h('tbody', null,
        visibleTx.map(tx => h('tr', { key: tx.id, className: 'db-row' },
          h('td', { className: 'db-rank' }, '#' + tx.id),
          h('td', { style: { fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)' } }, tx.type),
          h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, tx.description || tx.stripeReference),
          h('td', { className: 'right db-mono' }, privacy ? '$•••••' : fmt(tx.amount)),
          h('td', { className: 'right', style: { fontSize: 10, fontWeight: 700 } }, tx.status)
        ))
      )
    )
  );
}

function ProfileBuyOrdersTab() {
  const [orders, setOrders] = useState(null);
  const [filter, setFilter] = useState('ACTIVE');
  const [busy, setBusy] = useState(false);
  // Edit state: which row is being edited, the in-flight draft values.
  // Null = no edit open. Only one row editable at a time — keeps the
  // UI simple and mirrors the MyStall inline-edit pattern.
  const [editing, setEditing] = useState(null);
  const [editMax, setEditMax] = useState('');
  const [editQty, setEditQty] = useState('');
  const startEdit = (o) => {
    setEditing(o.id);
    setEditMax(o.maxPrice != null ? String(o.maxPrice) : '');
    setEditQty(o.quantity != null ? String(o.quantity) : '');
  };
  const cancelEdit = () => { setEditing(null); setEditMax(''); setEditQty(''); };
  const saveEdit = async (o) => {
    const maxPrice = parseFloat(editMax);
    const quantity = parseInt(editQty, 10);
    if (!(maxPrice > 0)) { alert('Max price must be positive'); return; }
    if (!(quantity >= 1)) { alert('Quantity must be at least 1'); return; }
    setBusy(true);
    try {
      const { updateBuyOrder } = await import('./api.js');
      const res = await updateBuyOrder(o.id, { maxPrice, quantity });
      if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
      cancelEdit();
      load();
    } finally { setBusy(false); }
  };
  const load = useCallback(() => { fetchBuyOrders().then(setOrders); }, []);
  useEffect(() => { load(); }, [load]);
  const cancelOrder = async (o) => {
    if (!confirm(`Cancel buy order for "${o.itemName || 'item'}"? Any remaining quantity is freed.`)) return;
    setBusy(true);
    try {
      const res = await deleteBuyOrder(o.id);
      if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
      load();
    } finally { setBusy(false); }
  };
  if (orders === null) return h('div', { className: 'spinner' });
  if (orders.length === 0) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '🛒'),
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No buy orders yet. Create one from the user menu.'));
  const counts = {
    ALL:       orders.length,
    ACTIVE:    orders.filter(o => o.status === 'ACTIVE').length,
    FILLED:    orders.filter(o => o.status === 'FILLED').length,
    CANCELLED: orders.filter(o => o.status === 'CANCELLED').length
  };
  const filtered = filter === 'ALL' ? orders : orders.filter(o => o.status === filter);
  return h('div', null,
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, flexWrap: 'wrap' } },
      h('div', { className: 'wallet-tx-filter-row', style: { flex: 1, minWidth: 0 } },
        [
          { id: 'ALL',       label: 'All' },
          { id: 'ACTIVE',    label: 'Active' },
          { id: 'FILLED',    label: 'Filled' },
          { id: 'CANCELLED', label: 'Cancelled' }
        ].map(opt => h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${filter === opt.id ? 'active' : ''}`,
          onClick: () => setFilter(opt.id),
          disabled: counts[opt.id] === 0 && opt.id !== 'ALL'
        }, `${opt.label} · ${counts[opt.id] || 0}`))
      ),
      orders.length > 0 && h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: '/api/buy-orders/export.csv',
        title: 'Download every buy order (ACTIVE + FILLED + CANCELLED) as CSV'
      }, '⇣ CSV')
    ),
    filtered.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } }, `No ${filter.toLowerCase()} buy orders.`))
      : h('div', { className: 'buyorder-list' },
          filtered.map(o => h('div', { key: o.id, className: `buyorder-row ${(o.status || '').toLowerCase()}` },
            h('div', { style: { flex: 1, minWidth: 0 } },
              h('div', { className: 'buyorder-title' }, o.itemName || ((o.category || 'Any') + ' · ' + (o.rarity || 'any rarity'))),
              h('div', { className: 'buyorder-sub' }, 'Qty ', h('strong', null, o.quantity), ' / ', o.originalQuantity, ' · ', timeAgo(o.createdAt))
            ),
            h('div', { style: { textAlign: 'right' } },
              h('div', { className: 'buyorder-cap' }, '≤ ' + fmt(o.maxPrice)),
              // Floor gap — how close is this order to matching?
              // Negative (order ≥ floor) is green ("should match"),
              // small positive is amber, large positive is muted.
              // Null (no itemId, e.g. category-only orders) hides.
              o.currentFloor != null && o.floorGap != null && (() => {
                const gap = parseFloat(o.floorGap);
                const floor = parseFloat(o.currentFloor);
                const pct = floor > 0 ? Math.round((gap / floor) * 100) : 0;
                let color = 'var(--text-muted)';
                let label;
                if (gap <= 0) {
                  color = 'var(--green)';
                  label = '= floor';
                } else if (pct <= 10) {
                  color = '#fbbf24';
                  label = pct + '% below';
                } else {
                  label = pct + '% below';
                }
                return h('div', {
                  style: { fontSize: 10, color, marginTop: 2 },
                  title: `Current floor ${fmt(floor)} · gap ${fmt(gap)}`
                }, 'Floor ' + fmt(floor) + ' · ' + label);
              })(),
              // Queue-position chip — "#1 in queue" is green (next to
              // fill), "#2" amber, deeper muted. Silent for basket
              // (no-item) orders and non-ACTIVE rows per the controller
              // contract. The engine ranks by maxPrice DESC + createdAt
              // ASC so this is the real position, not a heuristic.
              o.queuePosition != null && (() => {
                const q = o.queuePosition;
                const color = q <= 1 ? 'var(--green)'
                            : q <= 3 ? '#fbbf24'
                            :          'var(--text-muted)';
                return h('div', {
                  style: { fontSize: 10, color, marginTop: 2, fontWeight: 700 },
                  title: q === 1
                    ? 'You are first in line — the next matching listing fills your order'
                    : `${q - 1} other buyer${q - 1 === 1 ? ' is' : 's are'} ahead of you for this item. Raise your max price or wait for them to fill.`
                }, '#' + q + ' in queue');
              })(),
              h('div', { className: `buyorder-status ${o.status}` }, o.status),
              // Edit panel — visible when the user clicked ✎. Two
              // compact inputs for maxPrice + quantity, save/cancel
              // buttons. Live-reloads the row on save so the queue
              // chip re-ranks with the new price.
              o.status === 'ACTIVE' && editing === o.id && h('div', {
                style: { display: 'flex', gap: 4, marginTop: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }
              },
                h('input', {
                  className: 'price-input',
                  style: { width: 80, padding: '4px 6px', fontSize: 11 },
                  type: 'number', step: '0.01', min: '0.01',
                  value: editMax, onChange: e => setEditMax(e.target.value),
                  placeholder: 'Max $',
                  title: 'New max price per item'
                }),
                h('input', {
                  className: 'price-input',
                  style: { width: 52, padding: '4px 6px', fontSize: 11 },
                  type: 'number', step: '1', min: '1',
                  value: editQty, onChange: e => setEditQty(e.target.value),
                  placeholder: 'Qty',
                  title: "Remaining quantity (can't exceed original)"
                }),
                h('button', { className: 'buy-btn', style: { padding: '4px 10px', fontSize: 11 }, disabled: busy, onClick: () => saveEdit(o) }, 'Save'),
                h('button', { className: 'btn btn-ghost', style: { padding: '4px 8px', fontSize: 11 }, onClick: cancelEdit }, '✕')
              ),
              // Edit + Cancel buttons — only shown while the order still
              // has remaining fillable quantity AND no edit is open for
              // another row. Fills / cancels terminate the row so the
              // buttons hide.
              o.status === 'ACTIVE' && editing !== o.id && h('div', { style: { display: 'flex', gap: 4, marginTop: 6, justifyContent: 'flex-end' } },
                h('button', {
                  className: 'btn btn-ghost',
                  style: { padding: '4px 10px', fontSize: 10, border: '1px solid var(--border)' },
                  disabled: busy, onClick: () => startEdit(o),
                  title: 'Adjust your max price or remaining quantity'
                }, '✎ Edit'),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { padding: '4px 10px', fontSize: 10, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
                  disabled: busy,
                  onClick: () => cancelOrder(o)
                }, '✕ Cancel')
              )
            )
          ))
        )
  );
}

function ProfileAutoBidsTab() {
  const [bids, setBids] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => { fetchAutoBids().then(setBids); }, []);
  useEffect(() => { load(); }, [load]);
  const cancelOne = async (b) => {
    if (!confirm(`Stop auto-raising on listing #${b.listingId}?\nYour current bid (${fmt(b.amount)}) stays live.`)) return;
    setBusy(true);
    try {
      const res = await cancelAutoBid(b.id);
      if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
      load();
    } finally { setBusy(false); }
  };
  const cancelAll = async () => {
    if (!bids || bids.length === 0) return;
    if (!confirm(`Stop auto-raising on all ${bids.length} active bids? Your current bid amounts stay live.`)) return;
    setBusy(true);
    try {
      const res = await cancelAllAutoBids();
      if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
      load();
    } finally { setBusy(false); }
  };
  if (bids === null) return h('div', { className: 'spinner' });
  if (bids.length === 0) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '⚡'),
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No active auto-bids. Place one from any auction listing.'));
  return h('div', null,
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 } },
      h('div', { style: { fontSize: 12, color: 'var(--text-muted)' } },
        `${bids.length} active auto-raise${bids.length === 1 ? '' : 's'} — the bot pushes your bid up to the listed cap whenever you're outbid.`),
      h('div', { style: { flex: 1 } }),
      h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 12px', fontSize: 11 },
        disabled: busy,
        onClick: cancelAll
      }, 'Stop all auto-raises')
    ),
    h('table', { className: 'db-table' },
      h('thead', null, h('tr', null,
        h('th', null, 'ID'), h('th', null, 'Listing'),
        h('th', { className: 'right' }, 'Current'),
        h('th', { className: 'right' }, 'Max'),
        h('th', { className: 'right' }, 'Placed'),
        h('th', { className: 'right' }, '')
      )),
      h('tbody', null, bids.map(b => h('tr', { key: b.id, className: 'db-row' },
        h('td', { className: 'db-rank' }, '#' + b.id),
        h('td', null, 'Listing #' + b.listingId),
        h('td', { className: 'right db-mono accent' }, fmt(b.amount)),
        h('td', { className: 'right db-mono' }, fmt(b.maxAmount || b.amount)),
        h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(b.createdAt)),
        h('td', { className: 'right' },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
            disabled: busy,
            onClick: () => cancelOne(b)
          }, 'Stop')
        )
      )))
    )
  );
}

function ProfileTradesTab({ me, privacy }) {
  // Real escrow-state trades via /api/trades. Replaces the old "list every
  // PURCHASE/SALE transaction" behaviour — those are in Transactions tab now.
  // Each row shows the full state machine and only renders the button that
  // applies to the viewing user (seller-accept, seller-sent, buyer-confirm,
  // dispute, cancel).
  const [trades, setTrades] = useState(null);
  const [busy, setBusy]     = useState(false);
  const [filter, setFilter] = useState('ALL');
  // Role filter alongside the state filter — buyer-side and seller-
  // side trades have very different action requirements (confirm
  // vs. accept+send), and a heavy seller with dozens of pending
  // trades often only cares about their side. Persists to
  // localStorage so a seller doesn't have to re-flip it every visit.
  const [roleFilter, setRoleFilter] = useState(() => {
    try { return localStorage.getItem('sb_trade_role') || 'all'; }
    catch { return 'all'; }
  });
  const setRole = (v) => {
    setRoleFilter(v);
    try { localStorage.setItem('sb_trade_role', v); } catch (_) {}
  };
  // Per-trade chat panel state: which trade's chat is open, the loaded
  // messages keyed by trade id, the draft input per trade, and the
  // send-in-flight flag. Closed by default — a user with dozens of
  // trades doesn't want every thread loading on tab open.
  const [openChat, setOpenChat] = useState(null);
  const [chatThreads, setChatThreads] = useState({});
  const [chatDraft, setChatDraft] = useState('');
  const [chatSending, setChatSending] = useState(false);
  const loadChat = async (tradeId) => {
    const rows = await fetchTradeMessages(tradeId);
    setChatThreads(prev => ({ ...prev, [tradeId]: rows }));
  };
  const toggleChat = async (tradeId) => {
    if (openChat === tradeId) {
      setOpenChat(null);
      return;
    }
    setOpenChat(tradeId);
    setChatDraft('');
    await loadChat(tradeId);
  };
  const sendChat = async (tradeId) => {
    const body = chatDraft.trim();
    if (!body) return;
    setChatSending(true);
    try {
      const res = await postTradeMessage(tradeId, body);
      if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
      setChatDraft('');
      await loadChat(tradeId);
    } finally { setChatSending(false); }
  };
  // Review modal state — which trade we're reviewing, current star pick,
  // comment text, and whether the submit is in flight. Null = closed.
  const [reviewTrade, setReviewTrade] = useState(null);
  const [reviewStars, setReviewStars] = useState(5);
  const [reviewText,  setReviewText]  = useState('');
  const [reviewBusy,  setReviewBusy]  = useState(false);
  const [reviewErr,   setReviewErr]   = useState('');
  const [reviewDone,  setReviewDone]  = useState(false);

  // Refund-request helper — fires a support ticket with the trade details
  // baked into the subject + body so the CSR queue has full context on the
  // first look. Optimistic: we don't block the UI on the reply, just flash
  // a toast and the user can track it in /support.
  const [refundBusy, setRefundBusy] = useState(false);
  const openRefundForTrade = async (trade) => {
    const reason = window.prompt(
      `Refund request for "${trade.itemName || 'Trade #' + trade.id}"\n\n` +
      `What's wrong? (item not received, item doesn't match, seller unresponsive, etc.)`);
    if (!reason || !reason.trim()) return;
    setRefundBusy(true);
    try {
      const dt = new Date(trade.settledAt || trade.updatedAt || trade.createdAt);
      const res = await createSupportTicket({
        category: 'REFUND',
        subject:  `Refund request · Trade #${trade.id} · ${trade.itemName || 'item'}`,
        body:     `Trade ID: ${trade.id}\n` +
                  `Item: ${trade.itemName || '—'}\n` +
                  `Price: $${(trade.price ?? 0).toString()}\n` +
                  `Settled: ${isNaN(dt.getTime()) ? '—' : dt.toISOString()}\n` +
                  `Counterparty: ${trade.counterpartyName || ('user #' + (trade.sellerUserId || trade.buyerUserId))}\n\n` +
                  `Reason from buyer:\n${reason.trim()}`
      });
      if (res && (res.error || res.code)) {
        alert(res.message || res.error || 'Could not open refund ticket.');
      } else {
        alert('Refund request submitted. A support agent will reply — track progress in Support.');
      }
    } finally { setRefundBusy(false); }
  };

  const openReview = (trade) => {
    setReviewTrade(trade);
    setReviewStars(5);
    setReviewText('');
    setReviewErr('');
    setReviewDone(false);
  };
  const closeReview = () => {
    setReviewTrade(null);
    setReviewErr('');
    setReviewDone(false);
  };
  const submitReview = async () => {
    if (!reviewTrade) return;
    setReviewBusy(true); setReviewErr('');
    try {
      const res = await leaveReview(reviewTrade.id, reviewStars, reviewText);
      if (res.code || res.error) {
        setReviewErr(res.message || res.error || 'Review failed');
        return;
      }
      setReviewDone(true);
      setTimeout(closeReview, 900);
    } finally { setReviewBusy(false); }
  };

  const load = useCallback(async () => { setTrades(await fetchTrades()); }, []);
  useEffect(() => { load(); }, [load]);

  if (!me) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '🔒'),
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your trades.'));
  if (trades === null) return h('div', { className: 'spinner' });

  const stateFiltered = filter === 'ALL'
    ? trades
    : filter === 'OPEN'
      ? trades.filter(t => !['VERIFIED','CANCELLED'].includes(t.state))
      : trades.filter(t => t.state === filter);
  // Layer the role filter over the state filter. "all" = every trade,
  // "buying" = trades where the current user is the buyer, "selling" =
  // seller. Unauthenticated callers shouldn't land here but we still
  // treat me?.id-missing as "show everything" so we don't lose the row.
  const filtered = (() => {
    if (!me?.id || roleFilter === 'all') return stateFiltered;
    if (roleFilter === 'buying')  return stateFiltered.filter(t => t.buyerUserId  === me.id);
    if (roleFilter === 'selling') return stateFiltered.filter(t => t.sellerUserId === me.id);
    return stateFiltered;
  })();

  const tradeOp = async (fn, ...args) => {
    setBusy(true);
    try {
      const res = await fn(...args);
      if (res && res.error) { alert(res.error); return; }
      await load();
    } finally { setBusy(false); }
  };
  const onAccept  = (id) => tradeOp(tradeAccept, id);
  const onSent    = (id) => tradeOp(tradeMarkSent, id);
  // Buyer confirm is financially irreversible — it releases escrow to the
  // seller. Gate it behind a summary modal so buyers review (item, price,
  // fee, seller) before committing. Without this, a mis-click on "Confirm"
  // on the wrong trade row could release funds early.
  const [confirmTrade, setConfirmTrade] = useState(null);
  const onConfirm = (trade) => setConfirmTrade(trade);
  const runConfirm = async () => {
    if (!confirmTrade) return;
    const id = confirmTrade.id;
    setConfirmTrade(null);
    await tradeOp(tradeConfirm, id);
  };
  const onDispute = async (id) => {
    const reason = prompt('Why are you disputing this trade?');
    if (!reason) return;
    tradeOp(tradeDispute, id, reason);
  };
  const onCancel  = async (id) => {
    if (!confirm('Cancel this trade? The buyer will be refunded.')) return;
    tradeOp(tradeCancel, id, 'User cancelled');
  };

  const STATE_LABEL = {
    PENDING_SELLER_ACCEPT:  { label: 'Awaiting seller accept',  color: '#fbbf24', step: 1 },
    PENDING_SELLER_SEND:    { label: 'Awaiting Steam offer',    color: '#fbbf24', step: 2 },
    PENDING_BUYER_CONFIRM:  { label: 'Awaiting buyer confirm',  color: '#60a5fa', step: 3 },
    VERIFIED:               { label: 'Verified · funds released', color: '#4ade80', step: 4 },
    DISPUTED:               { label: 'Disputed',                color: '#f87171', step: 0 },
    CANCELLED:              { label: 'Cancelled',               color: '#8590b3', step: 0 },
  };

  // Summary chips above the state filter. Counts derived from the
  // already-loaded trades list so adding them is essentially free.
  // Surfaces "you've sold 12, had 2 disputes, 1 cancel" at a glance.
  const openStates = ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM'];
  const summaryCounts = {
    total:     trades.length,
    open:      trades.filter(t => openStates.includes(t.state)).length,
    verified:  trades.filter(t => t.state === 'VERIFIED').length,
    disputed:  trades.filter(t => t.state === 'DISPUTED').length,
    cancelled: trades.filter(t => t.state === 'CANCELLED').length
  };

  return h('div', null,
    trades.length > 0 && h('div', { className: 'trade-summary-chips' },
      h('div', { className: 'trade-summary-chip' },
        h('span', { className: 'trade-summary-num' }, summaryCounts.total),
        h('span', { className: 'trade-summary-label' }, 'total')
      ),
      h('div', { className: 'trade-summary-chip accent' },
        h('span', { className: 'trade-summary-num' }, summaryCounts.open),
        h('span', { className: 'trade-summary-label' }, 'open')
      ),
      h('div', { className: 'trade-summary-chip green' },
        h('span', { className: 'trade-summary-num' }, summaryCounts.verified),
        h('span', { className: 'trade-summary-label' }, 'verified')
      ),
      summaryCounts.disputed > 0 && h('div', { className: 'trade-summary-chip red' },
        h('span', { className: 'trade-summary-num' }, summaryCounts.disputed),
        h('span', { className: 'trade-summary-label' }, 'disputed')
      ),
      summaryCounts.cancelled > 0 && h('div', { className: 'trade-summary-chip muted' },
        h('span', { className: 'trade-summary-num' }, summaryCounts.cancelled),
        h('span', { className: 'trade-summary-label' }, 'cancelled')
      )
    ),
    h('div', { className: 'trade-filter-bar', style: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 4 } },
      ['ALL','OPEN','PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM','VERIFIED','DISPUTED','CANCELLED'].map(f =>
        h('button', {
          key: f,
          className: `offer-tab ${filter === f ? 'active' : ''}`,
          onClick: () => setFilter(f)
        }, f.replace(/_/g, ' ').toLowerCase())
      ),
      h('div', { style: { flex: 1 } }),
      // Role toggle — muted divider then three pill buttons. Persisted
      // so a heavy seller doesn't have to flip "Selling" every visit.
      h('span', { style: { fontSize: 11, color: 'var(--text-muted)', marginRight: 6 } }, 'Role:'),
      ['all','buying','selling'].map(r => h('button', {
        key: r,
        className: `wallet-tx-filter-chip ${roleFilter === r ? 'active' : ''}`,
        onClick: () => setRole(r),
        title: r === 'all' ? 'Show every trade'
             : r === 'buying' ? 'Only trades you bought'
             : 'Only trades you sold'
      }, r === 'all' ? 'All' : r[0].toUpperCase() + r.slice(1))),
      // CSV export — opens /api/profile/trades.csv in a new tab. The
      // browser handles the download via the Content-Disposition header
      // the endpoint sets. Only surfaced once the user has at least one
      // trade to avoid a dead-end download on fresh accounts.
      trades.length > 0 && h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: '/api/profile/trades.csv',
        title: 'Download every trade you participated in as a CSV (tax / accounting)'
      }, '⇣ CSV')
    ),
    filtered.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '⇄'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No trades in this filter.'))
      : h('div', { className: 'trade-list' },
          filtered.map(t => {
            const isSeller = me && t.sellerUserId === me.id;
            const isBuyer  = me && t.buyerUserId === me.id;
            const meta = STATE_LABEL[t.state] || { label: t.state, color: '#8590b3', step: 0 };
            return h('div', { key: t.id, className: `trade-row ${(t.state || '').toLowerCase()}` },
              h('div', { className: 'trade-main' },
                h('div', { className: 'trade-title' },
                  (isSeller ? '→ ' : '← ') + (t.itemName || ('Trade #' + t.id)),
                  h('span', { className: 'trade-role' }, isSeller ? 'You are selling' : 'You are buying')
                ),
                h('div', { className: 'trade-state', style: { color: meta.color } }, meta.label),
                // Labeled phase stepper — four checkpoints with descriptive
                // text so users know what each dot represents. DISPUTED /
                // CANCELLED states drop the bar to a single red/grey chip.
                (t.state === 'DISPUTED' || t.state === 'CANCELLED')
                  ? h('div', { className: `trade-progress terminal ${(t.state || '').toLowerCase()}` },
                      h('span', null, t.state === 'DISPUTED' ? 'Trade disputed — awaiting staff review' : 'Trade cancelled')
                    )
                  : h('div', { className: 'trade-progress-stepper' },
                      [
                        { step: 1, short: 'Open',    long: 'Open' },
                        { step: 2, short: 'Accept',  long: 'Seller accepts' },
                        { step: 3, short: 'Send',    long: 'Steam offer sent' },
                        { step: 4, short: 'Confirm', long: 'Buyer confirms' }
                      ].map((p, idx, arr) => h('div', {
                        key: p.step,
                        className: `trade-phase ${p.step <= meta.step ? 'on' : ''} ${p.step === meta.step ? 'current' : ''}`
                      },
                        h('div', { className: 'trade-phase-dot' }, p.step <= meta.step ? '✓' : p.step),
                        h('div', { className: 'trade-phase-label' }, p.short),
                        idx < arr.length - 1 && h('div', { className: 'trade-phase-bar' })
                      ))
                    ),
                t.note && h('div', { className: 'trade-note' }, '"' + t.note + '"'),
                // Counterparty Steam trade URL — only shown during the
                // active escrow window (not after VERIFIED/CANCELLED). For
                // a seller this is the buyer's trade URL (so the seller
                // can send the offer); for a buyer this is the seller's
                // (so the buyer can verify the incoming offer came from
                // the right Steam account).
                t.counterpartyTradeUrl && !['VERIFIED','CANCELLED'].includes(t.state) &&
                  h('div', { className: 'trade-counterparty-url' },
                    h('span', { className: 'trade-counterparty-label' },
                      isSeller ? 'Send Steam offer to buyer' : 'Seller Steam URL'),
                    h('a', {
                      className: 'trade-counterparty-link',
                      href: t.counterpartyTradeUrl,
                      target: '_blank',
                      rel: 'noopener noreferrer'
                    }, t.counterpartyName ? `@${t.counterpartyName}` : 'Open Steam trade offer'),
                    h('button', {
                      className: 'trade-counterparty-copy',
                      type: 'button',
                      title: 'Copy URL to clipboard',
                      onClick: async () => {
                        try { await navigator.clipboard.writeText(t.counterpartyTradeUrl); }
                        catch (_) { window.prompt('Copy this trade URL:', t.counterpartyTradeUrl); }
                      }
                    }, '⎘')
                  ),
                // Nudge the viewer to set their own URL if the counterparty
                // can't contact them (common first-time seller friction).
                !t.counterpartyTradeUrl && ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM'].includes(t.state) &&
                  h('div', { className: 'trade-counterparty-missing' },
                    (isSeller ? 'The buyer' : 'The seller') + ' has no Steam trade URL on file yet.')
              ),
              h('div', { className: 'trade-side' },
                h('div', { className: 'trade-price' }, privacy ? '$•••••' : fmt(t.price)),
                h('div', { className: 'trade-date' }, timeAgo(t.createdAt)),
                // Copy trade ID — useful when opening a support ticket
                // about a specific trade. The support form references
                // #TRADE_ID; copying straight from the row beats retyping.
                h('button', {
                  className: 'trade-copy-id',
                  title: 'Copy trade #' + t.id + ' for support references',
                  'aria-label': 'Copy trade ID',
                  onClick: async (e) => {
                    e.stopPropagation();
                    const s = '#' + t.id;
                    try {
                      if (navigator.clipboard?.writeText) {
                        await navigator.clipboard.writeText(s);
                      } else {
                        window.prompt('Copy trade id:', s);
                      }
                    } catch (_) { window.prompt('Copy trade id:', s); }
                  }
                }, '#' + t.id + ' ⎘')
              ),
              h('div', { className: 'trade-actions' },
                isSeller && t.state === 'PENDING_SELLER_ACCEPT' &&
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => onAccept(t.id) }, 'Accept'),
                isSeller && t.state === 'PENDING_SELLER_SEND' &&
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => onSent(t.id) }, 'Mark Sent'),
                isBuyer && t.state === 'PENDING_BUYER_CONFIRM' &&
                  h('button', { className: 'buy-btn', disabled: busy, onClick: () => onConfirm(t) }, 'Confirm'),
                !['VERIFIED','CANCELLED','DISPUTED'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11 },
                    disabled: busy, onClick: () => onDispute(t.id)
                  }, 'Dispute'),
                (isSeller || isBuyer) && ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 10px', fontSize: 11 },
                    disabled: busy, onClick: () => onCancel(t.id)
                  }, 'Cancel'),
                // Leave review — only the buyer of a VERIFIED trade. Opens
                // the proper review modal below instead of native prompts.
                // Backend is idempotent so re-reviewing updates the same row.
                isBuyer && t.state === 'VERIFIED' &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11 },
                    disabled: busy,
                    onClick: () => openReview(t)
                  }, '★ Leave Review'),
                // Request refund — opens a support ticket pre-filled with
                // the trade metadata (id, item, price, date). Buyer-only on
                // VERIFIED trades; sellers have a separate dispute channel.
                // Without this buyers had no obvious "something went wrong"
                // path once escrow released — they'd cold-open a ticket and
                // paste trade details by hand.
                isBuyer && t.state === 'VERIFIED' &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(251,191,36,0.3)', color: '#fbbf24', padding: '6px 10px', fontSize: 11 },
                    disabled: busy,
                    onClick: () => openRefundForTrade(t)
                  }, '↩ Request refund'),
                // Report counterparty — opens a FRAUD support ticket
                // scoped to the specific trade. Distinct from Dispute
                // (which is "this trade has an issue, release/cancel
                // through the state machine"). Report is for "this
                // counterparty behaved badly — scam, harassment" and
                // should be escalated to support outside the trade.
                (isSeller || isBuyer) && !['VERIFIED','CANCELLED'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11, opacity: 0.7 },
                    disabled: busy,
                    title: 'Report the other party to support (scam / harassment)',
                    onClick: async () => {
                      const target = isSeller ? t.buyerUserId : t.sellerUserId;
                      if (!target) return;
                      const REASONS = ['Scam attempt','Harassment in chat','Impersonation','Other'];
                      const r = window.prompt(
                        `Report counterparty on Trade #${t.id}\n\nPick a reason by number:\n` +
                        REASONS.map((x, i) => `  ${i + 1}) ${x}`).join('\n'), '1');
                      if (!r) return;
                      const idx = parseInt(r, 10);
                      const pickedReason = (idx >= 1 && idx <= REASONS.length)
                        ? REASONS[idx - 1] : 'Other';
                      const ctx = window.prompt(`Reason: ${pickedReason}\n\nContext (include trade #${t.id}):`,
                        `Trade #${t.id} · item ${t.itemName || '—'}`);
                      if (ctx === null) return;
                      const { reportUser } = await import('./api.js');
                      const res = await reportUser(target, pickedReason, ctx);
                      if (res && (res.error || res.code)) {
                        alert(res.message || res.error || 'Could not file report.');
                      } else {
                        alert('Report filed. Support will review it — track in /support.');
                      }
                    }
                  }, '🚩 Report'),
                // Private chat toggle — only meaningful while the trade
                // is still live. Shows messages count when closed so the
                // user sees there's unread activity without opening.
                !['CANCELLED'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11 },
                    onClick: () => toggleChat(t.id),
                    title: 'Private chat with the other trade participant'
                  },
                    '💬 ',
                    openChat === t.id ? 'Hide chat' : 'Chat',
                    (chatThreads[t.id] || []).length > 0 &&
                      h('span', { style: { marginLeft: 6, fontSize: 10, color: 'var(--text-muted)' } },
                        '· ' + chatThreads[t.id].length)
                  )
              ),
              openChat === t.id && h('div', {
                style: {
                  gridColumn: '1 / -1',
                  marginTop: 8, padding: 10,
                  background: 'var(--bg-elevated)',
                  border: '1px solid var(--border)',
                  borderRadius: 6
                }
              },
                h('div', {
                  style: { maxHeight: 200, overflowY: 'auto', marginBottom: 8, display: 'flex', flexDirection: 'column', gap: 6 }
                },
                  (chatThreads[t.id] || []).length === 0
                    ? h('div', { style: { fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', padding: 8 } },
                        'No messages yet. Send the first one — the other party will see a notification.')
                    : chatThreads[t.id].map(m => {
                        const mine = m.senderUserId === me?.id;
                        return h('div', {
                          key: m.id,
                          style: {
                            alignSelf: mine ? 'flex-end' : 'flex-start',
                            maxWidth: '80%',
                            padding: '6px 10px',
                            borderRadius: 8,
                            background: mine ? 'rgba(96,165,250,0.15)' : 'var(--bg-card)',
                            border: '1px solid ' + (mine ? 'rgba(96,165,250,0.3)' : 'var(--border)'),
                            fontSize: 12
                          }
                        },
                          h('div', { style: { color: 'var(--text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' } }, m.body),
                          h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2 } },
                            (mine ? 'You' : (t.counterpartyName || 'Other')) + ' · ' + timeAgo(m.createdAt))
                        );
                      })
                ),
                h('div', { style: { display: 'flex', gap: 6 } },
                  h('input', {
                    className: 'chat-input',
                    style: { flex: 1 },
                    placeholder: 'Type a message…',
                    value: chatDraft,
                    maxLength: 2000,
                    onChange: e => setChatDraft(e.target.value),
                    onKeyDown: e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(t.id); } }
                  }),
                  h('button', {
                    className: 'btn btn-accent',
                    style: { padding: '6px 14px', fontSize: 12 },
                    disabled: chatSending || !chatDraft.trim(),
                    onClick: () => sendChat(t.id)
                  }, chatSending ? '…' : 'Send')
                )
              )
            );
          })
        ),
    // Review modal — overlays the trades tab when `reviewTrade` is set.
    // Clean star picker + textarea + submit, no native prompts.
    reviewTrade && h('div', { className: 'modal-backdrop', onClick: closeReview },
      h('div', {
        className: 'modal review-modal',
        onClick: e => e.stopPropagation(),
        style: { maxWidth: 480, padding: 0 }
      },
        h('button', { className: 'modal-close', onClick: closeReview, 'aria-label': 'Close' }, '✕'),
        h('div', { style: { padding: '24px 26px 20px' } },
          h('div', { style: { fontSize: 18, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 4 } },
            'Leave a review'),
          h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 18 } },
            'For "', h('strong', { style: { color: 'var(--text-secondary)' } }, reviewTrade.itemName || ('Trade #' + reviewTrade.id)), '"'),

          // Star picker
          h('div', {
            style: { display: 'flex', justifyContent: 'center', gap: 6, marginBottom: 18 },
            role: 'radiogroup',
            'aria-label': 'Star rating'
          },
            [1, 2, 3, 4, 5].map(n => h('button', {
              key: n,
              onClick: () => setReviewStars(n),
              'aria-label': n + ' stars',
              'aria-pressed': reviewStars === n,
              style: {
                background: 'transparent',
                border: 'none',
                padding: 4,
                cursor: 'pointer',
                fontSize: 36,
                lineHeight: 1,
                color: n <= reviewStars ? '#fbbf24' : 'var(--border-light)',
                textShadow: n <= reviewStars ? '0 0 12px rgba(251, 191, 36, 0.45)' : 'none',
                transition: 'color 0.12s, transform 0.12s',
                transform: n <= reviewStars ? 'scale(1.05)' : 'scale(1)'
              }
            }, '★'))
          ),

          h('div', {
            style: { textAlign: 'center', fontSize: 12, color: 'var(--text-muted)', marginBottom: 16 }
          },
            {1: 'Terrible experience', 2: 'Poor', 3: 'Okay', 4: 'Good', 5: 'Excellent'}[reviewStars]
          ),

          h('textarea', {
            className: 'price-input',
            placeholder: "Optional — what went well or didn't? (max 500 chars)",
            value: reviewText,
            maxLength: 500,
            onChange: e => setReviewText(e.target.value),
            style: {
              width: '100%',
              minHeight: 88,
              padding: '10px 12px',
              fontSize: 13,
              fontFamily: 'inherit',
              resize: 'vertical',
              lineHeight: 1.5,
              marginBottom: 4
            }
          }),
          h('div', { style: { fontSize: 10, color: 'var(--text-muted)', textAlign: 'right', marginBottom: 14 } },
            reviewText.length + ' / 500'
          ),

          reviewErr && h('div', {
            style: {
              padding: '10px 12px',
              background: 'var(--red-dim)',
              border: '1px solid rgba(248, 113, 113, 0.3)',
              color: 'var(--red)',
              borderRadius: 6,
              fontSize: 12,
              marginBottom: 12
            }
          }, reviewErr),

          reviewDone && h('div', {
            style: {
              padding: '10px 12px',
              background: 'var(--green-dim)',
              border: '1px solid rgba(74, 222, 128, 0.3)',
              color: 'var(--green)',
              borderRadius: 6,
              fontSize: 12,
              marginBottom: 12,
              textAlign: 'center',
              fontWeight: 700
            }
          }, '✓ Review saved'),

          h('div', { style: { display: 'flex', gap: 10 } },
            h('button', {
              className: 'btn btn-ghost',
              style: { flex: 1, border: '1px solid var(--border)', justifyContent: 'center' },
              onClick: closeReview,
              disabled: reviewBusy
            }, 'Cancel'),
            h('button', {
              className: 'btn btn-accent',
              style: { flex: 2, justifyContent: 'center' },
              onClick: submitReview,
              disabled: reviewBusy || reviewDone
            }, reviewBusy ? 'Submitting…' : (reviewDone ? 'Saved' : 'Submit review'))
          )
        )
      )
    ),
    // Trade confirm modal — guards the buyer's final release-funds action.
    // Shows item, price, 2% platform fee, and the estimated seller payout so
    // the buyer sees exactly what they're confirming before escrow flips.
    confirmTrade && h('div', { className: 'modal-backdrop', onClick: () => setConfirmTrade(null) },
      h('div', {
        className: 'trade-confirm-modal',
        onClick: e => e.stopPropagation()
      },
        h('div', { className: 'trade-confirm-title' }, 'Confirm receipt'),
        h('div', { className: 'trade-confirm-sub' },
          'You are about to release funds to the seller. This cannot be undone. Only confirm if you have received the Steam trade offer and accepted it.'),
        h('div', { className: 'trade-confirm-detail' },
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Item'),
            h('span', { className: 'trade-confirm-v' }, confirmTrade.itemName || ('Trade #' + confirmTrade.id))
          ),
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Price you paid'),
            h('span', { className: 'trade-confirm-v' }, fmt(confirmTrade.price || 0))
          ),
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Platform fee (2%)'),
            h('span', { className: 'trade-confirm-v muted' }, fmt((confirmTrade.price || 0) * 0.02))
          ),
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Seller will receive'),
            h('span', { className: 'trade-confirm-v accent' }, fmt((confirmTrade.price || 0) * 0.98))
          )
        ),
        h('div', { className: 'trade-confirm-actions' },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            onClick: () => setConfirmTrade(null),
            disabled: busy
          }, 'Not yet'),
          h('button', {
            className: 'btn btn-accent',
            onClick: runConfirm,
            disabled: busy
          }, busy ? 'Confirming…' : 'Release funds')
        )
      )
    )
  );
}

function ProfileOffersTab() {
  // Two tabs (incoming / outgoing) with accept / reject / counter / cancel
  // buttons right on each row. Countering opens an inline input, so the user
  // never leaves the list. Reject + cancel confirm via native prompt.
  const [data, setData] = useState(null);
  const [tab, setTab]   = useState('incoming');
  const [busy, setBusy] = useState(false);
  const [counterFor, setCounterFor] = useState(null);
  const [counterAmt, setCounterAmt] = useState('');

  const load = useCallback(async () => {
    setData(null);
    const [i, o] = await Promise.all([fetchIncomingOffers(), fetchOutgoingOffers()]);
    setData({ incoming: i, outgoing: o });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (data === null) return h('div', { className: 'spinner' });

  const run = async (fn) => {
    setBusy(true);
    try { await fn(); await load(); }
    finally { setBusy(false); }
  };
  const doAccept  = (id) => run(() => acceptOffer(id));
  const doReject  = (id) => run(() => rejectOffer(id));
  const doCancel  = (id) => run(() => cancelOffer(id));
  const doCounter = async (id) => {
    const amt = parseFloat(counterAmt);
    if (!amt || amt <= 0) return;
    setBusy(true);
    try {
      const res = await counterOffer(id, amt);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      setCounterFor(null); setCounterAmt('');
      await load();
    } finally { setBusy(false); }
  };
  const doRaise = async (id) => {
    const amt = parseFloat(counterAmt);
    if (!amt || amt <= 0) return;
    setBusy(true);
    try {
      const { raiseOffer } = await import('./api.js');
      const res = await raiseOffer(id, amt);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      setCounterFor(null); setCounterAmt('');
      await load();
    } finally { setBusy(false); }
  };

  const row = (o, isIncoming) => {
    const pct = Math.round(
      (1 - parseFloat(o.amount) / parseFloat(o.askingPrice || o.amount)) * 100
    );
    const isCountering = counterFor === o.id;
    const isPending = o.status === 'PENDING';
    const fromLabel = isIncoming
      ? ((o.author === 'SELLER' ? 'Your counter to ' : '') + (o.buyerName || 'anon'))
      : (o.author === 'SELLER' ? 'Seller counter' : 'Your offer');
    // Auto-decline window — OfferService sweeper closes PENDING offers
    // after 7 days of inactivity (offer.auto-decline-days default). The
    // chip tells both sides how much runway is left before the offer
    // disappears — red under 24h, amber under 48h, muted otherwise.
    const OFFER_TTL_MS = 7 * 24 * 3600 * 1000;
    const expiresChip = (() => {
      if (!isPending) return null;
      const left = (o.updatedAt || o.createdAt || 0) + OFFER_TTL_MS - Date.now();
      if (left <= 0) return null;
      let label;
      if (left < 3600 * 1000)       label = Math.max(1, Math.round(left / 60_000)) + 'm';
      else if (left < 24 * 3600 * 1000) label = Math.max(1, Math.round(left / 3_600_000)) + 'h';
      else                               label = Math.max(1, Math.round(left / (24 * 3_600_000))) + 'd';
      const cls = left < 24 * 3600 * 1000 ? 'var(--red)'
               : left < 48 * 3600 * 1000 ? '#fbbf24'
                                         : 'var(--text-faint)';
      return h('span', {
        style: { marginLeft: 8, color: cls, fontWeight: 700 },
        title: 'This offer auto-declines after 7 days of inactivity. Either side can accept, reject, or counter before then.'
      }, '· expires in ' + label);
    })();

    return h('div', { key: o.id, className: 'offer-row' },
      // Tiny thread chain indicator if this row is a counter or was countered
      o.parentOfferId && h('div', { className: 'offer-thread-tag' }, '↳ counter to #' + o.parentOfferId),
      h('div', { className: 'offer-body' },
        h('div', { className: 'offer-title' }, o.itemName || ('Listing #' + o.listingId)),
        h('div', { className: 'offer-sub' },
          fromLabel, ' · ', timeAgo(o.createdAt),
          o.askingPrice && h('span', { style: { marginLeft: 8, color: 'var(--text-faint)' } },
            'Ask: ', fmt(o.askingPrice)),
          expiresChip
        )
      ),
      h('div', { className: 'offer-price' },
        h('div', { className: 'offer-amt' }, fmt(o.amount)),
        pct > 0 && h('div', { className: 'offer-pct' }, '−' + pct + '%')
      ),
      h('div', { className: 'offer-status-col' },
        h('div', { className: `wallet-tx-status ${o.status}` }, o.status),
        isPending && isIncoming && !isCountering && h('div', { style: { display: 'flex', gap: 4, marginTop: 6 } },
          h('button', { className: 'buy-btn', disabled: busy, onClick: () => doAccept(o.id), 'aria-label': 'Accept offer', title: 'Accept' }, '✓'),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 8px', fontSize: 10 },
            disabled: busy, onClick: () => { setCounterFor(o.id); setCounterAmt(String(parseFloat(o.amount) + 1)); },
            'aria-label': 'Counter offer', title: 'Counter'
          }, '⇄'),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 8px', fontSize: 10 },
            disabled: busy, onClick: () => doReject(o.id),
            'aria-label': 'Reject offer', title: 'Reject'
          }, '✕')
        ),
        // Outgoing pending offers — author is the buyer. Cancel is
        // always available; Raise lets the buyer escalate without
        // waiting for the seller to respond. Raise only makes sense on
        // USER-authored rows (you can't raise a seller's counter —
        // accept/reject/counter-again instead).
        isPending && !isIncoming && !isCountering && h('div', { style: { display: 'flex', gap: 4, marginTop: 6 } },
          o.author === 'USER' && h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--accent-border)', color: 'var(--accent)', padding: '5px 10px', fontSize: 11 },
            disabled: busy,
            onClick: () => { setCounterFor(o.id); setCounterAmt((parseFloat(o.amount) + 1).toFixed(2)); },
            title: 'Raise your offer without waiting for the seller'
          }, '↑ Raise'),
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
            disabled: busy, onClick: () => doCancel(o.id)
          }, 'Cancel')
        )
      ),
      isCountering && h('div', { className: 'offer-counter-form' },
        h('input', {
          className: 'price-input',
          type: 'number', step: '0.01', min: '0.01',
          placeholder: isIncoming ? 'Counter price' : 'Raise to',
          value: counterAmt,
          onChange: e => setCounterAmt(e.target.value)
        }),
        // Submit label changes by side: seller sends a counter, buyer
        // raises their own offer. Backend routes diverge but the inline
        // form shape is identical — reuse the same state + input.
        isIncoming
          ? h('button', { className: 'buy-btn', disabled: busy, onClick: () => doCounter(o.id) }, 'Send counter')
          : h('button', { className: 'buy-btn', disabled: busy, onClick: () => doRaise(o.id)   }, 'Raise offer'),
        h('button', { className: 'btn btn-ghost', style: { padding: '6px 10px', fontSize: 11 }, onClick: () => setCounterFor(null) }, '✕')
      )
    );
  };

  const list = tab === 'incoming' ? data.incoming : data.outgoing;

  return h('div', null,
    h('div', { className: 'offer-tabs' },
      h('button', { className: `offer-tab ${tab === 'incoming' ? 'active' : ''}`, onClick: () => setTab('incoming') },
        'Incoming', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.incoming.filter(o => o.status === 'PENDING').length)),
      h('button', { className: `offer-tab ${tab === 'outgoing' ? 'active' : ''}`, onClick: () => setTab('outgoing') },
        'Outgoing', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.outgoing.filter(o => o.status === 'PENDING').length))
    ),
    list.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '💬'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            tab === 'incoming' ? 'No incoming offers. Any time a buyer bargains on your listings, they show up here.'
                               : 'No outgoing offers. Use "Make Offer" from any item detail to bargain with a seller.'))
      : h('div', { className: 'offer-list' }, list.map(o => row(o, tab === 'incoming')))
  );
}

// ── Profile "Reviews received" — surfaces the same rows buyers see on
// the public stall page, but inside the seller's private profile + with
// a star-rating filter and an inline reply editor per row. Re-uses the
// `replyToReview` API from the stall view so the reply state is
// single-sourced.
function ProfileReviewsTab({ me }) {
  const [rows, setRows]       = useState(null);
  const [summary, setSummary] = useState(null);
  const [starFilter, setStarFilter] = useState(0);
  const [editId, setEditId]   = useState(null);
  const [draft, setDraft]     = useState('');
  const [busy, setBusy]       = useState(false);
  const load = useCallback(async () => {
    if (!me) return;
    const [list, sum] = await Promise.all([
      fetchReviewsForUser(me.id),
      fetchReviewSummary(me.id)
    ]);
    setRows(Array.isArray(list) ? list : []);
    setSummary(sum || { count: 0, average: null });
  }, [me?.id]);
  useEffect(() => { load(); }, [load]);

  if (!me) return h(InfoModal, { title: 'Reviews' }, h('div', { className: 'empty-inline' },
    h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view reviews.')));
  if (rows === null) return h('div', { className: 'spinner' });

  const filtered = starFilter > 0 ? rows.filter(r => r.rating === starFilter) : rows;
  const submit = async (reviewId, clear = false) => {
    setBusy(true);
    try {
      const res = await replyToReview(reviewId, clear ? '' : (draft || '').trim());
      if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
      setEditId(null); setDraft('');
      await load();
    } finally { setBusy(false); }
  };

  const avgLabel = summary && summary.count > 0
    ? `${Number(summary.average || 0).toFixed(1)} ★  ·  ${summary.count} review${summary.count === 1 ? '' : 's'}`
    : 'No reviews yet';

  // Per-star histogram — same shape RatingBreakdown in app.js renders
  // on the stall page. Inlined here to avoid modals.js → app.js imports.
  const buckets = Array.isArray(summary?.histogram) ? summary.histogram : [0,0,0,0,0];
  const maxBucket = Math.max(1, ...buckets);

  return h('div', null,
    h('div', { className: 'profile-reviews-head' },
      h('div', { className: 'profile-reviews-avg' }, avgLabel)
    ),
    (summary?.count || 0) >= 3 && h('div', { className: 'rating-breakdown', style: { marginBottom: 14 } },
      buckets.map((n, i) => {
        const stars = 5 - i;
        const pct = Math.round((n / maxBucket) * 100);
        return h('div', { key: stars, className: 'rating-breakdown-row' },
          h('span', { className: 'rating-breakdown-stars' }, stars + '★'),
          h('div', { className: 'rating-breakdown-bar' },
            h('div', { className: 'rating-breakdown-fill', style: { width: pct + '%' } })
          ),
          h('span', { className: 'rating-breakdown-count' }, n)
        );
      })
    ),
    rows.length > 0 && h('div', { className: 'profile-reviews-filter' },
      [0, 5, 4, 3, 2, 1].map(n => h('button', {
        key: n,
        className: `wallet-tx-filter-chip ${starFilter === n ? 'active' : ''}`,
        onClick: () => setStarFilter(n)
      }, n === 0 ? 'All' : `${n}★`))
    ),
    filtered.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '★'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            rows.length === 0
              ? 'No reviews yet. Every VERIFIED trade lets the buyer rate you 1–5 stars.'
              : 'No reviews match this filter.'))
      : h('div', { className: 'profile-reviews-list' },
          filtered.map(r => h('div', { key: r.id, className: 'stall-review' },
            h('div', { className: 'stall-review-head' },
              h('span', { className: 'stall-review-stars' }, '★'.repeat(r.rating) + '☆'.repeat(5 - r.rating)),
              h('span', { className: 'stall-review-from' }, r.fromDisplayName || 'Anonymous'),
              h('span', { className: 'stall-review-time' }, new Date(r.createdAt).toLocaleDateString())
            ),
            r.itemName && h('div', { className: 'stall-review-item' }, '↳ ' + r.itemName),
            r.comment && h('div', { className: 'stall-review-body' }, r.comment),
            r.sellerReply && editId !== r.id && h('div', { className: 'stall-review-reply' },
              h('span', { className: 'stall-review-reply-label' }, 'Your response'),
              h('div', { className: 'stall-review-reply-body' }, r.sellerReply)
            ),
            editId === r.id
              ? h('div', { className: 'stall-review-reply-edit' },
                  h('textarea', {
                    value: draft,
                    onChange: e => setDraft(e.target.value),
                    maxLength: 300,
                    placeholder: 'Public response (300 chars)',
                    autoFocus: true
                  }),
                  h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 } },
                    h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: busy, onClick: () => { setEditId(null); setDraft(''); } }, 'Cancel'),
                    h('button', { className: 'btn btn-accent', disabled: busy || !draft.trim(), onClick: () => submit(r.id, false) }, busy ? 'Saving…' : 'Post reply')
                  )
                )
              : h('div', { style: { marginTop: 8, display: 'flex', gap: 8 } },
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
                    onClick: () => { setDraft(r.sellerReply || ''); setEditId(r.id); }
                  }, r.sellerReply ? '✎ Edit reply' : '↩ Reply'),
                  r.sellerReply && h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
                    onClick: () => submit(r.id, true)
                  }, 'Remove reply')
                )
          ))
        )
  );
}

function ProfileSupportTab() {
  const [tickets, setTickets] = useState(null);
  const [viewing, setViewing] = useState(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ subject: '', category: 'OTHER', body: '' });
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  // Status filter — mirrors the backend's ticket lifecycle. 'ALL' = no
  // filter; default is 'OPEN' which excludes RESOLVED tickets so the
  // active queue is front and centre.
  const [statusFilter, setStatusFilter] = useState('OPEN');

  const load = useCallback(async () => { setTickets(await fetchSupportTickets()); }, []);
  useEffect(() => { load(); }, [load]);

  const openTicket = async (id) => {
    setViewing(await fetchSupportTicket(id));
  };

  const submitCreate = async () => {
    if (!form.subject.trim() || !form.body.trim()) return;
    setBusy(true);
    try {
      const res = await createSupportTicket(form);
      if (res && res.error) { alert(res.error); return; }
      setCreating(false);
      setForm({ subject: '', category: 'OTHER', body: '' });
      load();
    } finally { setBusy(false); }
  };

  const submitReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    setBusy(true);
    try {
      const res = await replySupportTicket(viewing.ticket.id, reply);
      if (res && res.error) { alert(res.error); return; }
      setReply('');
      setViewing(await fetchSupportTicket(viewing.ticket.id));
    } finally { setBusy(false); }
  };

  const resolve = async () => {
    if (!viewing?.ticket) return;
    const res = await resolveSupportTicket(viewing.ticket.id);
    if (res && res.error) { alert(res.error); return; }
    setViewing(await fetchSupportTicket(viewing.ticket.id));
    load();
  };

  if (viewing) {
    return h('div', { className: 'profile-panel' },
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 } },
        h('button', { className: 'btn btn-ghost', onClick: () => setViewing(null) }, '← Tickets'),
        h('div', { style: { flex: 1 } },
          h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, '#' + viewing.ticket.id + ' · ' + viewing.ticket.subject),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, viewing.ticket.category + ' · ' + viewing.ticket.status)
        ),
        viewing.ticket.status !== 'RESOLVED' && h('button', { className: 'btn btn-ghost', onClick: resolve, style: { border: '1px solid var(--border)' } }, 'Mark Resolved')
      ),
      h('div', { className: 'support-thread' },
        viewing.messages.map(m => h('div', { key: m.id, className: `support-msg ${m.author === 'STAFF' ? 'staff' : 'user'}` },
          h('div', { className: 'support-msg-head' }, m.authorName, ' · ', timeAgo(m.createdAt)),
          h('div', { className: 'support-msg-body' }, m.body)
        ))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 14 } },
        h('input', { className: 'chat-input', style: { flex: 1 }, placeholder: 'Reply…', value: reply, onChange: e => setReply(e.target.value), onKeyDown: e => { if (e.key === 'Enter') submitReply(); } }),
        h('button', { className: 'btn btn-accent', disabled: busy || !reply.trim(), onClick: submitReply }, 'Send')
      )
    );
  }

  return h('div', { className: 'profile-panel' },
    h('button', { className: 'btn btn-accent', style: { marginBottom: 14 }, onClick: () => setCreating(c => !c) },
      creating ? 'Cancel' : '+ New Ticket'),
    creating && h('div', { className: 'buyorder-form' },
      h('div', { className: 'wallet-input-label' }, 'Subject'),
      h('input', { className: 'wallet-amount-input', value: form.subject, onChange: e => setForm({ ...form, subject: e.target.value }), placeholder: 'Short subject line…' }),
      h('div', { className: 'wallet-input-label' }, 'Category'),
      h('select', { className: 'sort-select', value: form.category, onChange: e => setForm({ ...form, category: e.target.value }) },
        ['TRADE','PAYMENT','ACCOUNT','BUG','OTHER'].map(c => h('option', { key: c, value: c }, c))
      ),
      h('div', { className: 'wallet-input-label' }, 'Message'),
      h('textarea', { className: 'wallet-amount-input', style: { minHeight: 100, fontFamily: 'inherit' }, value: form.body, onChange: e => setForm({ ...form, body: e.target.value }), placeholder: 'Describe your issue…' }),
      h('button', { className: 'btn btn-accent wallet-submit', disabled: busy, onClick: submitCreate }, busy ? 'Submitting…' : 'Submit Ticket')
    ),
    (() => {
      if (tickets === null) return h('div', { className: 'spinner' });
      if (tickets.length === 0) return h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '🎧'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No tickets yet. Open one above if you need help.'));
      const filtered = statusFilter === 'ALL'
        ? tickets
        : statusFilter === 'OPEN'
          ? tickets.filter(t => t.status !== 'RESOLVED')
          : tickets.filter(t => t.status === statusFilter);
      const counts = {
        ALL: tickets.length,
        OPEN: tickets.filter(t => t.status !== 'RESOLVED').length,
        WAITING_STAFF: tickets.filter(t => t.status === 'WAITING_STAFF').length,
        WAITING_USER:  tickets.filter(t => t.status === 'WAITING_USER').length,
        RESOLVED:      tickets.filter(t => t.status === 'RESOLVED').length
      };
      return h('div', null,
        h('div', { className: 'wallet-tx-filter-row' },
          [
            { id: 'ALL',           label: 'All' },
            { id: 'OPEN',          label: 'Open' },
            { id: 'WAITING_STAFF', label: 'Waiting staff' },
            { id: 'WAITING_USER',  label: 'Waiting you' },
            { id: 'RESOLVED',      label: 'Resolved' }
          ].map(opt => h('button', {
            key: opt.id,
            className: `wallet-tx-filter-chip ${statusFilter === opt.id ? 'active' : ''}`,
            onClick: () => setStatusFilter(opt.id)
          }, `${opt.label} · ${counts[opt.id] || 0}`))
        ),
        filtered.length === 0
          ? h('div', { className: 'empty-inline' },
              h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } }, 'No tickets in this filter.'))
          : h('table', { className: 'db-table' },
              h('thead', null, h('tr', null,
                h('th', null, 'ID'), h('th', null, 'Subject'), h('th', null, 'Category'), h('th', null, 'Status'), h('th', { className: 'right' }, 'Updated'))),
              h('tbody', null,
                filtered.map(t => h('tr', { key: t.id, className: 'db-row', onClick: () => openTicket(t.id) },
                  h('td', { className: 'db-rank' }, '#' + t.id),
                  h('td', null, t.subject),
                  h('td', { className: 'db-cat' }, t.category),
                  h('td', { style: { fontSize: 10, fontWeight: 700 } }, t.status),
                  h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } }, timeAgo(t.updatedAt))
                ))
              )
            )
      );
    })()
  );
}

function ProfileDevelopersTab() {
  const [keys, setKeys] = useState(null);
  const [label, setLabel] = useState('');
  const [newKey, setNewKey] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => { setKeys(await fetchApiKeys()); }, []);
  useEffect(() => { load(); }, [load]);

  const mint = async () => {
    setBusy(true);
    try {
      const res = await createApiKey(label || 'Untitled');
      if (res && res.error) { alert(res.error); return; }
      setNewKey(res);
      setLabel('');
      load();
    } finally { setBusy(false); }
  };

  const revoke = async (id) => {
    if (!confirm('Revoke this API key? Applications using it will stop working immediately.')) return;
    const res = await revokeApiKey(id);
    if (res && res.error) { alert(res.error); return; }
    load();
  };

  return h('div', { className: 'profile-panel' },
    h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 14, padding: 10, background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 8 } },
      'API keys authenticate third-party bots and browser extensions. They carry your account rights — never paste them into a public file or chat.'),
    h('div', { style: { display: 'flex', gap: 8, marginBottom: 14 } },
      h('input', { className: 'price-input', placeholder: 'Label (e.g. my-bot)', style: { flex: 1 }, value: label, onChange: e => setLabel(e.target.value) }),
      h('button', { className: 'btn btn-accent', disabled: busy, onClick: mint }, busy ? 'Minting…' : '+ New Key')
    ),
    newKey && h('div', { className: 'api-key-new' },
      h('div', { style: { fontSize: 11, color: 'var(--yellow)', fontWeight: 700, marginBottom: 6, letterSpacing: 0.4 } },
        '⚠ COPY THIS NOW — it will not be shown again'),
      h('div', { className: 'api-key-token' }, newKey.token),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
        h('button', {
          className: 'btn btn-accent',
          style: { padding: '6px 14px', fontSize: 11 },
          onClick: async () => {
            try {
              if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(newKey.token);
                alert('Key copied. Paste it into your bot / extension config now.');
              } else {
                window.prompt('Copy the API key:', newKey.token);
              }
            } catch (_) {
              window.prompt('Copy the API key:', newKey.token);
            }
          }
        }, '⎘ Copy key'),
        h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
          href: 'data:application/json;charset=utf-8,' + encodeURIComponent(JSON.stringify({
            label: newKey.label, token: newKey.token, createdAt: newKey.createdAt
          }, null, 2)),
          download: `skinbox-api-key-${(newKey.label || 'key').replace(/[^a-z0-9]+/gi, '-')}.json`,
          title: 'Download the key as a JSON file (safer than a paste for long-term storage)'
        }, '⇣ Download .json'),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
          onClick: () => setNewKey(null)
        }, 'Dismiss')
      )
    ),
    keys === null
      ? h('div', { className: 'spinner' })
      : keys.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '🔑'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No API keys yet.'))
        : h('table', { className: 'db-table' },
            h('thead', null, h('tr', null,
              h('th', null, 'Label'),
              h('th', null, 'Prefix'),
              h('th', null, 'Created'),
              h('th', null, 'Last Used'),
              h('th', { className: 'right' }, 'Action'))),
            h('tbody', null, keys.map(k => h('tr', { key: k.id, className: 'db-row' },
              h('td', null, k.label || '—'),
              h('td', { className: 'db-mono' }, k.publicPrefix + '…'),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, new Date(k.createdAt).toLocaleDateString()),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, k.lastUsedAt ? timeAgo(k.lastUsedAt) : 'Never'),
              h('td', { className: 'right' },
                k.revoked
                  ? h('span', { style: { fontSize: 10, color: 'var(--red)', fontWeight: 700 } }, 'REVOKED')
                  : h('button', { className: 'btn btn-ghost', style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 }, onClick: () => revoke(k.id) }, 'Revoke')
              )
            )))
          )
  );
}

// ── Trades ──────────────────────────────────────────────────────
export function TradesModal({ onClose, transactions }) {
  const trades = transactions.filter(t => t.type === 'PURCHASE' || t.type === 'SALE');
  return h(InfoModal, { title: `Trades (${trades.length})`, onClose },
    trades.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '⇄'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No trades yet. Purchases and sales will show up here.'))
      : h('div', { className: 'wallet-tx-list', style: { maxHeight: 'none' } },
          trades.map(tx => {
            const inbound = tx.type === 'SALE';
            return h('div', { key: tx.id, className: 'wallet-tx' },
              h('div', { className: `wallet-tx-icon ${inbound ? 'in' : 'out'}` }, inbound ? '↓' : '↑'),
              h('div', { className: 'wallet-tx-main' },
                h('div', { className: 'wallet-tx-type' }, tx.type === 'PURCHASE' ? 'Purchase' : 'Sale'),
                h('div', { className: 'wallet-tx-desc' }, tx.description || '—')
              ),
              h('div', { className: 'wallet-tx-right' },
                h('div', { className: `wallet-tx-amt ${inbound ? 'in' : 'out'}` }, (inbound ? '+' : '−') + fmt(tx.amount)),
                h('div', { className: `wallet-tx-status ${tx.status}` }, tx.status)
              )
            );
          })
        )
  );
}

// ── Sell Items (from Steam inventory + from in-market inventory) ──
//
// Two sources:
//   1) Steam inventory — items the user actually owns on Steam (live fetch
//      from the Steam community API via our /api/steam/inventory endpoint).
//      Picking one and entering a price calls POST /api/steam/list which
//      creates the catalogue entry on the fly if the item is new.
//   2) sboxmarket inventory — items the user bought *on sboxmarket* and
//      wants to relist. Same flow as before via relistItem().
//
// CSFloat has the same split — "Your Steam items" and "Owned on platform".
export function SellItemsModal({ onClose, me, onRefresh }) {
  const [source, setSource]       = useState('steam'); // steam | internal
  const [steamData, setSteamData] = useState(null);    // {items, count, lastSyncedAt}
  const [internal, setInternal]   = useState(null);
  const [picking, setPicking]     = useState(null);    // { kind: 'steam'|'internal', item: {...} }
  const [price, setPrice]         = useState('');
  const [busy, setBusy]           = useState(false);
  const [error, setError]         = useState('');
  const [syncing, setSyncing]     = useState(false);
  // Filter chips — narrow the Steam inventory view when the user has a
  // lot of items. Rarity filter + free-text name search. Pure client-
  // side; backend still returns the full set.
  const [sellRarityFilter, setSellRarityFilter] = useState('All');
  const [sellSearch, setSellSearch]             = useState('');

  const loadSteam = useCallback(async () => {
    setSteamData(await fetchSteamInventory());
  }, []);
  const loadInternal = useCallback(async () => {
    setInternal(await fetchInventory());
  }, []);

  useEffect(() => {
    if (!me) return;
    loadSteam();
    loadInternal();
  }, [me, loadSteam, loadInternal]);

  if (!me) return h(InfoModal, { title: 'Sell Items', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 14 } }, 'Sign in with Steam to sell items.'),
      h('a', { className: 'steam-btn', href: '/api/auth/steam/login' },
        h('div', { className: 'steam-btn-icon' }, '◆'), 'Sign in through Steam')));

  const resync = async () => {
    setSyncing(true);
    try { await syncSteam(); await loadSteam(); }
    finally { setSyncing(false); }
  };

  const startPickSteam = (si) => {
    setPicking({ kind: 'steam', item: si });
    setPrice(parseFloat(si.suggestedPrice || 0).toFixed(2));
    setError('');
  };
  const startPickInternal = (l) => {
    setPicking({ kind: 'internal', item: l.item, listingId: l.id });
    setPrice(parseFloat(l.item.lowestPrice).toFixed(2));
    setError('');
  };

  const submit = async () => {
    setError('');
    const p = parseFloat(price);
    if (!p || p <= 0) { setError('Enter a valid price'); return; }
    setBusy(true);
    try {
      let res;
      if (picking.kind === 'steam') {
        res = await listFromSteam(picking.item.assetId, p);
      } else {
        res = await relistItem(picking.listingId, p);
      }
      if (res.code || res.error) { setError(res.message || res.error); return; }
      await onRefresh();
      onClose();
    } finally { setBusy(false); }
  };

  if (picking) {
    const item = picking.item;
    const isSteam = picking.kind === 'steam';
    const suggested = isSteam ? parseFloat(item.suggestedPrice || 0).toFixed(2) : parseFloat(item.lowestPrice).toFixed(2);
    return h(InfoModal, { title: 'List Item for Sale', onClose },
      h('div', { style: { display: 'flex', gap: 18, marginBottom: 20 } },
        h('div', { style: { width: 120, aspectRatio: '1', borderRadius: 10, background: 'radial-gradient(ellipse at 50% 35%, rgba(30,165,255,0.14) 0%, transparent 65%), linear-gradient(180deg, #1a2236 0%, #0d1320 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, padding: 8 } },
          h(ItemImage, {
            item: isSteam
              ? { imageUrl: item.imageUrl || item.iconUrl, name: item.name, category: item.category, iconEmoji: '👕' }
              : item,
            variant: 'card'
          })
        ),
        h('div', null,
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 700, marginBottom: 4 } }, item.category || 'Steam item'),
          h('div', { style: { fontSize: 18, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 10 } }, item.name),
          isSteam && !item.tradable && h('div', { style: { fontSize: 11, color: 'var(--red)', marginBottom: 8, fontWeight: 700 } }, '⚠ Not tradable on Steam right now'),
          h(RarityBadge, { rarity: item.rarity || 'Standard' }),
          h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 12 } },
            'Suggested price: ', h('span', { style: { color: 'var(--accent)', fontWeight: 700 } }, '$' + suggested))
        )
      ),
      h('div', { className: 'wallet-input-label' }, 'Your asking price (USD)'),
      h('input', {
        className: 'wallet-amount-input',
        type: 'number', min: '0.01', step: '0.01',
        placeholder: suggested,
        value: price,
        onChange: e => setPrice(e.target.value)
      }),
      // Price suggestion chips. Floor = current market low, Steam = what
      // Steam Community Market is charging, Undercut 5% = classic fast-sell
      // move, Markup 5% = for new/rare items without competition. All
      // editable via the input above, so chips just prefill.
      h('div', { className: 'price-suggest-row' },
        (() => {
          const floor = parseFloat(isSteam ? (item.suggestedPrice || 0) : (item.lowestPrice || 0));
          const steamPrice = parseFloat(isSteam ? (item.steamPrice || item.suggestedPrice || 0) : (item.steamPrice || 0));
          const chips = [];
          if (floor > 0) chips.push({ label: 'Floor', v: floor });
          if (floor > 0) chips.push({ label: '−5%', v: +(floor * 0.95).toFixed(2), hint: 'Undercut, sells faster' });
          if (floor > 0) chips.push({ label: '+5%', v: +(floor * 1.05).toFixed(2), hint: 'Patience markup' });
          if (steamPrice > 0 && Math.abs(steamPrice - floor) > 0.01) chips.push({ label: 'Steam', v: steamPrice });
          return chips.map((c, i) => h('button', {
            key: i,
            type: 'button',
            className: 'price-suggest-chip',
            title: c.hint || '',
            onClick: () => setPrice(c.v.toFixed(2))
          },
            h('span', { className: 'price-suggest-chip-label' }, c.label),
            h('span', { className: 'price-suggest-chip-amt' }, '$' + c.v.toFixed(2))
          ));
        })()
      ),
      (() => {
        const p = parseFloat(price) || 0;
        if (p <= 0) return h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 8 } },
          'A 2% platform fee is deducted when the item sells.');
        const fee = +(p * 0.02).toFixed(2);
        const net = +(p - fee).toFixed(2);
        const floor = parseFloat(isSteam ? (item.suggestedPrice || 0) : (item.lowestPrice || 0));
        const vsFloor = (floor > 0 && p > 0) ? Math.round(((p - floor) / floor) * 100) : null;
        return h('div', {
          style: {
            fontSize: 12, marginTop: 10, padding: 10, borderRadius: 6,
            background: 'var(--bg-elevated)', border: '1px solid var(--border)',
            display: 'grid', gridTemplateColumns: '1fr auto', gap: 4, rowGap: 2
          }
        },
          h('div', { style: { color: 'var(--text-muted)' } }, 'Listed price'),
          h('div', { style: { color: 'var(--text-primary)', fontWeight: 700, fontFamily: 'JetBrains Mono, monospace' } }, fmt(p)),
          h('div', { style: { color: 'var(--text-muted)' } }, 'Platform fee (2%)'),
          h('div', { style: { color: 'var(--red)', fontWeight: 700, fontFamily: 'JetBrains Mono, monospace' } }, '−' + fmt(fee)),
          h('div', { style: { color: 'var(--text-muted)', fontWeight: 700 } }, "You'll receive"),
          h('div', { style: { color: 'var(--accent)', fontWeight: 800, fontFamily: 'JetBrains Mono, monospace' } }, fmt(net)),
          vsFloor !== null && h('div', {
            style: { gridColumn: '1 / -1', fontSize: 10, color: 'var(--text-muted)', marginTop: 4, borderTop: '1px solid var(--border)', paddingTop: 6 }
          },
            vsFloor === 0 ? 'At the current floor — competitive with other active listings.' :
            vsFloor < 0    ? `${Math.abs(vsFloor)}% below floor — expected to sell quickly.` :
            vsFloor < 10   ? `${vsFloor}% above floor — may sit in queue behind cheaper listings.` :
                             `${vsFloor}% above floor — buyers will pass on this unless the item is rare or the floor shifts up.`
          )
        );
      })(),
      error && h('div', { className: 'wallet-error' }, error),
      h('div', { style: { display: 'flex', gap: 10, marginTop: 20 } },
        h('button', { className: 'btn btn-ghost', style: { flex: 1, border: '1px solid var(--border)', justifyContent: 'center', padding: 13 }, onClick: () => setPicking(null) }, 'Back'),
        h('button', { className: 'btn btn-accent', style: { flex: 1, justifyContent: 'center', padding: 13 }, disabled: busy || (isSteam && !item.tradable), onClick: submit }, busy ? 'Listing…' : 'List for Sale')
      )
    );
  }

  const steamList = steamData?.items || [];
  const internalList = internal || [];

  return h(InfoModal, { title: 'Sell Items', onClose },
    h('div', { className: 'sell-source-tabs' },
      h('button', { className: `offer-tab ${source === 'steam' ? 'active' : ''}`, onClick: () => setSource('steam') },
        '🎮 Steam Inventory', steamData && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, steamList.length)),
      h('button', { className: `offer-tab ${source === 'internal' ? 'active' : ''}`, onClick: () => setSource('internal') },
        '📦 Platform Inventory', internal && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, internalList.length)),
      h('div', { style: { flex: 1 } }),
      source === 'steam' && h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 }, disabled: syncing, onClick: resync }, syncing ? 'Syncing…' : 'Sync Steam')
    ),

    source === 'steam' && steamData === null && h('div', { className: 'spinner' }),
    source === 'steam' && steamData && steamList.length === 0 && h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🎮'),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'No s&box items in your Steam inventory'),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
        'Either your Steam inventory is private, or there are no s&box cosmetics in it. Click Sync Steam to retry.')
    ),
    source === 'steam' && steamData && steamList.length > 0 && (() => {
      // Apply filter chips + name search before rendering. Rarity chips
      // derived from whatever rarities the user's inventory actually
      // contains so we don't dangle empty buttons.
      const q = sellSearch.trim().toLowerCase();
      const filtered = steamList.filter(si => {
        if (sellRarityFilter !== 'All' && (si.rarity || 'Standard') !== sellRarityFilter) return false;
        if (q && !(si.name || '').toLowerCase().includes(q)) return false;
        return true;
      });
      const rarities = Array.from(new Set(steamList.map(s => s.rarity || 'Standard'))).sort();
      return h('div', null,
        // Summary chips: tradable / matched / new.
        h('div', { className: 'sell-summary' },
          h('div', { className: 'sell-summary-chip' },
            h('span', { className: 'sell-summary-num' }, steamList.length), ' total'),
          h('div', { className: 'sell-summary-chip ok' },
            h('span', { className: 'sell-summary-num' }, steamList.filter(s => s.tradable).length), ' tradable'),
          h('div', { className: 'sell-summary-chip warn' },
            h('span', { className: 'sell-summary-num' }, steamList.filter(s => !s.tradable).length), ' locked'),
          h('div', { className: 'sell-summary-chip accent' },
            h('span', { className: 'sell-summary-num' }, steamList.filter(s => s.catalogueId).length), ' already in catalogue'),
          h('div', { className: 'sell-summary-chip' },
            h('span', { className: 'sell-summary-num' }, steamList.filter(s => !s.catalogueId).length), ' new to sboxmarket')
        ),
        // Filter bar — rarity chips + free-text search. Hides when the
        // inventory has <=6 items because the chips add noise for free.
        steamList.length > 6 && h('div', { className: 'sell-filter-bar' },
          h('button', {
            className: `wallet-tx-filter-chip ${sellRarityFilter === 'All' ? 'active' : ''}`,
            onClick: () => setSellRarityFilter('All')
          }, `All · ${steamList.length}`),
          rarities.map(r => h('button', {
            key: r,
            className: `wallet-tx-filter-chip ${sellRarityFilter === r ? 'active' : ''}`,
            onClick: () => setSellRarityFilter(r)
          }, `${r} · ${steamList.filter(s => (s.rarity || 'Standard') === r).length}`)),
          h('input', {
            className: 'sell-filter-search',
            placeholder: 'Search items…',
            value: sellSearch,
            onChange: e => setSellSearch(e.target.value)
          }),
          (sellRarityFilter !== 'All' || sellSearch) && h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
            onClick: () => { setSellRarityFilter('All'); setSellSearch(''); }
          }, 'Clear')
        ),
        filtered.length === 0
          ? h('div', { className: 'empty-inline' },
              h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } },
                'No Steam items match this filter.'))
          : h('div', { className: 'inventory-grid' },
        filtered.map(si => h('div', {
          key: si.assetId,
          className: `inventory-item ${si.tradable ? '' : 'disabled'}`,
          onClick: () => startPickSteam(si),
          role: 'button',
          tabIndex: si.tradable ? 0 : -1,
          'aria-disabled': !si.tradable
        },
          !si.catalogueId && h('div', { className: 'inventory-new-badge' }, 'NEW'),
          h('div', { className: 'inventory-thumb' },
            // Wrap the Steam-shape record into the shape ItemImage expects so it
            // gets the same lazy-load + poster-fallback treatment as every other
            // thumbnail. If Steam's CDN 404s we end up with a category glyph
            // instead of a broken-image icon.
            h(ItemImage, { item: { imageUrl: si.imageUrl || si.iconUrl, name: si.name, category: si.category, iconEmoji: '👕' }, variant: 'card' })
          ),
          h('div', { className: 'inventory-name' }, si.name),
          h('div', { className: 'inventory-floor' }, si.catalogueId ? 'Floor ' + fmt(si.suggestedPrice) : 'Set your price'),
          !si.tradable && h('div', { style: { fontSize: 9, color: 'var(--red)', fontWeight: 700, marginTop: 2 } }, 'NOT TRADABLE')
        ))
      )
      );
    })(),

    source === 'internal' && internal === null && h('div', { className: 'spinner' }),
    source === 'internal' && internal && internalList.length === 0 && h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '📦'),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'Platform inventory empty'),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
        'Items you buy on sboxmarket appear here. You can relist any of them at a new price.')
    ),
    source === 'internal' && internalList.length > 0 && (() => {
      // Match the Steam-inventory tab: filter chips for rarity + a
      // name search. A heavy buyer who's accumulated dozens of items
      // shouldn't have to eyeball the grid to find one specific hat.
      // Share state with the Steam tab so flipping between the two
      // preserves the filter intent.
      const rarities = Array.from(new Set(
        internalList.map(l => l.item?.rarity || 'Standard')
      )).sort();
      const q = sellSearch.trim().toLowerCase();
      const filtered = internalList.filter(l => {
        if (sellRarityFilter !== 'All' && (l.item?.rarity || 'Standard') !== sellRarityFilter) return false;
        if (!q) return true;
        return (l.item?.name || '').toLowerCase().includes(q);
      });
      return h('div', null,
        (rarities.length > 1 || internalList.length > 6) && h('div', { className: 'sell-filter-bar', style: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, marginBottom: 12 } },
          h('button', {
            className: `wallet-tx-filter-chip ${sellRarityFilter === 'All' ? 'active' : ''}`,
            onClick: () => setSellRarityFilter('All')
          }, `All · ${internalList.length}`),
          rarities.map(r => h('button', {
            key: r,
            className: `wallet-tx-filter-chip ${sellRarityFilter === r ? 'active' : ''}`,
            onClick: () => setSellRarityFilter(r)
          }, `${r} · ${internalList.filter(l => (l.item?.rarity || 'Standard') === r).length}`)),
          h('div', { style: { flex: 1, minWidth: 140 } },
            h('input', {
              className: 'price-input',
              style: { width: '100%', fontSize: 12 },
              placeholder: '🔎 Filter inventory…',
              value: sellSearch,
              onChange: e => setSellSearch(e.target.value)
            })
          ),
          (sellRarityFilter !== 'All' || sellSearch) && h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
            onClick: () => { setSellRarityFilter('All'); setSellSearch(''); }
          }, 'Clear')
        ),
        filtered.length === 0
          ? h('div', { className: 'empty-inline', style: { marginTop: 8 } },
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)' } },
                'No platform-inventory items match this filter.'))
          : h('div', { className: 'inventory-grid' },
              filtered.map(l => h('div', {
                key: l.id, className: 'inventory-item',
                onClick: () => startPickInternal(l)
              },
                h('div', { className: 'inventory-thumb' }, h(ItemImage, { item: l.item })),
                h('div', { className: 'inventory-name' }, l.item.name),
                h('div', { className: 'inventory-floor' }, 'Floor ' + fmt(l.item.lowestPrice))
              ))
            )
      );
    })()
  );
}

// ── My Stall ────────────────────────────────────────────────────
export function MyStallModal({ onClose, me, onRefresh }) {
  const [stall, setStall] = useState(null);
  const [sold, setSold] = useState(null);
  const [tab, setTab] = useState('active'); // 'active' | 'sold'
  const [editing, setEditing] = useState(null); // listing id being edited inline
  const [editPrice, setEditPrice] = useState('');
  const [editDesc, setEditDesc]   = useState('');
  // Auto-accept offer discount (0..50 stored as integer percent in the
  // UI, converted to 0..1 fraction on save). Empty string = no auto-
  // accept (listing.maxDiscount stays null).
  const [editAutoPct, setEditAutoPct] = useState('');
  const [away, setAway] = useState(false);
  // Per-listing PENDING offer summary — keyed by listingId. Null until
  // the first fetch lands so the chip row doesn't flash. Re-fetched
  // alongside the stall load so a row the seller just listed still
  // gets its chip as soon as a buyer bargains.
  const [offerMap, setOfferMap] = useState({});
  const load = useCallback(() => {
    fetchMyStall().then(setStall);
    fetchBestOfferPerListing().then(m => setOfferMap(m || {}));
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);
  // Load sold history lazily when the user clicks the tab — avoids a second
  // list fetch on every modal open for sellers who never touch the history.
  useEffect(() => {
    if (!me || tab !== 'sold' || sold !== null) return;
    fetchMyStallSold().then(setSold);
  }, [me, tab, sold]);

  if (!me) return h(InfoModal, { title: 'My Stall', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your stall.')));
  if (stall === null) return h(InfoModal, { title: 'My Stall', onClose }, h('div', { className: 'spinner' }));

  const doCancel = async (id) => {
    if (!confirm('Remove this listing?')) return;
    const res = await cancelListing(id);
    if (res && res.error) { alert(res.error); return; }
    load();
    onRefresh && onRefresh();
  };

  const startEdit = (l) => {
    setEditing(l.id);
    setEditPrice(parseFloat(l.price).toFixed(2));
    setEditDesc(l.description || '');
    // Backend ships the fraction (0.20). The UI edits as an integer
    // percent (20). Empty string when no auto-accept is set so the
    // input placeholder stays readable.
    const md = parseFloat(l.maxDiscount);
    setEditAutoPct(isFinite(md) && md > 0 ? Math.round(md * 100).toString() : '');
  };

  const saveEdit = async (id) => {
    const patch = {
      price: parseFloat(editPrice),
      description: editDesc
    };
    // Translate the integer percent back to a 0..1 fraction. Empty
    // string / 0 clears the auto-accept so the seller opts out.
    const pct = parseFloat(editAutoPct);
    if (editAutoPct.trim() === '' || !isFinite(pct) || pct <= 0) {
      patch.maxDiscount = null;
    } else {
      patch.maxDiscount = Math.min(50, Math.max(1, pct)) / 100;
    }
    const res = await updateStallListing(id, patch);
    if (res && res.error) { alert(res.error); return; }
    setEditing(null);
    load();
    onRefresh && onRefresh();
  };

  const toggleHidden = async (l) => {
    const res = await updateStallListing(l.id, { hidden: !l.hidden });
    if (res && res.error) { alert(res.error); return; }
    load();
  };

  const toggleAway = async () => {
    const next = !away;
    setAway(next);
    const res = await setAwayMode(next);
    if (res && res.error) { setAway(!next); alert(res.error); return; }
    load();
  };

  const soldTotal = sold ? sold.reduce((s, l) => s + (parseFloat(l.price) || 0), 0) : 0;

  // Bulk price adjust — apply ±% to every active non-auction listing. Max
  // ±50% per pass (server cap); auctions are skipped server-side. Kept
  // compact so it lives inline in the toolbar rather than a separate
  // modal. One confirm prompt since it touches every row.
  const bulkAdjust = async () => {
    const raw = window.prompt(
      'Apply a percentage adjustment to every active BUY NOW listing.\n' +
      'Positive = markup, negative = discount. Max ±50 per pass.\n\n' +
      'e.g. -5 for a 5% discount');
    if (raw == null) return;
    const pct = parseFloat(raw);
    if (!isFinite(pct) || pct === 0) return;
    if (Math.abs(pct) > 50) { alert('Max ±50% per pass'); return; }
    const res = await bulkAdjustStall(pct);
    if (res && (res.error || res.code)) { alert(res.message || res.error || 'Failed'); return; }
    alert(`Touched ${res.touched || 0} listing${(res.touched || 0) === 1 ? '' : 's'}, skipped ${res.skipped || 0} (auctions / unchanged).`);
    load();
    onRefresh && onRefresh();
  };

  // Book value — sum of ask prices across every active listing. The
  // "liquidation ceiling": if every row sold at ask (ignoring the 2%
  // fee), this is the seller's gross. Lives alongside the away toggle
  // so a seller sees their total exposure at a glance.
  const stallBookValue = stall.reduce((s, l) => s + (parseFloat(l.price) || 0), 0);
  const pendingOfferRows = Object.keys(offerMap || {}).length;

  return h(InfoModal, { title: `My Stall · ${stall.length} active`, onClose },
    h('div', { className: 'stall-toolbar' },
      h('button', {
        className: `chat-toggle ${away ? '' : 'off'}`,
        style: { display: 'inline-block', verticalAlign: 'middle', marginRight: 10 },
        onClick: toggleAway
      }),
      h('span', { style: { fontSize: 12, color: 'var(--text-secondary)' } },
        'Away Mode — ', away ? 'all listings hidden' : 'listings visible'
      ),
      // Book-value chip — neutral signal, silent when the stall is
      // empty so first-time sellers don't see a "$0.00" stub.
      stall.length > 0 && h('span', {
        style: {
          marginLeft: 14, fontSize: 11, fontWeight: 700, color: 'var(--text-primary)',
          background: 'var(--bg-elevated)', border: '1px solid var(--border)',
          padding: '4px 10px', borderRadius: 6
        },
        title: 'Sum of ask prices across every active listing. The "liquidation ceiling" before the 2% platform fee.'
      },
        'Book value · ', fmt(stallBookValue)
      ),
      // Pending-offers chip — compact sibling to the per-row chip,
      // shown when at least one listing has a PENDING buyer offer so
      // a seller who just opened the stall sees the count before
      // scrolling. Amber tint matches the per-row chip.
      pendingOfferRows > 0 && h('a', {
        href: '/offers',
        style: {
          marginLeft: 8, fontSize: 11, fontWeight: 700, color: '#fbbf24',
          background: 'rgba(251,191,36,0.12)', border: '1px solid rgba(251,191,36,0.35)',
          padding: '4px 10px', borderRadius: 6, textDecoration: 'none'
        },
        title: `${pendingOfferRows} listing${pendingOfferRows === 1 ? '' : 's'} with pending buyer offers`
      },
        '💬 ', pendingOfferRows, ' ', pendingOfferRows === 1 ? 'listing' : 'listings', ' with offers'
      ),
      h('div', { style: { flex: 1 } }),
      stall.length > 0 && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
        onClick: bulkAdjust,
        title: 'Apply a ±% adjustment to every active BUY NOW listing'
      }, '⚖ Bulk price adjust')
    ),
    // Active vs Sold tabs. Sold tab reveals a seller's own sale history
    // with gross revenue (pre-fee). Matches CSFloat's "My Sales" list.
    h('div', { className: 'mystall-tabs' },
      h('button', {
        className: `offer-tab ${tab === 'active' ? 'active' : ''}`,
        onClick: () => setTab('active')
      }, 'Active ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, stall.length)),
      h('button', {
        className: `offer-tab ${tab === 'sold' ? 'active' : ''}`,
        onClick: () => setTab('sold')
      }, 'Sold ', sold ? h('span', { className: 'filter-count', style: { marginLeft: 6 } }, sold.length) : null)
    ),
    tab === 'sold' && (
      sold === null
        ? h('div', { className: 'spinner' })
        : sold.length === 0
          ? h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, '📦'),
              h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
                "You haven't sold anything yet. Listings you post will appear here once a buyer confirms."))
          : h('div', null,
              h('div', { className: 'mystall-sold-summary' },
                h('span', { className: 'mystall-sold-label' }, 'Gross revenue · last ' + sold.length + ' sales'),
                h('span', { className: 'mystall-sold-total' }, fmt(soldTotal)),
                h('span', { className: 'mystall-sold-hint' }, '(2% platform fee already deducted at payout)'),
                h('a', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11, marginLeft: 'auto' },
                  href: '/api/listings/my-stall/sold.csv',
                  title: 'Download the last 1,000 of your settled sales as CSV — pairs with the wallet transactions export for accounting'
                }, '⇣ CSV')
              ),
              h('div', { className: 'recent-sales-list' },
                sold.map(l => h('div', { key: l.id, className: 'recent-sales-row' },
                  h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 } },
                    h('div', { className: 'item-thumb', style: { width: 28, height: 28, flexShrink: 0 } }, h(ItemImage, { item: l.item })),
                    h('span', { style: { fontSize: 12.5, color: 'var(--text-primary)', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } }, l.item?.name || 'Item')
                  ),
                  h('span', { className: 'recent-sales-price' }, fmt(l.price)),
                  h('span', { className: 'recent-sales-time' }, timeAgo(l.soldAt || l.updatedAt || l.listedAt))
                ))
              )
            )
    ),
    tab === 'active' && (stall.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '🏪'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            'Your stall is empty. Use Sell Items to list something.'))
      : h('div', { className: 'stall-list' },
          stall.map(l => h('div', { key: l.id, className: `stall-row ${l.hidden ? 'hidden-listing' : ''}` },
            h('div', { className: 'item-thumb', style: { width: 48, height: 48 } }, h(ItemImage, { item: l.item })),
            h('div', { style: { flex: 1, minWidth: 0 } },
              h('div', { className: 'item-name' }, l.item.name,
                l.hidden && h('span', { style: { marginLeft: 8, fontSize: 10, color: 'var(--text-muted)', fontWeight: 700 } }, '· HIDDEN')
              ),
              editing === l.id
                ? h('div', { style: { marginTop: 6, display: 'flex', gap: 6 } },
                    h('input', { className: 'price-input', value: editPrice, onChange: e => setEditPrice(e.target.value), placeholder: 'price', style: { width: 90 } }),
                    h('input', { className: 'price-input', value: editDesc, maxLength: 64, onChange: e => setEditDesc(e.target.value), placeholder: 'description (64 chars)', style: { flex: 1 } }),
                    h('input', {
                      className: 'price-input',
                      value: editAutoPct,
                      onChange: e => setEditAutoPct(e.target.value.replace(/[^0-9]/g, '')),
                      placeholder: 'auto %',
                      title: 'Auto-accept offers at or above this % discount (blank = off). e.g. 20 = accept offers ≥ 80% of ask.',
                      inputMode: 'numeric',
                      style: { width: 80 }
                    })
                  )
                : h('div', { className: 'item-sub' },
                    l.item.category + ' · listed ' + timeAgo(l.listedAt),
                    l.description && h('span', { style: { marginLeft: 8, fontStyle: 'italic' } }, '"' + l.description + '"'),
                    // Best-offer chip — renders when the seller has at
                    // least one PENDING buyer offer on this listing.
                    // One click routes straight to the Offers tab so
                    // the seller can respond without hunting.
                    (() => {
                      const o = offerMap[l.id];
                      if (!o || !(o.count > 0)) return null;
                      const pct = Math.max(0, Math.round(
                        (1 - parseFloat(o.bestAmount) / parseFloat(l.price)) * 100));
                      return h('a', {
                        href: '/offers',
                        onClick: (e) => { e.stopPropagation(); },
                        style: {
                          marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                          fontSize: 10, fontWeight: 700, color: '#fbbf24',
                          background: 'rgba(251,191,36,0.12)',
                          border: '1px solid rgba(251,191,36,0.35)',
                          textDecoration: 'none'
                        },
                        title: `${o.count} pending offer${o.count === 1 ? '' : 's'} · best ${fmt(o.bestAmount)}${pct > 0 ? ` (−${pct}% vs ask)` : ''}`
                      },
                        '💬 Best offer ', fmt(o.bestAmount),
                        o.count > 1 ? ` · ${o.count}` : ''
                      );
                    })()
                  )
            ),
            editing === l.id
              ? h('div', { style: { display: 'flex', gap: 6 } },
                  h('button', { className: 'buy-btn', onClick: () => saveEdit(l.id) }, 'Save'),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 12px', fontSize: 11 }, onClick: () => setEditing(null) }, '✕')
                )
              : h('div', { style: { display: 'flex', gap: 6, alignItems: 'center' } },
                  h('div', { className: 'price-val', style: { marginRight: 10 } }, fmt(l.price)),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 10px', fontSize: 11 }, onClick: () => startEdit(l) }, '✎ Edit'),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 10px', fontSize: 11 }, onClick: () => toggleHidden(l) }, l.hidden ? '👁 Show' : '🙈 Hide'),
                  // Copy a direct link to this listing's item detail page —
                  // sellers paste it into Discord / Steam groups to drive
                  // traffic. Falls back to window.prompt on old browsers
                  // without Clipboard API access.
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '7px 10px', fontSize: 11 },
                    title: 'Copy a link to this listing',
                    onClick: async () => {
                      const url = `${window.location.origin}/item/${l.item.id}`;
                      try {
                        if (navigator.clipboard?.writeText) {
                          await navigator.clipboard.writeText(url);
                          alert('Link copied to clipboard:\n' + url);
                        } else {
                          window.prompt('Copy this link:', url);
                        }
                      } catch (_) { window.prompt('Copy this link:', url); }
                    }
                  }, '⎘ Link'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 10px', fontSize: 11 },
                    onClick: () => doCancel(l.id)
                  }, '🗑')
                )
          ))
        )
    )
  );
}

// ── Offers (incoming/outgoing) ───────────────────────────────────
export function OffersModal({ onClose, me, onRefresh }) {
  const [tab, setTab]       = useState('incoming');
  const [incoming, setIn]   = useState(null);
  const [outgoing, setOut]  = useState(null);
  const [busy, setBusy]     = useState(false);

  const load = useCallback(async () => {
    const [i, o] = await Promise.all([fetchIncomingOffers(), fetchOutgoingOffers()]);
    setIn(i);
    setOut(o);
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  if (!me) return h(InfoModal, { title: 'Offers', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, '🔒'),
      h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Sign in to view your offers.')));

  const handleAccept = async (id) => {
    if (busy) return;
    setBusy(true);
    try {
      const res = await acceptOffer(id);
      if (res.code || res.error) { alert(res.message || res.error); return; }
      await load();
      onRefresh && onRefresh();
    } finally { setBusy(false); }
  };
  const handleReject = async (id) => {
    if (busy) return;
    setBusy(true);
    try { await rejectOffer(id); await load(); }
    finally { setBusy(false); }
  };
  const handleCancel = async (id) => {
    if (busy) return;
    setBusy(true);
    try { await cancelOffer(id); await load(); }
    finally { setBusy(false); }
  };

  const renderOffer = (offer, isIncoming) => {
    const diff   = parseFloat(offer.askingPrice) - parseFloat(offer.amount);
    const pctOff = Math.round(diff / parseFloat(offer.askingPrice) * 100);
    return h('div', { key: offer.id, className: 'offer-row' },
      h('div', { className: 'item-thumb', style: { width: 56, height: 56 } },
        offer.itemImageUrl
          ? h('img', { src: offer.itemImageUrl, alt: offer.itemName })
          : h('span', null, '📦')
      ),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { className: 'item-name' }, offer.itemName || ('Listing #' + offer.listingId)),
        h('div', { className: 'item-sub' },
          isIncoming ? `From ${offer.buyerName}` : 'Your offer',
          ' · ', timeAgo(offer.createdAt)
        )
      ),
      h('div', { style: { textAlign: 'right', marginRight: 14 } },
        h('div', { style: { fontSize: 14, fontWeight: 800, color: 'var(--accent)', fontFamily: 'JetBrains Mono, monospace' } }, fmt(offer.amount)),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', textDecoration: 'line-through', fontFamily: 'JetBrains Mono, monospace' } }, fmt(offer.askingPrice)),
        h('div', { style: { fontSize: 10, fontWeight: 700, color: pctOff > 0 ? 'var(--green)' : 'var(--text-muted)' } }, pctOff > 0 ? `−${pctOff}%` : '')
      ),
      offer.status === 'PENDING'
        ? (isIncoming
            ? h('div', { style: { display: 'flex', gap: 6 } },
                h('button', { className: 'buy-btn', onClick: () => handleAccept(offer.id), disabled: busy }, 'Accept'),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 11px', fontSize: 11 },
                  onClick: () => handleReject(offer.id), disabled: busy
                }, 'Reject')
              )
            : h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '7px 14px', fontSize: 12 },
                onClick: () => handleCancel(offer.id), disabled: busy
              }, 'Cancel'))
        : h('div', { className: `wallet-tx-status ${offer.status}`, style: { padding: '4px 10px', borderRadius: 5, background: 'var(--bg-elevated)', fontSize: 10 } }, offer.status)
    );
  };

  const list = tab === 'incoming' ? incoming : outgoing;
  const anyOffers = (incoming && incoming.length > 0) || (outgoing && outgoing.length > 0);
  return h(InfoModal, { title: 'Offers', onClose },
    h('div', { className: 'offer-tabs', style: { display: 'flex', alignItems: 'center', gap: 4 } },
      h('button', { className: `offer-tab ${tab === 'incoming' ? 'active' : ''}`, onClick: () => setTab('incoming') },
        'Incoming', incoming && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, incoming.filter(o => o.status === 'PENDING').length)),
      h('button', { className: `offer-tab ${tab === 'outgoing' ? 'active' : ''}`, onClick: () => setTab('outgoing') },
        'Outgoing', outgoing && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, outgoing.filter(o => o.status === 'PENDING').length)),
      h('div', { style: { flex: 1 } }),
      anyOffers && h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: '/api/profile/offers.csv',
        title: 'Download every offer you made or received as a CSV'
      }, '⇣ CSV')
    ),
    list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '💬'),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              tab === 'incoming'
                ? "No incoming offers. They'll show up here when buyers make offers on your listings."
                : 'No outgoing offers. Make an offer on any listing using the "Make Offer" button.'))
        : h('div', { className: 'offer-list' }, list.map(o => renderOffer(o, tab === 'incoming')))
  );
}

// ── Watchlist ───────────────────────────────────────────────────
export function WatchlistModal({ onClose, watchlist, allListings, onOpen, onToggleStar, onAddToCart, cartHas }) {
  // Dedupe by item id (one card per item) and compute price drop since the
  // item was first starred. We store a { itemId → price } snapshot in
  // localStorage so each card can show "−$X since you watchlisted" even
  // across sessions. Also supports a Drops Only filter.
  const [showDropsOnly, setShowDropsOnly] = useState(false);
  const [sortBy, setSortBy] = useState(() => {
    try { return localStorage.getItem('sb_watchlist_sort') || 'added'; }
    catch { return 'added'; }
  });
  const setSort = (v) => {
    setSortBy(v);
    try { localStorage.setItem('sb_watchlist_sort', v); } catch (_) {}
  };
  const [snapshots, setSnapshots] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_watchlist_snap') || '{}'); }
    catch { return {}; }
  });
  // Per-item alert targets in localStorage. Each card gets a popover where
  // the user can type a target price ("tell me when this drops below $X").
  // Triggered alerts show a distinctive badge and a toast when the modal
  // opens. Persisted across sessions, survives a reload.
  const [alerts, setAlerts] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_watchlist_alerts') || '{}'); }
    catch { return {}; }
  });
  // Server-side alerts — the new persistent-across-devices + push-
  // notified variant. Fetched once on modal open; delete updates in
  // place. Shown in a compact summary strip above the card grid so the
  // user sees how many alerts are pending + can cancel any of them.
  const [serverAlerts, setServerAlerts] = useState(null);
  const loadServerAlerts = useCallback(async () => {
    try {
      const { fetchWatchlistAlerts } = await import('./api.js');
      const data = await fetchWatchlistAlerts();
      setServerAlerts(Array.isArray(data) ? data : []);
    } catch (_) { setServerAlerts([]); }
  }, []);
  useEffect(() => { loadServerAlerts(); }, [loadServerAlerts]);
  const cancelServerAlert = async (id) => {
    const { cancelWatchlistAlert } = await import('./api.js');
    const res = await cancelWatchlistAlert(id);
    if (res && (res.error || res.code)) {
      alert(res.message || res.error || 'Could not cancel alert');
      return;
    }
    loadServerAlerts();
  };
  const [editingAlert, setEditingAlert] = useState(null);
  const [alertDraft, setAlertDraft] = useState('');
  const saveAlert = (itemId, value) => {
    const n = parseFloat(value);
    const next = { ...alerts };
    if (!isFinite(n) || n <= 0) delete next[itemId];
    else next[itemId] = n;
    setAlerts(next);
    localStorage.setItem('sb_watchlist_alerts', JSON.stringify(next));
    setEditingAlert(null);
  };

  // The parent passes in the currently-filtered marketplace view, which
  // means a watchlisted item is invisible here whenever it falls outside
  // the active category/rarity/search. We fetch an unfiltered page AND,
  // for any watched ID that still has no listing (because the market is
  // empty or the items have all sold), we fall back to /api/items/{id}
  // and show a stub row marked "No active listings". Previously this
  // modal silently showed "empty" whenever the pool was zero which made
  // the nav badge and the page disagree — a real bug.
  const [pool, setPool] = useState(() => allListings || []);
  const [fallbackItems, setFallbackItems] = useState({}); // id → item
  useEffect(() => {
    let alive = true;
    fetchListings({ limit: 500 }).then(rows => {
      if (alive && Array.isArray(rows)) setPool(rows);
    }).catch(() => {});
    return () => { alive = false; };
  }, []);

  // For every watched ID that's missing from the pool, fetch the item
  // directly so we can render a card with "No active listings" rather
  // than silently dropping it.
  useEffect(() => {
    let alive = true;
    const present = new Set((pool || []).map(l => l?.item?.id).filter(Boolean));
    const missing = watchlist.filter(id => !present.has(id) && fallbackItems[id] == null);
    if (missing.length === 0) return;
    Promise.all(missing.map(id => fetchItem(id).catch(() => null))).then(results => {
      if (!alive) return;
      const next = { ...fallbackItems };
      missing.forEach((id, i) => { if (results[i]) next[id] = results[i]; });
      setFallbackItems(next);
    });
    return () => { alive = false; };
  }, [watchlist, pool]);

  // Walk the unfiltered pool → one row per starred item (cheapest listing).
  // Any starred ID without a listing becomes a synthetic "stub" listing
  // built from the item catalogue so the card still renders.
  const starred = useMemo(() => {
    const byItem = {};
    (pool || []).forEach(l => {
      if (!watchlist.includes(l.item.id)) return;
      const cur = byItem[l.item.id];
      if (!cur || parseFloat(l.price) < parseFloat(cur.price)) byItem[l.item.id] = l;
    });
    watchlist.forEach(id => {
      if (byItem[id] != null) return;
      const item = fallbackItems[id];
      if (!item) return;
      byItem[id] = {
        id: `stub-${id}`,
        price: item.lowestPrice ?? null,
        item,
        __noListing: true
      };
    });
    return Object.values(byItem);
  }, [watchlist, pool, fallbackItems]);

  useEffect(() => {
    const next = { ...snapshots };
    let changed = false;
    starred.forEach(l => {
      if (next[l.item.id] == null) {
        next[l.item.id] = parseFloat(l.price);
        changed = true;
      }
    });
    // Drop snapshots for items that were unstarred
    Object.keys(next).forEach(k => {
      if (!watchlist.includes(parseInt(k, 10))) { delete next[k]; changed = true; }
    });
    if (changed) {
      setSnapshots(next);
      localStorage.setItem('sb_watchlist_snap', JSON.stringify(next));
    }
  }, [starred, watchlist]);

  const rows = starred.map(l => {
    const snap = snapshots[l.item.id];
    const cur = parseFloat(l.price);
    const delta = snap != null ? cur - snap : 0;
    const pct = snap && snap > 0 ? (delta / snap) * 100 : 0;
    const target = alerts[l.item.id];
    const alertHit = target != null && !l.__noListing && isFinite(cur) && cur <= target;
    return { listing: l, snap, delta, pct, target, alertHit };
  });
  const filteredBeforeSort = showDropsOnly ? rows.filter(r => r.delta < 0 || r.alertHit) : rows;
  // Apply the user-picked sort. "Added" preserves the insertion order
  // from the watchlist array (most-recently-starred lives at the tail
  // — we flip to newest-first so a star sticks to the top). "Price" /
  // "Drop" / "Alert-gap" rank by the derived metrics per row.
  const filtered = (() => {
    const order = [...filteredBeforeSort];
    if (sortBy === 'added') {
      // The parent watchlist is push-on-star so newest is last. Rank
      // rows by the index of their item id in that array, descending.
      const idx = new Map();
      watchlist.forEach((id, i) => idx.set(id, i));
      order.sort((a, b) => (idx.get(b.listing.item.id) ?? -1) - (idx.get(a.listing.item.id) ?? -1));
    } else if (sortBy === 'price_asc') {
      order.sort((a, b) => (parseFloat(a.listing.price) || Infinity) - (parseFloat(b.listing.price) || Infinity));
    } else if (sortBy === 'price_desc') {
      order.sort((a, b) => (parseFloat(b.listing.price) || 0) - (parseFloat(a.listing.price) || 0));
    } else if (sortBy === 'biggest_drop') {
      // Negative delta = drop. The most negative delta should come first.
      order.sort((a, b) => (a.delta || 0) - (b.delta || 0));
    } else if (sortBy === 'alert_gap') {
      // Smallest positive gap (listing price minus alert target) first.
      // Rows without an alert are pushed to the bottom.
      const gap = (r) => {
        if (r.target == null) return Infinity;
        const p = parseFloat(r.listing.price);
        if (!isFinite(p)) return Infinity;
        return Math.max(0, p - r.target);
      };
      order.sort((a, b) => gap(a) - gap(b));
    }
    return order;
  })();

  return h(InfoModal, { title: `Watchlist · ${starred.length} items`, onClose },
    starred.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '♡'),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'Nothing on your watchlist'),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 14px' } },
            'Click the ♡ on any item card and it will show up here with a live price-drop alert.'),
          h('a', { className: 'btn btn-accent', href: '/' }, 'Browse marketplace →')
        )
      : h('div', null,
          // Server-side alerts summary — appears above the filter row when
          // the user has any persistent alerts. ACTIVE + FIRED counts plus
          // an inline list (first 5) with per-row cancel buttons. When
          // there are none, render nothing so anonymous users and users
          // who never set an alert don't see dead chrome.
          serverAlerts && serverAlerts.length > 0 && (() => {
            const active = serverAlerts.filter(a => a.status === 'ACTIVE');
            const fired  = serverAlerts.filter(a => a.status === 'FIRED');
            return h('div', {
              style: {
                background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                borderRadius: 8, padding: '10px 14px', marginBottom: 14,
                fontSize: 12
              }
            },
              h('div', { style: { display: 'flex', gap: 10, alignItems: 'center', marginBottom: active.length > 0 ? 8 : 0 } },
                h('span', { style: { fontWeight: 700, color: 'var(--text-primary)' } },
                  '🔔 Price alerts'),
                h('span', { style: { color: 'var(--green)', fontWeight: 700 } }, `${active.length} watching`),
                fired.length > 0 && h('span', { style: { color: '#fbbf24', fontWeight: 700 } },
                  ` · ${fired.length} fired`),
                h('div', { style: { flex: 1 } }),
                fired.length > 0 && h('button', {
                  className: 'btn btn-ghost',
                  style: { padding: '3px 10px', fontSize: 11, border: '1px solid var(--border)' },
                  title: 'Delete every fired alert so only still-watching rows stay',
                  onClick: async () => {
                    const { clearFiredWatchlistAlerts } = await import('./api.js');
                    const res = await clearFiredWatchlistAlerts();
                    if (res && (res.error || res.code)) {
                      alert(res.message || res.error || 'Could not clear');
                      return;
                    }
                    loadServerAlerts();
                  }
                }, 'Clear fired')
              ),
              active.length > 0 && h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
                active.slice(0, 5).map(a => h('div', {
                  key: a.id,
                  style: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: 'var(--text-secondary)' }
                },
                  h('a', {
                    href: '/item/' + a.itemId,
                    style: { color: 'var(--accent)', textDecoration: 'none', flex: 1 }
                  }, 'Item #' + a.itemId),
                  h('span', { className: 'db-mono', style: { color: 'var(--text-primary)', fontWeight: 700 } },
                    '≤ $' + a.targetPrice),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)' },
                    onClick: () => cancelServerAlert(a.id),
                    title: 'Cancel this price alert'
                  }, '✕')
                )),
                active.length > 5 && h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 4 } },
                  `+ ${active.length - 5} more`)
              )
            );
          })(),
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' } },
            h('button', {
              className: `offer-tab ${!showDropsOnly ? 'active' : ''}`,
              onClick: () => setShowDropsOnly(false)
            }, 'All ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, rows.length)),
            h('button', {
              className: `offer-tab ${showDropsOnly ? 'active' : ''}`,
              onClick: () => setShowDropsOnly(true)
            }, 'Price drops ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, rows.filter(r => r.delta < 0).length)),
            // Sort select — defaults to "Added" (newest-first) so the
            // user sees their most recent stars on top, matching the
            // mental model of a shopping queue. Persisted to
            // localStorage so the pick survives a reload.
            h('select', {
              className: 'sort-select',
              value: sortBy,
              onChange: e => setSort(e.target.value),
              'aria-label': 'Sort watchlist',
              style: { fontSize: 12 }
            },
              h('option', { value: 'added' },        'Newest added'),
              h('option', { value: 'price_asc' },    'Price: Low → High'),
              h('option', { value: 'price_desc' },   'Price: High → Low'),
              h('option', { value: 'biggest_drop' }, 'Biggest drop'),
              h('option', { value: 'alert_gap' },    'Closest to alert')
            ),
            h('div', { style: { flex: 1 } }),
            // Add every watchlisted listing that's still actively for
            // sale to the cart in one click. Skips rows that have no
            // real listing yet (NO_LISTINGS badge) and items already
            // in the cart. Only shown when there's at least one
            // add-able row + a signed-in user (backend gates checkout).
            onAddToCart && (() => {
              const addable = rows.filter(r => !r.listing.__noListing &&
                !(cartHas && cartHas(r.listing.id)));
              if (addable.length === 0) return null;
              return h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--accent-border)', color: 'var(--accent)', padding: '6px 12px', fontSize: 11 },
                onClick: () => addable.forEach(r => onAddToCart(r.listing)),
                title: `Add ${addable.length} listing${addable.length === 1 ? '' : 's'} to your cart`
              }, '+ Add all ' + addable.length + ' to cart');
            })(),
            // Bulk clear — asks before wiping every starred item. Also
            // drops price snapshots + alert targets so a re-star doesn't
            // resurrect stale state.
            rows.length > 0 && h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 12px', fontSize: 11 },
              onClick: () => {
                if (!confirm(`Clear all ${rows.length} watchlisted items?`)) return;
                // Unstar each via the parent's handler so the app's
                // watchlist state + price-drop indicators stay consistent.
                starred.forEach(l => { if (l?.item?.id) onToggleStar(l.item.id); });
                localStorage.removeItem('sb_watchlist_snap');
                localStorage.removeItem('sb_watchlist_alerts');
                setSnapshots({});
                setAlerts({});
              }
            }, '✕ Clear all')
          ),
          filtered.length === 0
            ? h('div', { className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, '📉'),
                h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
                  'No price drops yet. We remember what each item cost when you starred it and show the diff here.'))
            : h('div', { className: 'listing-grid', style: { gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' } },
                filtered.map(r => h('div', { key: r.listing.id, style: { position: 'relative' } },
                  h(GridCard, {
                    listing: r.listing,
                    starred: true,
                    onToggleStar,
                    onClick: () => { if (!r.listing.__noListing) { onClose(); onOpen(r.listing); } }
                  }),
                  /* Stub-listing overlay: the item is watched but there's
                     nothing active on the market right now. */
                  r.listing.__noListing && h('div', {
                    className: 'watchlist-delta',
                    style: {
                      background: 'rgba(15, 20, 36, 0.85)',
                      color: 'var(--text-muted)',
                      top: 12, left: 12,
                      letterSpacing: '0.04em',
                      fontWeight: 700,
                      padding: '4px 8px',
                      border: '1px solid var(--border)'
                    }
                  }, 'NO LISTINGS'),
                  !r.listing.__noListing && r.snap != null && r.delta !== 0 && h('div', {
                    className: 'watchlist-delta ' + (r.delta < 0 ? 'drop' : 'rise'),
                    title: `Starred at ${fmt(r.snap)} · now ${fmt(r.listing.price)}`
                  },
                    r.delta < 0 ? '▼ ' : '▲ ',
                    fmt(Math.abs(r.delta)),
                    ' (', (r.pct >= 0 ? '+' : ''), r.pct.toFixed(1), '%)'
                  ),
                  r.alertHit && h('div', { className: 'watchlist-alert-hit', title: `Alert target: ${fmt(r.target)}` },
                    '🔔 Alert hit'),
                  // Alert control strip — click to set, edit, or clear a
                  // per-item price target. Persisted in sb_watchlist_alerts.
                  !r.listing.__noListing && h('div', { className: 'watchlist-alert-bar' },
                    editingAlert === r.listing.item.id
                      ? h('div', { className: 'watchlist-alert-edit' },
                          h('input', {
                            type: 'number',
                            step: '0.01',
                            min: '0',
                            placeholder: 'Target price',
                            value: alertDraft,
                            autoFocus: true,
                            onChange: (e) => setAlertDraft(e.target.value),
                            onKeyDown: (e) => {
                              if (e.key === 'Enter') saveAlert(r.listing.item.id, alertDraft);
                              if (e.key === 'Escape') setEditingAlert(null);
                            }
                          }),
                          h('button', {
                            className: 'btn btn-accent',
                            style: { padding: '4px 10px', fontSize: 11 },
                            onClick: () => saveAlert(r.listing.item.id, alertDraft)
                          }, 'Set'),
                          r.target != null && h('button', {
                            className: 'btn btn-ghost',
                            style: { padding: '4px 8px', fontSize: 11, border: '1px solid var(--border)' },
                            onClick: () => saveAlert(r.listing.item.id, '')
                          }, 'Clear')
                        )
                      : h('button', {
                          className: 'watchlist-alert-btn ' + (r.target != null ? 'set' : ''),
                          onClick: (e) => {
                            e.stopPropagation();
                            setEditingAlert(r.listing.item.id);
                            setAlertDraft(r.target != null ? String(r.target) : '');
                          }
                        }, r.target != null
                          ? `🔔 Alert ≤ ${fmt(r.target)}`
                          : '🔔 Set price alert')
                  )
                )))
        )
  );
}

// ── Wallet (deposit/withdraw/history) ───────────────────────────
export function WalletModal({ wallet, transactions, onClose, onRefresh, initialTab, prefillAmount }) {
  const [tab, setTab]       = useState(initialTab || 'deposit');
  const [amount, setAmount] = useState(prefillAmount != null ? String(prefillAmount) : '');
  // If the app redirected from the cart low-balance warning with a
  // shortfall amount, honor it exactly once. Subsequent tab switches or
  // the user typing take over. Prefill flashes a yellow glow so the
  // user sees where the number came from.
  useEffect(() => {
    if (prefillAmount != null && prefillAmount !== '') {
      setAmount(String(prefillAmount));
      setTab('deposit');
    }
  }, [prefillAmount]);
  const [dest, setDest]     = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [busy, setBusy]     = useState(false);
  const [error, setError]   = useState('');
  // History type filter. Values match the Transaction.type strings the
  // backend serializes — DEPOSIT / SALE / PURCHASE / WITHDRAW / REFUND /
  // ADJUSTMENT_CREDIT / ADJUSTMENT_DEBIT. 'ALL' = no filter.
  const [txTypeFilter, setTxTypeFilter] = useState('ALL');
  // Free-text search over description + stripeReference. Great for
  // finding "that listing I bought" or "where did this withdrawal go"
  // without scrolling through 500 rows.
  const [txSearch, setTxSearch] = useState('');

  // Fee preview for deposits and withdrawals. Matches the rates in the
  // homepage fee calculator so users see the same numbers everywhere.
  const amt = parseFloat(amount) || 0;
  const depositFee = amt * 0.028 + (amt > 0 ? 0.30 : 0);   // 2.8% + $0.30 (Stripe card)
  const depositNet = Math.max(0, amt - depositFee);
  const withdrawFee = amt * 0.015;                         // 1.5% platform
  const withdrawNet = Math.max(0, amt - withdrawFee);

  const submit = async () => {
    setError('');
    const num = parseFloat(amount);
    if (!num || num <= 0) { setError('Enter a valid amount'); return; }
    if (num > 10000) { setError('Maximum per transaction is $10,000'); return; }
    setBusy(true);
    try {
      if (tab === 'deposit') {
        const res = await depositFunds(num);
        if (res.code || res.error) { setError(res.message || res.error); return; }
        if (res.live && res.checkoutUrl) {
          window.location.href = res.checkoutUrl;
          return;
        }
        setAmount('');
        await onRefresh();
      } else {
        const res = await withdrawFunds(num, dest, totpCode);
        if (res.code || res.error) {
          // If 2FA required but missing, hint at the TOTP input instead
          // of just showing the raw message.
          if (res.code === 'TOTP_REQUIRED' || res.code === 'TOTP_INVALID') {
            setError(res.message || 'Two-factor code required');
          } else {
            setError(res.message || res.error);
          }
          return;
        }
        setAmount(''); setDest(''); setTotpCode('');
        await onRefresh();
      }
    } catch (e) {
      setError(e.message || 'Request failed');
    } finally {
      setBusy(false);
    }
  };

  const presets = tab === 'deposit' ? [25, 50, 100, 250, 500] : [25, 50, 100, 250];

  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal wallet-modal', onClick: e => e.stopPropagation() },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close wallet' }, '✕'),
      h('div', { className: 'wallet-hero' },
        h('div', { className: `wallet-mode-pill ${wallet.stripeLive ? 'live' : 'dev'}` },
          wallet.stripeLive ? '● STRIPE LIVE' : '● DEV MODE'),
        h('div', { className: 'wallet-hero-label' }, 'Wallet Balance'),
        h('div', { className: 'wallet-hero-balance' }, fmt(wallet.balance)),
        h('div', { className: 'wallet-hero-user' }, '@' + wallet.username),
        // Pending in-flight chips. Renders only when there's an actual
        // pending row so the wallet hero stays clean for users without
        // any outstanding deposits/withdrawals. Numbers come straight
        // from /api/wallet; the UI doesn't compute them itself.
        ((parseFloat(wallet.pendingWithdrawAmt) || 0) > 0 ||
         (parseFloat(wallet.pendingDepositAmt)  || 0) > 0) &&
          h('div', { className: 'wallet-pending-row' },
            (parseFloat(wallet.pendingWithdrawAmt) || 0) > 0 && h('div', { className: 'wallet-pending-chip withdraw' },
              h('span', { className: 'wallet-pending-dot' }),
              h('span', { className: 'wallet-pending-label' }, 'Withdrawal pending'),
              h('span', { className: 'wallet-pending-amt' }, '−' + fmt(wallet.pendingWithdrawAmt))
            ),
            (parseFloat(wallet.pendingDepositAmt) || 0) > 0 && h('div', { className: 'wallet-pending-chip deposit' },
              h('span', { className: 'wallet-pending-dot' }),
              h('span', { className: 'wallet-pending-label' }, 'Deposit pending'),
              h('span', { className: 'wallet-pending-amt' }, '+' + fmt(wallet.pendingDepositAmt))
            )
          )
      ),
      h('div', { className: 'wallet-tabs' },
        h('button', { className: `wallet-tab ${tab === 'deposit' ? 'active' : ''}`,  onClick: () => { setTab('deposit');  setError(''); } }, 'Deposit'),
        h('button', { className: `wallet-tab ${tab === 'withdraw' ? 'active' : ''}`, onClick: () => { setTab('withdraw'); setError(''); } }, 'Withdraw'),
        h('button', { className: `wallet-tab ${tab === 'history' ? 'active' : ''}`,  onClick: () => { setTab('history');  setError(''); } }, 'History'),
      ),
      h('div', { className: 'wallet-panel' },
        tab === 'history'
          ? (() => {
              // 7-day summary — computed over ALL transactions (not the
              // currently-filtered subset) so the card reflects a stable
              // "last week" view regardless of the filter chips.
              const weekAgo = Date.now() - 7 * 86_400_000;
              const last7 = transactions.filter(t => (t.createdAt || 0) >= weekAgo && t.status === 'COMPLETED');
              const sum = (pred) => last7.filter(pred).reduce((s, t) => s + (parseFloat(t.amount) || 0), 0);
              const inbound7  = sum(t => ['DEPOSIT','SALE','REFUND','ADJUSTMENT_CREDIT'].includes(t.type));
              const outbound7 = sum(t => ['PURCHASE','WITHDRAW','WITHDRAWAL','ADJUSTMENT_DEBIT'].includes(t.type));
              const net7 = inbound7 - outbound7;
              // Filter by type first so the CSV export button renders the
              // "N transactions" count the user actually sees in the list.
              let filtered = txTypeFilter === 'ALL'
                ? transactions
                : transactions.filter(tx => {
                    const t = (tx.type || '').toUpperCase();
                    if (txTypeFilter === 'WITHDRAWAL') return t === 'WITHDRAW' || t === 'WITHDRAWAL';
                    if (txTypeFilter === 'ADJUST') return t.startsWith('ADJUSTMENT_');
                    return t === txTypeFilter;
                  });
              const q = txSearch.trim().toLowerCase();
              if (q) {
                filtered = filtered.filter(tx =>
                  ((tx.description || '') + ' ' + (tx.stripeReference || '')).toLowerCase().includes(q));
              }
              return h('div', null,
                last7.length > 0 && h('div', { className: 'wallet-7d-summary' },
                  h('div', { className: 'wallet-7d-label' }, 'Last 7 days · ', last7.length, ' transaction', last7.length === 1 ? '' : 's'),
                  h('div', { className: 'wallet-7d-row' },
                    h('div', null,
                      h('div', { className: 'wallet-7d-subtitle' }, 'Inbound'),
                      h('div', { className: 'wallet-7d-val in' }, '+' + fmt(inbound7))
                    ),
                    h('div', null,
                      h('div', { className: 'wallet-7d-subtitle' }, 'Outbound'),
                      h('div', { className: 'wallet-7d-val out' }, '−' + fmt(outbound7))
                    ),
                    h('div', null,
                      h('div', { className: 'wallet-7d-subtitle' }, 'Net'),
                      h('div', { className: `wallet-7d-val ${net7 >= 0 ? 'in' : 'out'}` }, (net7 >= 0 ? '+' : '−') + fmt(Math.abs(net7)))
                    )
                  )
                ),
                h('div', { className: 'wallet-tx-filter-row' },
                  [
                    { id: 'ALL',        label: 'All' },
                    { id: 'DEPOSIT',    label: 'Deposits' },
                    { id: 'SALE',       label: 'Sales' },
                    { id: 'PURCHASE',   label: 'Purchases' },
                    { id: 'WITHDRAWAL', label: 'Withdrawals' },
                    { id: 'ADJUST',     label: 'Adjustments' }
                  ].map(opt => h('button', {
                    key: opt.id,
                    className: `wallet-tx-filter-chip ${txTypeFilter === opt.id ? 'active' : ''}`,
                    onClick: () => setTxTypeFilter(opt.id),
                    'aria-pressed': txTypeFilter === opt.id
                  }, opt.label)),
                  // Free-text search — walks description + stripeReference.
                  // Kept compact so it fits on one row with the chips.
                  h('input', {
                    className: 'sell-filter-search',
                    style: { flex: 1, minWidth: 140, maxWidth: 260 },
                    placeholder: 'Search description…',
                    value: txSearch,
                    onChange: e => setTxSearch(e.target.value)
                  }),
                  transactions.length > 0 && h('a', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
                    href: '/api/wallet/transactions.csv',
                    title: 'Download all transactions as a CSV file'
                  }, '⇣ Export CSV')
                ),
                h('div', { className: 'wallet-tx-list' },
                  filtered.length === 0
                    ? h('div', { className: 'wallet-tx-empty' },
                        transactions.length === 0
                          ? 'No transactions yet'
                          : q
                            ? `No transactions match "${q}"`
                            : 'No transactions match this filter')
                    : filtered.map(tx => {
                        const inbound = tx.type === 'DEPOSIT' || tx.type === 'SALE' || tx.type === 'REFUND' || tx.type === 'ADJUSTMENT_CREDIT';
                        const typeLabel = (tx.type || 'UNKNOWN').toString();
                        const prettyType = typeLabel.charAt(0) + typeLabel.slice(1).toLowerCase().replace('_', ' ');
                        const canCancel = (tx.type === 'WITHDRAW' || tx.type === 'WITHDRAWAL') && tx.status === 'PENDING';
                        return h('div', { key: tx.id, className: 'wallet-tx' },
                          h('div', { className: `wallet-tx-icon ${inbound ? 'in' : 'out'}` }, inbound ? '↓' : '↑'),
                          h('div', { className: 'wallet-tx-main' },
                            h('div', { className: 'wallet-tx-type' }, prettyType),
                            h('div', { className: 'wallet-tx-desc' }, tx.description || tx.stripeReference)
                          ),
                          h('div', { className: 'wallet-tx-right' },
                            h('div', { className: `wallet-tx-amt ${inbound ? 'in' : 'out'}` }, (inbound ? '+' : '−') + fmt(tx.amount)),
                            h('div', { className: `wallet-tx-status ${tx.status}` }, tx.status),
                            // Self-cancel for PENDING withdrawals — credits
                            // the balance back and flips the row to CANCELLED.
                            // Only visible on the actual PENDING withdrawal
                            // row so completed / failed rows stay clean.
                            canCancel && h('button', {
                              className: 'btn btn-ghost',
                              style: { marginTop: 6, padding: '4px 10px', fontSize: 10, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
                              onClick: async () => {
                                if (!confirm(`Cancel pending withdrawal for ${fmt(tx.amount)}? Your balance will be credited back.`)) return;
                                const res = await cancelPendingWithdrawal(tx.id);
                                if (res && (res.error || res.code)) { alert(res.message || res.error); return; }
                                await onRefresh();
                              }
                            }, '✕ Cancel')
                          )
                        );
                      })
                )
              );
            })()
          : h('div', null,
              h('div', { className: 'withdraw-step', style: { marginBottom: 8 } },
                h('div', { className: 'withdraw-step-num' }, '1'),
                h('div', { className: 'withdraw-step-content' },
                  h('div', { className: 'withdraw-step-title' },
                    tab === 'deposit' ? 'Enter an amount' : 'Enter withdrawal amount'),
                  h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', marginTop: 8 } },
                    h('span', { style: { fontSize: 20, fontWeight: 700, color: 'var(--text-muted)' } }, '$'),
                    h('input', {
                      className: 'wallet-amount-input',
                      style: { flex: 1, fontSize: 18, fontWeight: 700 },
                      type: 'number', min: '0', max: '10000', step: '0.01',
                      placeholder: '0.00',
                      value: amount,
                      onChange: e => setAmount(e.target.value),
                      'aria-label': tab === 'deposit' ? 'Deposit amount' : 'Withdrawal amount'
                    })
                  ),
                  h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 6 } },
                    'Or select a suggested amount'),
                  h('div', { className: 'wallet-quick' },
                    presets.map(a =>
                      h('button', { key: a, className: `wallet-quick-btn ${String(a) === amount ? 'active' : ''}`, onClick: () => setAmount(String(a)) }, '$' + a)
                    )
                  )
                )
              ),

              // Fee breakdown — appears once a non-zero amount is entered.
              amt > 0 && h('div', { className: 'wallet-fee-breakdown' },
                h('div', { className: 'wallet-fee-row' },
                  h('span', null, tab === 'deposit' ? 'Amount' : 'Withdraw'),
                  h('strong', null, fmt(amt))
                ),
                h('div', { className: 'wallet-fee-row' },
                  h('span', null, tab === 'deposit' ? 'Stripe fee (2.8% + $0.30)' : 'Platform fee (1.5%)'),
                  h('strong', { style: { color: 'var(--red)' } }, '−' + fmt(tab === 'deposit' ? depositFee : withdrawFee))
                ),
                h('div', { className: 'wallet-fee-row total' },
                  h('span', null, tab === 'deposit' ? 'You receive (credited)' : 'You receive (payout)'),
                  h('strong', { style: { color: 'var(--accent)' } }, fmt(tab === 'deposit' ? depositNet : withdrawNet))
                )
              ),

              tab === 'withdraw' && h('div', { className: 'withdraw-steps' },
                h('div', { className: 'withdraw-step' },
                  h('div', { className: 'withdraw-step-num' }, '2'),
                  h('div', { className: 'withdraw-step-content' },
                    h('div', { className: 'withdraw-step-title' }, 'Payout destination'),
                    h('input', {
                      className: 'wallet-amount-input',
                      style: { marginTop: 8 },
                      placeholder: 'Email, bank account, or Stripe Connect ID',
                      value: dest,
                      onChange: e => setDest(e.target.value)
                    }),
                    h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 6 } },
                      'Your payout will be processed within 1-2 business days.')
                  )
                ),
                h('div', { className: 'withdraw-step' },
                  h('div', { className: 'withdraw-step-num' }, '3'),
                  h('div', { className: 'withdraw-step-content' },
                    h('div', { className: 'withdraw-step-title' }, 'Security verification'),
                    h('input', {
                      className: 'wallet-amount-input',
                      type: 'text', inputMode: 'numeric', maxLength: 6,
                      placeholder: '6-digit 2FA code (if enabled)',
                      value: totpCode,
                      onChange: e => setTotpCode(e.target.value.replace(/\D/g, '')),
                      style: { letterSpacing: '4px', marginTop: 8 }
                    })
                  )
                )
              ),

              error && h('div', { className: 'wallet-error' }, error),
              h('button', {
                className: 'btn btn-accent wallet-submit',
                disabled: busy || !amt,
                onClick: submit
              }, busy ? 'Processing…' : tab === 'deposit' ? (wallet.stripeLive ? 'Continue to Stripe →' : 'Deposit (dev mode)') : `Withdraw ${amt > 0 ? fmt(withdrawNet) : ''}`),
              tab === 'withdraw' && h('div', { className: 'wallet-note' },
                h('span', null, 'You can only withdraw balance obtained through item sales. You have '),
                h('strong', { style: { color: 'var(--accent)' } }, fmt(wallet.balance)),
                h('span', null, ' in withdrawable balance.')
              ),
              tab === 'deposit' && h('div', { className: 'wallet-note' },
                wallet.stripeLive
                  ? 'You will be redirected to Stripe Checkout.'
                  : 'Dev mode: no Stripe keys configured — funds credited instantly for local testing.'
              ),
              tab === 'deposit' && h('div', { className: 'wallet-stripe-row' },
                h('a', {
                  className: 'stripe-badge',
                  href: 'https://stripe.com',
                  target: '_blank',
                  rel: 'noopener noreferrer',
                  title: 'Payments processed by Stripe'
                },
                  h('span', { className: 'stripe-badge-label' }, 'Powered by'),
                  h('span', { className: 'stripe-badge-mark' }, 'stripe')
                ),
                h('div', { className: 'wallet-stripe-info' },
                  h('div', { className: 'wallet-stripe-title' }, 'Secure card payments'),
                  h('div', { className: 'wallet-stripe-sub' }, 'Visa · Mastercard · Amex · Apple Pay · Google Pay')
                )
              )
            )
      )
    )
  );
}

// ── My Listings (legacy alias for older nav buttons) ───────────
export function MyListingsModal({ onClose, me }) {
  return h(InfoModal, { title: 'My Listings', onClose },
    me
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '📦'),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'See My Stall instead'),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
            "Use the 'My Stall' menu item to manage what you're selling."),
          h('button', { className: 'btn btn-accent', onClick: onClose }, 'Browse market →')
        )
      : h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, '🔒'),
          h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 14 } }, 'Sign in with Steam to manage your listings.'),
          h('a', { className: 'steam-btn', href: '/api/auth/steam/login' },
            h('div', { className: 'steam-btn-icon' }, '◆'),
            'Sign in through Steam'
          )
        )
  );
}

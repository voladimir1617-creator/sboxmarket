// All modal dialogs. Each modal is a narrow component with a focused prop
// surface — none of them receive the full App state.
import { h, useState, useEffect, useCallback, useMemo, useRef, fmt, timeAgo, discountPct, signInWithSteam, toast, linkifyText, highlightMatch, currencySymbol, fxConvertUsd, platformFee, sellerPayout, sellerPayoutTotal, useCustodyCopy } from './utils.js';
import { ItemImage, RarityBadge, Sparkline, SteamMarketLink, MaterialIcon, Avatar, DateRangeFilter, appendDateRange, PriceFreshnessChip } from './primitives.js';
import { GridCard } from './cards.js?v=5';
import { InfoModal, SignInNeededEmptyState } from './info-modal.js';
import { navigate } from './router.js';
import { AuctionBidPanel } from './csfloat-modals.js';
import { TradeProtectionPanel } from './trade-protection.js';
import {
  fetchInventory, fetchInventoryWithTotal, fetchMyStall, fetchMyStallWithTotal, fetchMyStallSold, fetchMyStallSoldWithTotal, fetchBestOfferPerListing, bulkAdjustStall, relistItem, cancelListing, fetchMyVerificationProgress, fetchMyStallEarnings,
  fetchIncomingOffers, fetchOutgoingOffers, acceptOffer, rejectOffer, cancelOffer, counterOffer,
  fetchOfferThread, fetchSimilar, fetchItemVelocity, reportListing, fetchReportReasons,
  depositFunds, withdrawFunds, cancelPendingWithdrawal, fetchConnectStatus, connectOnboard, updateStallListing, setAwayMode,
  fetchProfile, fetchSteamInventory, syncSteam, listFromSteam,
  fetchBuyOrders, deleteBuyOrder, fetchAutoBids, fetchActiveBids, cancelAutoBid, cancelAllAutoBids, fetchApiKeys, createApiKey, revokeApiKey, revokeAllApiKeys,
  fetchSupportTickets, fetchSupportTicket, createSupportTicket, replySupportTicket, resolveSupportTicket,
  fetchTrades, fetchTradesWithTotal, tradeAccept, tradeMarkSent, tradeConfirm, tradeDispute, tradeCancel,
  fetchTradeMessages, postTradeMessage,
  setEmail, verifyEmail, resendEmailVerification, setTradeUrl, enroll2fa, confirm2fa, cancel2fa, disable2fa,
  regenerate2faBackupCodes, fetch2faRecoveryStatus,
  fetchListings, fetchItem, fetchItemsByIds, leaveReview, fetchReviewSummary, fetchRecentSales,
  fetchReviewsForUser, fetchMyAuthoredReviews, fetchPendingReviews, deleteReview, replyToReview, fetchBuyOrderCountForItem,
  fetchBuyOrdersForItem,
  fetchWalletSpend, fetchDeliveryPolicy
} from './api.js';

export { InfoModal };

/**
 * WHAT THE BUYER ACTUALLY RECEIVES, AND WHEN — stated on the page where the
 * buying decision is made, not discovered afterwards.
 *
 * The wallet debit is instant and the site says so in several places. What
 * arrives is not. On a listing owned by another user, delivery is a human
 * being sending a Steam trade offer by hand; the money sits in escrow until
 * the buyer confirms it arrived, and if the seller never sends it the trade
 * auto-cancels and refunds. A buyer who expected an instant item and instead
 * waits on a person opens a dispute — this codebase has already paid for
 * exactly that ambiguity on the escrow path.
 *
 * Until now the ONLY surface that said any of this was the multi-item cart
 * confirm. The single-item Buy Now path — the common one — went from an item
 * page that said nothing to a dialog that said "an escrow trade opens with
 * the seller", which names a mechanism and answers neither question. The same
 * purchase made two ways gave two different pictures of what the buyer was
 * agreeing to.
 *
 * `sellerUserId == null` is the platform's own inventory: held by SkinBox,
 * delivered in-platform, nobody to wait on. Anything else is a person.
 *
 * `days` comes from the server (GET /api/listings/delivery-policy, the same
 * `trade.seller-response-days` the auto-cancel sweep enforces) and is null
 * when that lookup failed. A failed lookup renders the mechanism WITHOUT a
 * deadline — never a plausible-looking guess — and says so in
 * `data-delivery-deadline`, so "we did not ask" and "3 days" are different
 * states on the page and in the tests, not the same sentence.
 */
export function DeliveryExpectation({ sellerUserId, days, compact }) {
  // THREE states, not two. `null` is the platform's own inventory — a real
  // answer. `undefined` means we never resolved the listing row, and
  // collapsing that into the platform branch would print "Delivered
  // in-platform. There is no seller to wait on" about a purchase that may
  // well be waiting on a person. That is this codebase's whole defect family
  // in one line of copy, on the money path, so it gets its own branch.
  const unknown = sellerUserId === undefined;
  const fromSeller = !unknown && sellerUserId != null;
  if (unknown) {
    return h('div', {
      className: 'delivery-expectation',
      'data-testid': 'delivery-expectation-unknown',
      'data-delivery-deadline': 'unknown',
      style: {
        display: 'flex', gap: 8, alignItems: 'flex-start', textAlign: 'left',
        margin: compact ? '2px 0 12px' : '10px 0 4px',
        padding: '9px 12px', borderRadius: 8,
        background: 'var(--bg-elevated)', border: '1px solid var(--border)',
        fontSize: 12, lineHeight: 1.5, color: 'var(--text-muted)'
      }
    },
      h(MaterialIcon, { name: 'help', size: 16 }),
      h('span', null,
        h('strong', { style: { color: 'var(--text-primary)' } }, 'We could not confirm how this one is delivered. '),
        'Treat it as a seller listing: your wallet is charged straight away and the item may have to be sent to you by hand. ',
        'Reload the page before buying if you want the exact answer.')
    );
  }
  return h('div', {
    className: 'delivery-expectation',
    'data-testid': fromSeller ? 'delivery-expectation-seller' : 'delivery-expectation-platform',
    'data-delivery-deadline': fromSeller ? (days == null ? 'unknown' : String(days)) : 'n/a',
    style: {
      display: 'flex', gap: 8, alignItems: 'flex-start', textAlign: 'left',
      margin: compact ? '2px 0 12px' : '10px 0 4px',
      padding: '9px 12px', borderRadius: 8,
      background: 'var(--bg-elevated)', border: '1px solid var(--border)',
      fontSize: 12, lineHeight: 1.5, color: 'var(--text-muted)'
    }
  },
    h(MaterialIcon, { name: fromSeller ? 'schedule' : 'bolt', size: 16 }),
    fromSeller
      ? h('span', null,
          h('strong', { style: { color: 'var(--text-primary)' } }, 'Delivery is not instant. '),
          'Your wallet is charged straight away, but the seller has to send you a Steam trade offer by hand. ',
          'Your money stays in escrow until you confirm the item arrived',
          days == null
            // We could not read the policy. Say what always holds and stop —
            // a deadline invented here would be a promise the server has not
            // made.
            ? h('span', null, ', and if the seller never sends it the purchase cancels itself and you are refunded in full.')
            : h('span', null, ', and if they have not sent it within ',
                h('strong', { style: { color: 'var(--text-primary)' } }, days, days === 1 ? ' day' : ' days'),
                ' the purchase cancels itself and you are refunded in full.')
        )
      : h('span', null,
          h('strong', { style: { color: 'var(--text-primary)' } }, 'Delivered in-platform. '),
          'SkinBox holds this item itself, so there is no seller to wait on and no Steam trade offer to accept.')
  );
}

/**
 * One fetch of the delivery deadline per mount, shared by the item rail and
 * the Buy Now confirm so the two cannot disagree. `null` while in flight AND
 * on failure — both render the number-free sentence, which is true in either
 * case; only the deadline itself is withheld.
 */
export function useDeliveryPolicy() {
  const [days, setDays] = useState(null);
  useEffect(() => {
    let alive = true;
    fetchDeliveryPolicy()
      .then(p => { if (alive && p) setDays(p.sellerResponseDays); })
      .catch(() => { /* stays null — the copy degrades, it does not invent */ });
    return () => { alive = false; };
  }, []);
  return days;
}

// ── Item detail ──────────────────────────────────────────────────
export function ItemModal({ item, listings, history, onClose, onBuy, onMakeOffer, me, wallet, onRefresh, onCreateBuyOrder, onAddToCart, cartHas, watchlist, onToggleStar, isPageMode }) {
  // CSFloat-1:1 — the buy / offer surfaces must target the cheapest
  // BUY_NOW listing, not listings[0]. Listings come back sorted
  // price-ascending, so an auction sitting on a low current bid can
  // occupy listings[0] while a perfectly buyable BUY_NOW listing sits
  // behind it. Keying Buy Now / Make Offer / the offer form off
  // listings[0] then collapsed the whole action bar to a single
  // "Place Bid" CTA and hid the buy path entirely. `auctionOnly` is
  // true only when there is genuinely no buy-now option at all.
  const cheapestBuyNow = listings.find(l => l && l.listingType === 'BUY_NOW' && l.id) || null;
  const auctionOnly    = !cheapestBuyNow && !!(listings[0] && listings[0].listingType === 'AUCTION');
  const [offerOpen, setOfferOpen] = useState(false);
  // CSFloat-1:1 — single-listing "Buy Now" is a two-step flow on csfloat:
  // the click opens a confirm dialog showing the item + price breakdown,
  // and only the "Confirm purchase" button actually fires the purchase.
  // Our three direct buy buttons (top rail, Active Listings row, sticky
  // action bar) all used to call onBuy() straight away — too direct.
  // `buyConfirm` holds the pending { listingId, price, item } while the
  // dialog is open; null = closed. Confirm calls the SAME onBuy() the
  // direct buttons used, so nothing about what executes changes — we are
  // only inserting a review step in front of it.
  const [buyConfirm, setBuyConfirm] = useState(null);
  const [buyConfirmBusy, setBuyConfirmBusy] = useState(false);
  // The seller-response deadline, fetched once and shared by the rail's
  // delivery line and the Buy Now confirm — one number, so the page and the
  // dialog it opens cannot quote different deadlines. Null until it lands,
  // and null forever if the lookup fails; see DeliveryExpectation.
  const deliveryDays = useDeliveryPolicy();
  // Open the confirm dialog instead of buying immediately. `listingItem`
  // is the listing's own item when present (Active Listings rows carry
  // l.item); falls back to the modal's main `item` so the dialog always
  // has an image + name to render.
  const requestBuy = (listingId, price, listingItem) => {
    if (!listingId) return;
    setBuyConfirm({ listingId, price, item: listingItem || item });
  };
  const [thread, setThread] = useState(null);
  const [chartRange, setChartRange] = useState('30D');
  // Auto-clamp the active range to what the data can actually support.
  // Default '30D' is a no-op when history has 11 rows (1M / 3M / 1Y / ALL
  // all collapse to the same 11 points). Snap back to 7D so the user sees
  // a label that matches the rendered slice.
  useEffect(() => {
    // Wait for history to actually load before clamping — the parent fetches
    // it async, so on first render `history` is null/[]. Demoting to 7D
    // before data arrives would also stick on items that DO have rich
    // history (the effect would run once with len=0 and never restore).
    if (!history || history.length === 0) return;
    const len = history.length;
    if (chartRange === '30D'  && len <= 7)  setChartRange('7D');
    if (chartRange === '90D'  && len <= 30) setChartRange('7D');
    if (chartRange === '365D' && len <= 90) setChartRange('7D');
  }, [history, chartRange]);
  const [similar, setSimilar] = useState(null);
  // Batch 836 — expand/collapse the Active Listings list. Default
  // surfaces the cheapest 6 (matches the previous hard-coded slice);
  // "Show all N" swaps in every listing when a popular item has 10+
  // sellers at different prices.
  const [showAllListings, setShowAllListings] = useState(false);
  // Seller rating cache — { sellerUserId → { count, average } }. Populated
  // in parallel when the modal opens so every row in the Active Listings
  // section can show an inline reputation chip next to the seller name.
  const [sellerRatings, setSellerRatings] = useState({});
  // Bulk verified-seller flag ({userId → true}) for every seller in the
  // listings list. Powers the ✓ Verified chip next to the seller name
  // on the Active Listings row (batch 296). Missing ids = not verified.
  const [sellerVerified, setSellerVerified] = useState({});
  // Batch 710 — bulk-fetched typical ship time per seller (median over
  // 90d, null when < 3 samples). Decorates each Active Listing row.
  const [sellerShipTimes, setSellerShipTimes] = useState({});
  // Bulk-fetched Steam avatar URLs per seller. Replaces the all-caps
  // 2-letter monogram on the Active Listings row with the seller's
  // real Steam profile photo when available. Sellers without a photo
  // on file (brand-new accounts, private profiles) are absent from
  // the response and fall through to the monogram fallback.
  const [sellerAvatars, setSellerAvatars] = useState({});
  // "More from this seller" rail — pulled for the cheapest listing's
  // seller (the most likely trade target). `{sellerId, sellerName,
  // listings}` or null when: no listings, system-listed (no seller id),
  // or seller has no other active items (batch 312).
  const [otherFromSeller, setOtherFromSeller] = useState(null);
  // Pull the offer thread for the cheapest listing so buyers can see any
  // existing counter conversation before they bargain themselves. The
  // `alive` guard prevents a stale fetch from an earlier item/listing
  // resolving last and painting the wrong thread when the user navigates
  // between /item/:id pages quickly — same pattern as the velocity /
  // recent-sales / buy-order effects below.
  useEffect(() => {
    // Offer threads live on the buy-now listing offers are made against,
    // not listings[0] — which may be a cheaper auction with no offers.
    if (!cheapestBuyNow) return;
    let alive = true;
    fetchOfferThread(cheapestBuyNow.id)
      .then(t => { if (alive) setThread(t); })
      .catch(() => { if (alive) setThread(null); });
    return () => { alive = false; };
  }, [cheapestBuyNow?.id]);
  useEffect(() => {
    if (!item) return;
    let alive = true;
    fetchSimilar(item.id).then(s => { if (alive) setSimilar(s); });
    return () => { alive = false; };
  }, [item?.id]);
  // Report-listing drawer state. `reportTarget` holds the listing the user
  // clicked 🚩 on; null = drawer closed. Reasons whitelist is fetched once on
  // first-open and reused.
  const [reportTarget, setReportTarget] = useState(null);
  const [reportReasons, setReportReasons] = useState([]);
  // Image lightbox open state (clickable magnifier, /item page-mode only).
  const [zoomOpen, setZoomOpen] = useState(false);
  // Lightbox a11y: the trap/Escape/initial+restore-focus is handled by the
  // shared useDialogA11y hook (called below near the other dialog traps) so the
  // aria-modal image dialog no longer lets Tab escape to the page behind it.
  // (audit P3 — replaced a bare document Escape listener.)
  const lightboxRef = useRef(null);
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
  // array when no sales yet (freshly indexed item). `recentSalesLimit`
  // starts at the default 10 and bumps to 50 when the user clicks
  // "Show more" below the strip — one extra round-trip on demand,
  // stable median anchor when they expand.
  const [recentSales, setRecentSales] = useState([]);
  const [recentSalesLimit, setRecentSalesLimit] = useState(10);
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetchRecentSales(item.id, recentSalesLimit).then(rows => { if (alive) setRecentSales(rows || []); });
    return () => { alive = false; };
  }, [item?.id, recentSalesLimit]);
  // Reset the expansion when the modal swaps to a different item so
  // the next item doesn't inherit the previous item's expanded view.
  useEffect(() => { setRecentSalesLimit(10); }, [item?.id]);
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
  // Batch 639 — top-N active buy orders for this item (aggregate only,
  // no counterparty identity). Drives the CSFloat-style "Buy Orders"
  // table inline on the item detail modal. Lazy-expanded via a
  // toggle so the default view stays compact for items where only
  // the count + best-bid chips are interesting.
  const [buyOrdersOpen, setBuyOrdersOpen] = useState(false);
  const [buyOrderRows, setBuyOrderRows]   = useState(null);
  useEffect(() => {
    if (!item?.id || !buyOrdersOpen || buyOrderRows !== null) return;
    let alive = true;
    fetchBuyOrdersForItem(item.id, 10).then(rows => {
      if (alive) setBuyOrderRows(Array.isArray(rows) ? rows : []);
    });
    return () => { alive = false; };
  }, [item?.id, buyOrdersOpen]);
  // Viewer's own buy order on THIS item, if any. Lets the user see
  // their queue position + cancel without hopping to the Profile →
  // Buy Orders tab (batch 287). Signed-in only.
  const [myBuyOrder, setMyBuyOrder] = useState(null);
  const reloadMyBuyOrder = useCallback(async () => {
    if (!me?.id || !item?.id) { setMyBuyOrder(null); return; }
    try {
      const { fetchBuyOrders } = await import('./api.js');
      const rows = await fetchBuyOrders();
      const mine = (rows || []).find(o => o.itemId === item.id && o.status === 'ACTIVE');
      setMyBuyOrder(mine || null);
    } catch (_) { setMyBuyOrder(null); }
  }, [me?.id, item?.id]);
  useEffect(() => { reloadMyBuyOrder(); }, [reloadMyBuyOrder]);
  // Viewer's live (PENDING/COUNTERED) offer per listing (batch 368).
  // Fetched once via /api/offers/outgoing and keyed by listingId so the
  // per-listing row can render "You offered $X" without N round-trips.
  // Anon viewers skip the fetch. Refreshed on pick-change so a just-made
  // offer surfaces without a modal reload.
  const [myOffersByListing, setMyOffersByListing] = useState({});
  useEffect(() => {
    if (!me?.id) { setMyOffersByListing({}); return; }
    let alive = true;
    (async () => {
      try {
        const { fetchOutgoingOffers } = await import('./api.js');
        const rows = await fetchOutgoingOffers();
        if (!alive || !Array.isArray(rows)) return;
        const live = rows.filter(o => o.status === 'PENDING' || o.status === 'COUNTERED');
        const map = {};
        live.forEach(o => { if (o.listingId != null) map[o.listingId] = o; });
        setMyOffersByListing(map);
      } catch (_) { if (alive) setMyOffersByListing({}); }
    })();
    return () => { alive = false; };
  }, [me?.id, item?.id]);
  const cancelMyBuyOrder = async () => {
    if (!myBuyOrder) return;
    if (!window.confirm(`Cancel your buy order at ${fmt(myBuyOrder.maxPrice)}?`)) return;
    try {
      const { deleteBuyOrder } = await import('./api.js');
      const res = await deleteBuyOrder(myBuyOrder.id);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not cancel buy order', 'err');
        return;
      }
      const cancelledPrice = parseFloat(myBuyOrder.maxPrice);
      setMyBuyOrder(null);
      // Batch 910 — personalised toast. Naming the item + the max price +
      // the fact that locked wallet funds are freed gives the user the
      // three things they might want to verify after a cancel, without
      // opening /buy-orders to check.
      toast(item?.name
        ? `Buy order cancelled for "${item.name}" at ${fmt(cancelledPrice)}. Locked funds freed.`
        : `Buy order cancelled at ${fmt(cancelledPrice)}. Locked funds freed.`,
        'ok');
    } catch (_) {
      toast('Network error cancelling buy order', 'err');
    }
  };
  // Watcher count — number of users who have STARRED this item on
  // their watchlist. Pure social-proof chip; aggregate only, no
  // watcher identities exposed.
  //
  // Uses the SAME source as the marketplace-card "👁 N watching" badge
  // and the My-Stall view: the public `/api/watchlist/counts` endpoint
  // (backed by WatchlistItemRepository.countByItemIds). Previously this
  // chip called `/api/watchlist/alerts/count/item/{id}`, which counts
  // active PRICE ALERTS — a different number, so the same "N watching"
  // label showed two contradictory values across the app. Response is
  // a `{itemId: count}` map with zero-watcher items omitted.
  const [watcherCount, setWatcherCount] = useState(0);
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetch(`/api/watchlist/counts?ids=${item.id}`, { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : {})
      .then(map => {
        if (!alive) return;
        const n = (map && typeof map === 'object') ? Number(map[item.id] || 0) : 0;
        setWatcherCount(Number.isFinite(n) ? n : 0);
      })
      .catch(() => { if (alive) setWatcherCount(0); });
    return () => { alive = false; };
  }, [item?.id]);
  // Trade velocity — "N sold · 7d" activity chip in the header. Social
  // proof via realised sales (complement to the buy-order count which
  // shows demand without transactions). Payload also includes the
  // most recent SOLD listing's price + timestamp for the "last sold"
  // chip — different data point from current floor.
  const [velocity, setVelocity] = useState({ soldLast24h: 0, soldLast7d: 0, soldLast30d: 0, volumeLast30d: 0, lastSoldPrice: null, lastSoldAt: null });
  useEffect(() => {
    if (!item?.id) return;
    let alive = true;
    fetchItemVelocity(item.id).then(v => { if (alive) setVelocity(v || { soldLast24h: 0, soldLast7d: 0, soldLast30d: 0, volumeLast30d: 0, lastSoldPrice: null, lastSoldAt: null }); });
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
    // Bulk verified-seller lookup — single round-trip keyed on the same
    // sellerUserId set we just fetched ratings for. Decorates each
    // Active Listings row with a ✓ Verified chip when the seller cleared
    // the trust threshold (10+ sales, 4+ star avg or no reviews).
    (async () => {
      try {
        const { fetchVerifiedSellers } = await import('./api.js');
        const v = await fetchVerifiedSellers(ids);
        if (!alive) return;
        setSellerVerified(v || {});
      } catch (_) { /* best-effort — chip just doesn't render */ }
    })();
    // Batch 710 — bulk ship-time lookup. Decorates each row with a
    // "⚡ ships in ~Xh" chip so buyers comparing 10 listings of the
    // same item can pick a fast shipper. Sellers below the 3-sample
    // noise floor are absent from the response — the chip just
    // doesn't render for them.
    (async () => {
      try {
        const { fetchSellerShipTimes } = await import('./api.js');
        const t = await fetchSellerShipTimes(ids);
        if (!alive) return;
        setSellerShipTimes(t || {});
      } catch (_) { /* best-effort */ }
    })();
    // Steam avatar bulk lookup — replaces the monogram with the real
    // Steam profile photo on rows where the seller has one on file.
    (async () => {
      try {
        const { fetchSellerAvatars } = await import('./api.js');
        const a = await fetchSellerAvatars(ids);
        if (!alive) return;
        setSellerAvatars(a || {});
      } catch (_) { /* best-effort — monogram remains */ }
    })();
    // "More from this seller" rail — fetched for the cheapest listing's
    // seller. Multi-seller items just pick the cheapest (most likely
    // trade target); single-seller items get the obvious behaviour.
    // Skip entirely for system listings (sellerUserId null).
    const cheapest = [...listings]
      .filter(l => l.sellerUserId != null)
      .sort((a, b) => (parseFloat(a.price) || 0) - (parseFloat(b.price) || 0))[0];
    if (cheapest && item?.id) {
      (async () => {
        try {
          const { fetchOtherFromSeller } = await import('./api.js');
          const rows = await fetchOtherFromSeller(cheapest.sellerUserId, item.id, 8);
          if (!alive) return;
          setOtherFromSeller(rows.length > 0
            ? { sellerId: cheapest.sellerUserId, sellerName: cheapest.sellerName, listings: rows }
            : null);
        } catch (_) { /* quiet fail — rail just doesn't render */ }
      })();
    } else {
      setOtherFromSeller(null);
    }
    return () => { alive = false; };
  }, [listings.map(l => l.id).join(',')]);

  // Filter the history for the selected chart range (batch 640 — added
  // 3M / 1Y for CSFloat Visual Manual §15 parity). Ranges are applied
  // by row count, one row per day; `ALL` returns the full series.
  // Selecting a range longer than what's in the DB safely no-ops —
  // slice(-N) on a shorter array returns the full array.
  const slicedHistory = useMemo(() => {
    if (!history || history.length === 0) return history;
    if (chartRange === '7D')   return history.slice(-7);
    if (chartRange === '30D')  return history.slice(-30);
    if (chartRange === '90D')  return history.slice(-90);
    if (chartRange === '365D') return history.slice(-365);
    return history;
  }, [history, chartRange]);
  const [offerAmt, setOfferAmt]   = useState('');
  const [offerErr, setOfferErr]   = useState('');
  const [offerBusy, setOfferBusy] = useState(false);
  // Synchronous re-entrancy latch for the offer POST. A ref (not state) so a
  // fast double-click on "Send Offer" can't fire two POST /api/offers before
  // React commits setOfferBusy(true) and disables the button. Every other money
  // submit already has this (buyingRef / checkoutRef / submittingRef / busyRef);
  // make-offer was the lone gap.
  const offerBusyRef = useRef(false);
  // Optional buyer-supplied note alongside the offer ("brand new acct,
  // fast pay") — gives the seller context before they accept/reject. Capped
  // at 280 chars to match the V43 column width and the server-side guard.
  const [offerMsg, setOfferMsg]   = useState('');
  // Inline price-alert drawer (batch 385). Replaces the older window.prompt
  // flow — same endpoint, same cap, but renders a proper dialog with a
  // prefilled target, quick ±% adjusters, and a live preview sentence.
  const [alertOpen, setAlertOpen] = useState(false);
  const [alertTarget, setAlertTarget] = useState('');
  const [alertBusy, setAlertBusy] = useState(false);
  const [alertErr, setAlertErr]   = useState('');
  // Existing-alert awareness (batch 386). When the signed-in viewer
  // already has an ACTIVE watchlist alert on this item, the "Set Price
  // Alert" CTA flips to "Alert set at $X · Edit" so they don't think the
  // button does nothing / isn't recording. Clicking still opens the
  // drawer; submitting upserts the same alert row server-side.
  const [myAlert, setMyAlert] = useState(null);
  useEffect(() => {
    if (!me?.id || !item?.id) { setMyAlert(null); return; }
    let alive = true;
    (async () => {
      try {
        const { fetchWatchlistAlerts } = await import('./api.js');
        const rows = await fetchWatchlistAlerts();
        if (!alive || !Array.isArray(rows)) return;
        const mine = rows.find(a => a.itemId === item.id && a.status === 'ACTIVE');
        setMyAlert(mine || null);
      } catch (_) { if (alive) setMyAlert(null); }
    })();
    return () => { alive = false; };
  }, [me?.id, item?.id]);
  // The "30-day price change" stat must anchor on the point ~30 rows
  // back — NOT history[0]. With one row per day the series can carry a
  // full year of data (the 1Y chart range), so history[0] is up to 365
  // days old; reading the delta off it mislabels a 1-year move as a
  // 30-day one. Slice the last 30 rows and compare the current floor to
  // that window's first point (or the whole series when it's shorter).
  const change30dBase = (() => {
    if (!history || history.length < 2) return null;
    const window = history.slice(-30);
    const base = parseFloat(window[0]?.price);
    return Number.isFinite(base) && base > 0 ? base : null;
  })();
  // True only when there's enough history to compute a real 30-day
  // delta. Without it the pill below fabricated a "+$0.00 (0.0%)" that
  // read as a confident "no change" on brand-new, zero-history items.
  // Require a finite CURRENT floor too: a recently sold-out item can have
  // 30-day history (change30dBase) but no live lowestPrice, which made
  // change30d/changePct compute to the literal "NaN" → the pill rendered
  // "▼ $0.00 (NaN%)". No current price means no real delta, so hide the pill.
  // NOTE: must be `> 0`, not just `Number.isFinite(...)`. A sold-out / delisted
  // item comes back with lowestPrice 0.00 (finite!), which slipped past the
  // finite check and computed (0 - base)/base = a fake "-100.0%" crash pill.
  // Require a positive live floor so the pill hides (→ "no data") when nothing
  // is currently listed, matching the Listing-price "Not listed" treatment.
  const hasChange30dData = change30dBase != null && parseFloat(item.lowestPrice) > 0;
  const change30d = change30dBase != null
    ? (parseFloat(item.lowestPrice) - change30dBase).toFixed(2)
    : '0.00';
  const changePct = change30dBase != null
    ? ((change30d / change30dBase) * 100).toFixed(1)
    : '0.0';
  /* Derive trend from the LOCAL change30d so the +/-/color signal
     matches the value the user sees. Was using item.trendPercent (an API
     field that doesn't always match the displayed delta), which produced
     positive deltas rendered without a "+" prefix and in default color. */
  const change30dNum = parseFloat(change30d);
  const trendUp = change30dNum > 0, trendFlat = change30dNum === 0;
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

  // a11y: in MODAL mode (other surfaces still using ItemModal as overlay),
  // role=dialog + aria-modal + Escape-closes apply. In PAGE mode (/item/:id
  // — feedback_pages_not_popups.md), all of those are stripped: the page is
  // a destination, not a dialog. Browser back / nav anchors handle close.
  //
  // Batch 1167 — useDialogA11y mounts once for the lifetime of the
  // ItemModal (in non-page mode) so the focus trap + restore-focus
  // contract stays continuous. We route the inner-drawer Escape
  // suppression through a ref so flipping `offerOpen` / `reportTarget`
  // / `alertOpen` doesn't tear down and rebuild the trap (a rebuild
  // would prematurely restore focus to the original trigger and stomp
  // on the inner drawer's own focus management).
  const panelRef = useRef(null);
  const innerOpenRef = useRef(false);
  // Include buyConfirm so the item panel's Escape / backdrop close is
  // suppressed while the buy-confirm dialog is the topmost surface — the
  // dialog owns its own Escape via its useDialogA11y trap.
  innerOpenRef.current = !!(offerOpen || reportTarget || alertOpen || buyConfirm);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const dialogClose = useCallback(() => {
    if (innerOpenRef.current) return;
    if (typeof onCloseRef.current === 'function') onCloseRef.current();
  }, []);
  useDialogA11y(panelRef, dialogClose, !isPageMode);
  // Image lightbox a11y — trap focus + Escape + restore-focus while the
  // full-size zoom dialog is open (item page-mode only). (audit P3)
  useDialogA11y(lightboxRef, () => setZoomOpen(false), isPageMode && zoomOpen);
  // Buy-confirm dialog a11y — its own ref + close callback so Escape,
  // focus-trap and restore-focus work independently of the item panel's
  // trap. Busy-guarded so a click during the purchase POST can't tear
  // the dialog down mid-flight. Only armed while the dialog is open.
  const buyConfirmRef = useRef(null);
  const buyConfirmBusyRef = useRef(false);
  buyConfirmBusyRef.current = buyConfirmBusy;
  const closeBuyConfirm = useCallback(() => {
    // Always close — even mid-purchase (customer-readiness BLOCKER). The old
    // busyRef guard trapped the user in a non-dismissable dialog when a buy
    // request hung; the Confirm handler's own `if (busy) return` still blocks
    // a real double-submit, and the fetch timeout bounds any in-flight POST.
    setBuyConfirm(null);
  }, []);
  useDialogA11y(buyConfirmRef, closeBuyConfirm, !!buyConfirm);
  const handleBackdropClick = (e) => {
     if (isPageMode) return;
     // Don't dismiss the item panel when the buy-confirm dialog is open —
     // a click on its backdrop should close the dialog, not the page.
     if (innerOpenRef.current) return;
     if (document.querySelector('.site-root.full-page-mode')) return;
     onClose && onClose();
  };
  return h('div', { className: 'modal-backdrop' + (isPageMode ? ' page-mode' : ''), onClick: handleBackdropClick },
    h(isPageMode ? 'main' : 'div', {
      ref: isPageMode ? undefined : panelRef,
      className: 'modal' + (isPageMode ? ' item-page' : ''),
      onClick: isPageMode ? undefined : (e => e.stopPropagation()),
      ...(isPageMode ? { role: 'main', 'aria-label': item?.name ? `${item.name} — item details page` : 'Item details page' } : {
        role: 'dialog',
        'aria-modal': 'true',
        'aria-label': item?.name ? `${item.name} — item details` : 'Item details'
      })
    },
      !isPageMode && h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close item details' }, '✕'),
      h('div', { className: 'modal-header' },
        h('div', {
          className: 'modal-preview' + (zoomOpen ? ' zoom-active' : ''),
          onClick: isPageMode ? (() => setZoomOpen(true)) : undefined,
          style: isPageMode ? { cursor: 'zoom-in' } : undefined
        },
          h(ItemImage, { item, variant: 'hero' }),
          h(RarityBadge, { rarity: item.rarity }),
          /* Clickable magnifier — opens a centered lightbox at full
             item-image resolution. Available in page mode only since the
             modal-mode lightbox conflicts with the parent dialog. */
          h('button', {
            type: 'button',
            className: 'modal-preview-zoom',
            'aria-label': 'Open item image at full size',
            onClick: (e) => { e.stopPropagation(); setZoomOpen(true); }
          }, '⌕')
          /* I1/H1/S5 Boss-QA — float-value gradient bar removed.
             s&box has no float/wear/condition mechanic (CS-only).
             Leaving the red→green bar under every item viewer was
             leaking CS chrome into a non-CS marketplace. */
        ),
        h('div', null,
          /* CSFloat-1:1: full breadcrumb on /item page — "Market › Category › Item Name".
             Market crumb links to /market (the bare grid, post home/market
             split); category crumb deep-links to /market?category=...; the
             item-name terminal is plain text. */
          h('nav', {
            className: 'modal-breadcrumb',
            // a11y: <nav aria-label="Breadcrumb"> is the landmark screen
            // readers use to identify breadcrumb navigation. Without it
            // assistive tech can't distinguish breadcrumbs from any other
            // styled link group.
            'aria-label': 'Breadcrumb'
          },
            h('a', {
              className: 'modal-breadcrumb-link',
              href: '/market',
              onClick: (e) => { if (isPageMode) return; e.stopPropagation(); onClose && onClose(); },
              title: 'Browse the marketplace'
            }, 'Market'),
            h('span', { className: 'modal-breadcrumb-sep', 'aria-hidden': true }, '›'),
            h('a', {
              className: 'modal-breadcrumb-link',
              href: '/market?category=' + encodeURIComponent(item.category || ''),
              onClick: (e) => { if (isPageMode) return; e.stopPropagation(); onClose && onClose(); },
              title: `Browse every ${item.category || 'item'} listing`
            }, item.category || 'Items'),
            h('span', { className: 'modal-breadcrumb-sep', 'aria-hidden': true }, '›'),
            // The current page in a breadcrumb gets aria-current="page" so
            // screen readers announce "current page" when reading it.
            h('span', { className: 'modal-breadcrumb-cur', 'aria-current': 'page' }, item.name || 'Item')
          ),
          // Hidden eyebrow (kept for legacy CSS that targets `.modal-cat`)
          h('a', {
            className: 'modal-cat',
            href: '/market?category=' + encodeURIComponent(item.category || ''),
            style: { display: 'none' },
            onClick: (e) => { if (isPageMode) return; e.stopPropagation(); onClose && onClose(); },
            title: `Browse every ${item.category || 'item'} listing`
          }, item.category),
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' } },
            h('h1', { className: 'modal-name' }, item.name),
            /* CSFloat-1:1: rarity chip in orange italic next to title (csfloat
               shows StatTrak™ here). For s&box we use the rarity tier.
               Clickable — filters the marketplace by this rarity. */
            h('a', {
              className: 'modal-name-rarity',
              href: '/market?rarity=' + encodeURIComponent(item.rarity || 'Standard'),
              onClick: (e) => { if (isPageMode) return; e.stopPropagation(); onClose && onClose(); },
              title: `Browse all ${item.rarity || 'Standard'} items`
            }, item.rarity || 'Standard'),
            // Share button — tries the Web Share API first (opens the
            // native share sheet on mobile + Chrome desktop with every
            // installed app: Discord, Twitter, Messages, AirDrop, etc).
            // Falls back to clipboard when the API isn't available, and
            // to window.prompt when clipboard's also blocked.
            h('button', {
              className: 'btn btn-ghost',
              style: { padding: '5px 10px', fontSize: 11, border: '1px solid var(--border)', opacity: 0.75, display: 'inline-flex', alignItems: 'center', gap: 5 },
              title: 'Share this item',
              onClick: async (e) => {
                e.stopPropagation();
                const url = window.location.origin + '/item/' + item.id;
                const title = item.name + ' · SkinBox';
                // An unlisted / sold-out item comes back with lowestPrice 0
                // (not null) — don't share "listed from $0.00 on SkinBox".
                // Same 0-floor guard as the Listing-price stat above.
                const _lp = parseFloat(item.lowestPrice);
                const text  = (Number.isFinite(_lp) && _lp > 0)
                  ? `${item.name} — listed from ${fmt(item.lowestPrice)} on SkinBox`
                  : `${item.name} on SkinBox`;
                const btn = e.currentTarget;
                const flashCopied = () => {
                  /* Use innerHTML so the Material icon span survives the
                     "Copied" flash. Otherwise textContent overwrites the
                     icon and only the literal word "Copied" remains. */
                  const prev = btn.innerHTML;
                  btn.textContent = 'Copied';
                  btn.style.color = 'var(--green)';
                  setTimeout(() => { btn.innerHTML = prev; btn.style.color = ''; }, 1400);
                };
                // Native share sheet: Discord, Twitter/X, Messages,
                // AirDrop, etc. — the standard mobile experience and
                // the one Chrome desktop now supports. User-dismissed
                // sheet throws AbortError which we deliberately swallow.
                if (typeof navigator.share === 'function') {
                  try {
                    await navigator.share({ title, text, url });
                    return;
                  } catch (err) {
                    if (err && err.name === 'AbortError') return;
                    // Fall through to clipboard on other failures.
                  }
                }
                try {
                  if (navigator.clipboard?.writeText) {
                    await navigator.clipboard.writeText(url);
                    flashCopied();
                  } else {
                    window.prompt('Copy this link:', url);
                  }
                } catch (_) {
                  window.prompt('Copy this link:', url);
                }
              }
            }, h(MaterialIcon, { name: 'share', size: 12 }), 'Share')
          ),
          /* CSFloat-1:1: inline Buy Now action group at the TOP of the right
             rail — csfloat's most prominent action sits next to the price.
             Renders only when there is at least one BUY_NOW listing AND the
             viewer isn't the seller of that listing. Sticky bottom action
             bar still shows below for scroll-friendliness. */
          (() => {
            const cheap = listings.find(l => l && l.listingType === 'BUY_NOW' && l.id);
            if (!cheap) return null;
            const youOwn = me && cheap.sellerUserId === me.id;
            return h(React.Fragment, null,
              h('div', { className: 'item-rail-actions' },
                h('button', {
                  className: 'item-rail-actions-buy',
                  onClick: () => {
                    if (youOwn) return;
                    // Anon → redirect to Steam OpenID (matches the
                    // inline `.buy-btn` pattern in the Active Listings
                    // section). Pre-fix this called `onBuy()` which
                    // fires a 401 toast — the toast works, but the API
                    // round-trip was wasteful, and the toast text
                    // ("Sign in required") was less actionable than a
                    // direct redirect to the OpenID handshake.
                    if (!me) { signInWithSteam(); return; }
                    requestBuy(cheap.id, cheap.price, cheap.item);
                  },
                  disabled: !!youOwn,
                  title: youOwn ? 'You are the seller of the cheapest listing' : null
                },
                  me
                    ? h('span', null, 'Buy now · ', fmt(cheap.price))
                    : h('span', null, 'Sign in to buy · ', fmt(cheap.price))
                ),
                h('button', {
                  className: 'item-rail-actions-cart',
                  onClick: () => onAddToCart && onAddToCart(cheap),
                  disabled: cartHas && cartHas(cheap.id),
                  title: cartHas && cartHas(cheap.id) ? 'Already in your cart' : 'Add this listing to your cart'
                },
                  h(MaterialIcon, { name: 'shopping_cart', size: 14 }),
                  cartHas && cartHas(cheap.id) ? 'In Cart' : 'Cart'
                )
              ),
              /* CSFloat-1:1: Bargain row underneath Buy / Cart, blue ghost.
                 Anon → redirect to Steam OpenID (matches the action-bar
                 "Sign in to make offer" button). Signed-in → open the
                 inline offer drawer (same as the action-bar Make Offer).
                 Pre-fix this button called `onMakeOffer(cheap)` which
                 funnelled into App's `handleMakeOffer(listingId, amount,
                 message)` with `listingId=cheap-object` and `amount=undefined`
                 — the validator returned `{error: 'Missing data'}` SILENTLY.
                 Result: anon clicks did nothing, signed-in clicks did
                 nothing. Now both surfaces have a real path. */
              !youOwn && h('button', {
                className: 'item-rail-actions-bargain',
                onClick: () => {
                  if (!me) { signInWithSteam(); return; }
                  setOfferOpen(o => !o);
                }
              },
                h(MaterialIcon, { name: 'forum', size: 14 }),
                me ? (offerOpen ? 'Cancel Offer' : 'Bargain') : 'Sign in to bargain'
              ),
              // The buying decision is made HERE, next to the price — not in
              // the dialog that opens after the buyer has already decided,
              // and not after the money has moved. Keyed off the same
              // `cheap` listing the Buy button targets, so the line always
              // describes the purchase the button would make.
              h(DeliveryExpectation, {
                sellerUserId: cheap.sellerUserId,
                days: deliveryDays
              })
            );
          })(),
          h('div', { className: 'modal-stats' },
            // I2 Boss-QA: relabel "Floor Price" → "Listing price" to
            // remove the two-unlabeled-prices confusion. The cheapest
            // active listing is what the buyer pays before fees.
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Listing price'),
              // A sold-out / delisted item comes back with lowestPrice 0.00
              // (NOT null), so an ungated fmt() rendered "$0.00" — reading as
              // a free item. Mirror the search-suggest guard: only show a
              // price when there's a live listing, else an honest "Not listed".
              (parseFloat(item.lowestPrice) > 0)
                ? h('div', { className: 'modal-stat-val accent' }, fmt(item.lowestPrice))
                : h('div', { className: 'modal-stat-val', style: { color: 'var(--text-muted)' } }, 'Not listed')
            ),
            // I2 Boss-QA: "Steam Price" relabelled "Steam reference"
            // so the relationship to Listing price is obvious — it's a
            // fairness anchor, not the price you pay.
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, 'Steam reference'),
              item.steamPrice
                ? h('a', {
                    // Batch 725 — clickable "Steam Price" stat jumps to
                    // the same item's listing on the Steam Community
                    // Market. Gives buyers a one-click fairness check
                    // ("is SkinBox's price genuinely below Steam's?")
                    // and adds credibility by pointing at the anchor
                    // price. s&box appid is 590830; the market-hash-name
                    // typically matches the catalogue display name.
                    href: 'https://steamcommunity.com/market/listings/590830/' +
                          encodeURIComponent(item.name || ''),
                    target: '_blank',
                    rel: 'noopener noreferrer',
                    style: { textDecoration: 'none', color: 'inherit', display: 'block' },
                    title: `View ${item.name} on the Steam Community Market`,
                    onClick: (e) => e.stopPropagation()
                  },
                    /* Strikethrough only when our floor BEATS Steam — that's
                       the only context where the strike communicates a saving.
                       When our floor is higher than Steam, the strike read
                       as a stale/cancelled price and was confusing. */
                    h('div', {
                      className: 'modal-stat-val',
                      style: discountPct(item.lowestPrice, item.steamPrice) > 0
                        ? { textDecoration: 'line-through', color: 'var(--text-muted)' }
                        : { color: 'var(--text-muted)' }
                    }, fmt(item.steamPrice)),
                    discountPct(item.lowestPrice, item.steamPrice) > 0
                      ? h('div', { style: { fontSize: 11, fontWeight: 700, color: 'var(--green)', marginTop: 2 } },
                          `Save ${discountPct(item.lowestPrice, item.steamPrice)}%`
                        )
                      : null,
                    h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2, letterSpacing: 0.3 } }, '↗ View on Steam')
                  )
                : h('div', { className: 'modal-stat-val' }, '—')
            ),
            // I3 Boss-QA: 30D delta promoted to a proper trend pill
            // (matches the home Hottest rail style). Filled badge with
            // arrow icon + bg tint, not just colored text.
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' }, '30-day price change'),
              !hasChange30dData
                // No price history yet — show an honest "no data" marker
                // rather than a fabricated +$0.00 / 0.0% pill that reads
                // as a confident "no change".
                ? h('div', { className: 'modal-stat-val', style: { color: 'var(--ink-3)' },
                    title: 'Not enough price history yet to compute a 30-day change' }, 'No data yet')
                : h('div', { className: 'modal-stat-val', style: { display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' } },
                    h('span', {
                      className: 'modal-trend-pill ' + (trendFlat ? 'flat' : trendUp ? 'up' : 'down'),
                      title: trendFlat
                        ? 'No change over the last 30 days'
                        : (trendUp ? `Up ${changePct}% over 30 days` : `Down ${Math.abs(parseFloat(changePct) || 0)}% over 30 days`)
                    },
                      trendFlat ? '·' : (trendUp ? '▲' : '▼'),
                      ' ',
                      `${trendUp ? '+' : ''}${fmt(change30d)}`,
                      h('span', { style: { opacity: 0.85, marginLeft: 4 } },
                        `(${trendUp ? '+' : ''}${changePct}%)`)
                    )
                  )
            ),
            // I4 Boss-QA: explicit label — the unlabeled "11,652" was
            // unparseable to the boss. Data-honesty fix (fabricated-stats
            // audit): `item.supply` is the count of ACTIVE listings on
            // SkinBox (reconciled every 60s by ListingFloorRefreshService),
            // NOT a Workshop mint count — no mint-count source exists
            // (the SCMM sync that once fed one is retired). The old
            // "minted on the Steam Workshop / in circulation" wording
            // claimed data we don't have.
            h('div', { className: 'modal-stat-box' },
              h('div', { className: 'modal-stat-label' },
                'Supply',
                h('span', { className: 'modal-stat-sublabel' }, ' · listed on SkinBox')
              ),
              h('div', {
                className: 'modal-stat-val',
                title: `${Number(item.supply).toLocaleString()} active listing${Number(item.supply) === 1 ? '' : 's'} of ${item.name || 'this item'} on SkinBox right now`
              },
                Number(item.supply).toLocaleString(),
                h('span', { className: 'modal-stat-unit' }, Number(item.supply) === 1 ? ' item' : ' items')
              )
            )
          ),
          // View count chip (batch 409). Lifetime GET /api/items/{id}
          // hits — a low-cost liquidity + interest signal that
          // complements the buy-order / watcher chips. Threshold at
          // 10 so a fresh item doesn't render a noisy "1 view" chip
          // (and so the viewer's own first-open doesn't look weird).
          // CSFloat parity — "N active listings" chip. Pulled from the
          // already-loaded `listings` array so no extra round-trip. Visible
          // even when 0 (so a buyer who lands on a freshly sold-out item
          // sees explicit "0 listings" instead of guessing). Distinct
          // counters next to it on CSFloat: total listings + unique
          // sellers — we render both.
          listings && h('button', {
            className: 'modal-demand-chip',
            style: { cursor: 'pointer', border: 'inherit', background: 'inherit', color: 'inherit', font: 'inherit' },
            title: `${listings.length} listing${listings.length === 1 ? '' : 's'} from ${new Set(listings.map(l => l.seller?.id ?? l.sellerId)).size} seller${new Set(listings.map(l => l.seller?.id ?? l.sellerId)).size === 1 ? '' : 's'} — click to scroll to the active listings section`,
            onClick: () => {
              const section = document.querySelector('.active-listings-anchor, .listings-section');
              if (section) section.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
          },
            h('span', { className: 'modal-demand-chip-num' }, listings.length),
            ' listing' + (listings.length === 1 ? '' : 's')
          ),
          item && Number(item.viewCount) >= 10 && h('div', {
            className: 'modal-demand-chip',
            title: `${Number(item.viewCount).toLocaleString()} lifetime item-detail opens`
          },
            '—',
            h('span', { className: 'modal-demand-chip-num' }, Number(item.viewCount).toLocaleString()),
            ' views'
          ),
          // Batch 585 — "N sold" social-proof chip. Silent below 3
          // total sales so fresh items don't look empty; green accent
          // to distinguish from the blue view chip. Lifetime count,
          // not recent — the recent-sales strip below already surfaces
          // the velocity angle.
          item && Number(item.totalSold) >= 3 && h('div', {
            className: 'modal-demand-chip',
            title: `${Number(item.totalSold).toLocaleString()} lifetime sales on SkinBox`
          },
            '✓ ',
            h('span', { className: 'modal-demand-chip-num' }, Number(item.totalSold).toLocaleString()),
            ' sold'
          ),
          // Demand chip — number of standing buy orders pinned to this
          // item. Silent when 0 so new items don't look empty. Batch 639:
          // now a toggle that expands an inline CSFloat-style Buy Orders
          // table below the chip row (lazy-loaded the first time it opens).
          buyOrderCount > 0 && h('button', {
            className: 'modal-demand-chip',
            onClick: () => setBuyOrdersOpen(v => !v),
            style: { cursor: 'pointer', border: 'inherit', background: 'inherit', color: 'inherit' },
            title: buyOrdersOpen ? 'Hide the buy orders table' : 'Show the standing buy orders for this item'
          },
            h('span', { className: 'modal-demand-chip-num' }, buyOrderCount),
            ' buyer', buyOrderCount === 1 ? '' : 's',
            ' want', buyOrderCount === 1 ? 's' : '', ' this right now',
            h('span', { style: { marginLeft: 6, fontSize: 10, opacity: 0.7 } }, buyOrdersOpen ? '▲' : '▼')
          ),
          // Watcher chip — passive-demand signal complementing the
          // buyer-demand chip. "N watching" tells you who's waiting for
          // a price drop; the buy-order chip tells you who's committed
          // to paying up to $X right now. Different signals, both
          // aggregate-only. Silent when nobody is watching so new
          // items don't look empty.
          watcherCount > 0 && h('div', { className: 'modal-demand-chip' },
            h('span', { className: 'modal-demand-chip-num' }, watcherCount),
            ' watching'
          ),
          // Best-bid chip — top-of-book from the buy-order side. Pairs
          // with the demand count: "N want this" tells you whether
          // there's pressure, "Best bid $X" tells you the cheapest way
          // to auto-match. Silent when no standing bids.
          bestBid != null && bestBid > 0 &&
            h('div', { className: 'modal-demand-chip' },
              'Best bid · ',
              h('span', { className: 'modal-demand-chip-num' }, fmt(bestBid))
            ),
          // Viewer's own buy order on this item (batch 287). Lets the
          // user see + cancel without hopping to the Buy Orders tab.
          // Queue position chip when the order is in a competitive
          // queue (#1 = top, omitted when null which means basket-
          // style orders pinned to category/rarity rather than item).
          myBuyOrder && h('div', {
            className: 'modal-demand-chip',
            style: { display: 'flex', alignItems: 'center', gap: 6 }
          },
            'Your buy order · ',
            h('span', { className: 'modal-demand-chip-num' }, fmt(myBuyOrder.maxPrice)),
            myBuyOrder.queuePosition != null && h('span', {
              style: { fontSize: 10, color: 'var(--text-muted)', marginLeft: 4 }
            }, '#' + myBuyOrder.queuePosition + ' in queue'),
            h('button', {
              className: 'btn btn-ghost',
              style: {
                marginLeft: 6, padding: '0 8px', fontSize: 10, fontWeight: 700,
                color: 'var(--red)', border: '1px solid rgba(248,113,113,0.35)',
                background: 'transparent', cursor: 'pointer'
              },
              onClick: cancelMyBuyOrder,
              title: 'Cancel this buy order'
            }, '✕ Cancel')
          ),
          // Velocity chip — social proof via realised sales, complement
          // to the demand chip. Batch 874 — prefer the 24h count when
          // there's fresh activity ("N sold today" is a much stronger
          // signal than the week-average). Falls through to 7d → 30d
          // for quieter items.
          (velocity.soldLast24h > 0 || velocity.soldLast7d > 0 || velocity.soldLast30d > 0) &&
            h('div', { className: 'modal-demand-chip', style: { background: 'rgba(34,197,94,0.15)', borderColor: 'rgba(34,197,94,0.4)', color: '#22c55e' } },
              h('span', { className: 'modal-demand-chip-num' },
                velocity.soldLast24h > 0 ? velocity.soldLast24h
                  : velocity.soldLast7d > 0 ? velocity.soldLast7d
                  : velocity.soldLast30d),
              velocity.soldLast24h > 0 ? ' sold today'
                : velocity.soldLast7d > 0 ? ' sold this week'
                : ' sold this month'
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
            h('div', { className: 'modal-demand-chip' },
              '30D range · ',
              h('span', { className: 'modal-demand-chip-num' },
                `${fmt(priceExtremes.low30d)} – ${fmt(priceExtremes.high30d)}`)
            ),
          // Price-check chip — collapses the 30D range comparison into a
          // single red/green/amber verdict so a buyer doesn't have to do
          // the "is $35 closer to $30 or $50?" math in their head. Mirrors
          // CSFloat's "Good deal · priced below market" tag. Four buckets:
          //  - floor < 30D low      → "Below 30D low" (emerald)
          //  - floor ≤ low + 25%    → "Good deal"     (green)
          //  - floor ≥ low + 75%    → "High price"    (red)
          //  - else                 → "Fair price"    (neutral, hidden)
          //                            // when range is too narrow (<$0.02)
          //                            // since percentiles are meaningless.
          priceExtremes && parseFloat(item.lowestPrice) > 0 && (priceExtremes.high30d - priceExtremes.low30d) >= 0.02 && (() => {
            const floor = parseFloat(item.lowestPrice);
            const { low30d, high30d } = priceExtremes;
            const span = high30d - low30d;
            if (floor < low30d) {
              return h('div', { className: 'modal-demand-chip signal-up', title: `Current floor ${fmt(floor)} is below the 30-day low of ${fmt(low30d)}.` },
                'Below 30D low');
            }
            const pct = (floor - low30d) / span;
            if (pct <= 0.25) {
              return h('div', { className: 'modal-demand-chip signal-up', title: `Current floor is in the bottom ${Math.round(pct * 100)}% of the 30-day range.` },
                'Good deal');
            }
            if (pct >= 0.75) {
              return h('div', { className: 'modal-demand-chip signal-down', title: `Current floor is in the top ${Math.round((1 - pct) * 100)}% of the 30-day range — consider waiting.` },
                'High price');
            }
            return null;
          })(),
          // All-time low chip — standard buyer reference point: "is this
          // below the cheapest it's ever been?" Green to match the rest
          // of the "this is a deal" signals (below-30D-low + good-deal).
          priceExtremes && priceExtremes.allTimeLow < priceExtremes.low30d &&
            h('div', { className: 'modal-demand-chip signal-up' },
              'All-time low · ',
              h('span', { className: 'modal-demand-chip-num' }, fmt(priceExtremes.allTimeLow))
            )
        )
      ),

      // Batch 639 — expandable Buy Orders table (CSFloat Visual Manual §15
      // "Item Detail Modal" parity). Shows the top 10 ACTIVE buy orders
      // for this item sorted `maxPrice DESC, createdAt ASC` so the top
      // row is literally the next to fill. Counterparty identity is
      // NOT surfaced — same policy as every other aggregate buy-order
      // endpoint. Lazy-loaded: only hits the API when the user
      // actually expands the toggle.
      buyOrdersOpen && h('div', {
        style: {
          margin: '0 0 16px', padding: '12px 14px',
          background: 'var(--bg-elevated)', border: '1px solid var(--border)',
          borderRadius: 10, fontSize: 12
        }
      },
        h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 } },
          h('div', { style: { fontWeight: 700, color: 'var(--text-primary)', fontSize: 13 } }, 'Active buy orders'),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Top-of-book first · counterparties anonymous')
        ),
        buyOrderRows === null
          ? h('div', { style: { padding: 8, color: 'var(--text-muted)', fontStyle: 'italic' } }, 'Loading…')
          : buyOrderRows.length === 0
            ? h('div', { style: { padding: 8, color: 'var(--text-muted)' } }, 'No active buy orders for this item right now.')
            : h('table', { style: { width: '100%', borderCollapse: 'collapse', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } },
                h('thead', null,
                  h('tr', { style: { color: 'var(--text-muted)', fontSize: 10, letterSpacing: 0.4 } },
                    h('th', { style: { textAlign: 'left',  padding: '6px 4px', fontWeight: 700 } }, '#'),
                    h('th', { style: { textAlign: 'right', padding: '6px 4px', fontWeight: 700 } }, 'Max price'),
                    h('th', { style: { textAlign: 'right', padding: '6px 4px', fontWeight: 700 } }, 'Qty'),
                    h('th', { style: { textAlign: 'right', padding: '6px 4px', fontWeight: 700 } }, 'Placed')
                  )
                ),
                h('tbody', null,
                  buyOrderRows.map((r, i) => h('tr', {
                    key: r.id,
                    style: {
                      borderTop: '1px solid var(--border)',
                      color: i === 0 ? 'var(--accent)' : 'var(--text-secondary)'
                    }
                  },
                    h('td', { style: { padding: '7px 4px', fontWeight: i === 0 ? 800 : 600 } }, i + 1),
                    h('td', { style: { padding: '7px 4px', textAlign: 'right', fontWeight: i === 0 ? 800 : 600 } }, fmt(r.maxPrice)),
                    h('td', { style: { padding: '7px 4px', textAlign: 'right' } }, r.quantity || 1),
                    h('td', { style: { padding: '7px 4px', textAlign: 'right', fontSize: 10, color: 'var(--text-muted)' } }, timeAgo(r.createdAt))
                  ))
                )
              ),
        me && !myBuyOrder && h('button', {
          className: 'btn btn-ghost',
          style: { marginTop: 10, width: '100%', border: '1px dashed var(--accent-border)', padding: '8px 12px', fontSize: 11, color: 'var(--accent)' },
          onClick: () => onCreateBuyOrder && onCreateBuyOrder(item),
          title: 'Place your own buy order for this item'
        }, '+ Place a buy order')
      ),

      h('div', { className: 'modal-body' },
        // Trade-URL nag — signed-in viewers with no Steam trade URL set can't
        // buy / bid / offer on a P2P listing (server rejects with
        // TRADE_URL_MISSING). Surface the blocker upfront so the user fixes
        // Profile instead of pressing Buy and getting a red error. Only
        // renders when there's actually a P2P listing to act on (system
        // listings don't need a trade URL since no Steam handoff happens).
        me && !(me.tradeUrl && String(me.tradeUrl).trim()) &&
            listings.some(l => l && l.sellerUserId != null) && h('div', {
          style: {
            margin: '0 0 14px', padding: '10px 14px',
            background: 'rgba(250,204,21,0.08)',
            border: '1px solid rgba(250,204,21,0.35)',
            borderRadius: 8, color: '#fde68a',
            fontSize: 13, lineHeight: 1.5,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            gap: 12, flexWrap: 'wrap'
          }
        },
          h('span', null,
            h('strong', null, 'Steam trade URL needed'),
            ' — sellers can\'t send you the item without it. Add yours before buying or bidding.'),
          h('a', {
            href: '/profile',
            className: 'btn btn-accent',
            style: { padding: '6px 14px', fontSize: 12, textDecoration: 'none' },
            onClick: (e) => { e.stopPropagation(); onClose && onClose(); }
          }, 'Open Profile')
        ),
        (() => {
          // Render the bid panel whenever ANY auction listing exists for this
          // item — NOT just when listings[0] is an auction. Listings come back
          // price-ascending, so a cheaper BUY_NOW would otherwise occupy
          // listings[0] and hide a perfectly biddable auction (un-biddable
          // dead-end on the most common mixed-listing case).
          const auctionListing = (listings || []).find(l => l.listingType === 'AUCTION');
          return auctionListing && h('div', { 'data-auction-panel': 'true' },
            h(AuctionBidPanel, { listing: auctionListing, me, wallet, onPlaced: onRefresh })
          );
        })(),
        h('h2', { className: 'modal-section-title' },
          h('div', { className: 'section-title-dot' }),
          'Price History',
          // Batch 640 — CSFloat Visual Manual §15 parity time ranges
          // (1M / 3M / 1Y / ALL). Kept 7D too because for items with a
          // sparse history it's the only range that shows movement.
          // "90D" / "365D" beat calendar-accurate 3M/1Y math — the
          // sparkline is a trend indicator, not an accountant.
          h('div', { className: 'chart-range' },
            // Disable ranges that don't add information — if history has 11 rows,
            // 1M/3M/1Y/ALL all return the same 11 points and clicking them
            // produces no visible change. CSFloat parity: only enable ranges
            // whose window is shorter than the available history (so they'd
            // actually clamp the series to fewer points).
            [
              { id: '7D',   label: '7D',  needs: 0   },
              { id: '30D',  label: '1M',  needs: 7   },
              { id: '90D',  label: '3M',  needs: 30  },
              { id: '365D', label: '1Y',  needs: 90  },
              { id: 'ALL',  label: 'ALL', needs: 0   }
            ].map(r => {
              const histLen = (history && history.length) || 0;
              const hasHistory = !!history && histLen > 0;
              const insufficient = hasHistory && r.needs > 0 && histLen <= r.needs;
              return h('button', {
                key: r.id,
                className: `chart-range-btn ${chartRange === r.id ? 'active' : ''}${insufficient ? ' is-disabled' : ''}`,
                onClick: () => { if (!insufficient) setChartRange(r.id); },
                disabled: insufficient,
                title: insufficient
                  ? `Need at least ${r.needs + 1} days of price history to compare a ${r.label} window — try 7D.`
                  : null,
                'aria-label': insufficient
                  ? `${r.label} (insufficient history)`
                  : `Show ${r.label} price history`
              }, r.label);
            })
          )
        ),
        h('div', { className: 'chart-wrap' },
          slicedHistory && slicedHistory.length >= 2
            ? h(Sparkline, { data: slicedHistory, color: 'var(--cta)', height: 150, showAxes: true })
            : h('div', { className: 'chart-empty', style: { height: 150, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)', fontSize: 13, border: '1px dashed var(--border)', borderRadius: 10 } },
                'Price history will appear here after the next market sync.')
        ),

        // Recent sales strip — buyers anchor fairness on the actual sale
        // ladder (floor price alone tells them what sellers ASK for, not
        // what the market PAID). Counterparties aren't surfaced — buyer
        // privacy is non-negotiable.
        recentSales && recentSales.length > 0 && h('div', null,
          h('h2', { className: 'modal-section-title' },
            h('div', { className: 'section-title-dot' }),
            `Recent sales (${recentSales.length})`
          ),
          // Median summary chip — buyers want an at-a-glance "what is this
          // item actually clearing at?" number without squinting at the row
          // list. Median not mean so one outlier (auction spike, typo'd
          // 0.01 sale) doesn't drag the signal. Only shown with 3+ sales
          // so a noisy single data point doesn't read as an anchor.
          (() => {
            const prices = recentSales
              .map(s => parseFloat(s.price))
              .filter(n => Number.isFinite(n) && n > 0)
              .sort((a, b) => a - b);
            if (prices.length < 3) return null;
            const mid = Math.floor(prices.length / 2);
            const median = prices.length % 2 === 0
              ? (prices[mid - 1] + prices[mid]) / 2
              : prices[mid];
            const lo = prices[0];
            const hi = prices[prices.length - 1];
            const floor = parseFloat(item?.lowestPrice || 0);
            let deltaChip = null;
            if (floor > 0 && median > 0) {
              const pct = Math.round(((floor - median) / median) * 100);
              if (pct <= -5) {
                deltaChip = h('span', {
                  style: { color: 'var(--green)', fontWeight: 700 },
                  title: `Current floor (${fmt(floor)}) is ${Math.abs(pct)}% below the median clearing price — likely a deal.`
                }, ` · floor ${pct}% vs median`);
              } else if (pct >= 5) {
                deltaChip = h('span', {
                  style: { color: 'var(--red)', fontWeight: 700 },
                  title: `Current floor (${fmt(floor)}) is ${pct}% above the median clearing price — you may want to make an offer or wait.`
                }, ` · floor +${pct}% vs median`);
              }
            }
            return h('div', {
              style: {
                marginTop: 2, marginBottom: 10, fontSize: 11,
                color: 'var(--text-secondary)', lineHeight: 1.5
              }
            },
              'Median ', h('strong', { style: { color: 'var(--text-primary)' } }, fmt(median)),
              ' · range ', fmt(lo), '–', fmt(hi),
              deltaChip
            );
          })(),
          h('div', {
            className: 'recent-sales-list',
            // When expanded to 50 rows, cap the visible height so the
            // modal doesn't balloon vertically. ~10 visible rows before
            // the internal scrollbar kicks in. Default 10-row view stays
            // unconstrained so the CSS grid renders naturally.
            style: recentSalesLimit > 10 ? { maxHeight: 360, overflowY: 'auto' } : undefined
          },
            recentSales.map(s => h('div', { key: s.listingId, className: 'recent-sales-row' },
              h('span', { className: 'recent-sales-type' },
                s.listingType === 'AUCTION' ? 'Auction' : 'Buy now'),
              h('span', { className: 'recent-sales-price' }, fmt(s.price)),
              h('span', { className: 'recent-sales-time' }, timeAgo(s.soldAt))
            ))
          ),
          // "Show more" expander — bumps the fetch to limit=50 when the
          // viewer wants a deeper sample. Only appears when the current
          // payload is at the 10-row default AND already full (fewer
          // than 10 sales means there's nothing more to show). Once
          // expanded, replaced with a row count so the user knows the
          // view is at the server cap.
          recentSalesLimit === 10 && recentSales.length >= 10 && h('button', {
            className: 'btn btn-ghost',
            style: {
              width: '100%', marginTop: 6, padding: '6px 12px', fontSize: 11,
              border: '1px dashed var(--border)', color: 'var(--text-secondary)'
            },
            onClick: () => setRecentSalesLimit(50),
            title: 'Load up to 50 of the most-recent sales for a deeper price anchor'
          }, '↓ Show more sales'),
          recentSalesLimit > 10 && h('div', {
            style: { fontSize: 10, marginTop: 6, color: 'var(--text-muted)', textAlign: 'center' }
          }, `Showing ${recentSales.length} most-recent sales (server cap: 50)`)
        ),

        thread && thread.length > 0 && h('div', null,
          h('h2', { className: 'modal-section-title' }, h('div', { className: 'section-title-dot' }), `Offer thread (${thread.length})`),
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
              // Buyer/seller note attached to this thread entry (V43 / V45).
              // Italic + truncated so the bubble stays tight. Title attribute
              // carries the full text for hover reveal on long messages.
              o.message && h('div', {
                className: 'item-offer-note',
                style: {
                  fontSize: 11, fontStyle: 'italic',
                  color: 'var(--text-secondary)', marginTop: 3,
                  overflow: 'hidden', textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap', maxWidth: 180
                },
                title: o.message
              }, '“', o.message, '”'),
              o.status === 'REJECTED' && o.sellerReply && h('div', {
                style: {
                  fontSize: 10, color: 'var(--red)', marginTop: 2,
                  overflow: 'hidden', textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap', maxWidth: 180
                },
                title: o.sellerReply
              }, '↩ “', o.sellerReply, '”'),
              h('div', { className: 'item-offer-status' }, o.status)
            ))
          )
        ),
        h('h2', { className: 'modal-section-title active-listings-anchor', id: 'active-listings' }, h('div', { className: 'section-title-dot' }), `Active Listings (${listings.length})`),
        h('div', { className: 'modal-listings' },
          listings.length === 0
            ? h('div', { style: { padding: '14px 16px', background: 'var(--bg-1, var(--bg-elevated))', border: '1px solid var(--line, var(--border))', borderRadius: 'var(--r-md, 8px)', display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' } },
                // Batch 1068 — 📭 mailbox emoji + ⚡ bolt stripped for
                // editorial parity. Empty-listings block is all type now.
                // Boss QA cycle 11 — added a small page-topic SVG (an empty
                // price-tag stack) before the copy. Cycle 11 prompt: every
                // empty-state should have a custom branded illustration so
                // the surface feels $10M instead of like a flat error chip.
                h('div', {
                  style: {
                    width: 42, height: 42, borderRadius: 10, flexShrink: 0,
                    background: 'color-mix(in oklab, var(--accent) 8%, var(--bg-1))',
                    border: '1px solid color-mix(in oklab, var(--accent) 18%, var(--line))',
                    color: 'color-mix(in oklab, var(--accent) 85%, var(--ink-2))',
                    display: 'grid', placeItems: 'center'
                  },
                  'aria-hidden': true
                },
                  h('svg', { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' },
                    h('path', { d: 'M3 9l8 8a2 2 0 0 0 2.8 0L21 10V4h-6L3 9z' }),
                    h('circle', { cx: 16.5, cy: 7.5, r: 1.2, fill: 'currentColor', stroke: 'none' })
                  )
                ),
                h('div', { style: { flex: 1, minWidth: 200 } },
                  h('div', { style: { fontFamily: "'Geist', 'Inter', system-ui, sans-serif", fontSize: 13, fontWeight: 600, letterSpacing: '-0.005em', color: 'var(--ink, var(--text-primary))', marginBottom: 4 } },
                    'No active listings'),
                  // I6 Boss-QA: copy tweaked to point users at Database
                  // (past sales) as the next-best surface when nothing
                  // is currently for sale. Mirrors CSFloat parity.
                  h('div', { style: { fontSize: 12, color: 'var(--ink-3, var(--text-muted))', lineHeight: 1.55 } },
                    me
                      ? h(React.Fragment, null,
                          'Set a buy order at your max price and let the system auto-match — or browse ',
                          /* Boss QA cycle 12 — inline link inside paragraph copy needs an
   underline (or other non-color affordance) per WCAG 1.4.1: links
   distinguishable without color. axe-link-in-text-block flagged
   the bare-color variant. */
                          h('a', { href: '/db?q=' + encodeURIComponent(item?.name || ''), style: { color: 'var(--accent)', textDecoration: 'underline', textUnderlineOffset: '2px' } }, 'Database'),
                          ' for past sales.')
                      : h(React.Fragment, null,
                          'Sign in to set a restock alert or place a standing buy order — or browse ',
                          /* Boss QA cycle 12 — inline link inside paragraph copy needs an
   underline (or other non-color affordance) per WCAG 1.4.1: links
   distinguishable without color. axe-link-in-text-block flagged
   the bare-color variant. */
                          h('a', { href: '/db?q=' + encodeURIComponent(item?.name || ''), style: { color: 'var(--accent)', textDecoration: 'underline', textUnderlineOffset: '2px' } }, 'Database'),
                          ' for past sales.'))
                ),
                me
                  ? h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
                      onCreateBuyOrder && h('button', {
                        className: 'btn btn-accent',
                        style: { padding: '6px 14px', fontSize: 12 },
                        onClick: () => onCreateBuyOrder(item)
                      }, 'Place Buy Order')
                    )
                  : h('button', {
                      className: 'btn btn-accent',
                      style: { padding: '6px 14px', fontSize: 12 },
                      onClick: () => signInWithSteam()
                    }, 'Sign in with Steam')
              )
            : (showAllListings ? listings : listings.slice(0, 6)).map(l => {
                // Prefer the inline sellerRating attached by ListingController
                // (single GROUP BY for all sellers in the request) over the
                // legacy per-seller fetchReviewSummary loop. Fall back to the
                // map for backward compat — both routes resolve to the same
                // {count, average} shape downstream.
                const rating = (l.sellerRating != null && l.sellerReviewCount != null)
                  ? { average: l.sellerRating, count: l.sellerReviewCount }
                  : (l.sellerUserId ? sellerRatings[l.sellerUserId] : null);
                const isMine = me && l.sellerUserId === me.id;
                return h('div', {
                    key: l.id,
                    className: 'modal-listing-row',
                    style: isMine
                      ? { background: 'rgba(30,165,255,0.06)', borderLeft: '3px solid var(--accent)' }
                      : null,
                    title: isMine ? 'This is your listing' : null
                  },
                  (() => {
                    // Prefer the live Steam avatar (bulk-fetched above).
                    // Falls back to the stored monogram — which is either
                    // a legit 2-letter initial or (historical listings) an
                    // uppercased URL; `.toUpperCase()` on a monogram is a
                    // no-op, on a URL the letters are unreadable but the
                    // legacy path is unchanged for sellers without avatars.
                    const url = l.sellerUserId && sellerAvatars[l.sellerUserId];
                    if (url) {
                      return h('div', {
                        className: 'modal-seller-av',
                        style: { overflow: 'hidden', padding: 0, background: 'var(--bg-2, var(--bg-elevated))' }
                      },
                        h('img', {
                          src: url,
                          alt: '',
                          loading: 'lazy',
                          decoding: 'async',
                          referrerPolicy: 'no-referrer',
                          style: { width: '100%', height: '100%', objectFit: 'cover', display: 'block' },
                          onError: (e) => { e.currentTarget.parentNode.textContent = (l.sellerName?.substring(0,2) || 'US').toUpperCase(); }
                        })
                      );
                    }
                    return h('div', { className: 'modal-seller-av' },
                      (l.sellerAvatar || l.sellerName?.substring(0,2) || 'US').toUpperCase());
                  })(),
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
                    // Verified-seller chip — 10+ completed sales AND
                    // (no ratings OR avg >= 4). Computed server-side
                    // (/api/sellers/verified bulk endpoint) so the
                    // threshold stays consistent with the stall hero.
                    l.sellerUserId && sellerVerified[l.sellerUserId] && h('span', {
                      style: {
                        marginLeft: 6, fontSize: 10, fontWeight: 700,
                        color: 'var(--green)',
                        background: 'rgba(34,197,94,0.12)',
                        border: '1px solid rgba(34,197,94,0.35)',
                        padding: '1px 6px', borderRadius: 10,
                        letterSpacing: 0.3, whiteSpace: 'nowrap'
                      },
                      title: 'Verified seller — 10+ completed sales at 4+ star avg'
                    }, 'Verified'),
                    rating && rating.count > 0 && h('span', { className: 'modal-seller-rating', title: `${rating.count} review${rating.count === 1 ? '' : 's'}` },
                      '★ ', rating.average.toFixed(1),
                      h('span', { className: 'modal-seller-rating-count' }, ` (${rating.count})`)
                    ),
                    // Batch 710 — typical ship-time chip. Renders only
                    // when the seller has ≥ 3 samples in the last 90 days
                    // (server-side noise floor). Amber when >24h, yellow
                    // when >6h, green otherwise — lets buyers comparing
                    // 10 listings of the same item pick a fast shipper.
                    (() => {
                      const ms = l.sellerUserId ? sellerShipTimes[l.sellerUserId] : null;
                      if (!ms || ms <= 0) return null;
                      const hours = ms / 3600000;
                      let label, color;
                      if (hours < 1)      { label = Math.max(1, Math.round(ms / 60000)) + 'm'; color = 'var(--green)'; }
                      else if (hours < 6) { label = Math.round(hours) + 'h'; color = 'var(--green)'; }
                      else if (hours < 24){ label = Math.round(hours) + 'h'; color = '#fbbf24'; }
                      else                { label = Math.round(hours / 24) + 'd'; color = 'var(--text-muted)'; }
                      return h('span', {
                        style: { marginLeft: 6, fontSize: 10, fontWeight: 700, color,
                                 padding: '1px 6px', borderRadius: 10, whiteSpace: 'nowrap',
                                 border: '1px solid currentColor', opacity: 0.85 },
                        title: `Typical ship time · median over this seller's last 90 days of trades`
                      }, '⚡ ', label);
                    })()
                  ),
                  h('span', { className: 'modal-listing-condition' }, '#' + l.id),
                  l.listedAt && h('span', {
                    className: 'modal-listing-condition',
                    title: 'Listed ' + new Date(l.listedAt).toLocaleString(),
                    style: { fontSize: 10, color: 'var(--text-muted)' }
                  },
                    (Date.now() - l.listedAt < 48 * 3600 * 1000) ? '—' : '',
                    'listed ', timeAgo(l.listedAt)
                  ),
                  // Seller's optional description — lets a seller say
                  // "quick sale, accept 10% below" or "mint, never worn"
                  // without having to chat. Backend sanitizes + caps at
                  // 500 chars; we truncate to 100 for the row with an
                  // ellipsis + full-text tooltip. Hidden when blank.
                  l.description && l.description.trim() && h('span', {
                    style: {
                      fontSize: 11, color: 'var(--text-muted)',
                      fontStyle: 'italic', marginLeft: 8,
                      maxWidth: 180, overflow: 'hidden',
                      textOverflow: 'ellipsis', whiteSpace: 'nowrap'
                    },
                    title: l.description
                  }, '"' + (l.description.length > 100 ? l.description.substring(0, 97) + '…' : l.description) + '"'),
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
                  // Viewer's live offer chip (batch 368) — "You offered $X"
                  // when the signed-in user has a PENDING or COUNTERED
                  // offer on this specific listing. Lets a returning user
                  // pick up where they left off without hitting the Offers
                  // tab. Hidden when the viewer owns the listing (nothing
                  // to offer on).
                  (() => {
                    if (!me || isMine) return null;
                    const mine = myOffersByListing[l.id];
                    if (!mine) return null;
                    const isCountered = mine.status === 'COUNTERED';
                    return h('span', {
                      style: {
                        fontSize: 10, fontWeight: 800, padding: '2px 6px', borderRadius: 4,
                        background: isCountered ? 'rgba(251,191,36,0.15)' : 'rgba(30,165,255,0.12)',
                        color:      isCountered ? '#fbbf24'                : 'var(--accent)',
                        border:     '1px solid ' + (isCountered ? 'rgba(251,191,36,0.4)' : 'rgba(30,165,255,0.35)'),
                        marginRight: 6, whiteSpace: 'nowrap'
                      },
                      title: isCountered
                        ? 'The seller countered your offer. Check Profile → Offers to accept or raise.'
                        : 'Your offer is pending. Seller has not responded yet.'
                    }, isCountered ? '↩ Countered ' : 'Offered ',
                      fmt(parseFloat(mine.amount || 0)));
                  })(),
                  // For an auction row, the displayed amount is either the
                  // current top bid (≥1 bid) or the seller's reserve (0 bids).
                  // Without a label both read identical to a Buy-Now price,
                  // which can mislead a buyer into thinking someone has
                  // already bid the reserve and they need to outbid it. Show
                  // a small muted prefix so the meaning is unambiguous.
                  l.listingType === 'AUCTION' && h('span', {
                    style: {
                      fontSize: 10, fontWeight: 700, color: 'var(--text-muted)',
                      letterSpacing: 0.4, textTransform: 'uppercase',
                      marginRight: 6
                    },
                    title: (l.bidCount ?? 0) > 0
                      ? "Current top bid in this auction"
                      : "Seller's starting bid — be the first to bid"
                  }, (l.bidCount ?? 0) > 0 ? 'BID' : 'START'),
                  h('span', { className: 'modal-listing-price' },
                    fmt(l.listingType === 'AUCTION' && l.currentBid ? l.currentBid : l.price)),
                  // Anon users get a sign-in CTA — bouncing into the API would
                  // fail with a generic auth error that reads as a bug. Own
                  // listings disable the button so sellers don't accidentally
                  // try to buy themselves (the server also rejects but the UI
                  // should surface it up front).
                  l.listingType === 'AUCTION'
                    ? h('button', {
                        className: 'buy-btn',
                        onClick: () => {
                          // Scroll the auction bid panel into view so the
                          // click always lands on an actionable surface.
                          // The main AuctionBidPanel mounts at the top of
                          // the modal — data-auction-panel lets us find it
                          // without wiring refs through the whole tree.
                          const el = document.querySelector('[data-auction-panel]');
                          if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        },
                        title: 'Place or update your bid in the auction panel above'
                      }, 'Bid')
                    : !me
                      ? h('button', {
                          className: 'buy-btn',
                          onClick: () => signInWithSteam(),
                          title: 'Sign in with Steam to buy'
                        }, 'Sign in to buy')
                      : me.id === l.sellerUserId
                        ? h('a', {
                            className: 'buy-btn',
                            href: '/me/stall',
                            style: { background: 'var(--bg-card)', color: 'var(--accent)',
                                     border: '1px solid var(--accent-border)',
                                     textDecoration: 'none', textAlign: 'center' },
                            title: 'Your listing — manage it in My Stall'
                          }, '✎ Manage')
                        : (() => {
                            // Batch 793 — gate Buy on trade URL. Backend
                            // PurchaseService.buy throws TRADE_URL_MISSING
                            // when the buyer has no URL; the UI used to
                            // fire into that error instead of blocking
                            // the click. Now matches the cart + offer +
                            // bid preflight pattern.
                            // House rows (sellerUserId == null) deliver in-platform;
                            // PurchaseService skips TRADE_URL_MISSING for them, so
                            // the gate must too -- it was disabling a buy the server
                            // accepts (the hero "Buy now" for the same row worked).
                            const hasTradeUrl = l.sellerUserId == null || (me.tradeUrl && String(me.tradeUrl).trim());
                            return h('button', {
                              className: 'buy-btn',
                              disabled: !hasTradeUrl,
                              title: !hasTradeUrl
                                ? 'Add your Steam trade URL in Profile before buying'
                                : undefined,
                              onClick: () => requestBuy(l.id, l.price, l.item)
                            }, 'Buy');
                          })(),
                  me && me.id !== l.sellerUserId && h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '4px 8px', fontSize: 12, border: '1px solid var(--border)', opacity: 0.7 },
                    onClick: () => setReportTarget(l),
                    title: 'Report this listing to the moderation team',
                    'aria-label': `Report listing ${l.id}`
                  }, '—')
                );
              })
        ),
        // Batch 836 — "Show all N" expander below the listings list.
        // Only surfaces when there are more than the 6-row default so
        // the common case (sparse items) stays clean. Collapse back
        // to 6 after expanding — a big list on a small modal becomes
        // unwieldy and users sometimes want to re-focus on the floor.
        listings && listings.length > 6 && h('div', {
          style: { marginTop: 6, textAlign: 'center' }
        },
          h('button', {
            className: 'btn btn-ghost',
            style: {
              width: '100%', padding: '6px 12px', fontSize: 11,
              border: '1px dashed var(--border)', color: 'var(--text-secondary)'
            },
            onClick: () => setShowAllListings(v => !v),
            title: showAllListings
              ? `Collapse back to the cheapest 6 listings`
              : `Load every active listing on this item — compare seller rating, ship time, and price across the full set`
          }, showAllListings
              ? `↑ Collapse to top 6`
              : `↓ Show all ${listings.length} listings (+${listings.length - 6})`)
        ),

        // ── More from this seller strip (batch 312) ───────────────
        // Rail of other active listings from the cheapest listing's
        // seller. Tops the "You might also like" strip because a buyer
        // browsing item X by seller Y is more likely to want another
        // item from Y than a random similar-SKU match.
        otherFromSeller && otherFromSeller.listings.length > 0 && h('div', null,
          h('h2', { className: 'modal-section-title', style: { marginTop: 22 } },
            h('div', { className: 'section-title-dot' }),
            `More from ${otherFromSeller.sellerName || 'this seller'}`,
            otherFromSeller.sellerId && h('a', {
              href: '/stall/' + otherFromSeller.sellerId,
              style: { marginLeft: 10, fontSize: 11, color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 }
            }, 'Visit stall →')),
          h('div', { className: 'similar-strip' },
            otherFromSeller.listings.map(l => h('a', {
              key: l.id,
              className: 'similar-card',
              href: '/item/' + (l.item?.id || ''),
              title: l.item?.name,
              onClick: (e) => { if (!l.item?.id) e.preventDefault(); }
            },
              h('div', { className: 'similar-thumb' },
                h(ItemImage, { item: l.item, variant: 'thumb' })),
              h('div', { className: 'similar-name' }, l.item?.name || 'Item'),
              h('div', { className: 'similar-price' }, fmt(l.price))
            ))
          )
        ),

        // ── Similar items strip ────────────────────────────────────
        similar && similar.length > 0 && h('div', null,
          h('h2', { className: 'modal-section-title', style: { marginTop: 22 } },
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
              // Unlisted items have lowestPrice 0 → "$0.00" read as free; show "—".
              h('div', { className: 'similar-price' }, parseFloat(it.lowestPrice) > 0 ? fmt(it.lowestPrice) : '—')
            ))
          )
        )
      ),

      (() => {
        // Low-balance pre-check (batch 391). Surfaces the shortfall
        // inline next to the Buy Now button so the user sees it before
        // clicking — no round-trip to the 402 toast. Only rendered when
        // the viewer is signed-in, the item has a buy-now listing, and
        // the wallet can't cover it. Auction listings use their own bid
        // panel with its own solvency check.
        if (!(me && wallet && cheapestBuyNow)) return null;
        const price = parseFloat(cheapestBuyNow.price) || 0;
        const bal   = parseFloat(wallet.balance) || 0;
        const gap   = price - bal;
        if (!(gap > 0)) return null;
        return h('div', {
          style: {
            margin: '0 30px 10px', padding: '8px 12px',
            background: 'var(--red-dim)',
            border: '1px solid rgba(248,113,113,0.35)',
            borderRadius: 8, color: 'var(--red)',
            fontSize: 12, lineHeight: 1.45,
            display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap',
            justifyContent: 'space-between'
          }
        },
          h('div', null,
            h('strong', null, 'Balance '), fmt(bal), ' · ',
            'need ', h('strong', null, fmt(gap)), ' more to buy.'
          ),
          h('a', {
            href: '/wallet',
            className: 'btn btn-accent',
            style: { padding: '5px 12px', fontSize: 11, textDecoration: 'none' },
            onClick: (e) => { e.stopPropagation(); onClose && onClose(); }
          }, 'Deposit →')
        );
      })(),

      h('div', { className: 'modal-actions' },
        // CSFloat-1:1 — drive Buy Now / Make Offer off the cheapest
        // BUY_NOW listing. The single "Place Bid" CTA only takes over
        // when the item is auction-only (no buy-now listing at all):
        // before this fix an auction sitting at listings[0] hid a
        // perfectly buyable BUY_NOW listing behind a bid-only bar.
        auctionOnly
          ? h('button', {
              className: 'btn btn-accent',
              onClick: () => {
                const el = document.querySelector('[data-auction-panel]');
                if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
              },
              'aria-label': 'Place a bid in the auction panel above'
            }, 'Place Bid')
          : !me && cheapestBuyNow
            ? h('button', {
                className: 'btn btn-accent',
                onClick: () => { signInWithSteam(); },
                'aria-label': 'Sign in with Steam to buy this listing'
              }, `Sign in to buy · ${fmt(cheapestBuyNow.price)}`)
            : (() => {
                // Disable Buy Now when the wallet can't cover the price —
                // the banner above already tells the user what to do.
                // Clicking would just 402 on the server.
                const price = cheapestBuyNow ? (parseFloat(cheapestBuyNow.price) || 0) : 0;
                const bal   = wallet ? (parseFloat(wallet.balance) || 0) : Infinity;
                const broke = me && wallet && cheapestBuyNow && bal < price;
                return h('button', {
                  className: 'btn btn-accent',
                  disabled: !cheapestBuyNow || broke,
                  onClick: () => cheapestBuyNow && requestBuy(cheapestBuyNow.id, cheapestBuyNow.price, cheapestBuyNow.item),
                  'aria-label': cheapestBuyNow ? `Buy for ${fmt(cheapestBuyNow.price)}` : 'Out of stock',
                  title: broke ? 'Deposit funds first — your wallet is short' : null
                },
                  !cheapestBuyNow
                    ? 'Out of Stock'
                    : broke
                      ? `Deposit to buy · ${fmt(cheapestBuyNow.price)}`
                      : `Buy Now · ${fmt(cheapestBuyNow.price)}`
                );
              })(),
        // "Make Offer" — redirect to Steam OpenID for anon viewers so
        // the sign-in lands them back on the item URL with the modal
        // restored, rather than opening an empty bargaining form that
        // 401s on submit. Hidden when the item is auction-only — the
        // bid surface is the only valid bargain path there.
        auctionOnly
          ? null
          : !me
            ? h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                onClick: () => { signInWithSteam(); },
                disabled: !cheapestBuyNow
              }, 'Sign in to make offer')
            : h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                onClick: () => setOfferOpen(o => !o),
                disabled: !cheapestBuyNow
              }, offerOpen ? 'Cancel Offer' : 'Make Offer'),
        /* Place Buy Order hidden for anon viewers — requires auth anyway.
           Keeps the action bar focused like csfloat's item page. */
        onCreateBuyOrder && me && h('button', {
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
          // Restock-mode alerts are encoded as targetPrice ≥ 99999 (the
          // $100k cap). Detect it so the label/tooltip don't read
          // "Alert at $100,000.00" — that would confuse the user into
          // thinking they'd set a real threshold near the cap.
          const isRestock = myAlert && parseFloat(myAlert.targetPrice) >= 99999;
          const alertLabel = outOfStock
            ? (myAlert ? ' Restock Alert · On' : ' Notify When Listed')
            : myAlert
              ? (isRestock ? ' Restock Alert · Edit' : ` Alert at ${fmt(myAlert.targetPrice)} · Edit`)
              : ' Set Price Alert';
          return h('button', {
            className: 'btn btn-ghost',
            style: {
              border: '1px solid var(--border)',
              // Subtle accent border when an alert is already active so the
              // button visually reads as "done" rather than "todo".
              ...(myAlert ? { borderColor: 'rgba(34,197,94,0.4)', color: '#22c55e' } : {})
            },
            title: outOfStock
              ? (myAlert ? 'Restock alert active — fires the moment any listing appears.' : 'Get notified when this item is listed again')
              : myAlert
                ? (isRestock
                    ? 'Restock-style alert active — fires on any future listing regardless of price. Click to switch to a price target.'
                    : `Active alert · fires when any listing drops to ${fmt(myAlert.targetPrice)}. Click to edit.`)
                : 'Get notified when the floor price drops to your target',
            onClick: async () => {
              if (outOfStock) {
                // Restock alerts stay one-tap — there's no amount to adjust,
                // the sweeper just needs any future listing to exist.
                if (!confirm(`Notify me when ${item.name} is listed again?`)) return;
                const { createWatchlistAlert } = await import('./api.js');
                const res = await createWatchlistAlert(item.id, 100000);
                if (res && (res.error || res.code)) {
                  toast(res.message || res.error || 'Could not save alert', 'err');
                } else {
                  toast(`Restock alert set — you'll be notified when ${item.name} is listed again.`, 'ok');
                }
                return;
              }
              // In-stock: open the inline price-alert drawer. If the user
              // already has an active *threshold* alert, seed the current
              // target so "Edit" actually reflects what they set.
              // Restock-mode alerts (targetPrice ≥ 99999) used to seed
              // the input with $100,000 — meaningless once the item is
              // back in stock. Treat those like a fresh alert and default
              // to 15% below the current floor instead, so the user
              // gets a sensible starting point either way.
              const existingTarget = myAlert ? parseFloat(myAlert.targetPrice) : null;
              const isRestockSeed = existingTarget != null && existingTarget >= 99999;
              const seed = (existingTarget != null && !isRestockSeed)
                ? existingTarget.toFixed(2)
                : ((parseFloat(item.lowestPrice) || 0) * 0.85).toFixed(2);
              setAlertTarget(seed);
              setAlertErr('');
              setAlertOpen(o => !o);
            }
          },
            h(MaterialIcon, {
              name: myAlert ? 'notifications_active' : 'notifications_active',
              size: 16
            }),
            alertLabel
          );
        })(),
        onAddToCart && cheapestBuyNow && h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          disabled: cartHas && cartHas(cheapestBuyNow.id),
          onClick: () => onAddToCart(cheapestBuyNow),
          title: 'Add cheapest buy-now listing to cart'
        }, h(MaterialIcon, { name: 'shopping_cart', size: 16 }),
          cartHas && cartHas(cheapestBuyNow.id) ? ' In Cart' : ' Add to Cart'),
        // "List one of these" — deep-links the seller to /sell with the
        // item name pre-filled in the inventory search filter so they
        // land on the right row without scrolling. Signed-in only;
        // anonymous viewers can't list anyway. Hidden when the viewer
        // is the only seller of every active listing (no point telling
        // them to compete with themselves).
        me && (() => {
          const allMine = listings && listings.length > 0 &&
            listings.every(l => l.sellerUserId === me.id);
          if (allMine) return null;
          return h('a', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', textDecoration: 'none' },
            href: '/sell?q=' + encodeURIComponent(item.name || ''),
            title: 'Jump to Sell with this item pre-filtered in your inventory'
          },
            h(MaterialIcon, { name: 'sell', size: 16 }), ' List one of these');
        })(),
        h(SteamMarketLink, { item }),
        // Wishlist toggle - reads watchlist + toggleStar from props (passed
        // through from App). Was previously a placeholder button with no
        // onClick, so anon visitors saw a heart that did nothing. Now the
        // heart fills + the localStorage watchlist updates + the nav badge
        // re-counts, matching the GridCard star behavior.
        (() => {
          const starred = Array.isArray(watchlist) && item?.id != null && watchlist.includes(item.id);
          return h('button', {
            className: 'btn btn-ghost btn-wishlist' + (starred ? ' on' : ''),
            style: { border: '1px solid var(--border)' },
            onClick: () => onToggleStar && item?.id != null && onToggleStar(item.id),
            title: starred ? 'Remove from watchlist' : 'Add to watchlist',
            'aria-label': starred ? `Remove ${item.name} from watchlist` : `Add ${item.name} to watchlist`,
            'aria-pressed': starred
          }, starred ? '♥' : '♡');
        })()
      ),

      alertOpen && (() => {
        // Inline price-alert drawer (batch 385). Replaces the legacy
        // window.prompt with a proper dialog — ± quick-adjust chips,
        // $ currency affordance, and a live preview sentence so the
        // buyer knows exactly what they're signing up for.
        const floor = parseFloat(item.lowestPrice) || 0;
        const typed = parseFloat(alertTarget);
        const valid = Number.isFinite(typed) && typed > 0 && typed <= 100000;
        const aboveFloor = valid && floor > 0 && typed > floor;
        const bump = (deltaPct) => {
          const base = Number.isFinite(typed) && typed > 0 ? typed : floor;
          if (!base) return;
          const next = Math.max(0.01, base * (1 + deltaPct / 100));
          setAlertTarget(next.toFixed(2));
        };
        return h('div', {
          style: {
            padding: '14px 30px', background: 'var(--bg-secondary)',
            borderRadius: 8, margin: '10px 30px'
          }
        },
          h('div', {
            className: 'wallet-input-label',
            style: { marginBottom: 8, fontSize: 13, color: 'var(--text-secondary)' }
          }, 'Notify me when the floor drops to…'),
          // Percent-off preset chips — csfloat-parity quick-set for "10%
          // drop / 20% drop / etc." patterns. Computes
          //   target = floor × (1 - pct/100)
          // and seeds the input. Only renders when there's a real floor
          // to discount from; out-of-stock items use the restock flow
          // instead (handled at the Notify-When-Listed branch above).
          floor > 0 && h('div', {
            style: { display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 10 }
          },
            h('span', {
              style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', alignSelf: 'center', marginRight: 4 }
            }, 'Quick:'),
            [5, 10, 20, 30, 50].map(pct => {
              const next = Math.max(0.01, floor * (1 - pct / 100));
              const isActive = Number.isFinite(typed) && Math.abs(typed - next) < 0.005;
              return h('button', {
                key: 'pct-' + pct,
                type: 'button',
                className: 'btn btn-ghost',
                style: {
                  padding: '4px 10px', fontSize: 11,
                  border: '1px solid ' + (isActive ? 'var(--accent)' : 'var(--border)'),
                  color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
                  fontWeight: isActive ? 700 : 500
                },
                onClick: () => setAlertTarget(next.toFixed(2)),
                title: `Notify me when the floor drops ${pct}% from current — that's ${fmt(next)}`
              }, '−', pct, '%');
            })
          ),
          h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
            h('div', {
              style: {
                display: 'flex', alignItems: 'center', flex: 1,
                background: 'var(--bg-page-2, #0d1320)',
                border: '1px solid var(--border)', borderRadius: 6,
                padding: '0 10px'
              }
            },
              h('span', {
                style: { color: 'var(--text-muted)', fontSize: 14, marginRight: 6 }
              }, '$'),
              h('input', {
                className: 'wallet-amount-input',
                style: {
                  flex: 1, background: 'transparent', border: 'none',
                  padding: '8px 0', fontSize: 14, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace"
                },
                type: 'number', step: '0.01', min: '0.01', max: '100000',
                inputMode: 'decimal',
                enterKeyHint: 'done',
                'aria-label': 'Price alert target (USD)',
                value: alertTarget,
                onChange: e => setAlertTarget(e.target.value),
                autoFocus: true
              })
            ),
            h('button', {
              className: 'btn btn-ghost',
              style: { padding: '8px 10px', fontSize: 11, border: '1px solid var(--border)' },
              onClick: () => bump(-10),
              title: '-10%'
            }, '−10%'),
            h('button', {
              className: 'btn btn-ghost',
              style: { padding: '8px 10px', fontSize: 11, border: '1px solid var(--border)' },
              onClick: () => bump(10),
              title: '+10%'
            }, '+10%'),
            h('button', {
              className: 'btn btn-accent',
              style: { padding: '8px 18px', fontSize: 13 },
              disabled: alertBusy || !valid,
              onClick: async () => {
                setAlertErr('');
                if (!valid) { setAlertErr('Enter a positive dollar amount (≤ $100,000)'); return; }
                setAlertBusy(true);
                try {
                  const { createWatchlistAlert } = await import('./api.js');
                  const res = await createWatchlistAlert(item.id, typed);
                  if (res && (res.error || res.code)) {
                    setAlertErr(res.message || res.error || 'Could not save alert');
                    return;
                  }
                  toast(`Price alert set — you'll be notified when ${item.name} drops to ${fmt(typed)}.`, 'ok');
                  // Sync the "Alert at $X" chip immediately — refetch would
                  // also work but we already know the target we just saved.
                  setMyAlert({ itemId: item.id, targetPrice: typed, status: 'ACTIVE' });
                  setAlertOpen(false);
                } finally { setAlertBusy(false); }
              }
            }, alertBusy ? '…' : 'Save'),
            h('button', {
              className: 'btn btn-ghost',
              style: { padding: '8px 10px', fontSize: 11 },
              onClick: () => setAlertOpen(false),
              'aria-label': 'Close alert editor'
            }, '✕')
          ),
          h('div', {
            style: {
              fontSize: 11, marginTop: 8,
              color: aboveFloor ? 'var(--amber, #fbbf24)' : 'var(--text-muted)',
              lineHeight: 1.5
            }
          },
            floor > 0 && h('span', null, 'Current floor ', h('strong', null, fmt(floor)), ' · '),
            valid
              ? (aboveFloor
                  ? 'This target is already above the current floor — the alert will fire immediately.'
                  : `We'll ping you when any listing on this item drops to ${fmt(typed)} or lower.`)
              : 'Enter a positive dollar amount.'
          ),
          alertErr && h('div', {
            style: { color: 'var(--red)', fontSize: 12, marginTop: 6 }
          }, alertErr)
        );
      })(),
      offerOpen && (() => {
        // Live preview math (batch 407). Updates as the buyer types so
        // they see the % below ask, the $ they're saving, and an
        // optimistic "likely accept" / "below floor / won't match"
        // guidance without submitting. Anchored on the cheapest BUY_NOW
        // listing — that's the listing the offer targets and what the
        // server will charge if accepted.
        const ask = cheapestBuyNow ? parseFloat(cheapestBuyNow.price) || 0 : 0;
        const amt = parseFloat(offerAmt) || 0;
        const maxDisc = cheapestBuyNow ? parseFloat(cheapestBuyNow.maxDiscount) || 0 : 0;
        const belowAskPct = (ask > 0 && amt > 0) ? ((ask - amt) / ask * 100) : null;
        const savings     = (ask > 0 && amt > 0 && amt < ask) ? (ask - amt) : 0;
        const autoThreshold = (ask > 0 && maxDisc > 0) ? (ask * (1 - maxDisc)) : 0;
        const autoAccept = (autoThreshold > 0 && amt >= autoThreshold);
        const atOrAboveAsk = amt >= ask;
        // Live-offer pre-check (batch 438). When the viewer already has a
        // PENDING/COUNTERED offer on this exact listing, the server will
        // refuse a new one with OFFER_ALREADY_PENDING. Surface that state
        // upfront with a clear "View / raise it instead" route so the
        // user doesn't type a price, hit Send, and bounce off a 400.
        const myLive = cheapestBuyNow ? myOffersByListing[cheapestBuyNow.id] : null;
        return h('div', { className: 'item-offer-drawer', style: { padding: '14px 30px', background: 'var(--bg-secondary)', borderRadius: 8, margin: '10px 30px' } },
        myLive && h('div', {
          style: {
            margin: '0 0 10px', padding: '10px 12px',
            background: 'rgba(30,165,255,0.08)',
            border: '1px solid rgba(30,165,255,0.35)',
            borderRadius: 6, color: 'var(--text-primary)',
            fontSize: 12, lineHeight: 1.5,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            gap: 10, flexWrap: 'wrap'
          }
        },
          h('span', null,
            'You already have a ',
            h('strong', null, myLive.status === 'COUNTERED' ? 'COUNTERED' : 'PENDING'),
            ' offer on this listing at ',
            h('strong', { style: { color: 'var(--accent)' } }, fmt(parseFloat(myLive.amount || 0))),
            '. Raise it from your Offers tab instead of creating a new one.'
          ),
          h('a', {
            href: '/offers',
            className: 'btn btn-accent',
            style: { padding: '5px 12px', fontSize: 11, textDecoration: 'none' },
            onClick: (e) => { e.stopPropagation(); onClose && onClose(); }
          }, 'View offer →')
        ),
        h('div', { className: 'wallet-input-label', style: { marginBottom: 8, fontSize: 13, color: 'var(--text-secondary)' } }, 'Your offer (must be below asking price)'),
        h('input', {
          className: 'wallet-amount-input',
          type: 'number', min: '0.01', step: '0.01',
          // Batch 923 — mobile decimal keyboard hint (see wallet-amount
          // input elsewhere). Matches iOS/Android's expected keypad for
          // price-entry fields.
          inputMode: 'decimal',
          enterKeyHint: 'send',
          'aria-label': 'Offer amount in USD',
          placeholder: (ask > 0 ? ask * 0.85 : 0).toFixed(2),
          value: offerAmt,
          onChange: e => setOfferAmt(e.target.value),
          // Batch 823 — Esc closes the offer drawer. Before this the
          // drawer ate focus but gave the user no keyboard exit; the
          // only dismissal was a pointer click on the ✕ button.
          // (Enter-to-submit is NOT wired here because the submit
          // handler is a closure on the Send Offer button — refactoring
          // it out would churn validation logic that's already carefully
          // tested. Esc-to-close is the high-value half of the
          // keyboard loop.)
          onKeyDown: (e) => {
            if (e.key === 'Escape') {
              e.stopPropagation(); e.preventDefault();
              setOfferOpen(false);
              setOfferAmt('');
              setOfferMsg('');
              setOfferErr('');
            }
          },
          style: { width: '100%' },
          autoFocus: true
        }),
        // Batch 764 — quick-fill chips below the offer input, mirror
        // of the Sell form's price-suggest row. Gives bargain-hunting
        // buyers a one-click way to try -5/-10/-15/-20% vs ask. At the
        // seller's auto-accept threshold we also inject a "Auto-accept"
        // chip so the buyer can instantly settle without waiting.
        (() => {
          const ask = parseFloat(cheapestBuyNow?.price || 0);
          if (!(ask > 0)) return null;
          const chips = [
            { label: '−5%',  v: +(ask * 0.95).toFixed(2) },
            { label: '−10%', v: +(ask * 0.90).toFixed(2) },
            { label: '−15%', v: +(ask * 0.85).toFixed(2) },
            { label: '−20%', v: +(ask * 0.80).toFixed(2) }
          ];
          // When the listing has a published auto-accept threshold
          // (computed upstream from `listing.maxDiscount`), surface
          // it as a green chip so a buyer can skip the wait.
          if (autoThreshold > 0 && autoThreshold < ask) {
            chips.push({
              label: 'Auto-accept',
              v: +autoThreshold.toFixed(2),
              hint: `Exactly at the seller's auto-accept threshold — your offer would be accepted instantly.`,
              accept: true
            });
          }
          return h('div', { className: 'price-suggest-row', style: { marginTop: 8 } },
            chips.map((c, i) => h('button', {
              key: i,
              type: 'button',
              className: 'price-suggest-chip' + (c.accept ? ' accept' : ''),
              title: c.hint || `Fill in ${c.label} below ask (${fmt(c.v)})`,
              onClick: () => setOfferAmt(c.v.toFixed(2))
            },
              h('span', { className: 'price-suggest-chip-label' }, c.label),
              h('span', { className: 'price-suggest-chip-amt' }, fmt(c.v))
            ))
          );
        })(),
        h('div', { style: { display: 'flex', gap: 10, marginTop: 10 } },
          (() => {
            // Batch 791 — gate Send Offer on trade-URL presence. If the
            // seller's auto-accept fires and PurchaseService hits
            // TRADE_URL_MISSING, the offer silently stays PENDING and
            // the buyer is left wondering why their offer didn't
            // auto-accept. Blocking up front matches the preflight on
            // the cart (batches 788-790) + the ItemModal banner at the
            // top of the modal.
            const hasTradeUrl = me && me.tradeUrl && String(me.tradeUrl).trim();
            const whyDisabled = !hasTradeUrl
              ? 'Add your Steam trade URL in Profile before making offers'
              : myLive
                ? 'You already have an active offer on this listing'
                : undefined;
            return h('button', {
              className: 'btn btn-accent',
              style: { padding: '0 22px', fontSize: 13 },
              disabled: offerBusy || !offerAmt || atOrAboveAsk || !!myLive || !hasTradeUrl,
              title: whyDisabled,
              onClick: async () => {
                setOfferErr('');
                // Validate the typed amount before the POST. `min="0.01"` on the
                // input does NOT block free-typed/pasted values like -5, and the
                // disabled-state covers atOrAboveAsk only — so guard both bounds
                // here (a sub-cent offer would also round to $0.00 server-side).
                if (!Number.isFinite(amt) || amt < 0.01) { setOfferErr('Enter an offer of at least $0.01.'); return; }
                if (amt >= ask) { setOfferErr('Offer must be below the asking price.'); return; }
                if (offerBusyRef.current) return;   // synchronous double-submit guard
                offerBusyRef.current = true;
                setOfferBusy(true);
                try {
                  const res = await onMakeOffer(cheapestBuyNow?.id, parseFloat(offerAmt), offerMsg);
                  if (res && res.error) { setOfferErr(res.message || res.error); return; }
                  setOfferOpen(false);
                  setOfferAmt('');
                  setOfferMsg('');
                  onClose();
                } finally { offerBusyRef.current = false; setOfferBusy(false); }
              }
            }, offerBusy ? '...' : 'Send Offer');
          })()
        ),
        // Live math preview chip. Green when auto-accept would fire,
        // amber when the offer is under the seller's maxDiscount or
        // the amount is blank/invalid, red when at/above ask.
        belowAskPct != null && h('div', {
          style: {
            fontSize: 11, marginTop: 8, lineHeight: 1.5,
            color: atOrAboveAsk
              ? 'var(--red)'
              : autoAccept
                ? 'var(--green)'
                : 'var(--text-muted)'
          }
        },
          atOrAboveAsk
            ? `⚠ ${fmt(amt)} is at or above the asking price (${fmt(ask)}) — use Buy Now instead.`
            : (
                autoAccept
                  ? `✓ ${belowAskPct.toFixed(1)}% below ask · save ${fmt(savings)} · at or above seller's auto-accept threshold (${fmt(autoThreshold)}) — may accept instantly.`
                  : `${belowAskPct.toFixed(1)}% below ask · save ${fmt(savings)}${autoThreshold > 0 ? ` · auto-accept at ${fmt(autoThreshold)}` : ''}`
              )
        ),
        // Optional 280-char note. Sellers anchor on context — "first
        // purchase, will pay fast" lands very differently from a silent
        // 30%-off offer. Char counter goes red past the cap so the buyer
        // notices before the server bounces them.
        h('div', { style: { marginTop: 10 } },
          h('textarea', {
            className: 'price-input',
            style: {
              width: '100%', minHeight: 56, padding: '8px 10px',
              fontFamily: 'inherit', fontSize: 12, resize: 'vertical',
              background: 'var(--bg-page-2, #0d1320)',
              border: '1px solid var(--border)',
              borderRadius: 6, color: 'var(--text-primary)'
            },
            'aria-label': 'Optional note to seller (max 280 characters)',
            placeholder: 'Optional note to the seller (e.g. "brand new account, fast pay")',
            maxLength: 280,
            value: offerMsg,
            onChange: e => setOfferMsg(e.target.value)
          }),
          h('div', {
            style: {
              fontSize: 11, marginTop: 4, textAlign: 'right',
              color: offerMsg.length > 280 ? 'var(--red)' : 'var(--text-muted)'
            }
          }, `${offerMsg.length}/280`)
        ),
        offerErr && h('div', { style: { color: 'var(--red)', fontSize: 12, marginTop: 6 } }, offerErr)
      );
      })(),
      reportTarget && h(ReportListingDrawer, {
        listing: reportTarget,
        reasons: reportReasons,
        onCancel: () => setReportTarget(null),
        onSubmitted: () => { setReportTarget(null); onRefresh && onRefresh(); }
      }),
      // CSFloat parity — Recently Viewed strip as the last section in
      // the item modal, so a buyer who's been browsing 4+ items can hop
      // back to a recent one without leaving the page. Inlined (rather
      // than imported from app.js) to avoid a circular module import.
      // Reads the same `sb_recently_viewed` localStorage key the home
      // rail uses; hides the active item so the strip points outward.
      (() => {
        let recent = [];
        try { recent = JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]'); } catch (_) {}
        const visible = (recent || []).filter(r => !item?.id || String(r.id) !== String(item.id));
        if (visible.length < 4) return null;
        return h('section', { className: 'recently-viewed', style: { padding: '0 30px 20px' } },
          h('h2', { className: 'recently-viewed-head' },
            h('span', { className: 'section-title-dot' }),
            'Recently viewed'
          ),
          h('div', { className: 'recently-viewed-rail' },
            visible.slice(0, 12).map(it => h('a', {
              key: it.id,
              href: '/item/' + it.id,
              className: 'recently-viewed-card',
              onClick: (e) => { e.preventDefault(); navigate('/item/' + it.id); }
            },
              h('div', { className: 'recently-viewed-thumb' },
                it.imageUrl
                  ? h('img', { src: it.imageUrl, alt: it.name, loading: 'lazy' })
                  : h('div', { className: 'recently-viewed-glyph', style: { color: 'var(--ink-3)' } },
                      ({Hats:'◈',Jackets:'▲',Shirts:'■',Pants:'▮',Gloves:'◉',Boots:'▼',Accessories:'◆',Workshop:'❖'})[it.category] || '—')
              ),
              h('div', { className: 'recently-viewed-name' }, it.name),
              h('div', { className: 'recently-viewed-price' },
                // lowestPrice 0.00 (not null) for unlisted items rendered "$0.00"
                // (reads as free) — require > 0, else "—".
                (it.lowestPrice != null && parseFloat(it.lowestPrice) > 0) ? fmt(it.lowestPrice) : '—')
            ))
          )
        );
      })()
    ),
    /* Image lightbox — full-viewport overlay opened by the magnifier
       button on the item-detail image. Click backdrop or the close X to
       dismiss; Escape + Tab focus-trap handled by useDialogA11y(lightboxRef). */
    isPageMode && zoomOpen && h('div', {
      ref: lightboxRef,
      className: 'item-lightbox',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-label': item?.name ? `${item.name} — full size image` : 'Item image at full size',
      onClick: () => setZoomOpen(false)
    },
      h('button', {
        type: 'button',
        className: 'item-lightbox-close',
        onClick: (e) => { e.stopPropagation(); setZoomOpen(false); },
        'aria-label': 'Close full-size image'
      }, '✕'),
      h('div', { className: 'item-lightbox-frame', onClick: (e) => e.stopPropagation() },
        h(ItemImage, { item, variant: 'hero' })
      ),
      h('div', { className: 'item-lightbox-cap' }, item?.name || 'Item')
    ),
    // CSFloat-1:1 — Buy Now confirm step. Reuses the cart checkout's
    // `cart-confirm-*` styling so the single-item dialog matches the
    // cart confirm dialog 1:1. Renders only while `buyConfirm` is set;
    // the three buy buttons open it via requestBuy(), and "Confirm
    // purchase" calls the SAME onBuy() the direct buttons used to call —
    // the fee model is untouched, this only inserts a review step.
    buyConfirm && (() => {
      const bc = buyConfirm;
      const price = parseFloat(bc.price) || 0;
      // Trade Protection display line. Reuses the exact 2%-of-price
      // expression already used by the trade confirm modal (`(price) *
      // 0.02`) — no new fee math is introduced. Trade Protection is an
      // optional opt-in add-on enabled AFTER purchase on the trade row,
      // so it is shown here as an informational estimate and is NOT
      // added to the charged total — matching this app's frozen model
      // where the buyer is debited exactly the listing price (see the
      // cart confirm dialog: "Total charged to wallet" == subtotal).
      // Match the REAL protection fee the trade row will charge: 2% of
      // price floored at $0.25 (TradeProtectionService.MIN_FEE /
      // trade-protection.js computeFee). A bare price*0.02 under-quoted
      // cheap items by up to 25x — a $0.60 item showed "$0.01" here but
      // costs $0.25 to actually protect, so the estimate set a false
      // expectation. Round the 2% to cents half-up before the floor,
      // mirroring computeFee exactly.
      const tradeProtection = Math.max(0.25, Math.round(price * 2) / 100);
      const tpFloored = tradeProtection <= 0.25;
      const confirmItem = bc.item || item;
      const buyConfirmRow = (listings || []).find(l => l && l.id === bc.listingId) || cheapestBuyNow;
      const buyConfirmIsHouse = !!buyConfirmRow && buyConfirmRow.sellerUserId === null;
      return h('div', {
        className: 'cart-confirm-backdrop',
        // Higher than the item modal (modal-backdrop is z-index 200) so
        // the confirm sits above the page it was launched from.
        style: { zIndex: 240 },
        onClick: () => closeBuyConfirm()
      },
        h('div', {
          ref: buyConfirmRef,
          className: 'cart-confirm-panel',
          style: { maxWidth: 460 },
          onClick: (e) => e.stopPropagation(),
          role: 'dialog',
          'aria-modal': 'true',
          'aria-labelledby': 'buy-confirm-title'
        },
          h('div', { className: 'cart-confirm-title', id: 'buy-confirm-title' }, 'Confirm purchase'),
          h('div', { className: 'cart-confirm-sub' },
            // A house listing (sellerUserId === null) has no seller and opens
            // no trade -- the item lands in Platform Inventory -- so the
            // escrow sentence contradicted the delivery line right under it.
            buyConfirmIsHouse
              ? 'Review your purchase. Your wallet is charged the listing price and the item is added to your Platform Inventory.'
              : 'Review your purchase. Your wallet is charged the listing price and an escrow trade opens with the seller.'),
          // "An escrow trade opens with the seller" names a mechanism and
          // answers neither question a buyer has at this moment: what do I
          // get, and when. The multi-item cart confirm has said so since the
          // delivery-expectation fix; this single-item path — the common one
          // — did not, so the same purchase made two ways described itself
          // two different ways. Resolve the listing so the line reflects the
          // row actually being bought (an Active Listings row may not be the
          // cheapest one), and fall back to the cheapest BUY_NOW the rail
          // targets when the id is not in the array.
          (() => {
            const row = (listings || []).find(l => l && l.id === bc.listingId) || cheapestBuyNow;
            return h(DeliveryExpectation, {
              // `undefined` when no row resolved — NOT null. Null is the
              // platform's own inventory and would print a confident
              // "nothing to wait on" about a listing we failed to look up.
              sellerUserId: row ? row.sellerUserId : undefined,
              days: deliveryDays,
              compact: true
            });
          })(),
          // Item row — image + name, mirroring csfloat's confirm dialog
          // which shows what you're about to buy at the top.
          h('div', {
            style: {
              display: 'flex', alignItems: 'center', gap: 12,
              margin: '4px 0 14px', padding: '10px 12px',
              background: 'var(--bg-elevated)',
              border: '1px solid var(--border)', borderRadius: 8
            }
          },
            h('div', { style: { width: 48, height: 48, flexShrink: 0 } },
              h(ItemImage, { item: confirmItem, variant: 'thumb' })),
            h('div', { style: { flex: 1, minWidth: 0 } },
              h('div', {
                style: {
                  fontSize: 13, fontWeight: 700, color: 'var(--text-primary)',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'
                }
              }, confirmItem?.name || 'Item'),
              confirmItem?.category && h('div', {
                style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }
              }, confirmItem.category)
            )
          ),
          // Price breakdown — item price, Trade Protection (2%) line, and
          // the total. Uses the same cart-confirm row classes for visual
          // parity with the cart checkout summary.
          h('div', { style: { margin: '0 0 6px' } },
            h('div', { className: 'cart-confirm-row' },
              h('div', { style: { flex: 1 } }, 'Item price'),
              h('div', { className: 'cart-confirm-amt' }, fmt(price))
            ),
            h('div', { className: 'cart-confirm-row' },
              h('div', { style: { flex: 1 } },
                tpFloored ? `Trade Protection (min ${fmt(0.25)})` : 'Trade Protection (2%)',
                h('span', {
                  style: { color: 'var(--text-muted)', fontSize: 11, marginLeft: 6, fontWeight: 500 }
                }, '· optional, add after purchase')
              ),
              h('div', { className: 'cart-confirm-amt', style: { color: 'var(--text-muted)' } }, fmt(tradeProtection))
            )
          ),
          h('div', { className: 'cart-confirm-total' },
            h('div', null,
              h('div', { className: 'cart-confirm-total-label' }, 'Total charged to wallet'),
              !buyConfirmIsHouse && h('div', { className: 'cart-confirm-total-hint' }, 'Seller receives price minus 2% platform fee after confirmed delivery.')
            ),
            h('div', { className: 'cart-confirm-total-amt' }, fmt(price))
          ),
          h('div', { className: 'cart-confirm-actions' },
            h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)' },
              // Always allow back-out, even mid-purchase (customer-readiness
              // BLOCKER): disabling Cancel on busy trapped the user when a
              // request hung. The Confirm guard below still blocks double-submit.
              onClick: () => closeBuyConfirm()
            }, 'Cancel'),
            h('button', {
              className: 'btn btn-accent',
              disabled: buyConfirmBusy,
              onClick: async () => {
                // SYNCHRONOUS re-entrancy latch on a MONEY action. Checking the
                // async `buyConfirmBusy` STATE left a double-click window: both
                // clicks fire in one React batch before the setBuyConfirmBusy(true)
                // re-render lands, both see false, both POST the purchase ->
                // DOUBLE CHARGE. The ref flips immediately, so the 2nd click is
                // blocked at once. Mirrors the Make Offer button's offerBusyRef.
                // (frontend-audit fix — CRITICAL)
                if (buyConfirmBusyRef.current) return;
                buyConfirmBusyRef.current = true;
                setBuyConfirmBusy(true);
                try {
                  // Fire the EXISTING purchase function the direct buy
                  // buttons used. We only inserted a confirm step in
                  // front — nothing about what executes is changed.
                  await onBuy(bc.listingId, bc.price);
                } finally {
                  buyConfirmBusyRef.current = false;
                  setBuyConfirmBusy(false);
                  setBuyConfirm(null);
                }
              }
            }, buyConfirmBusy ? 'Confirming…' : `Confirm purchase · ${fmt(price)}`)
          )
        )
      );
    })()
  );
}

// Batch 1167 — full dialog a11y helper for the larger modal shells
// (ItemModal, WalletModal, ReviewModal, ConfirmTradeModal,
// BulkAdjustDrawer). Wraps:
//   • Escape-to-close
//   • Initial focus into the panel (first focusable or panel itself)
//   • Focus restoration to the triggering element on unmount
//   • Tab/Shift+Tab focus trap inside the panel
//
// Pure additive — does not alter visual behaviour. Pattern mirrors
// InfoModal in info-modal.js so the keyboard contract is consistent
// across every dialog surface in the app.
//
// `enabled` lets a caller suppress the hook (e.g. ItemModal in
// page mode where the dialog is actually a routed page).
export function useDialogA11y(panelRef, onClose, enabled = true) {
  useEffect(() => {
    if (!enabled) return;
    if (typeof onClose !== 'function') return;
    const prev = document.activeElement;
    // Defer one tick so the panel's children have mounted and a
    // focus() call lands on a real focusable instead of nothing.
    const rafId = requestAnimationFrame(() => {
      if (!panelRef.current) return;
      // Prefer the first natural focusable inside the panel — usually
      // the close button or a primary action. Fall back to the panel
      // itself (tabIndex=-1 so focus() lands; declared on the panel
      // element by the caller).
      const focusables = panelRef.current.querySelectorAll(
        'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
      );
      const target = focusables[0] || panelRef.current;
      try { target.focus({ preventScroll: true }); }
      catch { try { target.focus(); } catch (_) {} }
    });
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !panelRef.current) return;
      // Filter out hidden elements — calling .focus() on a display:none
      // node silently no-ops and breaks the trap.
      const focusables = Array.prototype.filter.call(
        panelRef.current.querySelectorAll(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
        ),
        el => {
          if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') return false;
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0;
        }
      );
      if (!focusables.length) return;
      const first = focusables[0];
      const last  = focusables[focusables.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && (active === first || !panelRef.current.contains(active))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (active === last || !panelRef.current.contains(active))) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      cancelAnimationFrame(rafId);
      document.removeEventListener('keydown', onKey);
      try {
        if (prev && typeof prev.focus === 'function' && document.contains(prev)) {
          prev.focus({ preventScroll: true });
        }
      } catch (_) {}
    };
  }, [onClose, enabled, panelRef]);
}

function ReportListingDrawer({ listing, reasons, onCancel, onSubmitted }) {
  // Batch 1167 — Escape + focus trap + restore-focus via useDialogA11y.
  // The drawer's `selectRef.current.focus()` below still grabs initial
  // focus on the reason picker (useDialogA11y's autofocus is a no-op
  // when something else already owns focus), but useDialogA11y keeps
  // Tab inside the drawer and restores focus to the row's Report
  // button on close. Replaces the old useEscapeToClose call.
  //
  // Parents pass `onCancel: () => setX(null)` — a fresh closure on
  // every render. Wrapping the prop in a ref-backed stable callback
  // keeps useDialogA11y mounted continuously through parent re-renders
  // (otherwise the focus trap would tear down + rebuild on every
  // re-render and prematurely steal focus back to the trigger).
  const panelRef = useRef(null);
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const stableCancel = useCallback(() => onCancelRef.current && onCancelRef.current(), []);
  useDialogA11y(panelRef, stableCancel);
  // 2026-05-20 — seed `reason` from the first server-supplied reason
  // rather than a hard-coded string. The <select> options come from the
  // fetched `reasons` list; if that list doesn't contain the literal
  // 'Suspicious pricing' the select rendered its own first option while
  // `reason` state still held the stale hard-coded value — so submit
  // sent a reason the reporter never saw selected. Effect below also
  // corrects the value if `reasons` resolves after this first render.
  const [reason, setReason] = useState(() =>
    (Array.isArray(reasons) && reasons.length > 0) ? reasons[0] : 'Suspicious pricing');
  const [note, setNote]     = useState('');
  const [busy, setBusy]     = useState(false);
  const [err, setErr]       = useState('');
  const [done, setDone]     = useState('');
  const MAX_NOTE = 500;
  // Keep `reason` consistent with the available options. Runs when the
  // reasons list changes (e.g. arrives async); leaves a valid current
  // selection untouched so the user's pick isn't clobbered.
  useEffect(() => {
    if (Array.isArray(reasons) && reasons.length > 0 && !reasons.includes(reason)) {
      setReason(reasons[0]);
    }
  }, [reasons]);
  // Batch 909 — autofocus the Reason select on mount so the reporter
  // can arrow-key + Enter or just Tab straight into the details box
  // without fishing for the first control.
  const selectRef = useRef(null);
  useEffect(() => {
    const id = requestAnimationFrame(() => {
      try { selectRef.current?.focus({ preventScroll: true }); } catch (_) {}
    });
    return () => cancelAnimationFrame(id);
  }, []);
  // Track the success-toast → onSubmitted handoff timer so we can cancel
  // it on unmount. Pre-fix, if a user closed the drawer (or navigated
  // away) inside the 1.5s "Report received" delay, the timeout still
  // fired onSubmitted on an unmounted drawer — re-opening the parent's
  // ReportTarget state from a stale callback and stomping focus.
  const submittedTimerRef = useRef(null);
  useEffect(() => () => {
    if (submittedTimerRef.current) {
      clearTimeout(submittedTimerRef.current);
      submittedTimerRef.current = null;
    }
  }, []);
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
      submittedTimerRef.current = setTimeout(() => {
        submittedTimerRef.current = null;
        onSubmitted();
      }, 1500);
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
      ref: panelRef,
      className: 'cart-confirm-panel',
      style: { maxWidth: 420 },
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-modal': true,
      'aria-labelledby': 'report-listing-title-' + listing.id
    },
      h('div', { id: 'report-listing-title-' + listing.id, className: 'cart-confirm-title' }, 'Report listing'),
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
              ref: selectRef,
              className: 'price-input',
              'aria-label': 'Reason',
              style: { width: '100%', marginBottom: 12 },
              value: reason,
              onChange: e => setReason(e.target.value),
              disabled: busy
            }, reasons.map(r => h('option', { key: r, value: r }, r))),
            h('div', {
              style: { display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 6 }
            },
              h('span', { style: { fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Details (optional)'),
              h('span', { style: { fontSize: 10, color: 'var(--text-muted)', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } },
                `${note.length}/${MAX_NOTE}`)
            ),
            h('textarea', {
              className: 'price-input',
              style: { width: '100%', minHeight: 70, marginBottom: 6, resize: 'vertical' },
              'aria-label': 'Optional moderator note',
              placeholder: 'Add any extra context that would help moderators. Ctrl+Enter submits.',
              value: note,
              maxLength: MAX_NOTE,
              onChange: e => setNote(e.target.value),
              onKeyDown: (e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !busy) {
                  e.preventDefault(); submit();
                }
              },
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

// ── Mark Sent drawer (batch 816) ─────────────────────────────────
// Replaces the legacy window.prompt() on the seller's Mark Sent
// action. The drawer gives the URL input a proper paste surface +
// live validation (mirrors the server-side regex in
// `TradeService.sellerMarkSent` so users see green-ticked valid URLs
// before submit), shows the trade context (buyer + item + price) so
// a seller with multiple open trades doesn't mis-mark, and has an
// explicit "Skip for now" button so the legacy "dismiss = still
// marks sent" behaviour stays available for sellers who already
// messaged the buyer a link over Discord.
function MarkSentDrawer({ trade, onCancel, onSubmit }) {
  // Batch 1167 — replaced useEscapeToClose with useDialogA11y so the
  // drawer now also traps Tab inside the panel and restores focus to
  // the row's "Mark sent" button on close. Stable-callback wrapper
  // for the same render-stability reason as ReportListingDrawer above.
  const panelRef = useRef(null);
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const stableCancel = useCallback(() => onCancelRef.current && onCancelRef.current(), []);
  useDialogA11y(panelRef, stableCancel);
  const [url, setUrl]   = useState('');
  const [busy, setBusy] = useState(false);
  // Synchronous re-entrancy latch — a same-tick double-click on "Mark sent" /
  // "Skip for now" must not fire onSubmit twice before React commits busy=true
  // (the disabled attr is async). Parent submitMarkSent already guards, but every
  // money-adjacent submit gets its own ref latch (matches ReportCounterpartyDrawer).
  const busyRef = useRef(false);
  const [err, setErr]   = useState('');
  // Live validation regex matches the server-side check.
  const URL_RE = /^https:\/\/steamcommunity\.com\/tradeoffer\/[A-Za-z0-9_?&=/\-]+$/;
  const trimmed = (url || '').trim();
  const valid = trimmed.length === 0 || URL_RE.test(trimmed);
  // Try to surface an auto-paste suggestion from the clipboard if it
  // already holds a Steam trade-offer URL — the user just pasted it
  // into the Steam window, so it's likely still there. Silent fallback
  // if clipboard permission is denied. `alive` flag prevents a setUrl
  // call after the drawer was dismissed while the clipboard read was
  // still pending (would warn about setState on unmounted component).
  useEffect(() => {
    if (typeof navigator === 'undefined' || !navigator.clipboard?.readText) return;
    let alive = true;
    navigator.clipboard.readText().then(t => {
      if (alive && t && URL_RE.test(t.trim())) setUrl(t.trim());
    }).catch(() => { /* denied — silent */ });
    return () => { alive = false; };
  }, []);
  const submit = async (override) => {
    // `override` lets the "Skip for now" button reuse the same busy-flag
    // guard (and short-circuit URL validation) without duplicating the
    // try/finally. Without this, rapid double-clicks on Skip fired
    // submitMarkSent twice before React tore down the drawer.
    // Only a STRING override (the "" from Skip-for-now) counts. The primary
    // button is wired `onClick: () => submit()`, but harden here too: if a
    // caller ever passes the click SyntheticEvent, treat it as no override so
    // we validate + send the typed URL instead of `event.trim()`-ing a crash.
    const hasOverride = typeof override === 'string';
    const value = hasOverride ? override : trimmed;
    if (!hasOverride && !valid) { setErr('That doesn\'t look like a Steam trade-offer URL.'); return; }
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try { await onSubmit(value); }
    finally { setBusy(false); busyRef.current = false; }
  };
  return h('div', {
    className: 'cart-confirm-backdrop',
    onClick: () => !busy && onCancel(),
    style: { zIndex: 100 }
  },
    h('div', {
      ref: panelRef,
      className: 'cart-confirm-panel',
      style: { maxWidth: 480 },
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-modal': true,
      'aria-labelledby': 'mark-sent-title-' + trade.id
    },
      h('div', { id: 'mark-sent-title-' + trade.id, className: 'cart-confirm-title' }, 'Mark trade as sent'),
      h('div', { className: 'cart-confirm-sub', style: { marginBottom: 14 } },
        'You just sent the Steam offer to ',
        h('strong', null, trade.counterpartyName || `#${trade.buyerUserId || '?'}`),
        trade.itemName ? h('span', null, ' for ', h('strong', null, trade.itemName)) : null,
        trade.price ? h('span', null, ' (', fmt(trade.price), ')') : null, '.'),
      h('div', { className: 'wallet-input-label', style: { marginTop: 4 } },
        'Steam offer URL (optional)'),
      h('input', {
        className: 'wallet-amount-input',
        type: 'url',
        autoComplete: 'off',
        autoCapitalize: 'off',
        spellCheck: false,
        'aria-label': 'Steam offer URL',
        placeholder: 'https://steamcommunity.com/tradeoffer/1234567/',
        value: url,
        onChange: e => { setUrl(e.target.value); setErr(''); },
        style: valid ? undefined : { borderColor: 'var(--red)' }
      }),
      h('div', {
        style: {
          fontSize: 11, marginTop: 6,
          color: trimmed.length === 0 ? 'var(--text-muted)'
               : valid             ? 'var(--green)'
               : 'var(--red)',
          lineHeight: 1.5
        }
      },
        trimmed.length === 0
          ? 'Paste the URL from the Steam offer you just sent — lets the buyer open it in one click. Skip if you can\'t grab it easily.'
          : valid
            ? 'Looks valid. Submit to mark the trade sent and ping the buyer.'
            : 'Must start with https://steamcommunity.com/tradeoffer/...'),
      err && h('div', { className: 'wallet-error', style: { marginTop: 6 } }, err),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 14, flexWrap: 'wrap' } },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 12 },
          disabled: busy,
          onClick: onCancel
        }, 'Cancel'),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 12,
                   marginLeft: 'auto' },
          disabled: busy,
          onClick: () => submit(''),
          title: 'Mark sent without attaching a URL — buyer sees the generic Steam inbox link'
        }, 'Skip for now'),
        h('button', {
          className: 'btn btn-accent',
          style: { padding: '6px 14px', fontSize: 12 },
          disabled: busy || !valid,
          onClick: () => submit()
        }, busy ? 'Marking…' : 'Mark sent')
      )
    )
  );
}

// ── Report counterparty drawer (batch 817) ───────────────────────
// Replaces the two-step window.prompt flow on a trade row's "Report"
// button. Structured reason picker (so support can triage incoming
// reports by category) + pre-filled context textarea carrying trade
// #id + item name so the user doesn't have to re-type the context.
// Shape mirrors ReportListingDrawer so the visual idiom stays
// consistent across the site.
function ReportCounterpartyDrawer({ trade, onCancel, onSubmitted }) {
  // Batch 1167 — replaced useEscapeToClose with useDialogA11y for Tab
  // focus trap + restore-focus on close. Stable-callback wrapper for
  // the same render-stability reason as ReportListingDrawer above.
  const panelRef = useRef(null);
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const stableCancel = useCallback(() => onCancelRef.current && onCancelRef.current(), []);
  useDialogA11y(panelRef, stableCancel);
  const REASONS = ['Scam attempt','Harassment in chat','Impersonation','Other'];
  const [reason, setReason] = useState(REASONS[0]);
  const [note, setNote]     = useState(`Trade #${trade.id} · item ${trade.itemName || '—'}`);
  const [busy, setBusy]     = useState(false);
  // Batch 1078 — synchronous re-entrancy latch; async setBusy can't stop
  // a same-frame double-click, and reportUser (SupportController#reportUser
  // -> SupportService.create) has no dedupe, so a double-click filed TWO
  // user-report tickets + doubled the admin/CSR bell fan-out. Synced to
  // busy each render; auto-resets after the finally's setBusy(false).
  const busyRef = useRef(busy); busyRef.current = busy;
  const [err, setErr]       = useState('');
  const counterparty = trade.counterpartyName ||
    (trade.__isSeller ? `buyer #${trade.buyerUserId || '?'}` : `seller #${trade.sellerUserId || '?'}`);
  const submit = async () => {
    if (!trade.__target) { setErr('Missing counterparty id'); return; }
    if (busyRef.current) return;
    busyRef.current = true;
    setErr(''); setBusy(true);
    try {
      const { reportUser } = await import('./api.js');
      const res = await reportUser(trade.__target, reason, note);
      if (res && (res.error || res.code)) {
        setErr(res.message || res.error || 'Could not file report');
        return;
      }
      toast('Report filed — Support will review, track in /support.', 'ok');
      onSubmitted && onSubmitted();
    } finally { setBusy(false); }
  };
  return h('div', {
    className: 'cart-confirm-backdrop',
    onClick: () => !busy && onCancel(),
    style: { zIndex: 100 }
  },
    h('div', {
      ref: panelRef,
      className: 'cart-confirm-panel',
      style: { maxWidth: 460 },
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-modal': true,
      'aria-labelledby': 'report-counterparty-title-' + trade.id
    },
      h('div', { id: 'report-counterparty-title-' + trade.id, className: 'cart-confirm-title' }, 'Report counterparty'),
      h('div', { className: 'cart-confirm-sub', style: { marginBottom: 14 } },
        'Trade #', String(trade.id), ' · ',
        h('strong', null, counterparty),
        trade.itemName ? h('span', null, ' · ', trade.itemName) : null),
      h('div', {
        style: {
          fontSize: 11, color: 'var(--text-secondary)', marginBottom: 12,
          padding: '8px 10px', borderRadius: 6,
          background: 'rgba(96,165,250,0.08)',
          border: '1px solid rgba(96,165,250,0.25)', lineHeight: 1.5
        }
      },
        'Reports open a support ticket and are reviewed by staff. ',
        'For a dispute over the trade itself (item not received / wrong item) use ',
        h('strong', null, 'Dispute'), ' instead so the trade state machine can resolve it.'),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6,
                          textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Reason'),
      h('select', {
        className: 'price-input',
        'aria-label': 'Report reason',
        style: { width: '100%', marginBottom: 12 },
        value: reason,
        onChange: e => setReason(e.target.value),
        disabled: busy
      }, REASONS.map(r => h('option', { key: r, value: r }, r))),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6,
                          textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } },
        'Context — what happened? ',
        h('span', { style: { textTransform: 'none', color: 'var(--text-muted)', fontWeight: 400 } },
          `(${note.length}/1000)`)),
      h('textarea', {
        className: 'price-input',
        style: { width: '100%', minHeight: 90, marginBottom: 4, resize: 'vertical',
                 fontFamily: 'inherit', fontSize: 13 },
        'aria-label': 'Report context (what happened)',
        placeholder: 'Include timestamps, chat snippets, screenshots links — anything that helps staff triage.',
        value: note,
        maxLength: 1000,
        onChange: e => setNote(e.target.value),
        disabled: busy
      }),
      h('div', {
        style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 12, textAlign: 'right' }
      }, note.length, ' / 1000'),
      err && h('div', { className: 'wallet-error', style: { marginBottom: 10 } }, err),
      h('div', { style: { display: 'flex', gap: 10, justifyContent: 'flex-end' } },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)' },
          disabled: busy,
          onClick: onCancel
        }, 'Cancel'),
        h('button', {
          className: 'btn btn-accent',
          disabled: busy || !note.trim(),
          onClick: submit
        }, busy ? 'Submitting…' : 'File report')
      )
    )
  );
}

// ── Trade dispute drawer ─────────────────────────────────────────
// Replaces the legacy prompt('Why are you disputing this trade?') with
// a structured picker — canonical reasons (so staff can triage by
// category) plus an optional context textarea (so the user can paste
// a Steam transcript / screenshot link / timeline). Mirrors the
// ReportListingDrawer shape so the visual idiom is consistent.
function DisputeTradeDrawer({ trade, onCancel, onSubmitted, isSeller }) {
  // Batch 1167 — focus trap + restore-focus via useDialogA11y. The
  // dispute drawer can freeze escrow, so a keyboard-only filer must
  // be able to dismiss it safely with Esc and have Tab cycle stay
  // inside the form rather than bleed into the trades grid behind.
  // Stable-callback wrapper for the same render-stability reason as
  // ReportListingDrawer above.
  const panelRef = useRef(null);
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const stableCancel = useCallback(() => onCancelRef.current && onCancelRef.current(), []);
  useDialogA11y(panelRef, stableCancel);
  const REASONS = isSeller
    ? [
        'Buyer claimed receipt but Steam offer was never accepted',
        'Buyer is harassing me in chat',
        'Buyer is attempting to scam (re-trade / charge-back threat)',
        'Other'
      ]
    : [
        'Item not received',
        'Item differs from listing description / photo',
        'Seller is unresponsive past the deadline',
        'Suspected scam (e.g. fake Steam offer, swapped item)',
        'Other'
      ];
  const [reason, setReason] = useState(REASONS[0]);
  const [note, setNote]     = useState('');
  const [busy, setBusy]     = useState(false);
  const [err, setErr]       = useState('');
  // Synchronous re-entrancy latch — filing a dispute freezes escrow + opens an
  // irreversible DISPUTED state + notifies staff; a double-click must not file
  // twice. Async `busy` alone leaves a window; gate on a ref. Synced each render.
  const busyRef = useRef(busy); busyRef.current = busy;
  const submit = async () => {
    if (!reason) { setErr('Pick a reason first'); return; }
    if (busyRef.current) return;
    busyRef.current = true;
    setErr(''); setBusy(true);
    try {
      // Compose reason + note into one server-side string (the trade
      // dispute endpoint takes a single `reason` field). Use a
      // structured prefix so staff can grep the audit log later.
      const trimmed = (note || '').trim();
      const composed = trimmed.length > 0
        ? `${reason}\n\n${trimmed}`
        : reason;
      const { tradeDispute } = await import('./api.js');
      const res = await tradeDispute(trade.id, composed);
      if (res && (res.error || res.code)) {
        setErr(res.message || res.error || 'Could not file dispute');
        return;
      }
      onSubmitted && onSubmitted();
    } catch (_) {
      setErr('Network error — try again');
    } finally { setBusy(false); }
  };
  return h('div', {
    className: 'cart-confirm-backdrop',
    onClick: onCancel,
    style: { zIndex: 100 }
  },
    h('div', {
      ref: panelRef,
      className: 'cart-confirm-panel',
      style: { maxWidth: 460 },
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-modal': true,
      'aria-labelledby': 'dispute-trade-title-' + trade.id
    },
      h('div', { id: 'dispute-trade-title-' + trade.id, className: 'cart-confirm-title' }, 'Dispute this trade'),
      h('div', { className: 'cart-confirm-sub', style: { marginBottom: 14 } },
        `Trade #${trade.id} · ${trade.itemName || 'Item'}`,
        trade.price != null && ` · ${fmt(trade.price)}`),
      h('div', {
        style: {
          background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.3)',
          color: 'var(--text-secondary)', padding: 10, borderRadius: 6,
          fontSize: 12, marginBottom: 14, lineHeight: 1.5
        }
      }, 'Disputing freezes the escrow — funds stay locked until staff reviews. ',
        'Use this only when the counterparty has actually broken the deal. False disputes are tracked.'),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Reason'),
      h('select', {
        className: 'price-input',
        'aria-label': 'Dispute reason',
        style: { width: '100%', marginBottom: 12 },
        value: reason,
        onChange: e => setReason(e.target.value),
        disabled: busy
      }, REASONS.map(r => h('option', { key: r, value: r }, r))),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 700 } }, 'Details (optional, helps staff)'),
      h('textarea', {
        className: 'price-input',
        style: { width: '100%', minHeight: 90, marginBottom: 4, resize: 'vertical' },
        'aria-label': 'Report details (optional)',
        placeholder: 'Paste the Steam offer link, a transcript, or a timeline. The more context, the faster staff can resolve.',
        value: note,
        maxLength: 1500,
        onChange: e => setNote(e.target.value),
        disabled: busy
      }),
      // Char counter — the 1500-cap isn't obvious until the user hits it,
      // so surface the count in the same way the refund drawer does.
      h('div', {
        style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 12, textAlign: 'right' }
      }, note.length, ' / 1500'),
      err && h('div', { className: 'wallet-error', style: { marginBottom: 10 } }, err),
      h('div', { style: { display: 'flex', gap: 10, justifyContent: 'flex-end' } },
        h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: busy, onClick: onCancel }, 'Cancel'),
        h('button', { className: 'btn btn-accent', style: { background: 'var(--red)', borderColor: 'var(--red)' }, disabled: busy, onClick: submit },
          busy ? 'Filing…' : 'File dispute')
      )
    )
  );
}

// ── Affiliate Program ────────────────────────────────────────────
// Mirrors CSFloat §21 (text manual) + §31 (visual manual). Marketing
// page for content creators who want to refer buyers/sellers. Pure
// presentation — no backend until a real affiliate program launches.
export function AffiliateModal({ onClose }) {
  // Custody is decided at runtime by whether the escrow bot is configured, so
  // the "About Us" blurb below asks the server rather than asserting it.
  const custody = useCustodyCopy();
  /* N3 cycle 9 — boss QA restated: chip row reads compact, promote to
     full .stall-stat-grid pattern (icon top, bold value middle,
     tracked-out caps label below). Same grid CSS as /stall/<n> hero,
     just inverted vertical order so the value dominates. */
  const REQUIREMENTS = [
    { platform: 'YouTube',   icon: 'smart_display',  value: '5,000', unit: 'subscribers' },
    { platform: 'X',         icon: 'tag',            value: '5,000', unit: 'followers' },
    { platform: 'TikTok',    icon: 'music_note',     value: '5,000', unit: 'followers' },
    { platform: 'Instagram', icon: 'photo_camera',   value: '5,000', unit: 'followers' },
    { platform: 'Website',   icon: 'language',       value: '5,000', unit: 'monthly users' }
  ];
  return h(InfoModal, { title: 'Affiliate Program', onClose },
    // Flat editorial hero — mono-primary eyebrow + Fraunces display.
    h('div', {
      style: {
        marginBottom: 22,
        padding: '32px 24px',
        borderRadius: 10,
        background: 'var(--bg-1)',
        border: '1px solid var(--line-2)',
        textAlign: 'center'
      }
    },
      h('div', { style: { fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', letterSpacing: '0.16em', textTransform: 'uppercase', marginBottom: 8 } }, 'SkinBox'),
      h('div', { style: { fontFamily: 'var(--serif)', fontSize: 34, fontWeight: 360, letterSpacing: '-0.02em', color: 'var(--ink)', fontVariationSettings: '"opsz" 144' } }, 'Affiliate Program')
    ),
    h('div', { style: { color: 'var(--text-secondary)', lineHeight: 1.6, fontSize: 14, marginBottom: 24 } },
      'The SkinBox Affiliate Program pays you a share of the platform fees from every user you refer. Earnings are credited to your SkinBox wallet and can be cashed out to Stripe like any other sale proceeds.'),

    /* W1 cycle 9 — fixed 1fr 1fr on mobile pushed Requirements card off
       the viewport (393px / 2 = 187px is too narrow once the 140px-min
       stat-tile grid kicks in). Switch to auto-fit with a 360px floor
       so the two cards stack on phones and sit side-by-side from
       tablet up. */
    h('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 360px), 1fr))', gap: 14, marginBottom: 22 } },
      h('div', { style: { padding: 16, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10 } },
        h('div', { style: { fontWeight: 700, color: 'var(--text-primary)', marginBottom: 10, fontSize: 14 } }, 'About Us'),
        h('div', { style: { fontSize: 12.5, color: 'var(--text-secondary)', lineHeight: 1.6 } },
          `SkinBox is a peer-to-peer marketplace for s&box cosmetic items. We're building the tools s&box traders have been asking for — a real marketplace grid, auctions with live bidding, standing buy orders, ${custody.shortLabel} escrow, and a wallet that pays out in under 2 business days. We want affiliates who share that mission.`)
      ),
      h('div', { style: { padding: 16, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10 } },
        h('div', { style: { fontWeight: 700, color: 'var(--text-primary)', marginBottom: 10, fontSize: 14 } }, 'Requirements'),
        h('div', { style: { fontSize: 11.5, color: 'var(--text-muted)', marginBottom: 14, lineHeight: 1.5 } },
          'We require our affiliate partners to focus primarily on s&box content and meet at least one of the platform thresholds below.'),
        /* N3 cycle 9 — stall-stat-grid promotion. Each platform is a
           tile: Material icon centered top, bold mono value (≥5,000)
           in the middle, tracked-out caps platform label at the
           bottom. Reuses .stall-stat shell (auto-fill grid w/ 140px
           min) so spacing matches /stall/<n>. */
        h('div', { className: 'stall-stat-grid affiliate-req-grid' },
          REQUIREMENTS.map(r => h('div', {
            key: r.platform,
            className: 'stall-stat affiliate-req-stat',
            style: { alignItems: 'center', textAlign: 'center', padding: '14px 12px' }
          },
            h('div', { style: {
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              width: 36, height: 36, borderRadius: 8,
              background: 'var(--bg-1)', border: '1px solid var(--line)',
              marginBottom: 4
            } },
              h(MaterialIcon, { name: r.icon, size: 20 })
            ),
            h('div', {
              className: 'stall-stat-val',
              style: { fontSize: 16, letterSpacing: '-0.01em' }
            },
              h('span', { style: { color: 'var(--ink-3)', fontWeight: 500, fontSize: 13, marginRight: 3 } }, '≥'),
              r.value
            ),
            h('div', {
              className: 'stall-stat-label',
              style: { marginTop: 2 }
            }, r.platform),
            h('div', {
              style: {
                fontSize: 10.5, color: 'var(--ink-4)', marginTop: 2,
                letterSpacing: 0, textTransform: 'none', fontFamily: 'inherit',
                fontWeight: 400
              }
            }, r.unit)
          ))
        )
      )
    ),

    h('div', {
      style: {
        padding: '18px 20px',
        borderRadius: 10,
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        gap: 12
      }
    },
      h('div', null,
        h('div', { style: { fontWeight: 700, color: 'var(--text-primary)', fontSize: 14 } }, 'Ready to apply?'),
        h('div', { style: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 4 } },
          "Email us with your platform(s), audience size, and a link or two. We review every application within a week.")
      ),
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
        h('input', {
          readOnly: true,
          value: 'affiliate@skinbox.market',
          className: 'price-input',
          'aria-label': 'Affiliate program contact email — click to select',
          style: { width: 220, fontSize: 12.5, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" },
          onClick: e => e.target.select()
        }),
        h('a', {
          className: 'btn btn-accent',
          href: 'mailto:affiliate@skinbox.market?subject=SkinBox%20Affiliate%20Application',
          style: { padding: '10px 18px', fontWeight: 700 }
        }, 'Apply')
      )
    )
  );
}

// ── FAQ / Support ───────────────────────────────────────────────
export function FaqModal({ onClose }) {
  // Batch 805 — read `?q=` from the URL so deep-links like
  // `/faq?q=fees` open the modal with the search already applied
  // (and filter down to the answer the link author intended). Matches
  // the behaviour of the Help Center modal (help-modal.js) which has
  // always honoured the param. Query stays in place — no replaceState
  // here because the user may navigate back from within the modal.
  const initialQ = (() => {
    try { return (new URLSearchParams(window.location.search).get('q') || '').slice(0, 60); }
    catch { return ''; }
  })();
  const [q, setQ] = useState(initialQ);
  const ENTRIES = [
    ['What is SkinBox?',
      "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Every listing comes from a real seller who sets their own price — we're the middle layer that makes transactions safe, fast, and cheaper than going through the Steam store."],
    ['How do I sign in?',
      "Click the ‘Sign in through Steam’ button in the top-right. You'll bounce to steamcommunity.com, approve the login, and land back here already authenticated. Your Steam password never touches our servers — everything goes through OpenID."],
    ['How do I buy something?',
      "Top up your wallet first, then click any item and hit Buy. Funds are charged from your balance instantly — there's no bid-and-wait or 7-day trade hold like the Steam market."],
    ['How does depositing work?',
      "Open your Wallet, pick Deposit, enter an amount (anything from $1 to $10,000), and you'll be handed to Stripe's checkout page. Once the payment clears, our webhook credits your balance automatically."],
    ['How do withdrawals work?',
      "From your Wallet, pick Withdraw and the amount. The first time, Set up cash-out links your bank or debit card through Stripe; after that every payout goes there. Your balance is debited and the payout is sent to that account right away; Stripe then pays your bank, usually within 1–2 business days. Any payout processing fee is shown as \"You receive\" before you confirm."],
    ['Why is SkinBox cheaper than Steam?',
      "Steam charges 12% in platform fees on Workshop sales and forces sellers into their pricing ladder. On SkinBox, sellers set whatever price they like — usually 10-30% below what the Steam store asks. The green '−%' chip on each card shows exactly how much you save versus Steam."],
    ['Do s&box items have wear levels?',
      "No. That's a Counter-Strike thing. s&box cosmetics are single items without Factory-New / Field-Tested / Battle-Scarred variants — closer to how Rust skins work. The item you pick is the exact item you receive."],
    ['Can I sell the items I own?',
      "Yes. Anything you've bought on SkinBox appears under Sell Items. Pick an item, set a price, and it goes live in your stall under My Stall. When it sells, the buyer's payment (minus a 2% platform fee) drops straight into your wallet."],
    ['What is a Stall?',
      "Your Stall is your personal storefront — the list of items you currently have up for sale. Other users can browse it via your profile. You can cancel any listing from My Stall and the item returns to your inventory."],
    ['What are Offers?',
      "Offers are non-binding price suggestions. A buyer can propose less than your asking price; you get a notification and can accept or reject from the Offers tab."],
    ['Are there bulk actions for cleaning up my account?',
      "Yes — heavy users can reset each list in one click: Profile → Buy Orders has Cancel all active, Profile → Offers → Outgoing has Cancel all pending, Profile → Active Bids has Stop all auto-raises, Profile → Personal → Following has ✕ all and 🔔/🔕 all, and the Watchlist page has Clear all. Each asks to confirm first."],
    ['Is my money safe?',
      "Deposits go through Stripe, the same processor used by millions of websites. We never store card details — only the amount and a Stripe reference. Withdrawal requests are logged and reviewed before payout. All balances are held in USD."],
    ['What happens if I dispute a deposit with my bank?',
      "Stripe notifies us within seconds. We freeze the disputed amount, pause your withdrawals while the dispute is open, and review the case. If your bank rules in your favour we lose the deposit and you keep the items / balance. If we win the dispute we credit you again and lift the hold. Filing a chargeback for a deposit you actually received counts as friendly fraud and can get your account banned permanently."],
    ['What are Auctions and how do they work?',
      "Some listings are auctions instead of Buy Now — they carry a countdown clock and a current bid. Place a bid (the minimum is one increment above the current top — the increment scales with price, from $0.05 on cheap items up to $100 on four-figure ones), and if you're winning when the clock hits zero you pay your winning bid from your wallet. Anti-snipe: bids placed in the last 30 seconds extend the close by 30 seconds so sniping is blunted. You can set an auto-raise cap (we'll outbid rivals up to your max) and cancel the cap any time — your current bid stays live, we just stop auto-raising. Use the \"Ending soonest\" marketplace sort to find last-call auctions."],
    ['What are Buy Orders?',
      "A standing \"I'll pay up to $X for this item\" request. When a seller lists (or drops their price to) at-or-below your max, our matcher auto-buys and opens an escrowed trade — no action needed on your side. Great for items you keep missing on drops. Cap: 200 active orders per account. Projected queue position shows up in the form so you know if you'd be #1 or stuck at #8 under current demand."],
    ['What is the Watchlist and what are Price Alerts?',
      "Click the ★ on any item card to add it to your Watchlist — a saved browsing shortcut. Separately, open an item and click \"Set Price Alert\" with a target price: you'll get a notification (and optionally an email) the instant an ACTIVE listing dips to or below your target. Alerts stay ACTIVE until they fire once (then flip to FIRED) or until you cancel. Max 50 active alerts per user."],
    ['What is the Loadout Lab?',
      "Curate up to 8 slots of s&box cosmetics (Hat / Shirt / Pants / Gloves / Boots / Accessories / etc.) into a named look. Publish publicly for discovery + favorites, or keep private while you tweak. \"Auto-generate from budget\" fills empty unlocked slots with the cheapest active listings that fit your total budget. Lock slots you've curated, let Auto-gen handle the rest. Cap: 50 loadouts per account."],
    ['Can I hide my wallet balance while streaming or on camera?',
      "Yes — the nav wallet chip shows \"$•••••\" in privacy mode. Toggle via Ctrl+click on the wallet button in the nav, or from Settings → Privacy. Privacy state syncs across open tabs and applies to the full Wallet modal, pending-withdraw chips, spend strip, Profile recent-purchases, MyStall earnings, and the cart total — every user-visible dollar amount in the product."],
    ['I found a bug / my purchase is stuck',
      "Head to the Support tab in your profile (or click the Support link in the user menu) and open a ticket. Include the transaction id from your Trades tab and we'll refund or retry as needed."]
  ];
  const t = q.trim().toLowerCase();
  const filtered = t ? ENTRIES.filter(([qq, aa]) => (qq + ' ' + aa).toLowerCase().includes(t)) : ENTRIES;
  return h(InfoModal, { title: 'FAQ', onClose },
    // Boss QA F1 — slim 48px-tall single-line banner so the FAQ
    // questions sit above the fold. The previous block was a wrapping
    // multi-line ad with the support link buried mid-sentence; the
    // shipped copy now reads as a one-liner with the CTA chained on.
    h('div', { className: 'faq-support-banner' },
      h('span', null, "Can't find an answer?"),
      h('a', {
        href: '/support',
        onClick: (e) => { e.preventDefault(); onClose && onClose(); navigate('/support'); }
      }, 'Open a support ticket →')
    ),
    ENTRIES.length > 4 && h('input', {
      className: 'price-input',
      style: { width: '100%', fontSize: 13, marginBottom: 18 },
      placeholder: 'Search FAQs…',
      value: q,
      onChange: e => setQ(e.target.value),
      'aria-label': 'Search FAQ'
    }),
    filtered.length === 0
      ? h('div', {
          // Batch 928 — richer zero-match state with Clear + Open-ticket
          // CTAs, matching the Help Center modal's upgrade in the same
          // batch. Users no longer dead-end on a search box.
          style: { fontSize: 13, color: 'var(--text-secondary)', padding: '20px 10px', textAlign: 'center' }
        },
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            'No FAQs match "', h('strong', { style: { color: 'var(--accent)' } }, q.trim()), '"'),
          h('div', { style: { maxWidth: 420, margin: '0 auto 14px', lineHeight: 1.55 } },
            'Try a broader keyword, or open a ticket and a CSR will reply within the hour.'),
          h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
            h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 12 },
              onClick: () => setQ('')
            }, 'Clear search'),
            h('a', {
              href: '/support',
              className: 'btn btn-accent',
              style: { padding: '8px 14px', fontSize: 12 },
              onClick: (e) => { e.preventDefault(); onClose && onClose(); navigate('/support'); }
            }, 'Open a ticket →')
          )
        )
      : filtered.map(([question, answer]) => h('div', { key: question, style: { marginBottom: 20, textAlign: 'left' } },
          // h3 (not <div>) so screen-reader heading-list lets users skim
          // questions without reading every answer. Visual styling matches
          // the previous div: bold ink-primary, 14px, 6px bottom margin.
          h('h3', { style: { fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 6px', fontSize: 14 } }, highlightMatch(question, q)),
          h('div', { style: { color: 'var(--text-secondary)', lineHeight: 1.6, fontSize: 13 } }, highlightMatch(answer, q))
        ))
  );
}

// ── Settings ────────────────────────────────────────────────────
export function SettingsModal({ onClose, me }) {
  const [currency, setCurrency] = useState(localStorage.getItem('sb_currency') || 'USD');
  const [notifs, setNotifs]     = useState(localStorage.getItem('sb_notifs') !== 'false');
  const [sounds, setSounds]     = useState(localStorage.getItem('sb_sounds') !== 'false');
  // Muted notification buckets — values match the `typeOf(kind)` classifier
  // in csfloat-modals.js: TRADES / AUCTIONS / OFFERS / MATCHES / WALLET / OTHER.
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
    try {
      const serialised = JSON.stringify([...next]);
      localStorage.setItem('sb_mute_kinds', serialised);
      // Dispatch a synthetic storage event so the nav bell + any other
      // consumer of sb_mute_kinds repaints immediately instead of waiting
      // for the 25s poll. Without this a user who mutes "Wallet" still
      // sees wallet notifications in the bell dropdown and the badge
      // count until the next poll lands.
      window.dispatchEvent(new StorageEvent('storage', {
        key: 'sb_mute_kinds', newValue: serialised
      }));
    } catch (_) {}
  };
  const [reduceMotion, setRM]   = useState(localStorage.getItem('sb_reduce_motion') === '1');
  const [highContrast, setHC]   = useState(localStorage.getItem('sb_contrast') === '1');
  // Privacy toggle — masks $ amounts (wallet hero, profile earnings,
  // MyStall earnings strip, the under-balance chip). Stored in
  // localStorage and read by every consumer that already honours
  // sb_privacy, so toggling here propagates without any extra wiring.
  const [privacy, setPrivacy]   = useState(localStorage.getItem('sb_privacy') === '1');
  // Codex 17:28Z polish — Sound effects "Test" button was audio-only and
  // appeared dead if the audio context was blocked by the browser (autoplay
  // policy, muted tab, or no Web Audio API at all). Surface a short status
  // message next to the button so a click without audible feedback still
  // shows "Played", "Audio blocked — interact with the page first", or
  // "Audio not supported." Auto-clears after 3s so it doesn't linger.
  const [soundTestStatus, setSoundTestStatus] = useState(null);
  useEffect(() => {
    if (!soundTestStatus) return undefined;
    const id = setTimeout(() => setSoundTestStatus(null), 3000);
    return () => clearTimeout(id);
  }, [soundTestStatus]);

  useEffect(() => {
    localStorage.setItem('sb_currency', currency);
    // Same-tab storage events don't fire automatically — without this
    // synthetic dispatch the footer "All prices in …" copy and the
    // NavPicker currency-aware re-renders (app.js:3758) only update on
    // the next render cycle / tab focus. Match the pattern used by the
    // NavPicker (app.js:5423) and the sb_privacy effect below.
    try {
      if (typeof window !== 'undefined' && window.SBOX_CURRENCY !== undefined) {
        window.SBOX_CURRENCY = currency;
      }
      window.dispatchEvent(new StorageEvent('storage', {
        key: 'sb_currency', newValue: currency
      }));
    } catch (_) {}
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
  // Cross-tab sync: a write to localStorage doesn't fire a 'storage'
  // event in the SAME tab, so other consumers (wallet hero, profile,
  // mystall) won't repaint until the next render. Dispatch a synthetic
  // event so the existing 'storage' listeners pick up the new value
  // immediately. Same pattern used by the profile-side privacy toggle.
  useEffect(() => {
    localStorage.setItem('sb_privacy', privacy ? '1' : '0');
    try {
      window.dispatchEvent(new StorageEvent('storage', {
        key: 'sb_privacy', newValue: privacy ? '1' : '0'
      }));
    } catch (_) {}
  }, [privacy]);

  const Row = (label, sublabel, control) => h('div', { className: 'settings-row' },
    h('div', null,
      h('div', { className: 'settings-label' }, label),
      sublabel && h('div', { className: 'settings-sublabel' }, sublabel)
    ),
    control
  );
  // Visible h2 section headings give screen reader users a navigable
  // landmark structure on /settings (rotor / heading-list shortcut)
  // and help sighted users group related preferences at a glance.
  // Mono-uppercase 11px to match the existing rail / sidebar headings
  // (`.just-listed-head`, `.filter-title`) elsewhere on the site.
  const Section = (title) => h('h2', { className: 'settings-section-heading' }, title);
  // Accessible switch: role+aria-checked expose the on/off state to screen
  // readers, aria-label names it (the visible Row label sits in a sibling
  // node so it isn't the control's accessible name), and tabIndex + the
  // Enter/Space key handler make it keyboard-operable — a bare onClick <div>
  // is mouse-only and invisible to assistive tech. The .toggle-switch
  // :focus-visible ring already exists in design.css, awaiting a focusable
  // host. The sibling away-mode toggle already shipped role:'switch'.
  const Toggle = (on, onChange, label) => h('div', {
    className: `toggle-switch ${on ? '' : 'off'}`,
    role: 'switch',
    'aria-checked': on ? 'true' : 'false',
    'aria-label': label,
    tabIndex: 0,
    onClick: onChange,
    onKeyDown: (e) => {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
        e.preventDefault();
        onChange(e);
      }
    }
  });

  const resetLocal = () => {
    if (!confirm('Reset UI preferences (currency, theme, reduce-motion, high-contrast, sounds, notification mutes, cookie consent)? Watchlist and cart are kept.')) return;
    ['sb_currency','sb_notifs','sb_sounds','sb_mute_kinds','sb_reduce_motion','sb_contrast','sb_theme','sb_privacy','sb_cookie_consent']
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

  // Boss QA G6 — anonymous viewers can change Display + Accessibility
  // preferences (currency, reduce-motion, high-contrast — all stored in
  // localStorage), but Notifications + sound bell-mute belongs to the
  // signed-in account. Show a clearly-labelled sign-in nudge in place
  // of the notification panel so anon users aren't toggling an opt-out
  // for events they can't receive yet.
  return h(InfoModal, { title: 'Settings', onClose },
    !me && h('div', {
      style: {
        marginBottom: 18, padding: '12px 16px',
        background: 'var(--accent-dim)',
        border: '1px solid var(--accent-border)',
        borderRadius: 8,
        fontSize: 12, color: 'var(--text-secondary)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        gap: 12, flexWrap: 'wrap'
      }
    },
      h('span', null,
        h('strong', { style: { color: 'var(--text-primary)' } }, 'Display & Accessibility only'),
        ' — sign in with Steam to manage notifications, sound mutes, and per-account preferences.'),
      h('button', {
        className: 'btn btn-accent',
        style: { padding: '6px 14px', fontSize: 12 },
        onClick: () => signInWithSteam()
      }, 'Sign in with Steam')
    ),
    Section('Display'),
    Row('Currency', 'Prices shown in your chosen currency (stored as USD)', h('select', {
        className: 'sort-select', value: currency,
        'aria-label': 'Display currency',
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
    me && Section('Notifications & sound'),
    me && Row('Sale notifications', 'Toast when someone buys', Toggle(notifs, () => setNotifs(v => !v), 'Sale notifications')),
    // Batch 819 — Sound-effects row now includes a "Send test
    // notification" button so users can hear the ding before deciding
    // whether to enable it. Bypasses the mute flag via `{force:true}`
    // so the button always plays regardless of the toggle state.
    // Boss QA G7 — relabelled the bare "Test" pill so it explicitly
    // names the action (was indistinguishable from a dev artifact).
    me && Row('Sound effects',      'Play sounds on actions',
      h('div', { style: { display: 'flex', alignItems: 'center', gap: 8 } },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '3px 10px', fontSize: 11 },
          title: 'Play the notification ding once (bypasses the mute toggle).',
          'aria-label': 'Send a test notification ding',
          onClick: async () => {
            try {
              const { playNotifyDing } = await import('./nav-widgets.js');
              // playNotifyDing is now async — it awaits AudioContext.resume()
              // before checking state, so a first-click in a fresh tab that
              // resumes the context now actually plays the ding instead of
              // reporting audio-blocked.
              const res = await playNotifyDing({ force: true });
              if (res && res.ok) {
                setSoundTestStatus({ kind: 'ok', text: 'Test ding played.' });
              } else if (res && res.reason === 'audio-blocked') {
                setSoundTestStatus({ kind: 'warn', text: 'Sound preview unavailable in this browser — interact with the page first.' });
              } else if (res && res.reason === 'no-audio-api') {
                setSoundTestStatus({ kind: 'err', text: 'Sound preview unavailable in this browser.' });
              } else {
                setSoundTestStatus({ kind: 'err', text: 'Sound preview unavailable in this browser.' });
              }
            } catch (_) {
              setSoundTestStatus({ kind: 'err', text: 'Sound preview unavailable in this browser.' });
            }
          }
        }, 'Send test notification'),
        // aria-live=polite so screen readers announce the result without
        // interrupting whatever they were reading. Visually hidden when
        // empty so the row doesn't expand-collapse on every test press.
        h('span', {
          'aria-live': 'polite', role: 'status',
          style: {
            fontSize: 11, lineHeight: 1.2, minHeight: 14,
            color: soundTestStatus?.kind === 'ok'   ? 'var(--green, #22c55e)'
                 : soundTestStatus?.kind === 'warn' ? '#fbbf24'
                 : soundTestStatus?.kind === 'err'  ? 'var(--red, #f87171)'
                 : 'transparent',
            transition: 'color 200ms ease'
          }
        }, soundTestStatus?.text || ' '),
        Toggle(sounds, () => setSounds(v => !v), 'Sound effects')
      )),
    // Mute per-category — hide alerts you don't care about from the bell
    // and the Notifications page without silencing everything else.
    me && Row('Mute notification types',
      'Hide these categories from the bell + /notifications. Applied client-side; the server still records the event.',
      h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: 6, justifyContent: 'flex-end', maxWidth: 320 } },
        [
          { id: 'TRADES',   label: 'Trades' },
          { id: 'AUCTIONS', label: 'Auctions' },
          { id: 'OFFERS',   label: 'Offers' },
          { id: 'MATCHES',  label: 'Matches' },
          { id: 'WALLET',   label: 'Wallet' },
          { id: 'OTHER',    label: 'Other' }
        ].map(opt => h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${muted.has(opt.id) ? '' : 'active'}`,
          // Batch 939 — aria-pressed exposes toggle state. Semantics
          // here are inverted vs. the other chips (.active = "not
          // muted" / audible) so pressed=true means the user is
          // receiving this category.
          'aria-pressed': !muted.has(opt.id),
          onClick: () => toggleMute(opt.id),
          title: muted.has(opt.id) ? 'Muted — click to unmute' : 'Click to mute this category'
        }, muted.has(opt.id) ? `Muted · ${opt.label}` : opt.label))
      )
    ),
    Section('Privacy'),
    Row('Hide $ amounts',
      'Mask wallet balance, earnings, profile and stall totals as "$•••••" — useful for streaming or screenshotting. Item prices stay visible.',
      Toggle(privacy, () => setPrivacy(v => !v), 'Hide dollar amounts')),
    Section('Accessibility & appearance'),
    Row('Reduce motion',      'Disable animations for card hover + ticker scroll',
      Toggle(reduceMotion, () => setRM(v => !v), 'Reduce motion')),
    Row('High contrast',      'Boost text / border contrast for readability',
      Toggle(highContrast, () => setHC(v => !v), 'High contrast')),
    Row('Accent colour',      'Editorial mono-primary palette — near-white chrome with blue reserved for CTAs and live indicators. No theme picker.',
      h('span', { style: { color: 'var(--text-muted)', fontSize: 12 } }, 'Dark · mono')
    ),
    me && h('div', { style: { marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--border)' } },
      Section('Account & data'),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10 } },
        'Display name and avatar come from your Steam profile and update on every sign-in. Email + 2FA + trade URL live on the Personal Info tab.'),
      h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'center' } },
        h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 12, textDecoration: 'none' },
          href: '/profile/personal',
          onClick: () => onClose && onClose()
        }, 'Manage account →'),
        h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 12, textDecoration: 'none' },
          href: '/api/profile/export',
          title: 'Download every piece of your data we store as a JSON file'
        }, '⇣ Export my data (JSON)')
      )
    ),
    h('div', { style: { marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--border)' } },
      Section('Local data'),
      h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10 } },
        'These preferences live in your browser. Your account data (wallet, listings, trades) is stored server-side and is not affected by these buttons.'),
      h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'center' } },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '8px 14px', fontSize: 12 },
          onClick: resetLocal
        }, 'Reset UI preferences'),
        h('button', {
          className: 'btn-danger-ghost',
          style: { padding: '8px 14px', fontSize: 12 },
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
  // ── ALL HOOKS MUST COME BEFORE THE EARLY RETURN ──
  // Moving hooks after `if (!me) return ...` violates React's rules-of-
  // hooks invariant (same hooks, same order, every render) and throws
  // React error #310 ("Rendered more hooks than during the previous
  // render") when the parent toggles `me` from null → user (e.g. signing
  // in while the modal is open, or the first render before auth resolves).
  // Batch 376 fix: every hook declaration lives above the guard clause.
  const [tab, setTab]             = useState(initialTab || 'personal');
  const [profile, setProfile]     = useState(null);
  const [privacy, setPrivacy]     = useState(() => localStorage.getItem('sb_privacy') === '1');
  const [syncing, setSyncing]     = useState(false);
  // Actionable-trade count — trades where the signed-in user is the
  // blocking party. Drives the red pip on the Trades tab so users see
  // at-a-glance that a trade needs their input. Fetched once at profile-
  // modal open; refreshes when the tab becomes active. Offer count is
  // pulled from the existing /offers/counts endpoint so the tab
  // mirrors the nav badge.
  const [actionableTradeCount, setActionableTradeCount] = useState(0);
  const [pendingOfferCount, setPendingOfferCount]       = useState(0);
  // Support tab badge — count of tickets in WAITING_USER state (staff
  // replied and expects the user to read / respond). Pulled from the
  // user's own ticket list so no new server endpoint is needed.
  const [waitingSupportCount, setWaitingSupportCount]   = useState(0);

  useEffect(() => {
    localStorage.setItem('sb_privacy', privacy ? '1' : '0');
  }, [privacy]);

  // 2026-05-20 — sync the active tab with the route param. The modal
  // stays mounted across /profile/trades → /profile/offers (same
  // routeName), and `useState(initialTab)` only reads its argument on
  // the first render. Without this effect, browser back/forward changed
  // the URL but left the displayed tab stale — contradicting the
  // "browser back/forward steps tab-by-tab" parity goal noted on the
  // tab onClick below. Mirrors the WalletModal initialTab sync effect.
  // Allowlist is inlined (rather than referencing TAB_TITLES, which is
  // declared below the anon guard) so the effect is self-contained.
  useEffect(() => {
    const VALID = ['personal','listings','transactions','buyorders','autobids','trades','offers','reviews','support','developers'];
    if (initialTab && VALID.includes(initialTab)) setTab(initialTab);
  }, [initialTab]);

  useEffect(() => {
    if (!me) return;
    // Race fix — without the `alive` flag, a stale fetchProfile() that
    // resolves AFTER the modal closes (or after `me` flips to a different
    // user via sign-out/sign-in) calls setProfile on an unmounted
    // component (React warning) or — worse — overwrites the current
    // user's profile with the previous user's data. The sibling effect
    // below (3384) uses the same pattern.
    let alive = true;
    fetchProfile().then(p => { if (alive) setProfile(p); });
    return () => { alive = false; };
  }, [me]);

  useEffect(() => {
    if (!me) return;
    let alive = true;
    (async () => {
      try {
        const [tr, oc, ti] = await Promise.all([
          fetch('/api/trades', { credentials: 'same-origin' }).then(r => r.ok ? r.json() : []),
          safeFetchJson('/api/offers/counts'),
          fetch('/api/support/tickets', { credentials: 'same-origin' }).then(r => r.ok ? r.json() : [])
        ]);
        if (!alive) return;
        const tradeN = (Array.isArray(tr) ? tr : []).filter(t =>
          (t.sellerUserId === me.id && ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND'].includes(t.state)) ||
          (t.buyerUserId  === me.id && t.state === 'PENDING_BUYER_CONFIRM')
        ).length;
        setActionableTradeCount(tradeN);
        setPendingOfferCount(Number(oc?.incomingPending || 0));
        const waitingN = (Array.isArray(ti) ? ti : []).filter(t => t.status === 'WAITING_USER').length;
        setWaitingSupportCount(waitingN);
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [me?.id, tab]);

  // Batch 893 — auto-submit the verification token when the user
  // lands on /profile?verify=<token> from an email click. Pre-batch-
  // 893 the verification email link used `/#/profile?verify=<token>`
  // (hash-routing) which the history-API router never read, so the
  // link had no effect. Fires directly against verifyEmail() without
  // going through confirmEmail() because that helper is defined after
  // the anon guard — keeping this hook up here respects rules-of-
  // hooks. Scrubs the param so a refresh can't spam the endpoint.
  //
  // Batch 896 — ALSO fires a toast + refreshes `me` so the user's
  // "pending verification" chip flips to "verified" without a full
  // page reload. Previously only the in-profile banner showed, which
  // was easy to miss.
  useEffect(() => {
    if (!me) return;
    let cancelled = false;
    try {
      const qs = new URLSearchParams(window.location.search);
      const tok = qs.get('verify');
      if (!tok || !tok.trim()) return;
      qs.delete('verify');
      const next = qs.toString();
      window.history.replaceState({}, '',
        window.location.pathname + (next ? '?' + next : ''));
      (async () => {
        const res = await verifyEmail(tok.trim());
        if (cancelled) return;
        // setEmailResult / setEmailToken live in ProfilePersonalTab, not
        // here. Calling them from this scope threw a ReferenceError AFTER
        // the server had already verified the address, so the user got no
        // toast, a stale UNVERIFIED chip and the "not confirmed" nag --
        // the verification link looked broken although it had worked.
        // Feedback here is the toast + the `me` refresh only.
        if (res && (res.code || res.error)) {
          toast(res.message || res.error || 'Could not verify email — the link may be expired.', 'err');
        } else {
          setTab('personal');  // flip to the tab that surfaces the banner
          toast('Email verified — you\'ll get trade activity, auction, and security alerts now.', 'ok');
          // Reload `me` so the hero's verified badge flips without a page refresh.
          try { onRefresh && onRefresh(); } catch (_) {}
        }
      })();
    } catch (_) { /* silent — no URLSearchParams */ }
    return () => { cancelled = true; };
  }, [me?.id]);

  // ── GUARD CLAUSE (all hooks are above, safe to early-return) ──
  // When the modal is opened via a tab-specific route (e.g. /support)
  // the header title mirrors that tab so anonymous viewers don't see
  // "Profile · Sign in required" on a page they asked to be Support.
  const TAB_TITLES = {
    personal:     'Profile',
    listings:     'Listings',
    transactions: 'Transactions',
    buyorders:    'Buy Orders',
    autobids:     'Active Bids',
    trades:       'Trades',
    offers:       'Offers',
    reviews:      'Reviews',
    support:      'Support',
    developers:   'Developers',
  };
  const modalTitle = TAB_TITLES[initialTab] || 'Profile';
  const signInWhat = initialTab === 'support' ? 'your support tickets'
                   : initialTab === 'listings' ? 'your listings'
                   : initialTab === 'trades' ? 'your trades'
                   : initialTab === 'transactions' ? 'your transactions'
                   : initialTab === 'buyorders' ? 'your buy orders'
                   : initialTab === 'autobids' ? 'your active bids'
                   : initialTab === 'offers' ? 'your offers'
                   : initialTab === 'reviews' ? 'your reviews'
                   : initialTab === 'developers' ? 'your API keys'
                   : 'your profile';
  // Footer chips and in-app help links route anon users to /support
  // (and /support?topic=bug). Hitting a pure "Sign in required" wall
  // there is a UX dead-end: they wanted to *contact* the team, not
  // read their own ticket history. Surface a no-login mailto fallback
  // with the topic pre-filled in the subject so the email lands tagged.
  let signInMailto = null;
  if (initialTab === 'support') {
    let topic = '';
    try { topic = (new URLSearchParams(window.location.search).get('topic') || '').slice(0, 40); } catch (_) {}
    const subject = topic === 'bug' ? '[Bug report] '
                  : topic === 'cap-raise' ? '[Cap raise request] '
                  : topic ? `[${topic}] `
                  : '[Support] ';
    signInMailto = { to: 'support@skinbox.market', subject, label: 'Email support@skinbox.market' };
  }
  if (!me) return h(InfoModal, { title: modalTitle, onClose },
    h(SignInNeededEmptyState, { what: signInWhat, mailto: signInMailto }));

  const maskAmount = (val) => privacy ? '$•••••' : fmt(val);

  const runSync = async () => {
    setSyncing(true);
    try {
      // Pre-fix: silent both ways — successful sync left the user to
      // detect that profile fields had updated, and a network / Steam-side
      // failure (rate-limited, OpenID expired, Steam down) looked
      // indistinguishable from a successful no-op sync. syncSteam() returns
      // `{ok:false}` on non-2xx and the response JSON on success; surface a
      // toast either way so the "Re-sync from Steam" button feels live.
      const res = await syncSteam();
      if (res && res.ok === false) {
        toast('Steam sync failed — try again in a minute.', 'err');
        return;
      }
      const fresh = await fetchProfile();
      setProfile(fresh);
      toast('Profile re-synced from Steam.', 'ok');
    } finally { setSyncing(false); }
  };

  const TABS = [
    { id: 'personal',     label: 'Personal Info' },
    // CSFloat-1:1 — a user's profile LEADS with their listings/inventory,
    // so the Listings tab sits right after Personal Info (ahead of the
    // money/activity tabs). Reuses the same /api/listings/my-stall feed
    // the MyStall page uses; no new backend. (batch: profile-parity)
    { id: 'listings',     label: 'Listings' },
    { id: 'transactions', label: 'Transactions' },
    { id: 'buyorders',    label: 'Buy Orders' },
    { id: 'autobids',     label: 'Active Bids' },
    { id: 'trades',       label: 'Trades', badge: actionableTradeCount },
    { id: 'offers',       label: 'Offers', badge: pendingOfferCount },
    { id: 'reviews',      label: 'Reviews' },
    { id: 'support',      label: 'Support', badge: waitingSupportCount },
    { id: 'developers',   label: 'Developers' },
  ];

  return h(InfoModal, { title: modalTitle, onClose },
    /* Hero: avatar + name + earnings privacy toggle + account standing bar */
    h('div', { className: 'profile-hero-split' },
    h('div', { className: 'profile-hero' },
      h('div', { className: 'profile-avatar' },
        h(Avatar, {
          src: me.avatarUrl,
          name: me.displayName || 'Player',
          alt: me.displayName,
          style: { width: '100%', height: '100%', borderRadius: 'inherit',
                   background: 'transparent', border: 'none', fontSize: 22 }
        })
      ),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { className: 'profile-name' }, me.displayName || 'Player',
          // Account standing chip — single source of truth for "is this
          // account in trouble / verified / unverified". Most users see
          // "Good standing" (quiet green). Banned, pending-deletion, or
          // unverified-email users get a louder chip that nudges them
          // toward the resolution path (Profile → Personal Info for
          // verification, Support for unban appeals).
          (() => {
            if (me.banned) {
              return h('span', {
                style: { marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                  fontSize: 10, fontWeight: 800,
                  background: 'rgba(248,113,113,0.15)',
                  color: 'var(--red)',
                  border: '1px solid rgba(248,113,113,0.45)' },
                title: me.banReason ? ('Reason: ' + me.banReason) : 'Account is currently suspended — contact Support to appeal.'
              }, 'SUSPENDED');
            }
            if (profile?.user?.deletionRequestedAt) {
              return h('span', {
                style: { marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                  fontSize: 10, fontWeight: 800,
                  background: 'rgba(251,191,36,0.15)',
                  color: '#fbbf24',
                  border: '1px solid rgba(251,191,36,0.45)' },
                title: 'Account deletion requested — cancel below to reverse before staff finalises.'
              }, 'Deletion pending');
            }
            if (me.role === 'ADMIN' || me.role === 'CSR') {
              return h('span', {
                style: { marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                  fontSize: 10, fontWeight: 800,
                  background: 'rgba(77,200,255,0.15)',
                  color: 'var(--accent)',
                  border: '1px solid rgba(77,200,255,0.45)' },
                title: 'Staff role — admin/CSR surfaces are available from the user menu.'
              }, me.role === 'ADMIN' ? 'Admin' : 'CSR');
            }
            if (me.email && !me.emailVerified) {
              return h('span', {
                style: { marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                  fontSize: 10, fontWeight: 800,
                  background: 'rgba(251,191,36,0.12)',
                  color: '#fbbf24',
                  border: '1px solid rgba(251,191,36,0.4)' },
                title: 'Check your inbox for a verification link — required for withdrawals.'
              }, 'Email unverified');
            }
            return h('span', {
              style: { marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                fontSize: 10, fontWeight: 800,
                background: 'rgba(34,197,94,0.12)',
                color: 'var(--green)',
                border: '1px solid rgba(34,197,94,0.35)' },
              title: 'No flags on this account — good standing.'
            }, 'Good standing');
          })()
        ),
        h('div', { className: 'profile-id' },
          'Steam ID · ',
          h('span', { style: { fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, me.steamId64),
          // Member-since chip — pulled from the profile's createdAt so
          // veterans get a subtle tenure signal (and brand-new users get
          // a nudge that they're fresh). Rendered next to Steam ID so
          // the line stays compact. Falls through silently when the
          // profile hasn't loaded yet.
          profile?.user?.createdAt && h('span', {
            style: { marginLeft: 10, fontSize: 11, color: 'var(--text-muted)', fontWeight: 600 },
            title: 'Account created ' + new Date(profile.user.createdAt).toLocaleString()
          }, '· Member since ', new Date(profile.user.createdAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short' })),
          // Self-rating chip (batch 491). Renders the user's own seller
          // rating next to their tenure so sellers see their score at a
          // glance every time they open the profile. Hidden before the
          // first review arrives so new users don't see a ★ 0 placeholder.
          profile?.rating && profile.rating.count > 0 && h('span', {
            style: { marginLeft: 10, fontSize: 11, color: '#fbbf24', fontWeight: 700 },
            title: `${profile.rating.count} review${profile.rating.count === 1 ? '' : 's'} across your completed sales`
          }, '· ★ ', Number(profile.rating.average || 0).toFixed(2), ' (', profile.rating.count, ')')
        ),
        h('div', { style: { display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', marginTop: 2 } },
          me.profileUrl && h('a', { href: me.profileUrl, target: '_blank', rel: 'noopener noreferrer', className: 'profile-link' }, 'View Steam profile ↗'),
          // Copy-my-stall-URL affordance. Users wanting to share their
          // storefront with friends or on Discord didn't have a single-
          // click copy before — they had to navigate to /stall/{my-id}
          // and then use the stall page's share button. Inline here is
          // one hop.
          h('button', {
            className: 'profile-link',
            style: { background: 'transparent', border: 'none', padding: 0, cursor: 'pointer', fontWeight: 600 },
            title: 'Copy your public stall link to the clipboard',
            onClick: async (e) => {
              e.preventDefault();
              const url = window.location.origin + '/stall/' + me.id;
              try {
                if (navigator.clipboard?.writeText) {
                  await navigator.clipboard.writeText(url);
                  toast('Stall link copied.', 'ok');
                } else {
                  window.prompt('Copy your stall link:', url);
                }
              } catch (_) {
                window.prompt('Copy your stall link:', url);
              }
            }
          }, '⎘ Copy stall link')
        )
      ),
      // Privacy toggle moved to the Earnings card header in batch 610.
    ),
    // Earnings panel (batch 610) — sits to the right of the avatar / name
    // block. Matches the CSFloat screenshot: Sales / Purchases / Net on
    // three rows with the amount right-aligned. Eye toggle mirrors the
    // privacy mode on the left so sellers who hide amounts see the
    // whole panel masked consistently.
    h('div', { className: 'profile-earnings' },
      h('div', { className: 'profile-earnings-head' },
        h('div', { className: 'profile-earnings-title' }, 'Earnings'),
        h('button', {
          className: 'profile-earnings-toggle',
          onClick: () => setPrivacy(p => !p),
          title: privacy ? 'Show amounts' : 'Hide amounts',
          'aria-label': privacy ? 'Show amounts' : 'Hide amounts'
        }, h(MaterialIcon, { name: privacy ? 'visibility_off' : 'visibility', size: 16, fill: true, color: 'var(--text-muted)' }))
      ),
      h('div', { className: 'profile-earnings-row' },
        h('span', { className: 'profile-earnings-label' }, 'Sales'),
        h('span', { className: 'profile-earnings-val' }, privacy ? '$•••••' : fmt(profile?.stats?.totalSold || 0))
      ),
      h('div', { className: 'profile-earnings-row' },
        h('span', { className: 'profile-earnings-label' }, 'Purchases'),
        h('span', { className: 'profile-earnings-val' }, privacy ? '$•••••' : fmt(profile?.stats?.totalPurchased || 0))
      ),
      h('div', { className: 'profile-earnings-row total' },
        h('span', { className: 'profile-earnings-label' }, 'Net'),
        h('span', {
          className: `profile-earnings-val ${parseFloat(profile?.stats?.net || 0) >= 0 ? 'green' : 'red'}`
        }, privacy
              ? '$•••••'
              : (parseFloat(profile?.stats?.net || 0) < 0 ? '− ' : '') + fmt(Math.abs(parseFloat(profile?.stats?.net || 0))))
      )
    )
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

    /* Account Standing gauge — 5-node horizontal ladder per CSFloat
       Visual Manual §23. Nodes ordered Excellent → Good → Poor → At Risk
       → Banned. The current state highlights a coloured ring + fills the
       trailing line. Note line on the right explains the state (e.g.
       "No restrictions on your account" or "Average rating 2.4★"). */
    profile?.accountStanding && (() => {
      const standing = profile.accountStanding;
      const NODES = [
        { id: 'excellent', label: 'Excellent', color: '#22c55e', icon: 'check_circle' },
        { id: 'good',      label: 'Good',      color: 'var(--accent)', icon: 'thumb_up' },
        { id: 'poor',      label: 'Poor',      color: '#fbbf24', icon: 'sentiment_dissatisfied' },
        { id: 'at_risk',   label: 'At Risk',   color: '#f97316', icon: 'shield' },
        { id: 'banned',    label: 'Banned',    color: '#ef4444', icon: 'block' }
      ];
      const idx = Math.max(0, NODES.findIndex(n => n.id === standing.state));
      const active = NODES[idx] || NODES[1];
      return h('div', {
        style: {
          margin: '18px 0',
          padding: '22px 26px',
          background: 'var(--bg-card)',
          border: '1px solid var(--border)',
          borderRadius: 10
        }
      },
        h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24, flexWrap: 'wrap', gap: 8 } },
          h('div', { style: { fontSize: 16, fontWeight: 700, color: 'var(--text-primary)' } }, 'Account Standing'),
          h('div', { style: { fontSize: 12, color: 'var(--text-muted)' } }, standing.note || 'No recent restrictions')
        ),
        h('div', { style: {
          position: 'relative', paddingTop: 4,
          display: 'grid', gridTemplateColumns: `repeat(${NODES.length}, 1fr)`, alignItems: 'flex-start'
        } },
          // Rail — behind the icon circles, spans from the centre of the
          // first node to the centre of the last. Segments up to the
          // active node are accent-coloured (progress), past the active
          // stay muted (remaining).
          h('div', {
            style: {
              position: 'absolute',
              top: 22, left: `calc(${100 / (2 * NODES.length)}%)`, right: `calc(${100 / (2 * NODES.length)}%)`,
              height: 2, background: 'var(--border)', zIndex: 0
            }
          }),
          h('div', {
            style: {
              position: 'absolute',
              top: 22, left: `calc(${100 / (2 * NODES.length)}%)`,
              width: `calc(${idx * (100 / NODES.length)}%)`,
              height: 2, background: active.color, zIndex: 0,
              transition: 'width 0.3s ease'
            }
          }),
          NODES.map((n, i) => {
            const isActive = i === idx;
            const isPast   = i < idx;
            const ringColor = isActive ? n.color
                             : isPast   ? active.color
                             : 'var(--border)';
            const iconColor = isActive ? '#fff'
                             : isPast   ? active.color
                             : 'var(--text-muted)';
            const size = isActive ? 46 : 36;
            return h('div', { key: n.id,
              style: { position: 'relative', zIndex: 1,
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }
            },
              h('div', {
                style: {
                  width: size, height: size,
                  borderRadius: '50%',
                  background: isActive ? n.color : 'var(--bg-elevated)',
                  border: `2px solid ${ringColor}`,
                  boxShadow: isActive ? `0 0 0 4px ${n.color}22, 0 8px 24px ${n.color}33` : 'none',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  transition: 'all 0.2s',
                  marginTop: isActive ? -5 : 0
                }
              },
                h(MaterialIcon, { name: n.icon, size: isActive ? 22 : 18, fill: true, color: iconColor })
              ),
              h('div', {
                style: {
                  fontSize: 12,
                  fontWeight: isActive ? 800 : 600,
                  color: isActive ? active.color : 'var(--text-muted)',
                  letterSpacing: 0.2
                }
              }, n.label)
            );
          })
        )
      );
    })(),

    /* Tabs — Batch 936: proper WAI-ARIA tablist semantics so screen
       readers announce "Tab 3 of 9, selected" while arrow-keying. */
    h('div', { className: 'profile-tabs', role: 'tablist', 'aria-label': 'Profile sections' },
      TABS.map(t => h('button', {
        key: t.id,
        id: 'profile-tab-' + t.id,
        className: `profile-tab ${tab === t.id ? 'active' : ''}`,
        role: 'tab',
        'aria-selected': tab === t.id,
        'aria-controls': 'profile-panel-' + t.id,
        tabIndex: tab === t.id ? 0 : -1,
        onClick: () => {
          setTab(t.id);
          // CSFloat-1:1: keep the URL in sync with the active tab so it's
          // shareable + browser back/forward steps tab-by-tab.
          // Use navigate() (not raw pushState) so the SPA router state
          // updates → routeName/route.params.tab change → title-effect
          // re-fires → document.title reflects the new tab. Pre-fix,
          // the URL changed but the title stayed stale on tab click.
          navigate('/profile/' + t.id);
        },
        onKeyDown: (e) => {
          // Batch 936 — arrow-key navigation between tabs. Standard
          // WAI-ARIA tablist pattern: Left/Right move the active tab,
          // Home/End jump to first/last. Only horizontal arrows here
          // since the tabs render in a row.
          if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === 'Home' || e.key === 'End') {
            e.preventDefault();
            const idx = TABS.findIndex(x => x.id === tab);
            let next = idx;
            if (e.key === 'ArrowRight') next = (idx + 1) % TABS.length;
            else if (e.key === 'ArrowLeft') next = (idx - 1 + TABS.length) % TABS.length;
            else if (e.key === 'Home') next = 0;
            else if (e.key === 'End') next = TABS.length - 1;
            setTab(TABS[next].id);
            // CSFloat-1:1: mirror the click handler — keyboard tab
            // switching must also sync the URL + document.title via the
            // SPA router, otherwise arrow-keying leaves them stale.
            navigate('/profile/' + TABS[next].id);
          }
        }
      },
        t.label,
        t.badge > 0 && h('span', { className: 'profile-tab-badge' }, t.badge > 99 ? '99+' : t.badge)
      ))
    ),

    // Batch 936 — tabpanel wrapper so the active tab button has a matching
    // role="tabpanel"/aria-labelledby target. One panel node tracks the
    // active tab (content is single-rendered, so a per-tab panel array
    // isn't needed).
    h('div', {
      role: 'tabpanel',
      id: 'profile-panel-' + tab,
      'aria-labelledby': 'profile-tab-' + tab
    },
      tab === 'personal' && h(ProfilePersonalTab, {
        me, profile, syncing, onSync: runSync, transactions, privacy,
        // Batch 929 — lightweight profile refresh for actions that need
        // to pick up the new `profile.user.deletionRequestedAt` state
        // without triggering a Steam sync round-trip (onSync does both).
        refreshProfile: () => fetchProfile().then(setProfile)
      }),
      tab === 'listings'    && h(ProfileListingsTab, { me }),
      tab === 'transactions' && h(ProfileTransactionsTab, { transactions, privacy }),
      tab === 'buyorders'   && h(ProfileBuyOrdersTab, null),
      tab === 'autobids'    && h(ProfileAutoBidsTab, null),
      tab === 'trades'      && h(ProfileTradesTab, { me, privacy }),
      tab === 'offers'      && h(ProfileOffersTab, null),
      tab === 'reviews'     && h(ProfileReviewsTab, { me }),
      tab === 'support'     && h(ProfileSupportTab, null),
      tab === 'developers'  && h(ProfileDevelopersTab, null)
    )
  );
}

// Email-notification toggle row. Lazy-loads the PUT helper so this
// file doesn't gain a new top-level dependency; state is local, with
// the server value seeded from ProfileService.buildProfile.
function EmailPrefToggle({ initial, onChange }) {
  const [on, setOn] = useState(initial !== false);
  const [busy, setBusy] = useState(false);
  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    const next = !on;
    setOn(next);  // optimistic
    // Notify parent so the gated EmailBucketMutes section shows/hides
    // without waiting for a profile refetch. Pre-fix the parent read
    // its stale `profile.user.emailNotificationsEnabled` snapshot so
    // flipping the master toggle never revealed the per-bucket panel
    // (and never hid it after a turn-off).
    try { onChange && onChange(next); } catch (_) {}
    try {
      const { setEmailNotifications } = await import('./api.js');
      const res = await setEmailNotifications(next);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not update email notifications', 'err');
        setOn(!next);  // revert
        try { onChange && onChange(!next); } catch (_) {}
      }
    } finally { setBusy(false); }
  };
  return h('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
    h('div', {
      className: `toggle-switch ${on ? '' : 'off'}`,
      onClick: toggle,
      role: 'switch',
      'aria-checked': on,
      title: on ? 'Email notifications ON — click to disable' : 'Email notifications OFF — click to enable'
    }),
    h('span', { style: { fontSize: 11, color: 'var(--text-muted)' } },
      busy ? 'Saving…' : (on ? 'On' : 'Off'))
  );
}

// Per-bucket email-mute selector. Layered on top of the global
// EmailPrefToggle — with emails globally ON, a user can still silence
// TRADES-bucket or WATCHLIST-bucket sends here. Checkboxes are
// optimistic + persist to /api/profile/email-mutes. Labels describe
// what actually gets silenced so a user knows before toggling.
function EmailBucketMutes() {
  const [loaded, setLoaded] = useState(false);
  const [available, setAvailable] = useState([]);
  const [muted, setMuted] = useState(new Set());
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let alive = true;
    (async () => {
      const { fetchEmailMutes } = await import('./api.js');
      const data = await fetchEmailMutes();
      if (!alive) return;
      if (data) {
        setAvailable(Array.isArray(data.available) ? data.available : []);
        setMuted(new Set(Array.isArray(data.muted) ? data.muted : []));
      }
      setLoaded(true);
    })();
    return () => { alive = false; };
  }, []);
  const toggle = async (bucket) => {
    if (busy) return;
    const next = new Set(muted);
    if (next.has(bucket)) next.delete(bucket); else next.add(bucket);
    setMuted(next);
    setBusy(true);
    try {
      const { setEmailMutes } = await import('./api.js');
      const res = await setEmailMutes([...next]);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not update email categories', 'err');
        // Revert on failure.
        setMuted(muted);
      }
    } finally { setBusy(false); }
  };
  if (!loaded) return h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Loading categories…');
  if (available.length === 0) return null;
  const LABELS = {
    TRADES:    { name: 'Trade activity',          hint: 'Emails when a sale clears escrow + money lands in your wallet' },
    AUCTIONS:  { name: 'Auction activity',        hint: 'Outbid notices + auction-won confirmations' },
    WATCHLIST: { name: 'Watchlist alerts',        hint: 'Price-drop emails for items you are watching' },
    FOLLOWS:   { name: 'Followed sellers',        hint: 'Emails when a seller you follow lists a new item' },
    MATCHES:   { name: 'Saved-search matches',    hint: 'Emails when a fresh listing matches one of your saved searches' }
  };
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6, marginTop: 4 } },
    available.map(b => {
      const meta = LABELS[b] || { name: b, hint: '' };
      const off = muted.has(b);
      return h('label', {
        key: b,
        style: {
          display: 'flex', alignItems: 'center', gap: 10,
          padding: '6px 10px', borderRadius: 6,
          border: '1px solid var(--border)',
          background: off ? 'rgba(248,113,113,0.08)' : 'var(--bg-elevated)',
          cursor: busy ? 'wait' : 'pointer', fontSize: 12
        },
        title: meta.hint
      },
        h('input', {
          type: 'checkbox',
          checked: !off,
          onChange: () => toggle(b),
          disabled: busy,
          style: { accentColor: 'var(--accent)' }
        }),
        h('div', { style: { flex: 1, minWidth: 0 } },
          h('div', { style: { fontWeight: 700, color: off ? 'var(--text-muted)' : 'var(--text-primary)' } },
            meta.name,
            off && h('span', { style: { marginLeft: 8, fontSize: 10, color: 'var(--red)', fontWeight: 800 } }, '· MUTED')
          ),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 1 } }, meta.hint)
        )
      );
    })
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
        const { fetchFollowing, fetchSellerNames } = await import('./api.js');
        const r = await fetchFollowing();
        if (!alive) return;
        setRows(Array.isArray(r) ? r : []);
        // One bulk round-trip for every followed seller's displayName —
        // replaces the prior N+1 stall-fetch-per-row pattern that fired
        // the full /api/listings/stall/{id} (listings + reviews + aggregates)
        // just to grab a name.
        const ids = [...new Set((r || []).map(x => x.sellerUserId))];
        const namesMap = await fetchSellerNames(ids);
        if (alive) setNames(namesMap || {});
      } catch (_) { if (alive) setRows([]); }
    })();
    return () => { alive = false; };
  }, []);
  const doUnfollow = async (sellerId) => {
    // Batch 925 — capture the seller name from the enriched-names map
    // BEFORE the row filter runs so the toast names the right stall.
    const who = names[sellerId];
    const { unfollowSeller } = await import('./api.js');
    const res = await unfollowSeller(sellerId);
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not unfollow', 'err');
      return;
    }
    setRows(rs => (rs || []).filter(r => r.sellerUserId !== sellerId));
    toast(who ? `Unfollowed @${who}.` : 'Unfollowed.', 'ok');
  };
  // Mute / un-mute the new-listing pings for one seller without
  // unfollowing (batch 279). Optimistic flip with revert on error.
  const toggleMute = async (sellerId, currentlyMuted) => {
    const who = names[sellerId];
    const { setSellerMuted } = await import('./api.js');
    setRows(rs => (rs || []).map(r =>
      r.sellerUserId === sellerId ? { ...r, notificationsMuted: !currentlyMuted } : r));
    const res = await setSellerMuted(sellerId, !currentlyMuted);
    if (res && (res.error || res.code)) {
      // Revert.
      setRows(rs => (rs || []).map(r =>
        r.sellerUserId === sellerId ? { ...r, notificationsMuted: currentlyMuted } : r));
      toast(res.message || res.error || 'Could not update mute', 'err');
      return;
    }
    // Batch 925 — name the seller + surface the consequence of the flip.
    const target = who ? `@${who}` : 'seller';
    toast(
      currentlyMuted
        ? `Pings for ${target} unmuted — new listings will ping you again.`
        : `Pings for ${target} muted — still following, but no bell / email pings.`,
      'ok'
    );
  };
  const doUnfollowAll = async () => {
    if (!confirm(`Unfollow all ${rows.length} seller${rows.length === 1 ? '' : 's'}? You'll stop getting new-listing pings from them.`)) return;
    const { unfollowAllSellers } = await import('./api.js');
    const res = await unfollowAllSellers();
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not unfollow', 'err');
      return;
    }
    const n = (res && res.unfollowed) || 0;
    setRows([]);
    toast(n === 0 ? 'Nothing to unfollow.' : `Unfollowed ${n} seller${n === 1 ? '' : 's'}.`, 'ok');
  };
  // Bulk-toggle mute across every follow. If any row is currently
  // loud → target is muted (one PATCH). Otherwise → target is unmuted.
  // Keeps the follow rows alive — only the bell + email fan-out
  // changes. Optimistic flip with revert on error.
  const anyLoud = (rows || []).some(r => !r.notificationsMuted);
  const doToggleMuteAll = async () => {
    const nextMuted = anyLoud; // mute if any are loud; unmute otherwise
    const snapshot = rows;
    setRows(rs => (rs || []).map(r => ({ ...r, notificationsMuted: nextMuted })));
    const { setAllSellerMuted } = await import('./api.js');
    const res = await setAllSellerMuted(nextMuted);
    if (res && (res.error || res.code)) {
      setRows(snapshot);
      toast(res.message || res.error || 'Could not update mute state', 'err');
      return;
    }
    toast(nextMuted
      ? `Muted pings from ${rows.length} seller${rows.length === 1 ? '' : 's'} (still following).`
      : `Un-muted pings from ${rows.length} seller${rows.length === 1 ? '' : 's'}.`, 'ok');
  };
  if (!rows || rows.length === 0) return null;
  return h('div', { className: 'profile-row' },
    h('div', { className: 'profile-row-label' },
      `Following ${rows.length}`,
      rows.length > 1 && h('button', {
        className: 'btn btn-ghost',
        style: { marginLeft: 8, padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)',
                 color: anyLoud ? 'var(--text-muted)' : 'var(--accent)' },
        onClick: doToggleMuteAll,
        title: anyLoud
          ? 'Mute new-listing pings from every followed seller (follow rows stay)'
          : 'Un-mute new-listing pings from every followed seller'
      }, anyLoud ? 'all' : 'all'),
      rows.length > 1 && h('button', {
        className: 'btn btn-ghost',
        style: { marginLeft: 6, padding: '2px 8px', fontSize: 10, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
        onClick: doUnfollowAll,
        title: 'Unfollow every seller in one click'
      }, '✕ all')
    ),
    h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 4, maxWidth: '100%' } },
      rows.slice(0, 10).map(r => h('div', {
        key: r.id,
        style: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }
      },
        h('a', {
          href: '/stall/' + r.sellerUserId,
          style: { color: 'var(--accent)', textDecoration: 'none', flex: 1 }
        }, names[r.sellerUserId] || ('Seller #' + r.sellerUserId)),
        // Mute toggle — 🔔 when loud, 🔕 when muted. Tooltip explains
        // that the follow stays in either state.
        h('button', {
          className: 'btn btn-ghost',
          style: {
            padding: '2px 8px', fontSize: 11, border: '1px solid var(--border)',
            color: r.notificationsMuted ? 'var(--text-muted)' : 'var(--accent)'
          },
          onClick: () => toggleMute(r.sellerUserId, !!r.notificationsMuted),
          title: r.notificationsMuted
            ? 'New-listing pings muted — click to re-enable'
            : 'Mute new-listing pings (you stay following)',
          'aria-label': r.notificationsMuted
            ? 'New-listing pings muted — click to re-enable'
            : 'Mute new-listing pings'
        }, r.notificationsMuted ? '🔕' : '🔔'),
        h('button', {
          className: 'btn btn-ghost',
          style: { padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)' },
          onClick: () => doUnfollow(r.sellerUserId),
          title: 'Unfollow this seller',
          'aria-label': 'Unfollow this seller'
        }, '✕')
      )),
      rows.length > 10 && h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
        `+ ${rows.length - 10} more`)
    )
  );
}

// "Blocked users" row inside ProfilePersonalTab (batch 344). Compact
// list + per-row unblock. Silently hidden when the user has blocked
// nobody so it doesn't clutter the Personal tab for the common case.
function BlockedListRow() {
  const [rows, setRows] = useState(null);
  const loadRows = useCallback(async () => {
    try {
      const { fetchBlockedUsers } = await import('./api.js');
      const data = await fetchBlockedUsers();
      setRows(Array.isArray(data?.items) ? data.items : []);
    } catch (_) { setRows([]); }
  }, []);
  useEffect(() => { loadRows(); }, [loadRows]);
  const doUnblock = async (userId) => {
    // Batch 911 — capture display name BEFORE the row is filtered out
    // of local state so the confirmation toast can name the user even
    // after the list has re-rendered.
    const r = (rows || []).find(x => Number(x.blockedUserId) === Number(userId));
    const who = r?.blockedName || r?.displayName || null;
    const { unblockUser } = await import('./api.js');
    const res = await unblockUser(userId);
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not unblock', 'err');
      return;
    }
    setRows(rs => (rs || []).filter(r => Number(r.blockedUserId) !== Number(userId)));
    toast(who ? `Unblocked @${who} — their listings are visible again.` : 'Unblocked.', 'ok');
  };
  const doUnblockAll = async () => {
    if (rows.length === 0) return;
    if (!confirm(`Unblock all ${rows.length} user${rows.length === 1 ? '' : 's'}? Their listings will return to your grid and they'll be able to send you offers again.`)) return;
    const { unblockAllUsers } = await import('./api.js');
    const res = await unblockAllUsers();
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not clear block list', 'err');
      return;
    }
    setRows([]);
    toast(`Cleared ${res?.removed || rows.length} block${(res?.removed || rows.length) === 1 ? '' : 's'}.`, 'ok');
  };
  if (!rows || rows.length === 0) return null;
  return h('div', { className: 'profile-row' },
    h('div', { className: 'profile-row-label' },
      `Blocked ${rows.length}`,
      // "Unblock all" shortcut (batch 355) — parity with Following +
      // watchlist + saved-searches bulk-clear affordances.
      rows.length > 1 && h('button', {
        className: 'btn btn-ghost',
        style: { marginLeft: 8, padding: '2px 8px', fontSize: 10, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
        onClick: doUnblockAll,
        title: 'Remove every block in one click'
      }, '✕ all')
    ),
    h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 4, maxWidth: '100%' } },
      rows.slice(0, 10).map(r => h('div', {
        key: r.blockedUserId,
        style: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }
      },
        h('a', {
          href: '/stall/' + r.blockedUserId,
          style: { color: 'var(--text-secondary)', textDecoration: 'none', flex: 1 },
          title: "View their stall (you won't see their listings in the main grid)"
        }, r.displayName || ('User #' + r.blockedUserId)),
        h('button', {
          className: 'btn btn-ghost',
          style: { padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)' },
          onClick: () => doUnblock(r.blockedUserId),
          title: 'Remove this block'
        }, 'Unblock')
      )),
      rows.length > 10 && h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
        `+ ${rows.length - 10} more`)
    )
  );
}

/** Recent sign-ins (batch 569) — collapsible list of the caller's last
 *  20 USER_SIGN_IN audit rows. Shows timestamp + IP + browser/OS so a
 *  user can spot unfamiliar sessions. Collapsed by default to keep
 *  the common-case profile view clean; opens on click. */
function SignInHistoryRow() {
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState(null);
  const loaded = useRef(false);
  const toggle = async () => {
    setOpen(v => !v);
    if (loaded.current) return;
    loaded.current = true;
    try {
      const { fetchSignInHistory } = await import('./api.js');
      const data = await fetchSignInHistory();
      setRows(Array.isArray(data) ? data : []);
    } catch (_) { setRows([]); }
  };
  // Compact user-agent summary — just the browser family, since the
  // full UA string is long and unreadable at a glance. Tooltip shows
  // the full string for anyone who wants the detail.
  const uaLabel = (ua) => {
    if (!ua) return 'Unknown device';
    const s = String(ua);
    if (/Edg\//.test(s))       return 'Edge';
    if (/Chrome\//.test(s))    return 'Chrome';
    if (/Firefox\//.test(s))   return 'Firefox';
    if (/Safari\//.test(s))    return 'Safari';
    return 'Browser';
  };
  return h('div', { className: 'profile-row' },
    h('div', { className: 'profile-row-label' }, 'Recent sign-ins'),
    h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8, flex: 1 } },
      h('button', {
        className: 'btn btn-ghost',
        style: { padding: '5px 12px', fontSize: 11, border: '1px solid var(--border)' },
        onClick: toggle
      }, open ? 'Hide' : 'Show last 20 sign-ins'),
      open && (rows === null
        ? h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Loading…')
        : rows.length === 0
          ? h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'No sign-in history recorded yet.')
          : h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4, width: '100%', maxHeight: 220, overflowY: 'auto' } },
              rows.map(r => h('div', {
                key: r.id,
                style: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, padding: '4px 8px', background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 4 }
              },
                h('span', { className: 'mono', style: { color: 'var(--text-secondary)', minWidth: 140 } },
                  r.createdAt ? new Date(r.createdAt).toLocaleString() : '—'),
                h('span', { className: 'mono', style: { color: 'var(--text-primary)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } },
                  r.ipAddress || 'no-ip'),
                h('span', {
                  style: { fontSize: 10, color: 'var(--text-muted)', minWidth: 60, textAlign: 'right' },
                  title: r.userAgent || ''
                }, uaLabel(r.userAgent))
              ))
            )
      ),
      open && rows && rows.length > 0 && h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 4, lineHeight: 1.4 } },
        'See a sign-in you don\'t recognise? Change your Steam password, then reset 2FA from the Security section below.')
    )
  );
}

// Batch 715 — SecurityActivityRow wires the batch-714 endpoint into a
// collapsed row on the Personal tab. Shows the last 50 whitelisted
// audit events where the user is the subject (2FA resets, API key
// mints/revocations, admin force-actions, withdrawals, disputes).
// Collapsed by default same as SignInHistoryRow so the profile view
// stays clean for users who don't need to audit.
function SecurityActivityRow() {
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState(null);
  const loaded = useRef(false);
  const toggle = async () => {
    setOpen(v => !v);
    if (loaded.current) return;
    loaded.current = true;
    try {
      const { fetchSecurityActivity } = await import('./api.js');
      const data = await fetchSecurityActivity();
      setRows(Array.isArray(data) ? data : []);
    } catch (_) { setRows([]); }
  };
  // Event-type label + emoji lookup. Kept in sync with the server-
  // side WHITELIST in ProfileController.securityActivity.
  const EVENT_META = {
    TWOFA_RESET:             { label: '2FA reset',              icon: '—' },
    API_KEY_MINTED:          { label: 'API key minted',         icon: '—' },
    API_KEY_REVOKED:         { label: 'API key revoked',        icon: '—' },
    SESSION_LOGOUT_ALL:      { label: 'Signed out everywhere',  icon: '—' },
    USER_FORCE_LOGOUT:       { label: 'Staff force-logout',     icon: '—' },
    WITHDRAW_REQUESTED:      { label: 'Withdrawal requested',   icon: '↗' },
    WITHDRAW_APPROVED:       { label: 'Withdrawal approved',    icon: '✓' },
    WITHDRAW_REJECTED:       { label: 'Withdrawal rejected',    icon: '✗' },
    WITHDRAW_SELF_CANCELLED: { label: 'Withdrawal cancelled',   icon: '↩' },
    DEPOSIT_COMPLETE:        { label: 'Deposit completed',      icon: '↙' },
    CHARGEBACK_OPENED:       { label: 'Chargeback opened',      icon: '⚠' },
    DISPUTE_CLEARED:         { label: 'Dispute cleared',        icon: '✓' },
    USER_BANNED:             { label: 'Account suspended',      icon: '⛔' },
    USER_UNBANNED:           { label: 'Account reinstated',     icon: '✓' },
    ADMIN_GRANTED:           { label: 'Admin role granted',     icon: '—' },
    ADMIN_REVOKED:           { label: 'Admin role revoked',     icon: '—' },
    CSR_GRANTED:             { label: 'CSR role granted',       icon: '—' },
    CSR_REVOKED:             { label: 'CSR role revoked',       icon: '—' },
    REFUND_ISSUED:           { label: 'Refund issued',          icon: '↩' }
  };
  return h('div', { className: 'profile-row' },
    h('div', { className: 'profile-row-label' }, 'Security activity'),
    h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 8, flex: 1 } },
      h('button', {
        className: 'btn btn-ghost',
        style: { padding: '5px 12px', fontSize: 11, border: '1px solid var(--border)' },
        onClick: toggle,
        title: 'Shows 2FA resets, API key mints / revocations, admin actions, withdrawals, and disputes on your account.'
      }, open ? 'Hide' : 'Show recent security events'),
      open && (rows === null
        ? h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Loading…')
        : rows.length === 0
          ? h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'No security-relevant events on record yet.')
          : h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4, width: '100%', maxHeight: 260, overflowY: 'auto' } },
              rows.map(r => {
                const meta = EVENT_META[r.eventType] || { label: r.eventType, icon: '•' };
                return h('div', {
                  key: r.id,
                  style: {
                    display: 'flex', alignItems: 'center', gap: 8, fontSize: 11,
                    padding: '6px 10px', background: 'var(--bg-elevated)',
                    border: '1px solid var(--border)', borderRadius: 4
                  },
                  title: r.summary || ''
                },
                  h('span', { style: { fontSize: 14, flexShrink: 0 } }, meta.icon),
                  h('div', { style: { flex: 1, minWidth: 0 } },
                    h('div', { style: { fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' } },
                      meta.label,
                      r.actorIsStaff && h('span', {
                        style: { marginLeft: 6, fontSize: 9, fontWeight: 800, padding: '1px 5px',
                                 borderRadius: 4, background: 'rgba(35,123,255,0.18)',
                                 color: 'var(--accent)', letterSpacing: 0.4 }
                      }, 'STAFF')
                    ),
                    r.summary && h('div', {
                      style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2,
                               overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
                    }, r.summary)
                  ),
                  h('span', {
                    style: { fontSize: 10, color: 'var(--text-muted)', minWidth: 90, textAlign: 'right' }
                  }, r.createdAt ? timeAgo(r.createdAt) : '—')
                );
              })
            )
      ),
      open && rows && rows.length > 0 && h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 4, lineHeight: 1.4 } },
        'See an event you didn\'t trigger? Sign out everywhere above, then open a support ticket with details.')
    )
  );
}

function ProfilePersonalTab({ me, profile, syncing, onSync, transactions, refreshProfile, privacy }) {
  const [editingEmail, setEditingEmail] = useState(false);
  const [emailDraft, setEmailDraft]     = useState('');
  const [emailToken, setEmailToken]     = useState('');
  const [emailResult, setEmailResult]   = useState(null);
  // Live mirror of the global email-notifications switch so the gated
  // EmailBucketMutes panel shows/hides the instant the toggle flips,
  // not on the next profile refetch. Seeded from the loaded profile;
  // re-syncs whenever the profile prop changes (re-sync, refresh).
  const [emailsOn, setEmailsOn] = useState(
    profile?.user?.emailNotificationsEnabled !== false);
  useEffect(() => {
    setEmailsOn(profile?.user?.emailNotificationsEnabled !== false);
  }, [profile?.user?.emailNotificationsEnabled]);

  const [enrolling, setEnrolling]   = useState(false);
  const [twofaSecret, setTwofaSecret] = useState('');
  const [twofaUrl, setTwofaUrl]     = useState('');
  const [twofaCode, setTwofaCode]   = useState('');
  const [twofaErr, setTwofaErr]     = useState('');
  const [twofaBusy, setTwofaBusy]   = useState(false);
  // Batch 814 — inline drawers for Disable 2FA + Regenerate Backup Codes.
  // Previous implementation used window.prompt() which blocks the page,
  // can't be styled, doesn't let password managers autofill, and gives
  // no feedback during the round-trip. `disableDrawerOpen` /
  // `regenDrawerOpen` flip on the corresponding button click; each
  // drawer has its own { code, err, busy } state keyed locally. Both
  // auto-submit at 6 digits like the enroll input (batch 813).
  const [disableDrawerOpen, setDisableDrawerOpen] = useState(false);
  const [disableCode, setDisableCode]             = useState('');
  const [disableErr, setDisableErr]               = useState('');
  const [disableBusy, setDisableBusy]             = useState(false);
  const [regenDrawerOpen, setRegenDrawerOpen]     = useState(false);
  const [regenCode, setRegenCode]                 = useState('');
  const [regenErr, setRegenErr]                   = useState('');
  const [regenBusy, setRegenBusy]                 = useState(false);
  // Backup-code UX state. `backupCodes` holds the plaintext list to show
  // after enrollment / regeneration (null while hidden). `recoveryStatus`
  // tracks how many codes the user has remaining so we can nag them once
  // they're down to 3. Both are purely client-side — the server never
  // returns plaintext codes except in the response that mints them.
  const [backupCodes, setBackupCodes] = useState(null);
  const [recoveryStatus, setRecoveryStatus] = useState(null);
  const refreshRecoveryStatus = useCallback(async () => {
    try {
      const s = await fetch2faRecoveryStatus();
      if (s) setRecoveryStatus(s);
    } catch (_) {}
  }, []);
  useEffect(() => { if (profile?.twoFactorEnabled) refreshRecoveryStatus(); }, [profile?.twoFactorEnabled, refreshRecoveryStatus]);

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
    // Batch 747 — client-side pre-flight. Mirrors the server-side
    // regex in ProfileController.TRADE_URL_RE so users get immediate
    // "that doesn't look right" feedback instead of a network round-trip.
    // Empty string is the explicit "remove my trade URL" path and
    // skips validation. Trailing whitespace is stripped upstream.
    if (raw && raw.length > 0) {
      const re = /^https:\/\/steamcommunity\.com\/tradeoffer\/new\/\?partner=\d{1,10}&token=[A-Za-z0-9_-]{1,16}$/;
      if (!re.test(raw)) {
        setTradeUrlErr('That doesn\'t look like a Steam trade URL. It should look like https://steamcommunity.com/tradeoffer/new/?partner=…&token=…');
        setTradeUrlBusy(false);
        return;
      }
    }
    try {
      const res = await setTradeUrl(raw);
      if (res.code || res.error) { setTradeUrlErr(res.message || res.error); return; }
      setEditingTradeUrl(false); setTradeUrlDraft('');
      // Pre-fix: success was visually terminal via exit-edit-mode but
      // gave no toast — and the empty-string path SILENTLY removed the
      // user's trade URL, which gates checkout/cart/buy-now. A removal
      // with no toast is the most disorienting failure mode here, so
      // we surface a distinct toast for each branch. ProfilePersonalTab
      // receives `profile` as a prop so we can't mutate it in place;
      // the parent refetches on the next tab switch.
      toast(raw && raw.length > 0
        ? 'Steam trade URL saved — sellers can now send your items.'
        : 'Steam trade URL removed — checkout will be blocked until you set a new one.',
        'ok');
    } finally { setTradeUrlBusy(false); }
  };

  const saveEmail = async () => {
    setEmailResult(null);
    const trimmed = emailDraft.trim();
    if (!trimmed) return;
    // Batch 748 — pre-flight email format check. The server still
    // runs its own validation (Jakarta @Email on the DTO), so this
    // is belt-and-braces UX polish that saves a round-trip when the
    // user obviously typo'd.
    const re = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
    if (!re.test(trimmed)) {
      setEmailResult({ err: 'That doesn\'t look like a valid email address (name@domain.tld).' });
      return;
    }
    const res = await setEmail(trimmed);
    if (res.code || res.error) { setEmailResult({ err: res.message || res.error }); return; }
    setEmailResult({ ok: true, token: res.token });
    setEditingEmail(false);
  };
  const confirmEmail = async (tokenArg) => {
    // Batch 893 — accept an optional token arg so the auto-submit on
    // `/profile?verify=<token>` deep-link can bypass the state race
    // (setEmailToken + confirmEmail in the same tick would otherwise
    // see stale empty state). Explicit button click still uses the
    // input value.
    const raw = (tokenArg != null ? tokenArg : emailToken).trim();
    if (!raw) return;
    const res = await verifyEmail(raw);
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
  // Batch 813 — accept an optional code arg so the auto-submit path
  // (fires from onChange closure) can pass the just-typed value
  // directly without racing state updates. Falls back to twofaCode
  // state for the explicit "Confirm & Enable" button.
  const finishEnroll = async (codeArg) => {
    setTwofaErr('');
    const code = typeof codeArg === 'string' ? codeArg : twofaCode;
    if (!/^\d{6}$/.test(code)) { setTwofaErr('Enter the 6-digit code'); return; }
    setTwofaBusy(true);
    try {
      const res = await confirm2fa(code);
      if (res.code || res.error) { setTwofaErr(res.message || res.error); return; }
      setEnrolling(false); setTwofaCode(''); setTwofaSecret(''); setTwofaUrl('');
      // Show the 10 backup codes inline — last chance for the user to
      // copy / download / print them. The server never exposes the
      // plaintext again; only the hash list stays on the user row.
      if (Array.isArray(res.recoveryCodes) && res.recoveryCodes.length) {
        setBackupCodes(res.recoveryCodes);
      }
      refreshRecoveryStatus();
      toast('Two-factor authentication enabled — save your backup codes below.', 'ok');
    } finally { setTwofaBusy(false); }
  };
  // Batch 814 — inline drawer submit. Accepts either the 6-digit TOTP
  // code OR a backup/recovery code. Server validates both shapes.
  const submitDisable = async (codeArg) => {
    const code = typeof codeArg === 'string' ? codeArg : disableCode;
    setDisableErr('');
    if (!code || code.trim().length < 6) {
      setDisableErr('Enter a 6-digit code or a backup code');
      return;
    }
    setDisableBusy(true);
    try {
      const res = await disable2fa(code.trim());
      if (res.code || res.error) {
        setDisableErr(res.message || res.error || 'Could not disable 2FA');
        return;
      }
      setBackupCodes(null);
      setRecoveryStatus(null);
      setDisableDrawerOpen(false);
      setDisableCode('');
      toast('Two-factor authentication disabled.', 'ok');
    } finally { setDisableBusy(false); }
  };
  const disableTwofa = () => {
    setDisableCode('');
    setDisableErr('');
    setDisableDrawerOpen(true);
  };
  const submitRegen = async (codeArg) => {
    const code = typeof codeArg === 'string' ? codeArg : regenCode;
    setRegenErr('');
    if (!/^\d{6}$/.test(code)) {
      setRegenErr('Enter the 6-digit code from your authenticator');
      return;
    }
    setRegenBusy(true);
    try {
      const res = await regenerate2faBackupCodes(code);
      if (res.code || res.error) {
        setRegenErr(res.message || res.error || 'Could not regenerate codes');
        return;
      }
      if (Array.isArray(res.recoveryCodes) && res.recoveryCodes.length) {
        setBackupCodes(res.recoveryCodes);
      }
      refreshRecoveryStatus();
      setRegenDrawerOpen(false);
      setRegenCode('');
      toast('Fresh backup codes minted — old codes are no longer valid.', 'ok');
    } finally { setRegenBusy(false); }
  };
  const regenerateBackupCodes = () => {
    setRegenCode('');
    setRegenErr('');
    setRegenDrawerOpen(true);
  };
  const downloadBackupCodes = () => {
    if (!backupCodes || backupCodes.length === 0) return;
    const when = new Date().toISOString().slice(0, 10);
    const body = [
      '# SkinBox — 2FA backup / recovery codes',
      '# Generated ' + new Date().toLocaleString(),
      '# Each code works exactly once. Store these somewhere safe',
      '# (password manager, printed + filed, etc).',
      '',
      ...backupCodes
    ].join('\r\n');
    const blob = new Blob([body], { type: 'text/plain;charset=utf-8' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `skinbox-2fa-backup-codes-${when}.txt`;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  // Setup checklist (batch 419). Shows the viewer how many of the four
  // trade-readiness steps they've completed + what's missing. Silent at
  // 4/4 to stay out of the way of users who are already set up. The
  // checks read from the same `profile.user.*` fields the individual
  // rows below use, so there's no redundant state.
  const setupSteps = [
    { id: 'email',    done: !!hasEmail,       label: 'Add an email address' },
    { id: 'verified', done: !!emailVerified,  label: 'Verify your email' },
    { id: 'trade',    done: !!hasTradeUrl,    label: 'Set your Steam trade URL' },
    { id: 'twofa',    done: !!has2fa,         label: 'Enable 2FA (recommended)' }
  ];
  const setupDone    = setupSteps.filter(s => s.done).length;
  const setupMissing = setupSteps.filter(s => !s.done);
  return h('div', { className: 'profile-panel' },
    // Use a real class (not an inline rgba background) so this callout does
    // NOT match the leftover `.profile-panel > div[style*="rgba(250,204,21,0.08)"]`
    // frosted-glass override (design.css ~24866) that forced it into an
    // unbalanced flex `space-between` pill — centered heading + checklist
    // stranded on the far right, plus a muddy orange blur (design review).
    // The class styling (.profile-setup-banner) keeps it a clean left-aligned
    // stacked callout.
    setupDone < 4 && h('div', {
      className: 'profile-setup-banner' +
        (setupMissing.some(s => s.id === 'trade' || s.id === 'verified') ? ' warn' : '')
    },
      h('div', { style: { fontWeight: 700, marginBottom: 6 } },
        setupMissing.some(s => s.id === 'trade') ? '⚠' : '—',
        ' Account setup · ',
        h('strong', { style: { color: 'var(--accent)' } }, `${setupDone}/4`),
        ' steps complete'),
      h('ul', {
        style: { margin: 0, paddingLeft: 18, color: 'var(--text-secondary)' }
      },
        setupSteps.map(s => h('li', {
          key: s.id,
          style: {
            textDecoration: s.done ? 'line-through' : 'none',
            color: s.done ? 'var(--text-muted)' : 'var(--text-primary)',
            marginBottom: 2
          }
        }, s.done ? '✓ ' : '◯ ', s.label))
      )
    ),
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
        }, '⎘'),
        // Steam profile deep-link (batch 404). Saves the user from
        // hand-composing the URL or navigating via Steam's search to
        // find their own profile page — useful when they need to share
        // the link externally or verify what their public profile
        // actually shows.
        h('a', {
          href: 'https://steamcommunity.com/profiles/' + me.steamId64,
          target: '_blank',
          rel: 'noopener noreferrer',
          className: 'btn btn-ghost',
          style: { padding: '3px 8px', fontSize: 10, border: '1px solid var(--border)', textDecoration: 'none' },
          title: 'Open your Steam profile in a new tab'
        }, '↗ Steam')
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
                  toast('Stall link copied — paste into Discord, Steam groups, etc.', 'ok');
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

    // Users I've blocked — same compact list pattern. Only shown when
    // the user has actually blocked someone so the common case doesn't
    // see an empty "Blocked 0" row (batch 344).
    me?.id && h(BlockedListRow, null),

    // Recent sign-ins (batch 569). Security-hygiene surface — the user
    // can spot "wait, I didn't sign in from Argentina" and immediately
    // reset 2FA / change password. Collapsible to keep the tab clean
    // for the majority who never glance at it.
    me?.id && h(SignInHistoryRow, null),

    // Batch 715 — security-activity feed. Complements sign-in-history
    // with the other sensitive events (2FA, API keys, admin actions,
    // withdrawals, disputes). Same collapsed-by-default pattern.
    me?.id && h(SecurityActivityRow, null),

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
          }, emailVerified ? 'Verified' : 'Unverified'),
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
            // Batch 771 — standard autofill hints so browsers +
            // password managers can surface the user's email address.
            autoComplete: 'email',
            autoCapitalize: 'off',
            spellCheck: false,
            value: emailDraft, onChange: e => setEmailDraft(e.target.value)
          }),
          h('button', { className: 'buy-btn', onClick: saveEmail }, 'Save'),
          h('button', { className: 'btn btn-ghost', style: { padding: '6px 10px', fontSize: 11 }, onClick: () => setEditingEmail(false), 'aria-label': 'Cancel email edit' }, '✕')
        ),
        // If /email returned a token (dev-mode), show the verify input
        emailResult?.token && h('div', { style: { padding: 10, background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 6, width: '100%' } },
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 6 } },
            'Dev mode: use this token to verify the email.'),
          h('div', { className: 'mono', style: { fontSize: 11, color: 'var(--accent)', marginBottom: 6 } }, emailResult.token),
          h('div', { style: { display: 'flex', gap: 6 } },
            h('input', { className: 'price-input', style: { flex: 1 }, placeholder: 'Paste token', value: emailToken, onChange: e => setEmailToken(e.target.value) }),
            h('button', { className: 'buy-btn', onClick: () => confirmEmail() }, 'Verify')
          )
        ),
        emailResult?.verified && h('div', { style: { fontSize: 11, color: 'var(--green)' } }, 'Email verified'),
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
            style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
            title: 'Copy your trade URL to the clipboard',
            onClick: async () => {
              const url = profile?.user?.tradeUrl || '';
              if (!url) return;
              try {
                if (navigator.clipboard?.writeText) {
                  await navigator.clipboard.writeText(url);
                  toast('Trade URL copied.', 'ok');
                } else {
                  window.prompt('Copy this trade URL:', url);
                }
              } catch (_) { window.prompt('Copy this trade URL:', url); }
            }
          }, '⧉ Copy'),
          hasTradeUrl && h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
            // Confirm — removing the trade URL blocks checkout and trades
            // until a new one is set, so a single misclick shouldn't do it.
            onClick: () => {
              if (confirm('Remove your Steam trade URL? Checkout and trades will be blocked until you add a new one.')) {
                saveTradeUrl('');
              }
            }
          }, 'Remove')
        ),
        editingTradeUrl && h('div', { style: { display: 'flex', gap: 8, width: '100%', flexWrap: 'wrap' } },
          h('input', {
            type: 'url',
            className: 'wallet-amount-input',
            'aria-label': 'Steam trade URL',
            placeholder: 'https://steamcommunity.com/tradeoffer/new/?partner=…&token=…',
            value: tradeUrlDraft,
            style: { flex: 1, minWidth: 240, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", fontSize: 12 },
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
        !enrolling && has2fa && h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 10 } },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' } },
            h('span', { style: { fontSize: 12, color: 'var(--green)', fontWeight: 700 } }, 'Enabled'),
            // Recovery-code status chip. Critical when ≤3 remain — without
            // backup codes a user who loses their device is locked out of
            // withdrawals. Amber at 4-5, red at 0-3.
            recoveryStatus && h('span', {
              style: {
                fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
                background: recoveryStatus.remainingCodes <= 3
                  ? 'rgba(248,113,113,0.15)'
                  : (recoveryStatus.remainingCodes <= 5 ? 'rgba(251,191,36,0.15)' : 'var(--bg-elevated)'),
                color: recoveryStatus.remainingCodes <= 3
                  ? 'var(--red)'
                  : (recoveryStatus.remainingCodes <= 5 ? '#fbbf24' : 'var(--text-muted)'),
                border: '1px solid var(--border)'
              },
              title: 'Unused one-time backup codes. Regenerate before the count reaches zero.'
            },
              recoveryStatus.remainingCodes <= 3 ? '⚠ ' : '',
              recoveryStatus.remainingCodes, ' backup code', recoveryStatus.remainingCodes === 1 ? '' : 's', ' left'
            ),
            h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 }, onClick: regenerateBackupCodes, title: 'Mint a fresh set of backup codes. The current set becomes invalid.' }, 'Regenerate codes'),
            h('button', { className: 'btn btn-ghost', style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 }, onClick: disableTwofa }, 'Disable')
          ),
          // Batch 814 — inline drawer: Disable 2FA. Replaces window.prompt()
          // so the TOTP input gets password-manager autofill, visible
          // error messaging, and a proper busy state. Accepts either a
          // 6-digit app code OR a backup/recovery code (server validates
          // both shapes). Auto-submits on 6-digit autofill match.
          disableDrawerOpen && h('div', {
            style: {
              width: '100%', marginTop: 8,
              padding: 14, borderRadius: 8,
              background: 'rgba(248,113,113,0.06)',
              border: '1px solid rgba(248,113,113,0.35)'
            }
          },
            h('div', { style: { fontSize: 12, fontWeight: 700, color: 'var(--red)', marginBottom: 4 } },
              'Disable two-factor authentication'),
            h('div', { style: { fontSize: 11, color: 'var(--text-secondary)', marginBottom: 10, lineHeight: 1.5 } },
              "Enter your current 6-digit code OR one of your backup / recovery codes. 2FA will be removed from this account immediately."),
            h('input', {
              className: 'wallet-amount-input',
              type: 'text',
              inputMode: 'text',
              autoComplete: 'one-time-code',
              autoCapitalize: 'off',
              spellCheck: false,
              maxLength: 32,
              'aria-label': '6-digit authenticator code or backup code',
              placeholder: '000000 or backup code',
              value: disableCode,
              onChange: (e) => {
                const v = e.target.value;
                setDisableCode(v);
                // Auto-submit if it looks like a pasted full TOTP code.
                if (/^\d{6}$/.test(v.trim()) && !disableBusy) submitDisable(v.trim());
              },
              onKeyDown: (e) => { if (e.key === 'Enter' && !disableBusy) submitDisable(); }
            }),
            disableErr && h('div', { className: 'wallet-error', style: { marginTop: 6 } }, disableErr),
            h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '5px 12px', fontSize: 11 },
                disabled: disableBusy,
                onClick: () => { setDisableDrawerOpen(false); setDisableCode(''); setDisableErr(''); }
              }, 'Cancel'),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.4)', color: 'var(--red)', padding: '5px 12px', fontSize: 11 },
                disabled: disableBusy || !disableCode.trim(),
                onClick: () => submitDisable()
              }, disableBusy ? 'Disabling…' : 'Disable 2FA')
            )
          ),
          // Batch 814 — inline drawer: Regenerate backup codes. Same
          // pattern as the Disable drawer above, but accepts only a
          // TOTP code (the backup-code path would let an attacker with
          // one recovered code mint a fresh set, which is bad).
          regenDrawerOpen && h('div', {
            style: {
              width: '100%', marginTop: 8,
              padding: 14, borderRadius: 8,
              background: 'rgba(251,191,36,0.08)',
              border: '1px solid rgba(251,191,36,0.4)'
            }
          },
            h('div', { style: { fontSize: 12, fontWeight: 700, color: '#fbbf24', marginBottom: 4 } },
              'Regenerate backup codes'),
            h('div', { style: { fontSize: 11, color: 'var(--text-secondary)', marginBottom: 10, lineHeight: 1.5 } },
              "Enter your current 6-digit authenticator code. Your existing backup codes will be INVALIDATED and a new set issued — save the new set somewhere safe."),
            h('input', {
              className: 'wallet-amount-input',
              type: 'text',
              inputMode: 'numeric',
              autoComplete: 'one-time-code',
              autoCapitalize: 'off',
              spellCheck: false,
              maxLength: 6,
              'aria-label': '6-digit authenticator code',
              placeholder: '000000',
              value: regenCode,
              onChange: (e) => {
                const v = e.target.value.replace(/\D/g, '').slice(0, 6);
                setRegenCode(v);
                if (v.length === 6 && !regenBusy) submitRegen(v);
              },
              onKeyDown: (e) => { if (e.key === 'Enter' && !regenBusy) submitRegen(); }
            }),
            regenErr && h('div', { className: 'wallet-error', style: { marginTop: 6 } }, regenErr),
            h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '5px 12px', fontSize: 11 },
                disabled: regenBusy,
                onClick: () => { setRegenDrawerOpen(false); setRegenCode(''); setRegenErr(''); }
              }, 'Cancel'),
              h('button', {
                className: 'btn btn-accent',
                style: { padding: '5px 12px', fontSize: 11 },
                disabled: regenBusy || regenCode.length !== 6,
                onClick: () => submitRegen()
              }, regenBusy ? 'Regenerating…' : 'Mint new codes')
            )
          ),
          // Backup-codes panel. Shown exactly once, immediately after
          // enrollment OR regeneration. The plaintext codes can never be
          // retrieved again — the user must copy, download, or print them
          // before dismissing the panel.
          backupCodes && backupCodes.length > 0 && h('div', {
            style: {
              width: '100%', marginTop: 6,
              padding: 14, borderRadius: 8,
              background: 'rgba(251,191,36,0.08)',
              border: '1px solid rgba(251,191,36,0.4)'
            }
          },
            h('div', { style: { fontSize: 12, fontWeight: 800, color: '#fbbf24', marginBottom: 4 } },
              'Save your backup codes NOW'),
            h('div', { style: { fontSize: 11, color: 'var(--text-secondary)', marginBottom: 10, lineHeight: 1.5 } },
              'Each code works exactly once. Save them somewhere safe — a password manager, printed + filed, or in your device\'s secure notes. We will NOT show them again.'),
            h('div', {
              className: 'mono',
              style: {
                display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                gap: 6, fontSize: 13,
                background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                borderRadius: 6, padding: 10, userSelect: 'all'
              }
            },
              backupCodes.map(c => h('span', { key: c, style: { color: 'var(--text-primary)' } }, c))
            ),
            h('div', { style: { display: 'flex', gap: 10, marginTop: 10, flexWrap: 'wrap' } },
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
                onClick: async () => {
                  // Critical safety surface — a silent-failure here used
                  // to leave users without their 2FA recovery codes if
                  // clipboard was unavailable (insecure context, denied
                  // permission, focus loss). Now mirrors the API-key
                  // copy path: prompt fallback both when the API is
                  // missing AND when the write throws, plus an error
                  // toast pointing at the Download button so users
                  // never lose their codes silently.
                  const text = backupCodes.join('\n');
                  try {
                    if (navigator.clipboard?.writeText) {
                      await navigator.clipboard.writeText(text);
                      toast('Backup codes copied to clipboard.', 'ok');
                    } else {
                      window.prompt('Copy your backup codes:', text);
                    }
                  } catch (_) {
                    window.prompt('Copy your backup codes:', text);
                  }
                }
              }, '⎘ Copy'),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
                onClick: downloadBackupCodes,
                title: 'Download as a .txt file'
              }, '⇣ Download'),
              h('button', {
                className: 'btn btn-accent',
                style: { padding: '5px 12px', fontSize: 11 },
                onClick: () => setBackupCodes(null)
              }, 'I\'ve saved them')
            )
          )
        ),
        enrolling && h('div', { className: 'twofa-enroll' },
          h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 } },
            'Add this to Google Authenticator, Authy, or 1Password — tap “Add to authenticator app” on mobile, or enter the secret key manually:'),
          // SECURITY: previously the QR was rendered by sending the otpauth URL
          // (which embeds this very secret) to a THIRD-PARTY image service
          // (api.qrserver.com) — leaking the TOTP seed off-device into their
          // logs. Replaced with an on-device deep-link + the manual secret, so
          // the seed never leaves the browser. (A locally-generated QR is the
          // ideal follow-up for scan-with-another-phone convenience.)
          h('div', { style: { display: 'flex', flexDirection: 'column', gap: 10 } },
            h('div', null,
              h('div', { style: { fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 } }, 'Secret key'),
              h('div', { className: 'mono', style: { fontSize: 14, color: 'var(--accent)', wordBreak: 'break-all', userSelect: 'all' } }, twofaSecret)
            ),
            h('a', {
              href: twofaUrl,
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)', alignSelf: 'flex-start', fontSize: 12 }
            }, '📱 Add to authenticator app')
          ),
          h('div', { className: 'wallet-input-label', style: { marginTop: 14 } }, '6-digit code from your app'),
          h('input', {
            className: 'wallet-amount-input',
            type: 'text',
            inputMode: 'numeric',
            // Batch 770 — `autocomplete="one-time-code"` lets iOS Safari
            // and Android Chrome surface TOTP codes from Messages /
            // password-manager suggest bars. `autocapitalize=off` +
            // `spellcheck=false` keep the bar clean for this numeric
            // field. No change for users without an autofill source.
            autoComplete: 'one-time-code',
            autoCapitalize: 'off',
            spellCheck: false,
            maxLength: 6,
            placeholder: '000000',
            value: twofaCode,
            // Batch 813 — auto-submit the moment 6 digits are in the
            // field. Autofill from iOS Messages / Android pastes the
            // full code at once, so waiting for the user to also click
            // Confirm is a needless extra tap. Still honours the busy
            // guard so a duplicate submit is impossible mid-flight.
            // Passes the code explicitly so we don't race React's
            // state update timing.
            onChange: (e) => {
              const v = e.target.value.replace(/\D/g, '').slice(0, 6);
              setTwofaCode(v);
              if (v.length === 6 && !twofaBusy) {
                finishEnroll(v);
              }
            }
          }),
          twofaErr && h('div', { className: 'wallet-error' }, twofaErr),
          h('div', { style: { display: 'flex', gap: 10, marginTop: 10 } },
            h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, onClick: async () => {
              // Tell the server to drop the staged secret — else email verify/resend
              // stays wedged with TWOFA_IN_PROGRESS. Best-effort; clear the UI either way.
              try { await cancel2fa(); } catch (_) {}
              setEnrolling(false); setTwofaSecret(''); setTwofaUrl(''); setTwofaCode(''); setTwofaErr('');
            } }, 'Cancel'),
            h('button', { className: 'btn btn-accent', disabled: twofaBusy || twofaCode.length !== 6, onClick: finishEnroll }, 'Confirm & Enable')
          )
        )
      )
    ),

    // "Log out on every device" — single POST bumps the user's session_epoch
    // on the server; every other live session 401s on its next request via
    // SessionEpochFilter. Current session is invalidated inline, so the UI
    // falls back to anon after the button is clicked.
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Active Sessions'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 6 } },
        h('div', { style: { fontSize: 12, color: 'var(--text-muted)' } },
          'Sign out of every browser and device where you are currently logged in.'),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 14px', fontSize: 11 },
          onClick: async () => {
            if (!window.confirm('Sign out of every device? You will have to sign in again via Steam.')) return;
            try {
              const res = await fetch('/api/auth/steam/logout-all', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'X-CSRF-Token': (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '' }
              });
              if (res.ok || res.status === 204 || res.status === 401) {
                toast('Signed out everywhere — redirecting…', 'ok');
                setTimeout(() => { window.location.href = '/'; }, 600);
              } else {
                toast('Could not sign out of all devices. Try again.', 'err');
              }
            } catch (_) {
              toast('Network error signing out of all devices.', 'err');
            }
          }
        }, 'Sign out of every device')
      )
    ),

    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Steam Inventory Size'),
      h('div', { className: 'profile-row-value mono' }, (profile?.user?.steamInventorySize ?? 0) + ((profile?.user?.steamInventorySize ?? 0) === 1 ? ' item' : ' items'))
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
    // loaded profile; click optimistically and commits via PUT. The
    // per-bucket mutes underneath let a user keep trade emails but
    // silence watchlist alerts (etc) — layered on top of the global
    // toggle, only consulted when emails are globally ON. The gate
    // uses the live `emailsOn` mirror so flipping the master toggle
    // shows/hides the bucket panel immediately, instead of waiting for
    // a profile refetch (which only fires on re-sync from Steam).
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Email notifications'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'stretch', gap: 10 } },
        h(EmailPrefToggle, {
          initial: profile?.user?.emailNotificationsEnabled !== false,
          onChange: (v) => setEmailsOn(!!v)
        }),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } },
          'Security + account-state emails (verification, withdrawal, ban) always send regardless of the settings below.'),
        // Only render the granular mutes when the global switch is ON —
        // the buckets are meaningless when *every* email is suppressed.
        emailsOn && h(EmailBucketMutes, null)
      )
    ),

    // Sign out everywhere (batch 697) — security recovery action. Our
    // security-alert emails point users here as the first response to
    // a suspicious new-device sign-in or an unknown API key mint. Bumps
    // the user's sessionEpoch so every live session (including this one)
    // 401s on the next request.
    h('div', { className: 'profile-row' },
      h('div', { className: 'profile-row-label' }, 'Sign out everywhere'),
      h('div', { className: 'profile-row-value', style: { flexDirection: 'column', alignItems: 'flex-start', gap: 6 } },
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 11 },
          onClick: async () => {
            if (!confirm('Sign out of every device?\n\nThis will end your session here and on every other browser or bot you\'ve signed in from. You\'ll need to sign in with Steam again.')) return;
            try {
              const { signOutEverywhere } = await import('./api.js');
              await signOutEverywhere();
            } catch (_) { /* 401 on self is fine — we just nuked the session */ }
            // Land on the marketplace — the SPA session-expired banner
            // will fire on the first /api/* fetch after this.
            location.href = '/';
          },
          title: 'Invalidates every active session token for this account, including the current one.'
        }, 'Sign out everywhere'),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 4 } },
          'Use this if you got a sign-in alert or API-key alert you didn\'t recognise.')
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
                'Deletion requested ',
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
                    toast(res.message || res.error || 'Could not cancel deletion request', 'err');
                    return;
                  }
                  // Batch 929 — replace location.reload() with a profile
                  // re-fetch, plus a confirmation toast. Reload was
                  // jarring (nav bar collapses + flash-of-unstyled-content
                  // + loses scroll position). The in-place refresh keeps
                  // the user on the Personal tab.
                  try { refreshProfile && await refreshProfile(); } catch (_) {}
                  toast('Deletion request cancelled — your account is active again.', 'ok');
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
                    toast(res.message || res.error || 'Could not request account deletion', 'err');
                    return;
                  }
                  // Batch 929 — same in-place refresh as the cancel path,
                  // with a toast that names the SLO (1-2 business days).
                  try { refreshProfile && await refreshProfile(); } catch (_) {}
                  toast('Deletion request filed — staff will review within 1-2 business days.', 'ok');
                },
                title: 'GDPR / data subject deletion request'
              }, 'Delete account'),
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
            h('div', { className: 'wallet-tx-amt out' }, '−' + (privacy ? '$•••••' : fmt(tx.amount))),
            h('div', { style: { fontSize: 10, color: 'var(--text-muted)' } }, timeAgo(tx.createdAt))
          )
        ))
      );
    })()
  );
}

// CSFloat-1:1 — a user's profile leads with their listings. This tab
// surfaces the signed-in seller's *active* listings right on the profile
// (csfloat shows a user's stall/inventory front-and-centre) without
// forcing a hop to /me/stall. It reuses the exact /api/listings/my-stall
// feed the MyStall page consumes — no new backend. Full inline edit /
// relist / cancel still lives in MyStallModal; this tab is a read-first
// glance with a one-click "Manage in your stall" deep-link.
//
// State model mirrors the sibling tabs:
//   data === null            → still loading (spinner, like ProfileOffersTab)
//   err   === true           → fetch failed (error card + Retry)
//   data  === []             → no active listings (empty-inline + CTA,
//                              like ProfileBuyOrdersTab)
// `total` feeds the same "Showing most recent N of M" overflow banner
// the MyStall Active tab uses for prolific sellers past the display cap.
function ProfileListingsTab({ me }) {
  const [data, setData]   = useState(null);   // null = loading; [] = empty; [...] = rows
  const [total, setTotal] = useState(0);
  const [err, setErr]     = useState(false);

  // Race-guarded load (mirrors the ProfileOffersTab / fetchProfile `alive`
  // pattern): a stale resolve after the modal closes or after `me` flips
  // to a different user must not write this tab's state. We fetch the
  // my-stall endpoint directly (rather than fetchMyStallWithTotal, which
  // swallows non-2xx into an empty array) so a genuine failure surfaces a
  // Retry affordance instead of masquerading as "no listings".
  const aliveRef = useRef(true);
  const load = useCallback(async () => {
    setErr(false);
    setData(null);
    try {
      const res = await fetch('/api/listings/my-stall', { credentials: 'same-origin' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const items = await res.json();
      if (!aliveRef.current) return;
      const rows = Array.isArray(items) ? items : [];
      const totalHeader = res.headers.get('X-Total-Count');
      const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
      setData(rows);
      setTotal(Number.isFinite(parsed) ? parsed : rows.length);
    } catch (_) {
      if (!aliveRef.current) return;
      setErr(true);
    }
  }, []);
  useEffect(() => {
    aliveRef.current = true;
    load();
    return () => { aliveRef.current = false; };
  }, [load, me?.id]);

  // Error card — recoverable, mirrors the "Retry" affordance used by
  // other self-fetching panels. Distinct from the empty state so a
  // network blip never reads as "you have no listings".
  if (err) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Couldn’t load your listings'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Something went wrong fetching your active listings. Check your connection and try again.'),
    h('button', { className: 'btn btn-accent', onClick: load }, 'Retry')
  );

  // Loading — single spinner, identical to ProfileOffersTab's null guard.
  if (data === null) return h('div', { className: 'spinner' });

  // Empty — no active listings. CTA routes to the inventory so the seller
  // can list an item (matches the prompt's empty-state copy). Uses the
  // same empty-inline shell + MaterialIcon as ProfileBuyOrdersTab.
  if (data.length === 0) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'storefront', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'No active listings'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'List an item from your inventory and it’ll show up here and on your public stall.'),
    h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
      h('a', { className: 'btn btn-accent', href: '/sell' }, '+ List an item'),
      h('a', { className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)' },
        href: '/me/stall' }, 'Open your stall')
    )
  );

  // Cap the rendered grid to match the MyStall Active display ceiling
  // (the endpoint already returns the most-recent 500). The overflow
  // banner explains the cap and points power-sellers at the full stall.
  const DISPLAY_CAP = 500;
  const shown = data.slice(0, DISPLAY_CAP);
  return h('div', null,
    // Header strip: live count + a manage deep-link to the full stall
    // (inline edit / relist / cancel / analytics all live there).
    h('div', {
      style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12, flexWrap: 'wrap' }
    },
      h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } },
        h('strong', { style: { color: 'var(--text-primary)' } }, total),
        ' active listing', total === 1 ? '' : 's'),
      h('a', {
        className: 'btn btn-ghost',
        style: { marginLeft: 'auto', border: '1px solid var(--border)', padding: '6px 12px', fontSize: 12 },
        href: '/me/stall',
        title: 'Open your full stall to edit prices, relist, or cancel listings.'
      }, 'Manage in your stall')
    ),
    // Overflow banner — mirrors the MyStall Active "Showing most recent
    // 500 of N" copy when a prolific seller crosses the display cap.
    total > DISPLAY_CAP && h('div', {
      style: {
        padding: '8px 12px', marginBottom: 12, fontSize: 12,
        color: 'var(--text-muted)',
        background: 'var(--bg-elevated, rgba(255,255,255,0.02))',
        border: '1px solid var(--border)', borderRadius: 6
      }
    }, `Showing most recent ${DISPLAY_CAP} of ${total} — open your stall to see them all.`),
    // Grid of listing cards — reuses GridCard + the .listing-grid layout
    // the marketplace and watchlist use. Cards deep-link to /item/:id via
    // the SPA router (same as the Recently-viewed rail). `meId` lets the
    // card show the "You're winning" auction chip consistently.
    h('div', {
      className: 'listing-grid',
      style: { gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }
    },
      shown.map(l => h(GridCard, {
        key: l.id,
        listing: l,
        meId: me?.id,
        onClick: () => { if (l?.item?.id) navigate('/item/' + l.item.id); }
      }))
    )
  );
}

function ProfileTransactionsTab({ transactions, privacy }) {
  const [txFilter, setTxFilter] = useState('ALL');
  const [txSearch, setTxSearch] = useState('');
  // Month picker per CSFloat Visual Manual §24 — narrows the ledger to a
  // single calendar month. Stored as YYYY-MM string ('' = all months).
  // Default blank so opening the tab shows the full history; user picks
  // a month explicitly when they want a specific period.
  const [txMonth, setTxMonth] = useState('');
  if (!transactions || transactions.length === 0) {
    // Batch 902 — concrete CTAs for new users. Before: a bare "No
    // transactions yet." dead-end. Now: three on-ramps that cover
    // the actual paths to a first ledger entry (deposit → buy, list
    // → sell, or set up a standing buy order).
    return h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
      h('div', { style: { fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
        'No transactions yet'),
      h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 14px', lineHeight: 1.5 } },
        'Deposits, purchases, sales, refunds, and withdrawals all land here with a running ledger. Top up to start, or list an item you own.'),
      h('div', { style: { display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' } },
        h('a', { className: 'btn btn-accent', href: '/wallet', style: { padding: '6px 14px', fontSize: 12 } },
          '＋ Deposit funds'),
        h('a', { className: 'btn btn-ghost', href: '/sell',
          style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 12 } },
          'Sell an item'),
        h('a', { className: 'btn btn-ghost', href: '/market',
          style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 12 } },
          'Browse marketplace')
      ));
  }
  // 30-day window summary — lightweight ledger header so a power user
  // can see "I spent $X / earned $Y in the last month" without opening
  // the CSV. COMPLETED-only so pending withdrawals + failed refunds
  // don't skew the figures.
  const now = Date.now();
  const recent = transactions.filter(t => t.status === 'COMPLETED' && (now - (t.createdAt || 0)) < 30 * 86400_000);
  // Credit / debit type sets — kept in sync with the WalletModal history
  // (see ~line 13929). The backend emits DEPOSIT / SALE / REFUND /
  // ADJUSTMENT_CREDIT inbound and PURCHASE / WITHDRAW(AL) /
  // ADJUSTMENT_DEBIT outbound. The old sets referenced types the backend
  // never emits (ADMIN_CREDIT, CSR_CREDIT, BUY_ORDER_REFUND, ADMIN_DEBIT,
  // AUCTION_HOLD) AND missed the real ADJUSTMENT_* pair — so a staff
  // wallet adjustment was silently dropped from the 30-day net figure.
  const CREDIT_TYPES = new Set(['DEPOSIT','SALE','REFUND','ADJUSTMENT_CREDIT']);
  const DEBIT_TYPES  = new Set(['PURCHASE','WITHDRAW','WITHDRAWAL','ADJUSTMENT_DEBIT']);
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
    WITHDRAW: new Set(['WITHDRAW']),
    REFUND:   new Set(['REFUND','BUY_ORDER_REFUND']),
    ADJUST:   new Set(['ADMIN_CREDIT','ADMIN_DEBIT','ADJUSTMENT_CREDIT','CSR_CREDIT'])
  };
  const countFor = (key) => {
    const set = FILTER_TYPES[key];
    return set == null ? transactions.length : transactions.filter(t => set.has(t.type)).length;
  };
  // Compute the distinct YYYY-MM buckets present in the ledger so the
  // dropdown only offers months the user actually has activity in.
  // Cheap: O(n) over an in-memory list.
  const monthOptions = (() => {
    const set = new Set();
    transactions.forEach(t => {
      if (!t.createdAt) return;
      const d = new Date(t.createdAt);
      const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
      set.add(key);
    });
    return Array.from(set).sort().reverse();
  })();
  const formatMonthLabel = (key) => {
    if (!key) return '';
    const [y, m] = key.split('-').map(Number);
    return new Date(y, m - 1, 1).toLocaleString(undefined, { month: 'short', year: 'numeric' });
  };
  const visibleTx = (() => {
    const set = FILTER_TYPES[txFilter];
    let rows = set == null ? transactions : transactions.filter(t => set.has(t.type));
    // Month filter narrows to a single calendar month (YYYY-MM).
    if (txMonth) {
      rows = rows.filter(t => {
        if (!t.createdAt) return false;
        const d = new Date(t.createdAt);
        const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
        return key === txMonth;
      });
    }
    const q = (txSearch || '').trim().toLowerCase();
    if (!q) return rows;
    // Search across description, type, listing id and stripe reference so
    // a user hunting "why was I charged $X" can grep by listing id / words
    // from the description. All client-side — the list is already in memory.
    return rows.filter(t => {
      const s = [t.description, t.type, t.stripeReference, t.listingId != null ? String(t.listingId) : '']
        .filter(Boolean).join(' ').toLowerCase();
      return s.includes(q);
    });
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
          { id: 'WITHDRAW', label: 'Withdrawals' },
          { id: 'REFUND',   label: 'Refunds' },
          { id: 'ADJUST',   label: 'Adjustments' }
        ].map(opt => {
          const n = countFor(opt.id);
          return h('button', {
            key: opt.id,
            className: `wallet-tx-filter-chip ${txFilter === opt.id ? 'active' : ''}`,
            // Batch 939 — aria-pressed exposes selected state to screen
            // readers (visual-only .active CSS didn't). Applied across
            // every toggle-style filter chip.
            'aria-pressed': txFilter === opt.id,
            disabled: n === 0 && opt.id !== 'ALL',
            onClick: () => setTxFilter(opt.id),
            title: `${n} row${n === 1 ? '' : 's'} match this filter`
          }, opt.label, ' ', h('span', { style: { opacity: 0.6, marginLeft: 4 } }, '· ', n));
        })
      ),
      monthOptions.length > 0 && h('select', {
        className: 'sort-select',
        value: txMonth,
        onChange: e => setTxMonth(e.target.value),
        style: { flex: '0 1 150px', minWidth: 130, fontSize: 12 },
        title: 'Filter to a specific month',
        'aria-label': 'Filter transactions by month'
      },
        h('option', { value: '' }, 'All months'),
        monthOptions.map(key => h('option', { key, value: key }, formatMonthLabel(key)))
      ),
      h('input', {
        className: 'price-input',
        style: { flex: '0 1 180px', minWidth: 140, fontSize: 12 },
        placeholder: 'Search description / id',
        value: txSearch,
        onChange: e => setTxSearch(e.target.value),
        'aria-label': 'Search transactions'
      }),
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        // Honour the active month filter so a user who narrowed the
        // visible list to "Jan 2026" doesn't get a 5,000-row dump
        // covering every month. Backend already accepts ?month=YYYY-MM
        // and the WalletController falls back to full-history for an
        // empty/malformed value, so the URL stays valid either way.
        href: txMonth
          ? `/api/wallet/transactions.csv?month=${encodeURIComponent(txMonth)}`
          : '/api/wallet/transactions.csv',
        title: txMonth
          ? `Download transactions for ${formatMonthLabel(txMonth)} as a CSV`
          : 'Download every transaction as a CSV'
      }, '⇣ CSV')
    ),
  visibleTx.length === 0
    ? h('div', { className: 'empty-inline', style: { marginTop: 8 } },
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)' } }, 'No transactions match this filter.'))
    : h('table', { className: 'db-table db-table--list' },
      h('thead', null, h('tr', null,
        h('th', null, 'ID'),
        h('th', null, 'Type'),
        // width:100% on the description column makes it the flexible one that
        // absorbs the table's slack (table-layout:auto), so ID/Type/Amount/
        // Status shrink to their content and Description gets the room it needs
        // instead of wrapping to 5 lines while Type balloons. Set on the <th>
        // AND the <td> so the column resolves wide regardless of which row the
        // sizer samples.
        h('th', { style: { width: '100%' } }, 'Description'),
        h('th', { className: 'right' }, 'Amount'),
        h('th', { className: 'right' }, 'Status')
      )),
      h('tbody', null,
        visibleTx.map(tx => h('tr', { key: tx.id, className: 'db-row' },
          h('td', { className: 'db-rank' }, '#' + tx.id),
          h('td', { style: { fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)' } }, tx.type),
          h('td', { style: { fontSize: 11, color: 'var(--text-muted)', width: '100%' } }, tx.description || tx.stripeReference),
          (() => {
            // Signed + coloured amount so a credit and a debit of the
            // same value are visually distinct — parity with the
            // WalletModal history rows (~line 14127), which the bare
            // fmt() here lacked.
            const inbound = CREDIT_TYPES.has(tx.type);
            return h('td', {
              className: 'right db-mono',
              style: privacy ? null : { color: inbound ? 'var(--green)' : 'var(--text-secondary)' }
            }, privacy ? '$•••••' : ((inbound ? '+' : '−') + fmt(tx.amount)));
          })(),
          h('td', { className: 'right', style: { fontSize: 10, fontWeight: 700 } }, tx.status)
        ))
      )
    )
  );
}

function ProfileBuyOrdersTab() {
  const [orders, setOrders] = useState(null);
  // A failed /api/buy-orders fetch must surface a Retry affordance, NOT
  // masquerade as "No buy orders yet" — these are escrow-bearing standing
  // orders, so a false-empty on an HTTP 500 makes the user believe their
  // locked-in orders vanished (and re-create them). Mirrors the
  // ProfileListingsTab err/aliveRef pattern.
  const [err, setErr] = useState(false);
  const aliveRef = useRef(true);
  const [filter, setFilter] = useState('ACTIVE');
  const [busy, setBusy] = useState(false);
  // Synchronous re-entrancy latch for saveEdit (updates a buy order's
  // maxPrice × quantity = escrow). Async `busy` alone leaves a double-click
  // window; gate on a ref checked-and-set before the await. Synced each render.
  const busyRef = useRef(busy); busyRef.current = busy;
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
    if (busyRef.current) return;
    const maxPrice = parseFloat(editMax);
    const quantity = parseInt(editQty, 10);
    if (!(maxPrice > 0)) { toast('Max price must be positive', 'err'); return; }
    if (!(quantity >= 1)) { toast('Quantity must be at least 1', 'err'); return; }
    busyRef.current = true;
    setBusy(true);
    try {
      const { updateBuyOrder } = await import('./api.js');
      const res = await updateBuyOrder(o.id, { maxPrice, quantity });
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not update buy order', 'err'); return; }
      cancelEdit();
      load();
      // Batch 910 — name the item + the new max + quantity so the user
      // sees exactly what they just changed. Doubly useful when they
      // edit one of many orders — the toast confirms which row landed.
      const qtyStr = quantity > 1 ? ` × ${quantity}` : '';
      toast(`Buy order updated: "${o.itemName || 'item'}" → max ${fmt(maxPrice)}${qtyStr}.`,
        'ok');
    } finally { setBusy(false); }
  };
  const load = useCallback(async () => {
    setErr(false);
    try {
      // Raw fetch (not fetchBuyOrders, which swallows non-2xx into []) so a
      // genuine server error surfaces the Retry card below instead of the
      // false "No buy orders yet" empty state. Refresh-in-place (no
      // setOrders(null)) so post-edit / post-cancel reloads don't flash a
      // spinner over the existing rows.
      const res = await fetch('/api/buy-orders', { credentials: 'same-origin' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      if (!aliveRef.current) return;
      setOrders(Array.isArray(data) ? data : []);
    } catch (_) { if (aliveRef.current) setErr(true); }
  }, []);
  useEffect(() => {
    aliveRef.current = true;
    load();
    return () => { aliveRef.current = false; };
  }, [load]);
  const cancelOrder = async (o) => {
    if (!confirm(`Cancel buy order for "${o.itemName || 'item'}"? Any remaining quantity is freed.`)) return;
    setBusy(true);
    try {
      const res = await deleteBuyOrder(o.id);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not cancel buy order', 'err'); return; }
      load();
      // Batch 910 — toast names the item + max price + remaining qty so
      // the user sees the exact order the row referenced (important
      // when they have 5+ orders open). "Wallet funds freed" reminds
      // them why the wallet balance just ticked up.
      const priceStr = o.maxPrice != null ? fmt(parseFloat(o.maxPrice)) : '';
      const remaining = (o.quantity || 0) - (o.filledQuantity || 0);
      const qtyStr = remaining > 1 ? ` (${remaining} units)` : '';
      toast(`Buy order cancelled for "${o.itemName || 'item'}"${priceStr ? ' at ' + priceStr : ''}${qtyStr}. Wallet funds freed.`,
        'ok');
    } finally { setBusy(false); }
  };
  if (err) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Couldn’t load your buy orders'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Something went wrong fetching your buy orders. Your standing orders are safe — check your connection and try again.'),
    h('button', { className: 'btn btn-accent', onClick: load }, 'Retry')
  );
  if (orders === null) return h('div', { className: 'spinner' });
  if (orders.length === 0) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'No buy orders yet'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Set a standing "pay up to $X" on any item and the matching engine auto-buys the next qualifying listing from your wallet. Great for items you check into but miss the drop on.'),
    h('a', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, href: '/buy-orders' }, '+ Create buy order')
  );
  const counts = {
    ALL:       orders.length,
    ACTIVE:    orders.filter(o => o.status === 'ACTIVE').length,
    FILLED:    orders.filter(o => o.status === 'FILLED').length,
    CANCELLED: orders.filter(o => o.status === 'CANCELLED').length
  };
  // Batch 853 — capital-exposure summary. Sum of maxPrice × remaining
  // quantity across ACTIVE orders. Buy orders DON'T pre-lock funds
  // (BuyOrderService checks balance at match time), so the metric is
  // "potential outlay if every active order fills at its ceiling" —
  // useful for sizing wallet headroom. Only rendered when the user
  // has ≥1 active order so the strip stays silent on an empty tab.
  const activeExposure = orders
    .filter(o => o.status === 'ACTIVE')
    .reduce((sum, o) => {
      const price = parseFloat(o.maxPrice) || 0;
      const qty   = parseInt(o.quantity, 10) || 0;
      return sum + price * qty;
    }, 0);
  // Same sum for FILLED (completed spend) so the user can scan
  // "I've actually spent $X on standing orders over the lifetime of this
  // account." Sum of price * (originalQuantity - quantity) would be
  // more accurate but we don't have `filledQuantity` per-row; for now
  // just sum FILLED rows' max×orig (upper bound).
  const filledSpend = orders
    .filter(o => o.status === 'FILLED')
    .reduce((sum, o) => {
      const price = parseFloat(o.maxPrice) || 0;
      const orig  = parseInt(o.originalQuantity, 10) || parseInt(o.quantity, 10) || 0;
      return sum + price * orig;
    }, 0);
  const filtered = filter === 'ALL' ? orders : orders.filter(o => o.status === filter);
  return h('div', null,
    counts.ACTIVE > 0 && h('div', {
      role: 'region',
      'aria-label': 'Buy orders capital summary',
      style: {
        display: 'flex', gap: 12, alignItems: 'center',
        padding: '8px 12px', marginBottom: 10,
        background: 'var(--bg-elevated, rgba(255,255,255,0.02))',
        border: '1px solid var(--border)', borderRadius: 6,
        fontSize: 12
      }
    },
      h('span', {
        style: { color: 'var(--text-muted)' },
        title: `Sum of max price × remaining quantity across ${counts.ACTIVE} active order${counts.ACTIVE === 1 ? '' : 's'}. This is the potential outlay if every active order fills at its ceiling — buy orders don't pre-lock funds.`
      },
        '—', h('strong', { style: { color: 'var(--accent)' } }, fmt(activeExposure)),
        ' potential outlay across ', counts.ACTIVE, ' active order', counts.ACTIVE === 1 ? '' : 's'
      ),
      counts.FILLED > 0 && h('span', {
        style: { marginLeft: 'auto', color: 'var(--text-muted)' },
        title: 'Upper-bound lifetime spend on filled buy orders. Rows with partial fills are capped at original-quantity × max-price.'
      },
        'Lifetime: ~', h('strong', { style: { color: 'var(--text)' } }, fmt(filledSpend))
      )
    ),
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
          'aria-pressed': filter === opt.id,
          onClick: () => setFilter(opt.id),
          disabled: counts[opt.id] === 0 && opt.id !== 'ALL'
        }, `${opt.label} · ${counts[opt.id] || 0}`))
      ),
      counts.ACTIVE > 1 && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11, color: 'var(--red)' },
        disabled: busy,
        title: `Cancel all ${counts.ACTIVE} active buy orders in one click`,
        onClick: async () => {
          if (!confirm(`Cancel all ${counts.ACTIVE} active buy orders? Any locked wallet funds are freed.`)) return;
          setBusy(true);
          try {
            const { cancelAllBuyOrders } = await import('./api.js');
            const res = await cancelAllBuyOrders();
            if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not cancel buy orders', 'err'); return; }
            const n = (res && res.cancelled) || 0;
            toast(n === 0 ? 'No active buy orders to cancel.' : `Cancelled ${n} buy order${n === 1 ? '' : 's'}.`, 'ok');
            load();
          } finally { setBusy(false); }
        }
      }, '✕ Cancel all active'),
      orders.length > 0 && h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        // Honour the active filter chip — same parity as the wallet
        // CSV ?month= filter. ALL bypasses the param so the URL stays
        // a valid bookmark for "give me everything." The backend tolerates
        // unknown / blank values, so this never 400s.
        href: filter && filter !== 'ALL'
          ? `/api/buy-orders/export.csv?status=${encodeURIComponent(filter)}`
          : '/api/buy-orders/export.csv',
        title: filter && filter !== 'ALL'
          ? `Download buy orders with status ${filter} as CSV`
          : 'Download every buy order (ACTIVE + FILLED + CANCELLED) as CSV'
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
              // Batch 655 — auto-expire countdown chip. Buy orders are
              // swept 30 days after their last `updatedAt` (batch 286).
              // Surface the remaining runway so users can see whether
              // they need to bump the order (which resets updatedAt via
              // the existing edit path) or accept the sweep. Silent on
              // non-ACTIVE rows and when we don't have updatedAt.
              o.status === 'ACTIVE' && o.updatedAt && (() => {
                const msLeft = (o.updatedAt + 30 * 24 * 3600 * 1000) - Date.now();
                if (msLeft <= 0) return null;  // sweep will pick it up any cycle
                const days = Math.floor(msLeft / (24 * 3600 * 1000));
                let color = 'var(--text-muted)';
                let prefix = '';
                if (days <= 2) { color = 'var(--red)';  prefix = '⚠ '; }
                else if (days <= 7) { color = '#fbbf24'; prefix = '⏳ '; }
                else                { prefix = '—'; }
                return h('div', {
                  style: { fontSize: 10, color, marginTop: 2, fontWeight: days <= 7 ? 700 : 400 },
                  title: `Auto-expires at ${new Date(o.updatedAt + 30 * 24 * 3600 * 1000).toLocaleString()}. Edit the order to reset the 30-day clock.`
                }, prefix, 'Auto-expires in ', days, 'd');
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
              h('div', { className: `buyorder-status ${o.status}` },
                o.status === 'ACTIVE'    ? 'Active'
                : o.status === 'FILLED'    ? 'Filled'
                : o.status === 'CANCELLED' ? 'Cancelled'
                : o.status),
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
                  inputMode: 'decimal',
                  'aria-label': 'New max price per item',
                  value: editMax, onChange: e => setEditMax(e.target.value),
                  placeholder: 'Max $',
                  title: 'New max price per item'
                }),
                h('input', {
                  className: 'price-input',
                  style: { width: 52, padding: '4px 6px', fontSize: 11 },
                  type: 'number', step: '1', min: '1',
                  inputMode: 'numeric',
                  'aria-label': 'New quantity',
                  value: editQty, onChange: e => setEditQty(e.target.value),
                  placeholder: 'Qty',
                  title: "Remaining quantity (can't exceed original)"
                }),
                h('button', { className: 'buy-btn', style: { padding: '4px 10px', fontSize: 11 }, disabled: busy, onClick: () => saveEdit(o) }, 'Save'),
                h('button', { className: 'btn btn-ghost', style: { padding: '4px 8px', fontSize: 11 }, onClick: cancelEdit, 'aria-label': 'Cancel edit' }, '✕')
              ),
              // Edit + Bump + Cancel buttons — only shown while the order
              // still has remaining fillable quantity AND no edit is
              // open for another row. Fills / cancels terminate the row
              // so the buttons hide.
              o.status === 'ACTIVE' && editing !== o.id && h('div', { style: { display: 'flex', gap: 4, marginTop: 6, justifyContent: 'flex-end' } },
                // Batch 857 — "Bump" one-click: resets the 30-day auto-
                // expire clock without opening the edit flow. Only
                // surfaces when the order is within 7 days of auto-
                // expiring (i.e. the amber/red chip is already showing)
                // so it doesn't add noise to orders with plenty of
                // runway. Calls the existing /api/buy-orders/{id} PUT
                // with an empty body — the service bumps updatedAt
                // regardless of whether fields changed.
                o.updatedAt && (() => {
                  const msLeft = (o.updatedAt + 30 * 24 * 3600 * 1000) - Date.now();
                  if (msLeft <= 0 || msLeft > 7 * 24 * 3600 * 1000) return null;
                  return h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '4px 10px', fontSize: 10, border: '1px solid rgba(251,191,36,0.35)', color: '#fbbf24' },
                    disabled: busy,
                    title: 'Reset the 30-day auto-expire clock — keeps this order active without changing price or quantity.',
                    onClick: async () => {
                      setBusy(true);
                      try {
                        const { bumpBuyOrder } = await import('./api.js');
                        const res = await bumpBuyOrder(o.id);
                        if (res && (res.error || res.code)) {
                          toast(res.message || res.error || 'Could not bump buy order', 'err');
                          return;
                        }
                        load();
                        // Batch 913 — cite which order + the new expiry
                        // window so the user sees exactly what they bumped.
                        // Match the buy-order toast family from batch 910.
                        toast(`Buy order for "${o.itemName || 'item'}" bumped — 30-day clock restarted.`, 'ok');
                      } finally { setBusy(false); }
                    }
                  }, 'Bump');
                })(),
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
  // Shows EVERY live bid (WINNING + OUTBID, both MANUAL and AUTO). The tab
  // name is "Auto-Bids" historically but the view now covers all live
  // bids since users without auto-raise set still want to see which
  // auctions they're in. Cancel-auto-raise only shows on AUTO rows.
  // Sub-tab switch (batch 361): Active (WINNING + OUTBID) vs Past
  // (WON / LOST / CANCELLED). Past rows don't show cancel-auto since
  // those auctions are already settled.
  const [subtab, setSubtab] = useState('active');
  const [bids, setBids] = useState(null);
  // A failed /api/bids/my-active fetch must surface a Retry affordance, NOT
  // read as "No active bids" — these are LIVE auction positions (potential
  // liability if you're winning); a false-empty on a 500 hides your exposure.
  // Mirrors the ProfileListingsTab err/aliveRef pattern. Scoped to the active
  // sub-tab; the Past tab already degrades to [] which is acceptable for
  // settled history.
  const [err, setErr] = useState(false);
  const aliveRef = useRef(true);
  const [past, setPast] = useState(null);
  const [busy, setBusy] = useState(false);
  // Date-range filter for bids CSV export — passes through to
  // /api/profile/bids.csv as ?from=&to= so a quarterly slice can be
  // downloaded for accounting reconciliation.
  const [bidsDateFrom, setBidsDateFrom] = useState(null);
  const [bidsDateTo,   setBidsDateTo]   = useState(null);
  const load = useCallback(async () => {
    setErr(false);
    try {
      // Raw fetch (not fetchActiveBids, which swallows non-2xx into []) so a
      // server error surfaces the Retry card instead of a false "No active
      // bids". Refresh-in-place (no setBids(null)) so post-cancel reloads
      // don't flash a spinner over the live rows.
      const res = await fetch('/api/bids/my-active', { credentials: 'same-origin' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      if (!aliveRef.current) return;
      setBids(Array.isArray(data) ? data : []);
    } catch (_) { if (aliveRef.current) setErr(true); }
  }, []);
  const loadPast = useCallback(async () => {
    try {
      const { fetchPastBids } = await import('./api.js');
      const rows = await fetchPastBids();
      setPast(Array.isArray(rows) ? rows : []);
    } catch (_) { setPast([]); }
  }, []);
  useEffect(() => {
    aliveRef.current = true;
    load();
    return () => { aliveRef.current = false; };
  }, [load]);
  useEffect(() => { if (subtab === 'past' && past === null) loadPast(); }, [subtab, past, loadPast]);
  const autoBidsCount = (bids || []).filter(b => b.kind === 'AUTO').length;
  const cancelOne = async (b) => {
    // Batch 886 — name the auction in the confirm + toast instead of
    // a bare "listing #23". Users with multiple auto-bids across
    // different auctions get concrete visual anchor for what they're
    // about to cancel.
    const label = b.itemName ? `"${b.itemName}"` : `listing #${b.listingId}`;
    if (!confirm(`Stop auto-raising on ${label}?\nYour current bid (${fmt(b.amount)}) stays live.`)) return;
    setBusy(true);
    try {
      const res = await cancelAutoBid(b.id);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not cancel auto-bid', 'err'); return; }
      load();
      toast(`Auto-bid on ${label} cancelled. Current bid (${fmt(b.amount)}) still live.`, 'ok');
    } finally { setBusy(false); }
  };
  const cancelAll = async () => {
    if (autoBidsCount === 0) return;
    if (!confirm(`Stop auto-raising on all ${autoBidsCount} active auto-bids? Your current bid amounts stay live.`)) return;
    const count = autoBidsCount;
    setBusy(true);
    try {
      const res = await cancelAllAutoBids();
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not cancel auto-bids', 'err'); return; }
      load();
      // Batch 911 — include the count + remind the user that their
      // current bids stay live. Matches the cancelOne toast style above
      // (batch 886) so bulk cancel isn't the odd one out.
      toast(`${count} auto-bid${count === 1 ? '' : 's'} cancelled. Your current bids stay live.`, 'ok');
    } finally { setBusy(false); }
  };
  const subTabPicker = (() => {
    const TABS = ['active', 'past'];
    const onKey = (e) => {
      if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
      e.preventDefault();
      const idx = TABS.indexOf(subtab);
      let n = idx;
      if (e.key === 'ArrowRight' || e.key === 'End') n = TABS.length - 1;
      else if (e.key === 'ArrowLeft' || e.key === 'Home') n = 0;
      setSubtab(TABS[n]);
    };
    return h('div', { style: { display: 'flex', gap: 6, marginBottom: 12 }, role: 'tablist', 'aria-label': 'Bids filter' },
      h('button', {
        className: `offer-tab ${subtab === 'active' ? 'active' : ''}`,
        role: 'tab',
        'aria-selected': subtab === 'active',
        tabIndex: subtab === 'active' ? 0 : -1,
        onKeyDown: onKey,
        onClick: () => setSubtab('active')
      }, 'Active', bids && bids.length > 0 ? h('span', { className: 'filter-count', style: { marginLeft: 6 } }, bids.length) : null),
      h('button', {
        className: `offer-tab ${subtab === 'past' ? 'active' : ''}`,
        role: 'tab',
        'aria-selected': subtab === 'past',
        tabIndex: subtab === 'past' ? 0 : -1,
        onKeyDown: onKey,
        onClick: () => setSubtab('past')
      }, 'Past', past && past.length > 0 ? h('span', { className: 'filter-count', style: { marginLeft: 6 } }, past.length) : null)
    );
  })();

  // ── Past sub-tab (batch 361) ───────────────────────────────────
  if (subtab === 'past') {
    if (past === null) return h('div', null, subTabPicker, h('div', { className: 'spinner' }));
    if (past.length === 0) return h('div', null, subTabPicker,
      h('div', { className: 'empty-inline' },
        h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
        h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
          'No past bids yet'),
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px', lineHeight: 1.55 } },
          "Settled auctions you bid on — won and lost — land here with win-rate + spend stats. Place a bid on any active auction to get started."),
        // Batch 915 — concrete CTA so a fresh bidder has one click to
        // the auctions they can bid on right now. Matches the empty-
        // state pattern from batches 914 / 901 / 900.
        h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
          h('a', {
            className: 'btn btn-accent',
            href: '/market?type=AUCTION',
            style: { padding: '10px 18px', fontWeight: 700 }
          }, 'Browse live auctions →')
        )
      ));
    // Batch 856 — Past bids win-rate + spend summary. Analytics the
    // user would otherwise compute by scrolling + hand-summing. Win-
    // rate is the fraction of settled rows where status === WON.
    // Total spent = sum of `amount` over WON rows (what actually came
    // out of the wallet when you won). Lost-bid-sum = sum over LOST
    // rows (what you were willing to spend but got outbid).
    const wonRows    = past.filter(b => b.status === 'WON');
    const lostRows   = past.filter(b => b.status === 'LOST');
    const wonSpend   = wonRows.reduce((sum, b) => sum + (parseFloat(b.amount) || 0), 0);
    const lostBids   = lostRows.reduce((sum, b) => sum + (parseFloat(b.amount) || 0), 0);
    const settled    = wonRows.length + lostRows.length;
    const winRate    = settled > 0 ? Math.round((wonRows.length / settled) * 100) : null;
    return h('div', null, subTabPicker,
      past.length > 0 && h('div', {
        role: 'region',
        'aria-label': 'Past bids summary',
        style: {
          display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap',
          padding: '8px 12px', marginBottom: 10,
          background: 'var(--bg-elevated, rgba(255,255,255,0.02))',
          border: '1px solid var(--border)', borderRadius: 6,
          fontSize: 12, color: 'var(--text-muted)'
        }
      },
        winRate != null && h('span', {
          title: `${wonRows.length} won / ${settled} settled (CANCELLED auctions excluded from the denominator).`
        },
          h('strong', { style: { color: winRate >= 50 ? 'var(--green)' : 'var(--text)' } }, winRate, '%'),
          ' win rate (', wonRows.length, '/', settled, ')'
        ),
        wonSpend > 0 && h('span', {
          title: 'Total sum of winning-bid amounts across every auction you won. Actually debited from the wallet at settlement.'
        },
          'Spent on wins: ', h('strong', { style: { color: 'var(--text)' } }, fmt(wonSpend))
        ),
        lostBids > 0 && h('span', {
          style: { marginLeft: 'auto' },
          title: 'Sum of bid amounts on auctions you lost — what you were willing to spend but got outbid. No money changed hands; this is just a "missed" opportunity gauge.'
        },
          'Lost bids: ', h('strong', { style: { color: 'var(--text-muted)' } }, fmt(lostBids))
        )
      ),
      h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 12 } },
        `${past.length} settled auction bid${past.length === 1 ? '' : 's'} · capped at the 100 most recent`),
      h('table', { className: 'db-table db-table--list' },
        h('thead', null, h('tr', null,
          h('th', null, 'ID'), h('th', null, 'Listing'),
          h('th', { className: 'center' }, 'Outcome'),
          h('th', { className: 'right' }, 'Your bid'),
          h('th', { className: 'right' }, 'Final'),
          h('th', { className: 'right' }, 'Bid at')
        )),
        h('tbody', null, past.map(b => {
          const statusStyle = b.status === 'WON'
            ? { bg: 'rgba(34,197,94,0.15)',  c: 'var(--green)', br: 'rgba(34,197,94,0.4)',  label: 'Won' }
            : b.status === 'LOST'
              ? { bg: 'rgba(248,113,113,0.12)', c: 'var(--red)', br: 'rgba(248,113,113,0.4)', label: 'Lost' }
              : { bg: 'rgba(148,163,184,0.12)', c: 'var(--text-muted)', br: 'rgba(148,163,184,0.4)', label: 'Cancelled' };
          return h('tr', { key: b.id, className: 'db-row' },
            h('td', { className: 'db-rank' }, '#' + b.id),
            h('td', null, h('a', {
              href: '/item/' + (b.itemId || ''),
              onClick: e => { if (!b.itemId) e.preventDefault(); },
              style: { color: 'var(--accent)', textDecoration: 'none' }
            }, b.itemName || ('Listing #' + b.listingId))),
            h('td', { className: 'center' },
              h('span', {
                style: {
                  fontSize: 9, fontWeight: 800, padding: '2px 6px', borderRadius: 4,
                  background: statusStyle.bg, color: statusStyle.c,
                  border: '1px solid ' + statusStyle.br
                }
              }, statusStyle.label)
            ),
            h('td', { className: 'right db-mono' }, fmt(b.amount)),
            h('td', { className: 'right db-mono' }, b.listingCurrentBid != null ? fmt(b.listingCurrentBid) : '—'),
            h('td', { className: 'right', style: { fontSize: 11, color: 'var(--text-muted)' } },
              timeAgo(b.createdAt))
          );
        }))
      )
    );
  }

  // ── Active sub-tab (existing behaviour) ────────────────────────
  if (err) return h('div', null, subTabPicker, h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Couldn’t load your bids'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Something went wrong fetching your live bids. Your auction positions are unaffected — check your connection and try again.'),
    h('button', { className: 'btn btn-accent', onClick: load }, 'Retry')
  ));
  if (bids === null) return h('div', null, subTabPicker, h('div', { className: 'spinner' }));
  if (bids.length === 0) return h('div', null, subTabPicker, h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'No active bids'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Place a bid on any auction to see it here. Optionally set an auto-bid cap so the proxy-bidder raises your bid by the minimum increment whenever you\'re outbid — up to your ceiling.'),
    // Batch 837 — URL param is `type`, not `listingType` (batch 812
    // mirroring). Old link landed on an unfiltered grid.
    h('a', { className: 'btn btn-accent', href: '/market?type=AUCTION' }, 'Browse auctions →')
  ));
  // Batch 854 — Active Bids capital-exposure summary, parallel to the
  // Buy Orders strip (batch 853). Auctions don't pre-lock wallet
  // balance, so a user with multiple WINNING bids could owe the
  // combined total if all of them close with them as the top bidder.
  // Surfacing this up-front lets the user see their downside without
  // scrolling through rows and hand-summing.
  const winningSum = bids
    .filter(b => b.status === 'WINNING')
    .reduce((sum, b) => sum + (parseFloat(b.amount) || 0), 0);
  const outbidSum = bids
    .filter(b => b.status === 'OUTBID')
    .reduce((sum, b) => sum + (parseFloat(b.amount) || 0), 0);
  // Max exposure — if every auction runs up to the auto-raise cap, or
  // the user's manual bid if they haven't set a cap. Only meaningful
  // for auctions where the user is either WINNING or actively re-
  // bidding (OUTBID), so we cap at those two.
  const maxExposure = bids
    .filter(b => b.status === 'WINNING' || b.status === 'OUTBID')
    .reduce((sum, b) => {
      const max = parseFloat(b.maxAmount);
      const cur = parseFloat(b.amount);
      return sum + (Number.isFinite(max) && max > 0 ? max : (Number.isFinite(cur) ? cur : 0));
    }, 0);
  return h('div', null, subTabPicker,
    bids.length > 0 && h('div', {
      role: 'region',
      'aria-label': 'Active bids capital summary',
      style: {
        display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap',
        padding: '8px 12px', marginBottom: 10,
        background: 'var(--bg-elevated, rgba(255,255,255,0.02))',
        border: '1px solid var(--border)', borderRadius: 6,
        fontSize: 12
      }
    },
      winningSum > 0 && h('span', {
        style: { color: 'var(--text-muted)' },
        title: 'Sum of your current bid amounts on auctions where you are the top bidder. You owe this total if every one of those auctions ends now.'
      },
        h('strong', { style: { color: 'var(--green)' } }, fmt(winningSum)),
        ' committed (', bids.filter(b => b.status === 'WINNING').length, ' winning)'
      ),
      outbidSum > 0 && h('span', {
        style: { color: 'var(--text-muted)' },
        title: 'Sum of your last bids on auctions where you have since been outbid. Counter-bid or walk away.'
      },
        '↑ ', h('strong', { style: { color: '#fbbf24' } }, fmt(outbidSum)),
        ' outbid'
      ),
      maxExposure > 0 && h('span', {
        style: { marginLeft: 'auto', color: 'var(--text-muted)' },
        title: 'Upper-bound: sum of auto-raise caps (or current bid when no cap is set) across every auction you are still in. This is the most your wallet owes if every auction runs up to your ceiling.'
      },
        'Max: ~', h('strong', { style: { color: 'var(--text)' } }, fmt(maxExposure))
      )
    ),
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 } },
      h('div', { style: { fontSize: 12, color: 'var(--text-muted)' } },
        `${bids.length} active bid${bids.length === 1 ? '' : 's'} across auctions` +
        (autoBidsCount > 0 ? ` · ${autoBidsCount} auto-raising` : '')),
      h('div', { style: { flex: 1 } }),
      h(DateRangeFilter, {
        from: bidsDateFrom,
        to:   bidsDateTo,
        onChange: ({ from, to }) => { setBidsDateFrom(from); setBidsDateTo(to); }
      }),
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11, margin: '0 8px' },
        href: appendDateRange('/api/profile/bids.csv', bidsDateFrom, bidsDateTo),
        title: 'Download every bid you have ever placed (WINNING + OUTBID + WON + LOST + CANCELLED) as CSV. Honours the date filter when set.'
      }, '⇣ CSV'),
      autoBidsCount > 0 && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 12px', fontSize: 11 },
        disabled: busy,
        onClick: cancelAll
      }, 'Stop all auto-raises')
    ),
    h('table', { className: 'db-table db-table--list' },
      h('thead', null, h('tr', null,
        h('th', null, 'ID'), h('th', null, 'Listing'),
        h('th', { className: 'center' }, 'State'),
        h('th', { className: 'right' }, 'Your bid'),
        h('th', { className: 'right' }, 'Current'),
        h('th', { className: 'right' }, 'Max'),
        h('th', { className: 'right' }, 'Ends'),
        h('th', { className: 'right' }, '')
      )),
      h('tbody', null, bids.map(b => h('tr', { key: b.id, className: 'db-row' },
        h('td', { className: 'db-rank' }, '#' + b.id),
        h('td', null, h('a', {
          href: '/item/' + (b.itemId || ''),
          onClick: e => { if (!b.itemId) e.preventDefault(); },
          style: { color: 'var(--accent)', textDecoration: 'none' },
          title: 'View item detail + auction panel'
        }, b.itemName || ('Listing #' + b.listingId))),
        h('td', { className: 'center' },
          h('span', {
            style: {
              fontSize: 9, fontWeight: 800, padding: '2px 6px', borderRadius: 4, marginRight: 4,
              background: b.status === 'WINNING' ? 'rgba(34,197,94,0.15)' : 'rgba(251,191,36,0.15)',
              color:      b.status === 'WINNING' ? 'var(--green)'          : '#fbbf24',
              border:     b.status === 'WINNING' ? '1px solid rgba(34,197,94,0.4)' : '1px solid rgba(251,191,36,0.4)'
            }
          }, b.status === 'WINNING' ? 'Winning' : '↑ Outbid'),
          b.kind === 'AUTO' && h('span', {
            style: {
              fontSize: 9, fontWeight: 800, padding: '2px 6px', borderRadius: 4,
              background: 'rgba(77,200,255,0.15)', color: 'var(--accent)',
              border: '1px solid rgba(77,200,255,0.35)'
            }
          }, 'AUTO')
        ),
        h('td', { className: 'right db-mono accent' }, fmt(b.amount)),
        h('td', {
          className: 'right db-mono',
          style: { color: b.status === 'WINNING' ? 'var(--text-primary)' : 'var(--red)' },
          title: b.status === 'WINNING'
            ? "You're the current top bid"
            : `Top bid is ahead by ${fmt((parseFloat(b.listingCurrentBid) || 0) - (parseFloat(b.amount) || 0))}`
        }, b.listingCurrentBid != null ? fmt(b.listingCurrentBid) : fmt(b.amount)),
        h('td', { className: 'right db-mono' }, fmt(b.maxAmount || b.amount)),
        h('td', {
          className: 'right',
          style: { fontSize: 11, color: 'var(--text-muted)' },
          title: b.listingExpiresAt ? `Auction closes ${new Date(b.listingExpiresAt).toLocaleString()}` : 'Unknown end time'
        },
          (() => {
            if (!b.listingExpiresAt) return timeAgo(b.createdAt);
            const ms = b.listingExpiresAt - Date.now();
            if (ms <= 0) return 'Ended';
            const s = Math.floor(ms / 1000);
            const d = Math.floor(s / 86400);
            const hr = Math.floor((s % 86400) / 3600);
            const mn = Math.floor((s % 3600) / 60);
            if (d > 0) return `${d}d ${hr}h`;
            if (hr > 0) return `${hr}h ${mn}m`;
            return `${mn}m`;
          })()
        ),
        h('td', { className: 'right' },
          // Only AUTO rows have a cancellable auto-raise; MANUAL rows
          // have nothing to stop — their bid is final. A Stop button on
          // MANUAL rows would 400 on the server's cancelAutoBid guard.
          b.kind === 'AUTO' && h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
            disabled: busy,
            onClick: () => cancelOne(b),
            title: "Stop the auto-raise proxy-bidder — your current bid stays live"
          }, 'Stop auto')
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
  // True row count on the server — populated from X-Total-Count so the
  // Trades tab can render "Showing most recent 200 of N" once a user
  // crosses the TRADE_LIST_CAP (200) display cap. Null until the first
  // fetch resolves so the banner doesn't flash on initial render.
  const [tradesTotal, setTradesTotal] = useState(null);
  // A failed /api/trades fetch must surface a Retry affordance, NOT read as
  // "no trades" — these are live escrow positions (a buyer awaiting delivery,
  // a seller awaiting confirm, a dispute deadline). A false-empty on an HTTP
  // 500 could make a user miss an escrow-action window. Mirrors the
  // ProfileListingsTab err/aliveRef pattern.
  const [err, setErr] = useState(false);
  const aliveRef = useRef(true);
  const [busy, setBusy]     = useState(false);
  const [filter, setFilter] = useState('ALL');
  // Per-30s tick so the trade-row countdown chips ("⏱ Xh left") actually
  // advance. Without it they freeze at whatever Date.now() returned on
  // the last load() / visibilitychange and can read a stale "1h left"
  // long after the deadline passed — 30s granularity is plenty for the
  // hour/day labels the chips render.
  const [nowTick, setNowTick] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNowTick(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);
  // "Collapse active" toggle per CSFloat Visual Manual §29 — hides the
  // three PENDING_* states so a user auditing settled trades doesn't
  // scroll past their active escrow rows. Persisted in localStorage so
  // the preference sticks across sessions.
  const [collapseActive, setCollapseActive] = useState(() => {
    try { return localStorage.getItem('sb_trade_collapse_active') === '1'; }
    catch { return false; }
  });
  useEffect(() => {
    try { localStorage.setItem('sb_trade_collapse_active', collapseActive ? '1' : '0'); } catch (_) {}
  }, [collapseActive]);
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
  // Search box for the trade list (batch 374). Case-insensitive match
  // against item name + counterparty display. Helpful when a user with
  // 100+ historical trades needs to find "that hat I sold last month."
  const [tradeSearch, setTradeSearch] = useState('');
  // Date-range filter — drives both the client-side row filter and the
  // CSV-export href (?from=&to= passed through to the controller). Lets
  // a user pull a quarterly slice for tax / accounting without piping a
  // full-year file through a spreadsheet filter. `to` is inclusive
  // end-of-day local time so picking Mar 31 covers "all of Mar 31."
  const [tradeDateFrom, setTradeDateFrom] = useState(null);
  const [tradeDateTo,   setTradeDateTo]   = useState(null);
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
  // Deep-link: if the URL carries `?openChat=<id>` (set by the TRADE_MESSAGE
  // notification's path), auto-open that trade's chat panel and load the
  // thread once the trade list is in memory. Clears the param from the URL
  // afterwards so a page refresh doesn't keep reopening the same chat
  // against the user's will. Runs once per trade-load.
  useEffect(() => {
    if (!trades || trades.length === 0) return;
    try {
      const qs = new URLSearchParams(window.location.search);
      const target = qs.get('openChat');
      if (!target) return;
      const id = parseInt(target, 10);
      if (!Number.isFinite(id)) return;
      if (!trades.some(t => t.id === id)) return;  // Not a trade this user is in
      setOpenChat(id);
      setChatDraft('');
      loadChat(id);
      qs.delete('openChat');
      const next = qs.toString();
      window.history.replaceState({}, '', window.location.pathname + (next ? '?' + next : ''));
    } catch (_) {}
  }, [trades?.length]);
  // Deep-link: `?highlight=<tradeId>` scrolls a specific trade row into
  // view and flashes it so notification click-through lands on the right
  // row instead of dumping the user at the top of a 30-trade list.
  // Batch 744. Runs once per trade-load; param scrubbed afterwards.
  useEffect(() => {
    if (!trades || trades.length === 0) return;
    try {
      const qs = new URLSearchParams(window.location.search);
      const target = qs.get('highlight');
      if (!target) return;
      const id = parseInt(target, 10);
      if (!Number.isFinite(id)) return;
      if (!trades.some(t => t.id === id)) return;
      const el = document.getElementById('trade-' + id);
      if (el) {
        // Delay by one frame so React's current render flushes to the
        // DOM before we measure + scroll. Smooth scroll feels more
        // deliberate than an instant jump; flash uses the existing
        // `--accent` token so the color matches the site theme.
        requestAnimationFrame(() => {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          el.style.transition = 'box-shadow 0.25s ease';
          el.style.boxShadow = '0 0 0 2px var(--accent), 0 0 24px rgba(30,165,255,0.4)';
          setTimeout(() => { el.style.boxShadow = ''; }, 1800);
        });
      }
      qs.delete('highlight');
      const next = qs.toString();
      window.history.replaceState({}, '', window.location.pathname + (next ? '?' + next : ''));
    } catch (_) {}
  }, [trades?.length]);
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
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Message not sent', 'err'); return; }
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
  // Refund-request drawer state (batch 426). Replaces the old window
  // .prompt that lost the trade context if the user fat-fingered Esc.
  // Now opens an in-modal drawer with the trade summary auto-rendered
  // + a multi-line reason textarea + an explicit submit button. Backend
  // contract is unchanged — still files a REFUND ticket via
  // createSupportTicket.
  const [refundBusy, setRefundBusy]     = useState(false);
  const [refundTrade, setRefundTrade]   = useState(null);
  // Batch 817 — inline drawer for reporting a trade counterparty.
  // Replaces the two-step window.prompt() flow (pick reason by number
  // → type context) with a proper dialog. State holds the entire
  // trade row so the drawer can render context (trade id, item,
  // counterparty name).
  const [reportUserTrade, setReportUserTrade] = useState(null);
  const [refundReason, setRefundReason] = useState('');
  const openRefundForTrade = (trade) => {
    setRefundTrade(trade);
    setRefundReason('');
  };
  const submitRefund = async () => {
    if (!refundTrade) return;
    const trimmed = (refundReason || '').trim();
    if (!trimmed) { toast('Please describe what went wrong.', 'err'); return; }
    // Batch 1078 — synchronous re-entrancy latch. async setRefundBusy
    // alone can't stop a same-frame double-click (both handlers capture
    // refundBusy===false from the same render). Unlike Mark-Sent, this
    // submit calls createSupportTicket directly — no parent latch, no
    // confirm() to serialize it — and SupportService.create has no
    // per-trade dedupe, so a fast double-click filed TWO REFUND tickets
    // and doubled the admin/CSR bell fan-out. refundBusyRef already
    // exists (a11y close-guard, re-synced to refundBusy each render);
    // gate the submit on it too, exactly as submitReview does. Auto-
    // resets via that per-render sync after setRefundBusy(false).
    if (refundBusyRef.current) return;
    refundBusyRef.current = true;
    setRefundBusy(true);
    try {
      const trade = refundTrade;
      const dt = new Date(trade.settledAt || trade.updatedAt || trade.createdAt);
      const res = await createSupportTicket({
        category: 'REFUND',
        subject:  `Refund request · Trade #${trade.id} · ${trade.itemName || 'item'}`,
        body:     `Trade ID: ${trade.id}\n` +
                  `Item: ${trade.itemName || '—'}\n` +
                  `Price: ${trade.price != null ? fmt(trade.price) : '—'}\n` +
                  `Settled: ${isNaN(dt.getTime()) ? '—' : dt.toISOString()}\n` +
                  `Counterparty: ${trade.counterpartyName || ('user #' + (trade.sellerUserId || trade.buyerUserId))}\n\n` +
                  `Reason from buyer:\n${trimmed}`
      });
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not open refund ticket.', 'err');
        return;
      }
      setRefundTrade(null); setRefundReason('');
      toast('Refund request submitted — track progress in Support.', 'ok');
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
  // Track the success-toast → closeReview handoff timer so we can cancel
  // it on unmount / re-open. Pre-fix, the bare `setTimeout(closeReview,
  // 900)` in submitReview leaked: if the user navigated away (back/
  // forward, opened a different modal, re-opened a different trade's
  // review) inside the 900ms window, the stale timer still fired
  // closeReview later — clearing the freshly-opened reviewTrade and
  // closing the new modal out from under the reviewer. Mirrors the
  // submittedTimerRef pattern used by the Report modal above.
  const reviewCloseTimerRef = useRef(null);
  useEffect(() => () => {
    if (reviewCloseTimerRef.current) {
      clearTimeout(reviewCloseTimerRef.current);
      reviewCloseTimerRef.current = null;
    }
  }, []);
  // Batch 829 / Batch 1167 — Escape + focus management for the review
  // modal. The dialogClose callback is stable (refs hold the latest
  // busy / close fn) so useDialogA11y only mounts/unmounts when the
  // modal actually opens/closes — busy-state toggles don't tear down
  // and rebuild the trap mid-submit, which would steal focus from
  // the user's in-flight Submit button.
  const reviewPanelRef = useRef(null);
  const reviewBusyRef  = useRef(reviewBusy);
  reviewBusyRef.current = reviewBusy;
  const reviewClose = useCallback(() => {
    if (reviewBusyRef.current) return;
    setReviewTrade(null);
    setReviewErr('');
    setReviewDone(false);
  }, []);
  useDialogA11y(reviewPanelRef, reviewClose, !!reviewTrade);
  // Batch 840 / Batch 1167 — Escape + focus management for the refund
  // drawer. Same stable-callback pattern as the review modal above.
  const refundPanelRef = useRef(null);
  const refundBusyRef  = useRef(refundBusy);
  refundBusyRef.current = refundBusy;
  const refundClose = useCallback(() => {
    if (refundBusyRef.current) return;
    setRefundTrade(null);
    setRefundReason('');
  }, []);
  useDialogA11y(refundPanelRef, refundClose, !!refundTrade);
  const submitReview = async () => {
    if (!reviewTrade) return;
    // reviewBusyRef already exists (a11y close-guard); gate the submit on it too
    // so a double-click can't POST the review twice (setReviewBusy is async).
    if (reviewBusyRef.current) return;
    reviewBusyRef.current = true;
    setReviewBusy(true); setReviewErr('');
    try {
      const res = await leaveReview(reviewTrade.id, reviewStars, reviewText);
      if (res.code || res.error) {
        setReviewErr(res.message || res.error || 'Review failed');
        return;
      }
      setReviewDone(true);
      // Batch 890 — toast the star count + counterparty so the reviewer
      // sees explicit confirmation, not just a 900ms "done" flash. The
      // inline "done" banner still fires for the user's direct focus;
      // the toast is for users who've scrolled away from the modal by
      // the time the save lands.
      const stars = '★'.repeat(reviewStars) + '☆'.repeat(5 - reviewStars);
      const sellerName = reviewTrade.counterpartyName;
      toast(sellerName
        ? `${stars} review posted for @${sellerName}.`
        : `${stars} review posted.`,
        'ok');
      if (reviewCloseTimerRef.current) clearTimeout(reviewCloseTimerRef.current);
      reviewCloseTimerRef.current = setTimeout(() => {
        reviewCloseTimerRef.current = null;
        closeReview();
      }, 900);
    } finally { setReviewBusy(false); }
  };

  const load = useCallback(async () => {
    setErr(false);
    try {
      // Raw fetch (not fetchTradesWithTotal, which swallows non-2xx into an
      // empty result) so a server error surfaces the Retry card instead of a
      // false "no trades". Same X-Total-Count → items.length total logic.
      // Refresh-in-place (no setTrades(null)) so the tab-focus refresh and
      // post-action reloads don't flash a spinner over the rows.
      const res = await fetch('/api/trades', { credentials: 'same-origin' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const items = await res.json();
      if (!aliveRef.current) return;
      const rows = Array.isArray(items) ? items : [];
      const totalHeader = res.headers.get('X-Total-Count');
      const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
      setTrades(rows);
      setTradesTotal(Number.isFinite(parsed) ? parsed : rows.length);
    } catch (_) { if (aliveRef.current) setErr(true); }
  }, []);
  useEffect(() => {
    aliveRef.current = true;
    load();
    return () => { aliveRef.current = false; };
  }, [load]);
  // Batch 665 — tab-focus refresh. A seller watching the Trades tab
  // for a buyer-confirm (or a buyer watching for a seller-send) hates
  // having to hit F5 to see the state flip. Mirrors the wallet's
  // visibilitychange handler: cheap single fetchTrades call each time
  // the browser tab becomes visible again. No interval polling —
  // the WebSocket-free design relies on user focus events so background
  // tabs don't thrash the /api/trades endpoint.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [load]);

  // React rules-of-hooks: every hook must run on every render in the same
  // order. Previously `markSentTrade`, `confirmTrade`, and `disputeTrade`
  // were declared below the (!me)/(trades===null) early returns, so the
  // first render (trades===null → spinner) called 10 hooks and the second
  // (trades loaded → real UI) called 13 — React error #310 nuked the whole
  // Trades tab. Hoisted them to before the early return so the count is
  // stable across renders.
  const [markSentTrade, setMarkSentTrade] = useState(null);
  const [confirmTrade, setConfirmTrade] = useState(null);
  const [disputeTrade, setDisputeTrade] = useState(null);

  // Confirm-receipt Escape + focus management — also has to live above
  // the early returns or its hook count varies across renders and trips
  // React #310. No-ops when confirmTrade is null. Busy-guarded so a user
  // can't cancel mid-flight while tradeOp is already in progress.
  // Batch 1167 — upgraded from raw Escape handler to useDialogA11y so
  // the financially-irreversible confirm-receipt dialog now traps Tab
  // inside the modal and restores focus to the row's Confirm button on
  // close. Stable-callback pattern (busy lives in a ref) keeps the
  // trap mounted continuously across busy-state toggles.
  const confirmPanelRef = useRef(null);
  const busyRef = useRef(busy);
  busyRef.current = busy;
  const confirmClose = useCallback(() => {
    if (busyRef.current) return;
    setConfirmTrade(null);
  }, []);
  useDialogA11y(confirmPanelRef, confirmClose, !!confirmTrade);

  if (!me) return h(SignInNeededEmptyState, { what: 'your trades' });
  if (err) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Couldn’t load your trades'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Something went wrong fetching your trades. Your escrowed trades are safe — check your connection and try again.'),
    h('button', { className: 'btn btn-accent', onClick: load }, 'Retry')
  );
  if (trades === null) return h('div', { className: 'spinner' });

  const stateFiltered = (() => {
    let rows = filter === 'ALL'
      ? trades
      : filter === 'OPEN'
        ? trades.filter(t => !['VERIFIED','CANCELLED'].includes(t.state))
        : trades.filter(t => t.state === filter);
    // Collapse active — drop every PENDING_* row, leaving only the
    // settled states. Only applies when the state filter isn't already
    // narrowing to a pending bucket (which would zero the list).
    if (collapseActive) {
      rows = rows.filter(t => !['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM'].includes(t.state));
    }
    return rows;
  })();
  // Layer the role filter over the state filter. "all" = every trade,
  // "buying" = trades where the current user is the buyer, "selling" =
  // seller. Unauthenticated callers shouldn't land here but we still
  // treat me?.id-missing as "show everything" so we don't lose the row.
  const roleFiltered = (() => {
    if (!me?.id || roleFilter === 'all') return stateFiltered;
    if (roleFilter === 'buying')  return stateFiltered.filter(t => t.buyerUserId  === me.id);
    if (roleFilter === 'selling') return stateFiltered.filter(t => t.sellerUserId === me.id);
    return stateFiltered;
  })();
  // Client-side search + date-range filter (batch 374). Item name +
  // counterparty display + trade id; small dataset (user's own trades,
  // capped at 200-ish) so a full-scan filter is fine. Date bounds use
  // `createdAt` so a user sees the same slice on screen as in the CSV
  // export they'd pull with the same window.
  const filtered = (() => {
    let rows = roleFiltered;
    const q = (tradeSearch || '').trim().toLowerCase();
    if (q) {
      rows = rows.filter(t =>
        (t.itemName || '').toLowerCase().includes(q) ||
        (t.counterpartyName || '').toLowerCase().includes(q) ||
        String(t.id).includes(q));
    }
    if (tradeDateFrom != null) rows = rows.filter(t => (t.createdAt || 0) >= tradeDateFrom);
    if (tradeDateTo   != null) rows = rows.filter(t => (t.createdAt || 0) <= tradeDateTo);
    return rows;
  })();

  const tradeOp = async (fn, ...args) => {
    setBusy(true);
    try {
      const res = await fn(...args);
      if (res && res.error) { toast(res.error, 'err'); return; }
      await load();
    } finally { setBusy(false); }
  };
  // Batch 888 — replace tradeOp wrapper for Accept so the seller gets
  // a named confirmation. Accept is the step that debits the buyer's
  // wallet and opens escrow, so silent success was misleading.
  const onAccept = async (id) => {
    // onAccept DEBITS the buyer's wallet + opens escrow — money-moving, so it
    // gets the same synchronous re-entrancy latch as runConfirm / checkout /
    // withdraw (the async `busy` state alone leaves a rapid-double-click window).
    if (busyRef.current) return;
    busyRef.current = true;
    const t = trades.find(x => x.id === id);
    setBusy(true);
    try {
      const res = await tradeAccept(id);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Accept failed', 'err'); return; }
      await load();
      const label = t?.itemName ? `"${t.itemName}"` : `trade #${id}`;
      toast(`Accepted ${label}. Send the Steam trade offer next.`, 'ok');
    } finally { setBusy(false); }
  };
  // Batch 816 — Mark Sent now opens a proper drawer (see MarkSentDrawer
  // below) instead of window.prompt(). The drawer gets paste support,
  // live regex validation, a clipboard-auto-grab, and a visible
  // "Skip — mark sent without the link" affordance so the seller
  // doesn't accidentally dismiss with the X. Previous prompt-based
  // flow had no live feedback so a typo-ed URL silently dropped at
  // the server's regex check.
  // (markSentTrade state is now declared above the early returns.)
  const onSent = (id) => {
    // Find the full trade record so the drawer can render the
    // buyer name + item + price as context — otherwise "paste a
    // URL" without a reminder of WHICH trade is disorienting
    // during a bulk-ship session.
    const t = trades.find(x => x.id === id);
    setMarkSentTrade(t || { id });
  };
  const submitMarkSent = async (id, url) => {
    // Batch 861 — don't close the drawer until the server confirms.
    // Previous code closed first then called the API, so a rejected
    // URL (e.g. batch 860's new TRADE_OFFER_URL_INVALID) surfaced a
    // toast but gave the seller nothing to retry — they'd have to
    // re-click Mark Sent and re-paste. Now the drawer stays open on
    // error so the seller can fix the URL in place.
    if (busyRef.current) return;
    busyRef.current = true;
    const t = trades.find(x => x.id === id);
    setBusy(true);
    try {
      const res = await tradeMarkSent(id, url || undefined);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Mark sent failed', 'err');
        return; // keep drawer open
      }
      setMarkSentTrade(null);
      await load();
      // Pre-fix: silent on success — the seller pasted a Steam offer
      // URL, hit Send, the drawer closed, and the only feedback that
      // anything happened was the row's state pill flipping to
      // "Awaiting buyer confirm" two scrolls down the trades list.
      // Mirror the named-success pattern from onAccept / onCancel so
      // every step in the trade state machine surfaces feedback.
      const label = t?.itemName ? `"${t.itemName}"` : `trade #${id}`;
      toast(`Marked ${label} as sent — buyer will confirm receipt to release escrow.`, 'ok');
    } finally { setBusy(false); }
  };
  // Buyer confirm is financially irreversible — it releases escrow to the
  // seller. Gate it behind a summary modal so buyers review (item, price,
  // fee, seller) before committing. Without this, a mis-click on "Confirm"
  // on the wrong trade row could release funds early.
  // (confirmTrade state is now declared above the early returns.)
  const onConfirm = (trade) => setConfirmTrade(trade);
  const runConfirm = async () => {
    // Synchronous re-entrancy latch on the escrow-RELEASE action (the most
    // financially-irreversible click in the app): the `busy` state is async, so
    // a rapid double-click could fire two tradeConfirm POSTs before the button
    // disables. Set busyRef synchronously here; it's re-synced to `busy` on the
    // next render and cleared when the finally's setBusy(false) re-renders.
    if (!confirmTrade || busyRef.current) return;
    busyRef.current = true;
    const id = confirmTrade.id;
    const itemName = confirmTrade.itemName;
    const price = confirmTrade.price;
    const fee = confirmTrade.feeAmount;
    // Batch 888 — keep the confirm modal open until the API confirms,
    // then surface a personalised success toast. Before: the modal
    // closed before the API call fired (batch 861 pattern lives here
    // too) so a rejected confirm (e.g. wallet frozen race, state
    // flipped by a sweeper) got a toast but the buyer wondered whether
    // their click actually did anything.
    setBusy(true);
    try {
      const res = await tradeConfirm(id);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Confirm failed', 'err');
        return;  // keep the confirm modal mounted so the buyer can retry
      }
      setConfirmTrade(null);
      await load();
      const label = itemName ? `"${itemName}"` : `trade #${id}`;
      // The seller is credited price MINUS the platform fee (the modal the
      // buyer just confirmed says so), so name that figure, not the gross.
      const net = (price != null && fee != null) ? Number(price) - Number(fee) : null;
      const priceBit = net != null
        ? ` — ${fmt(net)} released to the seller (${fmt(price)} minus the ${fmt(fee)} platform fee)`
        : (price != null ? ` — payment released to the seller` : '');
      toast(`Receipt confirmed for ${label}${priceBit}.`, 'ok');
    } finally { setBusy(false); }
  };
  // (Confirm-receipt Escape handler moved above the early returns to keep
  // the useEffect hook order stable — see hoisted block before
  // `if (!me) return …`. Hook order has to match across renders or React
  // throws #310.)
  // Dispute opens a structured drawer (batch 275) — replaces the legacy
  // free-text prompt. The drawer takes the trade row so it can show
  // item / price context + decide whether to render the buyer-side or
  // seller-side reason list.
  // (disputeTrade state is now declared above the early returns.)
  const onDispute = (id) => {
    const t = (trades || []).find(x => x.id === id);
    if (!t) return;
    setDisputeTrade(t);
  };
  const onCancel  = async (id) => {
    // Pre-fix: tradeOp(tradeCancel, …) was silent on success — the row
    // flipped to CANCELLED and the buyer-refund happened with zero
    // confirmation. Mirrors the named-success pattern from onAccept
    // (line 6293) so the seller gets symmetric feedback on every step.
    const t = trades.find(x => x.id === id);
    if (!confirm('Cancel this trade? The buyer will be refunded.')) return;
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await tradeCancel(id, 'User cancelled');
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Cancel failed', 'err'); return; }
      await load();
      const label = t?.itemName ? `"${t.itemName}"` : `trade #${id}`;
      const priceBit = t?.price != null ? ` — ${fmt(t.price)} refunded to the buyer` : '';
      toast(`Cancelled ${label}${priceBit}.`, 'ok');
    } finally { setBusy(false); }
  };

  // 6-node stepper mapping per CSFloat Visual Manual §29. Node 1 (Seller)
  // and node 6 (Buyer) are actor anchors — always rendered as the start /
  // end of the flow. Nodes 2-5 track the escrow state machine. VERIFIED
  // fills every node; terminal DISPUTED / CANCELLED fall out of the
  // stepper into a single chip below.
  const STATE_LABEL = {
    PENDING_SELLER_ACCEPT:  { label: 'Awaiting seller accept',    color: '#fbbf24', step: 2 },
    PENDING_SELLER_SEND:    { label: 'Awaiting Steam offer',      color: '#fbbf24', step: 3 },
    PENDING_BUYER_CONFIRM:  { label: 'Awaiting buyer confirm',    color: '#60a5fa', step: 4 },
    VERIFIED:               { label: 'Verified · funds released', color: '#4ade80', step: 6 },
    DISPUTED:               { label: 'Disputed',                  color: '#f87171', step: 0 },
    CANCELLED:              { label: 'Cancelled',                 color: '#8590b3', step: 0 },
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
  // Batch 815 — lifetime $ volume across VERIFIED trades, split by
  // role. Surfaces "you've spent $X on buys and earned $Y on sells"
  // without a separate ledger export. Buyers/sellers are identified
  // by comparing the trade's buyerUserId / sellerUserId against the
  // viewer's id. Only verified trades count — pending/disputed/
  // cancelled could still flip, so summing them would mislead.
  const dollars = (() => {
    if (!me?.id || !Array.isArray(trades)) return { spent: 0, earned: 0 };
    let spent = 0, earned = 0;
    trades.forEach(t => {
      if (t.state !== 'VERIFIED') return;
      const price = parseFloat(t.price) || 0;
      if (t.buyerUserId === me.id)  spent  += price;
      if (t.sellerUserId === me.id) {
        // Net-of-fee for sellers — the chip should match what
        // actually landed in the wallet, not the gross list price.
        const fee = parseFloat(t.fee ?? t.platformFee ?? 0) || 0;
        earned += Math.max(0, price - fee);
      }
    });
    return { spent, earned };
  })();

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
      ),
      // Batch 815 — lifetime $ volume chips. Spent = VERIFIED buys
      // (wallet debits at list price); Earned = VERIFIED sells net of
      // the 2% platform fee already deducted at trade time.
      dollars.spent > 0 && h('div', {
        className: 'trade-summary-chip',
        style: { borderColor: 'rgba(96,165,250,0.35)' },
        title: `Total charged to your wallet across every VERIFIED buy. Pending / disputed / cancelled trades are excluded.`
      },
        h('span', { className: 'trade-summary-num', style: { color: '#60a5fa' } }, fmt(dollars.spent)),
        h('span', { className: 'trade-summary-label' }, 'spent')
      ),
      dollars.earned > 0 && h('div', {
        className: 'trade-summary-chip',
        style: { borderColor: 'rgba(34,197,94,0.35)' },
        title: `Total credited to your wallet across every VERIFIED sell, net of the 2% platform fee. Pending / disputed / cancelled trades are excluded.`
      },
        h('span', { className: 'trade-summary-num', style: { color: 'var(--green)' } }, fmt(dollars.earned)),
        h('span', { className: 'trade-summary-label' }, 'earned')
      )
    ),
    h('div', { className: 'trade-filter-bar', style: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 4 } },
      ['ALL','OPEN','PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM','VERIFIED','DISPUTED','CANCELLED'].map(f =>
        h('button', {
          key: f,
          className: `offer-tab ${filter === f ? 'active' : ''}`,
          'aria-pressed': filter === f,
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
        'aria-pressed': roleFilter === r,
        onClick: () => setRole(r),
        title: r === 'all' ? 'Show every trade'
             : r === 'buying' ? 'Only trades you bought'
             : 'Only trades you sold'
      }, r === 'all' ? 'All' : r[0].toUpperCase() + r.slice(1))),
      // Collapse-active toggle per CSFloat Visual Manual §29. Hides the three
      // PENDING_* rows so a heavy seller auditing their completed trades
      // (for tax / feedback) doesn't scroll past active escrow noise.
      h('button', {
        className: `wallet-tx-filter-chip ${collapseActive ? 'active' : ''}`,
        'aria-pressed': collapseActive,
        style: { marginLeft: 6 },
        onClick: () => setCollapseActive(v => !v),
        title: collapseActive
          ? 'Active escrow rows are hidden — click to show them again'
          : 'Hide in-flight escrow rows (PENDING_*) and show only settled trades'
      }, collapseActive ? '⊟ Active hidden' : '⊟ Collapse active'),
      // Search box (batch 374) — item name + counterparty + trade id.
      // Only rendered when the user has enough trades to make searching
      // worthwhile — single-row histories don't need a text filter.
      trades.length > 3 && h('input', {
        className: 'price-input',
        style: { marginLeft: 8, minWidth: 140, maxWidth: 220, fontSize: 12, padding: '4px 8px' },
        placeholder: 'Search trades…',
        value: tradeSearch,
        onChange: e => setTradeSearch(e.target.value)
      }),
      // Date-range filter — drives both the client-side filter and the
      // CSV from/to params. Surfaced alongside the export button so the
      // user sees on screen the same slice they'd get in the download.
      trades.length > 0 && h(DateRangeFilter, {
        from: tradeDateFrom,
        to:   tradeDateTo,
        onChange: ({ from, to }) => { setTradeDateFrom(from); setTradeDateTo(to); }
      }),
      // CSV export — opens /api/profile/trades.csv in a new tab. The
      // browser handles the download via the Content-Disposition header
      // the endpoint sets. Only surfaced once the user has at least one
      // trade to avoid a dead-end download on fresh accounts. When the
      // date-range filter is set, the bounds are passed through so the
      // server-side slice matches the on-screen view.
      trades.length > 0 && (() => {
        // Honour the active state + role filter chips so a user who
        // narrowed to "VERIFIED · selling" downloads exactly that
        // slice. ALL state and 'all' role bypass the param so the
        // "give me everything" URL stays a clean bookmark. Same
        // UX-parity logic as the wallet/buy-orders/offers CSVs.
        const params = [];
        if (filter && filter !== 'ALL') params.push('state=' + encodeURIComponent(filter));
        if (roleFilter && roleFilter !== 'all') params.push('role=' + encodeURIComponent(roleFilter));
        let base = '/api/profile/trades.csv';
        if (params.length > 0) base += '?' + params.join('&');
        const csvHref = appendDateRange(base, tradeDateFrom, tradeDateTo);
        const activeCrumbs = [
          filter && filter !== 'ALL' ? filter : null,
          roleFilter && roleFilter !== 'all' ? roleFilter : null
        ].filter(Boolean).join(' · ');
        return h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: csvHref,
        title: activeCrumbs
          ? `Download trades matching the active filters (${activeCrumbs}) as CSV. Honours the date filter when set.`
          : 'Download every trade you participated in as a CSV (tax / accounting). Honours the date filter when set.'
      }, '⇣ CSV');
      })()
    ),
    // Batch 1007 — overflow banner when the server's 200-row trade-list
    // cap trims the payload. Silent for ordinary users (tradesTotal
    // equals trades.length); only surfaces for power-users with 200+
    // historical trades so they know older rows still exist server-side
    // (queryable by id, reachable via CSV export).
    tradesTotal != null && trades.length > 0 && tradesTotal > trades.length && h('div', {
      style: {
        margin: '0 0 12px', padding: '10px 14px', fontSize: 12,
        background: 'rgba(30,165,255,0.08)',
        border: '1px solid var(--accent-border)',
        borderRadius: 8, color: 'var(--text-secondary)',
        display: 'flex', alignItems: 'center', gap: 10
      },
      title: 'Server caps the list at 200 rows. Older trades stay on file — download the CSV for a full history.'
    },
      h('span', null, '⇄ ',
        'Showing most recent ',
        h('strong', { style: { color: 'var(--text-primary)' } }, trades.length),
        ' of ',
        h('strong', { style: { color: 'var(--accent)' } }, tradesTotal),
        ' trades. Use ⇣ CSV for the full history.')
    ),
    filtered.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
          // Batch 822 — empty-state distinguishes "never traded" from
          // "filtered to nothing". A brand-new account should get a
          // CTA to the marketplace; a filtered view should offer to
          // clear the filter instead.
          trades.length === 0
            ? h('div', null,
                h('div', { style: { fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
                  'No trades yet'),
                h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', maxWidth: 340, margin: '0 auto 14px', lineHeight: 1.5 } },
                  "Every Buy Now purchase and every auction you win or lose lands here. Browse the marketplace to get started."),
                h('div', { style: { display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' } },
                  h('a', { className: 'btn btn-accent', href: '/market', style: { padding: '6px 14px', fontSize: 12 } }, 'Browse marketplace'),
                  h('a', { className: 'btn btn-ghost', href: '/sell', style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 12 } }, 'Sell an item')
                ))
            : h('div', null,
                h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 10 } },
                  'No trades match this filter.'),
                (filter !== 'ALL' || roleFilter !== 'all' || tradeSearch || tradeDateFrom != null || tradeDateTo != null)
                  && h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 12 },
                    onClick: () => {
                      setFilter('ALL');
                      setRoleFilter('all');
                      setTradeSearch('');
                      setTradeDateFrom(null);
                      setTradeDateTo(null);
                    }
                  }, 'Clear filter')
              ))
      : h('div', { className: 'trade-list' },
          filtered.map(t => {
            const isSeller = me && t.sellerUserId === me.id;
            const isBuyer  = me && t.buyerUserId === me.id;
            const meta = STATE_LABEL[t.state] || { label: t.state, color: '#8590b3', step: 0 };
            // Automated delivery via the secure Steam bot. The backend sets
            // botManaged / botDeliveryState on the trade once a bot takes the
            // item into escrow. Until those fields reach the client this is
            // falsy and the existing manual flow is used unchanged. When true,
            // the seller is NEVER asked to send a Steam offer by hand.
            const automated = t.botManaged === true || !!t.botDeliveryState;
            // Item thumbnail (decorative — the item name beside it is the
            // actionable text, so aria-hidden). 44px square, object-fit
            // contain, rounded, accentColor at ~10% as backdrop. Falls
            // back to a generic gift-box icon for legacy trades whose
            // itemId no longer resolves (itemImageUrl will be null).
            // Wrapped INSIDE the same link as the item name below so
            // clicking thumb or text both navigate to /item/:id.
            const accent = t.itemAccentColor || 'var(--accent)';
            const thumbBg = t.itemAccentColor
              ? (t.itemAccentColor + '1A')        // 10% alpha hex suffix
              : 'rgba(30,165,255,0.08)';
            const thumb = h('span', {
              'aria-hidden': true,
              style: {
                width: 44, height: 44, flex: '0 0 44px',
                borderRadius: 6, background: thumbBg,
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                marginRight: 10, overflow: 'hidden'
              }
            }, t.itemImageUrl
              ? h('img', {
                  src: t.itemImageUrl, alt: '', loading: 'lazy',
                  // Steam CDN 403s hotlinked requests — strip referrer so it loads;
                  // hide a genuinely broken img instead of the broken-image glyph.
                  referrerPolicy: 'no-referrer',
                  onError: (e) => { e.currentTarget.style.display = 'none'; },
                  style: { width: '100%', height: '100%', objectFit: 'contain' }
                })
              : h(MaterialIcon, { name: 'inventory_2', size: 24, color: accent }));
            return h('div', { key: t.id, id: 'trade-' + t.id, className: `trade-row ${(t.state || '').toLowerCase()}` },
              h('div', { className: 'trade-main' },
                h('div', { className: 'trade-title', style: { display: 'flex', alignItems: 'center', flexWrap: 'wrap' } },
                  (isSeller ? '→ ' : '← '),
                  // Link the item name to the item detail page when the
                  // trade carries an itemId. The thumbnail rides INSIDE
                  // the same anchor so click anywhere on thumb+name
                  // navigates. Opens in the same tab so a buyer reviewing
                  // a pending trade can quickly check the catalogue
                  // history / current floor.
                  t.itemId
                    ? h('a', {
                        href: '/item/' + t.itemId,
                        style: { color: 'inherit', textDecoration: 'none', display: 'inline-flex', alignItems: 'center' },
                        title: 'Open item detail',
                        onClick: (e) => e.stopPropagation()
                      }, thumb, t.itemName || ('Trade #' + t.id))
                    : h('span', { style: { display: 'inline-flex', alignItems: 'center' } },
                        thumb, t.itemName || ('Trade #' + t.id)),
                  // marginLeft separates the role from the item name — both are
                  // adjacent flex items in .trade-title (no gap on the row), so
                  // without it the name butted directly into the label
                  // ("Crop TopYou are buying").
                  h('span', { className: 'trade-role', style: { marginLeft: 8 } }, isSeller ? 'You are selling' : 'You are buying'),
                  // Counterparty identity chip (batch 400) — avatar + name
                  // next to the role so a long trade list is scannable by
                  // person, not just by item. Clickable to the seller's
                  // public stall (sensible default even for buyer-side
                  // trades since a public stall is the only user-scoped
                  // page we expose). Silent when the counterparty is the
                  // system / has no avatar yet.
                  t.counterpartyName && (() => {
                    const cpId = isSeller ? t.buyerUserId : t.sellerUserId;
                    // Rating chip (batch 402). Hidden below the 3-review
                    // noise floor so a brand-new counterparty doesn't
                    // look artificially good/bad on a single rating.
                    const rating = parseFloat(t.counterpartyRating);
                    const reviewCount = parseInt(t.counterpartyReviewCount || 0, 10);
                    const ratingChip = (Number.isFinite(rating) && reviewCount >= 3)
                      ? h('span', {
                          style: {
                            marginLeft: 6, fontSize: 10, fontWeight: 700,
                            padding: '1px 6px', borderRadius: 4,
                            background: rating >= 4.5 ? 'rgba(34,197,94,0.15)'
                                      : rating >= 3.5 ? 'rgba(251,191,36,0.15)'
                                      : 'rgba(248,113,113,0.15)',
                            color: rating >= 4.5 ? 'var(--green)'
                                  : rating >= 3.5 ? '#fbbf24'
                                  : 'var(--red)'
                          },
                          title: `${rating.toFixed(1)}★ across ${reviewCount} review${reviewCount === 1 ? '' : 's'}`
                        }, rating.toFixed(1) + '★')
                      : null;
                    const body = [
                      t.counterpartyAvatarUrl && h('img', {
                        src: t.counterpartyAvatarUrl,
                        alt: '',
                        // Hide the avatar if the URL fails to load so a dead
                        // image (e.g. a stale Steam avatar 404) collapses to
                        // just the name instead of showing the UA broken-image
                        // glyph next to the counterparty.
                        onError: (e) => { e.target.style.display = 'none'; },
                        style: { width: 16, height: 16, borderRadius: '50%', verticalAlign: 'middle', marginRight: 6 }
                      }),
                      t.counterpartyName,
                      ratingChip
                    ];
                    const common = {
                      style: {
                        marginLeft: 10, fontSize: 11, color: 'var(--text-muted)',
                        display: 'inline-flex', alignItems: 'center'
                      },
                      title: `Counterparty: ${t.counterpartyName}`
                    };
                    return cpId
                      ? h('a', {
                          ...common,
                          href: '/stall/' + cpId,
                          onClick: (e) => e.stopPropagation(),
                          // Preserve the muted look — avoid the accent
                          // recolor that `<a>` inherits by default.
                          style: { ...common.style, textDecoration: 'none' }
                        }, body)
                      : h('span', common, body);
                  })()
                ),
                h('div', { className: 'trade-state', style: { color: meta.color } },
                  // Automated delivery relabels the seller-send / buyer-confirm
                  // states so neither side reads them as "a human must act".
                  automated && t.state === 'PENDING_SELLER_SEND' ? 'Delivering automatically'
                    : automated && t.state === 'PENDING_BUYER_CONFIRM' ? 'Delivered — awaiting confirmation'
                    : meta.label,
                  // Batch 560 — "Seller sent N ago" chip on PENDING_BUYER_CONFIRM
                  // rows. Batch 550 added the sent_at column; surfacing it
                  // here gives the buyer a concrete answer to "when did the
                  // seller actually ship?" without cross-checking the Steam
                  // inbox. Silent on other states (sent_at is null there).
                  t.state === 'PENDING_BUYER_CONFIRM' && t.sentAt && h('span', {
                    style: { marginLeft: 10, fontSize: 11, color: 'var(--accent)', fontWeight: 700 },
                    title: `Seller marked sent at ${new Date(t.sentAt).toLocaleString()}`
                  }, '· sent', timeAgo(t.sentAt)),
                  // Countdown chip — server computes an absolute epoch-ms
                  // deadline for states that auto-cancel or auto-release.
                  // Converts to a terse "Xh left / Xd left" so users know
                  // how urgent each step is without opening the trade.
                  // Turns red once under 12h so stale trades nag visually.
                  t.expiresAt && (() => {
                    const msLeft = t.expiresAt - nowTick;
                    if (msLeft <= 0) return null;
                    const hours = msLeft / 3_600_000;
                    let label;
                    if (hours < 1)       label = Math.max(1, Math.round(msLeft / 60_000)) + 'm left';
                    else if (hours < 24) label = Math.round(hours) + 'h left';
                    else                 label = Math.round(hours / 24) + 'd left';
                    const urgent = hours < 12;
                    // The deadline does opposite things per state — name
                    // which one, viewer-aware, so a buyer doesn't read a
                    // generic "Auto-resolves" while the timer is actually
                    // about to release their escrow to the seller.
                    const when = new Date(t.expiresAt).toLocaleString();
                    const tip = t.state === 'PENDING_BUYER_CONFIRM'
                      ? (isSeller ? `Funds auto-release to you at ${when}`
                                  : `Funds auto-release to the seller at ${when}`)
                      : (isSeller ? `Auto-cancels (item returns to you) at ${when}`
                                  : `Auto-cancels and refunds you at ${when}`);
                    return h('span', {
                      style: {
                        marginLeft: 10, fontSize: 11, fontWeight: 700,
                        color: urgent ? 'var(--red)' : 'var(--text-muted)'
                      },
                      title: tip
                    }, '· ⏱ ', label);
                  })()
                ),
                // Labeled phase stepper — four checkpoints with descriptive
                // text so users know what each dot represents. DISPUTED /
                // CANCELLED states drop the bar to a single red/grey chip.
                (t.state === 'DISPUTED' || t.state === 'CANCELLED')
                  ? h('div', { className: `trade-progress terminal ${(t.state || '').toLowerCase()}` },
                      h('span', null, t.state === 'DISPUTED' ? 'Trade disputed — awaiting staff review' : 'Trade cancelled')
                    )
                  : h('div', { className: 'trade-progress-stepper six-node' },
                      (() => {
                        // Batch 899 — per-phase timestamp tooltips. Uses
                        // what the backend actually records:
                        //   createdAt → step 1 (trade opened)
                        //   sentAt    → step 3 (seller pressed Mark Sent)
                        //   settledAt → step 5 (verified)
                        // Gaps (steps 2, 4, 6) fall back to the phase
                        // name. Helps a user with a dragging trade
                        // anchor "how long has this been stuck?".
                        const fmtTime = (ms) => ms ? `${new Date(ms).toLocaleString()} (${timeAgo(ms)})` : null;
                        const stepTime = (step) => {
                          if (step === 1) return fmtTime(t.createdAt);
                          if (step === 3) return fmtTime(t.sentAt);
                          if (step === 5) return fmtTime(t.settledAt);
                          return null;
                        };
                        return [
                          { step: 1, short: 'Seller',   icon: '—', anchor: true  },
                          { step: 2, short: 'Accepts',  icon: null, anchor: false },
                          { step: 3, short: 'Sends',    icon: null, anchor: false },
                          { step: 4, short: 'Receives', icon: null, anchor: false },
                          { step: 5, short: 'Verified', icon: null, anchor: false },
                          { step: 6, short: 'Buyer',    icon: '—', anchor: true  }
                        ].map((p, idx, arr) => {
                          const ts = stepTime(p.step);
                          return h('div', {
                            key: p.step,
                            className: `trade-phase ${p.step <= meta.step ? 'on' : ''} ${p.step === meta.step ? 'current' : ''} ${p.anchor ? 'anchor' : ''}`,
                            title: ts ? `${p.short} · ${ts}` : p.short
                          },
                            h('div', { className: 'trade-phase-dot' },
                              p.icon
                                ? p.icon
                                // meta.step is the phase being WAITED ON, so it is
                                // not done yet: tick only the phases before it.
                                // `<=` ticked "Accepts" while the seller had not
                                // accepted and "Receives" before the buyer confirmed.
                                : (p.step < meta.step ? '✓' : (p.step - 1))
                            ),
                            h('div', { className: 'trade-phase-label' }, p.short),
                            idx < arr.length - 1 && h('div', { className: 'trade-phase-bar' })
                          );
                        });
                      })()
                    ),
                t.note && h('div', { className: 'trade-note' }, '"' + t.note + '"'),
                // Counterparty Steam trade URL — only shown during the
                // active escrow window (not after VERIFIED/CANCELLED). For
                // a seller this is the buyer's trade URL (so the seller
                // can send the offer); for a buyer this is the seller's
                // (so the buyer can verify the incoming offer came from
                // the right Steam account).
                // On the automated path the bot delivers, so the seller has no
                // manual offer to send — hide the counterparty trade-URL block.
                !automated && t.counterpartyTradeUrl && !['VERIFIED','CANCELLED'].includes(t.state) &&
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
                        // Counterparty trade URL — sellers paste this
                        // into Steam to send the trade offer, so silent
                        // success ("did it copy or not?") is a real
                        // hold-up. Toast confirms; prompt fallback for
                        // missing/blocked clipboard API. Pre-fix the
                        // success path was silent: clipboard wrote but
                        // the user had no signal whether to switch
                        // tabs to Steam yet.
                        try {
                          if (navigator.clipboard?.writeText) {
                            await navigator.clipboard.writeText(t.counterpartyTradeUrl);
                            toast(isSeller
                              ? "Buyer's trade URL copied — paste into Steam to send the offer."
                              : "Seller's trade URL copied.", 'ok');
                          } else {
                            window.prompt('Copy this trade URL:', t.counterpartyTradeUrl);
                          }
                        } catch (_) { window.prompt('Copy this trade URL:', t.counterpartyTradeUrl); }
                      }
                    }, '⎘'),
                    // Direct Steam profile link (batch 399). Lets a seller
                    // sanity-check the buyer's account (friend count,
                    // games, age) before sending a Steam trade offer —
                    // a fresh empty account is a fraud red flag.
                    t.counterpartySteamProfileUrl && h('a', {
                      className: 'trade-counterparty-copy',
                      href: t.counterpartySteamProfileUrl,
                      target: '_blank',
                      rel: 'noopener noreferrer',
                      title: 'Open Steam profile in a new tab',
                      style: { textDecoration: 'none' }
                    }, '—')
                  ),
                // Automated delivery banner — replaces every manual-offer
                // instruction when the bot is handling the trade. Shown to both
                // sides during the active escrow window.
                automated && ['PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM'].includes(t.state) &&
                  h('div', { className: 'trade-auto-banner' },
                    h('span', { className: 'trade-auto-banner-icon' }, '🔒'),
                    h('span', null,
                      t.state === 'PENDING_SELLER_SEND'
                        ? (isSeller
                            ? 'Your item is in escrow. Our secure bot is delivering it to the buyer automatically — no action needed.'
                            : 'The item is in escrow and being delivered automatically by our secure bot. It will arrive in your inventory shortly.')
                        : (isSeller
                            ? 'Delivered automatically. Waiting for the buyer to confirm receipt so your payout is released.'
                            : 'Delivered by our secure bot. Confirm receipt once it lands in your inventory to release payment.'))
                  ),
                // Nudge the viewer to set their own URL if the counterparty
                // can't contact them (common first-time seller friction).
                // Manual flow only — irrelevant when the bot delivers.
                // SELLER side only: in the manual flow the seller sends the
                // offer to the BUYER's URL; the buyer never uses the seller's.
                // Shown to buyers it read as a problem with their purchase
                // ("The seller has no Steam trade URL on file yet") on every
                // healthy trade.
                !automated && isSeller && !t.counterpartyTradeUrl && ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM'].includes(t.state) &&
                  h('div', { className: 'trade-counterparty-missing' },
                    'The buyer has no Steam trade URL on file yet.'),
                // Steam quick-action shortcuts (batch 285) — drops the
                // user one click from the action they need to take.
                // Seller in SEND state → open their inventory to pick
                // the item; buyer in CONFIRM state → open the Steam
                // offers inbox to verify the incoming offer.
                !automated && isSeller && t.state === 'PENDING_SELLER_SEND' && h('div', {
                  style: { marginTop: 6, display: 'flex', gap: 8, fontSize: 11 }
                },
                  h('a', {
                    href: 'https://steamcommunity.com/my/inventory/',
                    target: '_blank',
                    rel: 'noopener noreferrer',
                    style: {
                      color: 'var(--accent)', textDecoration: 'none',
                      padding: '3px 10px', borderRadius: 4,
                      border: '1px solid var(--accent-border)',
                      background: 'rgba(30,165,255,0.08)'
                    },
                    title: "Open your Steam inventory in a new tab — pick the item, then send a trade offer to the buyer's Steam URL above."
                  }, 'Open my Steam inventory')
                ),
                !isSeller && t.state === 'PENDING_BUYER_CONFIRM' && h('div', {
                  style: { marginTop: 6, display: 'flex', gap: 8, fontSize: 11, flexWrap: 'wrap', alignItems: 'center' }
                },
                  // Batch 773 — if the seller included a direct trade-offer
                  // URL at Mark-Sent time, show a one-click deep link to
                  // that exact offer. Still render the generic inbox
                  // shortcut alongside so a buyer who can't open the
                  // direct link for any reason has a fallback.
                  t.tradeOfferUrl && /^https:\/\/steamcommunity\.com\/tradeoffer\//.test(t.tradeOfferUrl) && h('a', {
                    href: t.tradeOfferUrl,
                    target: '_blank',
                    rel: 'noopener noreferrer',
                    style: {
                      color: '#0b1220', textDecoration: 'none',
                      padding: '3px 10px', borderRadius: 4,
                      background: 'var(--green)', fontWeight: 700
                    },
                    title: 'Open the Steam offer the seller sent — direct link from the trade record'
                  }, 'Open this Steam offer'),
                  h('a', {
                    href: 'https://steamcommunity.com/my/tradeoffers/',
                    target: '_blank',
                    rel: 'noopener noreferrer',
                    style: {
                      color: 'var(--accent)', textDecoration: 'none',
                      padding: '3px 10px', borderRadius: 4,
                      border: '1px solid var(--accent-border)',
                      background: 'rgba(30,165,255,0.08)'
                    },
                    title: "Open your Steam offers inbox in a new tab — verify the incoming offer is from the right Steam account, accept it, then click Confirm Receipt below."
                  }, t.tradeOfferUrl ? 'Inbox' : 'Check my Steam offers')
                )
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
                    // Pre-fix: success path was silent — clicking the
                    // trade-id chip with a button label '#42 ⎘' that
                    // never visually changed left the user wondering
                    // whether the copy landed before pasting into a
                    // support ticket. Toast on success now matches
                    // every other clipboard surface in this file.
                    try {
                      if (navigator.clipboard?.writeText) {
                        await navigator.clipboard.writeText(s);
                        toast(`Trade ${s} copied — paste into your support ticket.`, 'ok');
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
                // Automated: bot delivers — no "Mark Sent" button. Calm status
                // pill instead so the seller knows nothing is required of them.
                isSeller && t.state === 'PENDING_SELLER_SEND' && automated &&
                  h('span', { className: 'trade-auto-status' }, '🔒 Delivering automatically'),
                // Manual fallback: seller marks the Steam offer as sent.
                isSeller && t.state === 'PENDING_SELLER_SEND' && !automated &&
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
                  }, 'Leave Review'),
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
                    // Batch 817 — opens a drawer instead of the legacy
                    // two-step window.prompt. Drawer state lives at the
                    // TradesTab level so a dismissed trade row doesn't
                    // strand a half-filled form.
                    onClick: () => setReportUserTrade({
                      ...t,
                      __target: isSeller ? t.buyerUserId : t.sellerUserId,
                      __isSeller: isSeller
                    })
                  }, 'Report'),
                // Private chat toggle — only meaningful while the trade
                // is still live. Shows messages count when closed so the
                // user sees there's unread activity without opening.
                !['CANCELLED'].includes(t.state) &&
                  h('button', {
                    className: 'btn btn-ghost',
                    style: {
                      border: '1px solid var(--border)', padding: '6px 10px', fontSize: 11,
                      // Highlight the button when there's unread chat
                      // from the counterparty so a user with 12 trades
                      // can scan-find the row needing reply (batch 281).
                      background: t.unreadCount > 0 ? 'rgba(96,165,250,0.15)' : null,
                      color:      t.unreadCount > 0 ? 'var(--accent)' : null,
                      fontWeight: t.unreadCount > 0 ? 700 : 600
                    },
                    onClick: () => toggleChat(t.id),
                    title: t.unreadCount > 0
                      ? `${t.unreadCount} unread message${t.unreadCount === 1 ? '' : 's'} from the counterparty`
                      : 'Private chat with the other trade participant'
                  },
                    // Speech-bubble glyph (✕ when open) — matches the staff
                    // trade-chat button. Previously a bare em-dash '—' that
                    // rendered as a stray dash glued to the label ("—Chat").
                    openChat === t.id ? '✕ ' : '💬 ',
                    openChat === t.id ? 'Hide chat' : 'Chat',
                    // Unread badge — accent-coloured pill with the
                    // server-side count. Only shown when > 0 AND chat
                    // panel is closed (open + you've scrolled = read).
                    t.unreadCount > 0 && openChat !== t.id && h('span', {
                      style: {
                        marginLeft: 6, fontSize: 10, fontWeight: 800,
                        color: '#0b0f1a', background: 'var(--red)',
                        padding: '0 6px', borderRadius: 8, minWidth: 14,
                        display: 'inline-block', textAlign: 'center'
                      }
                    }, t.unreadCount > 99 ? '99+' : t.unreadCount),
                    (chatThreads[t.id] || []).length > 0 && !(t.unreadCount > 0 && openChat !== t.id) &&
                      h('span', { style: { marginLeft: 6, fontSize: 10, color: 'var(--text-muted)' } },
                        '· ' + chatThreads[t.id].length)
                  ),
                // Inline last-message preview — shown only when the chat
                // panel is closed (open = the user is reading the full
                // thread). Server-truncated to 80 chars (batch 283).
                // Counterparty name prefix when the message is from
                // them, "you:" prefix when it's the user's own message.
                t.lastMessage && openChat !== t.id && h('div', {
                  style: {
                    fontSize: 11, color: 'var(--text-muted)', marginTop: 4,
                    fontStyle: 'italic',
                    overflow: 'hidden', textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap', maxWidth: '100%'
                  },
                  title: `Last message · ${new Date(t.lastMessage.createdAt).toLocaleString()}`
                },
                  h('span', { style: { fontWeight: 700, fontStyle: 'normal' } },
                    me && t.lastMessage.senderUserId === me.id
                      ? 'you: '
                      : `${t.counterpartyName || 'them'}: `),
                  t.lastMessage.body
                )
              ),
              // Trade Protection — optional paid buyer add-on. The panel
              // owns its own data fetching (quote + protection state) and
              // self-selects which of three states to render: an opt-in
              // panel for an unprotected buyer in escrow, a "Protected"
              // badge for both parties once cover is on, or nothing.
              // Spans the full grid like the chat panel below. `onChanged`
              // re-pulls the trade list so the row picks up the new
              // `protected` flag on the next render.
              h(TradeProtectionPanel, { trade: t, me, onChanged: load }),
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
                          // Redacted messages (batch 349) — staff soft-redacted this
                          // row so the body is cleared and a placeholder renders in
                          // its place. Styled muted + italic so participants can
                          // tell moderation intervened without reading the original.
                          m.redactedAt
                            ? h('div', {
                                style: {
                                  color: 'var(--text-muted)',
                                  fontStyle: 'italic',
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: 6,
                                  fontSize: 11
                                },
                                title: 'Removed ' + new Date(m.redactedAt).toLocaleString()
                              },
                                h('span', null, '—'),
                                h('span', null, 'Message removed by moderators')
                              )
                            : h('div', { style: { color: 'var(--text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' } },
                                // Auto-linkify URLs (batch 441) — Steam trade
                                // URLs are the most-pasted payload here.
                                linkifyText(m.body, 'tcm-' + m.id)
                              ),
                          h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2, display: 'flex', alignItems: 'center', gap: 6 } },
                            h('span', null,
                              (mine ? 'You' : (t.counterpartyName || 'Other')) + ' · ' + timeAgo(m.createdAt)),
                            // Read receipt — only on OWN messages (batch 280).
                            // ✓✓ in accent when the counterparty has loaded the
                            // thread; ✓ in muted when still unread. Tooltip
                            // shows the absolute "read at" timestamp.
                            mine && (m.readAt
                              ? h('span', {
                                  style: { color: 'var(--accent)', fontWeight: 700 },
                                  title: 'Read ' + new Date(m.readAt).toLocaleString()
                                }, '✓✓')
                              : h('span', {
                                  style: { color: 'var(--text-muted)' },
                                  title: 'Sent — counterparty has not opened the thread yet'
                                }, '✓'))
                          )
                        );
                      })
                ),
                // Quick-reply chips — one-tap common trade-flow phrases.
                // Fills the draft so the user can tweak before sending, or
                // hit Enter / Send immediately. Role-aware: sellers see
                // "Sent the trade" / "Give me 5 min", buyers see "Got it,
                // thanks" / "Confirmed on my end". Trade-URL-missing state
                // shows a prompt for it.
                (() => {
                  const isBuyer = me && t.buyerUserId === me.id;
                  const base = [
                    { l: 'Hey',          v: 'Hey — when you\'re ready.' },
                    { l: '5 min',        v: "Give me 5 minutes and I'll be right with you." },
                    { l: 'Trade URL?',   v: "Can you send me your Steam trade URL? It's under Profile → Personal Info on SkinBox." }
                  ];
                  const sellerOnly = [
                    { l: '✈ Sent',         v: "I've sent the Steam trade offer — accept it on your end to confirm." }
                  ];
                  const buyerOnly = [
                    { l: 'Got it',       v: "Got the item, thanks! Marking as confirmed now." }
                  ];
                  const chips = [...(isBuyer ? buyerOnly : sellerOnly), ...base];
                  return h('div', { style: { display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 6 } },
                    chips.map(c => h('button', {
                      key: c.l,
                      className: 'btn btn-ghost',
                      style: { padding: '3px 8px', fontSize: 10, border: '1px solid var(--border)' },
                      onClick: () => setChatDraft(c.v),
                      title: c.v
                    }, c.l))
                  );
                })(),
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
        ref: reviewPanelRef,
        className: 'modal review-modal',
        onClick: e => e.stopPropagation(),
        style: { maxWidth: 480, padding: 0 },
        // Batch 829 — review modal a11y. Matches the pattern from
        // batches 821/826/827: role=dialog + aria-modal + aria-
        // labelledby on the heading. Escape-to-close + focus trap
        // are wired via useDialogA11y in TradesTab so the ref above
        // pulls focus into the modal on open and restores it on close.
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'review-modal-title'
      },
        h('button', { className: 'modal-close', onClick: closeReview, 'aria-label': 'Close' }, '✕'),
        h('div', { style: { padding: '24px 26px 20px' } },
          h('div', { id: 'review-modal-title', style: { fontSize: 20, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 4 } },
            'Leave a review'),
          h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 18 } },
            'For "', h('strong', { style: { color: 'var(--text-secondary)' } }, reviewTrade.itemName || ('Trade #' + reviewTrade.id)), '"'),

          // Star picker — batch 842 upgrade: proper WAI-ARIA radio
          // group with arrow-key navigation and roving tabindex so a
          // keyboard-only user can land on the currently-selected star,
          // then tap ← / → to move between choices. Previously used
          // aria-pressed which screen readers announce as "toggle
          // button" rather than as a single-choice rating control.
          h('div', {
            style: { display: 'flex', justifyContent: 'center', gap: 6, marginBottom: 18 },
            role: 'radiogroup',
            'aria-label': 'Star rating (1 to 5)'
          },
            [1, 2, 3, 4, 5].map(n => h('button', {
              key: n,
              role: 'radio',
              'aria-checked': reviewStars === n,
              'aria-label': n + (n === 1 ? ' star' : ' stars'),
              // Roving tabindex — only the selected star gets focus.
              // Tab lands on the current pick; Shift-Tab exits group.
              tabIndex: reviewStars === n ? 0 : -1,
              onClick: () => setReviewStars(n),
              onKeyDown: (e) => {
                let next = null;
                if (e.key === 'ArrowRight' || e.key === 'ArrowUp')   next = Math.min(5, n + 1);
                else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') next = Math.max(1, n - 1);
                else if (e.key === 'Home') next = 1;
                else if (e.key === 'End')  next = 5;
                if (next != null) {
                  e.preventDefault();
                  setReviewStars(next);
                  // Move focus to the newly-selected star so the
                  // keyboard user sees their own selection.
                  requestAnimationFrame(() => {
                    const btns = e.currentTarget.parentElement?.querySelectorAll('button[role="radio"]');
                    if (btns && btns[next - 1]) btns[next - 1].focus();
                  });
                }
              },
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
            'aria-label': 'Optional review text (max 500 characters)',
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
          }, 'Review saved'),

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
    confirmTrade && h('div', { className: 'modal-backdrop', onClick: () => !busy && setConfirmTrade(null) },
      h('div', {
        ref: confirmPanelRef,
        className: 'trade-confirm-modal',
        onClick: e => e.stopPropagation(),
        // Batch 826 — escrow-release is financially irreversible, so
        // the modal gets proper a11y: role=dialog + aria-modal so
        // screen readers announce it, aria-labelledby on the title so
        // the content is readable in the dialog announcement.
        // Batch 1167 — Escape-to-close, focus trap, and restore-focus
        // are wired via useDialogA11y at the TradesTab level (the ref
        // above carries it). Can't add hooks inside a conditional
        // render so they have to live on the parent component.
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'trade-confirm-title'
      },
        h('div', { className: 'trade-confirm-title', id: 'trade-confirm-title' }, 'Confirm receipt'),
        h('div', { className: 'trade-confirm-sub' },
          'You are about to release funds to the seller. This cannot be undone. Only confirm if you have received the Steam trade offer and accepted it.'),
        h('div', { className: 'trade-confirm-detail' },
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Item'),
            h('span', { className: 'trade-confirm-v' }, confirmTrade.itemName || ('Trade #' + confirmTrade.id))
          ),
          // Seller identity + Steam profile link (batch 401). Guards
          // against the "I confirmed the wrong trade" foot-gun — a buyer
          // with multiple pending trades can double-check here that the
          // Steam offer they accepted came from THIS seller's Steam
          // account before releasing funds.
          confirmTrade.counterpartyName && h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Seller'),
            h('span', { className: 'trade-confirm-v', style: { display: 'flex', alignItems: 'center', gap: 8 } },
              confirmTrade.counterpartyAvatarUrl && h('img', {
                src: confirmTrade.counterpartyAvatarUrl,
                alt: '',
                style: { width: 20, height: 20, borderRadius: '50%' }
              }),
              confirmTrade.counterpartyName,
              confirmTrade.counterpartySteamProfileUrl && h('a', {
                href: confirmTrade.counterpartySteamProfileUrl,
                target: '_blank',
                rel: 'noopener noreferrer',
                style: { fontSize: 10, color: 'var(--accent)', textDecoration: 'none', marginLeft: 4 },
                title: 'Open seller Steam profile to verify the incoming offer came from this account'
              }, 'verify ↗')
            )
          ),
          // Batch 780 — Steam offer link surfaced at confirm time (if
          // the seller attached one at Mark-Sent via batch 773). Lets
          // the buyer open the exact offer in a new tab to verify the
          // items, trade partner, and amount before releasing escrow.
          // Hidden on legacy trades without a captured URL.
          confirmTrade.tradeOfferUrl && /^https:\/\/steamcommunity\.com\/tradeoffer\//.test(confirmTrade.tradeOfferUrl) && h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Steam offer'),
            h('span', { className: 'trade-confirm-v', style: { display: 'flex', alignItems: 'center', gap: 8 } },
              h('a', {
                href: confirmTrade.tradeOfferUrl,
                target: '_blank',
                rel: 'noopener noreferrer',
                style: { color: 'var(--accent)', textDecoration: 'underline', fontSize: 12 },
                title: 'Re-check the Steam offer in a new tab before releasing funds'
              }, 'Open offer ↗')
            )
          ),
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Price you paid'),
            h('span', { className: 'trade-confirm-v' }, fmt(confirmTrade.price || 0))
          ),
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Platform fee (2%)'),
            h('span', { className: 'trade-confirm-v muted' }, fmt(platformFee(confirmTrade.price || 0)))
          ),
          h('div', { className: 'trade-confirm-row' },
            h('span', { className: 'trade-confirm-k' }, 'Seller will receive'),
            // sellerPayout(), not price * 0.98 — the server rounds the FEE and
            // subtracts, so on a split-cent price (.25 / .75) the naive form
            // promised the seller a cent the server will not pay.
            h('span', { className: 'trade-confirm-v accent' }, fmt(sellerPayout(confirmTrade.price || 0)))
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
    ),
    // Batch 816 — Mark Sent drawer. Replaces the window.prompt call
    // with a proper inline dialog that shows the trade context (item,
    // buyer, price) so the seller is sure which trade they're
    // marking sent, and gives live regex validation on the URL input
    // matching the server-side check.
    markSentTrade && h(MarkSentDrawer, {
      trade: markSentTrade,
      onCancel: () => setMarkSentTrade(null),
      onSubmit: (url) => submitMarkSent(markSentTrade.id, url)
    }),
    // Batch 817 — Report counterparty drawer. Replaces the two-step
    // window.prompt flow with a structured picker + textarea. Still
    // submits to the same `reportUser` API used by the legacy flow.
    reportUserTrade && h(ReportCounterpartyDrawer, {
      trade: reportUserTrade,
      onCancel: () => setReportUserTrade(null),
      onSubmitted: () => { setReportUserTrade(null); }
    }),
    // Dispute drawer (batch 275) — opens when the row's "Dispute"
    // button is clicked. Submitting fires the existing tradeDispute
    // API and reloads the trade list so the row flips to DISPUTED.
    disputeTrade && h(DisputeTradeDrawer, {
      trade: disputeTrade,
      isSeller: me && disputeTrade.sellerUserId === me.id,
      onCancel: () => setDisputeTrade(null),
      onSubmitted: async () => {
        // Batch 889 — name the item + bind the response-time SLO into
        // the toast so the filer gets concrete expectations (≤4h for
        // trade/payment issues per the SLO page) instead of a vague
        // "staff will review".
        const label = disputeTrade.itemName ? `"${disputeTrade.itemName}"` : `trade #${disputeTrade.id}`;
        setDisputeTrade(null);
        await load();
        toast(`Dispute filed on ${label} — staff will respond within 4 hours.`, 'ok');
      }
    }),
    // Refund-request drawer (batch 426). Replaces the previous
    // window.prompt — gives the user a context-anchored form with the
    // trade summary inline + a generous textarea for the reason.
    refundTrade && h('div', {
      className: 'cart-confirm-backdrop',
      onClick: () => { if (!refundBusy) { setRefundTrade(null); setRefundReason(''); } },
      style: { zIndex: 100 }
    },
      h('div', {
        ref: refundPanelRef,
        className: 'cart-confirm-panel',
        style: { maxWidth: 460 },
        onClick: e => e.stopPropagation(),
        // Batch 840 — refund-request drawer a11y. Inline drawer
        // (not extracted to a function) so we add role=dialog +
        // aria-modal + aria-labelledby directly on the panel.
        // Batch 1167 — Escape, focus trap, and restore-focus are
        // wired via useDialogA11y at the TradesTab level (the ref
        // above carries it).
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'refund-drawer-title'
      },
        h('div', { className: 'cart-confirm-title', id: 'refund-drawer-title' }, '↩ Request a refund'),
        h('div', { className: 'cart-confirm-sub' },
          'Files a support ticket with the trade details pre-attached. Staff usually replies within 24h.'),
        h('div', {
          style: {
            margin: '8px 0 12px', padding: '8px 10px',
            background: 'var(--bg-elevated)', border: '1px solid var(--border)',
            borderRadius: 6, fontSize: 12, color: 'var(--text-secondary)',
            lineHeight: 1.5
          }
        },
          h('strong', { style: { color: 'var(--text-primary)' } },
            refundTrade.itemName || ('Trade #' + refundTrade.id)),
          h('br', null),
          'Trade #', refundTrade.id, ' · paid ',
          h('strong', null, fmt(refundTrade.price ?? 0)),
          refundTrade.counterpartyName && h('span', null, ' · seller ',
            h('strong', null, refundTrade.counterpartyName))
        ),
        h('textarea', {
          style: {
            width: '100%', minHeight: 110, padding: '10px 12px',
            background: 'var(--bg-page-2, #0d1320)',
            border: '1px solid var(--border)', borderRadius: 6,
            color: 'var(--text-primary)', fontFamily: 'inherit', fontSize: 13
          },
          'aria-label': 'Refund / dispute reason',
          placeholder: "What went wrong? (item not received, item doesn't match, seller unresponsive…)",
          maxLength: 1000,
          value: refundReason,
          onChange: e => setRefundReason(e.target.value),
          autoFocus: true
        }),
        h('div', {
          style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 4, textAlign: 'right' }
        }, refundReason.length, ' / 1000'),
        h('div', { className: 'cart-confirm-actions', style: { marginTop: 14 } },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            disabled: refundBusy,
            onClick: () => { setRefundTrade(null); setRefundReason(''); }
          }, 'Cancel'),
          h('button', {
            className: 'btn btn-accent',
            disabled: refundBusy || !(refundReason || '').trim(),
            onClick: submitRefund
          }, refundBusy ? 'Submitting…' : 'Submit request')
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
  // A failed offers fetch must surface a Retry affordance, NOT read as "no
  // offers" — incoming offers are pending money decisions (a buyer's bid on
  // your item); a false-empty on an HTTP 500 makes a seller think offers
  // vanished. Mirrors the ProfileListingsTab err/aliveRef pattern. The silent
  // tab-focus refresh keeps last-good data on error instead of flipping to
  // the card.
  const [err, setErr] = useState(false);
  const aliveRef = useRef(true);
  const [tab, setTab]   = useState('incoming');
  const [busy, setBusy] = useState(false);
  // Synchronous re-entrancy latch (matches the trades busyRef / buyConfirmBusyRef
  // / wallet submittingRef). doAccept/doCounter/doRaise set setBusy AFTER their
  // await, so a rapid double-click fires the money action twice before the
  // disabled state lands. busyRef gates it synchronously; it auto-resets because
  // `busyRef.current = busy` re-syncs on every render after setBusy(false).
  const busyRef = useRef(busy); busyRef.current = busy;
  const [counterFor, setCounterFor] = useState(null);
  const [counterAmt, setCounterAmt] = useState('');
  // Batch 645 — sort dropdown. Heavy sellers with dozens of pending
  // offers want to prioritise "which should I accept first?". Options:
  //   newest  (default — mirrors the old unsorted behaviour closely)
  //   oldest  (clear the backlog top-down)
  //   amount  (highest cash first — "best offer" sort)
  //   closest (nearest to ask — lowest discount %, i.e. least concession)
  // Persisted per-tab so the Incoming and Outgoing views can keep
  // different sort defaults ("amount" for incoming, "newest" for
  // outgoing are typical power-user picks).
  const [sortBy, setSortBy] = useState(() => {
    try { return localStorage.getItem('sb_offers_sort') || 'newest'; }
    catch { return 'newest'; }
  });
  const setSort = (v) => {
    setSortBy(v);
    try { localStorage.setItem('sb_offers_sort', v); } catch (_) {}
  };
  // Date-range filter for the offers CSV export — bounds pass through
  // to /api/profile/offers.csv as ?from=&to= so a quarterly slice can
  // be downloaded for accounting reconciliation. Not applied to the
  // on-screen list because power-users sort by status and amount, not
  // date — but the bounds DO show on the CSV link tooltip.
  const [offersDateFrom, setOffersDateFrom] = useState(null);
  const [offersDateTo,   setOffersDateTo]   = useState(null);

  // Raw fetch both lists (not fetchIncoming/OutgoingOffers, which swallow
  // non-2xx into []) so a server error surfaces the Retry card instead of a
  // false "no offers". Throws if either leg is non-2xx.
  const fetchBoth = useCallback(async () => {
    const [ri, ro] = await Promise.all([
      fetch('/api/offers/incoming', { credentials: 'same-origin' }),
      fetch('/api/offers/outgoing', { credentials: 'same-origin' })
    ]);
    if (!ri.ok || !ro.ok) throw new Error('HTTP ' + (ri.ok ? ro.status : ri.status));
    const [i, o] = await Promise.all([ri.json(), ro.json()]);
    return { incoming: Array.isArray(i) ? i : [], outgoing: Array.isArray(o) ? o : [] };
  }, []);
  const load = useCallback(async () => {
    setErr(false);
    setData(null);
    try { const d = await fetchBoth(); if (aliveRef.current) setData(d); }
    catch (_) { if (aliveRef.current) setErr(true); }
  }, [fetchBoth]);
  // Silent refresh — same fetch pair, but doesn't flash the spinner by
  // clearing `data` first. Best-effort: on error it KEEPS the last-good data
  // (no overwrite with [], no error card) since it's a background tab-focus
  // refresh, not a user-initiated load.
  const silentRefresh = useCallback(async () => {
    try { const d = await fetchBoth(); if (aliveRef.current) setData(d); } catch (_) {}
  }, [fetchBoth]);
  useEffect(() => {
    aliveRef.current = true;
    load();
    return () => { aliveRef.current = false; };
  }, [load]);
  // Batch 665 — tab-focus silent refresh (mirrors the Trades tab + the
  // wallet). When the tab flips back to visible, refetch both offer
  // lists without a spinner flash. Critical for sellers who leave the
  // Offers tab open to watch for incoming offers from a second screen.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') silentRefresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [silentRefresh]);

  if (err) return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Couldn’t load your offers'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
      'Something went wrong fetching your offers. Nothing was lost — check your connection and try again.'),
    h('button', { className: 'btn btn-accent', onClick: load }, 'Retry')
  );
  if (data === null) return h('div', { className: 'spinner' });

  const run = async (fn) => {
    setBusy(true);
    try { await fn(); await load(); }
    finally { setBusy(false); }
  };
  // Batch 887 — explicit toasts on offer state transitions. `run` was
  // silent; sellers / buyers clicking accept/reject/cancel got no
  // confirmation beyond the row re-rendering in a new status pill.
  // Accept in particular is financially significant (wallet debit on
  // the buyer's side, trade opened on the seller's), so a visible
  // confirmation matters. Lookup the offer for item name + amount so
  // the toast cites what changed.
  const findOffer = (id) => (data?.incoming || []).find(o => o.id === id)
    || (data?.outgoing || []).find(o => o.id === id) || null;
  const doAccept = async (id) => {
    if (busyRef.current) return;
    busyRef.current = true;
    const o = findOffer(id);
    setBusy(true);
    try {
      const res = await acceptOffer(id);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Accept failed', 'err'); return; }
      await load();
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      toast(`Accepted offer on ${label} for ${fmt(o?.amount || 0)} — trade opened.`, 'ok');
    } finally { setBusy(false); }
  };
  const doReject = async (id) => {
    const o = findOffer(id);
    const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
    // Confirm — reject notifies the buyer and can't be undone; the ✕
    // button is small enough that a misclick shouldn't fire it.
    if (!confirm(`Reject the ${fmt(o?.amount || 0)} offer on ${label}? The buyer is notified and this can't be undone.`)) return;
    setBusy(true);
    try {
      const res = await rejectOffer(id);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Reject failed', 'err'); return; }
      await load();
      toast(`Offer on ${label} rejected.`, 'ok');
    } finally { setBusy(false); }
  };
  const doCancel = async (id) => {
    const o = findOffer(id);
    const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
    // Confirm — cancelling withdraws the offer (or declines a seller
    // counter); a single misclick shouldn't drop it silently.
    if (!confirm(`Cancel the ${fmt(o?.amount || 0)} offer on ${label}?`)) return;
    setBusy(true);
    try {
      const res = await cancelOffer(id);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Cancel failed', 'err'); return; }
      await load();
      toast(`Your offer on ${label} was cancelled.`, 'ok');
    } finally { setBusy(false); }
  };
  const doCounter = async (id) => {
    const amt = parseFloat(counterAmt);
    if (!amt || amt <= 0) return;
    if (busyRef.current) return;
    busyRef.current = true;
    const o = findOffer(id);
    setBusy(true);
    try {
      const res = await counterOffer(id, amt);
      if (res.code || res.error) { toast(res.message || res.error || 'Counter failed', 'err'); return; }
      setCounterFor(null); setCounterAmt('');
      await load();
      // Batch 910 — name the item + counter amount so the user sees the
      // exact offer row that changed hands. Consistent with the
      // accept/reject/cancel toasts above.
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      toast(`Counter sent on ${label} at ${fmt(amt)} — buyer notified.`, 'ok');
    } finally { setBusy(false); }
  };
  const doRaise = async (id) => {
    const amt = parseFloat(counterAmt);
    if (!amt || amt <= 0) return;
    if (busyRef.current) return;
    busyRef.current = true;
    const o = findOffer(id);
    setBusy(true);
    try {
      const { raiseOffer } = await import('./api.js');
      const res = await raiseOffer(id, amt);
      if (res.code || res.error) { toast(res.message || res.error || 'Raise failed', 'err'); return; }
      setCounterFor(null); setCounterAmt('');
      await load();
      // Batch 910 — name the item + new offer amount + original so the
      // buyer sees the concrete change. Useful when they're raising one
      // of several outgoing offers in the list.
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      const wasStr = o?.amount ? ` (was ${fmt(parseFloat(o.amount))})` : '';
      toast(`Raised offer on ${label} to ${fmt(amt)}${wasStr}.`, 'ok');
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
    // Auto-decline window — OfferService stamps a server-computed
    // `expiresAt` on every PENDING offer (driven by the configurable
    // offer.auto-decline-days). Use it directly. The old code recomputed
    // a hardcoded 7-day TTL off updatedAt||createdAt — which drifted
    // from the real auto-decline whenever the config wasn't 7 days, and
    // mismatched the sweeper (it keys off updatedAt) on countered offers.
    // The standalone OffersModal already reads expiresAt; this matches it.
    const expiresChip = (() => {
      if (!isPending || !o.expiresAt) return null;
      const left = o.expiresAt - Date.now();
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
        title: 'This offer auto-declines after a period of inactivity. Either side can accept, reject, or counter before then.'
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
        pct > 0 && h('div', { className: 'offer-pct' }, '−' + pct + '%'),
        // Seller-side net-after-fee preview (batch 492). Shows the
        // seller what they'd receive if they accept this incoming
        // offer — 2% platform fee matches the trade-settlement
        // constant the backend uses. Only rendered on incoming
        // PENDING offers where the accept button is relevant.
        isIncoming && isPending && (() => {
          const amt = parseFloat(o.amount) || 0;
          if (amt <= 0) return null;
          const net = sellerPayout(amt);
          return h('div', {
            style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2 },
            title: 'Platform fee is 2%. Final payout lands in your wallet after buyer confirms receipt.'
          }, 'You\'d net ', h('span', { style: { color: 'var(--green)', fontWeight: 700, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, fmt(net)));
        })()
      ),
      h('div', { className: 'offer-status-col' },
        h('div', { className: `wallet-tx-status ${o.status}` },
          o.status === 'PENDING'   ? 'Pending'
          : o.status === 'ACCEPTED'  ? 'Accepted'
          : o.status === 'REJECTED'  ? 'Rejected'
          : o.status === 'CANCELLED' ? 'Cancelled'
          : o.status === 'EXPIRED'   ? 'Expired'
          : o.status === 'COUNTERED' ? 'Countered'
          : o.status),
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
        // Outgoing pending offers — author is the buyer (USER) or the
        // seller's counter waiting on the buyer (SELLER). USER rows get
        // Raise + Cancel; SELLER counters get Accept + Decline so the
        // buyer can settle the seller's reply without leaving the list.
        // Counter-back is "decline + make a fresh offer" by design — the
        // bargain thread always alternates author, so a buyer who wants
        // to push back declines this counter and re-offers.
        isPending && !isIncoming && !isCountering && (
          o.author === 'SELLER'
            ? h('div', { style: { display: 'flex', gap: 4, marginTop: 6 } },
                h('button', { className: 'buy-btn', disabled: busy, onClick: () => doAccept(o.id), 'aria-label': 'Accept seller counter', title: `Accept ${fmt(o.amount)} counter` }, '✓ Accept'),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 },
                  disabled: busy, onClick: () => doCancel(o.id), title: 'Walk away from the counter'
                }, 'Decline')
              )
            : h('div', { style: { display: 'flex', gap: 4, marginTop: 6 } },
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--accent-border)', color: 'var(--accent)', padding: '5px 10px', fontSize: 11 },
                  disabled: busy,
                  onClick: () => { setCounterFor(o.id); setCounterAmt(((parseFloat(o.amount) || 0) + 1).toFixed(2)); },
                  title: 'Raise your offer without waiting for the seller'
                }, '↑ Raise'),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--border)', padding: '5px 10px', fontSize: 11 },
                  disabled: busy, onClick: () => doCancel(o.id)
                }, 'Cancel')
              )
        )
      ),
      isCountering && h('div', { className: 'offer-counter-form' },
        h('input', {
          className: 'price-input',
          type: 'number', step: '0.01', min: '0.01',
          inputMode: 'decimal', enterKeyHint: 'send',
          'aria-label': isIncoming ? 'Counter price' : 'Raise offer to',
          placeholder: isIncoming ? 'Counter price' : 'Raise to',
          value: counterAmt,
          onChange: e => setCounterAmt(e.target.value)
        }),
        // Quick-price chips (batch 372) — one-click common counter
        // amounts so the seller doesn't have to type. Halfway between
        // offer and ask is the typical middle-ground counter; +10% is
        // the "meet me slightly above" nudge; asking is the "decline
        // politely" signal.
        isIncoming && (() => {
          const offer = parseFloat(o.amount) || 0;
          const ask = parseFloat(o.askingPrice) || 0;
          if (offer <= 0 || ask <= 0 || ask <= offer) return null;
          const midway = +(((offer + ask) / 2).toFixed(2));
          const plus10 = +((offer * 1.10).toFixed(2));
          const justUnderAsk = +(ask * 0.95).toFixed(2);
          const chips = [
            { label: `${fmt(midway)} (midway)`,  v: midway },
            { label: `+10% (${fmt(plus10)})`,    v: plus10 },
            { label: `${fmt(justUnderAsk)} (−5% off ask)`, v: justUnderAsk }
          ];
          return h('div', { style: { display: 'flex', gap: 4, flexWrap: 'wrap', marginLeft: 4 } },
            chips.map(c => h('button', {
              key: c.label,
              type: 'button',
              className: 'price-suggest-chip',
              style: { fontSize: 10, padding: '3px 6px' },
              onClick: () => setCounterAmt(String(c.v)),
              title: 'Use this counter price'
            }, c.label))
          );
        })(),
        // Submit label changes by side: seller sends a counter, buyer
        // raises their own offer. Backend routes diverge but the inline
        // form shape is identical — reuse the same state + input.
        // Inline validation — both sides want amount strictly between
        // the buyer's offer and the seller's ask. Disabling here saves
        // a server round-trip + toast on the obvious "≥ ask" mistake.
        (() => {
          const amt = parseFloat(counterAmt);
          const offer = parseFloat(o.amount) || 0;
          const ask = parseFloat(o.askingPrice) || 0;
          const empty = !counterAmt || !Number.isFinite(amt) || amt <= 0;
          const tooLow  = !empty && ask > 0 && amt <= offer;
          const tooHigh = !empty && ask > 0 && amt >= ask;
          const bad = empty || tooLow || tooHigh;
          const tip = empty   ? 'Enter an amount above the offer and below the ask'
                    : tooLow  ? `Must be above the current ${isIncoming ? 'offer' : 'offer'} of ${fmt(offer)}`
                    : tooHigh ? `Must be below the asking price of ${fmt(ask)}`
                              : undefined;
          return isIncoming
            ? h('button', { className: 'buy-btn', disabled: busy || bad, onClick: () => doCounter(o.id), title: tip }, 'Send counter')
            : h('button', { className: 'buy-btn', disabled: busy || bad, onClick: () => doRaise(o.id),   title: tip }, 'Raise offer');
        })(),
        h('button', { className: 'btn btn-ghost', style: { padding: '6px 10px', fontSize: 11 }, onClick: () => setCounterFor(null), 'aria-label': 'Cancel counter' }, '✕')
      )
    );
  };

  const rawList = tab === 'incoming' ? data.incoming : data.outgoing;
  const outgoingPending = data.outgoing.filter(o => o.status === 'PENDING').length;
  // Batch 645 — apply the chosen sort client-side. PENDING rows bubble
  // to the top regardless of sort so actionable items always lead
  // (accept/reject on a 30-day-old REJECTED row is wasted energy).
  const sortFn = (() => {
    if (sortBy === 'oldest')   return (a, b) => (a.createdAt || 0) - (b.createdAt || 0);
    if (sortBy === 'amount')   return (a, b) => (parseFloat(b.amount) || 0) - (parseFloat(a.amount) || 0);
    if (sortBy === 'closest')  return (a, b) => {
      const aPct = parseFloat(a.askingPrice) > 0 ? parseFloat(a.amount) / parseFloat(a.askingPrice) : 0;
      const bPct = parseFloat(b.askingPrice) > 0 ? parseFloat(b.amount) / parseFloat(b.askingPrice) : 0;
      return bPct - aPct; // closest to ask (ratio ≈ 1.0) first
    };
    // Batch 859 — reputation sort. Highest buyer-trade-count first on
    // incoming (seller picks the most-proven offers); falls through to
    // amount desc as tiebreaker. Only meaningful on incoming where
    // buyerCompletedTrades is populated (batch 847) — outgoing rows
    // don't carry that field, so sort-by-rep on outgoing falls back to
    // amount.
    if (sortBy === 'reputation') return (a, b) => {
      const aRep = Number(a.buyerCompletedTrades) || 0;
      const bRep = Number(b.buyerCompletedTrades) || 0;
      if (bRep !== aRep) return bRep - aRep;
      return (parseFloat(b.amount) || 0) - (parseFloat(a.amount) || 0);
    };
    return (a, b) => (b.createdAt || 0) - (a.createdAt || 0); // newest
  })();
  const list = [...rawList].sort((a, b) => {
    // PENDING floats to the top
    const aPending = a.status === 'PENDING' ? 0 : 1;
    const bPending = b.status === 'PENDING' ? 0 : 1;
    if (aPending !== bPending) return aPending - bPending;
    return sortFn(a, b);
  });

  const cancelAllOutgoing = async () => {
    // The outgoing list mixes the buyer's own offers with seller counters
    // awaiting the buyer's reply — "cancel all" walks away from those
    // counters too, so the confirm must say so.
    const sellerCounters = data.outgoing.filter(o => o.status === 'PENDING' && o.author === 'SELLER').length;
    const msg = sellerCounters > 0
      ? `Cancel all ${outgoingPending} pending outgoing offers? This also declines ${sellerCounters} seller counter${sellerCounters === 1 ? '' : 's'} awaiting your reply. Sellers are notified.`
      : `Cancel all ${outgoingPending} pending outgoing offers? Sellers are notified.`;
    if (!confirm(msg)) return;
    setBusy(true);
    try {
      const { cancelAllOutgoingOffers } = await import('./api.js');
      const res = await cancelAllOutgoingOffers();
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not cancel offers', 'err'); return; }
      const n = (res && res.cancelled) || 0;
      toast(n === 0 ? 'No pending offers to cancel.' : `Cancelled ${n} offer${n === 1 ? '' : 's'}.`, 'ok');
      load();
    } finally { setBusy(false); }
  };

  return h('div', null,
    // Batch 937 — tablist semantics (see batch 936 for profile tabs).
    h('div', { className: 'offer-tabs', role: 'tablist', 'aria-label': 'Offer direction' },
      (() => {
        const OFFER_TABS = ['incoming', 'outgoing'];
        const onKey = (e) => {
          if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
          e.preventDefault();
          const idx = OFFER_TABS.indexOf(tab);
          let n = idx;
          if (e.key === 'ArrowRight') n = (idx + 1) % OFFER_TABS.length;
          else if (e.key === 'ArrowLeft') n = (idx - 1 + OFFER_TABS.length) % OFFER_TABS.length;
          else if (e.key === 'Home') n = 0;
          else if (e.key === 'End') n = OFFER_TABS.length - 1;
          setTab(OFFER_TABS[n]);
        };
        return [
          h('button', {
            key: 'incoming',
            id: 'profile-offers-tab-incoming',
            className: `offer-tab ${tab === 'incoming' ? 'active' : ''}`,
            role: 'tab',
            'aria-selected': tab === 'incoming',
            'aria-controls': 'profile-offers-panel-incoming',
            tabIndex: tab === 'incoming' ? 0 : -1,
            onClick: () => setTab('incoming'),
            onKeyDown: onKey
          }, 'Incoming', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, data.incoming.filter(o => o.status === 'PENDING').length)),
          h('button', {
            key: 'outgoing',
            id: 'profile-offers-tab-outgoing',
            className: `offer-tab ${tab === 'outgoing' ? 'active' : ''}`,
            role: 'tab',
            'aria-selected': tab === 'outgoing',
            'aria-controls': 'profile-offers-panel-outgoing',
            tabIndex: tab === 'outgoing' ? 0 : -1,
            onClick: () => setTab('outgoing'),
            onKeyDown: onKey
          }, 'Outgoing', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, outgoingPending))
        ];
      })(),
      h('div', { style: { flex: 1 } }),
      // Batch 645 — sort dropdown. Only rendered when there's enough
      // volume for the sort to matter (>1 row) so a user with a single
      // offer doesn't see noise.
      rawList.length > 1 && h('select', {
        className: 'sort-select',
        style: { fontSize: 12, padding: '4px 8px' },
        value: sortBy,
        onChange: e => setSort(e.target.value),
        'aria-label': 'Sort offers',
        title: 'Sort the offers list. PENDING rows always float to the top regardless of sort.'
      },
        h('option', { value: 'newest'  }, 'Newest'),
        h('option', { value: 'oldest'  }, 'Oldest'),
        h('option', { value: 'amount'  }, 'Highest amount'),
        h('option', { value: 'closest' }, 'Closest to ask'),
        // Reputation sort (batch 859) only useful on the incoming tab
        // where buyerCompletedTrades is populated per-row.
        tab === 'incoming' && h('option', { value: 'reputation' }, 'Buyer reputation')
      ),
      tab === 'outgoing' && outgoingPending > 1 && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11, color: 'var(--red)' },
        disabled: busy,
        onClick: cancelAllOutgoing,
        title: `Cancel all ${outgoingPending} pending outgoing offers in one click`
      }, '✕ Cancel all pending'),
      // Date-range filter — pass-through to the CSV `from` / `to`
      // params so a quarterly export matches the user's accounting
      // window without spreadsheet post-processing.
      (data.incoming.length + data.outgoing.length) > 0 && h(DateRangeFilter, {
        from: offersDateFrom,
        to:   offersDateTo,
        onChange: ({ from, to }) => { setOffersDateFrom(from); setOffersDateTo(to); }
      }),
      // Batch 694 — CSV export. Only shown when there's actual history
      // worth exporting (otherwise the chip is noise on a fresh account).
      // Honours the active tab via `?role=` so a user on the Outgoing
      // tab who clicks ⇣ CSV gets just their outgoing offers, not the
      // merged incoming+outgoing set. Same UX-parity logic as the
      // wallet/buy-orders CSV filters.
      (data.incoming.length + data.outgoing.length) > 0 && (() => {
        const base = '/api/profile/offers.csv';
        const role = tab === 'incoming' ? 'SELLER' : tab === 'outgoing' ? 'BUYER' : null;
        const withRole = role ? `${base}?role=${role}` : base;
        return h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
          href: appendDateRange(withRole, offersDateFrom, offersDateTo),
          title: role
            ? `Download ${tab} offer history as CSV — honours the date filter when set.`
            : 'Download incoming + outgoing offer history as CSV — honours the date filter when set.'
        }, '⇣ CSV');
      })()
    ),
    // Batch 936 — tabpanel wrapper so each tab button has a matching
    // role="tabpanel" target (capital summary + offer list per direction).
    h('div', {
      role: 'tabpanel',
      id: tab === 'outgoing' ? 'profile-offers-panel-outgoing' : 'profile-offers-panel-incoming',
      'aria-labelledby': tab === 'outgoing' ? 'profile-offers-tab-outgoing' : 'profile-offers-tab-incoming'
    },
    // Batch 855 — pending-offer capital summary, parallel to Buy Orders
    // + Active Bids exposure strips. Offers don't pre-lock funds
    // (PurchaseService.buy fires on accept, not on creation), so
    // "outgoing pending" is a potential outlay — what the wallet owes
    // IF every seller accepts. Conversely "incoming pending" is
    // potential revenue for the seller.
    (() => {
      const isIncoming = tab === 'incoming';
      const pending = (isIncoming ? data.incoming : data.outgoing).filter(o => o.status === 'PENDING');
      if (pending.length === 0) return null;
      const sum = pending.reduce((acc, o) => acc + (parseFloat(o.amount) || 0), 0);
      return h('div', {
        role: 'region',
        'aria-label': isIncoming ? 'Incoming offers revenue summary' : 'Outgoing offers capital summary',
        style: {
          display: 'flex', gap: 12, alignItems: 'center',
          padding: '8px 12px', marginBottom: 10,
          background: 'var(--bg-elevated, rgba(255,255,255,0.02))',
          border: '1px solid var(--border)', borderRadius: 6,
          fontSize: 12, color: 'var(--text-muted)'
        }
      },
        isIncoming
          ? h('span', {
              title: 'Sum of pending-offer amounts across your incoming list. This is the gross revenue you\'d collect (before 2% platform fee) if you accepted every pending offer right now.'
            },
              '—', h('strong', { style: { color: 'var(--accent)' } }, fmt(sum)),
              ' in pending offers across ', pending.length, ' listing',
              pending.length === 1 ? '' : 's'
            )
          : h('span', {
              title: 'Sum of your pending outgoing offers. Offers don\'t pre-lock wallet funds — the balance is debited only on seller accept, so this is the potential outlay if every seller accepts.'
            },
              '—', h('strong', { style: { color: 'var(--accent)' } }, fmt(sum)),
              ' potential outlay across ', pending.length, ' pending offer',
              pending.length === 1 ? '' : 's'
            )
      );
    })(),
    list.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            tab === 'incoming' ? 'No incoming offers yet' : 'No offers out'),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
            tab === 'incoming'
              ? 'Any time a buyer bargains on your listings, the offer shows up here with accept / counter / reject controls.'
              : 'Use "Make Offer" from any item detail to bargain with a seller. The seller has 7 days to respond before the offer auto-expires.'),
          h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
            tab === 'incoming'
              ? h('a', { className: 'btn btn-accent', href: '/me/stall' }, 'Open My Stall →')
              : h('a', { className: 'btn btn-accent', href: '/market' }, 'Browse marketplace →')
          ))
      : h('div', { className: 'offer-list' }, list.map(o => row(o, tab === 'incoming')))
    )
  );
}

// ── Profile "Reviews received" — surfaces the same rows buyers see on
// the public stall page, but inside the seller's private profile + with
// a star-rating filter and an inline reply editor per row. Re-uses the
// `replyToReview` API from the stall view so the reply state is
// single-sourced.
function ProfileReviewsTab({ me }) {
  // mode: 'received' (default — reviews about me) | 'given' (reviews I wrote)
  //       | 'pending' (trades I haven't reviewed yet — batch 337)
  const [mode, setMode]       = useState('received');
  const [received, setReceived] = useState(null);
  const [given, setGiven]     = useState(null);
  // Distinguish "you have no reviews" from "the fetch failed" — a 500/offline used
  // to render "No reviews yet", misrepresenting the user's reputation as empty.
  const [reviewsErr, setReviewsErr] = useState(false);
  const [pending, setPending] = useState(null);
  const [summary, setSummary] = useState(null);
  const [starFilter, setStarFilter] = useState(0);
  const [editId, setEditId]   = useState(null);
  const [draft, setDraft]     = useState('');
  const [busy, setBusy]       = useState(false);
  // Batch 750 — Given-tab inline edit state. Separate from the
  // Received-tab reply-edit fields so a user mid-edit on one side
  // doesn't lose their draft when switching tabs.
  const [editGivenId, setEditGivenId] = useState(null);
  const [editGivenStars, setEditGivenStars] = useState(5);
  const [editGivenComment, setEditGivenComment] = useState('');
  const [editGivenBusy, setEditGivenBusy] = useState(false);
  // Sync re-entrancy latch — the old `if (editGivenBusy) return` read async state,
  // so a same-tick double-click slipped through and PATCHed the review twice.
  const editGivenBusyRef = useRef(editGivenBusy); editGivenBusyRef.current = editGivenBusy;
  const loadReceived = useCallback(async () => {
    if (!me) return;
    setReviewsErr(false);
    try {
      // Raw fetch (not fetchReviewsForUser, which swallows non-2xx into []) so a
      // genuine server error surfaces the Retry card instead of a false "No reviews
      // yet". fetchReviewSummary already degrades gracefully, so it can't mask the err.
      const [rRes, sum] = await Promise.all([
        fetch(`/api/reviews/user/${me.id}`, { credentials: 'same-origin' }),
        fetchReviewSummary(me.id)
      ]);
      if (!rRes.ok && rRes.status !== 404) throw new Error('HTTP ' + rRes.status);
      const list = rRes.ok ? await rRes.json() : [];
      setReceived(Array.isArray(list) ? list : []);
      setSummary(sum || { count: 0, average: null });
    } catch (_) {
      setReviewsErr(true);
    }
  }, [me?.id]);
  const loadGiven = useCallback(async () => {
    if (!me) return;
    const list = await fetchMyAuthoredReviews();
    setGiven(Array.isArray(list) ? list : []);
  }, [me?.id]);
  const loadPending = useCallback(async () => {
    if (!me) return;
    const data = await fetchPendingReviews();
    setPending(Array.isArray(data?.items) ? data.items : []);
  }, [me?.id]);
  useEffect(() => { loadReceived(); }, [loadReceived]);
  // Pending is the one query we want the badge from, so load it on mount
  // regardless of active tab. Small payload (cap 50), one round-trip.
  useEffect(() => { loadPending(); }, [loadPending]);
  useEffect(() => { if (mode === 'given' && given === null) loadGiven(); }, [mode, given, loadGiven]);

  if (!me) return h(InfoModal, { title: 'Reviews' },
    h(SignInNeededEmptyState, { what: 'your reviews' }));

  // Pending is its own rendering path (no star filter, no edit/delete, each
  // row deep-links to the seller's stall for the review form). Handle it
  // before the shared filter/rows logic to keep the rest untouched.
  if (mode === 'pending') {
    if (pending === null) return h('div', { className: 'spinner' });
    return h('div', null,
      h('div', { style: { display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap' } },
        h('button', {
          className: `offer-tab ${mode === 'received' ? 'active' : ''}`,
          onClick: () => setMode('received')
        }, 'Received', received && received.length > 0 && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, received.length)),
        h('button', {
          className: `offer-tab ${mode === 'given' ? 'active' : ''}`,
          onClick: () => setMode('given')
        }, 'Given', given && given.length > 0 && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, given.length)),
        h('button', {
          className: `offer-tab active`,
          onClick: () => setMode('pending'),
          title: 'Trades you settled but never reviewed'
        }, 'Pending', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, pending.length))
      ),
      pending.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
              "You're all caught up — no trades waiting on a review."))
        : h('div', { className: 'profile-reviews-list' },
            pending.map(t => h('div', {
              key: t.tradeId,
              className: 'stall-review',
              style: { display: 'flex', alignItems: 'center', gap: 14, justifyContent: 'space-between', flexWrap: 'wrap' }
            },
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 } },
                  'Trade #' + t.tradeId + ' · ' + new Date(t.settledAt).toLocaleDateString()),
                h('div', { style: { fontSize: 14, color: 'var(--text-primary)', fontWeight: 600 } },
                  t.itemName || 'Item'),
                h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 } },
                  'Sold by ',
                  h('a', {
                    href: '/stall/' + t.sellerUserId,
                    style: { color: 'var(--accent)', textDecoration: 'none' }
                  }, t.sellerName || ('user #' + t.sellerUserId)),
                  t.price ? (' · ' + fmt(t.price)) : '')
              ),
              h('a', {
                className: 'buy-btn',
                // Deep-link ?leaveReview=<tradeId> so the stall page
                // auto-focuses the review form on THIS trade instead of
                // making the user scan the CTA list (batch 341).
                href: '/stall/' + t.sellerUserId + '?leaveReview=' + t.tradeId,
                style: { padding: '8px 14px', fontSize: 12, whiteSpace: 'nowrap' }
              }, 'Leave review →')
            ))
          )
    );
  }

  const rows = mode === 'received' ? received : given;
  if (mode === 'received' && reviewsErr) return h('div', { className: 'empty-inline', style: { padding: '24px 16px' } },
    h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, "Couldn't load your reviews"),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
      'Something went wrong fetching your reviews — your reputation is safe; check your connection and retry.'),
    h('button', { className: 'btn btn-accent', onClick: loadReceived }, 'Retry')
  );
  if (rows === null) return h('div', { className: 'spinner' });

  const filtered = starFilter > 0 ? rows.filter(r => r.rating === starFilter) : rows;
  const submit = async (reviewId, clear = false) => {
    setBusy(true);
    try {
      // Pre-fix: silent on success — seller replies to a review, hits
      // submit, the reply appears in-place but no toast confirms. Pair
      // with a matching error toast (already present) so the OK path
      // isn't the inconsistent outlier on this modal.
      const r = (received || []).find(x => x.id === reviewId);
      const res = await replyToReview(reviewId, clear ? '' : (draft || '').trim());
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Reply failed', 'err'); return; }
      setEditId(null); setDraft('');
      await loadReceived();
      const who = r?.fromDisplayName ? `@${r.fromDisplayName}` : 'reviewer';
      toast(clear
        ? `Reply removed from ${who}'s review.`
        : `Reply posted to ${who}'s review.`,
        'ok');
    } finally { setBusy(false); }
  };
  const deleteMine = async (reviewId) => {
    if (!confirm('Delete this review? This cannot be undone.')) return;
    // Batch 910 — find the review before the API call so the toast can
    // name the counterparty even after the row has been removed from
    // the `given` list.
    const r = (given || []).find(x => x.id === reviewId);
    setBusy(true);
    try {
      const res = await deleteReview(reviewId);
      if (res && (res.error || res.code)) { toast(res.message || res.error || 'Delete failed', 'err'); return; }
      await loadGiven();
      const who = r?.toUserName || r?.sellerName || null;
      toast(who ? `Review for @${who} deleted.` : 'Review deleted.', 'ok');
    } finally { setBusy(false); }
  };
  // Batch 750 — inline edit for a review the signed-in user authored.
  // Reuses /api/reviews POST (leaveReview) which the service-layer has
  // always treated as upsert-by-(fromUserId, tradeId). Saves stamp
  // editedAt server-side (V52) so the public stall surfaces a "· edited"
  // marker future buyers can see.
  const startEditGiven = (r) => {
    setEditGivenId(r.id);
    setEditGivenStars(r.rating || 5);
    setEditGivenComment(r.comment || '');
  };
  const cancelEditGiven = () => {
    setEditGivenId(null);
    setEditGivenStars(5);
    setEditGivenComment('');
  };
  const saveEditGiven = async (r) => {
    if (editGivenBusyRef.current) return;
    const stars = parseInt(editGivenStars, 10);
    if (!Number.isFinite(stars) || stars < 1 || stars > 5) {
      toast('Pick a rating between 1 and 5', 'err');
      return;
    }
    editGivenBusyRef.current = true;
    setEditGivenBusy(true);
    try {
      const { leaveReview } = await import('./api.js');
      const res = await leaveReview(r.tradeId, stars, (editGivenComment || '').trim());
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Save failed', 'err');
        return;
      }
      cancelEditGiven();
      await loadGiven();
      // Batch 910 — name the counterparty + render star glyphs in the
      // toast so the user sees which review they just edited. Matches
      // the review-post toast style (batch 890).
      const starGlyphs = '★'.repeat(stars) + '☆'.repeat(5 - stars);
      const who = r.toUserName || r.sellerName || null;
      toast(who
        ? `${starGlyphs} review for @${who} updated.`
        : `${starGlyphs} review updated.`,
        'ok');
    } finally { setEditGivenBusy(false); }
  };

  const avgLabel = summary && summary.count > 0
    ? `${Number(summary.average || 0).toFixed(1)} ★  ·  ${summary.count} review${summary.count === 1 ? '' : 's'}`
    : 'No reviews yet';

  // Per-star histogram — same shape RatingBreakdown in app.js renders
  // on the stall page. Inlined here to avoid modals.js → app.js imports.
  const buckets = Array.isArray(summary?.histogram) ? summary.histogram : [0,0,0,0,0];
  const maxBucket = Math.max(1, ...buckets);

  return h('div', null,
    // Received / Given / Pending sub-tabs. Given hides the histogram +
    // average — those aggregate over the signed-in user's own inbox, not
    // their feedback-given history. Pending is only offered when the user
    // has trades waiting for a review (batch 337) so users without
    // pending reviews don't see an extra dead tab.
    // WAI-ARIA tabs pattern + Arrow/Home/End keyboard nav, mirroring
    // /wallet, /loadout, /me/stall, /watchlist.
    (() => {
      const TABS = pending && pending.length > 0 ? ['received', 'given', 'pending'] : ['received', 'given'];
      const onKey = (e) => {
        if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
        e.preventDefault();
        const idx = TABS.indexOf(mode);
        let n = idx;
        if (e.key === 'ArrowRight') n = (idx + 1) % TABS.length;
        else if (e.key === 'ArrowLeft') n = (idx - 1 + TABS.length) % TABS.length;
        else if (e.key === 'Home') n = 0;
        else if (e.key === 'End') n = TABS.length - 1;
        setMode(TABS[n]);
      };
      const sharedProps = (id) => ({
        role: 'tab',
        'aria-selected': mode === id,
        tabIndex: mode === id ? 0 : -1,
        onKeyDown: onKey
      });
      return h('div', { style: { display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap' }, role: 'tablist', 'aria-label': 'Reviews filter' },
        h('button', Object.assign({
          className: `offer-tab ${mode === 'received' ? 'active' : ''}`,
          onClick: () => setMode('received')
        }, sharedProps('received')), 'Received', received && received.length > 0 && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, received.length)),
        h('button', Object.assign({
          className: `offer-tab ${mode === 'given' ? 'active' : ''}`,
          onClick: () => setMode('given')
        }, sharedProps('given')), 'Given', given && given.length > 0 && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, given.length)),
        pending && pending.length > 0 && h('button', Object.assign({
          className: `offer-tab ${mode === 'pending' ? 'active' : ''}`,
          onClick: () => setMode('pending'),
          title: 'Trades you settled but never reviewed',
          style: { position: 'relative' }
        }, sharedProps('pending')), 'Pending ', h('span', {
          className: 'filter-count',
          style: { marginLeft: 6, background: 'rgba(30,165,255,0.15)', color: 'var(--accent)', fontWeight: 700 }
        }, pending.length))
      );
    })(),
    mode === 'received' && h('div', { className: 'profile-reviews-head' },
      h('div', { className: 'profile-reviews-avg' }, avgLabel)
    ),
    mode === 'received' && (summary?.count || 0) >= 3 && h('div', { className: 'rating-breakdown', style: { marginBottom: 14 } },
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
        'aria-pressed': starFilter === n,
        onClick: () => setStarFilter(n)
      }, n === 0 ? 'All' : `${n}★`))
    ),
    filtered.length === 0
      ? h('div', { className: 'empty-inline' },
          /* Boss QA cycle 11 — replaced generic Material 'inbox' glyph with a
             reviews-themed star-in-quote SVG. Cycle 11 prompt: every empty-
             state should feel custom-illustrated, not just a stock icon. */
          h('div', { className: 'empty-icon empty-icon-lg', 'aria-hidden': true,
            style: {
              width: 64, height: 64, borderRadius: 16, margin: '0 auto 12px',
              background: 'color-mix(in oklab, var(--accent) 8%, var(--bg-1))',
              border: '1px solid color-mix(in oklab, var(--accent) 18%, var(--line))',
              color: 'color-mix(in oklab, var(--accent) 85%, var(--ink-2))',
              display: 'grid', placeItems: 'center'
            } },
            h('svg', { width: 32, height: 32, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' },
              h('path', { d: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z' }),
              h('path', { d: 'M12 7.5l1.3 2.6 2.9.4-2.1 2 .5 2.9L12 14l-2.6 1.4.5-2.9-2.1-2 2.9-.4z', fill: 'currentColor', stroke: 'none' })
            )
          ),
          rows.length === 0 && mode === 'received' && h('div', null,
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              'No reviews yet'),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 4px', lineHeight: 1.55 } },
              'Every VERIFIED trade lets the buyer rate you 1–5 stars. Settle a few trades and the rating starts building here.')
          ),
          rows.length === 0 && mode === 'given' && h('div', null,
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              "No reviews given yet"),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 14px', lineHeight: 1.55 } },
              "After a trade settles, you can leave the seller feedback from their stall page. The Pending tab lists trades waiting on a review."),
            // Batch 915 — inline CTA to the pending sub-tab so a user
            // who lands on "Given" by accident doesn't have to hunt
            // for the right place to write reviews.
            pending && pending.length > 0 && h('button', {
              className: 'btn btn-accent',
              style: { padding: '10px 18px', fontWeight: 700 },
              onClick: () => setMode('pending')
            }, `Check ${pending.length} pending review${pending.length === 1 ? '' : 's'} →`)
          ),
          rows.length > 0 && h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
            'No reviews match this filter.'))
      : h('div', { className: 'profile-reviews-list' },
          filtered.map(r => h('div', { key: r.id, className: 'stall-review' },
            h('div', { className: 'stall-review-head' },
              h('span', { className: 'stall-review-stars' }, '★'.repeat(r.rating) + '☆'.repeat(5 - r.rating)),
              h('span', { className: 'stall-review-from' },
                // Received rows: who wrote it. Given rows: who it's about,
                // linked to their stall.
                mode === 'received'
                  ? (r.fromDisplayName || 'Anonymous')
                  : h('a', {
                      href: '/stall/' + r.toUserId,
                      style: { color: 'inherit', textDecoration: 'none' },
                      title: "View this seller's stall"
                    }, '→ about seller #' + r.toUserId)
              ),
              // Verified buyer chip — every review is trade-gated.
              h('span', {
                style: {
                  fontSize: 9, fontWeight: 800, padding: '2px 6px', borderRadius: 4,
                  background: 'rgba(34,197,94,0.12)', color: '#22c55e',
                  border: '1px solid rgba(34,197,94,0.35)', letterSpacing: 0.3
                },
                title: 'Tied to a completed trade — not a drive-by rating.'
              }, 'Verified buyer'),
              h('span', { className: 'stall-review-time' },
                new Date(r.createdAt).toLocaleDateString(),
                // Batch 750 — mirror of the public stall "· edited"
                // marker. Lets the author see which of their reviews
                // have been rewritten, not just the future buyers.
                r.editedAt && h('span', {
                  style: { marginLeft: 6, fontSize: 10, fontStyle: 'italic', color: 'var(--text-muted)' },
                  title: 'Last edited ' + new Date(r.editedAt).toLocaleString()
                }, '· edited')
              )
            ),
            r.itemName && h('div', { className: 'stall-review-item' }, '↳ ' + r.itemName),
            // Batch 750 — inline edit UI for Given reviews. When the
            // row is being edited, replace the star bar + comment with
            // a picker + textarea. Otherwise show them normally.
            mode === 'given' && editGivenId === r.id
              ? h('div', { style: { marginTop: 6 } },
                  h('div', { className: 'stall-review-stars-picker' },
                    [1,2,3,4,5].map(n => h('button', {
                      key: n,
                      type: 'button',
                      className: 'star-btn' + (editGivenStars >= n ? ' on' : ''),
                      onClick: () => setEditGivenStars(n),
                      title: n + '★'
                    }, editGivenStars >= n ? '★' : '☆'))
                  ),
                  h('textarea', {
                    value: editGivenComment,
                    onChange: e => setEditGivenComment(e.target.value),
                    maxLength: 500,
                    'aria-label': 'Edit your review comment',
                    placeholder: 'Share context so future buyers can judge this seller (optional, 500 chars).',
                    style: { width: '100%', marginTop: 8, minHeight: 80, fontSize: 12 }
                  }),
                  h('div', {
                    style: { fontSize: 10, color: 'var(--text-muted)', textAlign: 'right', marginTop: 2 }
                  }, (editGivenComment || '').length, ' / 500'),
                  h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 } },
                    h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid var(--border)' },
                      disabled: editGivenBusy,
                      onClick: cancelEditGiven
                    }, 'Cancel'),
                    h('button', {
                      className: 'btn btn-accent',
                      disabled: editGivenBusy,
                      onClick: () => saveEditGiven(r)
                    }, editGivenBusy ? 'Saving…' : 'Save changes')
                  )
                )
              : r.comment && h('div', { className: 'stall-review-body' }, r.comment),
            r.sellerReply && editId !== r.id && h('div', { className: 'stall-review-reply' },
              h('span', { className: 'stall-review-reply-label' },
                mode === 'received' ? 'Your response' : "Seller's response"),
              h('div', { className: 'stall-review-reply-body' }, r.sellerReply)
            ),
            mode === 'received' && editId === r.id
              ? h('div', { className: 'stall-review-reply-edit' },
                  h('textarea', {
                    value: draft,
                    onChange: e => setDraft(e.target.value),
                    maxLength: 300,
                    'aria-label': 'Seller reply to review',
                    placeholder: 'Public response (300 chars)',
                    autoFocus: true
                  }),
                  h('div', {
                    style: { fontSize: 10, color: 'var(--text-muted)', textAlign: 'right', marginTop: 2 }
                  }, (draft || '').length, ' / 300'),
                  h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 } },
                    h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: busy, onClick: () => { setEditId(null); setDraft(''); } }, 'Cancel'),
                    h('button', { className: 'btn btn-accent', disabled: busy || !draft.trim(), onClick: () => submit(r.id, false) }, busy ? 'Saving…' : 'Post reply')
                  )
                )
              : mode === 'received'
                ? h('div', { style: { marginTop: 8, display: 'flex', gap: 8 } },
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
                : mode === 'given' && editGivenId !== r.id && h('div', { style: { marginTop: 8, display: 'flex', gap: 8 } },
                    h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
                      disabled: busy,
                      onClick: () => startEditGiven(r),
                      title: 'Edit your rating or comment — the stall page will show "· edited" next to the original date.'
                    }, '✎ Edit'),
                    h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
                      disabled: busy,
                      onClick: () => deleteMine(r.id),
                      title: 'Permanently delete your review'
                    }, busy ? 'Deleting…' : 'Delete')
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
  // Batch 1078 — synchronous re-entrancy latch shared by submitCreate +
  // submitReply. async setBusy alone can't stop a same-frame double-
  // click (both handlers capture busy===false from the same render).
  // Neither submit has a confirm() or parent latch, and
  // SupportService.create has no per-category dedupe, so a double-clicked
  // "Open ticket" filed TWO tickets + doubled the admin/CSR bell fan-out;
  // a double-clicked reply double-posted the message. Synced to busy each
  // render; auto-resets after the finally's setBusy(false) re-renders.
  const busyRef = useRef(busy); busyRef.current = busy;
  // Status filter — mirrors the backend's ticket lifecycle. 'ALL' = no
  // filter; default is 'OPEN' which excludes RESOLVED tickets so the
  // active queue is front and centre.
  const [statusFilter, setStatusFilter] = useState('OPEN');

  const load = useCallback(async () => { setTickets(await fetchSupportTickets()); }, []);
  useEffect(() => { load(); }, [load]);

  // Batch 803 — deep-link pre-fill for "Request a cap raise" links on
  // the Wallet banners. `/support?topic=cap-raise&kind=deposit&used=X&cap=Y`
  // opens the new-ticket form already filled with a PAYMENT-category
  // draft. Also handles the generic `topic=refund` shape that legacy
  // links use. Strips the query string after reading so a refresh
  // doesn't keep re-opening the form.
  useEffect(() => {
    try {
      const qs = new URLSearchParams(window.location.search);
      const topic = qs.get('topic');
      if (!topic) return;
      let draft = null;
      if (topic === 'cap-raise') {
        const kind = qs.get('kind') === 'withdraw' ? 'withdrawal' : 'deposit';
        const used = qs.get('used');
        const cap  = qs.get('cap');
        const context = (used && cap)
          ? `\n\nContext: I've ${kind === 'deposit' ? 'deposited' : 'withdrawn'} $${used} of my $${cap} daily ${kind} cap.\n`
          : '';
        draft = {
          subject: `Request a ${kind} cap raise`,
          category: 'PAYMENT',
          body:
            `I'd like to request a temporary or permanent raise on my daily ${kind} cap.${context}` +
            `\nReason for the raise:\n\n\n` +
            `How much do I need (in USD)?\n\n\n` +
            `By when?\n`
        };
      } else if (topic === 'refund') {
        draft = { subject: 'Refund request', category: 'PAYMENT', body: '' };
      } else if (topic === 'trade') {
        draft = { subject: 'Trade issue', category: 'TRADE',   body: '' };
      } else if (topic === 'bug') {
        draft = {
          subject: 'Bug report',
          category: 'OTHER',
          body:
            'What happened?\n\n\n' +
            'What did you expect to happen?\n\n\n' +
            'Steps to reproduce (page URL, clicks, inputs)?\n\n\n' +
            `Browser / OS: ${navigator.userAgent || 'unknown'}\n`
        };
      }
      if (draft) {
        setForm(draft);
        setCreating(true);
      }
      // Clean the URL so a refresh / back-nav doesn't keep firing.
      if (qs.get('topic')) {
        qs.delete('topic'); qs.delete('kind'); qs.delete('used'); qs.delete('cap');
        const next = qs.toString();
        window.history.replaceState({}, '', window.location.pathname + (next ? '?' + next : ''));
      }
    } catch (_) { /* no URLSearchParams or no search — silent */ }
  }, []);

  const openTicket = async (id) => {
    setViewing(await fetchSupportTicket(id));
  };

  const submitCreate = async () => {
    if (!form.subject.trim() || !form.body.trim()) return;
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await createSupportTicket(form);
      if (res && res.error) { toast(res.error, 'err'); return; }
      setCreating(false);
      const ticketId = res?.id;
      setForm({ subject: '', category: 'OTHER', body: '' });
      load();
      // Batch 897 — name the ticket + cite the category's SLO so the
      // user knows when to expect a response. Trade + Payment are
      // high-priority (≤4h) per /legal/trade-safety.html; the rest
      // fall under the ≤24h default.
      const sloHours = ['TRADE', 'PAYMENT', 'REFUND'].includes(form.category) ? 4 : 24;
      toast(
        `Ticket ${ticketId ? '#' + ticketId : ''} opened — staff will respond within ${sloHours} hours.`,
        'ok'
      );
    } finally { setBusy(false); }
  };

  const submitReply = async () => {
    if (!reply.trim() || !viewing?.ticket) return;
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await replySupportTicket(viewing.ticket.id, reply);
      if (res && res.error) { toast(res.error, 'err'); return; }
      setReply('');
      setViewing(await fetchSupportTicket(viewing.ticket.id));
      // Batch 898 — subtle reply-sent toast so a user who sends a
      // reply and immediately scrolls up in the thread (long tickets
      // have 20+ messages) knows their reply landed. Also serves as
      // the error path's counterpart — without an OK-path signal,
      // users were second-guessing whether Enter actually submitted.
      toast('Reply sent. Staff will respond in-thread.', 'ok');
    } finally { setBusy(false); }
  };

  const resolve = async () => {
    if (!viewing?.ticket) return;
    // Batch 913 — capture id + subject BEFORE the resolve so the toast
    // names the ticket even after the tab reloads.
    const t = viewing.ticket;
    const res = await resolveSupportTicket(t.id);
    if (res && res.error) { toast(res.error, 'err'); return; }
    setViewing(await fetchSupportTicket(t.id));
    load();
    const subjStr = t.subject ? ` — "${t.subject.length > 40 ? t.subject.slice(0, 40) + '…' : t.subject}"` : '';
    toast(`Ticket #${t.id}${subjStr} resolved. Reopen any time from the thread.`, 'ok');
  };

  // Batch 858 — reopen a RESOLVED ticket so the user isn't forced to
  // open a brand-new ticket with no context when follow-up comes up.
  const reopen = async () => {
    if (!viewing?.ticket) return;
    setBusy(true);
    try {
      const { reopenSupportTicket } = await import('./api.js');
      const res = await reopenSupportTicket(viewing.ticket.id);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not reopen', 'err');
        return;
      }
      setViewing(await fetchSupportTicket(viewing.ticket.id));
      load();
      toast('Ticket reopened — staff will pick it back up.', 'ok');
    } finally { setBusy(false); }
  };

  if (viewing) {
    return h('div', { className: 'profile-panel' },
      h('div', { style: { display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 } },
        h('button', { className: 'btn btn-ghost', onClick: () => setViewing(null) }, '← Tickets'),
        h('div', { style: { flex: 1 } },
          h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, '#' + viewing.ticket.id + ' · ' + viewing.ticket.subject),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, viewing.ticket.category + ' · ' + viewing.ticket.status)
        ),
        viewing.ticket.status !== 'RESOLVED' && h('button', { className: 'btn btn-ghost', onClick: resolve, style: { border: '1px solid var(--border)' } }, 'Mark Resolved'),
        // Batch 858 — Reopen button on RESOLVED tickets. Keeps the
        // existing thread instead of forcing a fresh ticket; staff are
        // re-notified via the same fan-out pattern as user replies.
        viewing.ticket.status === 'RESOLVED' && h('button', {
          className: 'btn btn-ghost',
          onClick: reopen,
          disabled: busy,
          style: { border: '1px solid rgba(30,165,255,0.4)', color: 'var(--accent)' },
          title: 'Reopen this ticket so staff can pick it back up in the same thread instead of a new one.'
        }, busy ? 'Reopening…' : '↻ Reopen')
      ),
      h('div', { className: 'support-thread' },
        viewing.messages.map(m => h('div', { key: m.id, className: `support-msg ${m.author === 'STAFF' ? 'staff' : 'user'}` },
          h('div', { className: 'support-msg-head' },
            m.authorName,
            // Staff badge (batch 406). Makes it unmistakable which
            // messages are official replies vs the user's own posts —
            // a social-engineering defence (no one can fake a "staff
            // reply" by choosing a display name like "Support") and a
            // reassurance for the user waiting on a real answer.
            m.author === 'STAFF' && h('span', {
              style: {
                marginLeft: 6, fontSize: 9, fontWeight: 800,
                padding: '1px 6px', borderRadius: 4,
                background: 'rgba(30,165,255,0.18)',
                color: 'var(--accent)',
                letterSpacing: 0.4
              },
              title: 'Official reply from a SkinBox staff member'
            }, 'STAFF'),
            ' · ', timeAgo(m.createdAt)
          ),
          h('div', { className: 'support-msg-body' }, linkifyText(m.body, 'sup-' + m.id))
        ))
      ),
      viewing.ticket.status !== 'RESOLVED' && h('div', { style: { display: 'flex', gap: 8, marginTop: 14 } },
        h('input', { className: 'chat-input', style: { flex: 1 }, placeholder: 'Reply…', value: reply, onChange: e => setReply(e.target.value), onKeyDown: e => { if (e.key === 'Enter' && !busy && reply.trim()) submitReply(); } }),
        h('button', { className: 'btn btn-accent', disabled: busy || !reply.trim(), onClick: submitReply }, 'Send')
      )
    );
  }

  return h('div', { className: 'profile-panel' },
    h('button', { className: 'btn btn-accent', style: { marginBottom: 14 }, onClick: () => setCreating(c => !c) },
      creating ? 'Cancel' : '+ New Ticket'),
    creating && h('div', { className: 'buyorder-form' },
      // Batch 683 — SLA commitment banner. Sets user expectations up
      // front. Category-aware tilt matches the internal staff routing:
      // trade + payment disputes are triaged fastest because dollars
      // are in escrow; account + bug reports follow during business
      // hours.
      h('div', {
        style: {
          padding: '10px 14px', marginBottom: 14, fontSize: 11.5,
          background: 'rgba(30,165,255,0.08)',
          border: '1px solid rgba(30,165,255,0.35)',
          borderRadius: 8, color: 'var(--text-secondary)', lineHeight: 1.55
        }
      },
        h('div', { style: { color: 'var(--accent)', fontWeight: 700, marginBottom: 4 } },
          '⏱ Response times'),
        h('div', null,
          ['TRADE','PAYMENT','REFUND'].includes(form.category)
            ? 'Trade, payment, and refund tickets are triaged first — staff typically responds within 4 hours (≤ 24h worst case). Include transaction IDs in the message to speed up review.'
            : 'Staff typically responds within 24 hours on business days. Trade, payment, and refund tickets jump the queue automatically. Include any relevant IDs, screenshots, or Steam offer URLs.')
      ),
      h('div', { className: 'wallet-input-label' }, 'Subject'),
      // a11y audit — visible label exists but wasn't programmatically tied
      // to the input. Screen-reader users heard "edit, blank" instead of
      // "Ticket subject, edit". Same fix for the Message textarea below.
      h('input', { className: 'wallet-amount-input', 'aria-label': 'Ticket subject', value: form.subject, onChange: e => setForm({ ...form, subject: e.target.value }), placeholder: 'Short subject line…' }),
      h('div', { className: 'wallet-input-label' }, 'Category'),
      h('select', { className: 'sort-select', 'aria-label': 'Ticket category', value: form.category, onChange: e => setForm({ ...form, category: e.target.value }) },
        ['TRADE','PAYMENT','REFUND','ACCOUNT','BUG','OTHER'].map(c => h('option', { key: c, value: c }, c))
      ),
      h('div', { className: 'wallet-input-label' }, 'Message'),
      h('textarea', { className: 'wallet-amount-input', 'aria-label': 'Ticket message body', style: { minHeight: 100, fontFamily: 'inherit' }, value: form.body, onChange: e => setForm({ ...form, body: e.target.value }), placeholder: 'Describe your issue…' }),
      h('button', { className: 'btn btn-accent wallet-submit', disabled: busy, onClick: submitCreate }, busy ? 'Submitting…' : 'Submit Ticket')
    ),
    (() => {
      if (tickets === null) return h('div', { className: 'spinner' });
      if (tickets.length === 0) return h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
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
            'aria-pressed': statusFilter === opt.id,
            onClick: () => setStatusFilter(opt.id)
          }, `${opt.label} · ${counts[opt.id] || 0}`))
        ),
        filtered.length === 0
          ? h('div', { className: 'empty-inline' },
              h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } }, 'No tickets in this filter.'))
          : h('table', { className: 'db-table db-table--list' },
              h('thead', null, h('tr', null,
                h('th', null, 'ID'), h('th', null, 'Subject'), h('th', null, 'Category'), h('th', null, 'Status'), h('th', { className: 'right' }, 'Updated'))),
              h('tbody', null,
                filtered.map(t => h('tr', {
                  key: t.id, className: 'db-row',
                  onClick: () => openTicket(t.id),
                  role: 'button',
                  tabIndex: 0,
                  'aria-label': `Open ticket #${t.id} — ${t.subject} (${t.status})`,
                  onKeyDown: (e) => {
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openTicket(t.id); }
                  }
                },
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
  // Batch 676 — scope picker. Default RW to preserve the pre-scope UX
  // (existing docs tell bots to use keys for both read + write). Users
  // who want a safer key for a price-watcher bot pick RO.
  const [scope, setScope] = useState('RW');
  const [newKey, setNewKey] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => { setKeys(await fetchApiKeys()); }, []);
  useEffect(() => { load(); }, [load]);

  const mint = async () => {
    setBusy(true);
    try {
      const res = await createApiKey(label || 'Untitled', scope);
      if (res && res.error) { toast(res.error, 'err'); return; }
      setNewKey(res);
      setLabel('');
      setScope('RW');
      load();
    } finally { setBusy(false); }
  };

  const revoke = async (id) => {
    if (!confirm('Revoke this API key? Applications using it will stop working immediately.')) return;
    // Batch 911 — snapshot the row BEFORE the revoke so the toast can
    // name the key label. Useful for users with 3+ keys ("was it
    // my-bot or my-scraper I just revoked?").
    const k = (keys || []).find(x => x.id === id);
    const res = await revokeApiKey(id);
    if (res && res.error) { toast(res.error, 'err'); return; }
    load();
    toast(k?.label
      ? `API key "${k.label}" revoked — calls using it will 401.`
      : 'API key revoked — calls using it will 401.',
      'ok');
  };

  return h('div', { className: 'profile-panel' },
    h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 14, padding: 10, background: 'var(--accent-dim)', border: '1px solid var(--accent-border)', borderRadius: 8 } },
      'API keys authenticate third-party bots and browser extensions. They carry your account rights — never paste them into a public file or chat.',
      // Batch 708 — point developers at the FAQ entry that explains the
      // Bearer-auth flow + scope semantics. Deep-link via /help?q= so
      // the Help modal opens pre-filtered to the API entry.
      h('a', {
        href: '/help?q=API', style: { color: 'var(--accent)', marginLeft: 6 }
      }, 'How to use the API →')),
    h('div', { style: { display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap' } },
      h('input', { className: 'price-input', placeholder: 'Label (e.g. my-bot)', style: { flex: 1, minWidth: 180 }, value: label, onChange: e => setLabel(e.target.value) }),
      h('select', {
        className: 'price-input',
        'aria-label': 'API key scope',
        value: scope,
        onChange: e => setScope(e.target.value),
        title: 'RW = full read + write (buy, sell, transfer). RO = read-only — safer for price-watcher bots.',
        style: { width: 110 }
      },
        h('option', { value: 'RW' }, 'RW · full'),
        h('option', { value: 'RO' }, 'RO · read-only')
      ),
      h('button', { className: 'btn btn-accent', disabled: busy, onClick: mint }, busy ? 'Minting…' : '+ New Key')
    ),
    h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: -6, marginBottom: 14 } },
      scope === 'RO'
        ? 'Read-only keys can fetch listings, wallet balance, and trade history but can’t buy, sell, or transfer funds.'
        : 'Full-access keys can buy, sell, move funds, and cancel trades on your behalf. Only use for trusted bots you control.'
    ),
    newKey && h('div', { className: 'api-key-new' },
      h('div', { style: { fontSize: 11, color: 'var(--yellow)', fontWeight: 700, marginBottom: 6, letterSpacing: 0.4 } },
        'COPY THIS NOW — it will not be shown again'),
      h('div', { className: 'api-key-token' }, newKey.token),
      h('div', { style: { display: 'flex', gap: 8, marginTop: 10 } },
        h('button', {
          className: 'btn btn-accent',
          style: { padding: '6px 14px', fontSize: 11 },
          onClick: async () => {
            try {
              if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(newKey.token);
                toast('API key copied — paste into your bot / extension config now.', 'ok');
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
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'No API keys yet.'))
        : h('div', null,
            // Batch 705 — security panic button. Only rendered when
            // the user has at least 2 active keys — revoking a single
            // key is already one click via the per-row Revoke button.
            (keys.filter(k => !k.revoked).length >= 2) && h('div', {
              style: { display: 'flex', justifyContent: 'flex-end', marginBottom: 10 }
            },
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.4)', color: 'var(--red)', padding: '5px 12px', fontSize: 11 },
                onClick: async () => {
                  const n = keys.filter(k => !k.revoked).length;
                  if (!confirm(`Revoke ALL ${n} active API keys?\n\nEvery third-party bot or extension using these will immediately stop working. Use this if you suspect any of them were compromised.`)) return;
                  const res = await revokeAllApiKeys();
                  if (res && (res.error || res.code)) { toast(res.message || res.error || 'Bulk revoke failed', 'err'); return; }
                  toast(`Revoked ${res.revoked || 0} API key${res.revoked === 1 ? '' : 's'}.`, 'ok');
                  load();
                },
                title: 'Security panic button — revokes every active API key on your account in one click.'
              }, 'Revoke all active keys')
            ),
            h('table', { className: 'db-table db-table--list' },
            h('thead', null, h('tr', null,
              h('th', null, 'Label'),
              h('th', null, 'Prefix'),
              h('th', null, 'Scope'),
              h('th', null, 'Created'),
              h('th', null, 'Last Used'),
              h('th', { className: 'right' }, 'Action'))),
            h('tbody', null, keys.map(k => h('tr', { key: k.id, className: 'db-row' },
              h('td', null, k.label || '—'),
              h('td', { className: 'db-mono' }, k.publicPrefix + '…'),
              h('td', null, h('span', {
                title: k.scope === 'RO' ? 'Read-only — cannot buy, sell, or transfer funds' : 'Full access — can act on your account',
                style: {
                  fontSize: 10, fontWeight: 700, letterSpacing: 0.4,
                  padding: '2px 6px', borderRadius: 6,
                  background: k.scope === 'RO' ? 'rgba(34,197,94,0.15)' : 'rgba(250,204,21,0.15)',
                  color: k.scope === 'RO' ? 'var(--green, #22c55e)' : 'var(--yellow, #facc15)'
                }
              }, k.scope || 'RW')),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, new Date(k.createdAt).toLocaleDateString()),
              h('td', { style: { fontSize: 11, color: 'var(--text-muted)' } }, k.lastUsedAt ? timeAgo(k.lastUsedAt) : 'Never'),
              h('td', { className: 'right' },
                k.revoked
                  ? h('span', { style: { fontSize: 10, color: 'var(--red)', fontWeight: 700 } }, 'REVOKED')
                  : h('button', { className: 'btn btn-ghost', style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '5px 10px', fontSize: 11 }, onClick: () => revoke(k.id) }, 'Revoke')
              )
            )))
          )
        )  // close the batch-705 h('div', null, …) wrapper
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
  // Total inventory row count on the server — populated alongside the
  // fetched list so the modal can surface "Showing most recent 500 of N"
  // when the server cap truncates the payload. Null until the first
  // fetch resolves so the banner doesn't flash on initial render.
  const [internalTotal, setInternalTotal] = useState(null);
  const [source, setSource]       = useState('steam'); // steam | internal
  const [steamData, setSteamData] = useState(null);    // {items, count, lastSyncedAt}
  const [internal, setInternal]   = useState(null);
  // Platform-inventory fetch failure (vs genuinely empty) so the Sell modal's
  // internal tab shows a retry affordance instead of spinning forever / falsely
  // claiming "Platform inventory empty". (fetch-swallow bug class)
  const [internalErr, setInternalErr] = useState(false);
  const [picking, setPicking]     = useState(null);    // { kind: 'steam'|'internal', item: {...} }
  const [price, setPrice]         = useState('');
  const [busy, setBusy]           = useState(false);
  // Sync re-entrancy latch — submit() calls setBusy AFTER its await (list/relist),
  // so a rapid double-click lists the SAME item twice. Auto-resets via the
  // per-render busyRef sync after setBusy(false).
  const busyRef = useRef(busy); busyRef.current = busy;
  const [error, setError]         = useState('');
  const [syncing, setSyncing]     = useState(false);
  // Listing-type + auction duration. Default to BUY_NOW so the form shape
  // stays backwards-compatible; the user opts into AUCTION by flipping the
  // radio, which reveals the duration picker. Duration is in hours, 1-168
  // (7 days) per the server-side DTO cap.
  const [sellType, setSellType]               = useState('BUY_NOW');
  const [sellDurationHours, setSellDurationHours] = useState('24');
  // Optional Buy-Now ceiling on an auction (batch 371). When set, a
  // buyer can skip the auction at this price. Empty string = no Buy-Now.
  const [sellBuyNow, setSellBuyNow] = useState('');
  // Batch 646 — auto-accept threshold as integer percent (e.g. "15"
  // for "accept any offer >= 85% of ask"). Empty = no auto-accept.
  // Mirrors the editAutoPct state in MyStallModal.
  const [sellAutoPct, setSellAutoPct] = useState('');
  // Optional seller note — "quick sale", "mint never worn", etc. Sanitised
  // + 500-char capped server-side (batch 304). Surfaces on the public
  // ItemModal listings row so buyers can see it without DMing the seller.
  const [sellDescription, setSellDescription] = useState('');
  // Filter chips — narrow the Steam inventory view when the user has a
  // lot of items. Rarity filter + free-text name search. Pure client-
  // side; backend still returns the full set. Pre-fills from the URL's
  // `?q=` so the "List one of these" button on item detail (batch 271)
  // lands the seller on the right row without a manual search. Also
  // auto-flips the source tab to 'internal' when `?source=internal` is
  // present (the Platform Inventory tab is the natural destination for
  // a "List from your platform inventory" deep-link).
  const [sellRarityFilter, setSellRarityFilter] = useState('All');
  const [sellSearch, setSellSearch] = useState(() => {
    try {
      const qs = new URLSearchParams(window.location.search);
      return (qs.get('q') || '').slice(0, 80);
    } catch { return ''; }
  });
  // Same one-time URL read for the source tab so a /sell?source=internal
  // deep-link lands on the right inventory.
  useEffect(() => {
    try {
      const qs = new URLSearchParams(window.location.search);
      const src = qs.get('source');
      if (src === 'internal' || src === 'steam') setSource(src);
      // Strip the bridging params from the URL so a refresh doesn't
      // keep re-applying them against the user's will.
      if (qs.has('q') || qs.has('source')) {
        qs.delete('q'); qs.delete('source');
        const next = qs.toString();
        window.history.replaceState({}, '', window.location.pathname + (next ? '?' + next : ''));
      }
    } catch (_) {}
  }, []);
  // Recent-sales median for the currently-picked item. Sellers often
  // anchor on "what has this actually been going for?" — the floor chip
  // tells them what's for sale right now, but recent *closed* trades are
  // the better signal. Populated lazily when the user picks an item with
  // a known catalogue id (Steam items without a catalogue row yet can't
  // carry history). Null when the fetch hasn't resolved or the item has
  // no sales yet.
  const [pickedRecentMedian, setPickedRecentMedian] = useState(null);
  const [pickedRecentCount, setPickedRecentCount]   = useState(0);
  // Did the market answer when we asked it what this item is worth?
  //   null  — still asking (the form must not quote a price yet)
  //   false — it answered (possibly "nothing", which is a real answer)
  //   true  — we could not get an answer
  // Without this the third case was rendered as the second: a failed
  // probe hid the chips and the form printed a "Suggested price" built
  // from whatever it happened to have, with nothing on screen saying the
  // question had failed. Same defect family as the empty-inventory
  // reasons above, one screen further into the flow.
  const [priceProbeErr, setPriceProbeErr] = useState(false);
  // The catalogue id of the current pick, so the Retry button can re-ask
  // without re-opening the item.
  const [pickedItemId, setPickedItemId] = useState(null);
  // Competing-listings snapshot for the picked item (batch 360). Tells
  // the seller "N sellers already listing, floor $X" so they can price
  // competitively without manually browsing. Fetched once per pick via
  // /api/listings/item/{id} (public, cacheable, no auth cost).
  const [pickedCompeting, setPickedCompeting] = useState({ count: 0, floor: null });
  // Bulk-list state (batch 370). Set of selected assetIds. When > 0,
  // a floating action bar at the bottom of the modal lets the user
  // list all selected items at one shared price. Only BUY_NOW — auction
  // semantics on a batch are ambiguous (do all 8 auctions share one
  // expiry?) so we keep that flow single-item.
  const [bulkSelected, setBulkSelected] = useState(() => new Set());
  const [bulkPrice, setBulkPrice] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);
  // Batch 648 — shared auto-accept percent for the bulk-list flow,
  // applied uniformly to every asset in the batch. Empty = no auto-
  // accept. Mirrors the single-list `sellAutoPct` semantics so sellers
  // get consistent UX between the two flows.
  const [bulkAutoPct, setBulkAutoPct] = useState('');
  // Batch 551 — bulk buy-order demand map {itemId → {count, bestBid}} so
  // the inventory grid can surface "Top buy order · $Y" on each row.
  // Huge lift for sellers: they can see the liquidity they'd auto-fill
  // BEFORE they decide a price, instead of eyeballing and hoping.
  const [inventoryBuyOrderDemand, setInventoryBuyOrderDemand] = useState({});
  // Quick-Sell busy row key — assetId for Steam rows, `int:<listingId>` for
  // internal rows. Tracks the in-flight row so the button for the clicked
  // row shows a spinner while OTHER rows' quick-sell buttons stay clickable
  // (a slow network shouldn't freeze the whole grid).
  const [quickSellBusy, setQuickSellBusy] = useState(null);
  // Synchronous re-entrancy latch — quick-sell one-click LISTS an item at the
  // best bid (creates a real listing). `quickSellBusy` is async useState, so a
  // double-click could fire two listFromSteam/relistItem POSTs. Mirror the key
  // into a ref checked-and-set before the await. Synced each render so the
  // finally's setQuickSellBusy(null) re-render clears it.
  const quickSellBusyRef = useRef(null); quickSellBusyRef.current = quickSellBusy;
  const toggleBulk = (assetId) => {
    setBulkSelected(prev => {
      const next = new Set(prev);
      if (next.has(assetId)) next.delete(assetId); else next.add(assetId);
      return next;
    });
  };

  const loadSteam = useCallback(async () => {
    const d = await fetchSteamInventory();
    setSteamData(d);
    return !(d && d.error);  // success flag drives the poll's backoff
  }, []);
  const loadInternal = useCallback(async () => {
    const { items, total, error } = await fetchInventoryWithTotal();
    if (error) { setInternalErr(true); return false; }  // retry card, not a forever-spinner / false-empty
    setInternalErr(false);
    setInternal(items);
    setInternalTotal(total);
    return true;
  }, []);

  useEffect(() => {
    if (!me) return;
    loadSteam();
    loadInternal();
  }, [me, loadSteam, loadInternal]);

  // Visibility-aware 30s poll — refresh both inventory views in the
  // background while the Sell modal is open so floor / suggested
  // prices on each row stay current with the listing-floor sweep
  // (which runs every 60s server-side). Without this, a seller who
  // leaves the Pick screen open watches stale prices while the
  // backend keeps recomputing under them. Pauses on hidden tabs to
  // avoid burning bandwidth on backgrounded windows.
  useEffect(() => {
    if (!me) return;
    // Self-rescheduling poll with exponential backoff. A fixed 30s setInterval
    // hammered a failing backend every 30s forever (no error-awareness). Now a
    // failed tick doubles the delay (capped at 5min) and a success resets it to
    // 30s — so a partial outage isn't churned, and recovery snaps back to the
    // fast cadence. (self-review fix)
    let timer = null, stopped = false, delay = 30_000;
    const tick = async () => {
      if (stopped) return;
      let ok = true;
      if (document.visibilityState === 'visible') {
        const [okS, okI] = await Promise.all([loadSteam(), loadInternal()]);
        ok = okS && okI;
      }
      delay = ok ? 30_000 : Math.min(delay * 2, 300_000);
      if (!stopped) timer = setTimeout(tick, delay);
    };
    timer = setTimeout(tick, 30_000);
    const onVis = () => {
      if (document.visibilityState === 'visible' && !stopped) { clearTimeout(timer); delay = 30_000; tick(); }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [me, loadSteam, loadInternal]);

  // Batch 551 — bulk-fetch buy-order demand for every catalogued item in
  // the combined inventory view. Drops the result into
  // inventoryBuyOrderDemand keyed by itemId so the grid can render a
  // "🎯 Top buy order · $X" chip per row. Re-fires whenever the
  // underlying inventory lists change (e.g. after a resync pulls in
  // new Steam items, or listing one item flips it off the available
  // grid). Skips the call when there are no catalogued items to save
  // a round-trip on a brand-new account.
  useEffect(() => {
    if (!me) return;
    const steamIds   = (steamData?.items || []).map(si => si.catalogueId).filter(Boolean);
    const internalIds = (internal || []).map(l => l?.item?.id).filter(Boolean);
    const ids = Array.from(new Set([...steamIds, ...internalIds]));
    if (ids.length === 0) { setInventoryBuyOrderDemand({}); return; }
    let alive = true;
    (async () => {
      try {
        const r = await fetch(`/api/buy-orders/count/bulk?ids=${ids.join(',')}`, { credentials: 'same-origin' });
        if (!alive || !r.ok) return;
        const data = await r.json();
        if (data && typeof data === 'object') setInventoryBuyOrderDemand(data);
      } catch (_) { /* silent — chip just stays hidden */ }
    })();
    return () => { alive = false; };
  }, [me?.id,
      steamData?.items?.map(si => si.catalogueId).filter(Boolean).join(','),
      (internal || []).map(l => l?.item?.id).filter(Boolean).join(',')]);

  if (!me) return h(InfoModal, { title: 'Sell Items', onClose },
    h(SignInNeededEmptyState, { what: 'your Steam inventory and platform inventory' }));

  const resync = async () => {
    setSyncing(true);
    try {
      // Batch 920 — surface the sync result. Previously the click
      // flipped the button to "Syncing…" and back, and the only way to
      // tell if the sync actually worked was to count items in the grid.
      // Now the toast cites the refreshed count or surfaces an error.
      const res = await syncSteam();
      await loadSteam();
      if (res && res.ok === false) {
        toast(res.error || 'Steam sync failed — try again in a few minutes.', 'err');
        return;
      }
      const n = (res && typeof res.inventorySize === 'number') ? res.inventorySize : null;
      toast(n != null
        ? `Steam inventory synced — ${n} item${n === 1 ? '' : 's'} in your pool.`
        : 'Steam inventory synced.',
        'ok');
    } catch (_) {
      toast('Network error during Steam sync — try again in a few minutes.', 'err');
    } finally { setSyncing(false); }
  };

  // Fetch recent-sales median for the picked item. Internal items always
  // have a catalogue id; Steam items may not yet, so we only fire when
  // id is present. Median > average here because one outlier sale
  // shouldn't drag the recommendation around.
  //
  // ── A refused quote is not "no sales" ────────────────────────────────
  // Both price probes below used to end in `catch (_) { /* silent */ }`,
  // and the median one went through `fetchRecentSales`, which swallows a
  // non-200 into `[]`. So a 500, a dropped connection and an item that has
  // genuinely never sold all produced the SAME screen: no chip, and a
  // "Suggested price" line computed as if the market had answered. The
  // seller could not tell that we had failed to ask. `priceProbeErr`
  // splits those two states apart and gives him a retry.
  const loadRecentMedian = async (itemId) => {
    setPickedRecentMedian(null);
    setPickedRecentCount(0);
    if (!itemId) return true;
    try {
      // Direct fetch, not fetchRecentSales(): that helper returns [] for
      // both "no sales" and "the request failed", which is precisely the
      // distinction this state exists to preserve.
      const r = await fetch(`/api/items/${itemId}/recent-sales`, { credentials: 'same-origin' });
      if (!r.ok) return false;
      const rows = await r.json();
      if (!Array.isArray(rows)) return false;
      if (rows.length === 0) return true;   // asked, answered: no sales yet
      const prices = rows.map(x => parseFloat(x.price)).filter(n => Number.isFinite(n) && n > 0);
      if (prices.length === 0) return true;
      prices.sort((a, b) => a - b);
      const mid = Math.floor(prices.length / 2);
      const median = prices.length % 2 === 0
        ? (prices[mid - 1] + prices[mid]) / 2
        : prices[mid];
      setPickedRecentMedian(median);
      setPickedRecentCount(prices.length);
      return true;
    } catch (_) { return false; }
  };
  // Competing-listings probe (batch 360). Hits the public item-listings
  // endpoint, counts ACTIVE listings and projects the floor so the form
  // can render "N sellers already listing · floor $X". Self-listings are
  // EXCLUDED from the count — a seller doesn't compete with themselves.
  // Returns false when we could not get an answer (see loadRecentMedian).
  const loadCompeting = async (itemId) => {
    setPickedCompeting({ count: 0, floor: null });
    if (!itemId) return true;
    try {
      const r = await fetch(`/api/listings/item/${itemId}`, { credentials: 'same-origin' });
      if (!r.ok) return false;
      const rows = await r.json();
      if (!Array.isArray(rows)) return false;
      const mine = me?.id;
      const competing = rows.filter(l =>
        l && l.status === 'ACTIVE' && !l.hidden &&
        l.listingType === 'BUY_NOW' &&
        (mine == null || l.sellerUserId !== mine));
      if (competing.length === 0) return true;
      const floor = competing
        .map(l => parseFloat(l.price))
        .filter(n => Number.isFinite(n) && n > 0)
        .sort((a, b) => a - b)[0];
      setPickedCompeting({ count: competing.length, floor });
      return true;
    } catch (_) { return false; }
  };
  // Run both probes for one item and record whether the market answered.
  // `null` = still asking (so the form can say "checking…" instead of
  // quoting a price it has not got yet), false = answered, true = refused.
  const loadPriceProbes = async (itemId) => {
    setPriceProbeErr(null);
    if (!itemId) {
      // No catalogue row: there is nothing to ask ABOUT. That is a known
      // state, not a failure — an uncatalogued item has no market yet.
      setPriceProbeErr(false);
      return;
    }
    const [okMedian, okCompeting] = await Promise.all([
      loadRecentMedian(itemId), loadCompeting(itemId)
    ]);
    setPriceProbeErr(!(okMedian && okCompeting));
  };

  // Reset every form field that's NOT auto-seeded by start* below, so a
  // user who picks item A, configures it (auction 72h, $50 Buy-Now,
  // 10% auto-accept, "quick sale" note), hits Back, then picks item B
  // doesn't see stale options bleed across. Without this, `sellType`,
  // `sellDurationHours`, `sellBuyNow`, `sellAutoPct`, `sellDescription`
  // all persisted to the new pick — easy to miss-list with the wrong
  // options. `price` + `error` are still reseeded per-pick below.
  const resetSellFormFields = () => {
    setSellType('BUY_NOW');
    setSellDurationHours('24');
    setSellBuyNow('');
    setSellAutoPct('');
    setSellDescription('');
  };
  // ── Why a Steam row cannot be listed, if it cannot ───────────────────
  // Returns 'ALREADY_LISTED', 'NOT_TRADABLE' or null. The server now sends
  // `unlistableReason` (and `listableQuantity`) per row; older payloads —
  // and any cached response from before that deploy — carry neither, so
  // fall back to the tradable flag, which is all this UI ever had.
  //
  // The point is to answer BEFORE the seller fills in a price. Both of
  // these were previously discovered only by submitting the form and
  // reading a red server error, and the ALREADY_LISTED one was not even
  // true of the item he had selected — it was true of the one copy the
  // payload happened to nominate.
  const steamRowBlock = (si) => {
    if (si?.unlistableReason === 'ALREADY_LISTED') return 'ALREADY_LISTED';
    if (si?.unlistableReason === 'NOT_TRADABLE')   return 'NOT_TRADABLE';
    if (si?.unlistableReason === null && si?.listableQuantity != null) return null;
    return si?.tradable ? null : 'NOT_TRADABLE';
  };
  // ── One answer to "what is this worth, and how do we know?" ──────────
  // Returns the anchor AND its basis, because a number with no basis is
  // what produced "Suggested price: $0.00" on every item that has no live
  // listing. `basis: null` means we have no reference at all — the caller
  // must say so in words rather than print a zero.
  //
  // Order is deliberate: the live floor is what a buyer can pay right now,
  // the median of real sales is what the market has actually been paying,
  // and the Steam Market reference is the last resort because it is
  // another venue's price, not ours.
  const priceAnchorFor = (item, isSteam, median) => {
    const floor = parseFloat(isSteam ? item?.suggestedPrice : item?.lowestPrice);
    if (Number.isFinite(floor) && floor > 0) return { value: floor, basis: 'floor' };
    if (median != null && Number.isFinite(median) && median > 0) return { value: median, basis: 'median' };
    const steam = parseFloat(item?.steamPrice);
    if (Number.isFinite(steam) && steam > 0) return { value: steam, basis: 'steam' };
    return { value: 0, basis: null };
  };
  const startPickSteam = (si) => {
    resetSellFormFields();
    setPicking({ kind: 'steam', item: si });
    // Seed the field from a price we actually HAVE. The old line was
    // `setPrice(parseFloat(si.suggestedPrice || 0).toFixed(2))`, which
    // typed "0.00" into the asking-price box for every item with no live
    // listing — a number the seller did not choose, in the one field that
    // decides what he is paid, and one the server then rejects with
    // "Price must be at least $0.01" if he trusts it. An empty box with a
    // placeholder says what is true: we do not have a price for this yet.
    const seed = priceAnchorFor(si, true);
    setPrice(seed.value > 0 ? seed.value.toFixed(2) : '');
    setError('');
    // The catalogue item id on a Steam inventory row is `catalogueId`
    // (the same field the bulk demand-lookup + every grid chip reads).
    // Was `si.itemId || si.id` — neither exists on a Steam row (`id` is
    // the Steam *asset* id), so the recent-sales-median + competing-
    // listings probes silently fired with undefined and their chips
    // never rendered for catalogued Steam items.
    const itemId = si?.catalogueId;
    setPickedItemId(itemId ?? null);
    loadPriceProbes(itemId);
  };
  const startPickInternal = (l) => {
    resetSellFormFields();
    setPicking({ kind: 'internal', item: l.item, listingId: l.id });
    // Floor may be null for an item with no other live listings (first
    // seller back on a freshly-spawned skin). parseFloat(null) is NaN
    // and `.toFixed(2)` then yields the literal string "NaN" — which
    // leaked into the price input's `value` AND into the placeholder, so
    // the seller saw a "NaN" field. It then became "0.00", which is not
    // NaN but is still a price nobody quoted; now an unknown price seeds
    // an EMPTY field and says so above it.
    const seed = priceAnchorFor(l?.item, false);
    setPrice(seed.value > 0 ? seed.value.toFixed(2) : '');
    setError('');
    setPickedItemId(l?.item?.id ?? null);
    loadPriceProbes(l?.item?.id);
  };

  // Quick-Sell — one-click list-at-best-bid. Skips the full Pick + Form
  // flow entirely: fires listFromSteam / relistItem at the standing buy
  // order's bestBid, which BuyOrderService.tryMatch then auto-fills on
  // the next sweep. Huge UX win for sellers sitting on liquid items —
  // three clicks (row → chip → List) collapse to one.
  const quickSell = async (kind, row, bestBid) => {
    const key = kind === 'steam' ? `steam:${row.assetId}` : `int:${row.id}`;
    if (quickSellBusyRef.current) return;
    if (!(Number.isFinite(bestBid) && bestBid > 0)) return;
    const price = Number(bestBid.toFixed(2));
    quickSellBusyRef.current = key;
    setQuickSellBusy(key);
    try {
      const res = kind === 'steam'
        ? await listFromSteam(row.assetId, price, { listingType: 'BUY_NOW' })
        : await relistItem(row.id,       price, { listingType: 'BUY_NOW' });
      if (res && (res.code || res.error)) {
        toast(res.message || res.error || 'Could not quick-sell', 'err');
        return;
      }
      // Batch 885 — name the quick-sold item so sellers burst-selling
      // across their inventory can confirm the correct row fired.
      const itemName = row?.name || row?.item?.name;
      toast(
        itemName
          ? `Listed "${itemName}" at ${fmt(price)} — auto-match on next sweep.`
          : `Listed at ${fmt(price)} — auto-match on next sweep.`,
        'ok'
      );
      // Refresh both inventory lists so the sold row drops off and the
      // buyer-demand map refreshes (best-bid may shift after we fill).
      await Promise.all([loadSteam(), loadInternal()]);
      try { onRefresh && onRefresh(); } catch (_) {}
    } catch (e) {
      toast('Network error during quick-sell', 'err');
    } finally {
      setQuickSellBusy(null);
    }
  };

  const submit = async () => {
    setError('');
    const p = parseFloat(price);
    // Floor at $0.01 — matches the relist DTO @DecimalMin("0.01") and the
    // server PRICE_TOO_LOW guard. Without this a sub-cent price (e.g. 0.004)
    // passed `<= 0` and rounded to $0.00 in NUMERIC(10,2) → a free, instantly
    // buyable listing.
    if (!p || p < 0.01) { setError('Price must be at least $0.01.'); return; }
    // Cap inline — matches the $100k server limit (PRICE_TOO_HIGH) and
    // submitBulk, so a fat-fingered price fails here instead of on a
    // server round-trip. `p` is shared by the buy-now price and the
    // auction starting bid, so this covers both listing types.
    if (p > 100000) { setError('Price must not exceed $100,000.'); return; }
    const opts = { listingType: sellType };
    const trimmedDesc = (sellDescription || '').trim();
    if (trimmedDesc) {
      if (trimmedDesc.length > 500) {
        setError('Description must be 500 characters or fewer.');
        return;
      }
      opts.description = trimmedDesc;
    }
    if (sellType === 'AUCTION') {
      const d = parseInt(sellDurationHours, 10);
      if (!Number.isFinite(d) || d < 1 || d > 168) {
        setError('Auction duration must be between 1 and 168 hours.');
        return;
      }
      opts.durationHours = d;
      // Optional Buy-Now ceiling (batch 371).
      const bn = (sellBuyNow || '').trim();
      if (bn) {
        const bnVal = parseFloat(bn);
        if (!Number.isFinite(bnVal) || bnVal <= 0) {
          setError('Buy-Now price must be a positive number.');
          return;
        }
        if (bnVal <= p) {
          setError('Buy-Now price must be greater than the starting bid.');
          return;
        }
        if (bnVal > 100000) {
          setError('Buy-Now price must not exceed $100,000.');
          return;
        }
        opts.buyNowPrice = bnVal;
      }
    }
    // Batch 646 — attach auto-accept threshold (percent input → 0..1).
    // Accept 1..50 % as sane bounds; empty string means "no auto-accept"
    // and the server treats null the same way.
    const rawPct = (sellAutoPct || '').trim();
    if (rawPct) {
      const pct = parseFloat(rawPct);
      if (!Number.isFinite(pct) || pct < 1 || pct > 50) {
        setError('Auto-accept discount must be between 1% and 50%.');
        return;
      }
      opts.maxDiscount = pct / 100;
    }
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      let res;
      if (picking.kind === 'steam') {
        res = await listFromSteam(picking.item.assetId, p, opts);
      } else {
        res = await relistItem(picking.listingId, p, opts);
      }
      // ── THE REPLY WE COULD NOT READ ──────────────────────────────────
      // `writeJson` returns the parsed body on any 2xx, and parses with
      // `try { body = await r.json(); } catch { body = null; }` — so a 2xx
      // whose body is not JSON (a proxy's HTML error page, a truncated
      // response, a 204) arrives here as `null`. `res.code` then threw a
      // TypeError out of `submit`, past a `finally` that only cleared
      // `busy`: the modal stayed open, nothing was said, and the seller had
      // no way to know whether his item was now on sale.
      //
      // Guessing either way is worse than saying so. Call it a failure and
      // he lists again — and the double-list guard answers ALREADY_LISTED
      // about the copy he just successfully listed, which is the confusing
      // refusal this whole stream exists to remove. Call it a success and
      // he goes looking in a stall that may be empty. The POST may well
      // have been applied; what we do not have is the answer.
      if (!res || typeof res !== 'object') {
        setError(
          'We sent your listing but could not read the reply, so we do not know whether it went up. ' +
          'Check My Stall before listing this item again — if it is there, it worked.'
        );
        // Refresh the stall behind the modal so the answer he is being sent
        // to look for is already loaded when he gets there.
        try { await onRefresh(); } catch (_) {}
        return;
      }
      if (res.code || res.error) { setError(res.message || res.error); return; }
      // Batch 884 — success toast. Previous flow closed silently: user
      // clicked List, modal went away, and their only feedback that
      // anything happened was the stall refresh behind the modal. Now
      // they see an explicit confirmation with item name, price, and
      // listing type so they can confirm they picked the right options.
      const itemName = picking.item?.name;
      const typeLabel = sellType === 'AUCTION'
        ? `auction (${sellDurationHours}h)`
        : 'Buy Now';
      // A listing created under bot-escrow is NOT live yet. The backend
      // returns status PENDING_ESCROW and the item only reaches the market
      // once the seller accepts the bot's Steam trade offer and the bot
      // genuinely holds it. Saying "Listed" here would be a success message
      // for something that has not happened: the seller would then refresh
      // into a stall that filters on status='ACTIVE' and find nothing, with
      // no hint that the next move is theirs and lives in Steam, not here.
      // Report what actually happened, and name the action they have to take.
      const held = res?.status === 'PENDING_ESCROW' || res?.escrowPending === true;
      const label = itemName ? `"${itemName}"` : 'your item';
      if (held) {
        // 'warn', not 'ok' and not a bare default. utils.toast only knows
        // ok / warn / err — an unknown kind silently renders as ok, i.e.
        // pixel-identical to the success toast, which would leave this
        // whole branch cosmetic. 'warn' also buys 7000ms of dwell instead
        // of 4500ms, and this toast asks the seller to go do something in
        // another application, so it needs to be readable that long.
        toast(
          `${label} is reserved at ${fmt(p)} — accept the Steam trade offer from our bot to send us the item, and it goes live the moment we receive it.`,
          'warn'
        );
      } else {
        toast(
          itemName
            ? `Listed "${itemName}" for ${fmt(p)} as ${typeLabel}.`
            : `Listed for ${fmt(p)} as ${typeLabel}.`,
          'ok'
        );
      }
      await onRefresh();
      onClose();
    } finally { setBusy(false); }
  };

  if (picking) {
    const item = picking.item;
    const isSteam = picking.kind === 'steam';
    // A never-listed item has no live floor, which used to collapse the
    // suggestion to "$0.00" on BOTH tabs (the internal tab was given a
    // fallback chain; the Steam tab was not, and its rows did not even
    // carry `steamPrice` until the projection was fixed). One helper now
    // answers for both, and it reports the BASIS as well as the number so
    // the form can say where the figure came from — or say, in words,
    // that there is no figure. `suggested` stays a string for the
    // placeholder; anchor.basis === null means "do not quote a price".
    // Blocked, and why — computed once for the warning line and the button.
    const pickBlock = isSteam ? steamRowBlock(item) : null;
    const anchor = priceAnchorFor(item, isSteam, pickedRecentMedian);
    const suggested = anchor.value > 0 ? anchor.value.toFixed(2) : '';
    const anchorLabel = anchor.basis === 'floor'  ? 'lowest active listing right now'
                      : anchor.basis === 'median' ? `median of the last ${pickedRecentCount} sale${pickedRecentCount === 1 ? '' : 's'} here`
                      : anchor.basis === 'steam'  ? 'Steam Community Market reference'
                      : null;
    return h(InfoModal, { title: 'List Item for Sale', onClose },
      h('div', { style: { display: 'flex', gap: 18, marginBottom: 20 } },
        h('div', { style: { width: 120, aspectRatio: '1', borderRadius: 10, background: 'radial-gradient(ellipse at 50% 35%, rgba(30,165,255,0.14) 0%, transparent 65%), linear-gradient(180deg, #1a2236 0%, #0d1320 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, padding: 8 } },
          h(ItemImage, {
            item: isSteam
              ? { imageUrl: item.imageUrl || item.iconUrl, name: item.name, category: item.category, iconEmoji: '—' }
              : item,
            variant: 'card'
          })
        ),
        h('div', null,
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 700, marginBottom: 4 } }, item.category || 'Steam item'),
          h('div', { style: { fontSize: 20, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 10 } }, item.name),
          // Say which of the two blocks applies, and what to do about it.
          // 'Not tradable on Steam right now' was the only sentence here, so
          // the OTHER reason a list attempt gets refused — every copy of
          // this stack is already on sale — arrived as a server error after
          // the seller had priced it.
          isSteam && pickBlock === 'NOT_TRADABLE' && h('div', { style: { fontSize: 11, color: 'var(--red)', marginBottom: 8, fontWeight: 700 } },
            'Not tradable on Steam right now',
            h('span', { style: { display: 'block', fontWeight: 400, color: 'var(--text-secondary)', marginTop: 2 } },
              'Steam holds new or recently-traded items for up to 7 days. It becomes listable here the moment Steam releases it — nothing to do on our side.')),
          isSteam && pickBlock === 'ALREADY_LISTED' && h('div', { style: { fontSize: 11, color: 'var(--red)', marginBottom: 8, fontWeight: 700 } },
            (item.quantity || 1) > 1
              ? `All ${item.quantity} copies are already listed`
              : 'This item is already listed',
            h('span', { style: { display: 'block', fontWeight: 400, color: 'var(--text-secondary)', marginTop: 2 } },
              'It is on sale right now — change the price or cancel it in ',
              h('a', { href: '/me/stall', style: { color: 'var(--accent)', fontWeight: 700 } }, 'My Stall'),
              '.')),
          // A partially-listed stack is NOT blocked: there are free copies.
          // Say how many, because the ×N badge alone reads as "all of these
          // are available" and the seller has no other way to tell.
          isSteam && !pickBlock && (item.listedCount > 0) && h('div', {
            style: { fontSize: 11, color: 'var(--text-secondary)', marginBottom: 8, fontWeight: 600 }
          }, `${item.listedCount} of ${item.quantity} already listed · listing copy ${item.listedCount + 1}`),
          h(RarityBadge, { rarity: item.rarity || 'Standard' }),
          // ── The suggested price, its basis, or the reason there isn't one ──
          // Four distinct states, which this line used to render as two.
          // "$0.00" was printed for an item nobody has listed, for an item
          // with no Steam reference, AND for an item whose price lookup had
          // just failed — three different situations, one confident number
          // that was never true (a listing cannot be $0.00; the server
          // rejects anything under a cent).
          priceProbeErr === null
            ? h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 12 } },
                'Checking what this item is going for…')
          : priceProbeErr === true
            ? h('div', {
                style: {
                  fontSize: 12, marginTop: 12, padding: '8px 10px', borderRadius: 6,
                  background: 'rgba(239,68,68,0.10)', border: '1px solid rgba(239,68,68,0.3)',
                  color: '#f87171', fontWeight: 600
                }
              },
                "Couldn't check this item's market price.",
                h('span', { style: { display: 'block', fontWeight: 400, color: 'var(--text-secondary)', marginTop: 2 } },
                  'This is a connection problem on our side, not a price of zero — you can still set your own price and list.'),
                h('button', {
                  type: 'button',
                  className: 'btn btn-ghost',
                  style: { marginTop: 6, padding: '4px 10px', fontSize: 11, border: '1px solid var(--border)' },
                  onClick: () => loadPriceProbes(pickedItemId)
                }, 'Retry price check')
              )
          : anchor.basis
            ? h('div', { style: { fontSize: 12, color: 'var(--text-muted)', marginTop: 12 } },
                'Suggested price: ',
                h('span', { style: { color: 'var(--accent)', fontWeight: 700 } }, fmt(anchor.value)),
                h('span', { style: { display: 'block', fontSize: 11, marginTop: 2 } }, 'Based on the ', anchorLabel, '.'))
            : h('div', { style: { fontSize: 12, color: 'var(--text-secondary)', marginTop: 12 } },
                h('span', { style: { fontWeight: 700, color: 'var(--text-primary)' } }, 'No price reference for this item yet.'),
                h('span', { style: { display: 'block', fontSize: 11, marginTop: 2, color: 'var(--text-muted)' } },
                  'Nothing like it is listed here right now and it has no recorded sales, so you are setting the first price.')),
          // Competitive-landscape chip (batch 360). Only renders when
          // there's at least one competing active listing on this item
          // so a niche/new item's sell form stays uncluttered.
          pickedCompeting.count > 0 && h('div', {
            style: { fontSize: 11, color: 'var(--text-secondary)', marginTop: 6 },
            title: "Based on currently-active BUY NOW listings for this item — your own listings are excluded."
          },
            '—', h('b', null, pickedCompeting.count),
            ' other seller', pickedCompeting.count === 1 ? '' : 's', ' · floor ',
            h('span', {
              style: { color: 'var(--accent)', fontWeight: 700, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" }
            }, fmt(pickedCompeting.floor))
          )
        )
      ),
      // Listing-type toggle — Buy Now (flat price) vs Auction (starting bid
       // for `durationHours`). Default Buy Now so the common path stays a
       // one-click flow; Auction reveals the duration picker when chosen.
      h('div', { className: 'wallet-input-label' }, 'Listing type'),
      h('div', { style: { display: 'flex', gap: 8, marginBottom: 14 } },
        h('button', {
          type: 'button',
          className: `offer-tab ${sellType === 'BUY_NOW' ? 'active' : ''}`,
          style: { flex: 1 },
          onClick: () => setSellType('BUY_NOW'),
          title: 'Instant-buy listing at a flat price'
        }, 'Buy Now'),
        h('button', {
          type: 'button',
          className: `offer-tab ${sellType === 'AUCTION' ? 'active' : ''}`,
          style: { flex: 1 },
          onClick: () => setSellType('AUCTION'),
          title: 'Starting bid — bidders push the price up until the auction ends'
        }, 'Auction')
      ),
      sellType === 'AUCTION' && h('div', { style: { marginBottom: 14 } },
        h('div', { className: 'wallet-input-label' }, 'Auction duration'),
        h('div', { style: { display: 'flex', gap: 6, flexWrap: 'wrap' } },
          [
            { v: '6',   l: '6h' },
            { v: '12',  l: '12h' },
            { v: '24',  l: '1d' },
            { v: '48',  l: '2d' },
            { v: '72',  l: '3d' },
            { v: '168', l: '7d' }
          ].map(opt => h('button', {
            key: opt.v,
            type: 'button',
            className: `price-suggest-chip ${String(opt.v) === String(sellDurationHours) ? 'active' : ''}`,
            onClick: () => setSellDurationHours(opt.v),
            style: String(opt.v) === String(sellDurationHours)
              ? { borderColor: 'var(--accent)', color: 'var(--accent)' }
              : {}
          }, opt.l))
        ),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 6 } },
          'Bids are locked in when the auction ends. Anti-snipe: any bid placed in the final 30 seconds extends the auction by another 30 seconds so nobody wins purely on timing.'),
        // Buy-Now ceiling input (batch 371). Optional. When set, a
        // buyer can skip the auction and instantly settle at this price.
        // Must be > starting bid — server validates.
        h('div', { className: 'wallet-input-label', style: { marginTop: 12 } }, 'Buy Now price (optional)'),
        h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
          h('span', { style: { color: 'var(--text-muted)', fontWeight: 700 } }, '$'),
          h('input', {
            className: 'wallet-amount-input',
            type: 'number', min: '0', max: '100000', step: '0.01',
            inputMode: 'decimal',
            'aria-label': 'Buy Now price',
            placeholder: 'Leave blank for no Buy Now',
            value: sellBuyNow,
            onChange: e => setSellBuyNow(e.target.value),
            style: { flex: 1 }
          })
        ),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 6 } },
          'Optional ceiling — a buyer can hit Buy Now at this price to close the auction instantly. Must be above the starting bid.')
      ),
      h('div', { className: 'wallet-input-label' },
        sellType === 'AUCTION' ? 'Starting bid (USD)' : 'Your asking price (USD)'),
      h('input', {
        className: 'wallet-amount-input',
        type: 'number', min: '0.01', step: '0.01',
        inputMode: 'decimal', enterKeyHint: 'done',
        'aria-label': sellType === 'AUCTION' ? 'Starting bid' : 'Asking price',
        // Empty when we have no anchor: a placeholder of "0.00" reads as a
        // suggestion, and $0.00 is not a price this market will accept.
        placeholder: suggested || 'Set your price',
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
          // Median of the last N recorded sales — empty when the item
          // has no sale history on the platform yet.
          if (pickedRecentMedian != null && pickedRecentMedian > 0) {
            chips.push({
              label: `Last ${pickedRecentCount} sold`,
              v: +pickedRecentMedian.toFixed(2),
              hint: `Median of the last ${pickedRecentCount} actual sale${pickedRecentCount === 1 ? '' : 's'} — anchor to what the market has been paying, not just the current floor.`
            });
          }
          // Batch 551 follow-up — "Top buy order" chip reuses the
          // inventoryBuyOrderDemand map we already populated for the grid.
          // One click fills the price at the best standing bid, which
          // triggers BuyOrderService.tryMatch on submit and auto-fills
          // the order against the seller's listing. Shown only when the
          // bid is genuinely different from the floor so the chip row
          // doesn't duplicate "Floor" on low-liquidity items.
          (() => {
            // Steam rows key the catalogue id as `catalogueId`; internal
            // (relist) rows carry the catalogue item object directly as
            // `item` with `.id`. Reading only `item.id` meant the Top
            // Buy Order chip never rendered on the Steam-item sell form.
            const itemId = isSteam ? item?.catalogueId : item?.id;
            const d = itemId != null && inventoryBuyOrderDemand[String(itemId)];
            if (!d || !d.bestBid) return;
            const best = parseFloat(d.bestBid);
            if (!Number.isFinite(best) || best <= 0) return;
            if (floor > 0 && Math.abs(best - floor) < 0.01) return;
            chips.push({
              label: `Top buy order (${d.count})`,
              v: +best.toFixed(2),
              hint: `${d.count} active buy order${d.count === 1 ? '' : 's'} on this item — list at exactly ${fmt(best)} and it auto-fills on the next matching sweep.`
            });
          })();
          return chips.map((c, i) => h('button', {
            key: i,
            type: 'button',
            className: 'price-suggest-chip',
            title: c.hint || '',
            onClick: () => setPrice(c.v.toFixed(2))
          },
            h('span', { className: 'price-suggest-chip-label' }, c.label),
            h('span', { className: 'price-suggest-chip-amt' }, fmt(c.v))
          ));
        })()
      ),
      (() => {
        const p = parseFloat(price) || 0;
        const isAuction = sellType === 'AUCTION';
        if (p <= 0) return h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 8 } },
          'A 2% platform fee is deducted when the item sells.');
        const fee = platformFee(p);
        const net = sellerPayout(p);
        const floor = parseFloat(isSteam ? (item.suggestedPrice || 0) : (item.lowestPrice || 0));
        const vsFloor = (floor > 0 && p > 0) ? Math.round(((p - floor) / floor) * 100) : null;
        return h('div', {
          style: {
            fontSize: 12, marginTop: 10, padding: 10, borderRadius: 6,
            background: 'var(--bg-elevated)', border: '1px solid var(--border)',
            display: 'grid', gridTemplateColumns: '1fr auto', gap: 4, rowGap: 2
          }
        },
          h('div', { style: { color: 'var(--text-muted)' } }, isAuction ? 'Starting bid' : 'Listed price'),
          // The asking-price field is labeled "(USD)", so p/fee/net are literal
          // USD — render them with a fixed '$', NOT fmt() (which multiplies by
          // the selected-currency FX rate and would show e.g. "€90.16" under a
          // USD field, misleading a EUR seller about what they net). (wave-146)
          h('div', { style: { color: 'var(--text-primary)', fontWeight: 700, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, '$' + p.toFixed(2)),
          h('div', { style: { color: 'var(--text-muted)' } }, 'Platform fee (2%)'),
          h('div', { style: { color: 'var(--red)', fontWeight: 700, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, '−$' + fee.toFixed(2)),
          h('div', { style: { color: 'var(--text-muted)', fontWeight: 700 } },
            isAuction ? 'Minimum you\'ll receive' : "You'll receive"),
          h('div', { style: { color: 'var(--accent)', fontWeight: 800, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, '$' + net.toFixed(2)),
          isAuction && h('div', {
            style: { gridColumn: '1 / -1', fontSize: 10, color: 'var(--text-muted)', marginTop: 4, borderTop: '1px solid var(--border)', paddingTop: 6 }
          },
            'Final payout scales with the winning bid — bidders push the price up until the auction ends. 2% platform fee applies to the final sale, not the starting bid.'
          ),
          !isAuction && vsFloor !== null && h('div', {
            style: { gridColumn: '1 / -1', fontSize: 10, color: 'var(--text-muted)', marginTop: 4, borderTop: '1px solid var(--border)', paddingTop: 6 }
          },
            vsFloor === 0 ? 'At the current floor — competitive with other active listings.' :
            vsFloor < 0    ? `${Math.abs(vsFloor)}% below floor — expected to sell quickly.` :
            vsFloor < 10   ? `${vsFloor}% above floor — may sit in queue behind cheaper listings.` :
                             `${vsFloor}% above floor — buyers will pass on this unless the item is rare or the floor shifts up.`
          ),
          // Steam market-overcharge warning. Buyers will just open the
          // Steam Community Market and buy from there if our listing
          // beats Steam's price in the wrong direction — surface the
          // comparison before the seller commits. Only fires when we
          // have a real Steam reference + the price exceeds it by at
          // least $0.05 so rounding near equality doesn't nag.
          (() => {
            const steam = parseFloat(isSteam ? (item.steamPrice || item.suggestedPrice || 0) : (item.steamPrice || 0));
            if (!(steam > 0) || !(p > 0)) return null;
            if (p <= steam + 0.04) return null;
            const vsSteam = Math.round(((p - steam) / steam) * 100);
            return h('div', {
              style: {
                gridColumn: '1 / -1', fontSize: 10,
                marginTop: 4, padding: '6px 8px', borderRadius: 4,
                background: 'rgba(239,68,68,0.10)', border: '1px solid rgba(239,68,68,0.3)',
                color: '#f87171', fontWeight: 600
              }
            }, `⚠ ${vsSteam}% above the Steam market price (${fmt(steam)}). Buyers comparing against Steam will go there instead.`);
          })()
        );
      })(),
      // Batch 646 — Auto-accept offers (BUY_NOW only). Sellers who
      // don't want to babysit incoming offers can let the server
      // auto-accept anything above `price × (1 − maxDiscount)`. Offers
      // below the threshold still queue for manual review. Auction
      // listings skip this — they settle by bid, not offer.
      sellType === 'BUY_NOW' && h('div', { style: { marginTop: 14 } },
        h('div', { className: 'wallet-input-label' },
          'Auto-accept offers',
          h('span', { style: { marginLeft: 6, fontSize: 11, color: 'var(--text-muted)', fontWeight: 400 } },
            '(optional · 1–50 %)')
        ),
        h('div', { style: { display: 'flex', gap: 8, alignItems: 'center' } },
          h('input', {
            className: 'wallet-amount-input',
            type: 'number', min: '0', max: '50', step: '1',
            inputMode: 'numeric',
            'aria-label': 'Auto-accept discount percent',
            placeholder: 'e.g. 10 → accept offers ≥ 90% of ask',
            value: sellAutoPct,
            onChange: e => setSellAutoPct(e.target.value),
            style: { flex: 1 }
          }),
          h('span', { style: { color: 'var(--text-muted)', fontWeight: 700, fontSize: 13 } }, '%')
        ),
        (() => {
          const pct = parseFloat(sellAutoPct);
          const p = parseFloat(price) || 0;
          if (!Number.isFinite(pct) || pct <= 0 || p <= 0) {
            return h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 6 } },
              'Leave blank to manually review every offer. Set a percent to instantly accept offers within that discount of your ask.');
          }
          const threshold = +(p * (1 - pct / 100)).toFixed(2);
          return h('div', {
            style: {
              fontSize: 11, color: 'var(--green)', marginTop: 6, fontWeight: 700
            }
          }, `✓ Offers of ${fmt(threshold)} or higher will auto-accept (${pct}% off ask).`);
        })()
      ),
      // Optional seller note — helps move items faster by letting the
      // seller spell out terms ("quick sale, accepting offers 10% below"
      // or "mint, never worn"). Sanitised server-side, 500-char cap.
      h('div', { style: { marginTop: 14 } },
        h('div', { className: 'wallet-input-label' },
          'Seller note',
          h('span', { style: { marginLeft: 6, fontSize: 11, color: 'var(--text-muted)', fontWeight: 400 } },
            `(optional · ${sellDescription.length}/500)`)
        ),
        h('textarea', {
          className: 'wallet-amount-input',
          rows: 2,
          maxLength: 500,
          placeholder: 'e.g. "Quick sale, open to offers" or "Mint condition"',
          value: sellDescription,
          onChange: e => setSellDescription(e.target.value),
          style: { fontFamily: 'inherit', fontSize: 13, lineHeight: 1.4, resize: 'vertical' }
        })
      ),
      error && h('div', { className: 'wallet-error' }, error),
      h('div', { style: { display: 'flex', gap: 10, marginTop: 20 } },
        h('button', { className: 'btn btn-ghost', style: { flex: 1, border: '1px solid var(--border)', justifyContent: 'center', padding: 13 }, onClick: () => setPicking(null) }, 'Back'),
        h('button', {
          className: 'btn btn-accent',
          style: { flex: 1, justifyContent: 'center', padding: 13 },
          // Blocked for either reason, not just the tradable one.
          disabled: busy || (isSteam && !!pickBlock),
          title: isSteam && pickBlock === 'ALREADY_LISTED'
            ? 'Every copy of this item you own is already on sale — cancel one in My Stall to relist it'
            : isSteam && pickBlock === 'NOT_TRADABLE'
              ? 'Steam will not let this item move yet'
              : null,
          onClick: submit
        }, busy ? 'Listing…' : 'List for Sale')
      )
    );
  }

  const steamList = steamData?.items || [];
  const internalList = internal || [];

  // Bulk-list submit handler (batch 370). Calls /api/steam/list-bulk
  // with the selected assetIds at the one bulkPrice. Reports per-row
  // successes + failures via toast. Clears selection on any success.
  // Batch 648 — also threads the bulk auto-accept percent through to
  // the same endpoint so the whole batch inherits a uniform threshold.
  const submitBulk = async () => {
    const ids = Array.from(bulkSelected);
    if (ids.length === 0) return;
    const p = parseFloat(bulkPrice);
    if (!p || p < 0.01) { toast('Price must be at least $0.01.', 'err'); return; }
    if (p > 100000) { toast('Price must not exceed $100,000.', 'err'); return; }
    // Optional auto-accept percent, same 1..50 range as the single-list
    // form. Empty / 0 / out-of-range silently drops the opts field so
    // the backend treats it as "no auto-accept" (null).
    const opts = {};
    const rawBulkPct = (bulkAutoPct || '').trim();
    if (rawBulkPct) {
      const pct = parseFloat(rawBulkPct);
      if (!Number.isFinite(pct) || pct < 1 || pct > 50) {
        toast('Auto-accept discount must be between 1% and 50%.', 'err');
        return;
      }
      opts.maxDiscount = pct / 100;
    }
    if (!confirm(`List ${ids.length} item${ids.length === 1 ? '' : 's'} at ${fmt(p)} each (Buy Now)${opts.maxDiscount != null ? ` with ${(opts.maxDiscount*100)|0}% auto-accept` : ''}? This cannot be undone in one click — you'd have to cancel each listing individually.`)) return;
    setBulkBusy(true);
    try {
      const { bulkListFromSteam } = await import('./api.js');
      const res = await bulkListFromSteam(ids, p, opts);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Bulk list failed', 'err');
        return;
      }
      const ok = (res?.ok || []).length;
      const failed = (res?.failed || []).length;
      if (ok > 0) {
        setBulkSelected(new Set());
        setBulkPrice('');
        await onRefresh();
        await loadSteam();
      }
      if (failed === 0) {
        toast(`Listed ${ok} item${ok === 1 ? '' : 's'} at ${fmt(p)}.`, 'ok');
      } else {
        toast(`Listed ${ok} · ${failed} failed (${(res.failed || []).slice(0, 3).map(f => f.code).join(', ')}${failed > 3 ? '…' : ''})`,
          ok > 0 ? 'ok' : 'err');
      }
    } finally { setBulkBusy(false); }
  };
  return h(InfoModal, { title: 'Sell Items', onClose },
    h('div', { className: 'sell-source-tabs' },
      h('button', { className: `offer-tab ${source === 'steam' ? 'active' : ''}`, onClick: () => setSource('steam') },
        'Steam Inventory',
        // When Steam rate-limits us we don't actually know the inventory
        // count, so showing "0" misleadingly suggests the user is broke
        // or has a private inventory. Show an em-dash placeholder until
        // a real fetch lands. Tooltip explains why.
        steamData && h('span', {
          className: 'filter-count',
          style: { marginLeft: 6 },
          title: steamData.blocked
            ? 'Count unavailable — Steam rate-limited our request'
            : steamData.error
              ? "Count unavailable — couldn't reach the inventory service"
              : null
        }, (steamData.blocked || steamData.error) && steamList.length === 0 ? '—' : steamList.length)),
      h('button', { className: `offer-tab ${source === 'internal' ? 'active' : ''}`, onClick: () => setSource('internal') },
        'Platform Inventory', internal && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, internalList.length)),
      h('div', { style: { flex: 1 } }),
      // Price-freshness chip — quiet "Prices updated 23s ago" badge
      // so a seller can tell the suggested-price column on each row
      // hasn't gone stale. Compact variant (slim inline) so it
      // doesn't crowd the Sync Steam button next to it.
      h('div', {
        style: { display: 'inline-flex', alignItems: 'center', marginRight: 8 }
      }, h(PriceFreshnessChip, { compact: true })),
      source === 'steam' && h('button', {
        className: 'btn btn-ghost',
        style: {
          display: 'inline-flex', alignItems: 'center', gap: 6,
          height: 32, padding: '0 12px', fontSize: 11, lineHeight: 1,
          border: '1px solid var(--border)', boxSizing: 'border-box'
        },
        disabled: syncing,
        onClick: resync
      },
        h(MaterialIcon, { name: syncing ? 'hourglass_top' : 'refresh', size: 14 }),
        syncing ? 'Syncing…' : 'Sync Steam'
      )
    ),

    source === 'steam' && steamData === null && h('div', { className: 'spinner' }),
    source === 'steam' && steamData && steamList.length === 0 && (() => {
      // ── Say which of the seven things actually happened ──────────────────
      // The server distinguishes private_profile / rate_limited / upstream_error
      // / malformed_response / network_error / empty_or_wrong_context and sends
      // `reason`, `unreadable` and a remedy sentence in `message`. This block
      // used to read NONE of them: it branched on `error` and `blocked` only,
      // so every server-diagnosed cause collapsed back into one of two strings.
      //
      // Worst case, and the reason this matters: a private profile trips the
      // negative cache, so the server sets `blocked` as well as
      // reason='private_profile'. Checking `blocked` first told that seller
      // "try again in ~5 minutes" — advice that can never come true, because
      // no amount of waiting makes a private inventory readable.
      //
      // `unreadable` is the load-bearing flag: true means we could not get an
      // answer, and we must NOT tell the seller he owns nothing.
      const reason     = steamData?.reason;
      const unreadable = steamData?.unreadable === true;
      const serverMsg  = steamData?.message;
      const isPrivate  = reason === 'private_profile';
      const isThrottled = reason === 'rate_limited' || (!reason && steamData?.blocked);
      // A connection error OR anything the server marked unreadable.
      const couldNotRead = !!steamData?.error || unreadable;

      const icon = steamData?.error || (unreadable && !isThrottled && !isPrivate)
        ? 'cloud_off'
        : isThrottled ? 'hourglass_top'
        : isPrivate   ? 'lock'
        : 'inbox';

      const title = steamData?.error
        ? "Couldn't load your Steam inventory"
        : isPrivate    ? 'Your Steam inventory is private'
        : isThrottled  ? 'Steam is rate-limiting our requests'
        : unreadable   ? "We couldn't read your Steam inventory"
        : 'No s&box items in your Steam inventory';

      // Prefer the server's sentence — it names the actual remedy. Fall back
      // to local copy only when the server said nothing specific.
      const detail = steamData?.error
        ? "We couldn't reach the inventory service — this is a connection problem, not an empty inventory. Re-sync to try again."
        : serverMsg
          ? (isThrottled && steamData?.retryInSec
              ? `${serverMsg} (about ${Math.max(1, Math.ceil(Number(steamData.retryInSec) / 60))} minute${Math.max(1, Math.ceil(Number(steamData.retryInSec) / 60)) === 1 ? '' : 's'}.) Your previously-synced inventory still works for listing.`
              : serverMsg)
          : couldNotRead
            ? "We couldn't read your inventory from Steam, so we don't yet know what you own. This is not the same as owning nothing — please try again."
            : "Either your Steam inventory is set to Private, or there are no s&box cosmetics in it. If the inventory is public and you still see this, the sync cache may be stale — try again.";

      return h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' },
        h(MaterialIcon, { name: icon, size: 26 })),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
        title),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 420, margin: '0 auto 14px', lineHeight: 1.55 } },
        detail),
      // Batch 831 — actionable empty-state. Two real CTAs instead of
      // one "Sync Steam" hint buried at the bottom of the page:
      // 1) Direct link to Steam's privacy settings so a user who
      //    shrugs at "is private" knows where to fix it.
      // 2) Sync button right here so they don't have to scroll back
      //    up to the toolbar's Sync Steam affordance.
      h('div', {
        style: {
          display: 'inline-flex', gap: 8, justifyContent: 'center',
          alignItems: 'center', flexWrap: 'wrap', marginBottom: 18
        }
      },
        // Privacy-settings link is only relevant to the "private inventory"
        // empty state — hide it on a connection error, where it would
        // misdirect the user away from the real fix (Re-sync / retry).
        !steamData?.error && h('a', {
          className: 'btn btn-ghost',
          href: 'https://steamcommunity.com/my/edit/settings',
          target: '_blank',
          rel: 'noopener noreferrer',
          style: {
            display: 'inline-flex', alignItems: 'center', gap: 6,
            height: 32, padding: '0 14px', fontSize: 12, lineHeight: 1,
            border: '1px solid var(--border)', boxSizing: 'border-box'
          },
          title: "Opens Steam's privacy settings. Set Inventory → Public, then click Re-sync."
        },
          'Open Steam privacy settings',
          h(MaterialIcon, { name: 'open_in_new', size: 14 })
        ),
        h('button', {
          className: 'btn btn-accent',
          disabled: syncing,
          onClick: resync,
          style: {
            display: 'inline-flex', alignItems: 'center', gap: 6,
            height: 32, padding: '0 14px', fontSize: 12, lineHeight: 1,
            boxSizing: 'border-box'
          }
        },
          h(MaterialIcon, { name: syncing ? 'hourglass_top' : 'refresh', size: 14 }),
          syncing ? 'Syncing…' : 'Re-sync now'
        )
      )
    );
    })(),
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
        // Incomplete-list warning. The server caps the Steam fetch at 500
        // assets and does not paginate, so a large inventory arrives short.
        // Without this the missing items are indistinguishable from items the
        // seller does not own — a silent wrong answer on a successful 200.
        steamData?.truncated && h('div', {
          style: {
            display: 'flex', alignItems: 'flex-start', gap: 8,
            padding: '10px 12px', marginBottom: 12, borderRadius: 8,
            border: '1px solid var(--border)', background: 'var(--bg-elevated, rgba(255,180,0,0.08))',
            fontSize: 12, lineHeight: 1.5, color: 'var(--text-secondary)'
          }
        },
          h(MaterialIcon, { name: 'warning', size: 16 }),
          h('span', null, steamData.truncationMessage ||
            `Showing ${steamData.shownCount} of ${steamData.totalInventoryCount} items — this list is incomplete.`)
        ),
        // Summary chips: tradable / matched / new + estimated floor value
        // + liquid-value (what the tradable subset would fetch right now
        // if every match-ready row were quick-sold at its top buy order).
        (() => {
          // Stack-aware totals. Each row from the server may now
          // represent N physical assets (`quantity` field, defaulting
          // to 1 for rows from older payloads). All summary chips below
          // multiply by `qty` so a stack of 50 Lunar Trousers counts
          // as 50 toward "total"/"tradable" and 50 × suggestedPrice
          // toward est. value — not 1×, which is what the pre-stack
          // implementation reported.
          const qty = (s) => Number(s.quantity) > 0 ? Number(s.quantity) : 1;
          const totalAssets = steamList.reduce((a, s) => a + qty(s), 0);
          const lockedAssets = steamList.filter(s => !s.tradable).reduce((a, s) => a + qty(s), 0);
          // ── "tradable" was not the question he is asking ─────────────────
          // This chip counted every Steam-tradable copy, listed or not. A
          // seller holding fifty Lunar Trousers with forty already on sale
          // read "50 tradable · $500 est. value" — two numbers describing an
          // inventory he cannot sell, in the strip he reads FIRST, above a
          // grid that (now) tells him the truth item by item. The summary
          // was the last place still making the old claim.
          //
          // `listableQuantity` comes from the server and is already
          // "tradable AND not already listed". Older payloads (and anything
          // cached from before that deploy) do not carry it, so fall back to
          // the tradable count, which is all this UI ever had.
          const copiesListable = (s) => Number.isFinite(Number(s.listableQuantity))
            ? Number(s.listableQuantity)
            : (s.tradable ? qty(s) : 0);
          const listableAssets = steamList.reduce((a, s) => a + copiesListable(s), 0);
          const listedAssets   = steamList.reduce((a, s) => a + (Number(s.listedCount) || 0), 0);
          // Floor-sum across catalogued items (uncatalogued rows have
          // no reference price so they don't contribute). Only counts
          // tradable items — a locked inventory row is unsellable so
          // including it in the value would overpromise. Multiplied by
          // quantity now that one row can represent a stack.
          // ...and over the copies he can still LIST, not every tradable
          // copy. Counting the forty already on sale here quotes their value
          // to him twice: once in this estimate and once in My Stall.
          const estValue = steamList
            .filter(s => s.catalogueId)
            .reduce((acc, s) => acc + (parseFloat(s.suggestedPrice) || 0) * copiesListable(s), 0);
          // Liquid-value — sum of bestBid across tradable items that
          // have a standing buy order. This is the exact wallet credit
          // the seller would realise if they hit Quick Sell on every
          // liquid row right now. Note we do NOT multiply by qty here:
          // the standing buy order's bestBid only buys ONE copy at that
          // price (the rest of the stack would have to fall through to
          // the next price level), so the realisable liquid value of
          // a stack of N is bounded by the buy-order book depth, not
          // N × bestBid. Keep the conservative one-per-row sum.
          let liquidValue = 0;
          let liquidCount = 0;
          steamList.forEach(s => {
            if (!s.tradable || !s.catalogueId) return;
            const d = inventoryBuyOrderDemand[String(s.catalogueId)];
            const best = d && parseFloat(d.bestBid);
            if (Number.isFinite(best) && best > 0) {
              liquidValue += best;
              liquidCount++;
            }
          });
          return h('div', { className: 'sell-summary' },
            h('div', { className: 'sell-summary-chip' },
              h('span', { className: 'sell-summary-num' }, totalAssets), ' total'),
            h('div', {
              className: 'sell-summary-chip ok',
              'data-testid': 'sell-summary-listable',
              title: 'Copies you can put up for sale right now: tradable on Steam and not already listed here.'
            }, h('span', { className: 'sell-summary-num' }, listableAssets), ' can list now'),
            // Only when there is something to say. A seller with nothing on
            // sale does not need a zero, but a seller with forty on sale
            // needs to know why "can list now" is smaller than "total".
            listedAssets > 0 && h('div', {
              className: 'sell-summary-chip accent',
              'data-testid': 'sell-summary-listed',
              title: 'Copies already on sale here. Reprice or cancel them in My Stall.'
            }, h('span', { className: 'sell-summary-num' }, listedAssets), ' already listed'),
            h('div', { className: 'sell-summary-chip warn' },
              h('span', { className: 'sell-summary-num' }, lockedAssets), ' locked'),
            h('div', { className: 'sell-summary-chip accent' },
              h('span', { className: 'sell-summary-num' }, steamList.filter(s => s.catalogueId).length), ' already in catalogue'),
            h('div', { className: 'sell-summary-chip' },
              h('span', { className: 'sell-summary-num' }, steamList.filter(s => !s.catalogueId).length), ' new to sboxmarket'),
            estValue > 0 && h('div', {
              className: 'sell-summary-chip accent',
              title: 'Sum of the floor price across the catalogued copies you can still list (stacks counted by quantity; copies already on sale are in My Stall, not here). A rough "what\'s this inventory worth?" number — actual sale prices can land above or below.'
            }, h('span', { className: 'sell-summary-num' }, localStorage.getItem('sb_privacy') === '1' ? '$•••••' : fmt(estValue)), ' est. value'),
            liquidCount > 0 && h('div', {
              className: 'sell-summary-chip ok',
              title: `${liquidCount} stack${liquidCount === 1 ? '' : 's'} have a standing buy order — the total is the wallet credit if one copy of each were Quick Sold right now (before the 2% platform fee). Stacks of multiple copies may go further if buy-order depth allows.`
            }, '⚡ ', h('span', { className: 'sell-summary-num' }, localStorage.getItem('sb_privacy') === '1' ? '$•••••' : fmt(liquidValue)), ' liquid now')
          );
        })(),
        // Bulk-list action bar (batch 370). Sticky at the top of the
        // inventory grid when at least one item is checked. Price applies
        // to every selected item as a flat BUY_NOW listing. Hidden when
        // nothing is selected so the form stays clean for single-item use.
        bulkSelected.size > 0 && h('div', {
          style: {
            position: 'sticky', top: 0, zIndex: 5,
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '10px 14px', marginBottom: 10,
            background: 'rgba(30,165,255,0.12)',
            border: '1px solid rgba(30,165,255,0.35)',
            borderRadius: 8,
            fontSize: 12, flexWrap: 'wrap'
          }
        },
          h('span', null,
            h('b', { style: { color: 'var(--accent)' } }, bulkSelected.size),
            ' selected for bulk list'),
          h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, '·'),
          h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, 'Price'),
          h('input', {
            className: 'wallet-amount-input',
            style: { width: 100, fontSize: 13 },
            type: 'number', min: '0', max: '100000', step: '0.01',
            inputMode: 'decimal',
            'aria-label': 'Bulk list price',
            placeholder: '0.00',
            value: bulkPrice,
            onChange: e => setBulkPrice(e.target.value)
          }),
          // Batch 648 — bulk auto-accept percent. Compact 60px input so it
          // fits inline with the Price field; empty = no auto-accept.
          h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, 'Auto-accept'),
          h('input', {
            className: 'wallet-amount-input',
            style: { width: 60, fontSize: 13 },
            type: 'number', min: '0', max: '50', step: '1',
            inputMode: 'numeric',
            'aria-label': 'Bulk auto-accept percent',
            placeholder: '%',
            value: bulkAutoPct,
            onChange: e => setBulkAutoPct(e.target.value),
            title: 'Optional: auto-accept offers within this % of the bulk price. Empty = manual review for every offer.'
          }),
          h('button', {
            className: 'btn btn-accent',
            style: { padding: '6px 14px', fontSize: 12, fontWeight: 700 },
            disabled: bulkBusy || !bulkPrice,
            onClick: submitBulk
          }, bulkBusy ? 'Listing…' : `List ${bulkSelected.size} @ ${fmt(parseFloat(bulkPrice || 0))}`),
          h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 12px', fontSize: 11, border: '1px solid var(--border)' },
            onClick: () => { setBulkSelected(new Set()); setBulkPrice(''); setBulkAutoPct(''); }
          }, 'Clear')
        ),
        // Filter bar — rarity chips + free-text search. Hides when the
        // inventory has <=6 items because the chips add noise for free.
        steamList.length > 6 && h('div', { className: 'sell-filter-bar' },
          h('button', {
            className: `wallet-tx-filter-chip ${sellRarityFilter === 'All' ? 'active' : ''}`,
            'aria-pressed': sellRarityFilter === 'All',
            onClick: () => setSellRarityFilter('All')
          }, `All · ${steamList.length}`),
          rarities.map(r => h('button', {
            key: r,
            className: `wallet-tx-filter-chip ${sellRarityFilter === r ? 'active' : ''}`,
            'aria-pressed': sellRarityFilter === r,
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
          // `block` is null, 'NOT_TRADABLE' or 'ALREADY_LISTED'. A row whose
          // every copy is already on sale now LOOKS unavailable, instead of
          // looking identical to a listable one and failing on submit.
          className: `inventory-item ${steamRowBlock(si) ? 'disabled' : ''} ${bulkSelected.has(si.assetId) ? 'bulk-selected' : ''}`,
          style: bulkSelected.has(si.assetId)
            ? { outline: '2px solid var(--accent)', outlineOffset: 2 }
            : null,
          // A blocked row is not a button. It used to be one: `role="button"`
          // with `aria-disabled="true"` and a live onClick, which told
          // assistive tech the control was unavailable while sighted users
          // could click it into a form that could only fail. Now it carries
          // no button semantics at all, and the reason plus the way out are
          // ON the card — nothing to click, nothing to find out the hard way.
          onClick: steamRowBlock(si) ? undefined : () => startPickSteam(si),
          role: steamRowBlock(si) ? null : 'button',
          tabIndex: steamRowBlock(si) ? null : 0,
          'aria-label': !steamRowBlock(si)
            ? `List ${si.name} for sale${bulkSelected.has(si.assetId) ? ' (selected for bulk)' : ''}`
            : steamRowBlock(si) === 'ALREADY_LISTED'
              ? `${si.name} — already listed, not available to list again`
              : `${si.name} — not tradable on Steam right now`,
          onKeyDown: (e) => {
            if (steamRowBlock(si)) return;
            const tag = (e.target?.tagName || '').toLowerCase();
            if (tag === 'input' || tag === 'button' || tag === 'label') return;
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              startPickSteam(si);
            }
          }
        },
          // Bulk-select checkbox (batch 370) — absolute positioned in the
          // top-left. stopPropagation so clicking the checkbox doesn't
          // also trigger the per-row Pick flow.
          // Not offered on a row with no listable copy: bulk-listing it
          // would send an assetId the server is going to refuse, and the
          // failure would arrive as one line in a "Listed 4 · 1 failed"
          // toast with a code in it.
          !steamRowBlock(si) && h('label', {
            style: {
              position: 'absolute', top: 4, left: 4,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              width: 22, height: 22, borderRadius: 4,
              background: bulkSelected.has(si.assetId) ? 'var(--accent)' : 'rgba(11,15,26,0.75)',
              border: '1px solid ' + (bulkSelected.has(si.assetId) ? 'var(--accent)' : 'var(--border)'),
              cursor: 'pointer', zIndex: 2
            },
            title: 'Select for bulk-list',
            onClick: e => { e.stopPropagation(); toggleBulk(si.assetId); }
          },
            h('input', {
              type: 'checkbox',
              checked: bulkSelected.has(si.assetId),
              onChange: () => {},
              onClick: e => e.stopPropagation(),
              style: { display: 'none' }
            }),
            bulkSelected.has(si.assetId) && h('span', {
              style: { color: '#0b0f1a', fontSize: 14, fontWeight: 900, lineHeight: 1 }
            }, '✓')
          ),
          !si.catalogueId && h('div', { className: 'inventory-new-badge' }, 'NEW'),
          // Stack-quantity badge — surfaces when one Steam descriptor
          // represents N physical copies (e.g. 50× Lunar Trousers). The
          // ×N pill lives in the top-right corner so it doesn't clash
          // with the bulk-select checkbox (top-left) or the NEW badge
          // (which the existing CSS positions). Hidden for singletons
          // so single-copy items look identical to the pre-stacking UI.
          (Number(si.quantity) || 1) > 1 && h('div', {
            style: {
              position: 'absolute', top: 4, right: 4,
              padding: '2px 8px', borderRadius: 999,
              background: 'rgba(11,15,26,0.92)',
              border: '1px solid var(--accent-border)',
              color: 'var(--accent)', fontSize: 11, fontWeight: 800,
              letterSpacing: '0.02em', lineHeight: 1, zIndex: 2,
              boxShadow: '0 1px 3px rgba(0,0,0,0.45)'
            },
            title: `You have ${si.quantity} copies of this item. Listing creates one listing per click — repeat to list more, or use the bulk-list checkbox to list multiple at once.`
          }, '×', si.quantity),
          h('div', { className: 'inventory-thumb' },
            // Wrap the Steam-shape record into the shape ItemImage expects so it
            // gets the same lazy-load + poster-fallback treatment as every other
            // thumbnail. If Steam's CDN 404s we end up with a category glyph
            // instead of a broken-image icon.
            h(ItemImage, { item: { imageUrl: si.imageUrl || si.iconUrl, name: si.name, category: si.category, iconEmoji: '—' }, variant: 'card' })
          ),
          h('div', { className: 'inventory-name' }, si.name),
          h('div', { className: 'inventory-floor' }, (si.catalogueId && parseFloat(si.suggestedPrice) > 0) ? 'Floor ' + fmt(si.suggestedPrice) : 'Set your price'),
          // Batch 551 — buy-order demand chip. When a standing buy order
          // exists at >= floor, highlight that price so the seller can
          // instantly match it instead of undercutting the floor. Uses
          // the accent-colored "🎯" so the eye lands on it without
          // drowning out the grid.
          (() => {
            const d = si.catalogueId && inventoryBuyOrderDemand[String(si.catalogueId)];
            if (!d || !d.bestBid) return null;
            const best = parseFloat(d.bestBid);
            if (!Number.isFinite(best) || best <= 0) return null;
            return h('div', {
              style: { fontSize: 10, color: 'var(--accent)', fontWeight: 700, marginTop: 2 },
              title: `${d.count} buyer${d.count === 1 ? '' : 's'} want this — list at ${fmt(best)} or below to auto-fill`
            }, 'Top buy order · ', fmt(best));
          })(),
          // Quick-Sell button — only on rows where a standing buy order
          // is actually match-ready AND the item is Steam-tradable (a
          // non-tradable Steam item can't reach the buyer even if listed,
          // so surfacing Quick Sell would be a false promise). One click
          // → listing created at bestBid → BuyOrderService.tryMatch fires
          // on the next sweep → wallet credit lands without the seller
          // ever opening the pricing form.
          (() => {
            // Same false promise if every copy is already listed: the click
            // would POST an assetId the server refuses, and the seller would
            // get "Could not quick-sell" with no reason he can act on.
            if (steamRowBlock(si)) return null;
            const d = si.catalogueId && inventoryBuyOrderDemand[String(si.catalogueId)];
            if (!d || !d.bestBid) return null;
            const best = parseFloat(d.bestBid);
            if (!Number.isFinite(best) || best <= 0) return null;
            const key = `steam:${si.assetId}`;
            const busyThis = quickSellBusy === key;
            return h('button', {
              className: 'btn btn-accent',
              style: {
                marginTop: 4, padding: '4px 8px', fontSize: 10, fontWeight: 800,
                width: '100%', letterSpacing: '0.03em',
                opacity: quickSellBusy && !busyThis ? 0.5 : 1,
                cursor: quickSellBusy && !busyThis ? 'not-allowed' : 'pointer'
              },
              disabled: !!quickSellBusy,
              title: `List at ${fmt(best)} — auto-fills against the standing buy order on the next sweep`,
              onClick: e => { e.stopPropagation(); quickSell('steam', si, best); }
            }, busyThis ? 'Listing…' : `⚡ Quick Sell · ${fmt(best)}`);
          })(),
          // The two blocked states, and the partially-listed one. Previously
          // only "NOT TRADABLE" existed, so a copy that could not be listed
          // because it was ALREADY listed looked exactly like one that could.
          steamRowBlock(si) === 'NOT_TRADABLE' &&
            h('div', { style: { fontSize: 9, color: 'var(--red)', fontWeight: 700, marginTop: 2 } }, 'NOT TRADABLE'),
          steamRowBlock(si) === 'ALREADY_LISTED' &&
            h('div', {
              style: { fontSize: 9, color: 'var(--red)', fontWeight: 700, marginTop: 2 },
              title: 'Already on sale — reprice or cancel it in My Stall'
            }, (si.quantity || 1) > 1 ? `ALL ${si.quantity} LISTED` : 'ALREADY LISTED'),
          // The way out, on the card, because the card is no longer clickable.
          steamRowBlock(si) === 'ALREADY_LISTED' &&
            h('a', {
              href: '/me/stall',
              style: { fontSize: 9, color: 'var(--accent)', fontWeight: 700, marginTop: 2, display: 'inline-block' },
              onClick: e => e.stopPropagation()
            }, 'Manage in My Stall'),
          !steamRowBlock(si) && si.listedCount > 0 &&
            h('div', {
              style: { fontSize: 9, color: 'var(--text-secondary)', fontWeight: 700, marginTop: 2 },
              title: `${si.listedCount} of your ${si.quantity} copies are on sale; ${si.listableQuantity} can still be listed`
            }, `${si.listedCount} OF ${si.quantity} LISTED`)
        ))
      )
      );
    })(),

    source === 'internal' && internalErr && h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'cloud_off', size: 26 })),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
        "Couldn't load your platform inventory"),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
        'Something interrupted the request. Your items are safe — this is only a display hiccup.'),
      h('div', { style: { display: 'flex', justifyContent: 'center' } },
        h('button', { className: 'btn btn-secondary', onClick: () => { setInternalErr(false); loadInternal(); } }, 'Retry'))),
    source === 'internal' && !internalErr && internal === null && h('div', { className: 'spinner' }),
    source === 'internal' && internal && internalList.length === 0 && h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'Platform inventory empty'),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 18px' } },
        'Items you buy on sboxmarket appear here. You can relist any of them at a new price.')
    ),
    // Batch 1006 — overflow banner when the server caps the payload at
    // 500 rows. Harmless for typical users (internalTotal == internalList.length);
    // only surfaces once a power-user crosses the cap. Uses the total
    // reported by the server's X-Total-Count header, not the trimmed
    // length, so "Showing most recent 500 of 742" renders accurately.
    source === 'internal' && internalTotal != null && internalList.length > 0
      && internalTotal > internalList.length && h('div', {
        style: {
          margin: '0 0 12px', padding: '10px 14px', fontSize: 12,
          background: 'rgba(30,165,255,0.08)',
          border: '1px solid var(--accent-border)',
          borderRadius: 8, color: 'var(--text-secondary)',
          display: 'flex', alignItems: 'center', gap: 10
        },
        title: `Server caps this list at 500 rows per request. Older items stay safe in your inventory — list the most recent ones first.`
      },
        h('span', null, '—',
          'Showing most recent ',
          h('strong', { style: { color: 'var(--text-primary)' } }, internalList.length),
          ' of ',
          h('strong', { style: { color: 'var(--accent)' } }, internalTotal),
          ' inventory items.')
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
      // Value summary — same shape as the Steam tab so flipping between
      // them doesn't lose the "what's this inventory worth?" anchor.
      // Platform-inventory rows always have a catalogue id + floor, so
      // estValue is unconditional (vs. Steam where we filter on
      // catalogueId to skip uncatalogued rows). Liquid value uses the
      // same buy-order demand map that powers the per-row chips.
      //
      // A row's market value is the LIVE floor (lowest active listing)
      // when the item has listings, else the Steam Market reference
      // price. Treating a 0 floor (no current listings) as a literal
      // $0 made a $76 helmet read "Floor $0.00" and crushed the est-
      // value total to a few dollars even when the inventory held
      // $100+ of items. Fall back to steamPrice so the grid shows a
      // real, non-misleading number, and tag whether it's a live floor
      // or a reference estimate so the per-row label reads "Floor" vs
      // "Est." instead of an alarming zero.
      const rowValue = (it) => {
        const f = parseFloat(it?.lowestPrice);
        if (Number.isFinite(f) && f > 0) return { v: f, kind: 'floor' };
        const s = parseFloat(it?.steamPrice);
        if (Number.isFinite(s) && s > 0) return { v: s, kind: 'est' };
        return { v: 0, kind: 'none' };
      };
      const intEstValue = internalList.reduce((acc, l) => acc + rowValue(l?.item).v, 0);
      let intLiquidValue = 0;
      let intLiquidCount = 0;
      internalList.forEach(l => {
        const id = l?.item?.id;
        if (!id) return;
        const d = inventoryBuyOrderDemand[String(id)];
        const best = d && parseFloat(d.bestBid);
        if (Number.isFinite(best) && best > 0) {
          intLiquidValue += best;
          intLiquidCount++;
        }
      });
      return h('div', null,
        // Summary strip — parity with the Steam tab (batch 634).
        h('div', { className: 'sell-summary' },
          h('div', { className: 'sell-summary-chip' },
            h('span', { className: 'sell-summary-num' }, internalList.length), ' total'),
          intEstValue > 0 && h('div', {
            className: 'sell-summary-chip accent',
            title: 'Approximate market value across every platform-inventory item — the live floor price where the item has active listings, otherwise the Steam Market reference price. Actual sale prices can land above or below.'
          }, h('span', { className: 'sell-summary-num' }, localStorage.getItem('sb_privacy') === '1' ? '$•••••' : fmt(intEstValue)), ' est. value'),
          intLiquidCount > 0 && h('div', {
            className: 'sell-summary-chip ok',
            title: `${intLiquidCount} item${intLiquidCount === 1 ? '' : 's'} have a standing buy order — the total is the exact wallet credit if every row were Quick Sold right now (before the 2% platform fee).`
          }, '⚡ ', h('span', { className: 'sell-summary-num' }, localStorage.getItem('sb_privacy') === '1' ? '$•••••' : fmt(intLiquidValue)), ' liquid now')
        ),
        (rarities.length > 1 || internalList.length > 6) && h('div', { className: 'sell-filter-bar', style: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, marginBottom: 12 } },
          h('button', {
            className: `wallet-tx-filter-chip ${sellRarityFilter === 'All' ? 'active' : ''}`,
            'aria-pressed': sellRarityFilter === 'All',
            onClick: () => setSellRarityFilter('All')
          }, `All · ${internalList.length}`),
          rarities.map(r => h('button', {
            key: r,
            className: `wallet-tx-filter-chip ${sellRarityFilter === r ? 'active' : ''}`,
            'aria-pressed': sellRarityFilter === r,
            onClick: () => setSellRarityFilter(r)
          }, `${r} · ${internalList.filter(l => (l.item?.rarity || 'Standard') === r).length}`)),
          h('div', { style: { flex: 1, minWidth: 140 } },
            h('input', {
              className: 'price-input',
              style: { width: '100%', fontSize: 12 },
              placeholder: 'Filter inventory…',
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
                onClick: () => startPickInternal(l),
                role: 'button',
                tabIndex: 0,
                'aria-label': (() => { const rv = rowValue(l.item); return `Relist ${l.item?.name || 'item'} (${rv.kind === 'floor' ? 'floor ' + fmt(rv.v) : rv.kind === 'est' ? 'est. ' + fmt(rv.v) : 'unpriced'})`; })(),
                onKeyDown: (e) => {
                  const tag = (e.target?.tagName || '').toLowerCase();
                  if (tag === 'button' || tag === 'a' || tag === 'input') return;
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    startPickInternal(l);
                  }
                }
              },
                h('div', { className: 'inventory-thumb' }, h(ItemImage, { item: l.item || {}, variant: 'thumb' })),
                h('div', { className: 'inventory-name' }, l.item?.name || 'Item'),
                (() => {
                  // Live floor when listed; Steam reference price otherwise
                  // (never a bare "Floor $0.00" on a $76 item with no live
                  // listings). "Unpriced" only when neither is known.
                  const rv = rowValue(l.item);
                  return h('div', {
                    className: 'inventory-floor',
                    title: rv.kind === 'est'
                      ? 'No active listings right now — showing the Steam Market reference price'
                      : rv.kind === 'none' ? 'No price reference available yet' : null
                  }, rv.kind === 'floor' ? 'Floor ' + fmt(rv.v)
                   : rv.kind === 'est'   ? 'Est. ' + fmt(rv.v)
                   : 'Unpriced');
                })(),
                // Batch 551 — buy-order demand chip on the internal
                // (platform) inventory grid, mirroring the Steam tab.
                // Relist-at-best-bid is the single fastest path to a
                // sale on a platform-owned item, so surface the signal
                // the moment the grid paints.
                (() => {
                  const d = l.item?.id && inventoryBuyOrderDemand[String(l.item.id)];
                  if (!d || !d.bestBid) return null;
                  const best = parseFloat(d.bestBid);
                  if (!Number.isFinite(best) || best <= 0) return null;
                  return h('div', {
                    style: { fontSize: 10, color: 'var(--accent)', fontWeight: 700, marginTop: 2 },
                    title: `${d.count} buyer${d.count === 1 ? '' : 's'} want this — list at ${fmt(best)} or below to auto-fill`
                  }, 'Top buy order · ', fmt(best));
                })(),
                // Quick-Sell on the platform-inventory tab too. Mirrors the
                // Steam-tab button — relistItem takes the current listingId
                // instead of an assetId. No tradable check (platform items
                // are always deliverable — they live in the sboxmarket
                // inventory table, not Steam).
                (() => {
                  const d = l.item?.id && inventoryBuyOrderDemand[String(l.item.id)];
                  if (!d || !d.bestBid) return null;
                  const best = parseFloat(d.bestBid);
                  if (!Number.isFinite(best) || best <= 0) return null;
                  const key = `int:${l.id}`;
                  const busyThis = quickSellBusy === key;
                  return h('button', {
                    className: 'btn btn-accent',
                    style: {
                      marginTop: 4, padding: '4px 8px', fontSize: 10, fontWeight: 800,
                      width: '100%', letterSpacing: '0.03em',
                      opacity: quickSellBusy && !busyThis ? 0.5 : 1,
                      cursor: quickSellBusy && !busyThis ? 'not-allowed' : 'pointer'
                    },
                    disabled: !!quickSellBusy,
                    title: `List at ${fmt(best)} — auto-fills against the standing buy order on the next sweep`,
                    onClick: e => { e.stopPropagation(); quickSell('internal', l, best); }
                  }, busyThis ? 'Listing…' : `⚡ Quick Sell · ${fmt(best)}`);
                })()
              ))
            )
      );
    })()
  );
}

// ── My Stall ────────────────────────────────────────────────────
// Batch 876 — wrapper that handles the anon case BEFORE any hooks fire
// in the inner component. Moving the `if (!me)` check inside
// `MyStallModalInner` would violate React's rules-of-hooks because the
// inner body defines ~30 hooks and the early-return would skip them on
// anon. Direct-URL to /me/stall used to render a spinner indefinitely
// because the load() only fires when `me` is set.
export function MyStallModal(props) {
  if (!props.me) {
    return h(InfoModal, { title: 'My Stall', onClose: props.onClose },
      h(SignInNeededEmptyState, { what: 'and manage your stall' }));
  }
  return h(MyStallModalInner, props);
}

function MyStallModalInner({ onClose, me, onRefresh, initialTab }) {
  const [stall, setStall] = useState(null);
  // Active-stall fetch failure (vs a genuinely-empty stall) so the modal can
  // show a retry card instead of spinning forever / claiming the seller's live
  // listings vanished — sibling of soldErr. (audit P2)
  const [stallErr, setStallErr] = useState(false);
  // True active-listing count on the server — feeds the MyStall Active
  // tab's "Showing most recent 500 of N" overflow banner when a
  // prolific seller crosses the 500-row display cap (batch 1033).
  const [stallTotal, setStallTotal] = useState(null);
  const [sold, setSold] = useState(null);
  // Sold-history fetch failure (vs a genuinely-empty history) so the tab can
  // show a retry card instead of falsely telling a seller their payouts
  // vanished. (audit P2)
  const [soldErr, setSoldErr] = useState(false);
  // True sale-history count on the server — feeds the "Showing most
  // recent 200 of N" overflow banner on the Sold tab for power-sellers.
  // Null until the first fetch resolves so the banner doesn't flash.
  // Named `soldRowCount` (not `soldTotal`) because `soldTotal` downstream
  // is the dollar-sum of the page's rows.
  const [soldRowCount, setSoldRowCount] = useState(null);
  // Per-listing analytics (view counts, item supply, 30-day item demand,
  // price-vs-floor delta) — backs the third "Analytics" tab. Fetched lazily
  // when the seller opens the tab so the default Active view stays fast.
  const [analytics, setAnalytics] = useState(null);
  const [tab, setTab] = useState(
    initialTab === 'sold' ? 'sold'
      : initialTab === 'analytics' ? 'analytics'
        : 'active'
  ); // 'active' | 'sold' | 'analytics'
  // Batch 772 — active-tab sub-filter chips (ALL / BUY_NOW / AUCTION /
  // HIDDEN). Heavy sellers often want to audit just their auctions or
  // just their hidden listings without scrolling past 50 rows of the
  // other kind. Persisted so the filter survives a modal close.
  const [stallTypeFilter, setStallTypeFilter] = useState(() => {
    try { return localStorage.getItem('sb_mystall_filter') || 'ALL'; }
    catch { return 'ALL'; }
  });
  const setStallTypeFilterPersist = (v) => {
    setStallTypeFilter(v);
    try { localStorage.setItem('sb_mystall_filter', v); } catch (_) {}
  };
  // 2026-05-20 — sync the active tab with the route param. pickTab
  // navigates to /me/stall/<tab>, and the modal stays mounted across
  // tab routes, so browser back/forward changed initialTab but left the
  // displayed tab stale (useState only reads its argument once).
  // Mirrors the WalletModal initialTab sync effect.
  useEffect(() => {
    if (initialTab === 'active' || initialTab === 'sold' || initialTab === 'analytics') {
      setTab(initialTab);
    }
  }, [initialTab]);
  const [editing, setEditing] = useState(null); // listing id being edited inline
  const [editPrice, setEditPrice] = useState('');
  const [editDesc, setEditDesc]   = useState('');
  // Date-range filter for the my-stall Sold CSV export — bounds pass
  // through to /api/listings/my-stall/sold.csv as ?from=&to= so a
  // seller can pull a quarterly sales slice straight into Excel for tax.
  const [soldDateFrom, setSoldDateFrom] = useState(null);
  const [soldDateTo,   setSoldDateTo]   = useState(null);
  // Auto-accept offer discount (0..50 stored as integer percent in the
  // UI, converted to 0..1 fraction on save). Empty string = no auto-
  // accept (listing.maxDiscount stays null).
  const [editAutoPct, setEditAutoPct] = useState('');
  const [away, setAway] = useState(false);
  // Vacation-mode resume timestamp — null = no scheduled return. Read
  // from /api/listings/away on mount so a returning seller sees the
  // chip without flipping the toggle. Updated optimistically when the
  // user picks a return date in the inline picker.
  const [awayUntil, setAwayUntil] = useState(null);
  const [pickingAwayUntil, setPickingAwayUntil] = useState(false);
  const [awayDraftDate, setAwayDraftDate] = useState('');
  // Per-item watcher counts on the seller's stall — bulk-fetched via
  // /api/watchlist/counts after the stall load. Lets the seller see
  // which items have buyer demand at a glance and consider price drops.
  const [stallWatcherCounts, setStallWatcherCounts] = useState({});
  // Per-item buy-order demand (batch 415) — {itemId: {count, bestBid}}.
  // Lets the seller eyeball "N buyers have standing orders on this item
  // · best offer $X" so they can decide to drop their price to match.
  // Bulk-fetched alongside the watcher counts on stall load.
  const [stallBuyOrderDemand, setStallBuyOrderDemand] = useState({});
  // Per-listing PENDING offer summary — keyed by listingId. Null until
  // the first fetch lands so the chip row doesn't flash. Re-fetched
  // alongside the stall load so a row the seller just listed still
  // gets its chip as soon as a buyer bargains.
  const [offerMap, setOfferMap] = useState({});
  // Seller's own response stats (batch 393). Surfaces the exact metrics
  // the public stall page shows to buyers, so the seller can self-audit
  // ("am I slow? is my response rate dragging?"). Nulls until the fetch
  // resolves; typicalResponseMs can be null even after fetch if there
  // aren't 3+ resolved offers yet (noise floor on the backend service).
  const [selfStats, setSelfStats] = useState(null);
  // Verification-progress widget data (batch 595). Shows "N sales until
  // ✓ Verified" — drives the small green/amber progress strip below the
  // response-stats strip. Nulls until the first fetch lands.
  const [verify, setVerify] = useState(null);
  // Earnings summary (batch 605). Gross revenue + sold counts in three
  // windows. Lifetime first, then 30d, then 7d — tells the seller
  // "where am I at" + "how's it going recently" in one glance.
  const [earnings, setEarnings] = useState(null);
  // Batch 994 — honour sb_privacy on the MyStall earnings strip. Shoulder-
  // surfing protection was half-done: the nav wallet + WalletModal masked,
  // but the seller's lifetime revenue / 24h haul / fees-paid were rendered
  // in plain text. maskEarn() wraps every dollar render below.
  const [privacy, setPrivacy] = useState(() => {
    try { return localStorage.getItem('sb_privacy') === '1'; } catch { return false; }
  });
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key === 'sb_privacy') setPrivacy(e.newValue === '1');
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
  const maskEarn = (v) => privacy ? '$•••••' : fmt(v);
  const load = useCallback(() => {
    fetchMyStallWithTotal().then(({ items, total, error }) => {
      if (error) { setStallErr(true); return; }  // retry card, not a permanent spinner / false-empty
      setStallErr(false);
      setStall(items);
      setStallTotal(total);
    });
    fetchBestOfferPerListing().then(m => setOfferMap(m || {}));
    fetchMyVerificationProgress().then(v => setVerify(v || null)).catch(() => {});
    fetchMyStallEarnings().then(e => setEarnings(e || null)).catch(() => {});
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);
  // Fetch the same `/api/listings/stall/{me.id}` payload the public
  // stall page uses — cheap, already cached, single extra round trip.
  // Read only the response-stats block; everything else is redundant
  // with the MyStall listings fetch.
  useEffect(() => {
    if (!me?.id) return;
    let alive = true;
    (async () => {
      try {
        const r = await fetch(`/api/listings/stall/${me.id}`, { credentials: 'same-origin' });
        if (!alive || !r.ok) return;
        const data = await r.json();
        if (data && data.seller) setSelfStats(data.seller);
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [me?.id]);
  // Bulk-fetch watcher counts for the items the seller has on stall —
  // surfaces "👁 N watchers" on each row so a seller sees which items
  // have buyer demand worth pricing aggressively. Empty stall = no fetch.
  useEffect(() => {
    if (!stall || stall.length === 0) {
      setStallWatcherCounts({});
      setStallBuyOrderDemand({});
      return;
    }
    let alive = true;
    const ids = stall.map(l => l?.item?.id).filter(Boolean);
    if (ids.length === 0) return;
    (async () => {
      try {
        const [wRes, bRes] = await Promise.all([
          fetch(`/api/watchlist/counts?ids=${ids.join(',')}`, { credentials: 'same-origin' }),
          fetch(`/api/buy-orders/count/bulk?ids=${ids.join(',')}`, { credentials: 'same-origin' })
        ]);
        if (!alive) return;
        if (wRes.ok) {
          const w = await wRes.json();
          if (w && typeof w === 'object') setStallWatcherCounts(w);
        }
        if (bRes.ok) {
          const b = await bRes.json();
          if (b && typeof b === 'object') setStallBuyOrderDemand(b);
        }
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [stall && stall.map(l => l?.item?.id).join(',')]);
  // Hydrate the away-mode state from the server on mount so the chip is
  // accurate before the user touches anything. /api/listings/away returns
  // {hidden, until} — drives both the toggle's initial position and the
  // "Returns on …" chip. Best-effort; on 401 / network blip the toggle
  // stays at its useState default (false).
  useEffect(() => {
    if (!me) return;
    let alive = true;
    (async () => {
      try {
        const { fetchAwayMode } = await import('./api.js');
        const data = await fetchAwayMode();
        if (!alive || !data) return;
        setAway(!!data.hidden);
        setAwayUntil(data.until || null);
      } catch (_) {}
    })();
    return () => { alive = false; };
  }, [me?.id]);
  // Load sold history lazily when the user clicks the tab — avoids a second
  // list fetch on every modal open for sellers who never touch the history.
  useEffect(() => {
    if (!me || tab !== 'sold' || sold !== null || soldErr) return;
    // alive guard: clicking Retry flips soldErr and re-runs this effect while the
    // previous (failed) fetch's promise may still be settling — without this, the
    // stale resolve could clobber the fresh one's setSold/setSoldRowCount. (self-review fix)
    let alive = true;
    fetchMyStallSoldWithTotal().then(({ items, total, error }) => {
      if (!alive) return;
      if (error) { setSoldErr(true); return; }  // retry card, not false "No sales yet"
      setSold(items);
      setSoldRowCount(total);
    });
    return () => { alive = false; };
  }, [me, tab, sold, soldErr]);
  // Same lazy-load for analytics. The endpoint computes per-item demand
  // (30-day item sales count) + view counts so opening this tab does ONE
  // round-trip and the per-row cells are filled from cached numbers.
  useEffect(() => {
    if (!me || tab !== 'analytics' || analytics !== null) return;
    fetch('/api/listings/my-stall/analytics', { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : [])
      .then(rows => setAnalytics(Array.isArray(rows) ? rows : []))
      .catch(() => setAnalytics([]));
  }, [me, tab, analytics]);

  // Hoisted above the early-return for anon viewers so hook order stays
  // stable across the null→authed transition (React rules of hooks).
  const [bulkAdjustOpen, setBulkAdjustOpen] = useState(false);
  const [bulkAdjustPct, setBulkAdjustPct]   = useState('');
  const [bulkAdjustBusy, setBulkAdjustBusy] = useState(false);
  const [bulkAdjustErr, setBulkAdjustErr]   = useState('');
  // Batch 1167 — bulk-adjust drawer Escape + focus trap + restore-focus.
  // Lives above the early-return for the same hook-count reason as the
  // state above. Stable-callback pattern (busy in a ref) keeps the
  // trap mounted continuously while a bulk-adjust API call is in flight.
  const bulkAdjustPanelRef = useRef(null);
  const bulkAdjustBusyRef  = useRef(bulkAdjustBusy);
  bulkAdjustBusyRef.current = bulkAdjustBusy;
  const bulkAdjustClose = useCallback(() => {
    if (bulkAdjustBusyRef.current) return;
    setBulkAdjustOpen(false);
  }, []);
  useDialogA11y(bulkAdjustPanelRef, bulkAdjustClose, bulkAdjustOpen);

  if (!me) return h(InfoModal, { title: 'My Stall', onClose },
    h(SignInNeededEmptyState, { what: 'your stall' }));
  if (stallErr) return h(InfoModal, { title: 'My Stall', onClose },
    h('div', { className: 'empty-inline' },
      h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'cloud_off', size: 26 })),
      h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
        "Couldn't load your stall"),
      h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px', lineHeight: 1.55 } },
        "Something interrupted the request for your stall. Your listings are safe — this is only a display hiccup."),
      h('div', { style: { display: 'flex', justifyContent: 'center' } },
        h('button', { className: 'btn btn-secondary', onClick: () => { setStallErr(false); load(); } }, 'Retry'))));
  if (stall === null) return h(InfoModal, { title: 'My Stall', onClose }, h('div', { className: 'spinner' }));

  const doCancel = async (listing) => {
    // Batch 883 — take the full listing so the confirm + toast can
    // cite the item name instead of a generic "this listing". Called
    // with an id integer from legacy sites still works through the
    // ternary below.
    const id = typeof listing === 'object' ? listing.id : listing;
    const itemName = typeof listing === 'object' ? listing?.item?.name : null;
    const confirmMsg = itemName
      ? `Remove the listing for "${itemName}"?`
      : 'Remove this listing?';
    if (!confirm(confirmMsg)) return;
    const res = await cancelListing(id);
    if (res && res.error) { toast(res.error, 'err'); return; }
    load();
    onRefresh && onRefresh();
    toast(itemName ? `Listing for "${itemName}" removed.` : 'Listing removed.', 'ok');
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
    // Batch 911 — snapshot the original listing BEFORE the save so the
    // toast can contrast the old price vs. the new, naming the item.
    // Mirrors the "Payout: X (+$Y vs. current)" preview (batch 894)
    // that the same form already shows above the Save button.
    const original = (stall || []).find(l => l.id === id);
    const itemName = original?.item?.name || null;
    const oldPrice = original ? parseFloat(original.price) : null;
    let res;
    try {
      res = await updateStallListing(id, patch);
    } catch (e) {
      // Without this catch a network throw rejected silently — the edit form
      // stayed open with no feedback and the seller assumed the price changed.
      toast('Could not update the listing — network error. Please try again.', 'err');
      return;
    }
    if (res && res.error) { toast(res.error, 'err'); return; }
    setEditing(null);
    load();
    onRefresh && onRefresh();
    const newP = parseFloat(editPrice);
    const delta = (oldPrice != null && isFinite(oldPrice)) ? (newP - oldPrice) : null;
    const deltaStr = delta != null && Math.abs(delta) >= 0.01
      ? ` (${delta >= 0 ? '+' : ''}${fmt(delta)})`
      : '';
    toast(itemName
      ? `"${itemName}" updated to ${fmt(newP)}${deltaStr}.`
      : `Listing updated to ${fmt(newP)}${deltaStr}.`,
      'ok');
  };

  const toggleHidden = async (l) => {
    const res = await updateStallListing(l.id, { hidden: !l.hidden });
    if (res && res.error) { toast(res.error, 'err'); return; }
    load();
    // Batch 919 — confirm toast so the seller sees what flipped.
    // Hiding removes the listing from public grids + stall page
    // without cancelling it; unhiding restores visibility. Previously
    // the flip was silent except for the row re-render.
    const itemName = l?.item?.name;
    const nextHidden = !l.hidden;
    toast(itemName
      ? (nextHidden ? `"${itemName}" hidden — removed from public grids.` : `"${itemName}" unhidden — back on the market.`)
      : (nextHidden ? 'Listing hidden — removed from public grids.' : 'Listing unhidden — back on the market.'),
      'ok');
  };

  const toggleAway = async () => {
    const next = !away;
    setAway(next);
    // Toggle-OFF clears any scheduled return time on the server too.
    if (!next) {
      setAwayUntil(null);
      setPickingAwayUntil(false);
    }
    const res = await setAwayMode(next);
    if (res && res.error) { setAway(!next); toast(res.error, 'err'); return; }
    load();
    // Batch 919 — away mode toggle surfaces the right downstream
    // consequence. On → every active listing is hidden from public
    // stalls + grids until Off; the seller's stall shows an "away"
    // banner instead of the usual grid. Off → listings re-surface.
    toast(next
      ? 'Away mode ON — your listings are hidden from public stalls + grids until you flip it off.'
      : 'Away mode OFF — your listings are public again.',
      'ok');
  };
  const scheduleAwayReturn = async () => {
    if (!awayDraftDate) { setPickingAwayUntil(false); return; }
    // <input type=date> gives YYYY-MM-DD — interpret in the user's TZ
    // and snap to end-of-day so a "back on the 25th" picks up the
    // morning of the 26th conveniently.
    const [y, m, d] = awayDraftDate.split('-').map(n => parseInt(n, 10));
    if (!y || !m || !d) return;
    const until = new Date(y, m - 1, d, 23, 59, 0, 0).getTime();
    if (until <= Date.now()) {
      toast('Pick a future date', 'err');
      return;
    }
    // Optimistic: flip into away mode + stash the local timestamp.
    setAway(true);
    setAwayUntil(until);
    setPickingAwayUntil(false);
    const res = await setAwayMode(true, until);
    if (res && (res.error || res.code)) {
      // Server-side validation failed — rewind both fields.
      setAway(false);
      setAwayUntil(null);
      toast(res.message || res.error || 'Could not schedule vacation', 'err');
      return;
    }
    load();
    toast(`Stall hidden until ${new Date(until).toLocaleDateString()}.`, 'ok');
  };

  const soldTotal = sold ? sold.reduce((s, l) => s + (parseFloat(l.price) || 0), 0) : 0;
  // Net revenue. The 2% platform fee is charged PER TRADE (TradeService.open
  // stores a per-trade feeAmount; release credits price − feeAmount), so the
  // net of a stall is the sum of each sale's payout — not the payout of the
  // summed gross. sellerPayoutTotal does exactly that, with the server's
  // round-the-fee-then-subtract order. Showing both makes the "fee already
  // deducted" hint line concrete instead of vague.
  const soldNet = sellerPayoutTotal(sold ? sold.map(l => l.price) : []);

  // 30-day rolling rollup per CSFloat Visual §23 — sellers want a
  // concrete "last month" figure for tax / cash-flow without filtering
  // the whole list by date. Derived from the same payload so no extra
  // round trip. Picks the single best-selling item by aggregate price
  // as the "top seller" hint.
  const rollup30d = (() => {
    if (!sold || sold.length === 0) return null;
    const cutoff = Date.now() - 30 * 86400_000;
    const recent = sold.filter(l => {
      const ts = l.soldAt || l.updatedAt || l.listedAt || 0;
      return ts >= cutoff;
    });
    if (recent.length === 0) return null;
    const gross = recent.reduce((s, l) => s + (parseFloat(l.price) || 0), 0);
    const byItem = {};
    recent.forEach(l => {
      const key = l.item?.name || 'Item';
      byItem[key] = (byItem[key] || 0) + (parseFloat(l.price) || 0);
    });
    const topItemName = Object.keys(byItem).sort((a, b) => byItem[b] - byItem[a])[0];
    return {
      count:   recent.length,
      gross,
      net:     sellerPayoutTotal(recent.map(l => l.price)),
      topItem: topItemName,
      topItemGross: byItem[topItemName] || 0
    };
  })();

  // Bulk price adjust — apply ±% to every active non-auction listing. Max
  // ±50% per pass (server cap); auctions are skipped server-side.
  // Batch 818 — replaces the legacy window.prompt with an inline
  // drawer featuring quick-adjust chips (−10% / −5% / −1% / +1% / +5%
  // / +10%) plus a typed fallback. A seller with 50 listings no longer
  // has to manually type "-5" into a tiny prompt — one click sets the
  // number, second click confirms. The modal also shows a live
  // "Touches N of M rows" preview so the seller knows exactly how many
  // listings the press will move before committing.
  // (bulkAdjust useStates hoisted above the anon-guard early return to
  // keep hook order stable across the null→authed transition.)
  const openBulkAdjust = () => {
    setBulkAdjustPct('');
    setBulkAdjustErr('');
    setBulkAdjustOpen(true);
  };
  const submitBulkAdjust = async () => {
    // Synchronous re-entry guard (mirrors the buyingRef/submittingRef money
    // pattern) — a rapid double-click must not fire two bulk-adjust passes,
    // which would COMPOUND the percent (two −10% ≈ −19%). The async
    // `bulkAdjustBusy` state alone leaves a pre-re-render window; the ref is
    // checked + set synchronously before any await. Set AFTER validation so a
    // rejected (non-numeric / >50%) submit doesn't leave the latch stuck.
    if (bulkAdjustBusyRef.current) return;
    setBulkAdjustErr('');
    const pct = parseFloat(bulkAdjustPct);
    if (!isFinite(pct) || pct === 0) {
      setBulkAdjustErr('Enter a non-zero percentage');
      return;
    }
    if (Math.abs(pct) > 50) {
      setBulkAdjustErr('Max ±50% per pass');
      return;
    }
    bulkAdjustBusyRef.current = true;
    setBulkAdjustBusy(true);
    try {
      const res = await bulkAdjustStall(pct);
      if (res && (res.error || res.code)) {
        setBulkAdjustErr(res.message || res.error || 'Bulk adjust failed');
        return;
      }
      setBulkAdjustOpen(false);
      // Batch 920 — name the direction + pct + touched/skipped so the
      // seller sees exactly what the bulk pass did. Distinguishes a
      // markup from a discount and cites the % that landed server-side
      // (which may be the clamp'd version of what they typed).
      const dirLabel = pct > 0 ? `marked up ${pct}%` : `discounted ${Math.abs(pct)}%`;
      const touched = res.touched || 0;
      const skipped = res.skipped || 0;
      toast(`${dirLabel} across ${touched} listing${touched === 1 ? '' : 's'}${skipped > 0 ? ` (skipped ${skipped} auction${skipped === 1 ? '' : 's'} / unchanged)` : ''}.`, 'ok');
      load();
      onRefresh && onRefresh();
    } finally { setBulkAdjustBusy(false); }
  };

  // Book value — sum of ask prices across every active listing. The
  // "liquidation ceiling": if every row sold at ask (ignoring the 2%
  // fee), this is the seller's gross. Lives alongside the away toggle
  // so a seller sees their total exposure at a glance.
  const stallBookValue = stall.reduce((s, l) => s + (parseFloat(l.price) || 0), 0);
  const pendingOfferRows = Object.keys(offerMap || {}).length;

  // "Slow movers" derivation — listings older than 14 days with no
  // active offers AND no watchlist demand are stale inventory the
  // seller should consider re-pricing or removing. Pure derivation
  // from data the tab already loads (stall + offerMap + stallWatcherCounts
  // from batch 272). Auctions are excluded — they have their own
  // expires-at lifecycle. Hidden rows are excluded since they're
  // already off the market.
  const SLOW_MOVER_DAYS = 14;
  const slowMovers = (stall || []).filter(l => {
    if (!l) return false;
    if (l.hidden) return false;
    if (l.listingType === 'AUCTION') return false;
    const ageMs = Date.now() - (l.listedAt || 0);
    if (ageMs < SLOW_MOVER_DAYS * 86400_000) return false;
    if (offerMap?.[l.id]) return false;
    if ((stallWatcherCounts[l.item?.id] || 0) > 0) return false;
    return true;
  });

  return h(InfoModal, { title: (
    tab === 'sold'      ? `Sold · My Stall` :
    tab === 'analytics' ? `Analytics · My Stall` :
                          `My Stall · ${stall.length} active`
  ), onClose },
    // Batch 818 — bulk-adjust drawer. Replaces the window.prompt flow
    // triggered by the "⚖ Bulk price adjust" button. Quick-chip picker
    // + typed fallback + live preview of how many listings will be
    // touched.
    bulkAdjustOpen && (() => {
      const buyNowCount = (stall || []).filter(l => l && l.listingType !== 'AUCTION' && !l.hidden).length;
      const setChip = (n) => { setBulkAdjustPct(String(n)); setBulkAdjustErr(''); };
      const pctNum = parseFloat(bulkAdjustPct);
      const previewValid = isFinite(pctNum) && pctNum !== 0 && Math.abs(pctNum) <= 50;
      return h('div', {
        className: 'cart-confirm-backdrop',
        onClick: () => !bulkAdjustBusy && setBulkAdjustOpen(false),
        style: { zIndex: 100 }
      },
        h('div', {
          ref: bulkAdjustPanelRef,
          className: 'cart-confirm-panel',
          style: { maxWidth: 480 },
          onClick: e => e.stopPropagation(),
          role: 'dialog',
          'aria-modal': true,
          'aria-labelledby': 'bulk-adjust-title'
        },
          h('div', { className: 'cart-confirm-title', id: 'bulk-adjust-title' }, '⚖ Bulk price adjust'),
          h('div', { className: 'cart-confirm-sub', style: { marginBottom: 10 } },
            'Apply a percentage to every active ',
            h('strong', null, 'BUY NOW'),
            ' listing. Auctions are skipped. Max ±50% per pass.'),
          h('div', {
            style: {
              display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12,
              justifyContent: 'center'
            }
          },
            [-10, -5, -1, 1, 5, 10].map(n => h('button', {
              key: n,
              className: `price-suggest-chip ${String(n) === bulkAdjustPct ? 'active' : ''}`,
              onClick: () => setChip(n),
              disabled: bulkAdjustBusy,
              style: n < 0
                ? { color: '#fbbf24', borderColor: 'rgba(251,191,36,0.35)' }
                : { color: 'var(--green)', borderColor: 'rgba(34,197,94,0.35)' }
            }, (n > 0 ? '+' : '') + n + '%'))),
          h('div', { className: 'wallet-input-label', style: { marginBottom: 4 } }, 'Or type a value'),
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 6, marginBottom: 10 } },
            h('input', {
              className: 'wallet-amount-input',
              type: 'number', step: '0.5', min: '-50', max: '50',
              inputMode: 'decimal',
              'aria-label': 'Bulk adjust percent',
              style: { flex: 1 },
              placeholder: '-5 for 5% off',
              value: bulkAdjustPct,
              onChange: e => { setBulkAdjustPct(e.target.value); setBulkAdjustErr(''); }
            }),
            h('span', { style: { fontSize: 14, color: 'var(--text-muted)' } }, '%')
          ),
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 10, lineHeight: 1.5 } },
            buyNowCount === 0
              ? 'You have no BUY NOW listings to adjust right now.'
              : previewValid
                ? (() => {
                    const direction = pctNum > 0 ? 'mark up' : 'discount';
                    return h('span', null, 'Will ', h('strong', null, direction), ' all ',
                      h('strong', null, buyNowCount), ' BUY NOW listing' + (buyNowCount === 1 ? '' : 's'),
                      ' by ', h('strong', null, Math.abs(pctNum) + '%'), '.');
                  })()
                : 'Pick a chip or type a non-zero percentage from -50 to +50.'),
          bulkAdjustErr && h('div', { className: 'wallet-error', style: { marginBottom: 10 } }, bulkAdjustErr),
          h('div', { style: { display: 'flex', gap: 10, justifyContent: 'flex-end' } },
            h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)', padding: '6px 14px', fontSize: 12 },
              disabled: bulkAdjustBusy,
              onClick: () => setBulkAdjustOpen(false)
            }, 'Cancel'),
            h('button', {
              className: 'btn btn-accent',
              style: { padding: '6px 14px', fontSize: 12 },
              disabled: bulkAdjustBusy || !previewValid || buyNowCount === 0,
              onClick: submitBulkAdjust
            }, bulkAdjustBusy ? 'Applying…' : (previewValid ? (pctNum > 0 ? `+${pctNum}% to ${buyNowCount}` : `${pctNum}% to ${buyNowCount}`) : 'Apply'))
          )
        )
      );
    })(),
    // Self response-stats strip (batch 393). Renders the exact metrics
    // buyers see on the public stall page so the seller can self-audit.
    // Only shows when at least one metric is populated (backend nulls
    // below the 3/5 offer noise floors) — silent for new sellers so the
    // empty-stall view stays clean.
    selfStats && (selfStats.typicalResponseMs != null || selfStats.responseRatePct != null) && (() => {
      const hrs = selfStats.typicalResponseMs != null
        ? (selfStats.typicalResponseMs / 3_600_000)
        : null;
      const timeLabel = hrs == null
        ? null
        : hrs < 1
          ? Math.max(1, Math.round(hrs * 60)) + 'm'
          : hrs < 24
            ? Math.round(hrs) + 'h'
            : Math.round(hrs / 24) + 'd';
      const ratePct = selfStats.responseRatePct != null
        ? Math.round(selfStats.responseRatePct) : null;
      const slow = hrs != null && hrs > 24;
      const lowRate = ratePct != null && ratePct < 70;
      return h('div', {
        style: {
          margin: '0 0 14px', padding: '10px 14px',
          background: 'var(--bg-elevated)',
          border: '1px solid ' + (slow || lowRate ? 'rgba(251,191,36,0.35)' : 'var(--border)'),
          borderRadius: 8, display: 'flex', alignItems: 'center',
          gap: 14, flexWrap: 'wrap', fontSize: 12
        },
        title: 'How your stall looks to buyers — exposed on the public stall hero. Improving both metrics lifts offer acceptance.'
      },
        h('span', { style: { fontSize: 14 } }, 'ⓘ'),
        h('span', { style: { color: 'var(--text-secondary)' } }, 'Buyer-visible stats ·'),
        timeLabel && h('span', null,
          'Typically responds in ',
          h('strong', {
            style: { color: slow ? '#fbbf24' : 'var(--accent)' }
          }, timeLabel)
        ),
        timeLabel && ratePct != null && h('span', { style: { color: 'var(--text-muted)' } }, '·'),
        ratePct != null && h('span', null,
          h('strong', { style: { color: lowRate ? '#fbbf24' : 'var(--accent)' } }, ratePct + '%'),
          ' response rate'
        ),
        (slow || lowRate) && h('span', {
          style: {
            marginLeft: 'auto', fontSize: 11, color: '#fbbf24', fontWeight: 600
          }
        }, slow ? 'Slow vs peers' : 'Low response rate')
      );
    })(),
    // Verification progress (batch 595). Silent for brand-new sellers
    // with 0 sales to keep the "welcome / empty stall" view clean.
    // Verified sellers see a single green chip confirming the badge;
    // unverified sellers see a progress strip with the exact remaining
    // threshold so there's no guessing.
    verify && verify.soldCount > 0 && h('div', {
      style: {
        margin: '0 0 14px', padding: '10px 14px',
        background: 'var(--bg-elevated)',
        border: '1px solid ' + (verify.verified
          ? 'rgba(74,222,128,0.4)'
          : 'rgba(96,165,250,0.35)'),
        borderRadius: 8, display: 'flex', alignItems: 'center',
        gap: 12, flexWrap: 'wrap', fontSize: 12
      },
      title: 'Verified sellers get a ✓ badge on every listing they own. ' +
             'Requirements: 10+ completed sales AND a rating of 4★ or higher (or no reviews yet).'
    },
      h('span', { style: { fontSize: 14 } }, verify.verified ? '✅' : '—'),
      h('span', { style: { color: 'var(--text-secondary)' } },
        verify.verified ? 'You\'re a verified seller · ' : 'Verified Seller progress ·'),
      !verify.verified && verify.salesNeeded > 0 && h('span', null,
        h('strong', { style: { color: 'var(--accent)' } }, verify.salesNeeded),
        ' more sale' + (verify.salesNeeded === 1 ? '' : 's'),
        ' (',
        h('strong', null, verify.soldCount + '/' + verify.thresholdSales),
        ')'
      ),
      !verify.verified && verify.salesNeeded === 0 && !verify.ratingOk && h('span', null,
        'Lift your rating to ',
        h('strong', { style: { color: '#fbbf24' } }, verify.thresholdRating + '★'),
        ' · currently ',
        h('strong', null,
          verify.ratingAverage != null ? verify.ratingAverage + '★' : '—',
          verify.ratingCount > 0 ? ' (' + verify.ratingCount + ' reviews)' : ''
        )
      ),
      verify.verified && h('span', { style: { color: 'var(--green)', fontWeight: 600 } },
        'Verified badge active'
      )
    ),
    // Earnings strip (batch 605). Three chips: lifetime / 30d / 7d —
    // revenue + sold count per window. Silent until at least one sale
    // has happened so the "welcome new seller" view stays clean.
    earnings && earnings.soldLifetime > 0 && h('div', {
      style: {
        margin: '0 0 14px', padding: '10px 14px',
        background: 'var(--bg-elevated)',
        border: '1px solid var(--border)',
        borderRadius: 8, display: 'flex', alignItems: 'center',
        gap: 18, flexWrap: 'wrap', fontSize: 12
      },
      title: 'Gross revenue shown (listing price the buyer paid). ' +
             'Net-of-fee credits land in your Wallet transaction history.'
    },
      h('span', { style: { fontSize: 14 } }, 'ⓘ'),
      h('span', { style: { color: 'var(--text-secondary)' } }, 'Your earnings ·'),
      h('span', null,
        'Lifetime: ',
        h('strong', { style: { color: 'var(--accent)' } }, maskEarn(earnings.lifetimeRevenue)),
        ' · ',
        earnings.soldLifetime + ' sold'
      ),
      earnings.sold30d > 0 && h('span', { style: { color: 'var(--text-secondary)' } }, '·'),
      earnings.sold30d > 0 && h('span', null,
        'Past 30d: ',
        h('strong', null, maskEarn(earnings.revenue30d)),
        ' · ',
        earnings.sold30d + ' sold'
      ),
      earnings.sold7d > 0 && h('span', { style: { color: 'var(--text-secondary)' } }, '·'),
      earnings.sold7d > 0 && h('span', null,
        'Past 7d: ',
        h('strong', { style: { color: 'var(--green)' } }, maskEarn(earnings.revenue7d)),
        ' · ',
        earnings.sold7d + ' sold'
      ),
      // Batch 872 — past-24h chip. Fresh "today's haul" figure so
      // sellers see live feedback on pricing experiments without
      // having to wait for the 7d window to move. Only renders when
      // there's been at least one sale in the window so a dormant
      // seller's strip stays uncluttered.
      earnings.sold24h > 0 && h('span', { style: { color: 'var(--text-secondary)' } }, '·'),
      earnings.sold24h > 0 && h('span', {
        title: 'Revenue + sold count across the last 24 hours — live pricing-experiment feedback'
      },
        'Past 24h: ',
        h('strong', { style: { color: 'var(--green)' } }, maskEarn(earnings.revenue24h)),
        ' · ',
        earnings.sold24h + ' sold'
      ),
      // Batch 709 — lifetime fees chip. Only rendered when the seller
      // has actually paid fees (soldLifetime > 0 and fees > 0), keeps
      // the row clean for first-time sellers. Muted color because this
      // is a cost, not a gain.
      parseFloat(earnings.lifetimeFees || 0) > 0 && h('span', { style: { color: 'var(--text-secondary)' } }, '·'),
      parseFloat(earnings.lifetimeFees || 0) > 0 && h('span', {
        title: 'Platform fees (2% of each settled sale) collected by SkinBox across your lifetime. Counted only on VERIFIED trades.'
      },
        'Fees paid: ',
        h('strong', { style: { color: 'var(--text-muted)' } }, maskEarn(earnings.lifetimeFees))
      )
    ),
    h('div', { className: 'stall-toolbar' },
      h('button', {
        className: `toggle-switch ${away ? '' : 'off'}`,
        style: { display: 'inline-block', verticalAlign: 'middle', marginRight: 10 },
        onClick: toggleAway
      }),
      h('span', { style: { fontSize: 12, color: 'var(--text-secondary)' } },
        'Away Mode — ', away ? 'all listings hidden' : 'listings visible'
      ),
      // Share-stall quick link (batch 406). Tries the native Web Share
      // API first (native share sheet on mobile + desktop Chrome/Safari)
      // and falls back to clipboard. Gives sellers one-click posting to
      // Discord/Twitter/Steam groups. Silent success via the ✓ flash.
      //
      // Batch 878 — personalise the share payload with the seller's
      // display name + pitch text (same treatment as batch 877 on the
      // public stall's ShareStallButton). A Discord embed preview
      // reading "@Alice's stall on SkinBox · …" is much clickier than
      // the generic "My SkinBox stall".
      h('button', {
        className: 'btn btn-ghost',
        style: { marginLeft: 10, padding: '4px 10px', fontSize: 11, border: '1px solid var(--border)' },
        title: `Share your public stall link (${window.location.origin}/stall/${me.id})`,
        onClick: async (e) => {
          const url = `${window.location.origin}/stall/${me.id}`;
          const title = me.displayName
            ? `${me.displayName}'s stall on SkinBox`
            : 'My SkinBox stall';
          const text  = me.displayName
            ? `Browse ${me.displayName}'s listings on SkinBox — s&box skin marketplace with auctions, buy orders, and secure escrow.`
            : 'Browse my SkinBox stall — s&box skin marketplace.';
          const btn = e.currentTarget;
          const flash = () => {
            const prev = btn.textContent;
            btn.textContent = 'Copied';
            btn.style.color = 'var(--green)';
            setTimeout(() => { btn.textContent = prev; btn.style.color = ''; }, 1400);
          };
          if (typeof navigator.share === 'function') {
            try {
              await navigator.share({ title, text, url });
              return;
            } catch (err) {
              if (err && err.name === 'AbortError') return;
            }
          }
          try {
            if (navigator.clipboard?.writeText) {
              await navigator.clipboard.writeText(url);
              flash();
            } else {
              window.prompt('Copy this stall link:', url);
            }
          } catch (_) {
            window.prompt('Copy this stall link:', url);
          }
        }
      }, 'Share stall'),
      // Vacation-return chip + inline date picker. When a return time is
      // scheduled, show a "Returns on …" chip; otherwise show a small
      // "Schedule return" link that opens a date input. Toggle-OFF clears
      // both ⇒ the chip + picker hide. Replaces the per-seller "I'll be
      // back" support ticket workflow.
      away && awayUntil && h('span', {
        style: {
          marginLeft: 10, fontSize: 11, fontWeight: 700, color: '#fbbf24',
          background: 'rgba(251,191,36,0.12)', border: '1px solid rgba(251,191,36,0.35)',
          padding: '4px 10px', borderRadius: 6
        },
        title: 'Listings auto-resume at this date — the hourly sweep flips them back on.'
      },
        'Returns ', new Date(awayUntil).toLocaleDateString()
      ),
      away && !awayUntil && !pickingAwayUntil && h('button', {
        className: 'btn btn-ghost',
        style: { marginLeft: 10, fontSize: 11, padding: '3px 10px', border: '1px solid var(--border)' },
        onClick: () => {
          // Pre-fill with one week from today as a friendly default.
          const d = new Date(Date.now() + 7 * 86400_000);
          const iso = d.toISOString().slice(0, 10);
          setAwayDraftDate(iso);
          setPickingAwayUntil(true);
        },
        title: 'Pick a date when your stall should auto-resume'
      }, 'Schedule return'),
      pickingAwayUntil && h('span', {
        style: { marginLeft: 10, display: 'inline-flex', alignItems: 'center', gap: 6 }
      },
        h('input', {
          type: 'date',
          value: awayDraftDate,
          min: new Date().toISOString().slice(0, 10),
          max: new Date(Date.now() + 90 * 86400_000).toISOString().slice(0, 10),
          onChange: e => setAwayDraftDate(e.target.value),
          style: { fontSize: 11, padding: '3px 6px' }
        }),
        h('button', {
          className: 'btn btn-accent',
          style: { fontSize: 11, padding: '3px 10px' },
          onClick: scheduleAwayReturn
        }, 'Set'),
        h('button', {
          className: 'btn btn-ghost',
          style: { fontSize: 11, padding: '3px 8px', border: '1px solid var(--border)' },
          onClick: () => setPickingAwayUntil(false)
        }, 'Cancel')
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
        // Privacy mode masks every seller dollar figure (see maskEarn
        // declaration) — the earnings hero strip above already masks,
        // so book value must too or the leak just moves down one chip.
        'Book value · ', maskEarn(stallBookValue)
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
        pendingOfferRows, ' ', pendingOfferRows === 1 ? 'listing' : 'listings', ' with offers'
      ),
      h('div', { style: { flex: 1 } }),
      stall.length > 0 && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
        onClick: openBulkAdjust,
        title: 'Apply a ±% adjustment to every active BUY NOW listing'
      }, '⚖ Bulk price adjust'),
      // Bulk-cancel (batch 375) — "✕ Cancel all active" shortcut for
      // sellers taking a break / quitting the platform. Auctions with
      // live bids are skipped server-side to avoid noisy fanout; the
      // toast surfaces that summary so the seller sees what happened.
      stall.length > 1 && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.35)', color: 'var(--red)',
                 padding: '6px 12px', fontSize: 11, marginLeft: 6 },
        onClick: async () => {
          if (!confirm(`Cancel all ${stall.length} active listings? Auctions with live bids are kept (cancel those individually if needed). Pending offers on the affected listings will be rejected with a courtesy notification.`)) return;
          try {
            const r = await fetch('/api/listings/my-stall', { method: 'DELETE', credentials: 'same-origin' });
            const data = await r.json();
            if (!r.ok) { toast(data?.message || 'Bulk cancel failed', 'err'); return; }
            const parts = [`Cancelled ${data.cancelled || 0}`];
            if (data.skippedAuctions) parts.push(`${data.skippedAuctions} auction${data.skippedAuctions === 1 ? '' : 's'} with live bids skipped`);
            if (data.failed)          parts.push(`${data.failed} failed`);
            toast(parts.join(' · ') + '.', 'ok');
            await onRefresh();
          } catch (_) { toast('Network error cancelling listings.', 'err'); }
        },
        title: 'Cancel every active BUY_NOW listing and every no-bid auction'
      }, '✕ Cancel all')
    ),
    // Match-ready buy-orders summary (batch 418). Aggregates the per-row
    // demand chip from batch 415 into a single header prompt so the
    // seller sees "3 listings have a buyer ready to auto-match" without
    // scanning the whole stall. Silent when nothing is match-ready so
    // a cold stall doesn't render a noisy 0-chip. Clicking scrolls the
    // stall list into view — sellers can then use the per-row Match
    // buttons to close.
    tab === 'active' && stall.length > 0 && (() => {
      let matchReady = 0;   // best bid >= ask
      let nearMatch  = 0;   // best bid < ask (Match button appears)
      stall.forEach(l => {
        const demand = stallBuyOrderDemand[l?.item?.id];
        if (!demand || !(demand.count > 0)) return;
        const priced = parseFloat(l.price) || 0;
        const best   = parseFloat(demand.bestBid) || 0;
        if (!(priced > 0 && best > 0)) return;
        if (best >= priced) matchReady++;
        else nearMatch++;
      });
      if (matchReady === 0 && nearMatch === 0) return null;
      return h('div', {
        style: {
          margin: '0 0 12px', padding: '8px 12px',
          background: matchReady > 0 ? 'rgba(34,197,94,0.08)' : 'rgba(251,191,36,0.08)',
          border: '1px solid ' + (matchReady > 0 ? 'rgba(34,197,94,0.35)' : 'rgba(251,191,36,0.35)'),
          borderRadius: 8, fontSize: 12, lineHeight: 1.5,
          color: matchReady > 0 ? 'var(--green)' : '#fbbf24'
        }
      },
        matchReady > 0 && h('span', null,
          '—', h('strong', null, matchReady),
          ' listing', matchReady === 1 ? '' : 's',
          ' match a buyer\'s standing order right now — sell-through is automatic. '),
        nearMatch > 0 && h('span', null,
          matchReady > 0 ? '+ ' : '—',
          h('strong', null, nearMatch),
          ' listing', nearMatch === 1 ? ' has' : 's have',
          ' buyer demand below your ask. Use the ',
          h('strong', null, 'Match'),
          ' button on each row to drop to the top bid.')
      );
    })(),
    // Active vs Sold vs Analytics tabs. Sold tab reveals seller's own sale
    // history with gross revenue (pre-fee). Matches CSFloat's "My Sales".
    // WAI-ARIA tabs pattern + Arrow/Home/End keyboard nav, mirroring
    // WalletModal (modals.js:12906) and LoadoutModal so /me/stall feels
    // identical to /wallet and /loadout for keyboard / screen-reader users.
    (() => {
      const TABS = ['active', 'sold', 'analytics'];
      const labels = { active: 'Active', sold: 'Sold', analytics: 'Analytics' };
      const counts = { active: stall.length, sold: sold ? sold.length : null, analytics: analytics ? analytics.length : null };
      const pickTab = (id) => { setTab(id); navigate('/me/stall/' + id); };
      const onKey = (e) => {
        if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
        e.preventDefault();
        const idx = TABS.indexOf(tab);
        let n = idx;
        if (e.key === 'ArrowRight') n = (idx + 1) % TABS.length;
        else if (e.key === 'ArrowLeft') n = (idx - 1 + TABS.length) % TABS.length;
        else if (e.key === 'Home') n = 0;
        else if (e.key === 'End') n = TABS.length - 1;
        pickTab(TABS[n]);
      };
      return h('div', { className: 'mystall-tabs', role: 'tablist', 'aria-label': 'My stall sections' },
        TABS.map(id => h('button', {
          key: id,
          className: `offer-tab ${tab === id ? 'active' : ''}`,
          role: 'tab',
          id: 'mystall-tab-' + id,
          'aria-selected': tab === id,
          'aria-controls': 'mystall-panel-' + id,
          tabIndex: tab === id ? 0 : -1,
          onClick: () => pickTab(id),
          onKeyDown: onKey
        }, labels[id], counts[id] != null && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, counts[id])))
      );
    })(),
    // Tab-switched content region. Wrapped in a single role="tabpanel"
    // node (id + aria-labelledby track the active tab) so each MyStall
    // tab button has a matching panel target — mirroring the Profile /
    // Offers / Wallet tab panels. The match-ready prompt above stays
    // outside this panel: it's a header summary, not tab content.
    h('div', {
      role: 'tabpanel',
      id: 'mystall-panel-' + tab,
      'aria-labelledby': 'mystall-tab-' + tab
    },
    tab === 'analytics' && (
      analytics === null
        ? h('div', { className: 'spinner' })
        : analytics.length === 0
          ? h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'analytics', size: 26 })),
              h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'No analytics yet'),
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 16px', lineHeight: 1.55 } },
                'List an item from your inventory and stats land here — view counts, item supply, 30-day demand for the same SKU across the whole marketplace, and how your price compares to the floor.'),
              h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
                h('a', {
                  className: 'btn btn-accent',
                  href: '/sell',
                  style: { padding: '10px 18px', fontWeight: 700 }
                }, '＋ List an item →')))
          : h('div', { className: 'analytics-grid', style: { display: 'grid', gap: 8, marginTop: 8 } },
              // Toolbar — same CSV-export affordance the sold tab carries
              // so a seller running >50 listings can rank-sort offline in
              // Excel / build a re-pricing pivot on category-level demand.
              // Mirror the .mystall-sold-summary visual to keep the two
              // tabs consistent.
              h('div', { className: 'mystall-sold-summary' },
                h('span', { className: 'mystall-sold-label' }, 'Per-listing analytics · ' + analytics.length + ' active'),
                h('span', { className: 'mystall-sold-hint' },
                  '· View counts + 30-day demand + price-vs-floor delta'),
                h('a', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11, marginLeft: 'auto' },
                  href: '/api/listings/my-stall/analytics.csv',
                  title: 'Download the per-listing analytics for every active listing in your stall (capped at 500 rows) as CSV — useful for offline rank-sorting / pivot-table work in Excel.'
                }, '⇣ CSV')
              ),
              h('div', { style: { display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto auto auto auto', gap: 12, padding: '8px 12px', fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-muted)', borderBottom: '1px solid var(--border)' } },
                h('div', null, 'Item'),
                h('div', { style: { textAlign: 'right' } }, 'My Price'),
                h('div', { style: { textAlign: 'right' } }, 'vs Floor'),
                h('div', { style: { textAlign: 'right' } }, 'Views'),
                h('div', { style: { textAlign: 'right' } }, '30d Sold')),
              analytics.map(row => h('div', {
                key: row.listingId,
                style: { display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto auto auto auto', gap: 12, padding: '8px 12px', fontSize: 13, alignItems: 'center', borderBottom: '1px solid var(--border)' }
              },
                h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 } },
                  row.imageUrl && h('img', { src: row.imageUrl, alt: '', style: { width: 32, height: 32, borderRadius: 4, objectFit: 'cover', flex: '0 0 auto' } }),
                  h('div', { style: { minWidth: 0, overflow: 'hidden' } },
                    h('div', { style: { fontWeight: 600, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, row.itemName || `#${row.itemId}`),
                    h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, `${row.itemRarity || '—'} · ${row.category || '—'}`))),
                h('div', { style: { textAlign: 'right', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", fontWeight: 600 } }, fmt(row.price)),
                h('div', { style: { textAlign: 'right', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", fontSize: 12, color: row.floorDelta == null ? 'var(--text-muted)' : (parseFloat(row.floorDelta) > 0 ? 'var(--down)' : (parseFloat(row.floorDelta) < 0 ? 'var(--up)' : 'var(--text-muted)')) } },
                  row.floorDelta == null ? '—' : (parseFloat(row.floorDelta) >= 0 ? '+' : '') + fmt(row.floorDelta)),
                h('div', { style: { textAlign: 'right', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", color: 'var(--text-secondary)' } }, Number(row.viewCount || 0).toLocaleString()),
                h('div', { style: { textAlign: 'right', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace", fontWeight: 600, color: row.itemSales30d > 0 ? 'var(--text-primary)' : 'var(--text-muted)' } }, row.itemSales30d > 0 ? row.itemSales30d : '—')
              ))
            )
    ),
    tab === 'sold' && (
      soldErr
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'cloud_off', size: 26 })),
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              "Couldn't load your sold history"),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px', lineHeight: 1.55 } },
              "Something interrupted the request for your settled sales. Your payout history is safe — this is only a display hiccup."),
            h('div', { style: { display: 'flex', justifyContent: 'center' } },
              h('button', { className: 'btn btn-secondary', onClick: () => setSoldErr(false) }, 'Retry')))
        : sold === null
          ? h('div', { className: 'spinner' })
          : sold.length === 0
          ? h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
              h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
                "No sales yet"),
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px', lineHeight: 1.55 } },
                "Settled sales land here with the gross, platform fee, and payout. First list an item; a sold listing shows up in this tab once the buyer confirms receipt."),
              // Batch 914 — same CTA pattern as the empty-active stall
              // state. One click to /sell gives the seller an on-ramp
              // back to the flow that seeds the future sales history.
              h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
                h('a', {
                  className: 'btn btn-accent',
                  href: '/sell',
                  style: { padding: '10px 18px', fontWeight: 700 }
                }, '＋ List an item →')
              )
            )
          : h('div', null,
              // Batch 1032 — overflow banner when the server's 200-row
              // cap trims the payload. Silent for ordinary sellers
              // (soldRowCount == sold.length); only surfaces once a
              // prolific seller crosses the cap.
              soldRowCount != null && sold.length > 0 && soldRowCount > sold.length && h('div', {
                style: {
                  margin: '0 0 12px', padding: '10px 14px', fontSize: 12,
                  background: 'rgba(30,165,255,0.08)',
                  border: '1px solid var(--accent-border)',
                  borderRadius: 8, color: 'var(--text-secondary)',
                  display: 'flex', alignItems: 'center', gap: 10
                },
                title: 'Server caps the list at 200 rows. Full history lives in the CSV export.'
              },
                h('span', null, '—',
                  'Showing most recent ',
                  h('strong', { style: { color: 'var(--text-primary)' } }, sold.length),
                  ' of ',
                  h('strong', { style: { color: 'var(--accent)' } }, soldRowCount),
                  ' sales. Use CSV export for the full history.')
              ),
              // 30-day rollup card — CSFloat Visual §23. Only rendered when
              // the seller has at least one sale in the last 30 days so a
              // dormant stall doesn't show a "$0.00 · 0 sales" stub. Sits
              // above the all-time gross summary so seasonal sellers see
              // the recent number first.
              rollup30d && h('div', {
                style: {
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                  gap: 10, marginBottom: 14
                }
              },
                h('div', { className: 'admin-stat' },
                  h('div', { className: 'admin-stat-label' }, '30d sales'),
                  h('div', { className: 'admin-stat-val' }, rollup30d.count)
                ),
                h('div', { className: 'admin-stat' },
                  h('div', { className: 'admin-stat-label' }, '30d gross'),
                  // Privacy-mask seller revenue — consistent with maskEarn
                  // on the earnings hero strip above.
                  h('div', { className: 'admin-stat-val' }, maskEarn(rollup30d.gross))
                ),
                h('div', { className: 'admin-stat' },
                  h('div', { className: 'admin-stat-label' }, '30d net'),
                  h('div', { className: 'admin-stat-val green' }, maskEarn(rollup30d.net))
                ),
                rollup30d.topItem && h('div', { className: 'admin-stat', title: `Best-selling item over the last 30d by total revenue (${maskEarn(rollup30d.topItemGross)})` },
                  h('div', { className: 'admin-stat-label' }, 'Top seller'),
                  h('div', {
                    className: 'admin-stat-val',
                    style: { fontSize: 13, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }
                  }, rollup30d.topItem)
                )
              ),
              h('div', { className: 'mystall-sold-summary' },
                h('span', { className: 'mystall-sold-label' }, 'Gross · last ' + sold.length + ' sales'),
                // Privacy-mask seller revenue — consistent with maskEarn
                // on the earnings hero strip above.
                h('span', { className: 'mystall-sold-total' }, maskEarn(soldTotal)),
                h('span', { className: 'mystall-sold-hint' },
                  '· Net after 2% fee ',
                  h('span', { style: { color: 'var(--accent)', fontWeight: 700, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, maskEarn(soldNet))
                ),
                h('span', { style: { marginLeft: 'auto' } },
                  h(DateRangeFilter, {
                    from: soldDateFrom,
                    to:   soldDateTo,
                    onChange: ({ from, to }) => { setSoldDateFrom(from); setSoldDateTo(to); }
                  })
                ),
                h('a', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11, marginLeft: 8 },
                  href: appendDateRange('/api/listings/my-stall/sold.csv', soldDateFrom, soldDateTo),
                  title: 'Download the last 1,000 of your settled sales as CSV — pairs with the wallet transactions export for accounting. Honours the date filter when set.'
                }, '⇣ CSV')
              ),
              h('div', { className: 'recent-sales-list' },
                sold.map(l => h('div', { key: l.id, className: 'recent-sales-row' },
                  h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 } },
                    h('div', { className: 'item-thumb', style: { width: 28, height: 28, flexShrink: 0 } }, h(ItemImage, { item: l.item, variant: 'mini' })),
                    h('span', { style: { fontSize: 12.5, color: 'var(--text-primary)', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } }, l.item?.name || 'Item')
                  ),
                  h('span', { className: 'recent-sales-price' }, fmt(l.price)),
                  h('span', { className: 'recent-sales-time' }, timeAgo(l.soldAt || l.updatedAt || l.listedAt))
                ))
              )
            )
    ),
    // Slow movers banner — listings sitting >14 days with zero offers
    // and zero watchers are stale inventory worth re-pricing or pulling.
    // Amber tint, dismissible-via-action (re-price one of the items
    // makes the row drop out automatically). Renders ABOVE the stall
    // list so the seller sees it on tab open.
    tab === 'active' && slowMovers.length > 0 && h('div', {
      style: {
        margin: '0 0 14px 0', padding: '10px 14px',
        background: 'rgba(251,191,36,0.08)',
        border: '1px solid rgba(251,191,36,0.35)',
        borderRadius: 8, display: 'flex', alignItems: 'center', gap: 10
      }
    },
      h('span', { style: { fontSize: 16 } }, '⏳'),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', { style: { fontSize: 12, fontWeight: 700, color: '#fbbf24' } },
          slowMovers.length, ' slow mover',
          slowMovers.length === 1 ? '' : 's',
          ' — listed >14 days with no offers, no watchers'),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 } },
          'Consider re-pricing (try the bulk-adjust button above) or removing these to free up your stall slots.',
          slowMovers.length <= 5 && ' Names: ',
          slowMovers.length <= 5 && h('span', { style: { color: 'var(--text-secondary)', fontWeight: 600 } },
            slowMovers.map(l => l.item?.name).filter(Boolean).join(', '))
        )
      )
    ),
    // Batch 1033 — overflow banner when the server's 500-row cap trims
    // the Active tab payload. Silent for ordinary sellers; only
    // surfaces once a prolific seller crosses the cap so they know
    // the rest of their stall lives in the CSV export.
    tab === 'active' && stallTotal != null && stall.length > 0 && stallTotal > stall.length && h('div', {
      style: {
        margin: '0 0 12px', padding: '10px 14px', fontSize: 12,
        background: 'rgba(30,165,255,0.08)',
        border: '1px solid var(--accent-border)',
        borderRadius: 8, color: 'var(--text-secondary)',
        display: 'flex', alignItems: 'center', gap: 10
      },
      title: 'Server caps the list at 500 rows. Download the CSV to see every active listing.'
    },
      h('span', null, '—',
        'Showing most recent ',
        h('strong', { style: { color: 'var(--text-primary)' } }, stall.length),
        ' of ',
        h('strong', { style: { color: 'var(--accent)' } }, stallTotal),
        ' active listings. Use ⇣ Active CSV for the full list.')
    ),
    // Batch 681 — active-listings CSV download pill. Only shown when
    // the seller actually has listings to export. Small, out-of-the-way
    // so it doesn't compete with the price/hide/cancel controls.
    tab === 'active' && stall.length > 0 && h('div', {
      style: { display: 'flex', justifyContent: 'flex-end', margin: '0 0 10px 0' }
    },
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: '/api/listings/my-stall/active.csv',
        title: 'Download your active listings as CSV — useful for bulk-reprice offline or reconciling against an inventory sheet'
      }, '⇣ Active CSV')
    ),
    // Batch 772 — active-tab filter chips. Counts compute over the
    // full stall so the chip labels stay truthful regardless of
    // which chip is active. Only rendered on the active tab and when
    // there's more than one type of listing to bother filtering.
    tab === 'active' && stall.length > 3 && (() => {
      const counts = {
        ALL:      stall.length,
        BUY_NOW:  stall.filter(l => l.listingType !== 'AUCTION' && !l.hidden).length,
        AUCTION:  stall.filter(l => l.listingType === 'AUCTION' && !l.hidden).length,
        HIDDEN:   stall.filter(l => l.hidden).length
      };
      // Skip the chip row when the seller only has one kind — no value in
      // offering a filter with a single non-zero bucket.
      const nonZero = ['BUY_NOW','AUCTION','HIDDEN'].filter(k => counts[k] > 0).length;
      if (nonZero <= 1) return null;
      return h('div', { style: { display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 } },
        [
          { id: 'ALL',     label: 'All' },
          { id: 'BUY_NOW', label: 'Buy Now' },
          { id: 'AUCTION', label: 'Auctions' },
          { id: 'HIDDEN',  label: 'Hidden' }
        ].map(opt => h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${stallTypeFilter === opt.id ? 'active' : ''}`,
          'aria-pressed': stallTypeFilter === opt.id,
          onClick: () => setStallTypeFilterPersist(opt.id),
          disabled: counts[opt.id] === 0 && opt.id !== 'ALL'
        }, `${opt.label} · ${counts[opt.id]}`))
      );
    })(),
    tab === 'active' && (stall.length === 0
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            'Your stall is empty'),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px', lineHeight: 1.55 } },
            "List an item to start selling — pick from your Steam inventory or anything you bought on SkinBox. Bulk-list at one price is available too."),
          // Batch 914 — concrete CTA on an empty stall. Matches the
          // pattern from the empty-cart (batch 427), empty-watchlist
          // (batch 428), empty-buy-orders (batch 901), empty-public-
          // stall (batch 900), and empty-transactions (batch 902)
          // states — every dead-end gets at least one actionable
          // on-ramp instead of bare instructional copy.
          h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
            h('a', {
              className: 'btn btn-accent',
              href: '/sell',
              style: { padding: '10px 18px', fontWeight: 700 }
            }, '＋ Sell an item →'),
            h('a', {
              className: 'btn btn-ghost',
              href: '/db',
              style: { border: '1px solid var(--border)', padding: '10px 18px' },
              title: 'Research what sells on SkinBox — volume, supply, and current floor per item.'
            }, 'Browse catalogue')
          )
        )
      : h('div', { className: 'stall-list' },
          (() => {
            // Apply the batch-772 type filter. ALL keeps the existing
            // behaviour; HIDDEN narrows to hidden rows regardless of
            // listing type; BUY_NOW / AUCTION narrow to the matching
            // non-hidden listings.
            const filteredByType = stallTypeFilter === 'ALL' ? stall
              : stallTypeFilter === 'HIDDEN' ? stall.filter(l => l.hidden)
              : stallTypeFilter === 'AUCTION' ? stall.filter(l => l.listingType === 'AUCTION' && !l.hidden)
              : stall.filter(l => l.listingType !== 'AUCTION' && !l.hidden);
            // Drop any listing whose item DTO didn't hydrate (null `item`).
            // The stall rows below dereference l.item.name / l.item.id
            // UNGUARDED (12659/12682/12713/12719 — note 12699 already uses
            // l.item?.viewCount, so the nullability was known but only
            // half-guarded), so a single null-item row throws a TypeError
            // into the ErrorBoundary and wedges the entire MyStall modal
            // UNRECOVERABLY. Same crash class + fix as the WatchlistModal
            // null-item guard (commit 7166d3e). Filtering here (not just at
            // the .map) keeps the length-check empty-state honest too.
            const filtered = filteredByType.filter(l => l?.item);
            return filtered.length === 0
              ? [h('div', { key: 'empty', className: 'empty-inline' },
                  h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } },
                    `No ${stallTypeFilter.toLowerCase()} listings.`))]
              : filtered.map(l => h('div', { key: l.id, className: `stall-row ${l.hidden ? 'hidden-listing' : ''}` },
            h('div', { className: 'item-thumb', style: { width: 48, height: 48 } }, h(ItemImage, { item: l.item, variant: 'mini' })),
            h('div', { style: { flex: 1, minWidth: 0 } },
              h('div', { className: 'item-name' }, l.item.name,
                l.hidden && h('span', { style: { marginLeft: 8, fontSize: 10, color: 'var(--text-muted)', fontWeight: 700 } }, '· HIDDEN'),
                // Batch 722 — "⚠ reported" chip for sellers. Fires when
                // a listing has at least one pending report from a
                // buyer. Lets the seller know staff is about to review.
                // Intentionally does NOT surface the count (keeps
                // reporters anonymous) or the reason (protects from
                // retaliation). Plain tooltip nudges the seller to
                // review the description / images / pricing for
                // anything that might trip a moderation flag.
                (l.reportCount || 0) > 0 && h('span', {
                  style: {
                    marginLeft: 8, fontSize: 10, fontWeight: 700,
                    color: '#fbbf24',
                    background: 'rgba(251,191,36,0.12)',
                    border: '1px solid rgba(251,191,36,0.4)',
                    padding: '2px 8px', borderRadius: 12
                  },
                  title: 'This listing has been flagged by one or more buyers. Staff will review — no action needed on your end unless you want to pre-emptively fix anything misleading.'
                }, 'reported'),
                // Watcher-count chip — silent when zero so a fresh stall
                // isn't visually noisy. Tooltip explains the signal so a
                // seller knows whether to consider a price drop.
                stallWatcherCounts[l.item.id] > 0 && h('span', {
                  style: {
                    marginLeft: 8, fontSize: 10, fontWeight: 700,
                    color: 'var(--accent)', padding: '2px 8px', borderRadius: 8,
                    background: 'rgba(30,165,255,0.10)', border: '1px solid rgba(30,165,255,0.35)'
                  },
                  title: `${stallWatcherCounts[l.item.id]} buyer${stallWatcherCounts[l.item.id] === 1 ? '' : 's'} have starred this item — they're watching for a deal`
                }, '★ ', stallWatcherCounts[l.item.id]),
                // Item view-count chip (batch 864) — shows sellers how
                // much detail-page traffic their listing is pulling.
                // Pairs with the watcher chip: high views + low watchers
                // means people look but don't star (listing is meh);
                // high watchers + stagnant views means interest but no
                // new eyeballs (needs re-promotion). Silent under 10 so
                // a fresh listing isn't visually noisy. Muted text (not
                // a colored chip) to stay below the priority of the
                // actionable demand signals.
                Number(l.item?.viewCount) >= 10 && h('span', {
                  style: {
                    marginLeft: 8, fontSize: 10, color: 'var(--text-muted)',
                    fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace"
                  },
                  title: `${Number(l.item.viewCount).toLocaleString()} lifetime item-detail opens. High views with no watchers means people look but don't star — check the price, description, or condition.`
                }, '· ', Number(l.item.viewCount).toLocaleString(), ' views'),
                // Buy-order demand chip (batch 415). Renders when at least
                // one buyer has a standing buy order on this item —
                // signals "someone is ready to auto-buy at $X if you
                // price to match". Amber to distinguish from the blue
                // watcher chip. Clicking opens the item page where the
                // seller can see the full buy-order queue.
                (() => {
                  const demand = stallBuyOrderDemand[l.item.id];
                  if (!demand || !(demand.count > 0)) return null;
                  const priced = parseFloat(l.price) || 0;
                  const best   = parseFloat(demand.bestBid) || 0;
                  const closesDeal = best > 0 && priced > 0 && best >= priced;
                  return h('a', {
                    href: '/item/' + l.item.id,
                    onClick: (e) => e.stopPropagation(),
                    style: {
                      marginLeft: 8, fontSize: 10, fontWeight: 700,
                      color: closesDeal ? 'var(--green)' : '#fbbf24',
                      padding: '2px 8px', borderRadius: 8, textDecoration: 'none',
                      background: closesDeal ? 'rgba(34,197,94,0.12)' : 'rgba(251,191,36,0.12)',
                      border: '1px solid ' + (closesDeal ? 'rgba(34,197,94,0.4)' : 'rgba(251,191,36,0.4)')
                    },
                    title: closesDeal
                      ? `${demand.count} buy order${demand.count === 1 ? '' : 's'} · best bid ${fmt(best)} already meets your ${fmt(priced)} ask — selling at current price will auto-match.`
                      : `${demand.count} buy order${demand.count === 1 ? '' : 's'} on this item · best bid ${fmt(best)}. Drop your price to ≤ ${fmt(best)} to auto-match.`
                  }, '⇄ ', demand.count, best > 0 ? ` · ${fmt(best)}` : '');
                })()
              ),
              editing === l.id
                ? h('div', { style: { marginTop: 6 } },
                    h('div', { style: { display: 'flex', gap: 6 } },
                      // Batch 825 — Enter submits, Esc cancels on the
                      // inline edit form. A seller editing 20 prices
                      // sequentially kept having to reach for the
                      // mouse to hit Save; keyboard parity cuts that
                      // to a typing-only flow.
                      h('input', {
                        className: 'price-input',
                        value: editPrice,
                        onChange: e => setEditPrice(e.target.value),
                        onKeyDown: (e) => {
                          if (e.key === 'Enter') { e.preventDefault(); saveEdit(l.id); }
                          else if (e.key === 'Escape') { e.preventDefault(); setEditing(null); }
                        },
                        placeholder: 'price', style: { width: 90 },
                        autoFocus: true
                      }),
                      h('input', {
                        className: 'price-input',
                        value: editDesc,
                        maxLength: 500,
                        onChange: e => setEditDesc(e.target.value),
                        onKeyDown: (e) => {
                          if (e.key === 'Enter') { e.preventDefault(); saveEdit(l.id); }
                          else if (e.key === 'Escape') { e.preventDefault(); setEditing(null); }
                        },
                        placeholder: 'description (500 chars)',
                        style: { flex: 1 }
                      }),
                      h('input', {
                        className: 'price-input',
                        value: editAutoPct,
                        onChange: e => setEditAutoPct(e.target.value.replace(/[^0-9]/g, '')),
                        onKeyDown: (e) => {
                          if (e.key === 'Enter') { e.preventDefault(); saveEdit(l.id); }
                          else if (e.key === 'Escape') { e.preventDefault(); setEditing(null); }
                        },
                        placeholder: 'auto %',
                        title: 'Auto-accept offers at or above this % discount (blank = off). e.g. 20 = accept offers ≥ 80% of ask.',
                        inputMode: 'numeric',
                        style: { width: 80 }
                      })
                    ),
                    // Batch 649 — live preview. Mirrors the sell-form
                    // preview from batch 646 so the seller sees exactly
                    // what the threshold resolves to before saving.
                    (() => {
                      const pct = parseFloat(editAutoPct);
                      const newP = parseFloat(editPrice) || parseFloat(l.price) || 0;
                      if (!Number.isFinite(pct) || pct <= 0 || newP <= 0) return null;
                      if (pct > 50) return h('div', { style: { fontSize: 11, color: 'var(--red)', marginTop: 6, fontWeight: 700 } },
                        'Auto-accept % must be ≤ 50. Set a lower number before saving.');
                      const threshold = +(newP * (1 - pct / 100)).toFixed(2);
                      return h('div', {
                        style: { fontSize: 11, color: 'var(--green)', marginTop: 6, fontWeight: 700 }
                      }, `✓ Offers of ${fmt(threshold)} or higher will auto-accept (${pct}% off ${fmt(newP)}).`);
                    })(),
                    // Batch 894 — live "payout after fees" preview when
                    // the seller types a new price. Shows what lands in
                    // the wallet (gross − 2% platform fee). Silent when
                    // the price hasn't changed (no decision to make).
                    (() => {
                      const newP = parseFloat(editPrice);
                      const oldP = parseFloat(l.price) || 0;
                      if (!Number.isFinite(newP) || newP <= 0) return null;
                      if (Math.abs(newP - oldP) < 0.005) return null;
                      const netNew = sellerPayout(newP);
                      const netOld = sellerPayout(oldP);
                      const delta = netNew - netOld;
                      const deltaStr = (delta >= 0 ? '+' : '') + fmt(delta);
                      const color = delta > 0 ? 'var(--green)'
                                  : delta < 0 ? 'var(--red)'
                                  : 'var(--text-muted)';
                      return h('div', {
                        style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 6 },
                        title: 'Your wallet receives 98% of the sale price (2% platform fee)'
                      },
                        'Payout: ',
                        h('strong', { style: { color: 'var(--text)' } }, fmt(netNew)),
                        ' ',
                        h('span', { style: { color } }, `(${deltaStr} vs. current ${fmt(netOld)})`)
                      );
                    })()
                  )
                : h('div', { className: 'item-sub' },
                    l.item.category + ' · listed ' + timeAgo(l.listedAt),
                    // Auction signal chip — shows type, current bid / bid
                    // count, and a time-to-close so the seller can spot
                    // hot auctions at a glance. Only renders for AUCTION
                    // rows; BUY_NOW stays clean.
                    l.listingType === 'AUCTION' && h('span', {
                      style: {
                        marginLeft: 10, padding: '2px 8px', borderRadius: 4,
                        fontSize: 10, fontWeight: 700,
                        background: 'var(--accent-dim)',
                        color: 'var(--accent)',
                        border: '1px solid var(--accent-border)'
                      },
                      title: l.expiresAt
                        ? `Auction ends at ${new Date(l.expiresAt).toLocaleString()}`
                        : 'Auction listing'
                    },
                      (() => {
                        if (!l.expiresAt) return 'Auction';
                        const ms = l.expiresAt - Date.now();
                        if (ms <= 0) return 'Ended';
                        const h1 = Math.floor(ms / 3600000);
                        const m  = Math.floor((ms % 3600000) / 60000);
                        return h1 > 0 ? `ends in ${h1}h ${m}m` : `ends in ${m}m`;
                      })(),
                      (l.bidCount ?? 0) > 0
                        ? ` · ${l.bidCount} bid${l.bidCount === 1 ? '' : 's'}${l.currentBid ? ` · now ${fmt(l.currentBid)}` : ''}`
                        : ' · no bids yet'
                    ),
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
                        'Best offer ', fmt(o.bestAmount),
                        o.count > 1 ? ` · ${o.count}` : ''
                      );
                    })()
                  )
            ),
            editing === l.id
              ? h('div', { style: { display: 'flex', gap: 6 } },
                  h('button', { className: 'buy-btn', onClick: () => saveEdit(l.id) }, 'Save'),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 12px', fontSize: 11 }, onClick: () => setEditing(null), 'aria-label': 'Cancel price edit' }, '✕')
                )
              : h('div', { style: { display: 'flex', gap: 6, alignItems: 'center' } },
                  h('div', { className: 'price-val', style: { marginRight: 10 } }, fmt(l.price)),
                  // Auctions with live bids are price-locked (server-side
                  // guard in ListingController.updateStall). Surface that
                  // up-front so the seller doesn't click Edit and hit a
                  // confusing 400 — the proper escape hatch is cancel +
                  // relist, which is the Remove button a few chips over.
                  (l.listingType === 'AUCTION' && (l.bidCount ?? 0) > 0)
                    ? h('button', {
                        className: 'btn btn-ghost',
                        style: { padding: '7px 10px', fontSize: 11, opacity: 0.5, cursor: 'not-allowed' },
                        disabled: true,
                        title: "Auctions with bids are price-locked — cancel + relist to change price"
                      }, 'Price locked')
                    : h('button', { className: 'btn btn-ghost', style: { padding: '7px 10px', fontSize: 11 }, onClick: () => startEdit(l) }, '✎ Edit'),
                  // Match-top-bid quick-action (batch 416). Renders only
                  // when a standing buy order exists on this item at a
                  // price BELOW the seller's current ask — one click
                  // drops the listing to `bestBid` which triggers the
                  // auto-match on the next buy-order tryMatch sweep.
                  // Auction listings with live bids can't be edited this
                  // way (server guard) — skip the button for those.
                  (() => {
                    const demand = stallBuyOrderDemand[l.item.id];
                    if (!demand || !(demand.count > 0)) return null;
                    const priced = parseFloat(l.price) || 0;
                    const best   = parseFloat(demand.bestBid) || 0;
                    if (!(best > 0 && priced > 0 && best < priced)) return null;
                    if (l.listingType === 'AUCTION' && (l.bidCount ?? 0) > 0) return null;
                    return h('button', {
                      className: 'btn btn-ghost',
                      style: {
                        padding: '7px 10px', fontSize: 11,
                        border: '1px solid rgba(34,197,94,0.4)', color: 'var(--green)'
                      },
                      title: `Drop this listing's price to ${fmt(best)} — the top standing buy order will auto-fill within seconds.`,
                      onClick: async () => {
                        if (!confirm(`Drop price from ${fmt(priced)} to ${fmt(best)} to match the top buy order? The listing will auto-fill.`)) return;
                        const res = await updateStallListing(l.id, { price: best });
                        if (res && (res.error || res.code)) {
                          toast(res.message || res.error || 'Could not update price', 'err');
                          return;
                        }
                        toast(`Listing dropped to ${fmt(best)} — expect auto-match shortly.`, 'ok');
                        load();
                      }
                    }, 'Match ', fmt(best));
                  })(),
                  h('button', { className: 'btn btn-ghost', style: { padding: '7px 10px', fontSize: 11 }, onClick: () => toggleHidden(l) }, l.hidden ? 'Show' : 'Hide'),
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
                          toast('Link copied to clipboard.', 'ok');
                        } else {
                          window.prompt('Copy this link:', url);
                        }
                      } catch (_) { window.prompt('Copy this link:', url); }
                    }
                  }, '⎘ Link'),
                  // csfloat parity: an auction locks once it has its first bid,
                  // so the server rejects the cancel (AUCTION_HAS_BIDS). Show a
                  // disabled lock instead of a live ✕ so the seller isn't invited
                  // to click a button that can only fail — the auction must settle
                  // when it ends. No-bid auctions and Buy-Now listings cancel as
                  // before.
                  (l.listingType === 'AUCTION' && (l.bidCount ?? 0) > 0)
                    ? h('button', {
                        className: 'btn btn-ghost',
                        disabled: true,
                        style: { border: '1px solid var(--border)', color: 'var(--text-muted)', padding: '7px 10px', fontSize: 11, cursor: 'not-allowed', opacity: 0.7 },
                        title: 'This auction has bids and can no longer be cancelled — it settles when it ends.',
                        'aria-label': 'Auction locked — has active bids'
                      }, '🔒')
                    : h('button', {
                        className: 'btn btn-ghost',
                        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 10px', fontSize: 11 },
                        title: 'Cancel listing',
                        'aria-label': 'Cancel listing',
                        onClick: () => doCancel(l)
                      }, '✕')
                )
          ));
          })()
        )
    )
    ) // close role="tabpanel" content region
  );
}

// ── Offers (incoming/outgoing) ───────────────────────────────────
export function OffersModal({ onClose, me, onRefresh, initialTab }) {
  const [tab, setTab]       = useState(initialTab || 'incoming');
  const [incoming, setIn]   = useState(null);
  const [outgoing, setOut]  = useState(null);
  const [busy, setBusy]     = useState(false);
  // Synchronous re-entrancy latch for the money/state-mutating handlers
  // below (accept / reject / cancel / counter / raise). `setBusy` is async
  // (React batches it), so a rapid double-click fires two POSTs before the
  // first render disables the button — accepting an offer debits the buyer's
  // wallet and opens a trade, so a double-accept is real money. `busyRef`
  // is checked-and-set BEFORE the await so the second click bails instantly.
  // Mirrors the ProfileOffersTab twin (busyRef) + handleBuy/checkout latches.
  // Kept in sync with `busy` each render so it auto-resets after a handler
  // finishes (the finally's setBusy(false) re-render clears it).
  const busyRef = useRef(busy); busyRef.current = busy;
  // Counter-offer inline state. `counterFor` is the offer id being
  // countered; `counterAmt` is the typed amount. Mirrors the thread-view
  // inline counter UX so sellers can counter straight from the offers
  // queue instead of navigating to the item page.
  const [counterFor, setCounterFor] = useState(null);
  const [counterAmt, setCounterAmt] = useState('');
  // Optional 280-char note alongside the counter / raise. Same field as
  // the initial makeOffer message so the back-and-forth thread reads as
  // one conversation. Reset when the inline drawer closes.
  const [counterMsg, setCounterMsg] = useState('');
  // Status filter chip (batch 390). 'ALL' by default; chips toggle to
  // PENDING / ACCEPTED / REJECTED / CANCELLED / EXPIRED / COUNTERED so a
  // user with hundreds of historical offers can jump to one bucket
  // without scrolling. Defaults to ALL so the baseline view matches the
  // older UX.
  const [statusFilter, setStatusFilter] = useState('ALL');
  // Text search across item name + counterparty name. A seller with
  // dozens of PENDING offers otherwise has to eyeball-scan the list
  // to find "the offer on the blue shirt from X." Matches case-insensitive
  // substrings. Empty = everything. Only surfaces when the list is long
  // enough to warrant it (threshold below), so small inboxes stay clean.
  const [offerSearch, setOfferSearch] = useState('');
  // Load-failure state. Pre-fix, a rejected fetch left `incoming`/`outgoing`
  // null forever → the `list === null` branch rendered a spinner with no
  // way out. Track the error so we can show a retryable message instead.
  const [loadErr, setLoadErr] = useState(false);

  // 2026-05-20 — keep the active tab in sync with the route param.
  // pickTab navigates to /offers/<tab>, so browser back/forward changes
  // initialTab while the modal stays mounted; useState only reads its
  // argument once. Without this the URL and the highlighted tab drifted
  // apart on back/forward. Mirrors the WalletModal initialTab sync.
  useEffect(() => {
    if (initialTab === 'incoming' || initialTab === 'outgoing') setTab(initialTab);
  }, [initialTab]);

  const load = useCallback(async () => {
    setLoadErr(false);
    try {
      const [i, o] = await Promise.all([fetchIncomingOffers(), fetchOutgoingOffers()]);
      setIn(i);
      setOut(o);
    } catch (_) {
      setLoadErr(true);
    }
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  // Hoisted above the early-return so hook order stays stable across
  // the null→authed transition (React rules of hooks).
  const [rejectFor, setRejectFor] = useState(null);
  const [rejectReply, setRejectReply] = useState('');

  if (!me) return h(InfoModal, { title: 'Offers', onClose },
    h(SignInNeededEmptyState, { what: 'your offers' }));

  // Helper to find an offer row by id across incoming + outgoing so the
  // success-toast can name the item + offered amount the way the
  // ProfileOffersTab equivalents (modals.js:7600+) already do. Pre-fix
  // every state-transition toast on this surface was generic ("Offer
  // accepted — trade opened.") while the ProfileOffersTab equivalents
  // had rich `<item>, <amount>, <buyer>` context. Symmetric naming so a
  // seller bouncing across both surfaces reads the same level of
  // detail everywhere.
  const findOffer = (id) => (incoming || []).find(x => x.id === id)
                          || (outgoing || []).find(x => x.id === id);

  const handleAccept = async (id) => {
    if (busyRef.current) return;
    busyRef.current = true;
    const o = findOffer(id);
    setBusy(true);
    try {
      const res = await acceptOffer(id);
      if (res.code || res.error) { toast(res.message || res.error || 'Could not accept offer', 'err'); return; }
      await load();
      onRefresh && onRefresh();
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      const amtBit = o?.amount != null ? ` at ${fmt(parseFloat(o.amount))}` : '';
      const buyerBit = o?.buyerName ? ` from ${o.buyerName}` : '';
      toast(`Accepted ${label}${amtBit}${buyerBit} — trade opened.`, 'ok');
    } finally { setBusy(false); }
  };
  // Reject flow (V45 / batch 387). `rejectFor` is the offer id the seller
  // has clicked Reject on; `rejectReply` is the optional 280-char note
  // surfaced to the buyer on their rejected offer row + notification body.
  // Separate from the counter drawer state so a seller can line up a
  // rejection note without blowing away an in-progress counter draft.
  // (useState pair hoisted above the anon-guard early return.)
  const confirmReject = async (id) => {
    if (busyRef.current) return;
    busyRef.current = true;
    const o = findOffer(id);
    setBusy(true);
    try {
      const res = await rejectOffer(id, rejectReply);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not reject offer', 'err');
        return;
      }
      setRejectFor(null); setRejectReply('');
      await load();
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      const buyerBit = o?.buyerName ? ` from ${o.buyerName}` : '';
      toast(`Rejected ${label}${buyerBit} — buyer notified.`, 'ok');
    } finally { setBusy(false); }
  };
  const handleCancel = async (id) => {
    if (busyRef.current) return;
    busyRef.current = true;
    const o = findOffer(id);
    setBusy(true);
    try {
      // Pre-fix: silent both ways — successful cancel got no toast (the user
      // had to read the row's status pill change to confirm) and a backend
      // rejection (race with seller-acceptance, already-cancelled, network
      // blip) just left the row alone with no feedback. Mirror handleAccept
      // / confirmReject so every offer state-transition gets a visible
      // confirmation or error.
      const res = await cancelOffer(id);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not cancel offer', 'err');
        return;
      }
      await load();
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      const amtBit = o?.amount != null ? ` (${fmt(parseFloat(o.amount))})` : '';
      toast(`Cancelled your offer on ${label}${amtBit}.`, 'ok');
    } finally { setBusy(false); }
  };
  const handleCounter = async (id, mode) => {
    if (busyRef.current) return;
    const amt = parseFloat(counterAmt);
    if (!Number.isFinite(amt) || amt <= 0) { toast('Enter an amount above $0', 'err'); return; }
    const o = findOffer(id);
    busyRef.current = true;
    setBusy(true);
    try {
      // Incoming = seller countering a buyer's offer → /counter.
      // Outgoing = buyer raising their own offer → /raise (different
      // endpoint, cancels the old offer + opens a new threaded one).
      const { raiseOffer } = await import('./api.js');
      const res = mode === 'raise'
        ? await raiseOffer(id, amt, counterMsg)
        : await counterOffer(id, amt, counterMsg);
      if (res.code || res.error) { toast(res.message || res.error || 'Action failed', 'err'); return; }
      setCounterFor(null); setCounterAmt(''); setCounterMsg('');
      await load();
      const label = o?.itemName ? `"${o.itemName}"` : `offer #${id}`;
      const wasStr = o?.amount ? ` (was ${fmt(parseFloat(o.amount))})` : '';
      toast(mode === 'raise'
        ? `Raised offer on ${label} to ${fmt(amt)}${wasStr}.`
        : `Countered ${label} at ${fmt(amt)} — buyer notified.`,
        'ok');
    } finally { setBusy(false); }
  };

  const renderOffer = (offer, isIncoming) => {
    const diff   = parseFloat(offer.askingPrice) - parseFloat(offer.amount);
    const pctOff = Math.round(diff / parseFloat(offer.askingPrice) * 100);
    // Deep-link the item name + thumb to /item/:id when the offer carries
    // the catalogue id (V44 / batch 384). Legacy offers with no itemId
    // fall back to plain text / non-clickable thumb. onClose fires so the
    // OffersModal gets out of the way before the router lands on the
    // item page — without it the modal stays mounted on top.
    const itemHref = offer.itemId ? `/item/${offer.itemId}` : null;
    const thumbInner = offer.itemImageUrl
      ? h('img', { src: offer.itemImageUrl, alt: offer.itemName, loading: 'lazy', decoding: 'async', referrerPolicy: 'no-referrer', onError: (e) => { e.currentTarget.style.display = 'none'; } })
      : h('span', null, '—');
    return h('div', { key: offer.id, className: 'offer-row' },
      itemHref
        ? h('a', {
            href: itemHref,
            className: 'item-thumb',
            style: { width: 56, height: 56, display: 'block' },
            onClick: (e) => { e.stopPropagation(); onClose && onClose(); },
            title: 'Open ' + (offer.itemName || 'item')
          }, thumbInner)
        : h('div', { className: 'item-thumb', style: { width: 56, height: 56 } }, thumbInner),
      h('div', { style: { flex: 1, minWidth: 0 } },
        itemHref
          ? h('a', {
              href: itemHref,
              className: 'item-name',
              style: { color: 'inherit', textDecoration: 'none' },
              onClick: (e) => { e.stopPropagation(); onClose && onClose(); },
              title: 'Open ' + (offer.itemName || 'item')
            }, offer.itemName || ('Listing #' + offer.listingId))
          : h('div', { className: 'item-name' }, offer.itemName || ('Listing #' + offer.listingId)),
        // Optional buyer note (V43 / batch 382). Italicised + quoted so it
        // visually reads as a quote, not an attribute. Renders for both
        // incoming (seller perspective) and outgoing (buyer's own draft
        // echoed back) so both sides see the same context.
        offer.message && h('div', {
          style: {
            fontSize: 12, color: 'var(--text-secondary)',
            fontStyle: 'italic', marginTop: 2,
            overflow: 'hidden', textOverflow: 'ellipsis',
            whiteSpace: 'nowrap', maxWidth: 320
          },
          title: offer.message
        }, '“', offer.message, '”'),
        // Seller rejection reason (V45 / batch 387). Only surfaces on
        // REJECTED rows — PENDING offers can't have one yet. Red accent
        // so the buyer can't miss the "why" next to the grey REJECTED
        // status chip.
        offer.status === 'REJECTED' && offer.sellerReply && h('div', {
          style: {
            fontSize: 11, color: 'var(--red)',
            marginTop: 3, lineHeight: 1.4,
            overflow: 'hidden', textOverflow: 'ellipsis',
            whiteSpace: 'nowrap', maxWidth: 320
          },
          title: offer.sellerReply
        }, '↩ Seller: “', offer.sellerReply, '”'),
        h('div', { className: 'item-sub' },
          isIncoming
            ? `From ${offer.buyerName}`
            : (offer.sellerName ? `To @${offer.sellerName}` : 'Your offer'),
          ' · ', timeAgo(offer.createdAt),
          // Auto-decline countdown — server-computed `expiresAt` is set
          // for PENDING rows only (batch 269). Red when ≤24h so a
          // seller with a long queue sees what's about to silently
          // disappear if they don't respond.
          offer.status === 'PENDING' && offer.expiresAt && (() => {
            const msLeft = offer.expiresAt - Date.now();
            if (msLeft <= 0) return null;
            const hours = msLeft / 3_600_000;
            let label;
            if (hours < 1)       label = Math.max(1, Math.round(msLeft / 60_000)) + 'm';
            else if (hours < 24) label = Math.round(hours) + 'h';
            else                 label = Math.round(hours / 24) + 'd';
            const urgent = hours < 24;
            return h('span', {
              style: {
                marginLeft: 10, fontSize: 11, fontWeight: 700,
                color: urgent ? 'var(--red)' : 'var(--text-muted)'
              },
              title: `Auto-declines at ${new Date(offer.expiresAt).toLocaleString()} if neither side responds`
            }, '· ⏱ ', label, ' left');
          })()
        ),
        // Counterparty reputation chips (batch 847, extended in 851).
        // Both sides see the other's track record: incoming rows show
        // the buyer's completed-trade count + any review avg; outgoing
        // rows show the seller's review avg (seller's completed-trade
        // count is less meaningful than their review profile since
        // every sale generates a trade anyway).
        (() => {
          if (isIncoming) {
            const trades = Number(offer.buyerCompletedTrades) || 0;
            const revCount = Number(offer.buyerReviewCount) || 0;
            if (trades === 0 && revCount < 3) return null;
            return h('div', {
              style: {
                display: 'flex', gap: 6, alignItems: 'center',
                marginTop: 4, fontSize: 11, color: 'var(--text-muted)'
              }
            },
              trades > 0 && h('span', {
                style: {
                  padding: '2px 7px', borderRadius: 10,
                  background: 'rgba(30,165,255,0.08)', color: 'var(--accent)',
                  border: '1px solid rgba(30,165,255,0.25)', fontWeight: 700
                },
                title: `This buyer has completed ${trades} verified trade${trades === 1 ? '' : 's'} on SkinBox — tangible history beats low-ball optics.`
              }, '✓ ', trades, ' ', trades === 1 ? 'trade' : 'trades'),
              revCount >= 3 && offer.buyerReviewAvg != null && h('span', {
                style: {
                  padding: '2px 7px', borderRadius: 10,
                  background: 'rgba(251,191,36,0.08)', color: '#fbbf24',
                  border: '1px solid rgba(251,191,36,0.25)', fontWeight: 700
                },
                title: `Average buyer rating across ${revCount} reviews — how sellers have rated this buyer's past behavior.`
              }, '★ ', Number(offer.buyerReviewAvg).toFixed(1), ' (', revCount, ')')
            );
          }
          // Outgoing — show the seller's review chip so the buyer can
          // judge who they're about to trade with if the seller accepts.
          const sellerRev = Number(offer.sellerReviewCount) || 0;
          if (sellerRev < 3 || offer.sellerReviewAvg == null) return null;
          return h('div', {
            style: {
              display: 'flex', gap: 6, alignItems: 'center',
              marginTop: 4, fontSize: 11, color: 'var(--text-muted)'
            }
          },
            h('span', {
              style: {
                padding: '2px 7px', borderRadius: 10,
                background: 'rgba(251,191,36,0.08)', color: '#fbbf24',
                border: '1px solid rgba(251,191,36,0.25)', fontWeight: 700
              },
              title: `Seller rating across ${sellerRev} reviews — buyer feedback from past trades.`
            }, '★ ', Number(offer.sellerReviewAvg).toFixed(1), ' (', sellerRev, ')')
          );
        })()
      ),
      h('div', { style: { textAlign: 'right', marginRight: 14 } },
        h('div', { style: { fontSize: 14, fontWeight: 800, color: 'var(--accent)', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, fmt(offer.amount)),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', textDecoration: 'line-through', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, fmt(offer.askingPrice)),
        h('div', { style: { fontSize: 10, fontWeight: 700, color: pctOff > 0 ? 'var(--green)' : 'var(--text-muted)' } }, pctOff > 0 ? `−${pctOff}%` : '')
      ),
      offer.status === 'PENDING'
        ? (counterFor === offer.id
            ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6, minWidth: 220 } },
                h('div', { style: { display: 'flex', gap: 6, alignItems: 'center' } },
                  h('input', {
                    className: 'wallet-amount-input',
                    style: { width: 90, padding: '6px 8px', fontSize: 12 },
                    type: 'number', step: '0.01', min: '0.01',
                    inputMode: 'decimal', enterKeyHint: 'send',
                    'aria-label': isIncoming ? 'Counter-offer amount' : 'Raise offer to',
                    placeholder: isIncoming ? 'Counter $' : 'Raise to',
                    value: counterAmt,
                    onChange: e => setCounterAmt(e.target.value),
                    // Batch 825 — Enter submits, Esc cancels. A seller
                    // burst-countering 10 offers shouldn't need the
                    // mouse for each one.
                    onKeyDown: (e) => {
                      if (e.key === 'Enter' && !busy) {
                        e.preventDefault();
                        handleCounter(offer.id, isIncoming ? 'counter' : 'raise');
                      } else if (e.key === 'Escape') {
                        e.preventDefault();
                        setCounterFor(null); setCounterAmt(''); setCounterMsg('');
                      }
                    },
                    autoFocus: true
                  }),
                  h('button', {
                    className: 'buy-btn',
                    style: { padding: '7px 11px', fontSize: 11 },
                    onClick: () => handleCounter(offer.id, isIncoming ? 'counter' : 'raise'),
                    disabled: busy
                  }, isIncoming ? 'Send' : 'Raise'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '6px 10px', fontSize: 11 },
                    onClick: () => { setCounterFor(null); setCounterAmt(''); setCounterMsg(''); },
                    'aria-label': 'Cancel counter'
                  }, '✕')
                ),
                // Optional 280-char note attached to the counter / raise.
                // Same wire-protocol as the initial makeOffer message so the
                // thread reads as one conversation. Compact textarea so the
                // inline drawer doesn't blow up the row height.
                h('textarea', {
                  style: {
                    width: '100%', minHeight: 40, padding: '6px 8px',
                    fontFamily: 'inherit', fontSize: 11, resize: 'vertical',
                    background: 'var(--bg-page-2, #0d1320)',
                    border: '1px solid var(--border)',
                    borderRadius: 5, color: 'var(--text-primary)'
                  },
                  placeholder: isIncoming
                    ? 'Optional note ("can\'t go lower — already 30% below median")'
                    : 'Optional note ("brand new acct, fast pay")',
                  maxLength: 280,
                  value: counterMsg,
                  onChange: e => setCounterMsg(e.target.value)
                })
              )
            : rejectFor === offer.id
              ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6, minWidth: 240 } },
                  h('textarea', {
                    style: {
                      width: '100%', minHeight: 54, padding: '6px 8px',
                      fontFamily: 'inherit', fontSize: 11, resize: 'vertical',
                      background: 'var(--bg-page-2, #0d1320)',
                      border: '1px solid var(--border)',
                      borderRadius: 5, color: 'var(--text-primary)'
                    },
                    placeholder: 'Optional reason shown to the buyer ("already committed to another buyer, sorry")',
                    maxLength: 280,
                    value: rejectReply,
                    onChange: e => setRejectReply(e.target.value),
                    autoFocus: true
                  }),
                  h('div', { style: { display: 'flex', gap: 6, justifyContent: 'flex-end' } },
                    h('button', {
                      className: 'btn btn-ghost',
                      style: { padding: '6px 10px', fontSize: 11 },
                      onClick: () => { setRejectFor(null); setRejectReply(''); }
                    }, 'Cancel'),
                    h('button', {
                      className: 'btn btn-ghost',
                      style: { border: '1px solid rgba(248,113,113,0.4)', color: 'var(--red)', padding: '6px 12px', fontSize: 11, fontWeight: 700 },
                      onClick: () => confirmReject(offer.id), disabled: busy
                    }, busy ? '…' : 'Reject offer')
                  )
                )
              : isIncoming
              ? h('div', { style: { display: 'flex', gap: 6 } },
                  h('button', { className: 'buy-btn', onClick: () => handleAccept(offer.id), disabled: busy }, 'Accept'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', color: 'var(--accent)', padding: '7px 11px', fontSize: 11 },
                    onClick: () => {
                      setCounterFor(offer.id);
                      setCounterAmt(((parseFloat(offer.amount) + parseFloat(offer.askingPrice)) / 2).toFixed(2));
                    }, disabled: busy,
                    title: 'Propose a price between the offer and your asking price'
                  }, 'Counter'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 11px', fontSize: 11 },
                    onClick: () => { setRejectFor(offer.id); setRejectReply(''); }, disabled: busy,
                    title: 'Decline this offer — optional note goes to the buyer'
                  }, 'Reject')
                )
              : (offer.author === 'SELLER'
                  ? h('div', { style: { display: 'flex', gap: 6 } },
                      h('button', {
                        className: 'buy-btn',
                        onClick: () => handleAccept(offer.id), disabled: busy,
                        title: `Accept ${fmt(offer.amount)} counter`
                      }, '✓ Accept'),
                      h('button', {
                        className: 'btn btn-ghost',
                        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 11px', fontSize: 11 },
                        onClick: () => handleCancel(offer.id), disabled: busy,
                        title: 'Walk away from the seller counter'
                      }, 'Decline')
                    )
                  : h('div', { style: { display: 'flex', gap: 6 } },
                      h('button', {
                        className: 'btn btn-ghost',
                        style: { border: '1px solid var(--border)', color: 'var(--accent)', padding: '7px 11px', fontSize: 11 },
                        onClick: () => {
                          setCounterFor(offer.id);
                          setCounterAmt((parseFloat(offer.amount) + 1).toFixed(2));
                        }, disabled: busy,
                        title: 'Raise your own offer'
                      }, 'Raise'),
                      h('button', {
                        className: 'btn btn-ghost',
                        style: { border: '1px solid var(--border)', padding: '7px 14px', fontSize: 12 },
                        onClick: () => handleCancel(offer.id), disabled: busy
                      }, 'Cancel')
                    )))
        : h('div', { className: `wallet-tx-status ${offer.status}`, style: { padding: '4px 10px', borderRadius: 5, fontSize: 10 } },
            offer.status === 'PENDING'   ? 'Pending'
            : offer.status === 'ACCEPTED'  ? 'Accepted'
            : offer.status === 'REJECTED'  ? 'Rejected'
            : offer.status === 'CANCELLED' ? 'Cancelled'
            : offer.status === 'EXPIRED'   ? 'Expired'
            : offer.status === 'COUNTERED' ? 'Countered'
            : offer.status)
    );
  };

  const rawList = tab === 'incoming' ? incoming : outgoing;
  const anyOffers = (incoming && incoming.length > 0) || (outgoing && outgoing.length > 0);
  // Status filter — only shown when there are actually offers to filter,
  // and only when at least one row wouldn't match the default ALL chip.
  // Hides dead buttons on empty states + keeps the header tight.
  const STATUS_CHIPS = [
    { id: 'ALL',       label: 'All' },
    { id: 'PENDING',   label: 'Pending' },
    { id: 'ACCEPTED',  label: 'Accepted' },
    { id: 'REJECTED',  label: 'Rejected' },
    { id: 'COUNTERED', label: 'Countered' },
    { id: 'CANCELLED', label: 'Cancelled' },
    { id: 'EXPIRED',   label: 'Expired' }
  ];
  const q = (offerSearch || '').trim().toLowerCase();
  const list = rawList == null
    ? rawList
    : (statusFilter === 'ALL' ? rawList : rawList.filter(o => (o.status || '').toUpperCase() === statusFilter))
        .filter(o => {
          if (!q) return true;
          const hay = [
            o.itemName || '',
            o.buyerName || '',
            o.sellerName || '',
            o.message || '',
            ('listing #' + (o.listingId || ''))
          ].join(' ').toLowerCase();
          return hay.includes(q);
        });
  return h(InfoModal, { title: tab === 'outgoing' ? 'Outgoing · Offers' : 'Incoming · Offers', onClose },
    // Batch 937 — tablist semantics on the full Offers modal too.
    h('div', { className: 'offer-tabs', role: 'tablist', 'aria-label': 'Offer direction', style: { display: 'flex', alignItems: 'center', gap: 4 } },
      (() => {
        const MODAL_OFFER_TABS = ['incoming', 'outgoing'];
        const onKey = (e) => {
          if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
          e.preventDefault();
          const idx = MODAL_OFFER_TABS.indexOf(tab);
          let n = idx;
          if (e.key === 'ArrowRight') n = (idx + 1) % MODAL_OFFER_TABS.length;
          else if (e.key === 'ArrowLeft') n = (idx - 1 + MODAL_OFFER_TABS.length) % MODAL_OFFER_TABS.length;
          else if (e.key === 'Home') n = 0;
          else if (e.key === 'End') n = MODAL_OFFER_TABS.length - 1;
          setTab(MODAL_OFFER_TABS[n]);
        };
        const pickTab = (id) => {
          setTab(id);
          navigate('/offers/' + id);
        };
        return [
          h('button', {
            key: 'incoming',
            id: 'offers-modal-tab-incoming',
            className: `offer-tab ${tab === 'incoming' ? 'active' : ''}`,
            role: 'tab',
            'aria-selected': tab === 'incoming',
            'aria-controls': 'offers-modal-panel-incoming',
            tabIndex: tab === 'incoming' ? 0 : -1,
            onClick: () => pickTab('incoming'),
            onKeyDown: onKey
          }, 'Incoming', incoming && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, incoming.filter(o => o.status === 'PENDING').length)),
          h('button', {
            key: 'outgoing',
            id: 'offers-modal-tab-outgoing',
            className: `offer-tab ${tab === 'outgoing' ? 'active' : ''}`,
            role: 'tab',
            'aria-selected': tab === 'outgoing',
            'aria-controls': 'offers-modal-panel-outgoing',
            tabIndex: tab === 'outgoing' ? 0 : -1,
            onClick: () => pickTab('outgoing'),
            onKeyDown: onKey
          }, 'Outgoing', outgoing && h('span', { className: 'filter-count', style: { marginLeft: 6 } }, outgoing.filter(o => o.status === 'PENDING').length))
        ];
      })(),
      h('div', { style: { flex: 1 } }),
      anyOffers && (() => {
        // Honour the active tab via `?role=` so a user on Outgoing
        // who clicks ⇣ CSV gets just their outgoing offers, not the
        // merged set. Same UX-parity logic as the other CSV exports.
        const role = tab === 'incoming' ? 'SELLER' : tab === 'outgoing' ? 'BUYER' : null;
        return h('a', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
          href: role ? `/api/profile/offers.csv?role=${role}` : '/api/profile/offers.csv',
          title: role
            ? `Download ${tab} offer history as CSV`
            : 'Download every offer you made or received as a CSV'
        }, '⇣ CSV');
      })()
    ),
    rawList && rawList.length > 8 && h('div', { style: { marginBottom: 10 } },
      h('input', {
        className: 'price-input',
        type: 'search',
        enterKeyHint: 'search',
        autoComplete: 'off',
        placeholder: 'Search by item name, counterparty, or note…',
        value: offerSearch,
        onChange: e => setOfferSearch(e.target.value),
        style: { width: '100%', fontSize: 13, padding: '8px 12px' },
        'aria-label': 'Search offers'
      })
    ),
    rawList && rawList.length > 3 && h('div', {
      style: { display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 }
    },
      STATUS_CHIPS.map(opt => {
        const count = opt.id === 'ALL'
          ? rawList.length
          : rawList.filter(o => (o.status || '').toUpperCase() === opt.id).length;
        return h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${statusFilter === opt.id ? 'active' : ''}`,
          'aria-pressed': statusFilter === opt.id,
          onClick: () => setStatusFilter(opt.id),
          disabled: count === 0 && opt.id !== 'ALL'
        }, `${opt.label} · ${count}`);
      })
    ),
    h('div', {
      role: 'tabpanel',
      id: tab === 'outgoing' ? 'offers-modal-panel-outgoing' : 'offers-modal-panel-incoming',
      'aria-labelledby': tab === 'outgoing' ? 'offers-modal-tab-outgoing' : 'offers-modal-tab-incoming'
    },
    loadErr
      // Load-failure branch — replaces the old never-ending spinner with a
      // retryable message so a transient network error is recoverable.
      ? h('div', { className: 'empty-inline' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            "Couldn't load your offers"),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
            'Something went wrong fetching your offers. Check your connection and try again.'),
          h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
            h('button', { className: 'btn btn-accent', onClick: () => load() }, 'Retry')))
      : list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              q
                ? `No offers match “${offerSearch}”`
                : statusFilter !== 'ALL'
                  ? `No ${statusFilter.toLowerCase()} offers`
                  : (tab === 'incoming' ? 'No incoming offers yet' : 'No offers out')),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
              q
                ? 'Clear the search or adjust the status chip to see other offers.'
                : statusFilter !== 'ALL'
                  ? 'Switch to "All" to see every offer, or pick a different status chip.'
                  : tab === 'incoming'
                    ? "They'll show up here when buyers make offers on your listings."
                    : 'Click "Make Offer" on any listing to bargain. Sellers have 7 days to respond before the offer auto-expires.'),
            h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
              q
                ? h('button', {
                    className: 'btn btn-accent',
                    onClick: () => setOfferSearch('')
                  }, 'Clear search')
                : statusFilter !== 'ALL'
                  ? h('button', {
                      className: 'btn btn-accent',
                      onClick: () => setStatusFilter('ALL')
                    }, 'Show all offers')
                  : tab === 'incoming'
                    ? h('a', { className: 'btn btn-accent', href: '/me/stall' }, 'Open My Stall →')
                    : h('a', { className: 'btn btn-accent', href: '/market' }, 'Browse marketplace →')
            ))
        : h('div', { className: 'offer-list' }, list.map(o => renderOffer(o, tab === 'incoming')))
    )
  );
}

// ── Watchlist ───────────────────────────────────────────────────
export function WatchlistModal({ onClose, me, watchlist, allListings, onOpen, onToggleStar, onAddToCart, cartHas, initialTab }) {
  // Dedupe by item id (one card per item) and compute price drop since the
  // item was first starred. We store a { itemId → price } snapshot in
  // localStorage so each card can show "−$X since you watchlisted" even
  // across sessions. Also supports a Drops Only filter.
  // Batch 768 — persist the "Drops only" toggle so a user who has
  // opted into the view doesn't have to re-toggle it on every watchlist
  // open. Matches the state + sort persistence already in place.
  const [showDropsOnly, setShowDropsOnlyRaw] = useState(() => {
    // Route param wins over localStorage so /watchlist/drops is authoritative.
    if (initialTab === 'drops') return true;
    if (initialTab === 'all')   return false;
    try { return localStorage.getItem('sb_watchlist_drops_only') === '1'; }
    catch { return false; }
  });
  const setShowDropsOnly = (v) => {
    setShowDropsOnlyRaw(v);
    try { localStorage.setItem('sb_watchlist_drops_only', v ? '1' : '0'); } catch (_) {}
  };
  // 2026-05-20 — sync the Drops-only filter with the route param. The
  // toggle navigates to /watchlist/drops or /watchlist/all, and the
  // modal stays mounted across those routes, so browser back/forward
  // changed initialTab but left the filter (and its highlighted tab)
  // stale. Only react to the explicit drops/all params — matches the
  // initial-state precedence above so a localStorage-only visit is
  // untouched. Mirrors the WalletModal initialTab sync effect.
  useEffect(() => {
    if (initialTab === 'drops') setShowDropsOnlyRaw(true);
    else if (initialTab === 'all') setShowDropsOnlyRaw(false);
  }, [initialTab]);
  // Category chip filter — lets users with 50+ starred items drill to a
  // single slice (Hats / Pants / Shirts / Accessories / …) without hunting.
  // 'All' is the default. Chip set is derived from the watchlist's own
  // categories so dead buttons don't render.
  const [catFilter, setCatFilter] = useState('All');
  // Batch 640 — State filter (CSFloat Visual Manual §27 parity).
  // ALL: every watched item. LISTED: only items with an active listing
  // right now — the "buy now" subset. UNAVAILABLE: items currently off
  // the market (sold out, delisted by the seller, or never had one) so
  // users can see which of their targets are currently unavailable at
  // a glance. Persists to localStorage so a returning visitor keeps
  // their filter intent across sessions.
  const [stateFilter, setStateFilter] = useState(() => {
    try { return localStorage.getItem('sb_watchlist_state') || 'ALL'; }
    catch { return 'ALL'; }
  });
  const setState = (v) => {
    setStateFilter(v);
    try { localStorage.setItem('sb_watchlist_state', v); } catch (_) {}
  };
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
    // Anon callers always 401 here — skip the round-trip so the network
    // panel + console error stream stay clean for sign-out browsing.
    if (!me?.id) { setServerAlerts([]); return; }
    try {
      const { fetchWatchlistAlerts } = await import('./api.js');
      const data = await fetchWatchlistAlerts();
      setServerAlerts(Array.isArray(data) ? data : []);
    } catch (_) { setServerAlerts([]); }
  }, [me?.id]);
  useEffect(() => { loadServerAlerts(); }, [loadServerAlerts]);
  const cancelServerAlert = async (id) => {
    // Batch 913 — snapshot the alert row before cancel so the toast
    // can name the item + target price. Server list is refetched via
    // loadServerAlerts(), so we need the row in hand up front.
    const a = (serverAlerts || []).find(x => x.id === id);
    const { cancelWatchlistAlert } = await import('./api.js');
    const res = await cancelWatchlistAlert(id);
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not cancel alert', 'err');
      return;
    }
    loadServerAlerts();
    const who = a?.itemName ? `"${a.itemName}"` : 'item';
    // Restock-style alerts encode targetPrice ≥ 99999 as "any future
    // listing" — don't read out the magic number in the toast.
    const isRestock = a?.targetPrice != null && parseFloat(a.targetPrice) >= 99999;
    const target = isRestock ? ' (restock)'
                : a?.targetPrice != null ? ` at ${fmt(parseFloat(a.targetPrice))}`
                : '';
    toast(`Alert on ${who}${target} cancelled.`, 'ok');
  };
  const [editingAlert, setEditingAlert] = useState(null);
  const [alertDraft, setAlertDraft] = useState('');
  // Batch 832 — inline edit for server-side price alerts. Replaces the
  // window.prompt-based edit with a compact inline number input so the
  // user doesn't lose the context of the alert row they're editing.
  const [editingServerAlertId, setEditingServerAlertId] = useState(null);
  const [serverAlertDraft, setServerAlertDraft] = useState('');
  const [serverAlertBusy, setServerAlertBusy] = useState(false);
  const startEditServerAlert = (a) => {
    setEditingServerAlertId(a.id);
    setServerAlertDraft(String(a.targetPrice));
  };
  const saveEditServerAlert = async (a) => {
    const n = parseFloat(serverAlertDraft);
    if (!isFinite(n) || n <= 0) {
      toast('Enter a positive dollar amount', 'err');
      return;
    }
    setServerAlertBusy(true);
    try {
      const { createWatchlistAlert } = await import('./api.js');
      const res = await createWatchlistAlert(a.itemId, n);
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not update alert', 'err');
        return;
      }
      setEditingServerAlertId(null);
      setServerAlertDraft('');
      loadServerAlerts();
      // Batch 913 — name the item + show old → new target delta so
      // the edit is visually confirmed. Consistent with stall price-edit
      // toast (batch 911).
      const oldN = parseFloat(a.targetPrice);
      const deltaStr = isFinite(oldN) && Math.abs(oldN - n) >= 0.01
        ? ` (was ${fmt(oldN)})`
        : '';
      const who = a?.itemName ? `"${a.itemName}"` : 'item';
      toast(`Alert target on ${who} updated to ${fmt(n)}${deltaStr}.`, 'ok');
    } finally { setServerAlertBusy(false); }
  };
  const saveAlert = (itemId, value) => {
    const n = parseFloat(value);
    const next = { ...alerts };
    const cleared = !isFinite(n) || n <= 0;
    if (cleared) delete next[itemId];
    else next[itemId] = n;
    setAlerts(next);
    // Same QuotaExceededError guard as sb_cart / sb_watchlist persist
    // writes — saveAlert runs in a click handler, so an unguarded throw
    // here crashes the click + the surrounding WatchlistModal render.
    // State stays in-memory; user just loses cross-reload persistence.
    try { localStorage.setItem('sb_watchlist_alerts', JSON.stringify(next)); } catch (_) {}
    setEditingAlert(null);
    // Pre-fix this was a silent click — the user typed a price, hit Set,
    // and got no confirmation that anything happened. Toast surfaces
    // the result so the action reads as real. The alert itself is
    // device-local; signed-in users get push notifications via the
    // server-side bell + email path which is wired separately.
    try {
      toast(cleared
        ? 'Price alert cleared.'
        : `Price alert set — we'll notify you when this drops to ${fmt(n)}.`, 'ok');
    } catch (_) { /* toast helper missing — non-fatal */ }
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
    // One batched /api/items/batch call per 50 ids (server caps at 50) instead
    // of one GET per missing id — kills the watchlist N+1 on modal open.
    const chunks = [];
    for (let i = 0; i < missing.length; i += 50) chunks.push(missing.slice(i, i + 50));
    Promise.all(chunks.map(c => fetchItemsByIds(c).catch(() => []))).then(chunkResults => {
      if (!alive) return;
      const next = { ...fallbackItems };
      chunkResults.flat().forEach(item => { if (item && item.id != null) next[item.id] = item; });
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
      // Null-item listing guard (resilience audit). A house/system row — or a
      // listing whose item DTO didn't populate — has a null `item`; `l.item.id`
      // would throw a TypeError that bubbles to the top-level ErrorBoundary and
      // wedges /watchlist UNRECOVERABLY (reload re-crashes). The same `pool` is
      // already guarded with `l?.item?.id` 17 lines up (the present-set memo) and
      // ~10 other sites use `l?.item`; this forEach was the lone outlier. A
      // null-item row can't be a watched item's listing anyway, so skip it.
      if (!l?.item) return;
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
      // Same QuotaExceededError guard as sb_cart / sb_watchlist persist
      // writes — an unguarded throw here bubbles out of the effect and
      // crashes the WatchlistModal render. Snapshots stay in-memory;
      // user just loses cross-reload "−$X since starred" delta on full
      // storage.
      try { localStorage.setItem('sb_watchlist_snap', JSON.stringify(next)); } catch (_) {}
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
  // Apply drops + category + state filters together. Category filter is
  // skipped when the user hasn't picked one; 'All' is a label-only default.
  const filteredBeforeSort = (() => {
    let list = showDropsOnly ? rows.filter(r => r.delta < 0 || r.alertHit) : rows;
    if (catFilter !== 'All') {
      list = list.filter(r => (r.listing?.item?.category || 'Other') === catFilter);
    }
    // Batch 640 — state filter. LISTED = has an active listing now;
    // UNAVAILABLE = stub row (item exists in catalogue, currently no
    // active listing).
    if (stateFilter === 'LISTED') {
      list = list.filter(r => !r.listing?.__noListing);
    } else if (stateFilter === 'UNAVAILABLE') {
      list = list.filter(r => r.listing?.__noListing === true);
    }
    return list;
  })();
  // Distinct categories represented in the watchlist — drives the chip row.
  const categoriesInWatchlist = (() => {
    const s = new Set();
    rows.forEach(r => { if (r.listing?.item?.category) s.add(r.listing.item.category); });
    return Array.from(s).sort();
  })();
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

  return h(InfoModal, { title: `Watchlist · ${starred.length} item${starred.length === 1 ? '' : 's'}`, onClose, wide: true },
    // Batch 695 — CSV export pill. Only rendered once the user has
    // actually starred something — otherwise it's noise on the empty
    // state. Placed at the top-right via margin-left: auto so it sits
    // out of the way of the main content.
    starred.length > 0 && h('div', { style: { display: 'flex', marginBottom: 10 } },
      h('div', { style: { flex: 1 } }),
      h('a', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        href: '/api/watchlist/export.csv',
        title: 'Download your watchlist as CSV — item ids, current floor, Steam price, and supply. Useful for tracking price movement across spreadsheet snapshots.'
      }, '⇣ CSV')
    ),
    starred.length === 0
      ? h('div', null,
          // Boss QA G10 — empty-state icon bumped to 48px in a 72px
          // accent-tinted chip; inline SVG inbox so the glyph survives
          // even when Material Symbols font hasn't finished loading
          // (headless screenshots, slow Google Fonts CDN).
          h('div', { className: 'empty-inline' },
            h('div', {
              className: 'empty-icon empty-icon-lg',
              style: {
                width: 72, height: 72, borderRadius: 18,
                margin: '0 auto 14px',
                background: 'color-mix(in oklab, var(--accent) 8%, var(--bg-1))',
                border: '1px solid color-mix(in oklab, var(--accent) 18%, var(--line))',
                color: 'color-mix(in oklab, var(--accent) 85%, var(--ink-2))',
                display: 'grid', placeItems: 'center'
              }
            /* Boss QA cycle 11 — replaced generic shopping-cart SVG with a
               watchlist-themed heart-on-card line drawing tied to the page
               topic. Cycle 11 prompt: "Even a simple SVG line-drawing tied
               to the page topic feels $10M." */
            }, h('svg', {
                width: 40, height: 40, viewBox: '0 0 24 24', fill: 'none',
                stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round',
                strokeLinejoin: 'round', 'aria-hidden': true
              },
                h('rect', { x: 3, y: 4, width: 18, height: 16, rx: 2.5 }),
                h('path', { d: 'M7 9.5h10' }),
                h('path', { d: 'M7 13h6' }),
                h('path', { d: 'M16.5 16.5c1.4-1.1 2.5-2 2.5-3.2 0-1-.8-1.8-1.8-1.8-.5 0-1 .2-1.4.6-.4-.4-.9-.6-1.4-.6-1 0-1.8.8-1.8 1.8 0 1.2 1.1 2.1 2.5 3.2.3.2.7.2 1.4 0z', fill: 'currentColor', stroke: 'none' })
              )),
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, 'Nothing on your watchlist'),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 14px' } },
              !me
                ? 'Sign in with Steam, then tap the ♡ on any item card to track price drops here.'
                : 'Click the ♡ on any item card and it will show up here with a live price-drop alert.'),
            !me
              ? h('button', {
                  className: 'btn btn-accent',
                  onClick: () => signInWithSteam()
                }, 'Sign in with Steam')
              : h('a', { className: 'btn btn-accent', href: '/market' }, 'Browse marketplace →')
          ),
          // Recently-viewed jumpstart (batch 428). Mirrors the empty-cart
          // pattern from batch 427 — offers one-click deep-links back to
          // items the user already considered, so a first-time ♡ visit
          // doesn't dead-end at "Browse marketplace" alone.
          (() => {
            let recent = [];
            try { recent = JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]'); }
            catch { recent = []; }
            recent = (recent || []).slice(0, 6);
            if (recent.length === 0) return null;
            return h('div', { style: { marginTop: 20 } },
              h('div', {
                style: {
                  fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase',
                  letterSpacing: 0.5, fontWeight: 700, marginBottom: 10, textAlign: 'center'
                }
              }, '⟲ Recently viewed · tap ♡ when you visit one'),
              h('div', {
                style: {
                  display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center'
                }
              },
                recent.map(it => {
                  // Batch 912 — same property-name fix as the cart-empty
                  // pill (app.js:6067). Stored records use imageUrl +
                  // lowestPrice; the pill was reading thumb + price and
                  // silently rendering naked without thumbnail / price.
                  const priceVal = it.lowestPrice != null ? parseFloat(it.lowestPrice) : null;
                  return h('a', {
                  key: 'rv-wl-' + it.id,
                  href: '/item/' + it.id,
                  style: {
                    display: 'flex', alignItems: 'center', gap: 8,
                    padding: '6px 12px', borderRadius: 999,
                    background: 'var(--bg-elevated)',
                    border: '1px solid var(--border)',
                    textDecoration: 'none', color: 'var(--text-primary)',
                    fontSize: 12, fontWeight: 600
                  },
                  title: it.name + (priceVal != null ? ' · ' + fmt(priceVal) : '')
                },
                  it.imageUrl && h('img', {
                    src: it.imageUrl, alt: '',
                    style: { width: 18, height: 18, borderRadius: 4, objectFit: 'cover' }
                  }),
                  h('span', { style: { maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, it.name),
                  priceVal != null && h('span', { style: { color: 'var(--accent)', fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } }, fmt(priceVal))
                );
                })
              )
            );
          })()
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
                  'Price alerts'),
                h('span', { style: { color: 'var(--green)', fontWeight: 700 } }, `${active.length} watching`),
                fired.length > 0 && h('span', { style: { color: '#fbbf24', fontWeight: 700 } },
                  ` · ${fired.length} fired`),
                h('div', { style: { flex: 1 } }),
                fired.length > 0 && h('button', {
                  className: 'btn btn-ghost',
                  style: { padding: '3px 10px', fontSize: 11, border: '1px solid var(--border)' },
                  title: 'Delete every fired alert so only still-watching rows stay',
                  onClick: async () => {
                    // Batch 913 — snapshot the count before clearing so
                    // the toast cites the number of rows just deleted.
                    const before = fired.length;
                    const { clearFiredWatchlistAlerts } = await import('./api.js');
                    const res = await clearFiredWatchlistAlerts();
                    if (res && (res.error || res.code)) {
                      toast(res.message || res.error || 'Could not clear fired alerts', 'err');
                      return;
                    }
                    loadServerAlerts();
                    toast(`${before} fired alert${before === 1 ? '' : 's'} cleared.`, 'ok');
                  }
                }, 'Clear fired')
              ),
              active.length > 0 && h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
                active.slice(0, 5).map(a => {
                  // Enrich with item name + current floor (batch 394). Look
                  // up the cheapest active listing for this item from the
                  // allListings prop the modal already has in scope — zero
                  // extra round trips. Falls back to "Item #N" when no
                  // listing matches (new/out-of-stock item).
                  let itemName = null;
                  let floor = null;
                  if (Array.isArray(allListings)) {
                    for (const l of allListings) {
                      if (!l || !l.item) continue;
                      if (l.item.id !== a.itemId) continue;
                      if (l.status && l.status !== 'ACTIVE') continue;
                      itemName = itemName || l.item.name;
                      const p = parseFloat(l.price);
                      if (Number.isFinite(p) && (floor == null || p < floor)) floor = p;
                    }
                  }
                  const target = parseFloat(a.targetPrice);
                  // Restock-mode alerts encode targetPrice ≥ 99999 as
                  // "any future listing." For those the price gap is
                  // meaningless — surface a "watching" state instead.
                  const isRestock = Number.isFinite(target) && target >= 99999;
                  const gap = (!isRestock && Number.isFinite(target) && floor != null) ? floor - target : null;
                  const gapPct = (gap != null && target > 0) ? (gap / target) * 100 : null;
                  let gapLabel = null;
                  let gapColor = 'var(--text-muted)';
                  if (isRestock) {
                    if (floor == null) {
                      gapLabel = 'awaiting restock';
                      gapColor = 'var(--text-muted)';
                    } else {
                      gapLabel = 'restock fires next sweep';
                      gapColor = 'var(--green)';
                    }
                  } else if (gap != null) {
                    if (gap <= 0) { gapLabel = 'match ready'; gapColor = 'var(--green)'; }
                    else if (gapPct < 10) { gapLabel = `${gapPct.toFixed(0)}% above`; gapColor = '#fbbf24'; }
                    else { gapLabel = `${gapPct.toFixed(0)}% above`; gapColor = 'var(--text-muted)'; }
                  } else if (floor == null) {
                    gapLabel = 'no listings';
                    gapColor = 'var(--text-muted)';
                  }
                  return h('div', {
                    key: a.id,
                    style: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: 'var(--text-secondary)' }
                  },
                    h('a', {
                      href: '/item/' + a.itemId,
                      style: { color: 'var(--accent)', textDecoration: 'none', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
                      title: itemName || ('Item #' + a.itemId)
                    }, itemName || ('Item #' + a.itemId)),
                    h('span', {
                      className: 'db-mono',
                      style: { color: 'var(--text-primary)', fontWeight: 700 },
                      title: isRestock
                        ? 'Restock-style alert — fires on any future listing regardless of price.'
                        : `Fires when the floor drops to or below ${fmt(a.targetPrice)}.`
                    }, isRestock ? '↻ restock' : ('≤ ' + fmt(a.targetPrice))),
                    gapLabel && h('span', {
                      style: { fontSize: 10, color: gapColor, fontWeight: 700 },
                      title: gap != null
                        ? (gap <= 0
                            ? `Current floor ${fmt(floor)} is at or below your target — the sweeper will fire this alert on its next pass.`
                            : `Current floor ${fmt(floor)} — ${fmt(gap)} above your target.`)
                        : 'No active listings on this item right now.'
                    }, gapLabel),
                  // Edit target — batch 832 replaces the window.prompt
                  // with an inline number input + Save/Cancel so the
                  // user doesn't lose the visual context of which
                  // alert row they're editing. Uses the same upsert
                  // endpoint as the "Set Price Alert" button on the
                  // item modal (WatchlistAlertService.upsertAlert is
                  // an upsert, so resaving with the same itemId
                  // updates the existing row).
                  editingServerAlertId === a.id
                    ? h('span', { style: { display: 'inline-flex', gap: 4, alignItems: 'center' } },
                        h('input', {
                          className: 'price-input',
                          type: 'number', min: '0.01', step: '0.01',
                          inputMode: 'decimal', enterKeyHint: 'done',
                          'aria-label': 'New alert target price',
                          style: { width: 72, padding: '1px 6px', fontSize: 10, height: 22 },
                          value: serverAlertDraft,
                          onChange: e => setServerAlertDraft(e.target.value),
                          onKeyDown: (e) => {
                            if (e.key === 'Enter' && !serverAlertBusy) {
                              e.preventDefault(); saveEditServerAlert(a);
                            } else if (e.key === 'Escape') {
                              e.preventDefault();
                              setEditingServerAlertId(null);
                              setServerAlertDraft('');
                            }
                          },
                          autoFocus: true
                        }),
                        h('button', {
                          className: 'btn btn-ghost',
                          style: { padding: '1px 6px', fontSize: 10, border: '1px solid var(--border)' },
                          disabled: serverAlertBusy,
                          onClick: () => saveEditServerAlert(a),
                          title: 'Save new target',
                          'aria-label': 'Save new target'
                        }, '✓'),
                        h('button', {
                          className: 'btn btn-ghost',
                          style: { padding: '1px 6px', fontSize: 10, border: '1px solid var(--border)' },
                          disabled: serverAlertBusy,
                          onClick: () => { setEditingServerAlertId(null); setServerAlertDraft(''); },
                          title: 'Cancel edit',
                          'aria-label': 'Cancel alert edit'
                        }, '✕')
                      )
                    : h('button', {
                        className: 'btn btn-ghost',
                        style: { padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)' },
                        onClick: () => startEditServerAlert(a),
                        title: 'Change the target price for this alert',
                        'aria-label': 'Change the target price for this alert'
                      }, '✎'),
                  editingServerAlertId !== a.id && h('button', {
                    className: 'btn btn-ghost',
                    style: { padding: '2px 8px', fontSize: 10, border: '1px solid var(--border)' },
                    onClick: () => cancelServerAlert(a.id),
                    title: 'Cancel this price alert',
                    'aria-label': 'Cancel this price alert'
                  }, '✕')
                  );
                }),
                active.length > 5 && h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 4 } },
                  `+ ${active.length - 5} more`)
              )
            );
          })(),
          // Category chip row — only rendered when the watchlist spans
          // more than one category, so a user with 3 items in the same
          // category doesn't see noise. Each chip is a one-click filter.
          categoriesInWatchlist.length > 1 && h('div', {
            style: { display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10 }
          },
            h('button', {
              className: `wallet-tx-filter-chip ${catFilter === 'All' ? 'active' : ''}`,
              'aria-pressed': catFilter === 'All',
              onClick: () => setCatFilter('All')
            }, `All · ${rows.length}`),
            categoriesInWatchlist.map(c => {
              const n = rows.filter(r => (r.listing?.item?.category || 'Other') === c).length;
              return h('button', {
                key: c,
                className: `wallet-tx-filter-chip ${catFilter === c ? 'active' : ''}`,
                'aria-pressed': catFilter === c,
                onClick: () => setCatFilter(c),
                disabled: n === 0
              }, `${c} · ${n}`);
            })
          ),
          // Batch 640 — State filter row (CSFloat Visual Manual §27).
          // All / Listed / Unavailable. Counts are derived from the
          // pre-state-filter rows so each chip always shows the real
          // bucket size regardless of which is currently selected.
          // Hidden when the watchlist is uniformly one state (e.g. a
          // user whose entire watchlist is actively listed) so the row
          // doesn't clutter an uninteresting view.
          (() => {
            const listedN = rows.filter(r => !r.listing?.__noListing).length;
            const gone    = rows.filter(r => r.listing?.__noListing === true).length;
            if (listedN === 0 || gone === 0) return null; // uniform state, hide
            return h('div', {
              style: { display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10, alignItems: 'center' }
            },
              h('span', { style: { fontSize: 11, color: 'var(--text-muted)', fontWeight: 700, marginRight: 2 } }, 'State'),
              [
                { id: 'ALL',          label: 'All',          n: rows.length },
                { id: 'LISTED',       label: 'Listed',       n: listedN },
                { id: 'UNAVAILABLE',  label: 'Unavailable',  n: gone }
              ].map(opt => h('button', {
                key: opt.id,
                className: `wallet-tx-filter-chip ${stateFilter === opt.id ? 'active' : ''}`,
                onClick: () => setState(opt.id),
                title: opt.id === 'UNAVAILABLE'
                  ? 'Watched items currently off the market (sold out or delisted)'
                  : opt.id === 'LISTED'
                    ? 'Watched items you can buy right now'
                    : 'Every watched item'
              }, `${opt.label} · ${opt.n}`))
            );
          })(),
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' }, role: 'tablist', 'aria-label': 'Watchlist filter' },
            // WAI-ARIA tabs pattern + keyboard nav, mirroring WalletModal /
            // LoadoutModal / MyStallModal. Use navigate() (not raw pushState)
            // so the SPA router fires popstate → title-effect → updates
            // document.title to "All Items · Watchlist" / "Price drops · Watchlist".
            (() => {
              const TABS = [false, true]; // showDropsOnly values
              const onKey = (e) => {
                if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
                e.preventDefault();
                const idx = TABS.indexOf(showDropsOnly);
                let n = idx;
                if (e.key === 'ArrowRight' || e.key === 'End') n = TABS.length - 1;
                else if (e.key === 'ArrowLeft' || e.key === 'Home') n = 0;
                const next = TABS[n];
                setShowDropsOnly(next);
                navigate('/watchlist/' + (next ? 'drops' : 'all'));
              };
              return [
                h('button', {
                  key: 'all',
                  className: `offer-tab ${!showDropsOnly ? 'active' : ''}`,
                  role: 'tab',
                  'aria-selected': !showDropsOnly,
                  // WCAG 4.1.2 — aria-controls points at the result region
                  // both tabs filter (same panel, different filter state).
                  'aria-controls': 'watchlist-results',
                  tabIndex: !showDropsOnly ? 0 : -1,
                  onKeyDown: onKey,
                  onClick: () => { setShowDropsOnly(false); navigate('/watchlist/all'); }
                }, 'All ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, rows.length)),
                h('button', {
                  key: 'drops',
                  className: `offer-tab ${showDropsOnly ? 'active' : ''}`,
                  role: 'tab',
                  'aria-selected': showDropsOnly,
                  'aria-controls': 'watchlist-results',
                  tabIndex: showDropsOnly ? 0 : -1,
                  onKeyDown: onKey,
                  onClick: () => { setShowDropsOnly(true); navigate('/watchlist/drops'); }
                }, 'Price drops ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, rows.filter(r => r.delta < 0).length))
              ];
            })(),
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
            // Bulk clear — one round-trip via DELETE /api/watchlist
            // instead of N per-item DELETEs. Drops price snapshots +
            // alert targets too so a re-star doesn't resurrect stale
            // state. Parent watchlist state is cleared by unstarring
            // each id through `onToggleStar` so the marketplace cards
            // un-highlight immediately — the server-side DELETE is a
            // best-effort reconcile on top.
            rows.length > 0 && h('button', {
              className: 'btn-danger-ghost',
              style: { padding: '6px 12px', fontSize: 11 },
              onClick: async () => {
                if (!confirm(`Clear all ${rows.length} watchlisted items?`)) return;
                try {
                  const { clearWatchlist } = await import('./api.js');
                  await clearWatchlist();
                } catch (_) { /* best-effort; state sync below still runs */ }
                // Drop client-side state after the server flushes so
                // the marketplace grid immediately un-stars cards.
                starred.forEach(l => { if (l?.item?.id) onToggleStar(l.item.id); });
                localStorage.removeItem('sb_watchlist_snap');
                localStorage.removeItem('sb_watchlist_alerts');
                setSnapshots({});
                setAlerts({});
                toast(`Cleared ${rows.length} watched item${rows.length === 1 ? '' : 's'}.`, 'ok');
              }
            }, '✕ Clear all')
          ),
          filtered.length === 0
            ? h('div', { id: 'watchlist-results', className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'inbox', size: 26 })),
                h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
                  stateFilter === 'LISTED'
                    ? 'None of your watched items are actively listed right now.'
                    : stateFilter === 'UNAVAILABLE'
                      ? 'All of your watched items are currently listed — no unavailable rows.'
                      : catFilter !== 'All'
                        ? `No watched items in "${catFilter}".`
                        : showDropsOnly
                          ? 'No price drops yet. We remember what each item cost when you starred it and show the diff here.'
                          : 'No watched items to show.'),
                (catFilter !== 'All' || showDropsOnly || stateFilter !== 'ALL') && h('button', {
                  className: 'btn btn-ghost',
                  style: { marginTop: 10, border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
                  onClick: () => { setCatFilter('All'); setShowDropsOnly(false); setState('ALL'); }
                }, 'Clear filters'))
            : h('div', { id: 'watchlist-results', className: 'listing-grid', style: { gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' } },
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
                    'Alert hit'),
                  // Alert control strip — click to set, edit, or clear a
                  // per-item price target. Persisted in sb_watchlist_alerts.
                  !r.listing.__noListing && h('div', { className: 'watchlist-alert-bar' },
                    editingAlert === r.listing.item.id
                      ? h('div', { className: 'watchlist-alert-edit' },
                          h('input', {
                            type: 'number',
                            step: '0.01',
                            min: '0',
                            inputMode: 'decimal',
                            enterKeyHint: 'done',
                            'aria-label': 'Target price (USD-anchored)',
                            placeholder: `Target price (${currencySymbol()})`,
                            title: 'Type a price (USD-anchored). When the listing drops at or below this number, you get a notification.',
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
                          ? `Alert ≤ ${fmt(r.target)}`
                          : 'Set price alert')
                  )
                )))
        )
  );
}

// ── Wallet (deposit/withdraw/history) ───────────────────────────
export function WalletModal({ wallet, transactions, me, onClose, onRefresh, initialTab, prefillAmount }) {
  const [tab, setTab]       = useState(initialTab || 'deposit');
  const [amount, setAmount] = useState(prefillAmount != null ? String(prefillAmount) : '');
  // Batch 991 — honour the sb_privacy toggle on the wallet hero too.
  // Previously only the nav-bar wallet button masked the balance when
  // privacy mode was on; the full WalletModal showed the real amount,
  // so privacy was half-effective (a screenshot / shoulder-surfing
  // user still saw the number the moment they opened the wallet).
  // The maskMoney helper pairs with the privacy state so any `fmt()`
  // call in the hero + pending-chips + transaction rows substitutes
  // `$•••••` when the user has opted into masking.
  const [privacy, setPrivacy] = useState(() => {
    try { return localStorage.getItem('sb_privacy') === '1'; } catch { return false; }
  });
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key === 'sb_privacy') setPrivacy(e.newValue === '1');
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
  const maskMoney = (v) => privacy ? '$•••••' : fmt(v);
  // Buyer-side spend summary (batch 846) — 7d / 30d / lifetime PURCHASE
  // totals + counts. Mirrors the seller-earnings strip on /me/stall.
  // Silent until the first purchase completes so new users don't see
  // a row of zeros dominating the hero.
  const [spend, setSpend] = useState(null);
  useEffect(() => {
    let alive = true;
    if (!me) return;
    fetchWalletSpend().then(s => { if (alive) setSpend(s); });
    return () => { alive = false; };
  }, [me, wallet?.balance]);
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
  useEffect(() => {
    if (initialTab && (initialTab === 'deposit' || initialTab === 'withdraw' || initialTab === 'history')) {
      setTab(initialTab);
    }
  }, [initialTab]);
  // Batch 829 / Batch 1167 — Escape closes the wallet modal AND focus
  // trap keeps Tab inside it AND focus restores to the trigger on
  // unmount. Busy state lives per submit call so the keyboard dismiss
  // is safe at rest; in-flight Stripe checkout redirects away from the
  // page before Esc could fire.
  const panelRef = useRef(null);
  // Synchronous re-entrancy guard (matches buyConfirmBusyRef / trades busyRef):
  // the `busy` state flag is async, leaving a rapid-double-click window that
  // could fire two withdrawal/deposit POSTs. This ref latches synchronously.
  const submittingRef = useRef(false);
  useDialogA11y(panelRef, onClose);
  const [totpCode, setTotpCode] = useState('');
  const [busy, setBusy]     = useState(false);
  const [error, setError]   = useState('');
  // Cash-out / payout status (Stripe Connect). `null` = still loading;
  // once resolved it's { payoutsEnabled, onboardingNeeded }. In dev with
  // no Stripe keys the endpoint 404s / returns null → we treat that as
  // "setup needed", which is harmless (the setup button still renders and
  // the backend gates the actual withdrawal). `onboarding` flips true
  // while we're redirecting to the Stripe-hosted flow.
  const [connect, setConnect] = useState(null);
  const [onboarding, setOnboarding] = useState(false);
  useEffect(() => {
    let alive = true;
    if (!me) return;
    fetchConnectStatus().then(s => {
      if (!alive) return;
      // Normalise: a null response (endpoint off / dev) means setup is
      // still needed. payoutsEnabled drives the "ready" branch.
      // `live` / `simulated` are carried through because they decide what the
      // withdraw form may ask for: on a live deployment the payout goes to the
      // wallet's Stripe Connect account and nothing the user types is used,
      // while a simulated (keyless) deployment has no Stripe account to
      // onboard at all -- its "Set up cash-out" button only bounced the page.
      setConnect(s
        ? { payoutsEnabled: !!s.payoutsEnabled, onboardingNeeded: s.onboardingNeeded || !s.payoutsEnabled,
            live: !!s.live, simulated: !!s.simulated && !s.live }
        : { payoutsEnabled: false, onboardingNeeded: true });
    });
    return () => { alive = false; };
  }, [me, wallet?.balance]);
  // Kick off Stripe-hosted onboarding (identity verification + bank/card
  // payout) and redirect the browser to the returned URL.
  const startCashoutSetup = async () => {
    setOnboarding(true);
    setError('');
    try {
      const res = await connectOnboard();
      // Backend returns the Stripe-hosted onboarding URL as `onboardingUrl`
      // (both live + dev/simulated modes). Accept `url` too as a defensive
      // fallback in case the contract ever changes.
      const url = res && (res.onboardingUrl || res.url);
      if (url) { window.location.href = url; return; }
      // No URL back — surface a friendly message rather than the raw code.
      setError((res && res.message) || 'Could not start cash-out setup. Please try again.');
    } catch (_) {
      setError('Could not start cash-out setup. Please try again.');
    } finally {
      setOnboarding(false);
    }
  };
  // History type filter. Values match the Transaction.type strings the
  // backend serializes — DEPOSIT / SALE / PURCHASE / WITHDRAW / REFUND /
  // ADJUSTMENT_CREDIT / ADJUSTMENT_DEBIT. 'ALL' = no filter.
  const [txTypeFilter, setTxTypeFilter] = useState('ALL');
  // A transaction reference as a CUSTOMER may see it.
  //
  // 5941d57 changed the admin credit stamp from 'admin' to 'admin_<adminUserId>'
  // so the rolling 24h cap could be keyed per actor -- that is load-bearing and
  // stays in the database. But this string is rendered in the user's OWN wallet
  // history and in the copy-for-support text, so it was showing customers the
  // internal id of the staff member who touched their account.
  //
  // Collapse it back for display only. Also covers 'admin_null', which would
  // otherwise render literally if adminUserId were ever absent.
  function customerFacingRef(ref) {
    if (!ref) return ref;
    return /^admin(_.*)?$/.test(ref) ? 'admin' : ref;
  }

  // Free-text search over description + stripeReference. Great for
  // finding "that listing I bought" or "where did this withdrawal go"
  // without scrolling through 500 rows.
  const [txSearch, setTxSearch] = useState('');
  // Batch 638 — month picker (CSFloat Visual Manual §24 parity).
  // Value is 'ALL' for no month filter, or 'YYYY-MM' for a specific
  // calendar month. Starts at 'ALL' so the view opens with every
  // transaction visible, not silently scoped to the current month.
  const [txMonth, setTxMonth] = useState('ALL');

  // Batch 720 removed a fee breakdown that claimed deductions the server
  // never applied — the UI was lying in the user's favour. Under the
  // pass-through pricing decision the server DOES deduct now, so the
  // breakdown is back, and the same rule applies in the other direction:
  // it must show exactly what will happen, never a rounder, friendlier
  // number. A deposit form promising $100 that credits $96.80 is a
  // chargeback generator.
  //
  // Rates come from the server (`wallet.feeSchedule`), never hardcoded —
  // a client carrying its own copy of "2.9%" silently stops matching the
  // day an operator sets a negotiated rate. `active` is false in dev mode
  // (no Stripe keys ⇒ no Stripe charge), so the preview shows no
  // deduction there, matching devModeDeposit crediting gross.
  //
  // The authoritative figures still come back on the /deposit and
  // /withdraw responses; this is the pre-commit estimate.
  const amt = parseFloat(amount) || 0;
  const feeSchedule = (wallet && wallet.feeSchedule) || null;
  const feesActive = !!(feeSchedule && feeSchedule.active);
  // FLOOR, mirroring PlatformLedgerService.feeCharged, which uses
  // RoundingMode.FLOOR so the sub-cent goes to the user.
  //
  // The Math.round-before-floor is NOT decoration. The obvious
  // `Math.floor(raw * 100) / 100` disagrees with the server's BigDecimal
  // on 14 of the 99,802 whole-cent amounts between $1.00 and $500.00 —
  // including **$20.00**, which is a preset button: 20 * 2.9 / 100 + 0.30
  // lands a hair under 0.88 in binary floating point, so the naive floor
  // quotes a $0.87 fee against the $0.88 actually charged and promises a
  // credit one cent too high. Scaling to micro-units and rounding there
  // first absorbs the representation error before the floor sees it.
  //
  // Verified exhaustively against the BigDecimal implementation across
  // both fee legs for every whole-cent amount in that range: 99,802/99,802
  // exact. A preview that is only usually right teaches users to stop
  // reading it, and this one is load-bearing disclosure.
  const feeFor = (gross, pct, fixed) => {
    if (!(gross > 0)) return 0;
    const raw = (gross * (parseFloat(pct) || 0)) / 100 + (parseFloat(fixed) || 0);
    return Math.max(0, Math.floor(Math.round(raw * 1e6) / 1e4) / 100);
  };
  const previewRateFee = !feesActive || !(amt > 0) ? 0 : (tab === 'deposit'
    ? feeFor(amt, feeSchedule.depositFeePercent, feeSchedule.depositFeeFixed)
    : feeFor(amt, feeSchedule.withdrawalFeePercent, feeSchedule.withdrawalFeeFixed));
  // The leg that is not a rate, and the reason the preview was wrong.
  //
  // Stripe Connect bills a fixed charge per monthly-active payout account.
  // The server passes it through on a payout BELOW the break-even and
  // absorbs it at or above (StripeService.requestWithdrawal), so on a $10
  // first-of-month withdrawal the real deduction is $0.27 + $2.00 — and
  // this preview, built only from the percentage legs, promised $0.27 and
  // "you receive $9.73" against an actual $7.73. Disclosure that is wrong
  // by 20% of the payout is worse than none: the user consents to a number
  // that never existed.
  //
  // Three inputs, all SERVER-SUPPLIED, none inferred: whether the charge is
  // due on this wallet this month (`perAccountChargeDue`), how much it is
  // (`perAccountMonthlyFee`), and the amount at or above which it is waived
  // (`perAccountWaiverAt`). The single comparison below is the same one the
  // server makes. All three collapse to zero on a rail with no per-account
  // charge and in dev mode, where the line simply does not render.
  const perAccountFee   = parseFloat((feeSchedule && feeSchedule.perAccountMonthlyFee) || 0) || 0;
  const perAccountWaive = parseFloat((feeSchedule && feeSchedule.perAccountWaiverAt) || 0) || 0;
  const perAccountDue   = !!(wallet && wallet.perAccountChargeDue);
  const previewAccountFee = (tab === 'withdraw' && feesActive && perAccountDue
      && amt > 0 && perAccountFee > 0 && amt < perAccountWaive) ? perAccountFee : 0;
  const previewFee = previewRateFee + previewAccountFee;
  const previewNet = Math.max(0, amt - previewFee);

  const submit = async () => {
    if (submittingRef.current) return;
    setError('');
    const num = parseFloat(amount);
    if (!num || num <= 0) { setError('Enter a valid amount'); return; }
    // Mirror the server's minimum so a too-small amount gets an actionable
    // inline message instead of a generic "Request body failed validation"
    // round-trip.
    //
    // The number is READ, never recomputed. It used to be a hardcoded $1.00
    // on both legs, matching the @DecimalMin on the request DTOs; both are
    // now DERIVED server-side from the processor rates and the per-account
    // payout charge (PlatformLedgerService.minDeposit / minWithdrawal), so a
    // second copy here would be wrong the moment a rate moved. The $1.00
    // fallback is the DTO floor that still applies underneath, and is what
    // this sees before /api/wallet has loaded or when pass-through pricing
    // is off.
    //
    // The absolute floor below is a CENT, matching WithdrawRequest's
    // @DecimalMin after it was lowered from $1.00. It used to be $1.00 here
    // too, and the pair of them cancelled the full-balance sweep exemption
    // for any balance under a dollar — the one case the exemption exists
    // for.
    //
    // Withdrawals use the per-wallet figure: the per-account charge falls
    // once a calendar month, so the minimum is higher on this wallet's first
    // payout of the month. A full-balance withdrawal is exempt from it
    // server-side and must not be blocked here either — that exemption is
    // the reason a raised minimum cannot strand a balance.
    const serverMin = tab === 'withdraw'
      ? parseFloat((wallet && wallet.minWithdrawalNow) || 0)
      : parseFloat((feeSchedule && feeSchedule.minDeposit) || 0);
    const bal = parseFloat((wallet && wallet.balance) || 0);
    const isSweep = tab === 'withdraw' && bal > 0 && Math.abs(num - bal) < 0.005;
    const minAmt = Math.max(0.01, serverMin || 0);
    if (num < minAmt && !isSweep) {
      setError(tab === 'withdraw'
        ? 'Minimum withdrawal is $' + minAmt.toFixed(2) + ' — or withdraw your full balance in one go.'
        : 'Minimum deposit is $' + minAmt.toFixed(2) + '.');
      return;
    }
    if (num > 10000) { setError('Maximum per transaction is $10,000'); return; }
    // No typed "payout destination". The form used to REQUIRE one ("Stripe
    // Connect ID or bank reference") although StripeService.requestWithdrawal
    // never reads it: live payouts go to the wallet's Stripe Connect account
    // and the simulated path writes its own dev_payout_ reference. The field
    // only made sellers type a meaningless string -- or their bank details --
    // into a box whose value was discarded.
    submittingRef.current = true;
    setBusy(true);
    try {
      if (tab === 'deposit') {
        const res = await depositFunds(num);
        if (res.code || res.error) { setError(res.message || res.error); return; }
        if (res.live && res.checkoutUrl) {
          // Real Stripe redirect — the user will land back on
          // /?deposit=success which already triggers a wallet refresh +
          // toast in app.js so no toast needed here.
          window.location.href = res.checkoutUrl;
          return;
        }
        setAmount('');
        await onRefresh();
        // Pre-fix: dev/test path was silent on success. The amount input
        // cleared and the balance updated, but a user clicking "Deposit
        // $50" got no explicit confirmation that the credit landed.
        // Mirrors the named-success pattern from buy/offer/bid handlers.
        toast(`Deposited ${fmt(num)} — your wallet balance updated.`, 'ok');
      } else {
        const res = await withdrawFunds(num, undefined, totpCode);
        if (res.code || res.error) {
          // Seller hasn't finished Stripe Connect onboarding yet — don't
          // dump the raw CONNECT_ONBOARDING_REQUIRED code. Point them at
          // the "Set up cash-out" card and make sure it's showing.
          if (res.code === 'CONNECT_ONBOARDING_REQUIRED') {
            setConnect({ payoutsEnabled: false, onboardingNeeded: true });
            setError('Finish setting up cash-out before you withdraw — use the “Set up cash-out” button above.');
            return;
          }
          // If 2FA required but missing, hint at the TOTP input instead
          // of just showing the raw message.
          if (res.code === 'TOTP_REQUIRED' || res.code === 'TOTP_INVALID') {
            setError(res.message || 'Two-factor code required');
          } else {
            setError(res.message || res.error);
          }
          return;
        }
        setAmount(''); setTotpCode('');
        await onRefresh();
        // Pre-fix: silent on success — user requested a withdraw, the
        // form cleared, and the only feedback was the wallet hero
        // ticking down. Withdrawals are PENDING until staff approves
        // (could be hours), so an explicit toast is critical so the
        // user understands the money isn't out yet but the request is
        // queued.
        // Say what actually happened. /withdraw completes immediately: live,
        // it is a Stripe Transfer to the seller's cash-out account (Stripe then
        // pays the bank on its own schedule); simulated, nothing is sent at
        // all. The old "pending staff review" toast described neither.
        if (res.status === 'PENDING') {
          toast(`Withdrawal of ${fmt(num)} requested — pending review. Track status in History.`, 'ok');
        } else if (connect && connect.simulated) {
          toast(`Test-mode withdrawal of ${fmt(num)} recorded — no real money was sent (no payment processor connected).`, 'ok');
        } else {
          toast(`Withdrawal of ${fmt(num)} sent to your Stripe cash-out account — it reaches your bank on Stripe's payout schedule, usually 1–2 business days.`, 'ok');
        }
      }
    } catch (e) {
      setError(e.message || 'Request failed');
    } finally {
      setBusy(false);
      submittingRef.current = false;
    }
  };

  const presets = tab === 'deposit' ? [25, 50, 100, 250, 500] : [25, 50, 100, 250];

  /* In full-page mode (.site-root.full-page-mode) the backdrop is a normal
     in-flow div — clicking outside the .modal would otherwise bounce back
     to /. Skip onClose then so /wallet behaves like a real page. */
  const handleBackdropClick = (e) => {
    if (document.querySelector('.site-root.full-page-mode')) return;
    onClose && onClose();
  };
  // Boss QA G8 — /wallet was rendering "$0.00" + Deposit / Withdraw /
  // History tabs even for anonymous viewers, which read like a real
  // empty wallet a returning user might mistakenly try to top up. Now
  // anon visitors get the same Sign-in gate as /profile and /sell so
  // the deposit form never appears until the account exists.
  if (!me) {
    return h(InfoModal, { title: 'Wallet', onClose },
      h(SignInNeededEmptyState, { what: 'your wallet, deposits, and withdrawal history' }));
  }
  return h('div', { className: 'modal-backdrop', onClick: handleBackdropClick },
    h('div', {
      ref: panelRef,
      className: 'modal wallet-modal',
      onClick: e => e.stopPropagation(),
      // Batch 829 — Wallet modal a11y. role=dialog + aria-modal so
      // screen readers announce the wallet dialog on open. aria-label
      // is static because the wallet doesn't have a visible header
      // (the hero shows balance, not a "Wallet" title).
      role: 'dialog',
      'aria-modal': 'true',
      'aria-label': 'Wallet'
    },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close wallet' }, '✕'),
      // Visually-hidden H1 so /wallet has a real page heading for screen
      // readers + SEO crawlers. The wallet hero shows Balance / @username
      // but no actual <h1> — without this every /wallet page render had
      // zero headings and crawlers indexed it as a content-less surface.
      h('h1', { className: 'visually-hidden' },
        tab === 'deposit'  ? 'Deposit · Wallet' :
        tab === 'withdraw' ? 'Withdraw · Wallet' :
        tab === 'history'  ? 'Transaction History · Wallet' : 'Wallet'),
      h('div', { className: 'wallet-hero' },
        // STRIPE LIVE / DEV MODE pill is a debug surface — meaningful to
        // staff (and the operator while wiring keys), confusing to end
        // users who just want to see their balance. Hide for non-staff.
        // The deposit modal already labels its own CTA "Deposit (dev mode)"
        // when stripeLive is false, so payers still see the warning where
        // it actually matters.
        me?.staff && h('div', { className: `wallet-mode-pill ${wallet.stripeLive ? 'live' : 'dev'}` },
          wallet.stripeLive ? '● STRIPE LIVE' : '● DEV MODE'),
        h('div', { className: 'wallet-hero-label' }, 'Wallet Balance'),
        h('div', { className: 'wallet-hero-balance' }, maskMoney(wallet.balance)),
        wallet.username && h('div', { className: 'wallet-hero-user' }, '@' + wallet.username),
        // Pending in-flight chips. Renders only when there's an actual
        // pending row so the wallet hero stays clean for users without
        // any outstanding deposits/withdrawals. Numbers come straight
        // from /api/wallet; the UI doesn't compute them itself.
        ((parseFloat(wallet.pendingWithdrawAmt) || 0) > 0 ||
         (parseFloat(wallet.pendingDepositAmt)  || 0) > 0) &&
          h('div', { className: 'wallet-pending-row' },
            (parseFloat(wallet.pendingWithdrawAmt) || 0) > 0 && (() => {
              // Batch 686 — ETA tooltip. A user who just submitted a
              // withdrawal has no signal for when to expect the money.
              // "1-2 business days" is our payout SLO per the support
              // SLA. Also surfaces the request age so a user who's
              // been waiting a week sees "requested 7d ago" and knows
              // to open a ticket instead of assuming it's normal.
              const pendingWithdraw = (transactions || [])
                .filter(t => (t.type === 'WITHDRAW' || t.type === 'WITHDRAWAL') && t.status === 'PENDING')
                .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0];
              const ageMs = pendingWithdraw?.createdAt
                ? (Date.now() - pendingWithdraw.createdAt)
                : 0;
              const ageLabel = ageMs > 0
                ? (ageMs < 3_600_000 ? Math.floor(ageMs / 60_000) + 'm ago'
                  : ageMs < 86_400_000 ? Math.floor(ageMs / 3_600_000) + 'h ago'
                  : Math.floor(ageMs / 86_400_000) + 'd ago')
                : '';
              const daysOld = ageMs / 86_400_000;
              const stale = daysOld > 3;
              return h('div', {
                className: 'wallet-pending-chip withdraw',
                title: stale
                  ? `Requested ${ageLabel} — beyond the typical 1-2 business day window. Open a support ticket for a status check.`
                  : `Typically arrives in 1-2 business days.${ageLabel ? ' Requested ' + ageLabel + '.' : ''}`
              },
                h('span', { className: 'wallet-pending-dot' }),
                h('span', { className: 'wallet-pending-label' }, 'Withdrawal pending'),
                h('span', { className: 'wallet-pending-amt' }, '−' + maskMoney(wallet.pendingWithdrawAmt)),
                ageLabel && h('span', { style: { marginLeft: 6, fontSize: 10, opacity: 0.7 } }, '· ' + ageLabel)
              );
            })(),
            (parseFloat(wallet.pendingDepositAmt) || 0) > 0 && (() => {
              // Tooltip ETA hint (batch 425). Pulls the most-recent
              // PENDING DEPOSIT createdAt from the transactions list so
              // the chip can answer "is this stuck or just slow?". Stripe
              // webhooks usually settle within ~10s but can lag a minute
              // on a busy hour. The 48h sweeper (batch 403) safety-nets
              // anything that never returns.
              const pendingDeposit = (transactions || [])
                .filter(t => t.type === 'DEPOSIT' && t.status === 'PENDING')
                .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0];
              const ageMs = pendingDeposit?.createdAt
                ? (Date.now() - pendingDeposit.createdAt)
                : 0;
              const ageLabel = ageMs > 0
                ? (ageMs < 60_000 ? 'just now'
                  : ageMs < 3_600_000 ? Math.floor(ageMs / 60_000) + 'm ago'
                  : Math.floor(ageMs / 3_600_000) + 'h ago')
                : '';
              const stale = ageMs > 5 * 60_000;
              return h('div', {
                className: 'wallet-pending-chip deposit',
                title: stale
                  ? `Started ${ageLabel}. Stripe webhooks usually settle within a minute — if it's been over 5 min, the checkout session may have been abandoned. The 48h sweeper will auto-expire it.`
                  : 'Stripe is processing — usually settles within a minute. This chip clears as soon as the webhook fires.'
              },
                h('span', { className: 'wallet-pending-dot' }),
                h('span', { className: 'wallet-pending-label' }, 'Deposit pending'),
                h('span', { className: 'wallet-pending-amt' }, '+' + maskMoney(wallet.pendingDepositAmt)),
                ageLabel && h('span', {
                  style: {
                    marginLeft: 6, fontSize: 10, color: stale ? '#fbbf24' : 'var(--text-muted)'
                  }
                }, '· ', ageLabel)
              );
            })()
          )
      ),
      // Buyer spend summary strip (batch 846). Rendered as a compact
      // three-cell chip row right below the wallet hero. Parallel to
      // the seller earnings strip on /me/stall (batch 605).
      me && spend && (parseFloat(spend.spentLifetime) > 0) &&
        h('div', {
          className: 'wallet-spend-strip',
          role: 'region',
          'aria-label': 'Purchase spending summary',
          style: {
            display: 'grid',
            // Batch 873 — four columns when 24h purchases exist, three
            // otherwise (matches the seller-side 24h chip; hides when
            // zero so a quiet week stays clean).
            gridTemplateColumns: 'repeat(' + (spend.purchases24h > 0 ? 4 : 3) + ', 1fr)',
            gap: 8,
            padding: '10px 14px', margin: '0 14px 10px',
            background: 'var(--bg-elevated, rgba(255,255,255,0.02))',
            border: '1px solid var(--border)', borderRadius: 6
          }
        },
          (() => {
            const cells = [];
            if (spend.purchases24h > 0) {
              cells.push({ label: 'Past 24h', amt: spend.spent24h, ct: spend.purchases24h });
            }
            cells.push({ label: 'Past 7 days',  amt: spend.spent7d,       ct: spend.purchases7d       });
            cells.push({ label: 'Past 30 days', amt: spend.spent30d,      ct: spend.purchases30d      });
            cells.push({ label: 'Lifetime',     amt: spend.spentLifetime, ct: spend.purchasesLifetime });
            return cells.map((c, i) => h('div', {
              key: c.label,
              style: {
                textAlign: 'center',
                borderRight: i < cells.length - 1 ? '1px solid var(--border)' : 'none',
                paddingRight: i < cells.length - 1 ? 8 : 0
              },
              title: `You completed ${c.ct} purchase${c.ct === 1 ? '' : 's'} (${c.label.toLowerCase()}). Gross price — refunds are logged as separate rows in History.`
            },
              h('div', { style: { fontSize: 10, opacity: 0.7, textTransform: 'uppercase', letterSpacing: 0.4 } }, c.label),
              h('div', { style: { fontSize: 15, fontWeight: 700, color: 'var(--text)', marginTop: 3 } },
                '—' + maskMoney(c.amt || 0)),
              h('div', { style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2 } },
                c.ct + (c.ct === 1 ? ' purchase' : ' purchases'))
            ));
          })()
        ),
      // Batch 936 — proper tablist semantics for keyboard/screen-reader users.
      h('div', { className: 'wallet-tabs', role: 'tablist', 'aria-label': 'Wallet sections' },
        (() => {
          const WALLET_TABS = ['deposit', 'withdraw', 'history'];
          const pickTab = (next) => {
            setTab(next); setError('');
            // CSFloat-1:1: tab changes also update the URL so /wallet/withdraw
            // is shareable + back-button works between tabs.
            navigate('/wallet/' + next);
          };
          const onKey = (e) => {
            if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
            e.preventDefault();
            const idx = WALLET_TABS.indexOf(tab);
            let n = idx;
            if (e.key === 'ArrowRight') n = (idx + 1) % WALLET_TABS.length;
            else if (e.key === 'ArrowLeft') n = (idx - 1 + WALLET_TABS.length) % WALLET_TABS.length;
            else if (e.key === 'Home') n = 0;
            else if (e.key === 'End') n = WALLET_TABS.length - 1;
            pickTab(WALLET_TABS[n]);
          };
          return WALLET_TABS.map(id => h('button', {
            key: id,
            id: 'wallet-tab-' + id,
            className: `wallet-tab ${tab === id ? 'active' : ''}`,
            role: 'tab',
            'aria-selected': tab === id,
            'aria-controls': 'wallet-panel-' + id,
            tabIndex: tab === id ? 0 : -1,
            onClick: () => pickTab(id),
            onKeyDown: onKey
          }, id.charAt(0).toUpperCase() + id.slice(1)));
        })()
      ),
      h('div', {
        className: 'wallet-panel',
        role: 'tabpanel',
        id: 'wallet-panel-' + tab,
        'aria-labelledby': 'wallet-tab-' + tab
      },
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
              // Month filter (batch 638) — YYYY-MM applied to the tx's
              // createdAt in the local calendar. Distinct-month options
              // are derived from the source `transactions` list so the
              // dropdown only surfaces months the user actually has
              // history in, oldest → newest with the current month at
              // the top.
              const monthKeyOf = (ts) => {
                if (!ts) return null;
                const d = new Date(ts);
                if (isNaN(d.getTime())) return null;
                return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
              };
              const monthLabelOf = (key) => {
                if (!key) return '';
                const [y, m] = key.split('-');
                const d = new Date(parseInt(y, 10), parseInt(m, 10) - 1, 1);
                return d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
              };
              const availableMonths = (() => {
                const set = new Set();
                (transactions || []).forEach(tx => {
                  const k = monthKeyOf(tx.createdAt);
                  if (k) set.add(k);
                });
                return Array.from(set).sort().reverse();
              })();
              if (txMonth !== 'ALL') {
                filtered = filtered.filter(tx => monthKeyOf(tx.createdAt) === txMonth);
              }
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
                      h('div', { className: 'wallet-7d-val in' }, '+' + maskMoney(inbound7))
                    ),
                    h('div', null,
                      h('div', { className: 'wallet-7d-subtitle' }, 'Outbound'),
                      h('div', { className: 'wallet-7d-val out' }, '−' + maskMoney(outbound7))
                    ),
                    h('div', null,
                      h('div', { className: 'wallet-7d-subtitle' }, 'Net'),
                      h('div', { className: `wallet-7d-val ${net7 >= 0 ? 'in' : 'out'}` }, (net7 >= 0 ? '+' : '−') + maskMoney(Math.abs(net7)))
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
                  // Month picker (batch 638, CSFloat Visual Manual §24).
                  // Scopes the history view to a calendar month. Only
                  // renders when there's at least one transaction — an
                  // empty inbox shouldn't get a useless dropdown.
                  availableMonths.length > 0 && h('select', {
                    className: 'sort-select',
                    'aria-label': 'Filter transactions by month',
                    style: { minWidth: 150, fontSize: 12 },
                    value: txMonth,
                    onChange: e => setTxMonth(e.target.value),
                    title: 'Filter transactions to a specific calendar month'
                  },
                    h('option', { value: 'ALL' }, 'All months'),
                    availableMonths.map(k => h('option', { key: k, value: k }, monthLabelOf(k)))
                  ),
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
                    // Batch 642 — when a month filter is active, download
                    // just that month. Useful for tax work where users
                    // want a single month at a time instead of the full
                    // 5000-row dump.
                    href: txMonth !== 'ALL'
                      ? `/api/wallet/transactions.csv?month=${encodeURIComponent(txMonth)}`
                      : '/api/wallet/transactions.csv',
                    title: txMonth !== 'ALL'
                      ? `Download ${monthLabelOf(txMonth)} transactions as a CSV`
                      : 'Download all transactions as a CSV file'
                  }, txMonth !== 'ALL'
                      ? `⇣ Export ${monthLabelOf(txMonth)}`
                      : '⇣ Export CSV')
                ),
                h('div', { className: 'wallet-tx-list' },
                  filtered.length === 0
                    ? h('div', { className: 'wallet-tx-empty' },
                        transactions.length === 0
                          ? 'No transactions yet'
                          : q
                            ? `No transactions match "${q}"`
                            : txMonth !== 'ALL'
                              ? `No transactions in ${monthLabelOf(txMonth)}`
                              : 'No transactions match this filter')
                    : filtered.map(tx => {
                        const inbound = tx.type === 'DEPOSIT' || tx.type === 'SALE' || tx.type === 'REFUND' || tx.type === 'ADJUSTMENT_CREDIT';
                        const typeLabel = (tx.type || 'UNKNOWN').toString();
                        const prettyType = typeLabel.charAt(0) + typeLabel.slice(1).toLowerCase().replace('_', ' ');
                        const canCancel = (tx.type === 'WITHDRAW' || tx.type === 'WITHDRAWAL') && tx.status === 'PENDING';
                        return h('div', { key: tx.id, className: 'wallet-tx' },
                          h('div', { className: `wallet-tx-icon ${inbound ? 'in' : 'out'}` }, inbound ? '↓' : '↑'),
                          h('div', { className: 'wallet-tx-main' },
                            h('div', { className: 'wallet-tx-type' },
                              prettyType,
                              // Batch 754 — relative timestamp next to the
                              // type label. Pure UX — a user scanning the
                              // history wants "2h ago" at a glance, not a
                              // raw epoch. Full date in the title so a
                              // hover reveals the exact moment.
                              tx.createdAt && h('span', {
                                style: { marginLeft: 8, fontSize: 10, color: 'var(--text-muted)', fontWeight: 500 },
                                title: new Date(tx.createdAt).toLocaleString()
                              }, '· ', timeAgo(tx.createdAt))
                            ),
                            h('div', { className: 'wallet-tx-desc' }, tx.description || customerFacingRef(tx.stripeReference)),
                            // Copyable transaction id — lets users quote the
                            // exact row in a support ticket ("my withdrawal
                            // #1042 is stuck") without screenshots. Small +
                            // muted so it doesn't dominate the row; click to
                            // copy with a brief flash confirmation.
                            h('button', {
                              type: 'button',
                              title: 'Copy transaction reference — quote this in a support ticket to speed up review',
                              style: {
                                marginTop: 2, padding: 0, background: 'none', border: 'none',
                                color: 'var(--text-muted)', fontSize: 10, fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace",
                                cursor: 'pointer', letterSpacing: 0.3, textAlign: 'left'
                              },
                              onClick: async (e) => {
                                e.stopPropagation();
                                const ref = '#' + tx.id + (tx.stripeReference ? ' (' + customerFacingRef(tx.stripeReference) + ')' : '');
                                const btn = e.currentTarget;
                                const prev = btn.textContent;
                                const flash = () => {
                                  btn.textContent = 'copied';
                                  btn.style.color = 'var(--green)';
                                  setTimeout(() => { btn.textContent = prev; btn.style.color = 'var(--text-muted)'; }, 1200);
                                };
                                try {
                                  if (navigator.clipboard?.writeText) {
                                    await navigator.clipboard.writeText(ref);
                                    flash();
                                  } else {
                                    window.prompt('Copy this reference:', ref);
                                  }
                                } catch (_) { window.prompt('Copy this reference:', ref); }
                              }
                            }, '#' + tx.id + (tx.stripeReference ? ' · ' + tx.stripeReference.slice(0, 14) + (tx.stripeReference.length > 14 ? '…' : '') : ''))
                          ),
                          h('div', { className: 'wallet-tx-right' },
                            // Honour privacy mode here too — the hero, pending
                            // chips, 7-day summary, and spend strip all mask
                            // via maskMoney(), so a bare fmt() on the per-row
                            // amount leaked the exact dollar values the user
                            // opted to hide (screenshot / shoulder-surf).
                            h('div', { className: `wallet-tx-amt ${inbound ? 'in' : 'out'}` }, (inbound ? '+' : '−') + maskMoney(tx.amount)),
                            h('div', { className: `wallet-tx-status ${tx.status}` }, tx.status),
                            // Self-cancel for PENDING withdrawals — credits
                            // the balance back and flips the row to CANCELLED.
                            // Only visible on the actual PENDING withdrawal
                            // row so completed / failed rows stay clean.
                            canCancel && h('button', {
                              className: 'btn btn-ghost',
                              style: { marginTop: 6, padding: '4px 10px', fontSize: 10, border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' },
                              onClick: async (e) => {
                                // Synchronous re-entry guard — this is a money
                                // action (cancelling a PENDING withdrawal credits
                                // the wallet back), so a double-click must not fire
                                // two cancel POSTs. Capture the button + latch it
                                // via .disabled BEFORE the await (e.currentTarget is
                                // nulled after the first await; the blocking confirm
                                // serialises clicks only up to the await). Mirrors
                                // the buyingRef/submittingRef latch on every other
                                // money submit.
                                const btn = e.currentTarget;
                                if (btn.disabled) return;
                                if (!confirm(`Cancel pending withdrawal for ${fmt(tx.amount)}? Your balance will be credited back.`)) return;
                                btn.disabled = true;
                                const amt = parseFloat(tx.amount);
                                try {
                                  const res = await cancelPendingWithdrawal(tx.id);
                                  if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not cancel withdrawal', 'err'); btn.disabled = false; return; }
                                  await onRefresh();
                                  // Batch 921 — cite the amount + id so a
                                  // user cancelling one of several pending
                                  // withdrawals sees which row just reverted.
                                  toast(`Withdrawal #${tx.id} cancelled — ${fmt(amt)} restored to your wallet.`, 'ok');
                                } catch (err) {
                                  btn.disabled = false;
                                  toast('Could not cancel withdrawal', 'err');
                                }
                              }
                            }, '✕ Cancel')
                          )
                        );
                      })
                )
              );
            })()
          : h('div', null,
              // Prefill source banner — when the cart low-balance handler
              // sent the user here with a precomputed shortfall amount,
              // show what triggered the prefill so the typed number isn't
              // mysterious. Display amount in the user's currency via
              // fmt() (it's a label, not the input) while the input itself
              // stays USD raw.
              tab === 'deposit' && prefillAmount != null && parseFloat(prefillAmount) > 0 && h('div', {
                className: 'wallet-prefill-source',
                style: {
                  padding: '10px 14px', marginBottom: 12, borderRadius: 8,
                  background: 'rgba(250,204,21,0.08)',
                  border: '1px solid rgba(250,204,21,0.3)',
                  fontSize: 12, color: '#fde68a', lineHeight: 1.5,
                  display: 'flex', gap: 10, alignItems: 'center'
                }
              },
                h('span', { style: { fontSize: 14 } }, '→'),
                h('div', { style: { flex: 1 } },
                  'Topping up to cover your cart shortfall · ',
                  h('strong', null, fmt(parseFloat(prefillAmount))))
              ),
              // Email-verification gate for withdrawals. Must mirror
              // the server guard (WalletController.withdraw checks
              // emailVerified before calling Stripe). Rendered at the
              // top of the form so a user doesn't fill in the amount
              // only to get a 400 on submit.
              // Daily withdrawal-cap chip (batch 357) — shows how much
              // of the rolling 24h cap remains so the user can size
              // the withdrawal before hitting the server-side reject.
              // Only shown to signed-in users with an email-verified
              // account (the common path); hides when the cap is
              // unconfigured or unused so the form stays clean for
              // first-time withdrawals.
              // Daily deposit-cap chip (batch 497) — mirrors the
              // withdraw chip below. Rolling 24h cap enforced
              // server-side in StripeService.createDepositSession;
              // surface it up front so the user doesn't type an
              // amount, click Continue to Stripe, and get a 400
              // back with DEPOSIT_DAILY_CAP. Hides when the cap is
              // unconfigured (dev mode) or at default usage.
              tab === 'deposit' && me && wallet && wallet.dailyDepositCap > 0 && h('div', {
                style: {
                  padding: '10px 14px', marginBottom: 12, borderRadius: 8,
                  background: (wallet.dailyDepositRemaining > 0 ? 'rgba(96,165,250,0.08)' : 'rgba(248,113,113,0.12)'),
                  border: '1px solid ' + (wallet.dailyDepositRemaining > 0 ? 'rgba(96,165,250,0.25)' : 'rgba(248,113,113,0.35)'),
                  fontSize: 12,
                  color: (wallet.dailyDepositRemaining > 0 ? 'var(--text-secondary)' : '#fca5a5'),
                  display: 'flex', gap: 10, alignItems: 'center'
                },
                title: 'Rolling 24-hour deposit cap — guards against card-testing and stolen-card drain.'
              },
                h('span', { style: { fontSize: 14 } },
                  wallet.dailyDepositRemaining > 0 ? 'ⓘ' : '⚠'),
                h('div', { style: { flex: 1 } },
                  wallet.dailyDepositRemaining > 0
                    ? h('span', null,
                        h('b', null, fmt(wallet.dailyDepositRemaining)),
                        ' of ', h('b', null, fmt(wallet.dailyDepositCap)),
                        ' daily deposit cap remaining',
                        wallet.dailyDepositUsed > 0
                          ? h('span', { style: { opacity: 0.7 } }, ' · ', fmt(wallet.dailyDepositUsed), ' already deposited in the last 24h')
                          : null)
                    : (() => {
                        // Batch 753 — real countdown based on the earliest
                        // window row. Falls back to the blanket "try in 24h"
                        // when the server didn't ship oldestAt (old client
                        // talking to a server without the field).
                        const oldest = wallet.dailyDepositOldestAt;
                        let tail = ' Try again in 24h or contact support for a cap raise.';
                        if (oldest) {
                          const msLeft = (oldest + 24 * 3600_000) - Date.now();
                          if (msLeft > 0) {
                            const h_ = Math.floor(msLeft / 3600_000);
                            const m_ = Math.max(1, Math.round((msLeft % 3600_000) / 60_000));
                            tail = ` Cap starts rolling off in ${h_}h${m_}m.`;
                          }
                        }
                        // Batch 803 — direct "Request a cap raise" link
                        // that opens /support with a pre-filled query so
                        // the ticket form lands on the right category
                        // with a ready-to-submit draft. Faster than
                        // hunting for support + typing context the
                        // banner already displays.
                        return h('span', null,
                          h('b', null, 'Daily deposit cap reached'),
                          ' — ', fmt(wallet.dailyDepositUsed), ' deposited in the last 24h.',
                          tail, ' ',
                          h('a', {
                            href: '/support?topic=cap-raise&kind=deposit&used=' +
                              encodeURIComponent(wallet.dailyDepositUsed) +
                              '&cap=' + encodeURIComponent(wallet.dailyDepositCap),
                            style: { color: '#fbbf24', textDecoration: 'underline', fontWeight: 700 },
                            onClick: (e) => {
                              e.preventDefault();
                              onClose && onClose();
                              navigate('/support?topic=cap-raise&kind=deposit');
                            }
                          }, 'Request a raise →'));
                      })())
              ),
              tab === 'withdraw' && me && wallet && wallet.dailyWithdrawCap && h('div', {
                style: {
                  padding: '10px 14px', marginBottom: 12, borderRadius: 8,
                  background: (wallet.dailyWithdrawRemaining > 0 ? 'rgba(96,165,250,0.08)' : 'rgba(248,113,113,0.12)'),
                  border: '1px solid ' + (wallet.dailyWithdrawRemaining > 0 ? 'rgba(96,165,250,0.25)' : 'rgba(248,113,113,0.35)'),
                  fontSize: 12,
                  color: (wallet.dailyWithdrawRemaining > 0 ? 'var(--text-secondary)' : '#fca5a5'),
                  display: 'flex', gap: 10, alignItems: 'center'
                },
                title: 'Rolling 24-hour cap — prevents draining the full wallet on a compromised account.'
              },
                h('span', { style: { fontSize: 14 } },
                  wallet.dailyWithdrawRemaining > 0 ? 'ⓘ' : '⚠'),
                h('div', { style: { flex: 1 } },
                  wallet.dailyWithdrawRemaining > 0
                    ? h('span', null,
                        h('b', null, fmt(wallet.dailyWithdrawRemaining)),
                        ' of ', h('b', null, fmt(wallet.dailyWithdrawCap)),
                        ' daily cap remaining',
                        wallet.dailyWithdrawUsed > 0
                          ? h('span', { style: { opacity: 0.7 } }, ' · ', fmt(wallet.dailyWithdrawUsed), ' already requested in the last 24h')
                          : null)
                    : (() => {
                        // Batch 753 — same real-countdown treatment as the
                        // deposit side. The withdraw cap matters more for
                        // payout UX: a seller who thinks their next withdraw
                        // is blocked for a full day will just wait that long.
                        const oldest = wallet.dailyWithdrawOldestAt;
                        let tail = ' Try again in 24h or contact support for a manual payout.';
                        if (oldest) {
                          const msLeft = (oldest + 24 * 3600_000) - Date.now();
                          if (msLeft > 0) {
                            const h_ = Math.floor(msLeft / 3600_000);
                            const m_ = Math.max(1, Math.round((msLeft % 3600_000) / 60_000));
                            tail = ` Cap starts rolling off in ${h_}h${m_}m.`;
                          }
                        }
                        // Batch 803 — same "Request a raise" deep-link as
                        // the deposit banner. For withdrawals the link
                        // carries kind=withdraw so staff sees the right
                        // cap in the ticket context.
                        return h('span', null,
                          h('b', null, 'Daily withdrawal cap reached'),
                          ' — ', fmt(wallet.dailyWithdrawUsed), ' requested in the last 24h.',
                          tail, ' ',
                          h('a', {
                            href: '/support?topic=cap-raise&kind=withdraw&used=' +
                              encodeURIComponent(wallet.dailyWithdrawUsed) +
                              '&cap=' + encodeURIComponent(wallet.dailyWithdrawCap),
                            style: { color: '#fbbf24', textDecoration: 'underline', fontWeight: 700 },
                            onClick: (e) => {
                              e.preventDefault();
                              onClose && onClose();
                              navigate('/support?topic=cap-raise&kind=withdraw');
                            }
                          }, 'Request a raise →'));
                      })())
              ),
              // Active-chargeback hold notice (batch 465). When Stripe
              // has flagged any deposit on this wallet as DISPUTED, the
              // server refuses /api/wallet/withdraw with WITHDRAW_DISPUTE_HOLD.
              // Surface it up front so the user understands BEFORE typing
              // an amount. Red because it's a hard stop, not a heads-up.
              me && wallet && wallet.disputeHoldCount > 0 && h('div', {
                style: {
                  padding: 12, marginBottom: 12, borderRadius: 8,
                  background: 'rgba(248,113,113,0.12)',
                  border: '1px solid rgba(248,113,113,0.4)',
                  color: '#fca5a5', fontSize: 12, fontWeight: 600,
                  display: 'flex', gap: 10, alignItems: 'flex-start'
                }
              },
                h('span', { style: { fontSize: 16 } }, '⛔'),
                h('div', { style: { flex: 1 } },
                  h('div', { style: { fontWeight: 700, marginBottom: 4 } }, 'Withdrawals and purchases paused'),
                  h('div', { style: { fontWeight: 500, lineHeight: 1.5 } },
                    'You have ', h('b', null, wallet.disputeHoldCount), ' unresolved deposit ',
                    'dispute', wallet.disputeHoldCount === 1 ? '' : 's', ' on file. ',
                    'Withdrawals, purchases, offers, bids, and buy orders are all paused until the hold clears. ',
                    'Once your bank closes the chargeback (or staff clears the hold), ',
                    'everything resumes. ',
                    h('a', {
                      href: '/support',
                      style: { color: '#fbbf24', textDecoration: 'underline' },
                      onClick: (e) => { e.preventDefault(); onClose && onClose(); navigate('/support'); }
                    }, 'Open a support ticket')
                  )
                )
              ),
              // Wallet-frozen banner (batch 509). Applies to BOTH tabs
              // because the freeze refuses deposit AND withdraw AND
              // purchase. Red hard-stop banner with the staff-supplied
              // reason + support-ticket CTA. Renders above the amount
              // input so the user sees it before they type anything.
              me && wallet && wallet.frozen && h('div', {
                style: {
                  padding: 12, marginBottom: 12, borderRadius: 8,
                  background: 'rgba(248,113,113,0.14)',
                  border: '1px solid rgba(248,113,113,0.45)',
                  color: '#fca5a5', fontSize: 12, fontWeight: 600,
                  display: 'flex', gap: 10, alignItems: 'flex-start'
                }
              },
                h('span', { style: { fontSize: 16 } }, '⚠'),
                h('div', { style: { flex: 1 } },
                  h('div', { style: { fontWeight: 700, marginBottom: 4 } }, 'Wallet frozen by staff'),
                  h('div', { style: { fontWeight: 500, lineHeight: 1.5 } },
                    wallet.frozenReason
                      ? h('span', null, h('b', null, 'Reason: '), wallet.frozenReason, ' ')
                      : 'All deposits, withdrawals, and purchases are blocked on this wallet. ',
                    h('a', {
                      href: '/support',
                      style: { color: '#fbbf24', textDecoration: 'underline' },
                      onClick: (e) => { e.preventDefault(); onClose && onClose(); navigate('/support'); }
                    }, 'Open a support ticket'),
                    ' to resolve.'
                  )
                )
              ),
              tab === 'withdraw' && me && !me.emailVerified && h('div', {
                style: {
                  padding: 12, marginBottom: 12, borderRadius: 8,
                  background: 'rgba(251,191,36,0.12)',
                  border: '1px solid rgba(251,191,36,0.4)',
                  color: '#fbbf24', fontSize: 12, fontWeight: 600,
                  display: 'flex', gap: 10, alignItems: 'flex-start'
                }
              },
                h('span', { style: { fontSize: 16 } }, '⚠'),
                h('div', { style: { flex: 1 } },
                  h('div', { style: { fontWeight: 700, marginBottom: 4 } },
                    me.email ? 'Verify your email before withdrawing' : 'Add an email before withdrawing'),
                  h('div', { style: { opacity: 0.9 } },
                    me.email
                      ? 'We send withdrawal-approved / rejected emails to this address. Open Profile → Personal Info to click the verify link.'
                      : 'Withdrawals require a verified email so we can contact you about payout status. Add one in Profile → Personal Info.'),
                  h('a', {
                    href: '/profile',
                    style: { display: 'inline-block', marginTop: 6, color: 'inherit', fontWeight: 800, textDecoration: 'underline' }
                  }, 'Open Profile →')
                )
              ),
              // ── Stepper layout (batch 609) — matches CSFloat's
              // numbered stepper: circle on the left, connector line
              // between steps, title + content to the right. Step 1 is
              // always "amount"; step 2 is "payment method" (card grid);
              // step 3 is checkout (deposit) / review + 2FA (withdraw).
              h('div', { className: 'wallet-stepper' },
                // ── Step 1: amount ──
                h('div', { className: `wallet-step ${amt > 0 ? 'done' : 'active'}` },
                  h('div', { className: 'wallet-step-rail' },
                    h('div', { className: 'wallet-step-num' }, '1'),
                    h('div', { className: 'wallet-step-line' })
                  ),
                  h('div', { className: 'wallet-step-body' },
                    h('div', { className: 'wallet-step-head' },
                      // Boss QA G9 — was the placeholder pair "Enter an
                      // amount of funds" / "Or select a suggested
                      // amount", which read like an unrevised brief. The
                      // single, decisive line lets the form do the
                      // explaining.
                      h('div', { className: 'wallet-step-title' }, 'Select an amount or enter a custom value'),
                      h('div', { className: 'wallet-step-subtitle' },
                        tab === 'deposit'
                          ? 'Pick a preset to top up, or type a precise amount.'
                          : 'Pick a preset to withdraw, or type a precise amount.')
                    ),
                    h('div', { className: 'wallet-amount-row' },
                      h('div', { className: 'wallet-amount-wrap' },
                        h('span', { className: 'wallet-amount-prefix' }, '$'),
                        h('input', {
                          className: 'wallet-amount-input-v2',
                          type: 'number', min: '0', max: '10000', step: '0.01',
                          // Batch 923 — inputMode hint so mobile browsers
                          // surface the decimal-key keyboard (period key
                          // instead of a comma, no QWERTY). iOS Safari
                          // respects this even when `type=number` alone
                          // would still show the full keyboard.
                          inputMode: 'decimal',
                          enterKeyHint: 'done',
                          placeholder: '0.00',
                          value: amount,
                          onChange: e => setAmount(e.target.value),
                          'aria-label': tab === 'deposit' ? 'Deposit amount' : 'Withdrawal amount'
                        }),
                        // FX equivalent hint — when the user has selected a
                        // non-USD display currency, show what the typed USD
                        // amount maps to in their currency AND explicitly
                        // call out that the actual charge stays USD. Without
                        // the "(charged USD)" tail, a CAD user typing "50"
                        // and seeing "≈ CA$68.50" could reasonably believe
                        // their card will be billed CA$68.50 — but Stripe
                        // round-trips USD raw and the cardholder bank
                        // applies its own FX. Hidden when the user is on
                        // USD (no conversion to show) or when the field
                        // is empty / non-numeric.
                        amt > 0 && currencySymbol() !== '$' && h('div', {
                          className: 'wallet-amount-fx-hint',
                          style: {
                            fontSize: 11, color: 'var(--text-muted)',
                            marginTop: 4, fontWeight: 500
                          },
                          title: 'Wallet ledger and Stripe charge are both denominated in USD. The approximate value in your selected display currency is shown for reference; your bank may apply its own FX rate at settlement.'
                        }, '≈ ', fmt(amt), ' ', h('span', {
                          style: { opacity: 0.75, fontWeight: 600 }
                        }, tab === 'deposit' ? '(charged in USD)' : '(paid out in USD)'))
                      ),
                      h('div', { className: 'wallet-preset-row' },
                        presets.map(a =>
                          h('button', {
                            key: a,
                            className: `wallet-preset-btn ${String(a) === amount ? 'active' : ''}`,
                            onClick: () => setAmount(String(a))
                          }, '$' + Number(a).toFixed(2))
                        ),
                        // Batch 966 — "Max" button on the withdraw tab
                        // only. Users draining a wallet before closing an
                        // account, or withdrawing accumulated sale
                        // proceeds, don't want to type the balance
                        // amount into the form. Clamped at $10k to match
                        // the server-side per-request cap
                        // (WithdrawRequest.DecimalMax). Silent on the
                        // deposit tab because there's no meaningful
                        // "max" for a top-up — user picks what they want
                        // to add, not a function of balance.
                        tab === 'withdraw' && (() => {
                          const bal = parseFloat(wallet.balance) || 0;
                          const maxable = Math.max(0, Math.min(bal, 10000));
                          if (maxable <= 0) return null;
                          const maxStr = maxable.toFixed(2);
                          return h('button', {
                            key: 'max',
                            className: `wallet-preset-btn ${maxStr === amount ? 'active' : ''}`,
                            title: `Fill with your full withdrawable balance (${fmt(maxable)})`,
                            onClick: () => setAmount(maxStr)
                          }, 'Max');
                        })()
                      )
                    ),
                    // Honest preview: exactly what the server will do.
                    // Card charged (deposit) → wallet credited NET of
                    // Stripe's fee; wallet debited (withdraw) → payout
                    // sent NET of Stripe's payout cost. The platform's
                    // own revenue is the 2% selling fee, which lives on
                    // the sale side and is not charged here.
                    amt > 0 && h('div', { className: 'wallet-fee-breakdown' },
                      h('div', { className: 'wallet-fee-row' },
                        h('span', null, tab === 'deposit' ? 'Card charged' : 'Wallet debited'),
                        // amt is the $-prefixed USD wallet input — literal USD, not fmt() (FX-converted). (wave-146)
                        h('strong', null, '$' + amt.toFixed(2))
                      ),
                      // The deduction line. Rendered ONLY when a fee is
                      // actually applied, so the dev-mode/absorbed case
                      // stays visually identical to the old behaviour
                      // instead of showing a "− $0.00" the user has to
                      // parse.
                      previewRateFee > 0 && h('div', { className: 'wallet-fee-row' },
                        h('span', null, tab === 'deposit'
                          ? 'Payment processing fee'
                          : 'Payout fee'),
                        h('strong', null, '− $' + previewRateFee.toFixed(2))
                      ),
                      // Its OWN line, not folded into the payout fee. It is
                      // a once-a-month charge with a waiver, so a seller who
                      // sees it merged into a per-payout fee draws the wrong
                      // conclusion about their next withdrawal — and the way
                      // out (withdraw the waiver amount or more, once) is
                      // only actionable if the charge is named.
                      previewAccountFee > 0 && h('div', { className: 'wallet-fee-row' },
                        h('span', null, 'Monthly payout account fee'),
                        h('strong', null, '− $' + previewAccountFee.toFixed(2))
                      ),
                      previewAccountFee > 0 && h('div', { className: 'wallet-fee-note' },
                        'Your payment provider charges this once a calendar month, on your first '
                        + 'payout. It is waived on a withdrawal of $' + perAccountWaive.toFixed(2)
                        + ' or more.'
                      ),
                      h('div', { className: 'wallet-fee-row total' },
                        h('span', null, tab === 'deposit' ? 'Wallet credit' : 'You receive'),
                        h('strong', { style: { color: 'var(--accent)' } }, '$' + previewNet.toFixed(2))
                      ),
                      // Cash-out setup (Stripe Connect) — withdraw tab only.
                      // Show a "Set up cash-out" card until payouts are
                      // enabled, then a small "✓ Cash-out ready" indicator.
                      tab === 'withdraw' && connect && connect.simulated && !connect.payoutsEnabled &&
                        h('div', { className: 'cashout-setup-card', 'data-testid': 'cashout-test-mode' },
                          h('div', { className: 'cashout-setup-title' }, 'Test mode — payouts are simulated'),
                          h('div', { className: 'cashout-setup-desc' },
                            'No payment processor is connected on this server, so there is no Stripe cash-out account to set up. ' +
                            'A withdrawal here debits your balance and sends no real money.')
                        ),
                      tab === 'withdraw' && connect && !connect.simulated && !connect.payoutsEnabled &&
                        h('div', { className: 'cashout-setup-card' },
                          h('div', { className: 'cashout-setup-title' }, 'Set up cash-out'),
                          h('div', { className: 'cashout-setup-desc' },
                            'Verify your identity and link a bank account or debit card to cash out your balance. Stripe handles identity verification and payouts securely.'),
                          h('button', {
                            className: 'btn btn-primary cashout-setup-btn',
                            disabled: onboarding,
                            onClick: startCashoutSetup
                          }, onboarding ? 'Redirecting…' : 'Set up cash-out')
                        ),
                      tab === 'withdraw' && connect && connect.payoutsEnabled &&
                        h('div', { className: 'cashout-ready', title: 'Your payout account is verified — withdrawals are paid out to it.' },
                          '✓ Cash-out ready'),
                      // Batch 799 — concrete ETA date for withdrawals. "1-2
                      // business days" is abstract; showing an actual date
                      // ("arrives by Mon, Apr 22") cuts the "is this stuck?"
                      // support ticket volume. Skips weekends per Stripe's
                      // banking-day convention.
                      tab === 'withdraw' && (() => {
                        const addBusinessDays = (date, n) => {
                          const d = new Date(date);
                          let added = 0;
                          while (added < n) {
                            d.setDate(d.getDate() + 1);
                            const dow = d.getDay();
                            if (dow !== 0 && dow !== 6) added++;
                          }
                          return d;
                        };
                        const eta = addBusinessDays(new Date(), 2);
                        const label = eta.toLocaleDateString(undefined,
                          { weekday: 'short', month: 'short', day: 'numeric' });
                        return h('div', { className: 'wallet-fee-row', style: { fontSize: 11 } },
                          h('span', null, 'Estimated arrival'),
                          h('strong', { style: { color: 'var(--accent)' } }, 'by ' + label)
                        );
                      })(),
                      h('div', { className: 'wallet-fee-row', style: { fontSize: 10, color: 'var(--text-muted)' } },
                        h('span', null,
                          // Two different truths depending on whether a fee
                          // is applied — never one line that hedges. The
                          // pre-pass-through copy claimed "100% of your
                          // deposit reaches your wallet", which is now the
                          // opposite of what happens.
                          !feesActive
                            ? (tab === 'deposit'
                                ? 'Test mode — no payment processor is charged, so 100% of your deposit reaches your wallet.'
                                : 'Test mode — simulated payout, no processor fee applied.')
                            : (tab === 'deposit'
                                ? 'This is the payment processor’s fee, passed through at cost. SkinBox adds nothing to it — our only fee is 2% when an item sells.'
                                : 'This is the payment processor’s payout cost, passed through at cost. Payouts arrive in 1-2 business days. SkinBox charges no withdrawal fee — our only fee is 2% when an item sells.')
                        )
                      )
                    ),
                    tab === 'withdraw' && h('div', { className: 'wallet-inline-hint' },
                      h('span', { className: 'wallet-inline-hint-icon' }, 'ⓘ'),
                      h('span', null,
                        // Copy matches what WalletController.withdraw actually
                        // enforces — the full wallet balance is withdrawable
                        // (it only checks balance >= amount). The old line
                        // falsely claimed only item-sale proceeds could be
                        // withdrawn, which the server never enforced.
                        'You have ',
                        h('strong', { style: { color: 'var(--accent)' } }, fmt(wallet.balance)),
                        ' available to withdraw.'
                      )
                    )
                  )
                ),
                // ── Step 2: payment method ──
                h('div', { className: `wallet-step ${amt > 0 ? 'active' : ''}` },
                  h('div', { className: 'wallet-step-rail' },
                    h('div', { className: 'wallet-step-num' }, '2'),
                    h('div', { className: 'wallet-step-line' })
                  ),
                  h('div', { className: 'wallet-step-body' },
                    h('div', { className: 'wallet-step-title' }, 'Select payment method'),
                    h('div', { className: 'wallet-method-grid' },
                      tab === 'deposit'
                        ? h('div', { className: 'wallet-method-card selected' },
                            h('div', { className: 'wallet-method-badges' },
                              h('span', { className: 'wallet-method-badge instant' }, '⚡'),
                              h('span', { className: 'wallet-method-badge ccy' }, 'USD')
                            ),
                            h('div', { className: 'wallet-method-icon' }, h(MaterialIcon, { name: 'credit_card', size: 42, fill: true, color: 'var(--accent)' })),
                            h('div', { className: 'wallet-method-title' }, 'Credit/Debit Card'),
                            h('div', { className: 'wallet-method-sub' }, 'Visa, Mastercard, Amex, Apple Pay, Google Pay')
                          )
                        : h('div', { className: 'wallet-method-card selected' },
                            h('div', { className: 'wallet-method-badges' },
                              h('span', { className: 'wallet-method-badge standard' }, 'STANDARD'),
                              h('span', { className: 'wallet-method-badge ccy' }, 'USD')
                            ),
                            h('div', { className: 'wallet-method-icon' }, h(MaterialIcon, { name: 'account_balance', size: 42, fill: true, color: 'var(--accent)' })),
                            h('div', { className: 'wallet-method-title' }, 'Bank Account / Stripe'),
                            h('div', { className: 'wallet-method-sub' }, 'Payout in 1–2 business days')
                          )
                    ),
                    // Withdraw-only: the 2FA code sits under the payment-method
                    // card so the user can complete the form before reviewing.
                    // (No destination input -- see submit() for why.)
                    tab === 'withdraw' && h('div', { className: 'wallet-method-fields' },
                      h('input', {
                        className: 'wallet-amount-input',
                        type: 'text', inputMode: 'numeric', maxLength: 6,
                        // Batch 770 — autofill hint for password managers
                        // + iOS Messages so a user who gets the TOTP via
                        // SMS/authenticator can tap to fill.
                        autoComplete: 'one-time-code',
                        autoCapitalize: 'off',
                        spellCheck: false,
                        placeholder: '6-digit 2FA code (if enabled)',
                        value: totpCode,
                        onChange: e => setTotpCode(e.target.value.replace(/\D/g, '')),
                        style: { letterSpacing: '4px', marginTop: 8 }
                      })
                    )
                  )
                ),
                // ── Step 3: checkout / review ──
                h('div', { className: `wallet-step last ${amt > 0 ? 'active' : ''}` },
                  h('div', { className: 'wallet-step-rail' },
                    h('div', { className: 'wallet-step-num' }, '3')
                  ),
                  h('div', { className: 'wallet-step-body' },
                    h('div', { className: 'wallet-step-title' },
                      tab === 'deposit' ? 'Checkout' : 'Review'),
                    error && h('div', { className: 'wallet-error' }, error),
                    h('button', {
                      className: 'btn btn-accent wallet-submit',
                      disabled: busy || !amt,
                      onClick: submit
                    }, busy ? 'Processing…' :
                       tab === 'deposit'
                         ? (wallet.stripeLive ? 'Continue to Stripe →' : 'Deposit (dev mode)')
                         : `Withdraw ${amt > 0 ? fmt(amt) : ''}`)
                  )
                )
              ),
              // Timing expectation (batch 431). Previously the form
              // just said "You have $X in withdrawable balance" with no
              // hint on how long until the money actually lands. Users
              // unfamiliar with the manual-admin-review model filed
              // support tickets within hours of submitting. Spell out
              // the flow so they know what to expect.
              tab === 'withdraw' && h('div', {
                style: {
                  fontSize: 11, color: 'var(--text-muted)',
                  lineHeight: 1.55, marginTop: 6,
                  padding: '8px 10px', borderRadius: 6,
                  background: 'var(--bg-elevated)', border: '1px solid var(--border)'
                }
              },
                h('strong', { style: { color: 'var(--text-primary)' } }, '⏱ Timing: '),
                // Was "admin review usually clears within 24h … cancel a PENDING
                // request". /withdraw never creates a PENDING row: it completes
                // on the spot (a Stripe Transfer when live, nothing when
                // simulated), so that copy promised a review and a cancel
                // window that do not exist.
                connect && connect.simulated
                  ? 'test mode — the withdrawal completes immediately and no real money is sent.'
                  : 'sent to your Stripe cash-out account as soon as you confirm; Stripe then pays it to your bank, usually within 1–2 business days.'
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

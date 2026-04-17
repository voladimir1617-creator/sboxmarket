// Top-level App component + ErrorBoundary.
// Owns marketplace state, wires modals, handles Stripe/Steam redirect return.
import { h, React, useState, useEffect, useCallback, useMemo, fmt, timeAgo } from './utils.js';
import {
  fetchListings, fetchListingsForItem, fetchHistory, fetchItem, buyListing,
  fetchWallet, fetchTransactions, fetchMe, logoutSteam, confirmDeposit, makeOffer,
  adminCheck, csrCheck, checkoutCart, fetchListingById, fetchPlatformRecentSales, fetchPublicStall, fetchPublicStallSold, fetchReviewsForUser,
  fetchEligibleReviews, leaveReview, fetchAuctionsEndingSoon, fetchOfferCounts,
  fetchAnnouncement, replyToReview, fetchJustListed, fetchTopSellers, fetchTopDeals,
  checkListingsActive, fetchFollowingFeed, fetchMarketStats
} from './api.js';
import { ItemImage, MaterialIcon } from './primitives.js';
import { GridCard, ListingRow, TrendCard } from './cards.js';
// Chat removed — was a placeholder with fake messages
import { NotificationBell, ThemePicker } from './nav-widgets.js';
import {
  ItemModal, WalletModal, FaqModal, SettingsModal, ProfileModal, TradesModal,
  SellItemsModal, MyStallModal, OffersModal, WatchlistModal, MyListingsModal
} from './modals.js';
import {
  DatabaseModal, BuyOrdersModal, LoadoutLabModal,
  NotificationsModal
} from './csfloat-modals.js';
import { AdminModal, CsrModal } from './staff-modals.js';
import { HelpModal } from './help-modal.js';
import { InfoModal } from './info-modal.js';
import { useRoute, navigate, paths, installAnchorInterceptor } from './router.js';

// ── Pending trade reminder — surfaces a slim banner whenever the signed-in
// user has a trade sitting in a state where they're the actor and the
// counterparty has been waiting > 2h. Keeps escrow moving without
// needing a scheduled email. Polls every 60s.
function PendingTradeReminder({ me }) {
  const [pending, setPending] = useState([]);
  const [dismissed, setDismissed] = useState(() => {
    try { return new Set(JSON.parse(localStorage.getItem('sb_trade_nudge_dismissed') || '[]')); }
    catch { return new Set(); }
  });
  useEffect(() => {
    if (!me) { setPending([]); return; }
    let alive = true;
    const reload = async () => {
      try {
        const r = await fetch('/api/trades', { credentials: 'same-origin' });
        if (!r.ok) return;
        const rows = await r.json();
        const now = Date.now();
        const stuck = (Array.isArray(rows) ? rows : []).filter(t => {
          const mine = (t.sellerUserId === me.id && (t.state === 'PENDING_SELLER_ACCEPT' || t.state === 'PENDING_SELLER_SEND'))
            || (t.buyerUserId === me.id && t.state === 'PENDING_BUYER_CONFIRM');
          if (!mine) return false;
          const age = now - (t.updatedAt || t.createdAt || now);
          return age > 2 * 3600 * 1000; // 2h
        });
        if (alive) setPending(stuck);
      } catch (_) {}
    };
    reload();
    const id = setInterval(reload, 60_000);
    return () => { alive = false; clearInterval(id); };
  }, [me?.id]);
  const visible = pending.filter(t => !dismissed.has(t.id));
  if (visible.length === 0) return null;
  const dismiss = (tradeId) => {
    const next = new Set(dismissed);
    next.add(tradeId);
    setDismissed(next);
    try { localStorage.setItem('sb_trade_nudge_dismissed', JSON.stringify([...next])); } catch (_) {}
  };
  const t = visible[0];
  const isSeller = t.sellerUserId === me.id;
  const action = t.state === 'PENDING_SELLER_ACCEPT' ? 'accept the trade'
               : t.state === 'PENDING_SELLER_SEND'   ? 'send the Steam offer'
               :                                        'confirm receipt';
  return h('div', { className: 'pending-trade-nudge', role: 'status' },
    h('span', { className: 'pending-trade-nudge-icon' }, '⇄'),
    h('div', { className: 'pending-trade-nudge-text' },
      h('strong', null, isSeller ? 'Buyer is waiting on you' : 'Confirm your trade'),
      ' — "', t.itemName || ('Trade #' + t.id), '": ', action, ' before the auto-release window.'
    ),
    h('a', {
      className: 'pending-trade-nudge-cta',
      href: paths.profile(),
      onClick: () => dismiss(t.id)
    }, 'Open trade'),
    h('button', {
      className: 'pending-trade-nudge-close',
      onClick: () => dismiss(t.id),
      title: 'Dismiss',
      'aria-label': 'Dismiss reminder'
    }, '✕')
  );
}

// ── Stall review row with optional seller reply UI. Always-visible block
// when the review carries a sellerReply; otherwise the seller themselves
// (viewing their own stall) sees a "Reply" button that toggles an inline
// textarea. 300-char cap mirrors the service-layer sanitiser.
function StallReviewRow({ review, isOwner, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft]     = useState('');
  const [busy, setBusy]       = useState(false);
  const [err, setErr]         = useState('');
  const submit = async (clear = false) => {
    setBusy(true); setErr('');
    try {
      const res = await replyToReview(review.id, clear ? '' : (draft || '').trim());
      if (res && (res.error || res.code)) { setErr(res.message || res.error); return; }
      setEditing(false);
      setDraft('');
      onSaved && onSaved();
    } finally { setBusy(false); }
  };
  return h('div', { className: 'stall-review' },
    h('div', { className: 'stall-review-head' },
      h('span', { className: 'stall-review-stars' }, '★'.repeat(review.rating) + '☆'.repeat(5 - review.rating)),
      h('span', { className: 'stall-review-from' }, review.fromDisplayName || 'Anonymous'),
      h('span', { className: 'stall-review-time' },
        new Date(review.createdAt).toLocaleDateString()
      )
    ),
    review.itemName && h('div', { className: 'stall-review-item' }, '↳ ' + review.itemName),
    review.comment && h('div', { className: 'stall-review-body' }, review.comment),
    review.sellerReply && !editing && h('div', { className: 'stall-review-reply' },
      h('span', { className: 'stall-review-reply-label' }, 'Seller response'),
      h('div', { className: 'stall-review-reply-body' }, review.sellerReply)
    ),
    isOwner && !editing && h('div', { style: { marginTop: 8, display: 'flex', gap: 8 } },
      h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
        onClick: () => { setDraft(review.sellerReply || ''); setEditing(true); }
      }, review.sellerReply ? '✎ Edit reply' : '↩ Reply'),
      review.sellerReply && h('button', {
        className: 'btn btn-ghost',
        style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '4px 10px', fontSize: 11 },
        onClick: () => submit(true)
      }, 'Remove reply')
    ),
    isOwner && editing && h('div', { className: 'stall-review-reply-edit' },
      h('textarea', {
        value: draft,
        onChange: e => setDraft(e.target.value),
        maxLength: 300,
        placeholder: 'Public response to this review (300 chars max)',
        autoFocus: true
      }),
      h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 } },
        h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, disabled: busy, onClick: () => { setEditing(false); setDraft(''); setErr(''); } }, 'Cancel'),
        h('button', { className: 'btn btn-accent', disabled: busy || !draft.trim(), onClick: () => submit(false) }, busy ? 'Saving…' : 'Post reply')
      ),
      err && h('div', { className: 'wallet-error' }, err)
    )
  );
}

// ── Announcement banner — renders the single live sitewide message.
// Polls every 120s so banners posted mid-session still land without a
// page reload. Dismissible per-user in localStorage (keyed by
// announcement id) so an admin can post a fresh banner and everyone
// sees it again even if they dismissed the previous one.
// Subtle nag banner for signed-in users who haven't confirmed their email
// yet. Required for withdrawals and 2FA recovery — a silently-unverified
// account is a footgun six months in when the user can't reset their 2FA.
// Dismissable for 7 days via localStorage so the banner isn't permanent
// noise for long power sessions; the day-bucket cooldown resets naturally.
function EmailVerifyNag({ me }) {
  const [dismissedAt, setDismissedAt] = useState(() => {
    try { return Number(localStorage.getItem('sb_email_nag_dismissed_at') || 0); }
    catch { return 0; }
  });
  if (!me) return null;
  if (me.emailVerified) return null;
  if (!me.email) return null;
  // Seven-day cooldown — matches CSFloat's email reminder cadence.
  if (dismissedAt && (Date.now() - dismissedAt) < 7 * 24 * 3600_000) return null;
  const dismiss = () => {
    try { localStorage.setItem('sb_email_nag_dismissed_at', String(Date.now())); } catch (_) {}
    setDismissedAt(Date.now());
  };
  return h('div', { className: 'announce-banner sev-warn', role: 'status' },
    h('span', { className: 'announce-banner-icon' }, '✉'),
    h('div', { className: 'announce-banner-text' },
      'Your email ', h('strong', null, me.email), ' is not confirmed yet. ',
      h('a', { href: paths.profile(), style: { color: 'inherit', textDecoration: 'underline', fontWeight: 700 } }, 'Confirm it'),
      ' to enable withdrawals and 2FA recovery.'
    ),
    h('button', {
      className: 'announce-banner-close',
      onClick: dismiss,
      title: 'Remind me later (7 days)',
      'aria-label': 'Dismiss email verification reminder'
    }, '✕')
  );
}

function AnnouncementBanner() {
  const [ann, setAnn] = useState(null);
  const [dismissedId, setDismissedId] = useState(() => {
    try { return Number(localStorage.getItem('sb_announce_dismissed') || 0); }
    catch { return 0; }
  });
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchAnnouncement();
        if (alive) setAnn(data || null);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 120_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!ann || ann.id === dismissedId) return null;
  const dismiss = () => {
    try { localStorage.setItem('sb_announce_dismissed', String(ann.id)); } catch (_) {}
    setDismissedId(ann.id);
  };
  const sev = (ann.severity || 'INFO').toLowerCase();
  return h('div', { className: `announce-banner sev-${sev}`, role: 'status' },
    h('span', { className: 'announce-banner-icon' },
      sev === 'critical' ? '⚠' : sev === 'warn' ? '⚠' : 'ℹ'),
    h('div', { className: 'announce-banner-text' }, ann.message),
    h('button', {
      className: 'announce-banner-close',
      onClick: dismiss,
      title: 'Dismiss',
      'aria-label': 'Dismiss announcement'
    }, '✕')
  );
}

// ── Rating breakdown — 5-row bar chart mirroring the Amazon / CSFloat
// review histogram. Each row: "5★  ████████ · 42". Used on stall page
// and Profile Reviews tab so buyers can see at a glance whether the
// rating is bimodal (5★/1★ split) or a smooth distribution.
export function RatingBreakdown({ summary }) {
  if (!summary || !summary.count || summary.count === 0) return null;
  const buckets = Array.isArray(summary.histogram)
    ? summary.histogram
    : [0, 0, 0, 0, 0];
  const max = Math.max(1, ...buckets);
  return h('div', { className: 'rating-breakdown' },
    buckets.map((n, i) => {
      const stars = 5 - i;
      const pct = Math.round((n / max) * 100);
      return h('div', { key: stars, className: 'rating-breakdown-row' },
        h('span', { className: 'rating-breakdown-stars' }, stars + '★'),
        h('div', { className: 'rating-breakdown-bar' },
          h('div', {
            className: 'rating-breakdown-fill',
            style: { width: pct + '%' }
          })
        ),
        h('span', { className: 'rating-breakdown-count' }, n)
      );
    })
  );
}

// ── Nav offers badge — actionable pending-incoming count. Only signed-in
// users see it; polls every 45s; clicking navigates to /offers.
function NavOffersBadge() {
  const [count, setCount] = useState(0);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const d = await fetchOfferCounts();
        if (alive) setCount(Number(d?.incomingPending || 0));
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 45_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  return h('a', {
    className: 'nav-icon-btn',
    href: paths.offers(),
    title: count > 0 ? `${count} offer${count === 1 ? '' : 's'} awaiting` : 'Offers',
    'aria-label': count > 0 ? `Offers (${count} pending)` : 'Offers'
  },
    h(MaterialIcon, { name: 'price_check', size: 18 }),
    count > 0 && h('div', { className: 'nav-icon-badge' }, count > 99 ? '99+' : count)
  );
}

// ── Auctions ending soon — polls /api/listings/ending-soon every 30s so
// the rail stays within ~30s of truth. Only renders when there's at least
// one active auction in the window, so the marketplace stays clean when
// nobody's running auctions.
function AuctionsEndingSoonRail({ watchlist, onToggleStar, onOpen }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchAuctionsEndingSoon(60 * 60 * 1000);
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 30_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'auctions-ending-soon' },
    h('div', { className: 'auctions-ending-soon-head' },
      h('span', { className: 'auctions-ending-soon-dot' }),
      h('span', null, 'Auctions ending soon'),
      h('span', { className: 'auctions-ending-soon-count' }, `${rows.length} live`)
    ),
    h('div', { className: 'auctions-ending-soon-rail' },
      rows.map(l => h('div', {
        key: 'ends-' + l.id,
        className: 'auctions-ending-soon-card-wrap',
        onClick: () => onOpen(l)
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l)
        })
      ))
    )
  );
}

// ── Top sellers rail — aggregate the highest-volume sellers by completed
// sales count and surface them for social proof. Anon-friendly (public
// endpoint), hides when the platform has no sellers meeting the threshold.
// 5-minute poll is plenty — the aggregate shifts on the hours-to-days
// scale, not seconds.
function TopSellersRail() {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchTopSellers();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'top-sellers-rail' },
    h('div', { className: 'top-sellers-head' },
      h('span', { className: 'section-title-dot' }),
      h('span', null, 'Top sellers'),
      h('span', { className: 'top-sellers-count' }, `${rows.length} active`)
    ),
    h('div', { className: 'top-sellers-track' },
      rows.map(s => h('a', {
        key: 'ts-' + s.id,
        href: paths.stall(s.id),
        className: 'top-seller-card'
      },
        h('div', { className: 'top-seller-avatar' },
          s.avatarUrl
            ? h('img', { src: s.avatarUrl, alt: s.displayName, loading: 'lazy' })
            : (s.displayName || 'U').substring(0, 2).toUpperCase()
        ),
        h('div', { className: 'top-seller-body' },
          h('div', { className: 'top-seller-name' },
            s.displayName || 'Player',
            s.verified && h('span', { className: 'top-seller-verified', title: 'Verified seller' }, '✓')
          ),
          h('div', { className: 'top-seller-meta' },
            `${s.soldCount} sold`,
            (s.rating && s.rating.count > 0)
              ? ` · ★ ${Number(s.rating.average || 0).toFixed(1)}`
              : ''
          )
        )
      ))
    )
  );
}

// ── Top deals — deepest-discount rail. Reads /api/listings/top-deals
// (sorted by price/steamPrice ratio) and renders a compact horizontal
// strip. Polls every 2 min — deal ordering shifts as sellers re-price.
function TopDealsRail({ watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchTopDeals();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 120_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'top-deals-rail' },
    h('div', { className: 'top-deals-head' },
      h('span', { className: 'top-deals-spark' }, '%'),
      h('span', null, 'Top deals today'),
      h('span', { className: 'top-deals-count' }, `${rows.length} under Steam price`)
    ),
    h('div', { className: 'top-deals-track' },
      rows.map(l => h('div', {
        key: 'td-' + l.id,
        className: 'top-deals-card-wrap',
        onClick: () => onOpen(l)
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

// ── Just listed — "what just dropped" rail. Polls every 45s; hides when
// empty. Sits on the marketplace home below the ending-soon strip.
function JustListedRail({ watchlist, onToggleStar, onOpen }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchJustListed();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 45_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail' },
    h('div', { className: 'just-listed-head' },
      h('span', { className: 'just-listed-dot' }),
      h('span', null, 'Just listed'),
      h('span', { className: 'just-listed-count' }, `${rows.length} fresh`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(l => h('div', {
        key: 'just-' + l.id,
        className: 'just-listed-card-wrap',
        onClick: () => onOpen(l)
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l)
        })
      ))
    )
  );
}

// Platform-wide "Just sold" ticker. Social proof on the homepage —
// anonymous visitors see the marketplace is live the moment the page
// loads. Polls /api/listings/recent-sales every 30s so new sales land
// in the rail without a manual refresh. Hides itself when the platform
// has no completed sales yet.
// ── Platform stats strip — renders under the hero. Pulls the public
// /api/listings/stats endpoint for volume24h + activeListings + floor
// and reshapes them into a trust-signal bar. Silent when the
// marketplace is empty so a fresh install doesn't show "$0 traded".
function MarketStatsStrip() {
  const [s, setS] = useState(null);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      const data = await fetchMarketStats();
      if (alive) setS(data);
    };
    load();
    // 5-minute poll — the endpoint is a single indexed aggregate query
    // so refreshing isn't expensive, but stats don't change fast enough
    // to need anything snappier.
    const id = setInterval(load, 5 * 60 * 1000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!s) return null;
  const active = Number(s.activeListings || 0);
  const vol    = parseFloat(s.volume24h || 0);
  const floor  = parseFloat(s.floorPrice || 0);
  if (active === 0 && vol === 0) return null;  // empty-state guard
  const Stat = (label, value) => h('div', {
    style: {
      display: 'flex', flexDirection: 'column', gap: 2,
      minWidth: 0
    }
  },
    h('span', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', fontWeight: 700 } }, label),
    h('span', { style: { fontSize: 14, fontWeight: 800, color: 'var(--text-primary)', fontFamily: 'JetBrains Mono, monospace' } }, value)
  );
  return h('section', {
    className: 'market-stats-strip',
    style: {
      margin: '18px auto 0',
      maxWidth: 1260,
      padding: '12px 18px',
      borderRadius: 10,
      background: 'var(--bg-card)',
      border: '1px solid var(--border)',
      display: 'flex', gap: 36, flexWrap: 'wrap', alignItems: 'center'
    }
  },
    h('span', { style: { fontSize: 11, color: 'var(--text-muted)', fontWeight: 700 } }, '📊 Marketplace at a glance'),
    Stat('Active listings', active.toLocaleString()),
    vol > 0 && Stat('24h volume', fmt(vol)),
    floor > 0 && Stat('Starting at', fmt(floor))
  );
}

// ── Following feed — fresh listings from sellers the signed-in user
// follows. Silent for anonymous viewers and for users who follow
// nobody yet. Re-polls every 90s since it's personalised and we
// don't want to spam the server with identical follower-fanout
// queries.
function FollowingRail({ me, watchlist, onToggleStar, onOpen, onAddToCart, cartHas }) {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    if (!me) { setRows([]); return; }
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchFollowingFeed();
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 90_000);
    return () => { alive = false; clearInterval(id); };
  }, [me?.id]);
  if (!me || !rows || rows.length === 0) return null;
  return h('section', { className: 'top-deals-rail' },
    h('div', { className: 'top-deals-head' },
      h('span', { className: 'top-deals-spark' }, '♥'),
      h('span', null, 'From sellers you follow'),
      h('span', { className: 'top-deals-count' },
        `${rows.length} listing${rows.length === 1 ? '' : 's'} from your follows`)
    ),
    h('div', { className: 'top-deals-track' },
      rows.map(l => h('div', {
        key: 'follow-' + l.id,
        className: 'top-deals-card-wrap',
        onClick: () => onOpen(l)
      },
        h(GridCard, {
          listing: l,
          starred: watchlist.includes(l.item.id),
          onToggleStar,
          onClick: () => onOpen(l),
          onAddToCart,
          cartHas
        })
      ))
    )
  );
}

function JustSoldRail() {
  const [rows, setRows] = useState([]);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await fetchPlatformRecentSales(12);
        if (alive) setRows(Array.isArray(data) ? data : []);
      } catch (_) {}
    };
    load();
    const id = setInterval(load, 30_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  if (!rows || rows.length === 0) return null;
  return h('section', { className: 'just-listed-rail' },
    h('div', { className: 'just-listed-head' },
      h('span', { className: 'just-listed-dot', style: { background: '#22c55e' } }),
      h('span', null, 'Just sold'),
      h('span', { className: 'just-listed-count' }, `${rows.length} recent`)
    ),
    h('div', { className: 'just-listed-track' },
      rows.map(s => h('a', {
        key: 'js-' + s.listingId,
        href: s.itemId ? ('/item/' + s.itemId) : '#',
        className: 'top-seller-card',
        style: { textDecoration: 'none', minWidth: 220, padding: 10 },
        title: `${s.itemName || 'Item'} sold for ${fmt(s.price)} · ${timeAgo(s.soldAt)}`
      },
        h('div', { className: 'top-seller-body', style: { width: '100%' } },
          h('div', { className: 'top-seller-name', style: { fontSize: 13, fontWeight: 700 } },
            s.itemName || 'Item'
          ),
          h('div', { className: 'top-seller-meta' },
            h('span', { style: { color: 'var(--green)', fontWeight: 800, fontFamily: 'JetBrains Mono, monospace' } },
              fmt(s.price)),
            h('span', { style: { color: 'var(--text-muted)', marginLeft: 8 } },
              timeAgo(s.soldAt))
          )
        )
      ))
    )
  );
}

// Follow / unfollow a seller. Fetches current status once on mount
// so the button label reflects reality; click flips optimistically.
// Click-when-following unfollows, click-when-not follows. Shows the
// current follower count as a quiet chip so buyers see social proof.
function FollowSellerButton({ sellerId, showToast }) {
  const [status, setStatus] = useState(null);
  const [busy, setBusy]     = useState(false);
  useEffect(() => {
    let alive = true;
    import('./api.js').then(({ fetchFollowStatus }) => fetchFollowStatus(sellerId))
      .then(data => { if (alive) setStatus(data || { following: false, followerCount: 0 }); })
      .catch(() => { if (alive) setStatus({ following: false, followerCount: 0 }); });
    return () => { alive = false; };
  }, [sellerId]);
  const toggle = async () => {
    if (!status) return;
    setBusy(true);
    try {
      const { followSeller, unfollowSeller } = await import('./api.js');
      const res = status.following ? await unfollowSeller(sellerId) : await followSeller(sellerId);
      if (res && (res.error || res.code)) {
        alert(res.message || res.error || 'Could not update follow');
        return;
      }
      const nowFollowing = !status.following;
      setStatus({
        following: nowFollowing,
        followerCount: status.followerCount + (nowFollowing ? 1 : -1)
      });
      showToast && showToast(nowFollowing ? 'Following — you\'ll be notified of new listings' : 'Unfollowed', 'ok');
    } finally { setBusy(false); }
  };
  if (!status) {
    return h('button', { className: 'stall-share-btn', disabled: true }, '…');
  }
  const cls = status.following ? 'stall-share-btn' : 'stall-share-btn';
  const style = status.following
    ? { border: '1px solid var(--border)', opacity: 0.75 }
    : { border: '1px solid var(--accent-border)', color: 'var(--accent)' };
  return h('button', {
    className: cls, style, disabled: busy, onClick: toggle,
    title: status.following ? 'Click to unfollow' : 'Get notified when this seller lists something new'
  },
    h('span', { className: 'stall-share-icon' }, status.following ? '✓' : '+'),
    status.following ? 'Following' : 'Follow',
    status.followerCount > 0 && h('span', {
      style: { marginLeft: 6, fontSize: 10, opacity: 0.7 }
    }, `· ${status.followerCount}`)
  );
}

// ── Share stall — copies the canonical URL to the clipboard with a
// toast fallback if the browser doesn't grant clipboard-write permission.
// Keeps the stall-hero compact; no floating-menu popover.
function ShareStallButton({ userId, showToast }) {
  const [copied, setCopied] = useState(false);
  const share = async () => {
    const url = `${window.location.origin}/stall/${userId}`;
    try {
      if (navigator.share) {
        await navigator.share({ title: 'SkinBox stall', url });
      } else if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(url);
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
        showToast && showToast('Link copied to clipboard', 'ok');
      } else {
        // Last-ditch fallback: prompt so the user can copy manually.
        window.prompt('Copy this link:', url);
      }
    } catch (e) {
      window.prompt('Copy this link:', url);
    }
  };
  return h('button', {
    className: 'stall-share-btn',
    onClick: share,
    title: 'Copy a link to this stall',
    'aria-label': 'Share stall'
  },
    h('span', { className: 'stall-share-icon' }, copied ? '✓' : '⎘'),
    copied ? 'Copied' : 'Share stall'
  );
}

// ── Recently viewed rail — reads sb_recently_viewed, renders a compact
// horizontal strip that mirrors CSFloat's "Recently browsed" row. Only
// renders when the user has at least two entries so it doesn't show up
// on a brand-new visitor's first page view.
function RecentlyViewedRail({ watchlist, onToggleStar }) {
  const [rows, setRows] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]'); }
    catch { return []; }
  });
  // Re-read when any navigation happens (the recently-viewed list is written
  // from the item-detail effect, so popstate catches every update). Saves us
  // from a cross-component event bus.
  useEffect(() => {
    const reload = () => {
      try { setRows(JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]')); }
      catch { setRows([]); }
    };
    window.addEventListener('popstate', reload);
    return () => window.removeEventListener('popstate', reload);
  }, []);
  if (!rows || rows.length < 2) return null;
  return h('section', { className: 'recently-viewed' },
    h('div', { className: 'recently-viewed-head' },
      h('span', { className: 'section-title-dot' }),
      'Recently viewed',
      h('button', {
        className: 'recently-viewed-clear',
        onClick: () => { localStorage.removeItem('sb_recently_viewed'); setRows([]); }
      }, 'Clear')
    ),
    h('div', { className: 'recently-viewed-rail' },
      rows.map(it => h('a', {
        key: it.id,
        href: paths.item(it.id),
        className: 'recently-viewed-card'
      },
        h('div', { className: 'recently-viewed-thumb' },
          it.imageUrl
            ? h('img', { src: it.imageUrl, alt: it.name, loading: 'lazy' })
            : h('div', { className: 'recently-viewed-glyph', style: { color: it.accentColor || '#60a5fa' } },
                it.iconEmoji || '📦')
        ),
        h('div', { className: 'recently-viewed-name' }, it.name),
        h('div', { className: 'recently-viewed-price' },
          it.lowestPrice != null ? fmt(it.lowestPrice) : '—')
      ))
    )
  );
}

export class ErrorBoundary extends React.Component {
  constructor(props) { super(props); this.state = { error: null }; }
  static getDerivedStateFromError(error) { return { error }; }
  componentDidCatch(error, info) { console.error('ErrorBoundary caught:', error, info); }
  render() {
    if (this.state.error) {
      const err = this.state.error;
      return h('div', {
        style: {
          padding: '40px', maxWidth: 900, margin: '40px auto',
          background: '#1a0a0a', border: '1px solid #f87171',
          borderRadius: 12, color: '#fca5a5',
          fontFamily: 'JetBrains Mono, monospace', fontSize: 13, lineHeight: 1.6
        }
      },
        h('h1', { style: { color: '#f87171', fontSize: 22, marginBottom: 12 } }, '💥 SkinBox render error'),
        h('div', { style: { color: '#fca5a5', marginBottom: 16 } },
          'Something threw during render. Full stack below:'),
        h('pre', { style: { whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#0a0a0a', padding: 16, borderRadius: 8 } },
          String(err && err.stack ? err.stack : err)
        ),
        h('div', { style: { marginTop: 18, display: 'flex', gap: 10 } },
          h('button', {
            style: { padding: '10px 20px', background: '#1ea5ff', color: '#051018', border: 'none', borderRadius: 8, fontWeight: 700, cursor: 'pointer' },
            onClick: () => location.reload()
          }, 'Reload'),
          h('button', {
            style: { padding: '10px 20px', background: 'transparent', color: '#1ea5ff', border: '1px solid #1ea5ff', borderRadius: 8, fontWeight: 700, cursor: 'pointer' },
            onClick: () => { location.href = '/'; }
          }, 'Go home')
        )
      );
    }
    return this.props.children;
  }
}

// Install the anchor interceptor exactly once, at module load, so every `<a
// href="/...">` in the app routes client-side instead of triggering a reload.
installAnchorInterceptor();

// Full-width site footer — rendered at the bottom of every route. Multi-column
// link map plus a "Powered by Stripe" mark that points users at the real
// payment processor. Surfacing the Stripe badge here gives visible proof that
// the integration is wired; the same badge appears inside the wallet page.
export function SiteFooter() {
  // Catalog sync status — polls /api/items/stats every 5 min and shows
  // "Catalog updated X ago" in the bottom meta bar. Quiet trust signal:
  // buyers know the floor prices haven't drifted from Steam for hours.
  const [lastSync, setLastSync] = useState(0);
  const [version, setVersion]   = useState('');
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/items/stats', { credentials: 'same-origin' });
        if (!r.ok) return;
        const d = await r.json();
        if (alive && typeof d?.lastSyncedAt === 'number') setLastSync(d.lastSyncedAt);
      } catch (_) {}
    };
    load();
    // Version is a one-shot fetch — it doesn't change without a deploy.
    fetch('/api/version', { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (alive && d?.version) setVersion(d.version); })
      .catch(() => {});
    const id = setInterval(load, 5 * 60_000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  return h('footer', { className: 'site-footer' },
    h('div', { className: 'site-footer-inner' },
      h('div', { className: 'site-footer-col site-footer-brand' },
        h('div', { className: 'site-footer-logo' },
          h('div', { className: 'nav-logo-icon' },
            h('img', {
              src: '/img/logo.png',
              alt: 'SkinBox',
              onError: (e) => { e.target.style.display = 'none'; e.target.parentElement.textContent = 'SB'; }
            })
          ),
          h('span', { className: 'nav-logo-text' }, 'SkinBox')
        ),
        h('p', { className: 'site-footer-tag' },
          'The s&box skin marketplace. Real-time prices, verified sellers, and escrowed trades — built for the Workshop community.'),
        h('div', { className: 'site-footer-badges' },
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
          h('span', { className: 'trust-badge' },
            h(MaterialIcon, { name: 'lock', size: 12 }),
            ' TLS 1.3 · Webhook-signed'
          )
        )
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Marketplace'),
        h('a', { href: paths.market() }, 'Browse Market'),
        h('a', { href: paths.database() }, 'Item Database'),
        h('a', { href: paths.buyorders() }, 'Buy Orders'),
        h('a', { href: paths.sell() }, 'Sell Items'),
        h('a', { href: paths.loadouts() }, 'Loadout Lab')
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Account'),
        h('a', { href: paths.profile() }, 'Profile'),
        h('a', { href: paths.wallet() }, 'Wallet'),
        h('a', { href: paths.offers() }, 'Offers'),
        h('a', { href: paths.watchlist() }, 'Watchlist'),
        h('a', { href: paths.notifications() }, 'Notifications')
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Resources'),
        h('a', { href: paths.help() }, 'Help Center'),
        h('a', { href: paths.faq() }, 'FAQ'),
        h('a', { href: paths.support() }, 'Support'),
        h('a', { href: paths.faq() }, 'Fees & Pricing'),
        h('a', { href: '/status.html' }, 'System Status'),
        h('a', { href: '/changelog.html' }, 'Changelog'),
        h('a', { href: paths.settings() }, 'Settings')
      ),
      h('div', { className: 'site-footer-col' },
        h('div', { className: 'site-footer-title' }, 'Legal'),
        h('a', { href: '/legal/terms.html' }, 'Terms of Service'),
        h('a', { href: '/legal/trade-safety.html' }, 'Trade Safety'),
        h('a', { href: '/legal/disclaimer.html' }, 'Risk Disclaimer'),
        h('a', { href: '/legal/acceptable-use.html' }, 'Acceptable Use'),
        h('a', { href: '/legal/cookies.html' }, 'Cookies')
        /* Privacy Policy and Refund Policy deliberately NOT surfaced here.
           The HTML files still exist at /legal/privacy.html and
           /legal/refunds.html so GDPR requests, payment processors, and
           search engines can discover them, but no user-facing nav links
           point to them. Payment processors (Stripe) will ask for a
           Privacy Policy URL — give them the direct link then. */
      )
    ),
    h('div', { className: 'site-footer-bottom' },
      h('div', { className: 'site-footer-copy' },
        '© ', new Date().getFullYear(), ' SkinBox · Not affiliated with Facepunch Studios. s&box is a trademark of Facepunch Ltd.',
        version && h('span', { style: { opacity: 0.6, marginLeft: 10 } }, '· v', version)),
      h('div', { className: 'site-footer-meta' },
        h('span', null, 'All prices in USD'),
        h('span', { className: 'dot' }, '·'),
        h('span', null, 'Stripe-secured payments'),
        h('span', { className: 'dot' }, '·'),
        h('span', null, 'Steam OpenID auth'),
        lastSync > 0 && h('span', { className: 'dot' }, '·'),
        lastSync > 0 && h('span', { className: 'footer-sync-badge', title: `Last catalogue sync: ${new Date(lastSync).toLocaleString()}` },
          h('span', { className: 'footer-sync-dot' }),
          'Catalog updated ', timeAgo(lastSync))
      )
    )
  );
}

// Pre-signin consent modal — appears the first time a visitor clicks
// "Sign in through Steam". Requires a checked ToS + Privacy box and a
// valid email address before it'll hand off to the Steam OpenID flow.
// Email is stashed in localStorage; the profile page fires a verification
// token against it automatically on first authenticated load.
export function PreSigninModal({ onClose, onAccept }) {
  // Restore any pending email the user entered before — if they closed the
  // modal by accident, they don't have to retype it.
  const [email,     setEmail]     = useState(() => {
    try { return localStorage.getItem('sb_pending_email') || ''; } catch { return ''; }
  });
  const [tos,       setTos]       = useState(false);
  const [marketing, setMarketing] = useState(() => {
    try { return localStorage.getItem('sb_marketing_opt_in') === '1'; } catch { return false; }
  });
  const [err,       setErr]       = useState('');

  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  const submit = () => {
    setErr('');
    if (!EMAIL_RE.test(email.trim())) { setErr('Please enter a valid email address'); return; }
    if (!tos)                         { setErr('You must agree to the Terms of Service'); return; }
    onAccept(email.trim(), marketing);
  };

  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', {
      className: 'modal presignin-modal',
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-labelledby': 'presignin-title'
    },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close' }, '✕'),
      h('div', { className: 'presignin-inner' },
        h('div', { className: 'presignin-logo' },
          h('img', {
            src: '/img/logo-square.png',
            alt: 'SkinBox',
            onError: (e) => { e.target.style.display = 'none'; }
          })
        ),
        h('h2', { id: 'presignin-title', className: 'presignin-title' }, 'Welcome to SkinBox'),
        h('p', { className: 'presignin-sub' },
          'Before we hand you off to Steam, we need your email for account recovery, receipts, and a one-time verification code.'),

        h('label', { className: 'presignin-label', htmlFor: 'presignin-email' }, 'Email address'),
        h('input', {
          id: 'presignin-email',
          className: 'presignin-input',
          type: 'email',
          value: email,
          onChange: e => setEmail(e.target.value),
          onKeyDown: e => { if (e.key === 'Enter') submit(); },
          placeholder: 'you@example.com',
          autoComplete: 'email',
          required: true
        }),

        h('label', { className: 'presignin-check' },
          h('input', {
            type: 'checkbox',
            checked: tos,
            onChange: e => setTos(e.target.checked)
          }),
          h('span', null,
            'I agree to the ',
            h('a', { href: '/legal/terms.html', target: '_blank', rel: 'noopener' }, 'Terms of Service'),
            ' and ',
            h('a', { href: '/legal/privacy.html', target: '_blank', rel: 'noopener' }, 'Privacy Policy'),
            '.'
          )
        ),

        err && h('div', { className: 'presignin-error' }, err),

        h('button', {
          className: 'btn btn-accent presignin-submit',
          onClick: submit,
          type: 'button'
        },
          h('div', { className: 'steam-btn-icon' },
            h('svg', {
              viewBox: '0 0 24 24',
              width: 20,
              height: 20,
              fill: 'currentColor',
              'aria-hidden': 'true'
            },
              h('path', {
                d: 'M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z'
              })
            )
          ),
          'Continue to Steam'
        ),

        h('div', { className: 'presignin-footnote' },
          'Your password never touches our servers — Steam handles the login. We only see your public Steam profile via OpenID.'
        )
      )
    )
  );
}

export function App() {
  // Router — every feature is reachable by its own URL. Modal state has been
  // replaced with route-driven rendering. `routeName` is what we switch on.
  const route = useRoute();
  const routeName = route.name;

  // Set a meaningful document.title per route so browser tabs + browser
  // history actually describe the page. The NotificationBell unread
  // prefix sits on top of whatever base title we set. For item detail
  // the actual item name lands below in the item-load effect.
  useEffect(() => {
    const titles = {
      market:        'Marketplace · SkinBox',
      database:      'Item Database · SkinBox',
      watchlist:     'Watchlist · SkinBox',
      sell:          'Sell Items · SkinBox',
      mystall:       'My Stall · SkinBox',
      cart:          'Cart · SkinBox',
      wallet:        'Wallet · SkinBox',
      profile:       'Profile · SkinBox',
      offers:        'Offers · SkinBox',
      buyorders:     'Buy Orders · SkinBox',
      notifications: 'Notifications · SkinBox',
      support:       'Support · SkinBox',
      help:          'Help Center · SkinBox',
      faq:           'FAQ · SkinBox',
      settings:      'Settings · SkinBox',
      admin:         'Admin Panel · SkinBox',
      csr:           'Customer Service · SkinBox',
      loadouts:      'Loadout Lab · SkinBox',
      stall:         'Seller Stall · SkinBox',
      item:          'Item · SkinBox'
    };
    const base = titles[routeName] || 'SkinBox — s&box Marketplace';
    // Preserve any (N) unread-notifications prefix set by NotificationBell.
    const currentPrefix = (document.title.match(/^(\(\d+\)\s+)/) || [, ''])[1];
    document.title = currentPrefix + base;
  }, [routeName]);

  // marketplace state
  const [listings, setListings]         = useState([]);
  const [loading, setLoading]           = useState(true);
  const [view, setView]                 = useState('grid');

  // filters
  // Skinport pattern: debounce the raw search input so the filtering
  // pipeline only re-runs 300ms after the user stops typing. Without
  // this, every keystroke re-filters thousands of listings and re-renders
  // every grid card. `search` is the committed value used by filters;
  // `searchInput` is what the text box holds while the user types.
  // Initial filter state is seeded from the URL query string so deep-links
  // like /market?category=Hats&sort=discount land on the same view the
  // sender saw. Read-once on mount — we update the URL back out below via
  // replaceState so subsequent in-app filter changes stay shareable without
  // thrashing the back/forward history.
  const __urlParams = (() => {
    try { return new URLSearchParams(window.location.search); }
    catch { return new URLSearchParams(); }
  })();
  const ALLOWED_SORTS      = ['price_desc','price_asc','newest','rarity','discount'];
  const ALLOWED_CATEGORIES = ['All','Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'];
  const ALLOWED_RARITIES   = ['All','Limited','Off-Market','Standard'];
  const __initialQ        = (__urlParams.get('q') || '').slice(0, 80);
  const __initialSort     = ALLOWED_SORTS.includes(__urlParams.get('sort')) ? __urlParams.get('sort') : 'price_desc';
  const __initialCategory = ALLOWED_CATEGORIES.includes(__urlParams.get('category')) ? __urlParams.get('category') : 'All';
  const __initialRarity   = ALLOWED_RARITIES.includes(__urlParams.get('rarity')) ? __urlParams.get('rarity') : 'All';
  const __initialMin      = (__urlParams.get('min') || '').slice(0, 16);
  const __initialMax      = (__urlParams.get('max') || '').slice(0, 16);

  const [searchInput, setSearchInput]   = useState(__initialQ);
  const [search, setSearch]             = useState(__initialQ);
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  // Autocomplete suggestions — keyboard-navigable dropdown showing up to 8
  // item matches. Debounced at 180ms so typing "watch" doesn't fire 5 GETs.
  // Closes on click-outside, Esc, or selecting a suggestion.
  const [suggest, setSuggest]           = useState([]);
  const [suggestOpen, setSuggestOpen]   = useState(false);
  const [suggestIdx, setSuggestIdx]     = useState(-1);
  // Recent search strings — persists last 6 across sessions. Populated
  // when the user presses Enter or selects a suggestion; surfaced when
  // the input is empty-focused so users can re-run a prior query.
  const [recentSearches, setRecentSearches] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_recent_searches') || '[]'); }
    catch { return []; }
  });
  const pushRecentSearch = (q) => {
    if (!q || !q.trim() || q.trim().length < 2) return;
    const val = q.trim();
    setRecentSearches(prev => {
      const next = [val, ...prev.filter(x => x.toLowerCase() !== val.toLowerCase())].slice(0, 6);
      try { localStorage.setItem('sb_recent_searches', JSON.stringify(next)); } catch (_) {}
      return next;
    });
  };

  // Saved searches — named filter presets so users who repeatedly hunt
  // the same slice of the marketplace (e.g. "Limited hats under $20 +
  // biggest discount") can re-apply the whole filter set in one click.
  // Capped at 10 — beyond that the dropdown becomes a scroll-hell.
  const [savedSearches, setSavedSearches] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_saved_searches') || '[]'); }
    catch { return []; }
  });
  const persistSavedSearches = (next) => {
    try { localStorage.setItem('sb_saved_searches', JSON.stringify(next)); } catch (_) {}
    setSavedSearches(next);
  };
  const saveCurrentSearch = () => {
    // Require at least one non-default filter — "all items, default sort"
    // is meaningless to save.
    const hasAnyFilter = search || category !== 'All' || rarity !== 'All' ||
                         sort !== 'price_desc' || minPrice || maxPrice;
    if (!hasAnyFilter) {
      alert('Adjust at least one filter before saving a search.');
      return;
    }
    const defaultName = [
      search ? `"${search}"` : null,
      category !== 'All' ? category : null,
      rarity !== 'All' ? rarity : null,
      (minPrice || maxPrice) ? `$${minPrice || 0}–${maxPrice || '∞'}` : null
    ].filter(Boolean).join(' · ') || 'Untitled';
    const name = window.prompt('Name this search (shows up in the dropdown):', defaultName);
    if (!name || !name.trim()) return;
    if (savedSearches.length >= 10) {
      alert('Saved-search slot limit (10) reached — delete one first.');
      return;
    }
    const entry = {
      id: Date.now(),
      name: name.trim().slice(0, 60),
      search, category, rarity, sort, minPrice, maxPrice,
      savedAt: Date.now()
    };
    persistSavedSearches([entry, ...savedSearches]);
  };
  const applySavedSearch = (s) => {
    setSearch(s.search || '');
    setSearchInput(s.search || '');
    setCategory(s.category || 'All');
    setRarity(s.rarity || 'All');
    setSort(s.sort || 'price_desc');
    setMinPrice(s.minPrice || '');
    setMaxPrice(s.maxPrice || '');
  };
  const deleteSavedSearch = (id) => {
    if (!confirm('Delete this saved search?')) return;
    persistSavedSearches(savedSearches.filter(s => s.id !== id));
  };
  useEffect(() => {
    const q = (searchInput || '').trim();
    if (q.length < 2) { setSuggest([]); return; }
    const t = setTimeout(async () => {
      try {
        const r = await fetch(`/api/items?q=${encodeURIComponent(q)}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const items = await r.json();
        setSuggest(Array.isArray(items) ? items.slice(0, 8) : []);
      } catch (_) {}
    }, 180);
    return () => clearTimeout(t);
  }, [searchInput]);
  useEffect(() => {
    const onDoc = (e) => {
      if (!e.target.closest?.('.search-wrap')) setSuggestOpen(false);
    };
    document.addEventListener('click', onDoc);
    return () => document.removeEventListener('click', onDoc);
  }, []);
  const [category, setCategory]         = useState(__initialCategory);
  const [rarity, setRarity]             = useState(__initialRarity);
  const [sort, setSort]                 = useState(__initialSort);
  const [minPrice, setMinPrice]         = useState(__initialMin);
  const [maxPrice, setMaxPrice]         = useState(__initialMax);
  // Listing-type filter. Three values: 'ALL' | 'BUY_NOW' | 'AUCTION'. We
  // apply this client-side on top of the server response so users can
  // toggle instantly without a roundtrip. Buy-now includes null
  // listingType for historical rows.
  const [listingTypeFilter, setListingTypeFilter] = useState('ALL');
  // Deal hunter toggle — when on, only show listings priced below the
  // catalogue steamPrice (i.e. cheaper than you'd pay on Steam Market).
  // Pure client-side filter applied before dedup so the cheapest seller
  // per item still wins the grid card.
  const [dealsOnly, setDealsOnly] = useState(false);
  // New-in-24h toggle — highlights fresh inventory. Client-side filter
  // on listedAt; pairs cleanly with Deals and the type toggles.
  const [newOnly, setNewOnly] = useState(false);

  // item detail
  const [selected, setSelected]         = useState(null);
  const [modalLoading, setModalLoading] = useState(false);

  // wallet
  const [wallet, setWallet]             = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [walletOpen, setWalletOpen]     = useState(false);
  const [walletInitialTab, setWalletInitialTab] = useState('deposit');
  // Deposit prefill — when the cart low-balance banner sends the user to
  // /wallet we stash the shortfall so the deposit form opens with the
  // right number already typed. Cleared once consumed. Also respected
  // from the URL: /wallet?prefill=12.34
  const [walletPrefillAmount, setWalletPrefillAmount] = useState(null);

  // auth
  // `meLoaded` is false until the first fetchMe() resolves. We use this
  // to hide the hero block on the very first paint — otherwise the page
  // renders the signed-out "Welcome to SkinBox" hero for ~200ms before
  // the cookie-based session comes back and flips it to the signed-in
  // "Welcome back, <name>" hero. That flash of wrong content is what
  // the user calls "flickering on reload".
  const [me, setMe]                     = useState(null);
  const [meLoaded, setMeLoaded]         = useState(false);
  const [menuOpen, setMenuOpen]         = useState(false);
  const [isAdmin, setIsAdmin]           = useState(false);
  const [isCsr, setIsCsrRole]           = useState(false);
  // Pre-signin ToS + email modal state. Opens on the "Sign in through
  // Steam" button; redirects to the real OpenID flow after the user ticks
  // the ToS box and enters a valid email.
  const [signinOpen, setSigninOpen]     = useState(false);

  // layout
  const chatHidden = true; // chat removed
  const [heroTab, setHeroTab]           = useState('topDeals');
  const [feeInput, setFeeInput]         = useState('100');

  // "Preselected" item the BuyOrdersModal uses when opened from ItemModal.
  // Not part of the URL — ephemeral state that lives only while the
  // buy-orders route is active for this specific item.
  const [preselectedBuyItem, setPreselectedBuyItem] = useState(null);

  // Public stall page data — loaded whenever we hit /stall/:id.
  // Reviews are fetched in parallel with the listings payload so the
  // rating chip + "Recent reviews" block render together. Eligible trades
  // only populate when a signed-in viewer loads someone else's stall.
  const [stallData, setStallData] = useState(null);
  const [stallReviews, setStallReviews] = useState(null);
  const [stallSold, setStallSold]       = useState([]);
  const [eligibleTrades, setEligibleTrades] = useState([]);
  // Star-rating filter for the recent-reviews strip. 0 = all.
  const [stallStarFilter, setStallStarFilter] = useState(0);
  // Stall listing filter + sort controls. Rarity stays 'All' by default
  // so new visitors see every listing; sort defaults to price_asc which
  // mirrors CSFloat's "best deal first" convention on stall views.
  const [stallRarity, setStallRarity] = useState('All');
  const [stallSort, setStallSort]     = useState('price_asc');
  useEffect(() => {
    if (routeName !== 'stall' || !route.params?.id) {
      setStallData(null); setStallReviews(null); setEligibleTrades([]); setStallSold([]); return;
    }
    let alive = true;
    Promise.all([
      fetchPublicStall(route.params.id),
      fetchReviewsForUser(route.params.id),
      me ? fetchEligibleReviews(route.params.id) : Promise.resolve([]),
      fetchPublicStallSold(route.params.id)
    ]).then(([stall, reviews, eligible, sold]) => {
      if (!alive) return;
      // Distinguish "still loading" (null) from "loaded but 404"
      // ({ __notFound: true }) so the render can show a friendly
      // empty-state instead of spinning forever on a bad id.
      setStallData(stall || { __notFound: true });
      setStallReviews(reviews);
      setEligibleTrades(Array.isArray(eligible) ? eligible : []);
      setStallSold(Array.isArray(sold) ? sold : []);
    });
    return () => { alive = false; };
  }, [routeName, route.params?.id, me?.user?.id]);

  // Inline review form state (lives on the stall page).
  const [reviewTradeId, setReviewTradeId] = useState(null);
  const [reviewStars, setReviewStars]     = useState(5);
  const [reviewText, setReviewText]       = useState('');
  const [reviewBusy, setReviewBusy]       = useState(false);
  const submitStallReview = async () => {
    if (!reviewTradeId) return;
    setReviewBusy(true);
    try {
      const res = await leaveReview(reviewTradeId, reviewStars, reviewText || '');
      if (res && !res.error) {
        setReviewTradeId(null); setReviewText(''); setReviewStars(5);
        // Refresh reviews + eligibility so the UI reflects the new state.
        const [reviews, eligible] = await Promise.all([
          fetchReviewsForUser(route.params.id),
          fetchEligibleReviews(route.params.id)
        ]);
        setStallReviews(reviews);
        setEligibleTrades(Array.isArray(eligible) ? eligible : []);
      }
    } finally { setReviewBusy(false); }
  };

  // privacy mode — hides balance + sensitive amounts across the whole UI
  const [privacy, setPrivacy] = useState(() => localStorage.getItem('sb_privacy') === '1');
  useEffect(() => { localStorage.setItem('sb_privacy', privacy ? '1' : '0'); }, [privacy]);

  // Settings change ticker — bumps on storage events so every rendered
  // `fmt()` call re-reads the current currency even when it was changed
  // in a different tab. We tick a dummy state and react's re-render picks
  // up the new fmt() output on next paint.
  const [, bumpCurrency] = useState(0);
  useEffect(() => {
    const onStorage = (e) => { if (e.key === 'sb_currency') bumpCurrency(x => x + 1); };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  // Shopping cart — stored as an array of { id, name, price, thumb } in
  // localStorage so it survives reloads and is still owned by the user, not
  // the server. Checkout POSTs just the ids to /api/cart/checkout.
  const [cart, setCart] = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_cart') || '[]'); } catch { return []; }
  });
  useEffect(() => { localStorage.setItem('sb_cart', JSON.stringify(cart)); }, [cart]);
  // Cross-tab cart sync — the browser fires a 'storage' event in every
  // tab EXCEPT the one that wrote the change, so this listener keeps
  // tabs B/C up-to-date when tab A adds or removes a row. Otherwise
  // the nav-badge count drifts until the tab is refreshed. Also used
  // to sync the watchlist and saved-search arrays.
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key === 'sb_cart') {
        try { setCart(JSON.parse(e.newValue || '[]')); } catch (_) {}
      } else if (e.key === 'sb_watchlist') {
        try { setWatchlist(JSON.parse(e.newValue || '[]')); } catch (_) {}
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
  // When the user lands on /cart, ping each cart-row's listing to make
  // sure it's still ACTIVE — if the listing was sold to someone else
  // (or force-cancelled) while it was sitting in this user's cart, we
  // drop it proactively + toast once so the checkout button doesn't
  // try to buy a 404. Only fires on cart route entry; no polling loop.
  useEffect(() => {
    if (routeName !== 'cart' || cart.length === 0) return;
    let alive = true;
    (async () => {
      const results = await Promise.all(cart.map(async it => {
        const l = await fetchListingById(it.id).catch(() => null);
        // null = 404 / network error; !ACTIVE = sold, cancelled, etc.
        return { id: it.id, stillActive: l && l.status === 'ACTIVE' };
      }));
      if (!alive) return;
      const goneIds = new Set(results.filter(r => !r.stillActive).map(r => r.id));
      if (goneIds.size === 0) return;
      setCart(c => c.filter(x => !goneIds.has(x.id)));
      setToast({
        text: `${goneIds.size} item${goneIds.size === 1 ? '' : 's'} removed — sold before checkout`,
        kind: 'err'
      });
      setTimeout(() => setToast(null), 4500);
    })();
    return () => { alive = false; };
  }, [routeName]);
  const cartCount = cart.length;
  const cartTotal = useMemo(() => cart.reduce((s, it) => s + (parseFloat(it.price) || 0), 0), [cart]);
  // Server-side freshness map — keyed by listing id. Re-fetched every
  // time /cart is opened because the cart is persisted client-side and
  // a row can go stale (bought by someone else) or have its price
  // edited by the seller between sessions. Missing keys render
  // neutrally (no banner) so a transient network blip doesn't scare
  // the buyer.
  const [cartFreshness, setCartFreshness] = useState({});
  useEffect(() => {
    if (routeName !== 'cart' || cart.length === 0) { setCartFreshness({}); return; }
    let alive = true;
    (async () => {
      const rows = await checkListingsActive(cart.map(it => it.id));
      if (!alive) return;
      const byId = {};
      rows.forEach(r => { byId[r.id] = r; });
      setCartFreshness(byId);
    })();
    return () => { alive = false; };
  }, [routeName, cart.length, cart.map(it => it.id).join(',')]);
  const cartHasStale = useMemo(
    () => cart.some(it => cartFreshness[it.id] && !cartFreshness[it.id].active),
    [cart, cartFreshness]
  );
  const removeStaleCartRows = () => setCart(c => c.filter(it => {
    const fresh = cartFreshness[it.id];
    return !fresh || fresh.active;
  }));
  const addToCart = (listing) => {
    setCart(c => {
      if (c.find(x => x.id === listing.id)) return c;
      return [...c, {
        id:         listing.id,
        itemId:     listing.item?.id,
        name:       listing.item?.name,
        price:      listing.price,
        // Stash the Steam reference price so the cart page can show
        // "saved $X vs Steam" without re-fetching the catalogue on
        // every render. Falls back to null when the catalogue has no
        // Steam Market data yet (new item, pre-sync).
        steamPrice: listing.item?.steamPrice ?? null,
        thumb:      listing.item?.imageUrl || null
      }];
    });
  };
  // Total Steam-reference price for every cart row that has a
  // steamPrice snapshot. Saves = max(0, steamTotal - cartTotal).
  const cartSteamTotal = useMemo(() => cart.reduce((s, it) => {
    const sp = parseFloat(it.steamPrice);
    return s + (isFinite(sp) && sp > 0 ? sp : parseFloat(it.price) || 0);
  }, 0), [cart]);
  const cartSavings = Math.max(0, cartSteamTotal - parseFloat(
    cart.reduce((s, it) => s + (parseFloat(it.price) || 0), 0)));
  const removeFromCart = (id) => setCart(c => c.filter(x => x.id !== id));
  const clearCart = () => setCart([]);
  // Confirmation gate so buyers see a summary before bulk checkout fires.
  // Without this the "Buy Now" button on /cart silently paid-and-trade-opened
  // every row, and if one failed the user had no reviewable explanation.
  const [cartConfirmOpen, setCartConfirmOpen] = useState(false);
  const [cartBusy, setCartBusy] = useState(false);
  const doCheckout = async () => {
    if (cart.length === 0) return;
    setCartBusy(true);
    try {
      const ids = cart.map(x => x.id);
      const res = await checkoutCart(ids);
      if (res && res.error) {
        showToast(res.error, 'err');
      } else if (res && res.results) {
        const ok = res.successful || 0;
        const fail = res.failed || 0;
        showToast(`Bought ${ok} item${ok === 1 ? '' : 's'}${fail > 0 ? ` · ${fail} failed` : ''}`, fail > 0 ? 'err' : 'ok');
        const failedIds = new Set(res.results.filter(r => r.status !== 'OK').map(r => r.listingId));
        setCart(c => c.filter(x => failedIds.has(x.id)));
        setCartConfirmOpen(false);
        await loadWallet();
        load();
        // Every successful cart row opens a trade — route straight to
        // the Trades tab so the user sees the escrow state machine
        // instead of landing on the Personal tab and having to switch.
        if (failedIds.size === 0) navigate('/profile?tab=trades');
      } else {
        showToast('Checkout failed', 'err');
      }
    } finally { setCartBusy(false); }
  };

  // watchlist (localStorage)
  const [watchlist, setWatchlist]       = useState(() => {
    try { return JSON.parse(localStorage.getItem('sb_watchlist') || '[]'); } catch { return []; }
  });
  useEffect(() => { localStorage.setItem('sb_watchlist', JSON.stringify(watchlist)); }, [watchlist]);
  const toggleStar = (itemId) => {
    setWatchlist(w => w.includes(itemId) ? w.filter(id => id !== itemId) : [...w, itemId]);
  };
  // Navigate helper closes the user-menu dropdown in the same click and
  // pushes a real URL onto history.
  const go = (pathFn, ...args) => {
    setMenuOpen(false);
    navigate(typeof pathFn === 'function' ? pathFn(...args) : pathFn);
  };

  // auth load
  const loadMe = useCallback(async () => {
    try {
      const m = await fetchMe();
      setMe(m);
      if (m) {
        // Check admin/csr role so we can show the right menu entries.
        const [a, c] = await Promise.all([adminCheck(), csrCheck()]);
        setIsAdmin(!!a?.admin);
        setIsCsrRole(!!c?.csr);
        // Post-Steam-return email verification hand-off. If the user went
        // through the pre-signin modal, a pending email is in localStorage;
        // fire it against /api/profile/email now so they get a verification
        // link in the first session. One-shot — key is cleared after.
        try {
          const pending = localStorage.getItem('sb_pending_email');
          if (pending && !m.email) {
            const { setEmail: apiSetEmail } = await import('./api.js');
            await apiSetEmail(pending);
            localStorage.removeItem('sb_pending_email');
          }
        } catch (e) { console.warn('pending email verify hand-off failed', e); }
      } else {
        setIsAdmin(false);
        setIsCsrRole(false);
      }
    } finally {
      setMeLoaded(true);
    }
  }, []);
  useEffect(() => { loadMe(); }, [loadMe]);

  // Session heartbeat — checks /api/auth/steam/me every 5 minutes.
  // If the backend session has expired (45-min timeout), clear the
  // frontend auth state so the user sees "Sign in" instead of ghost
  // 401 errors on every action. Shows a toast on expiry.
  useEffect(() => {
    if (!meLoaded) return;
    const interval = setInterval(async () => {
      if (!me) return;
      const fresh = await fetchMe();
      if (!fresh) {
        setMe(null);
        setIsAdmin(false);
        setIsCsrRole(false);
        // Show a non-blocking notification instead of silent 401s
        try {
          const ev = new CustomEvent('sbx-toast', { detail: { text: 'Session expired — please sign in again', kind: 'warn' } });
          window.dispatchEvent(ev);
        } catch {}
      }
    }, 5 * 60 * 1000);
    return () => clearInterval(interval);
  }, [me, meLoaded]);

  // wallet load
  const loadWallet = useCallback(async () => {
    try {
      const [w, tx] = await Promise.all([fetchWallet(), fetchTransactions()]);
      setWallet(w);
      setTransactions(Array.isArray(tx) ? tx : []);
    } catch (e) {
      console.error('loadWallet failed:', e);
    }
  }, []);
  useEffect(() => { loadWallet(); }, [loadWallet]);

  // Keyboard-shortcut help overlay state. `?` opens it, `Esc` closes.
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  // Keyboard shortcuts — CSFloat uses `/` to focus the market search.
  useEffect(() => {
    const onKey = (e) => {
      const tag = (e.target?.tagName || '').toLowerCase();
      // Ignore keys typed inside any input/textarea/select/contenteditable
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || e.target?.isContentEditable) return;
      if (e.key === '/') {
        e.preventDefault();
        const el = document.querySelector('.search-input');
        if (el) { el.focus(); el.select(); }
      } else if (e.key === '?') {
        e.preventDefault();
        setShortcutsOpen(v => !v);
      } else if (e.key === 'g') {
        // Gmail-style two-key prefix — arm a timer and wait for the next key
        const timer = setTimeout(() => { document.removeEventListener('keydown', onTarget); }, 1200);
        const onTarget = (ev) => {
          const t2 = (ev.target?.tagName || '').toLowerCase();
          if (t2 === 'input' || t2 === 'textarea' || t2 === 'select') return;
          clearTimeout(timer);
          document.removeEventListener('keydown', onTarget);
          const map = {
            m: paths.market(), d: paths.database(), p: paths.profile(),
            w: paths.wallet(),  c: paths.cart(),     l: paths.loadouts(),
            s: paths.sell(),    f: paths.watchlist(),h: paths.help(),
            o: paths.offers(),  b: paths.buyorders(), n: paths.notifications(),
            a: paths.admin(),   r: paths.csr()
          };
          if (map[ev.key]) { ev.preventDefault(); navigate(map[ev.key]); }
        };
        document.addEventListener('keydown', onTarget);
      } else if (e.key === 'Escape') {
        if (shortcutsOpen)        setShortcutsOpen(false);
        else if (selected)        setSelected(null);
        else if (routeName !== 'market') navigate(paths.market());
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [routeName, selected, shortcutsOpen]);

  // logout
  const doLogout = async () => {
    await logoutSteam();
    setMe(null);
    setMenuOpen(false);
    loadWallet();
  };

  // Handle Stripe / Steam redirect
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const state = params.get('deposit');
    const sid   = params.get('session_id');
    const login = params.get('login');
    let dirty = false;
    if (state === 'success' && sid) { confirmDeposit(sid).then(() => loadWallet()); dirty = true; }
    else if (state === 'cancel')     { dirty = true; }
    if (login === 'success')         { loadMe().then(() => loadWallet()); dirty = true; }
    else if (login === 'failed')     { alert('Steam sign-in failed. Please try again.'); dirty = true; }
    if (dirty) window.history.replaceState({}, '', window.location.pathname);
  }, [loadWallet, loadMe]);

  const CATEGORIES = ['All', 'Hats', 'Jackets', 'Shirts', 'Pants', 'Gloves', 'Boots', 'Accessories'];
  const RARITIES   = ['All', 'Limited', 'Off-Market', 'Standard'];

  // listings load. `silent` skips the loading spinner for background
  // polling so the grid doesn't blink on each refresh. Polling also
  // compares old vs new counts to fire a subtle "live sale" toast when
  // the feed shortens.
  // Mirror market-filter state into the URL query string so the current
  // view is shareable. Only active on `routeName === 'market'` so we don't
  // write query params onto item detail pages or the watchlist. replaceState
  // keeps the history stack clean — each filter change doesn't become a
  // new entry the user has to Back through.
  useEffect(() => {
    if (routeName !== 'market') return;
    const qs = new URLSearchParams();
    if (search)                    qs.set('q', search);
    if (sort && sort !== 'price_desc') qs.set('sort', sort);
    if (category && category !== 'All') qs.set('category', category);
    if (rarity && rarity !== 'All')     qs.set('rarity', rarity);
    if (minPrice)                  qs.set('min', minPrice);
    if (maxPrice)                  qs.set('max', maxPrice);
    const q = qs.toString();
    const nextSearch = q ? '?' + q : '';
    if (window.location.search !== nextSearch) {
      window.history.replaceState({}, '', window.location.pathname + nextSearch);
    }
  }, [routeName, search, sort, category, rarity, minPrice, maxPrice]);

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      // 'discount' is a pure client-side sort — backend doesn't know
      // about it. Fetch with a stable newest-first order and re-sort
      // by discount percentage below. If we asked the backend for
      // 'discount' it would 400 (unknown sort value).
      const backendSort = sort === 'discount' ? 'newest' : sort;
      let data = await fetchListings({
        sort: backendSort,
        category: category !== 'All' ? category : null,
        rarity:   rarity !== 'All'   ? rarity   : null,
        minPrice: minPrice || null,
        maxPrice: maxPrice || null,
        search:   search   || null
      });
      if (sort === 'discount') {
        // Pct discount = (steamPrice - price) / steamPrice, clamped to
        // zero for listings at or above Steam market. Items without a
        // Steam reference sink to the bottom.
        data = [...data].sort((a, b) => {
          const ap = parseFloat(a.price) || 0, as = parseFloat(a.item?.steamPrice) || 0;
          const bp = parseFloat(b.price) || 0, bs = parseFloat(b.item?.steamPrice) || 0;
          const ad = (as > 0 && ap > 0 && ap < as) ? (1 - ap / as) : -1;
          const bd = (bs > 0 && bp > 0 && bp < bs) ? (1 - bp / bs) : -1;
          return bd - ad;
        });
      }
      if (silent) {
        setListings(prev => {
          const prevIds = new Set(prev.map(l => l.id));
          const nextIds = new Set(data.map(l => l.id));
          const soldCount = [...prevIds].filter(id => !nextIds.has(id)).length;
          // Gated by the Settings > "Sale notifications" toggle. Default
          // is ON (sb_notifs absent or not 'false'); a user who muted
          // the toggle sees the grid update silently. Without this
          // check the toggle was a dead switch.
          const saleToastsOn = localStorage.getItem('sb_notifs') !== 'false';
          if (soldCount > 0 && saleToastsOn) {
            setToast({ text: `${soldCount} listing${soldCount === 1 ? '' : 's'} just sold`, kind: 'ok' });
            setTimeout(() => setToast(null), 3500);
          }
          return data;
        });
      } else {
        setListings(data);
      }
    } catch (e) { console.error(e); }
    finally { if (!silent) setLoading(false); }
  }, [sort, category, rarity, minPrice, maxPrice, search]);
  useEffect(() => { load(); }, [load]);

  // Soft poll the marketplace grid every 30s while the user is on a
  // browse route, so sold items disappear and price drops appear without
  // a manual refresh. Pauses on feature pages to save bandwidth.
  useEffect(() => {
    if (routeName !== 'market' && routeName !== 'item') return;
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') load(true);
    }, 30_000);
    return () => clearInterval(id);
  }, [routeName, load]);

  // open item detail. We push `/item/{id}` onto the URL so the detail view
  // is shareable and back/forward navigation works. The actual fetch happens
  // in the effect below that reacts to `route.name === 'item'`.
  const openModal = (listing) => {
    navigate(paths.item(listing.item.id));
  };

  // When the URL is /item/{id}, fetch that item's listings + history and
  // surface the ItemModal. Closing the modal navigates back to the market.
  useEffect(() => {
    if (routeName !== 'item' || !route.params?.id) return;
    let alive = true;
    setModalLoading(true);
    (async () => {
      try {
        const [itemListings, history] = await Promise.all([
          fetchListingsForItem(route.params.id),
          fetchHistory(route.params.id)
        ]);
        if (!alive) return;
        // Resolve the actual item object. Three-layer fallback:
        //   1. first listing we just fetched (most common — item has
        //      active listings),
        //   2. the already-loaded marketplace listings array (cache),
        //   3. a direct /api/items/{id} probe so an item with zero
        //      active listings still opens the modal (was a blank
        //      screen before — /item/{id} for an unlisted-but-real
        //      item rendered nothing).
        let item = itemListings[0]?.item ||
                   listings.find(l => String(l.item?.id) === String(route.params.id))?.item;
        if (!item) {
          try { item = await fetchItem(route.params.id); }
          catch (_) { item = null; }
          if (!alive) return;
        }
        if (item) {
          setSelected({ item, listings: itemListings, history });
          // Refine the route-driven title with the real item name — e.g.
          // "Black Modern Watch · SkinBox". Preserves the unread prefix.
          const currentPrefix = (document.title.match(/^(\(\d+\)\s+)/) || [, ''])[1];
          document.title = currentPrefix + (item.name || 'Item') + ' · SkinBox';
          // Track recently viewed for the homepage rail — keep the last 12,
          // newest first, deduped by item id. Pure localStorage, no backend.
          try {
            const prev = JSON.parse(localStorage.getItem('sb_recently_viewed') || '[]');
            const minimal = { id: item.id, name: item.name, category: item.category,
              rarity: item.rarity, imageUrl: item.imageUrl, iconEmoji: item.iconEmoji,
              accentColor: item.accentColor, lowestPrice: item.lowestPrice,
              steamPrice: item.steamPrice, viewedAt: Date.now() };
            const deduped = [minimal, ...prev.filter(x => x.id !== item.id)].slice(0, 12);
            localStorage.setItem('sb_recently_viewed', JSON.stringify(deduped));
          } catch (_) {}
        }
      } catch (e) { console.error(e); }
      finally { if (alive) setModalLoading(false); }
    })();
    return () => { alive = false; };
  }, [routeName, route.params?.id]);
  // If the URL leaves /item/:id, clear the selection so the modal disappears.
  useEffect(() => { if (routeName !== 'item') setSelected(null); }, [routeName]);

  // toast
  const [toast, setToast] = useState(null);
  const showToast = (text, kind = 'ok') => {
    setToast({ text, kind });
    setTimeout(() => setToast(null), 4500);
  };

  // buy flow — errors now come as {code, message} from GlobalExceptionHandler
  const handleBuy = async (listingId) => {
    try {
      const res = await buyListing(listingId);
      if (res && (res.code || res.error)) {
        const msg = res.message || res.error;
        showToast(msg, 'err');
        if (res.code === 'INSUFFICIENT_BALANCE' || /insufficient/i.test(msg || '')) {
          setSelected(null);
          setWalletInitialTab('deposit');
          setWalletOpen(true);
        }
        return;
      }
      setSelected(null);
      // The purchase creates an escrow trade; the item only lands in
      // inventory after the seller sends + buyer confirms. Old toast
      // said "added to your inventory" which was misleading during the
      // pending window. Nudge them toward the trades tab so they can
      // watch the state machine instead of hunting for the item.
      showToast('Purchase complete — trade opened, see Profile › Trades', 'ok');
      await loadWallet();
      load();
    } catch (e) {
      showToast('Purchase failed: ' + (e.message || 'unknown'), 'err');
    }
  };

  const handleMakeOffer = async (listingId, amount) => {
    if (!listingId || !amount) return { error: 'Missing data' };
    const res = await makeOffer(listingId, amount);
    if (!res.code && !res.error) showToast('Offer sent', 'ok');
    return res;
  };

  const clearFilters = () => {
    setCategory('All'); setRarity('All');
    setMinPrice(''); setMaxPrice(''); setSearch(''); setSearchInput('');
  };

  // derived data
  const catCounts = useMemo(() => {
    const counts = {};
    listings.forEach(l => { if (l?.item?.category) counts[l.item.category] = (counts[l.item.category] || 0) + 1; });
    return counts;
  }, [listings]);

  const trending = useMemo(() => {
    const seen = new Set();
    return [...listings]
      .filter(l => l && l.item)
      .sort((a, b) => Math.abs(b.item.trendPercent || 0) - Math.abs(a.item.trendPercent || 0))
      .filter(l => { if (seen.has(l.item.id)) return false; seen.add(l.item.id); return true; })
      .slice(0, 8);
  }, [listings]);

  // CSFloat-style hero tabs: Top Deals (biggest vs-store discount), Newest
  // (most recently listed), Unique Items (auctions / no-bid listings).
  // We intentionally do NOT show "top gainers/losers" — this is a marketplace,
  // not a stock exchange. Trend data stays as a small ▲/▼ inside cards only.
  const heroTabs = useMemo(() => {
    const uniqByItem = {};
    listings.filter(l => l?.item).forEach(l => {
      if (!uniqByItem[l.item.id] || l.price < uniqByItem[l.item.id].price) uniqByItem[l.item.id] = l;
    });
    const pool = Object.values(uniqByItem);
    const topDeals = [...pool]
      .filter(l => l.item.steamPrice && parseFloat(l.item.steamPrice) > parseFloat(l.price))
      .sort((a, b) => {
        const da = 1 - parseFloat(a.price) / parseFloat(a.item.steamPrice);
        const db = 1 - parseFloat(b.price) / parseFloat(b.item.steamPrice);
        return db - da;
      })
      .slice(0, 8);
    const newest = [...pool].sort((a, b) => (b.listedAt || 0) - (a.listedAt || 0)).slice(0, 8);
    const unique = [...pool].filter(l => l.listingType === 'AUCTION').slice(0, 8);
    return { topDeals, newest, unique };
  }, [listings]);

  const recentSales = useMemo(() =>
    [...listings].slice(0, 12).map((l, i) => ({ listing: l, time: (i * 3 + 2) + 'm ago' })),
  [listings]);

  // One-card-per-item view of the marketplace. We show the cheapest listing
  // per item with the total listing count as a "3 listings from $X" badge.
  // Matches CSFloat's grid layout and fixes the watchlist "starring one
  // card highlights every card of the same item" confusion.
  const dedupedListings = useMemo(() => {
    // Apply the listing-type filter before dedup so "Auction only" doesn't
    // pick the buy-now as the representative card for an item that has both.
    let pool = listingTypeFilter === 'ALL'
      ? listings
      : listings.filter(l => listingTypeFilter === 'AUCTION'
          ? l?.listingType === 'AUCTION'
          : l?.listingType !== 'AUCTION');
    if (dealsOnly) {
      pool = pool.filter(l => {
        const sp = parseFloat(l?.item?.steamPrice);
        const p  = parseFloat(l?.price);
        return isFinite(sp) && isFinite(p) && sp > 0 && p < sp;
      });
    }
    if (newOnly) {
      const cutoff = Date.now() - 24 * 3600 * 1000;
      pool = pool.filter(l => (l?.listedAt || 0) >= cutoff);
    }
    const byItem = {};
    pool.filter(l => l?.item).forEach(l => {
      const current = byItem[l.item.id];
      if (!current || parseFloat(l.price) < parseFloat(current.listing.price)) {
        byItem[l.item.id] = { listing: l, count: 1 };
      }
      if (current) current.count++;
    });
    const counts = {};
    pool.forEach(l => { if (l?.item) counts[l.item.id] = (counts[l.item.id] || 0) + 1; });
    return Object.values(byItem)
      .map(e => ({ ...e.listing, __listingCount: counts[e.listing.item.id] || 1 }));
  }, [listings, listingTypeFilter, dealsOnly, newOnly]);

  // Full-page routes vs overlay routes. CSFloat-style: most destinations
  // are real pages that replace the marketplace body; only the item detail
  // stays as a slide-in overlay on top of the grid.
  const FULL_PAGE_ROUTES = ['profile','wallet','cart','help','faq','watchlist','database','loadouts','loadout','sell','mystall','offers','buyorders','notifications','support','settings','admin','csr','notfound','stall'];
  const isFullPage = FULL_PAGE_ROUTES.includes(routeName);

  return h('div', {
    className: `site-root ${chatHidden ? 'chat-hidden' : ''} ${isFullPage ? 'full-page-mode' : ''}`
  },
    /* Chat removed — was placeholder with fake messages */

    /* Sitewide ops announcement — one row at a time, dismissible. */
    h(AnnouncementBanner, null),
    h(EmailVerifyNag, { me }),

    /* Pending-trade reminder — nudges users whose escrow has been
       waiting on them > 2h. Dismissible per-trade via localStorage. */
    h(PendingTradeReminder, { me }),

    /* NAV — full-width bar, aligned inner row clamped to content-max */
    h('nav', { className: 'nav' },
      h('div', { className: 'nav-inner' },
      h('a', { className: 'nav-logo', href: '/' },
        // Loot-crate logo. The <img> falls back to the "SB" initials inside
        // the gradient square if the file isn't present yet — so this
        // renders cleanly even before the user drops the real PNG into
        // /static/img/logo.png.
        h('div', { className: 'nav-logo-icon' },
          h('img', {
            src: '/img/logo-square.png',
            alt: 'SkinBox',
            onError: (e) => { e.target.style.display = 'none'; e.target.parentElement.textContent = 'SB'; }
          })
        ),
        h('span', { className: 'nav-logo-text' }, 'SkinBox'),
        h('span', { className: 'nav-logo-badge' }, 's&box')
      ),
      h('div', { className: 'nav-links' },
        h('a', { className: `nav-link ${routeName === 'market' ? 'active' : ''}`, href: paths.market() }, 'Market'),
        h('a', { className: `nav-link ${routeName === 'database' ? 'active' : ''}`, href: paths.database() }, 'Database'),
        h('a', { className: `nav-link ${routeName === 'loadouts' || routeName === 'loadout' ? 'active' : ''}`, href: paths.loadouts() }, 'Loadout Lab'),
        h('a', { className: `nav-link ${routeName === 'watchlist' ? 'active' : ''}`, href: paths.watchlist() },
          'Watchlist',
          watchlist.length > 0 && h('span', { className: 'nav-link-badge' }, watchlist.length)
        ),
        h('a', { className: `nav-link ${routeName === 'help' || routeName === 'faq' ? 'active' : ''}`, href: paths.help() }, 'Help'),
      ),
      h('div', { className: 'nav-right' },
        // Offers inbox icon + actionable pending-incoming badge. Clicking
        // jumps to /offers. Polls every 45s while signed in — offers are
        // less real-time than notifications so a slower cadence is fine.
        me && h(NavOffersBadge, null),
        h(NotificationBell, { me }),
        h(ThemePicker, null),
        h('a', {
          className: 'nav-icon-btn',
          href: paths.cart(),
          title: 'Cart'
        },
          h(MaterialIcon, { name: 'shopping_cart', size: 18 }),
          cartCount > 0 && h('div', { className: 'nav-icon-badge' }, cartCount)
        ),
        me && wallet && (() => {
          // Low-balance indicator — quiet amber amp on the wallet button
          // when balance < $5. Pending withdrawals still surface via the
          // WalletModal pending-chip; this just flags "heads up, top up
          // soon" without being noisy. Not shown in privacy mode since
          // it'd leak the fact that balance is low.
          const bal = parseFloat(wallet.balance) || 0;
          const low = !privacy && bal < 5 && bal >= 0;
          return h('button', {
            className: `wallet-btn${low ? ' low-balance' : ''}`,
            onClick: (e) => {
              if (e.ctrlKey || e.metaKey) { e.preventDefault(); setPrivacy(p => !p); return; }
              navigate(paths.wallet());
            },
            title: low
              ? `Balance is under $5 — top up to keep checking out.  ·  Ctrl-click to toggle privacy`
              : 'Open wallet · Ctrl-click to toggle privacy'
          },
            h('div', { className: 'wallet-btn-icon' }, low ? '!' : '$'),
            h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', lineHeight: 1.1 } },
              h('span', { className: 'wallet-btn-label' }, low ? 'Top up' : 'Balance'),
              h('span', { className: 'wallet-btn-amt' }, privacy ? '$•••••' : fmt(wallet.balance))
            )
          );
        })(),
        me
          ? h('div', { className: 'user-chip', onClick: () => setMenuOpen(o => !o) },
              h('div', { className: 'user-chip-avatar' },
                me.avatarUrl
                  ? h('img', { src: me.avatarUrl, alt: me.displayName })
                  : (me.displayName || 'U').substring(0, 2).toUpperCase()
              ),
              h('span', { className: 'user-chip-name' }, me.displayName || 'Player'),
              menuOpen && h('div', {
                className: 'user-menu-backdrop',
                onClick: (e) => { e.stopPropagation(); setMenuOpen(false); }
              }),
              menuOpen && h('div', { className: 'user-menu', onClick: e => e.stopPropagation() },
                h('a', { className: 'user-menu-item', href: paths.profile(),       onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'person', size: 18 }), 'Profile'),
                h('div', { className: 'user-menu-divider' }),
                h('button', { className: 'user-menu-item', onClick: () => { setWalletInitialTab('deposit');  navigate(paths.wallet()); setMenuOpen(false); } }, h(MaterialIcon, { name: 'south', size: 18 }), 'Deposit'),
                h('button', { className: 'user-menu-item', onClick: () => { setWalletInitialTab('withdraw'); navigate(paths.wallet()); setMenuOpen(false); } }, h(MaterialIcon, { name: 'north', size: 18 }), 'Withdraw'),
                h('a', { className: 'user-menu-item', href: '/profile?tab=trades', onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'swap_horiz', size: 18 }), 'Trades'),
                h('div', { className: 'user-menu-divider' }),
                h('a', { className: 'user-menu-item', href: paths.sell(),          onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'sell', size: 18 }), 'Sell Items'),
                h('a', { className: 'user-menu-item', href: paths.mystall(),       onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'storefront', size: 18 }), 'My Stall'),
                h('a', { className: 'user-menu-item', href: paths.offers(),        onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'forum', size: 18 }), 'Offers'),
                h('a', { className: 'user-menu-item', href: paths.buyorders(),     onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'bolt', size: 18 }), 'Buy Orders'),
                h('a', { className: 'user-menu-item', href: paths.notifications(), onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'notifications', size: 18 }), 'Notifications'),
                h('a', { className: 'user-menu-item', href: paths.loadouts(),      onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'checkroom', size: 18 }), 'Loadout Lab'),
                h('a', { className: 'user-menu-item', href: paths.watchlist(),     onClick: () => setMenuOpen(false) },
                  h(MaterialIcon, { name: 'favorite_border', size: 18 }),
                  'Watchlist',
                  watchlist.length > 0 && h('span', { className: 'filter-count', style: { marginLeft: 'auto' } }, watchlist.length)
                ),
                h('div', { className: 'user-menu-divider' }),
                h('a', { className: 'user-menu-item', href: paths.database(),  onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'database', size: 18 }), 'Database'),
                h('a', { className: 'user-menu-item', href: paths.help(),      onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'help', size: 18 }), 'Help Center'),
                h('a', { className: 'user-menu-item', href: paths.support(),   onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'support_agent', size: 18 }), 'Support'),
                h('a', { className: 'user-menu-item', href: paths.settings(),  onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'settings', size: 18 }), 'Settings'),
                // Staff shortcuts — only visible to CSR / ADMIN roles. Admin
                // role is ONLY granted via the server-side bootstrap list
                // (env var ADMIN_BOOTSTRAP_STEAM_IDS) or by an existing admin
                // through the Users tab. No self-service claim from the UI.
                (isCsr || isAdmin) && h('div', { className: 'user-menu-divider' }),
                isCsr && h('a', {
                  className: 'user-menu-item staff',
                  href: paths.csr(), onClick: () => setMenuOpen(false)
                }, h(MaterialIcon, { name: 'headset_mic', size: 18 }), 'Customer Service'),
                isAdmin && h('a', {
                  className: 'user-menu-item staff admin',
                  href: paths.admin(), onClick: () => setMenuOpen(false)
                }, h(MaterialIcon, { name: 'admin_panel_settings', size: 18 }), 'Admin Panel'),
                h('div', { className: 'user-menu-divider' }),
                h('button', { className: 'user-menu-item danger', onClick: doLogout }, h(MaterialIcon, { name: 'logout', size: 18 }), 'Logout')
              )
            )
          : h('button', {
              className: 'steam-btn',
              onClick: () => { window.location.href = '/api/auth/steam/login'; },
              type: 'button'
            },
              h('div', { className: 'steam-btn-icon' },
                /* Steam logomark — two concentric circles with a smaller
                   offset circle cutout, the canonical valve "bubble"
                   shape. Fill is Steam's link-blue #66c0f4 against a
                   near-black ball so it reads at 20px. */
                h('svg', {
                  viewBox: '0 0 24 24',
                  width: 20,
                  height: 20,
                  fill: 'currentColor',
                  'aria-hidden': 'true'
                },
                  h('path', {
                    d: 'M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z'
                  })
                )
              ),
              'Sign in through Steam'
            )
      )
      )
    ),

    /* HERO — single-row banner. Signed-in users just see the welcome +
       action buttons; signed-out users also get the marketing tagline.
       We only render the hero AFTER the first /api/me response has
       settled (meLoaded === true). Before that we render a neutral
       placeholder with the same vertical footprint, so the user never
       sees the wrong hero flash in and get replaced a moment later. */
    !meLoaded
      ? h('section', { className: 'hero', style: { visibility: 'hidden' } },
          h('div', { className: 'hero-inner' },
            h('div', { className: 'hero-text' },
              h('h1', null, ' '),
              h('p', null, ' ')
            )
          )
        )
      : h('section', { className: 'hero' },
          h('div', { className: 'hero-inner' },
            h('div', { className: 'hero-text' },
              me
                ? h('h1', null, 'Welcome back, ',
                    h('span', { className: 'accent-word' }, me.displayName || 'Player'),
                    '.')
                : h('h1', null, 'The ', h('span', { className: 'accent-word' }, 's&box'), ' Skin Marketplace'),
              h('p', null,
                me
                  ? 'Pick up where you left off — browse the marketplace, check your stall, or drop something new on sale.'
                  : 'Trade Workshop items, hats, and clothing with full price history, edition sizes, and trusted sellers.'
              )
            ),
            h('div', { className: 'hero-actions' },
              h('button', {
                className: 'btn btn-accent',
                onClick: () => {
                  const el = document.querySelector('.layout');
                  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
              }, 'Browse Market'),
              h('a', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border-light)' },
                href: paths.help()
              }, 'How it Works')
            )
          )
        ),

    /* CATEGORY TABS — thin underline row */
    h('section', { className: 'cat-tiles' },
      [
        { name: 'All',         emoji: '' },
        { name: 'Hats',        emoji: '🎩' },
        { name: 'Jackets',     emoji: '🧥' },
        { name: 'Shirts',      emoji: '👕' },
        { name: 'Pants',       emoji: '👖' },
        { name: 'Gloves',      emoji: '🧤' },
        { name: 'Boots',       emoji: '🥾' },
        { name: 'Accessories', emoji: '💍' },
      ].map(c => h('div', {
        key: c.name,
        className: `cat-tile ${category === c.name ? 'active' : ''}`,
        onClick: () => {
          setCategory(c.name);
          const el = document.querySelector('.layout');
          if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      },
        c.emoji && h('span', { className: 'cat-tile-emoji' }, c.emoji),
        h('div', { className: 'cat-tile-name' }, c.name),
        c.name !== 'All' && h('div', { className: 'cat-tile-count' }, catCounts[c.name] || 0)
      ))
    ),

    /* MARKET STATS STRIP — public trust signal. Silent for empty-
       marketplace states, so fresh installs don't see "$0 traded". */
    routeName === 'market' && h(MarketStatsStrip, null),

    /* HERO TABS — only render when there's actually something to show.
       Avoids leaving a ~300px empty panel on a fresh / zero-listing state. */
    ((heroTabs.topDeals || []).length > 0 || (heroTabs.newest || []).length > 0 || (heroTabs.unique || []).length > 0) && (
      h('section', { className: 'hero-tabs' },
        h('div', { className: 'hero-tabs-bar' },
          h('button', { className: `hero-tab ${heroTab === 'topDeals' ? 'active' : ''}`, onClick: () => setHeroTab('topDeals') }, '🔥 Top Deals'),
          h('button', { className: `hero-tab ${heroTab === 'newest' ? 'active' : ''}`,   onClick: () => setHeroTab('newest') },   '✨ Newest Items'),
          h('button', { className: `hero-tab ${heroTab === 'unique' ? 'active' : ''}`,   onClick: () => setHeroTab('unique') },   '🏷 Unique Items'),
          h('div', { style: { flex: 1 } }),
          h('button', { className: 'hero-tab-cta', onClick: () => { const el = document.querySelector('.layout'); if (el) el.scrollIntoView({ behavior: 'smooth' }); } }, 'Visit Marketplace →')
        ),
        h('div', { className: 'hero-tab-grid' },
          (heroTabs[heroTab] || []).map(l => h(GridCard, {
            key: l.id,
            listing: l,
            onClick: () => openModal(l),
            starred: watchlist.includes(l.item.id),
            onToggleStar: toggleStar
          }))
        )
      )
    ),

    /* MAIN LAYOUT */
    h('div', { className: 'layout' },
      h('aside', { className: 'sidebar' },
        h('div', { className: 'filter-section' },
          h('div', { className: 'filter-title' }, 'Category'),
          CATEGORIES.map(c =>
            h('div', {
              key: c,
              className: `filter-option ${category === c ? 'selected' : ''}`,
              onClick: () => setCategory(c)
            },
              c,
              c !== 'All' && h('span', { className: 'filter-count' }, catCounts[c] || 0)
            )
          )
        ),
        h('div', { className: 'filter-divider' }),
        h('div', { className: 'filter-section' },
          h('div', { className: 'filter-title' }, 'Availability'),
          RARITIES.map(r =>
            h('div', {
              key: r,
              className: `filter-option ${rarity === r ? 'selected' : ''}`,
              onClick: () => setRarity(r)
            },
              r !== 'All' && h('span', {
                className: 'filter-dot',
                style: {
                  background: r === 'Limited' ? 'var(--limited-color)' : r === 'Off-Market' ? 'var(--offmarket-color)' : 'var(--standard-color)',
                  color:      r === 'Limited' ? 'var(--limited-color)' : r === 'Off-Market' ? 'var(--offmarket-color)' : 'var(--standard-color)'
                }
              }),
              r
            )
          )
        ),
        h('div', { className: 'filter-divider' }),
        h('div', { className: 'filter-section' },
          h('div', { className: 'filter-title' }, 'Price Range'),
          h('div', { className: 'price-inputs' },
            h('input', { className: 'price-input', placeholder: '$ Min', value: minPrice, onChange: e => setMinPrice(e.target.value) }),
            h('input', { className: 'price-input', placeholder: '$ Max', value: maxPrice, onChange: e => setMaxPrice(e.target.value) })
          )
        ),
        h('button', { className: 'btn-clear', onClick: clearFilters }, 'Clear Filters')
      ),

      h('main', { className: 'main' },
        h('div', { className: 'toolbar' },
          h('div', { className: 'search-wrap' },
            h('span', { className: 'search-icon' }, '⌕'),
            h('input', {
              className: 'search-input',
              placeholder: 'Search s&box skins…  (press / to focus)',
              value: searchInput,
              onChange: e => { setSearchInput(e.target.value); setSuggestOpen(true); setSuggestIdx(-1); },
              onFocus: () => { setSuggestOpen(true); },
              onKeyDown: (e) => {
                if (!suggestOpen) return;
                if (e.key === 'ArrowDown' && suggest.length > 0) {
                  e.preventDefault();
                  setSuggestIdx(i => (i + 1) % suggest.length);
                }
                else if (e.key === 'ArrowUp' && suggest.length > 0) {
                  e.preventDefault();
                  setSuggestIdx(i => (i - 1 + suggest.length) % suggest.length);
                }
                else if (e.key === 'Enter') {
                  if (suggestIdx >= 0 && suggest[suggestIdx]) {
                    e.preventDefault();
                    const item = suggest[suggestIdx];
                    pushRecentSearch(searchInput);
                    setSuggestOpen(false); setSuggestIdx(-1);
                    navigate(paths.item(item.id));
                  } else if (searchInput && searchInput.trim().length >= 2) {
                    pushRecentSearch(searchInput);
                    setSuggestOpen(false);
                  }
                }
                else if (e.key === 'Escape') { setSuggestOpen(false); setSuggestIdx(-1); }
              },
              'aria-label': 'Search listings',
              'aria-autocomplete': 'list',
              'aria-expanded': suggestOpen && suggest.length > 0
            }),
            searchInput && h('button', {
              className: 'search-clear',
              onClick: () => { setSearchInput(''); setSearch(''); setSuggestOpen(false); },
              title: 'Clear search',
              'aria-label': 'Clear search'
            }, '✕'),
            // Recent-searches dropdown — shown when the input is empty
            // and focused. Clicking a row fills the search + opens the
            // item autocomplete.
            suggestOpen && (!searchInput || searchInput.trim().length < 2) && recentSearches.length > 0 && h('div', {
              className: 'search-suggest',
              role: 'listbox'
            },
              h('div', { className: 'search-suggest-heading' }, 'Recent searches'),
              recentSearches.map((q, i) => h('div', {
                key: 'rs-' + i,
                className: 'search-suggest-row recent',
                role: 'option',
                onClick: () => {
                  setSearchInput(q);
                  setSearch(q);
                  setSuggestOpen(true);
                  setSuggestIdx(-1);
                }
              },
                h('span', { className: 'search-suggest-recent-icon' }, '⟲'),
                h('span', { style: { flex: 1, fontSize: 13 } }, q),
                h('button', {
                  className: 'search-suggest-forget',
                  onClick: (e) => {
                    e.stopPropagation();
                    const next = recentSearches.filter(x => x !== q);
                    setRecentSearches(next);
                    try { localStorage.setItem('sb_recent_searches', JSON.stringify(next)); } catch (_) {}
                  },
                  title: 'Forget this search',
                  'aria-label': 'Forget'
                }, '✕')
              ))
            ),
            suggestOpen && suggest.length > 0 && h('div', {
              className: 'search-suggest',
              role: 'listbox'
            },
              suggest.map((item, i) => h('div', {
                key: item.id,
                className: `search-suggest-row ${i === suggestIdx ? 'active' : ''}`,
                role: 'option',
                'aria-selected': i === suggestIdx,
                onMouseEnter: () => setSuggestIdx(i),
                onClick: (e) => {
                  e.preventDefault();
                  setSuggestOpen(false); setSuggestIdx(-1);
                  navigate(paths.item(item.id));
                }
              },
                h('div', { className: 'search-suggest-thumb' },
                  item.imageUrl
                    ? h('img', { src: item.imageUrl, alt: '', loading: 'lazy' })
                    : h('span', { style: { color: item.accentColor || '#60a5fa' } }, item.iconEmoji || '📦')
                ),
                h('div', { className: 'search-suggest-body' },
                  h('div', { className: 'search-suggest-name' }, item.name),
                  h('div', { className: 'search-suggest-meta' },
                    item.category || 'Item',
                    item.rarity && item.rarity !== 'Standard' ? ` · ${item.rarity}` : ''
                  )
                ),
                h('div', { className: 'search-suggest-price' },
                  item.lowestPrice != null ? fmt(item.lowestPrice) : '—'
                )
              ))
            )
          ),
          h('select', {
            className: 'sort-select',
            value: sort,
            onChange: e => setSort(e.target.value),
            'aria-label': 'Sort listings'
          },
            h('option', { value: 'price_desc' }, 'Price: High → Low'),
            h('option', { value: 'price_asc' },  'Price: Low → High'),
            h('option', { value: 'newest' },     'Newest First'),
            h('option', { value: 'rarity' },     'Lowest Supply'),
            h('option', { value: 'discount' },   'Biggest Discount'),
          ),
          // Saved searches — dropdown of named filter presets. "Save current"
          // prompts for a name and stashes the full filter state. Picking
          // an entry re-applies every field in one click. Deliberately in
          // the toolbar next to sort so the "save this view" concept is
          // spatially close to the sort controls the user just touched.
          h('div', { style: { display: 'flex', gap: 4, alignItems: 'center' } },
            savedSearches.length > 0 && h('select', {
              className: 'sort-select',
              style: { maxWidth: 180 },
              value: '',
              onChange: (e) => {
                const v = e.target.value;
                if (!v) return;
                if (v.startsWith('del:')) {
                  deleteSavedSearch(parseInt(v.slice(4), 10));
                } else {
                  const s = savedSearches.find(x => x.id === parseInt(v, 10));
                  if (s) applySavedSearch(s);
                }
                e.target.value = '';
              },
              'aria-label': 'Apply a saved search'
            },
              h('option', { value: '' }, `★ Saved (${savedSearches.length})`),
              savedSearches.map(s => h('option', { key: s.id, value: s.id }, s.name)),
              savedSearches.length > 0 && h('option', { disabled: true, value: '' }, '─── delete ───'),
              savedSearches.map(s => h('option', { key: 'del-' + s.id, value: 'del:' + s.id }, '✕  ' + s.name))
            ),
            h('button', {
              className: 'btn btn-ghost',
              style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
              onClick: saveCurrentSearch,
              title: 'Save the current filter combination as a named preset'
            }, '★ Save search')
          ),
          // Listing-type toggle — three buttons, single active. Purely
          // client-side; server already returns both types and we filter
          // before dedup. Defaults to ALL so anon users see the full grid.
          h('div', { className: 'type-toggle', role: 'group', 'aria-label': 'Listing type' },
            [{ id: 'ALL', label: 'All' }, { id: 'BUY_NOW', label: 'Buy Now' }, { id: 'AUCTION', label: 'Auction' }]
              .map(opt => h('button', {
                key: opt.id,
                className: `type-toggle-btn ${listingTypeFilter === opt.id ? 'active' : ''}`,
                onClick: () => setListingTypeFilter(opt.id),
                'aria-pressed': listingTypeFilter === opt.id
              }, opt.label))
          ),
          // Deal-hunter chip. Separated from the type toggle because
          // it's orthogonal — you can stack "Auctions only" + "Deals only"
          // to see undervalued auctions. Purely client-side filter.
          h('button', {
            className: `deals-chip ${dealsOnly ? 'active' : ''}`,
            onClick: () => setDealsOnly(d => !d),
            title: 'Only show listings priced below the Steam Market price',
            'aria-pressed': dealsOnly
          }, '% Deals'),
          // New-in-24h chip. Pairs with Deals; stackable.
          h('button', {
            className: `deals-chip new-chip ${newOnly ? 'active' : ''}`,
            onClick: () => setNewOnly(n => !n),
            title: 'Only show listings posted in the last 24 hours',
            'aria-pressed': newOnly
          }, '★ New'),
          // Quick-filter chips — CSFloat-style one-click filter presets.
          // Each chip is an (isActive, apply, clear) pair so clicking twice
          // toggles the preset on/off. Chips don't stack with each other
          // because price-range presets are mutually exclusive — the most
          // recent click wins.
          (() => {
            const QF = [
              { id: 'under5',  label: 'Under $5',  test: () => maxPrice === '5' && !minPrice,
                apply: () => { setMinPrice(''); setMaxPrice('5'); } },
              { id: 'under20', label: 'Under $20', test: () => maxPrice === '20' && !minPrice,
                apply: () => { setMinPrice(''); setMaxPrice('20'); } },
              { id: 'under50', label: 'Under $50', test: () => maxPrice === '50' && !minPrice,
                apply: () => { setMinPrice(''); setMaxPrice('50'); } },
              { id: 'premium', label: 'Premium ($100+)', test: () => minPrice === '100' && !maxPrice,
                apply: () => { setMinPrice('100'); setMaxPrice(''); } },
              { id: 'limited', label: 'Limited only', test: () => rarity === 'Limited',
                apply: () => setRarity('Limited') }
            ];
            return QF.map(qf => {
              const active = qf.test();
              return h('button', {
                key: qf.id,
                className: `deals-chip ${active ? 'active' : ''}`,
                style: { fontSize: 11, padding: '6px 10px' },
                onClick: () => {
                  if (active) {
                    // Toggle off — reset whichever bound(s) the preset set.
                    if (qf.id === 'limited') setRarity('All');
                    else { setMinPrice(''); setMaxPrice(''); }
                  } else qf.apply();
                },
                'aria-pressed': active,
                title: 'Quick filter · ' + qf.label
              }, qf.label);
            });
          })(),
          h('div', { className: 'view-btns', role: 'group', 'aria-label': 'View mode' },
            h('button', { className: `view-btn ${view === 'grid' ? 'active' : ''}`,  onClick: () => setView('grid'), 'aria-label': 'Grid view',  'aria-pressed': view === 'grid' },  '⊞'),
            h('button', { className: `view-btn ${view === 'table' ? 'active' : ''}`, onClick: () => setView('table'), 'aria-label': 'Table view', 'aria-pressed': view === 'table' }, '☰')
          )
        ),
        // Active filter chips — visible whenever a non-default filter is set.
        (search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice) &&
          h('div', { className: 'active-filters' },
            search && h('button', { className: 'filter-chip', onClick: () => setSearch('') },
              'search: ', h('strong', null, '"' + search + '"'), h('span', null, ' ✕')),
            category !== 'All' && h('button', { className: 'filter-chip', onClick: () => setCategory('All') },
              h('strong', null, category), h('span', null, ' ✕')),
            rarity !== 'All' && h('button', { className: 'filter-chip', onClick: () => setRarity('All') },
              h('strong', null, rarity), h('span', null, ' ✕')),
            minPrice && h('button', { className: 'filter-chip', onClick: () => setMinPrice('') },
              '≥ $', h('strong', null, minPrice), h('span', null, ' ✕')),
            maxPrice && h('button', { className: 'filter-chip', onClick: () => setMaxPrice('') },
              '≤ $', h('strong', null, maxPrice), h('span', null, ' ✕')),
            h('button', { className: 'filter-chip clear-all', onClick: clearFilters },
              h('strong', null, 'Clear all'))
          ),
        h('div', { className: 'results-meta' },
          h('strong', null, listings.length), ' listings found',
          category !== 'All' && h('span', null, ' in ', h('strong', null, category)),
          search && h('span', null, ' matching ', h('strong', null, `"${search}"`))
        ),
        loading
          ? h('div', { className: 'listing-grid' },
              // Skeleton grid — reserves layout while listings fetch. 12
              // phantom cards match the average page size so the real grid
              // doesn't snap when it arrives.
              Array.from({ length: 12 }).map((_, i) => h('div', { key: 'sk-' + i, className: 'skeleton-card' },
                h('div', { className: 'skeleton-thumb' }),
                h('div', { className: 'skeleton-body' },
                  h('div', { className: 'skeleton-line med' }),
                  h('div', { className: 'skeleton-line short' })
                )
              ))
            )
          : listings.length === 0
            ? h('div', { className: 'empty-state' },
                h('div', { className: 'empty-state-icon' },
                  h(MaterialIcon, { name: 'inventory_2', size: 42 })
                ),
                h('div', { className: 'empty-state-title' },
                  (search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice)
                    ? 'No listings match your filters'
                    : 'Marketplace is empty'
                ),
                h('div', { className: 'empty-state-sub' },
                  (search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice)
                    ? 'Try a broader search, clear the filters, or list one of your own items.'
                    : 'Be the first to list an item — or spin up simulated listings from the admin panel for QA.'
                ),
                h('div', { className: 'empty-state-actions' },
                  (search || category !== 'All' || rarity !== 'All' || minPrice || maxPrice) && h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)' },
                    onClick: clearFilters
                  }, 'Clear Filters'),
                  me && h('a', { className: 'btn btn-accent', href: paths.sell() }, 'Sell Items')
                )
              )
            : view === 'grid'
              ? h('div', { className: 'listing-grid' },
                  dedupedListings.map(l => h(GridCard, {
                    key: 'item-' + l.item.id,
                    listing: l,
                    listingCount: l.__listingCount,
                    onClick: () => openModal(l),
                    starred: watchlist.includes(l.item.id),
                    onToggleStar: toggleStar,
                    meId: me?.id,
                    // Quick-add to cart — signed-in only; backend gates
                    // checkout on currentUser regardless.
                    onAddToCart: me ? addToCart : null,
                    cartHas: (id) => cart.some(c => c.id === id)
                  }))
                )
              : h('table', { className: 'listing-table' },
                  h('thead', null,
                    h('tr', null,
                      h('th', null, 'Item'),
                      h('th', null, 'Availability'),
                      h('th', { className: 'center' }, 'Steam Disc.'),
                      h('th', { className: 'center' }, 'Trend'),
                      h('th', null, 'Seller'),
                      h('th', null, 'Listed'),
                      h('th', { className: 'right' }, 'Price'),
                      h('th', { className: 'center' }, 'Action'),
                    )
                  ),
                  h('tbody', null,
                    dedupedListings.map(l =>
                      h(ListingRow, { key: 'item-row-' + l.item.id, listing: l, onClick: () => openModal(l), onBuy: handleBuy })
                    )
                  )
                )
      )
    ),

    /* AUCTIONS ENDING SOON — live rail pulled from /api/listings/ending-soon.
       Only renders when there's at least one auction closing in the next
       hour. Polls every 30s so the rail stays fresh without SSE. */
    routeName === 'market' && h(AuctionsEndingSoonRail, {
      watchlist, onToggleStar: toggleStar, onOpen: openModal
    }),

    /* TOP DEALS — rail of the 12 biggest % discounts vs Steam. */
    routeName === 'market' && h(TopDealsRail, {
      watchlist, onToggleStar: toggleStar, onOpen: openModal,
      onAddToCart: me ? addToCart : null, cartHas: (id) => cart.some(c => c.id === id)
    }),

    /* FROM SELLERS YOU FOLLOW — personalised rail for signed-in users
       who already follow at least one seller. Component is silent for
       anonymous viewers and for empty follow sets, so this marker is
       safe to always mount. */
    routeName === 'market' && h(FollowingRail, {
      me, watchlist, onToggleStar: toggleStar, onOpen: openModal,
      onAddToCart: me ? addToCart : null, cartHas: (id) => cart.some(c => c.id === id)
    }),

    /* JUST LISTED — rail of the 20 freshest listings site-wide. Drops onto
       the home page between the ending-soon strip and the recently-viewed
       rail so the "what's new" surface is always one glance away. */
    routeName === 'market' && h(JustListedRail, {
      watchlist, onToggleStar: toggleStar, onOpen: openModal
    }),

    /* JUST SOLD — live sales ticker for social proof. Polls every 30s
       so new platform-wide sales appear in the rail without refresh. */
    routeName === 'market' && h(JustSoldRail, null),

    /* TOP SELLERS — social proof rail showing the highest-volume
       verified sellers. Polls every 5 minutes; hides when the aggregate
       returns nothing (brand-new platform with <5-sale sellers). */
    routeName === 'market' && h(TopSellersRail, null),

    /* RECENTLY VIEWED RAIL — horizontal scroll strip of the last 12 items
       the user clicked into. Pure localStorage, shown only on the market
       route and only when there's history to display. */
    routeName === 'market' && h(RecentlyViewedRail, { watchlist, onToggleStar: toggleStar }),

    /* RECENT SALES TICKER — below the marketplace grid */
    recentSales.length > 0 && h('section', { className: 'ticker-section' },
      h('div', { className: 'ticker' },
        h('div', { className: 'ticker-label' }, 'LIVE SALES'),
        h('div', { className: 'ticker-track' },
          [...recentSales, ...recentSales].map((s, i) => h('div', { key: i, className: 'ticker-item' },
            h('div', { className: 'ticker-thumb' }, h(ItemImage, { item: s.listing.item, variant: 'thumb' })),
            h('span', { className: 'ticker-name' }, s.listing.item.name),
            h('span', { className: 'ticker-price' }, fmt(s.listing.price)),
            h('span', { className: 'ticker-time' }, s.time)
          ))
        )
      )
    ),

    /* ROUTE-DRIVEN PAGES — each one has a real URL. Closing any of them
       navigates back to /. Some (wallet, profile) need the shared wallet
       state, others are self-contained. */
    routeName === 'wallet' && wallet && h(WalletModal, {
      wallet, transactions,
      onClose: () => { setWalletPrefillAmount(null); navigate(paths.market()); },
      onRefresh: loadWallet,
      initialTab: walletInitialTab,
      prefillAmount: walletPrefillAmount
    }),
    routeName === 'stall' && h(InfoModal, {
      title: stallData?.seller?.displayName
        ? `${stallData.seller.displayName}'s Stall`
        : 'Stall',
      onClose: () => navigate(paths.market())
    },
      stallData === null
        ? h('div', { className: 'spinner' })
        : stallData.__notFound
          ? h('div', { className: 'empty-inline', style: { padding: '32px 16px' } },
              h('div', { className: 'empty-icon' }, '🏚️'),
              h('div', { style: { fontSize: 16, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 6 } }, 'Stall not found'),
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
                "This seller doesn't exist or has deactivated their account."),
              h('a', { className: 'btn btn-accent', href: '/' }, 'Back to marketplace')
            )
        : h('div', null,
            h('div', { className: 'stall-hero' },
              h('div', { className: 'stall-avatar' },
                stallData.seller.avatarUrl
                  ? h('img', { src: stallData.seller.avatarUrl, alt: stallData.seller.displayName })
                  : (stallData.seller.displayName || 'U').substring(0, 2).toUpperCase()
              ),
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('div', { className: 'stall-name' },
                  stallData.seller.displayName || 'Player',
                  // Verified trust badge — 10+ completed sales AND either
                  // no reviews OR 4+ star average. Backend computes it so
                  // the threshold is uniform across every surface.
                  stallData.seller.verified && h('span', {
                    className: 'seller-verified',
                    title: `Verified seller · ${stallData.seller.soldCount}+ completed sales`
                  }, '✓ Verified')
                ),
                h('div', { className: 'stall-meta' },
                  stallData.count, ' active listings',
                  stallData.seller.soldCount > 0 && ` · ${stallData.seller.soldCount} sold`,
                  stallData.seller.followerCount > 0 && ` · ${stallData.seller.followerCount} follower${stallData.seller.followerCount === 1 ? '' : 's'}`,
                  ' · joined ',
                  stallData.seller.joinedAt ? new Date(stallData.seller.joinedAt).toLocaleDateString() : '—',
                  // Last-seen chip — green if within 24h, yellow if 7d,
                  // muted otherwise. Softer than "online now" which we
                  // don't actually track, but clear enough to tell a
                  // buyer whether this seller is likely to respond.
                  stallData.seller.lastSyncedAt && (() => {
                    const age = Date.now() - stallData.seller.lastSyncedAt;
                    let cls, label;
                    if (age < 24 * 3600_000)       { cls = 'var(--green)'; label = 'Active recently'; }
                    else if (age < 7 * 24 * 3600_000) { cls = '#fbbf24';      label = 'Active this week'; }
                    else                              { cls = 'var(--text-muted)'; label = 'Last seen ' + timeAgo(stallData.seller.lastSyncedAt); }
                    return h('span', {
                      style: { marginLeft: 10, fontSize: 11, color: cls, fontWeight: 700 },
                      title: 'Last observed on Steam ' + new Date(stallData.seller.lastSyncedAt).toLocaleString()
                    }, '· ', label);
                  })(),
                  // Typical-response chip — median seller reply time across
                  // the most recent resolved offers. Null (hidden) until the
                  // seller has answered at least 3 offers so the stat isn't
                  // noisy. Helps bargain-oriented buyers decide whether
                  // offering is worth the wait vs. hitting Buy Now.
                  stallData.seller.typicalResponseMs != null && (() => {
                    const ms = stallData.seller.typicalResponseMs;
                    let label;
                    if (ms < 3_600_000)           label = Math.max(1, Math.round(ms / 60_000)) + 'm';
                    else if (ms < 24 * 3_600_000) label = Math.max(1, Math.round(ms / 3_600_000)) + 'h';
                    else                          label = Math.max(1, Math.round(ms / (24 * 3_600_000))) + 'd';
                    return h('span', {
                      style: { marginLeft: 10, fontSize: 11, color: 'var(--accent)', fontWeight: 700 },
                      title: `Median time from offer to seller response across the last ${50} offers`
                    }, '· Typically responds in ', label);
                  })(),
                  // Response-rate chip — companion to the response-time chip.
                  // Green if ≥80%, amber if 50-79%, red otherwise. Hidden
                  // until the seller has 5+ resolvable offers (denominator
                  // noise floor). Reads "· 92% response rate" and tells a
                  // buyer whether this seller engages with offers at all.
                  stallData.seller.responseRatePct != null && (() => {
                    const pct = stallData.seller.responseRatePct;
                    const cls = pct >= 80 ? 'var(--green)' : pct >= 50 ? '#fbbf24' : 'var(--red)';
                    return h('span', {
                      style: { marginLeft: 10, fontSize: 11, color: cls, fontWeight: 700 },
                      title: 'Fraction of offers the seller has resolved (accepted / rejected / countered) vs. let auto-expire'
                    }, '· ', Math.round(pct), '% response rate');
                  })()
                ),
                // Rating chip — only shows if the seller has at least one
                // review. Uses a simple star-count visual with the average
                // and review count, mirrored on /api/reviews/user/{id}/summary.
                stallData.rating && stallData.rating.count > 0 && h('div', { className: 'stall-rating' },
                  h('span', { className: 'stall-rating-stars' }, '★'.repeat(Math.round(stallData.rating.average || 0))),
                  h('span', { className: 'stall-rating-avg' }, (stallData.rating.average || 0).toFixed(1)),
                  h('span', { className: 'stall-rating-count' },
                    ` · ${stallData.rating.count} review${stallData.rating.count === 1 ? '' : 's'}`
                  )
                ),
                // Breakdown histogram — only worth showing when the seller
                // has ≥3 reviews so the bars aren't misleading.
                stallData.rating && stallData.rating.count >= 3 &&
                  h(RatingBreakdown, { summary: stallData.rating })
              ),
              // Contact button — opens a support ticket pre-filled with
              // the seller's id so CSR can triage a buyer's question about
              // a specific seller. Only shown to signed-in viewers on
              // someone else's stall (can't contact yourself).
              me && me.id !== stallData.seller.id && h('button', {
                className: 'stall-share-btn',
                onClick: async () => {
                  const reason = window.prompt(
                    `Contact @${stallData.seller.displayName || 'seller'}\n\n` +
                    `What do you want to ask? (goes through our support team — we don't share your email with the seller):`);
                  if (!reason || !reason.trim()) return;
                  const { createSupportTicket } = await import('./api.js');
                  const res = await createSupportTicket({
                    category: 'ACCOUNT',
                    subject:  `Contact seller · @${stallData.seller.displayName || stallData.seller.id}`,
                    body:     `Seller stall: /stall/${stallData.seller.id}\n\n${reason.trim()}`
                  });
                  if (res && (res.error || res.code)) {
                    alert(res.message || res.error || 'Could not open ticket.');
                  } else {
                    alert('Your message was sent through support. You can track it in /support.');
                  }
                },
                title: 'Contact this seller through support'
              },
                h('span', { className: 'stall-share-icon' }, '✉'),
                'Contact'),
              // Share button copies the canonical stall URL to the clipboard.
              // Useful for sellers promoting their stall on Discord / Steam
              // groups — CSFloat has the same affordance and users expect it.
              h(ShareStallButton, { userId: stallData.seller.id, showToast }),
              // Follow/unfollow — subscribes the viewer to NEW_LISTING
              // notifications from this seller. Only meaningful for other
              // users (can't follow yourself). Shown regardless of sign-in
              // state so signed-out users see the social proof chip; the
              // click path nudges them to sign in if needed.
              me && me.id !== stallData.seller.id &&
                h(FollowSellerButton, { sellerId: stallData.seller.id, showToast }),
              // Report button opens a FRAUD-category support ticket with the
              // seller's id pre-populated. Only shown on someone else's stall
              // (can't report yourself). Opens quietly via prompt so we don't
              // need a full modal for the rare path.
              me && me.id !== stallData.seller.id && h('button', {
                className: 'stall-share-btn',
                style: { opacity: 0.6, border: '1px solid var(--border)' },
                onClick: async () => {
                  const REPORT_REASONS = [
                    'Scam attempt', 'Suspicious pricing', 'Harassment in chat',
                    'Impersonation', 'Other'
                  ];
                  const reason = window.prompt(
                    `Report @${stallData.seller.displayName || 'seller'}\n\n` +
                    `Pick a reason by number:\n` +
                    REPORT_REASONS.map((r, i) => `  ${i + 1}) ${r}`).join('\n'), '1');
                  if (!reason) return;
                  const idx = parseInt(reason, 10);
                  const pickedReason = (idx >= 1 && idx <= REPORT_REASONS.length)
                    ? REPORT_REASONS[idx - 1] : 'Other';
                  const context = window.prompt(
                    `Reason: ${pickedReason}\n\nContext (optional, under 1000 chars — be specific):`, '');
                  if (context === null) return;
                  const { reportUser } = await import('./api.js');
                  const res = await reportUser(stallData.seller.id, pickedReason, context);
                  if (res && (res.error || res.code)) {
                    alert(res.message || res.error || 'Could not file report.');
                  } else {
                    alert('Report filed. Our support team will review it. You can track the ticket in /support.');
                  }
                },
                title: 'Report this user to support'
              },
                h('span', { className: 'stall-share-icon' }, '🚩'),
                'Report')
            ),
            stallData.away && h('div', { className: 'stall-away-banner' },
              h('span', { className: 'stall-away-dot' }),
              h('div', null,
                h('div', { className: 'stall-away-title' }, 'Seller is away'),
                h('div', { className: 'stall-away-sub' },
                  `All ${stallData.awayCount || 'active'} listings are temporarily hidden until the seller is back. You can still view their stall and leave a review.`)
              )
            ),
            stallData.count === 0
              ? h('div', { className: 'empty-inline' },
                  h('div', { className: 'empty-icon' }, stallData.away ? '🌙' : '🏪'),
                  h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
                    stallData.away
                      ? 'The seller will be back soon — check back later or watchlist one of their items.'
                      : 'This seller has no active listings right now.'))
              : (() => {
                  // Filter + sort — both purely client-side on the stall
                  // payload. Rarity chips derive from whatever rarities
                  // are actually represented so empty buttons don't
                  // dangle. Sort mirrors the marketplace toolbar vocab.
                  const rarities = Array.from(new Set(
                    stallData.listings.map(l => l?.item?.rarity || 'Standard')
                  )).sort();
                  let rows = stallRarity === 'All'
                    ? stallData.listings
                    : stallData.listings.filter(l => (l?.item?.rarity || 'Standard') === stallRarity);
                  rows = [...rows].sort((a, b) => {
                    if (stallSort === 'price_asc')  return parseFloat(a.price) - parseFloat(b.price);
                    if (stallSort === 'price_desc') return parseFloat(b.price) - parseFloat(a.price);
                    if (stallSort === 'newest')     return (b.listedAt || 0) - (a.listedAt || 0);
                    if (stallSort === 'rarity')     return (a.item?.supply || 0) - (b.item?.supply || 0);
                    return 0;
                  });
                  return h('div', null,
                    (rarities.length > 1 || stallData.listings.length > 6) && h('div', { className: 'stall-filter-row' },
                      h('div', { className: 'stall-filter-chips' },
                        h('button', {
                          className: `wallet-tx-filter-chip ${stallRarity === 'All' ? 'active' : ''}`,
                          onClick: () => setStallRarity('All')
                        }, `All · ${stallData.listings.length}`),
                        rarities.map(r => h('button', {
                          key: r,
                          className: `wallet-tx-filter-chip ${stallRarity === r ? 'active' : ''}`,
                          onClick: () => setStallRarity(r)
                        }, `${r} · ${stallData.listings.filter(l => (l?.item?.rarity || 'Standard') === r).length}`))
                      ),
                      h('select', {
                        className: 'sort-select',
                        value: stallSort,
                        onChange: e => setStallSort(e.target.value),
                        'aria-label': 'Sort stall listings'
                      },
                        h('option', { value: 'price_asc' },  'Price: Low → High'),
                        h('option', { value: 'price_desc' }, 'Price: High → Low'),
                        h('option', { value: 'newest' },     'Newest first'),
                        h('option', { value: 'rarity' },     'Lowest supply')
                      )
                    ),
                    rows.length === 0
                      ? h('div', { className: 'empty-inline' },
                          h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } }, 'No listings match this filter.'))
                      : h('div', { className: 'listing-grid' },
                          rows.map(l => h(GridCard, {
                            key: l.id,
                            listing: l,
                            onClick: () => navigate(paths.item(l.item.id)),
                            starred: watchlist.includes(l.item.id),
                            onToggleStar: toggleStar,
                            onAddToCart: me ? addToCart : null,
                            cartHas: (id) => cart.some(c => c.id === id)
                          }))
                        )
                  );
                })(),
            // Recent sales strip — last 10 completed sales by this
            // seller. Pure aggregate: item + price + soldAt, no buyer
            // identities. Builds trust by showing the seller actually
            // moves inventory.
            stallSold.length > 0 && h('div', { className: 'stall-recent-sales' },
              h('div', { className: 'stall-reviews-head' },
                h('span', { className: 'section-title-dot' }),
                `Recent sales (${stallSold.length})`
              ),
              h('div', { className: 'recent-sales-list' },
                stallSold.map(s => h('div', { key: s.listingId, className: 'recent-sales-row' },
                  h('span', { className: 'recent-sales-type' },
                    s.listingType === 'AUCTION' ? 'Auction' : 'Buy now'),
                  h('span', { style: { fontSize: 12, color: 'var(--text-secondary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } },
                    s.item?.name || 'Item'),
                  h('span', { className: 'recent-sales-price' }, fmt(s.price)),
                  h('span', { className: 'recent-sales-time' }, timeAgo(s.soldAt))
                ))
              )
            ),
            // "Leave a review" CTA — only shows up when the signed-in viewer
            // has at least one VERIFIED trade with this seller. Every trade
            // in `eligibleTrades` is already filtered server-side so there's
            // nothing more to check here.
            eligibleTrades.length > 0 && h('div', { className: 'stall-review-cta' },
              h('div', { className: 'stall-review-cta-head' },
                h('span', { className: 'section-title-dot' }),
                reviewTradeId ? 'Leave a review' : 'Leave a review'
              ),
              !reviewTradeId && h('div', { className: 'stall-review-cta-rows' },
                eligibleTrades.slice(0, 6).map(t => h('div', { key: t.tradeId, className: 'stall-review-cta-row' },
                  h('div', { style: { flex: 1, minWidth: 0 } },
                    h('div', { className: 'stall-review-cta-item' }, t.itemName || 'Trade'),
                    h('div', { className: 'stall-review-cta-sub' },
                      '$' + Number(t.price || 0).toFixed(2),
                      ' · ', new Date(t.settledAt || Date.now()).toLocaleDateString())
                  ),
                  t.reviewed
                    ? h('span', { className: 'stall-review-done' }, '✓ Reviewed')
                    : h('button', {
                        className: 'btn btn-primary-outline',
                        onClick: () => { setReviewTradeId(t.tradeId); setReviewStars(5); setReviewText(''); }
                      }, 'Write review')
                ))
              ),
              reviewTradeId && h('div', { className: 'stall-review-form' },
                h('div', { className: 'stall-review-stars-picker' },
                  [1, 2, 3, 4, 5].map(n => h('button', {
                    key: n,
                    type: 'button',
                    className: `star-btn ${reviewStars >= n ? 'on' : ''}`,
                    onClick: () => setReviewStars(n),
                    'aria-label': `${n} star${n === 1 ? '' : 's'}`
                  }, reviewStars >= n ? '★' : '☆'))
                ),
                h('textarea', {
                  className: 'stall-review-text',
                  value: reviewText,
                  maxLength: 500,
                  placeholder: 'Tell buyers what the transaction was like (optional, 500 chars max)',
                  onChange: (e) => setReviewText(e.target.value)
                }),
                h('div', { className: 'stall-review-actions' },
                  h('button', {
                    className: 'btn btn-ghost',
                    onClick: () => setReviewTradeId(null),
                    disabled: reviewBusy
                  }, 'Cancel'),
                  h('button', {
                    className: 'btn btn-primary',
                    onClick: submitStallReview,
                    disabled: reviewBusy
                  }, reviewBusy ? 'Sending…' : 'Submit review')
                )
              )
            ),
            // Recent reviews strip — only shows when the seller has feedback.
            // Reviews are trade-anchored so every entry is a real buyer who
            // actually traded with this user (see ReviewService.leaveReview).
            stallReviews && stallReviews.length > 0 && (() => {
              const displayReviews = stallStarFilter > 0
                ? stallReviews.filter(r => r.rating === stallStarFilter)
                : stallReviews;
              return h('div', { className: 'stall-reviews' },
                h('div', { className: 'stall-reviews-head' },
                  h('span', { className: 'section-title-dot' }),
                  `Recent reviews (${stallReviews.length})`
                ),
                // Star filter chips — All + 5★ .. 1★. Hidden when there's
                // nothing to filter (just one review makes the filter noise).
                stallReviews.length >= 3 && h('div', { className: 'stall-reviews-filter' },
                  [0, 5, 4, 3, 2, 1].map(n => h('button', {
                    key: n,
                    className: `wallet-tx-filter-chip ${stallStarFilter === n ? 'active' : ''}`,
                    onClick: () => setStallStarFilter(n)
                  }, n === 0 ? 'All' : `${n}★`))
                ),
                h('div', { className: 'stall-reviews-list' },
                  displayReviews.length === 0
                    ? h('div', { className: 'empty-inline' },
                        h('div', { style: { fontSize: 13, color: 'var(--text-muted)' } },
                          `No ${stallStarFilter}★ reviews yet.`))
                    : displayReviews.slice(0, 10).map(r => h(StallReviewRow, {
                        key: r.id,
                        review: r,
                        isOwner: me && stallData?.seller?.id === me.id,
                        onSaved: async () => {
                          const fresh = await fetchReviewsForUser(stallData.seller.id);
                          setStallReviews(fresh);
                        }
                      }))
                )
              );
            })()
          )
    ),
    routeName === 'notfound'      && h(InfoModal,       { title: 'Page not found', onClose: () => navigate(paths.market()) },
      h('div', { className: 'empty-inline' },
        h('div', { className: 'empty-icon' }, '🔎'),
        h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } }, '404 · nothing here'),
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 420, margin: '0 auto 18px' } },
          "The URL you followed doesn't match any page. Head back to the marketplace or try the Help Center."),
        h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center' } },
          h('a', { className: 'btn btn-accent', href: paths.market() }, 'Back to Market'),
          h('a', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, href: paths.help() }, 'Help Center')
        )
      )
    ),
    routeName === 'help'          && h(HelpModal,       { onClose: () => navigate(paths.market()) }),
    routeName === 'cart'          && h(InfoModal,       { title: `Cart · ${cartCount} item${cartCount === 1 ? '' : 's'}`, onClose: () => navigate(paths.market()) },
      cartCount === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, '🛒'),
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              'Your cart is empty'),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 380, margin: '0 auto 16px' } },
              'Browse the marketplace, tap the + on any listing card to queue it up, then come back here to check out.'),
            h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
              h('a', { className: 'btn btn-accent', href: '/' }, 'Browse marketplace →'),
              h('a', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                href: '/?sort=discount'
              }, '% Top deals')
            ))
        : h('div', null,
            // Unavailable-rows banner — fires when the bulk freshness
            // probe came back with at least one listing that is no
            // longer ACTIVE. Offers a one-click cleanup so the buyer
            // doesn't have to hunt the ✕ on each stale row.
            cartHasStale && h('div', {
              style: {
                padding: 10, marginBottom: 12, borderRadius: 8,
                background: 'rgba(248,113,113,0.12)',
                border: '1px solid rgba(248,113,113,0.35)',
                color: 'var(--red)',
                display: 'flex', alignItems: 'center', gap: 10, fontSize: 12, fontWeight: 700
              }
            },
              h('span', { style: { fontSize: 14 } }, '⚠'),
              h('div', { style: { flex: 1 } },
                'Some rows in your cart are no longer available. Checkout is paused until you remove them.'),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.4)', color: 'var(--red)', padding: '6px 12px', fontSize: 11 },
                onClick: removeStaleCartRows
              }, 'Remove unavailable')
            ),
            h('div', { className: 'cart-list' },
              cart.map(it => {
                const fresh = cartFreshness[it.id];
                const stale = fresh && !fresh.active;
                const newPrice = fresh && fresh.active && fresh.price != null
                  ? parseFloat(fresh.price) : null;
                const priceMoved = newPrice != null &&
                  Math.abs(newPrice - parseFloat(it.price)) > 0.005;
                return h('div', { key: it.id, className: 'cart-row', style: stale ? { opacity: 0.55 } : {} },
                  h('div', { className: 'cart-thumb' }, it.thumb
                    ? h('img', { src: it.thumb, alt: it.name })
                    : h('span', null, '📦')),
                  h('div', { className: 'cart-info' },
                    h('div', { className: 'cart-name' }, it.name,
                      stale && h('span', {
                        style: { marginLeft: 8, fontSize: 10, fontWeight: 700, color: 'var(--red)', background: 'rgba(248,113,113,0.15)', padding: '2px 6px', borderRadius: 4 }
                      }, 'NO LONGER AVAILABLE'),
                      priceMoved && h('span', {
                        style: { marginLeft: 8, fontSize: 10, fontWeight: 700, color: '#fbbf24', background: 'rgba(251,191,36,0.15)', padding: '2px 6px', borderRadius: 4 },
                        title: `Seller changed the price from ${fmt(it.price)} to ${fmt(newPrice)}`
                      }, `PRICE NOW ${fmt(newPrice)}`)
                    ),
                    h('div', { className: 'cart-id' }, 'Listing #' + it.id)
                  ),
                  h('div', { className: 'cart-price' }, fmt(it.price)),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 10px', fontSize: 11 },
                    onClick: () => removeFromCart(it.id)
                  }, '✕')
                );
              })
            ),
            h('div', { className: 'cart-footer' },
              h('div', { className: 'cart-total' },
                h('span', { className: 'cart-total-label' }, 'Total'),
                h('span', { className: 'cart-total-val' }, fmt(cartTotal)),
                // Savings vs Steam Market. Only renders when we have
                // reference-price snapshots for at least one row and the
                // total saves > $0. Silent when every row undercut is zero.
                cartSavings > 0 && h('span', { className: 'cart-savings-chip' },
                  '↓ Save ', fmt(cartSavings), ' vs Steam')
              ),
              h('div', { style: { display: 'flex', gap: 10, flexWrap: 'wrap' } },
                h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, onClick: clearCart }, 'Clear'),
                // Preserve buyer intent on a pricing-shift — instead of
                // forcing them to re-find each item after clearing the
                // cart, move every cart row to the watchlist in one click.
                // Dedupe against the existing watchlist; only items with a
                // known item id are movable (every real listing has one).
                cart.length > 0 && (() => {
                  const itemIds = cart.map(it => it.itemId).filter(Boolean);
                  const movable = itemIds.filter(id => !watchlist.includes(id));
                  if (movable.length === 0) return null;
                  return h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)' },
                    onClick: () => {
                      setWatchlist(w => Array.from(new Set([...w, ...itemIds])));
                      setCart([]);
                      setToast({ text: `Moved ${movable.length} item${movable.length === 1 ? '' : 's'} to watchlist`, kind: 'ok' });
                      setTimeout(() => setToast(null), 3500);
                    },
                    title: 'Move every cart row to your watchlist and clear the cart'
                  }, '♡ Move to watchlist');
                })(),
                h('button', {
                  className: 'btn btn-accent',
                  disabled: cartHasStale,
                  onClick: () => setCartConfirmOpen(true),
                  title: cartHasStale ? 'Remove unavailable rows before checkout' : undefined
                }, 'Checkout · ' + fmt(cartTotal))
              )
            )
          )
    ),
    cartConfirmOpen && h('div', { className: 'cart-confirm-backdrop', onClick: () => !cartBusy && setCartConfirmOpen(false) },
      h('div', { className: 'cart-confirm-panel', onClick: e => e.stopPropagation() },
        h('div', { className: 'cart-confirm-head' },
          h('div', { className: 'cart-confirm-title' }, 'Confirm purchase'),
          h('div', { className: 'cart-confirm-sub' },
            `Buying ${cart.length} item${cart.length === 1 ? '' : 's'} · funds held in escrow until each seller delivers.`)
        ),
        h('div', { className: 'cart-confirm-list' },
          cart.slice(0, 12).map(it => h('div', { key: it.id, className: 'cart-confirm-row' },
            h('div', { style: { flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, it.name),
            h('div', { className: 'cart-confirm-amt' }, fmt(it.price))
          )),
          cart.length > 12 && h('div', { className: 'cart-confirm-more' }, `+ ${cart.length - 12} more`)
        ),
        h('div', { className: 'cart-confirm-total' },
          h('div', null,
            h('div', { className: 'cart-confirm-total-label' }, 'Total charged to wallet'),
            h('div', { className: 'cart-confirm-total-hint' }, 'Seller receives price minus 2% platform fee after confirmed delivery.')
          ),
          h('div', { className: 'cart-confirm-total-amt' }, fmt(cartTotal))
        ),
        // Low-balance warning: if wallet balance is below cart total,
        // show a red banner with the shortfall amount + a Deposit CTA.
        // Computed client-side from the wallet the app already has; the
        // backend's checkout will still 402 on actual insufficient-funds,
        // but surfacing the gap here saves the user a round-trip.
        (() => {
          const bal = parseFloat(wallet?.balance || 0);
          const gap = cartTotal - bal;
          if (!(gap > 0)) return null;
          return h('div', { className: 'cart-confirm-low-balance' },
            h('div', null,
              h('strong', null, 'Not enough balance. '),
              `You have ${fmt(bal)} — add ${fmt(gap)} to complete this checkout.`
            ),
            h('button', {
              className: 'btn btn-accent',
              style: { padding: '6px 14px', fontSize: 12 },
              onClick: (e) => {
                e.preventDefault();
                setWalletInitialTab('deposit');
                setWalletPrefillAmount(gap.toFixed(2));
                setCartConfirmOpen(false);
                navigate(paths.wallet());
              }
            }, 'Deposit →')
          );
        })(),
        h('div', { className: 'cart-confirm-actions' },
          h('button', {
            className: 'btn btn-ghost',
            style: { border: '1px solid var(--border)' },
            onClick: () => setCartConfirmOpen(false),
            disabled: cartBusy
          }, 'Cancel'),
          h('button', {
            className: 'btn btn-accent',
            onClick: doCheckout,
            disabled: cartBusy || cart.length === 0 || cartTotal > parseFloat(wallet?.balance || 0)
          }, cartBusy ? 'Placing order…' : `Confirm · ${fmt(cartTotal)}`)
        )
      )
    ),
    routeName === 'faq'           && h(FaqModal,        { onClose: () => navigate(paths.market()) }),
    routeName === 'settings'      && h(SettingsModal,   { onClose: () => navigate(paths.market()) }),
    // Deep-link handling — notifications like TRADE_MESSAGE land us on
    // `/profile?tab=trades`. Pull the `tab` query param so the Profile
    // modal opens on the right tab instead of the Personal default.
    routeName === 'profile'       && h(ProfileModal,    {
      onClose: () => navigate(paths.market()),
      me, wallet, transactions,
      onRefresh: () => { loadWallet(); },
      initialTab: (() => {
        try {
          const q = new URLSearchParams(window.location.search).get('tab');
          const allowed = new Set(['personal','transactions','buyorders','autobids','trades','offers','reviews','support','developers']);
          return q && allowed.has(q) ? q : undefined;
        } catch { return undefined; }
      })()
    }),
    routeName === 'sell'          && h(SellItemsModal,  { onClose: () => navigate(paths.market()), me, onRefresh: load }),
    routeName === 'mystall'       && h(MyStallModal,    { onClose: () => navigate(paths.market()), me, onRefresh: load }),
    routeName === 'offers'        && h(OffersModal,     { onClose: () => navigate(paths.market()), me, onRefresh: () => { load(); loadWallet(); } }),
    routeName === 'watchlist'     && h(WatchlistModal,  {
      onClose: () => navigate(paths.market()),
      watchlist, allListings: listings,
      onOpen: openModal, onToggleStar: toggleStar,
      onAddToCart: me ? addToCart : null,
      cartHas: (id) => cart.some(c => c.id === id)
    }),
    routeName === 'database'      && h(DatabaseModal,      { onClose: () => navigate(paths.market()), onPickItem: (item) => { navigate(paths.item(item.id)); } }),
    routeName === 'buyorders'     && h(BuyOrdersModal,     { onClose: () => { setPreselectedBuyItem(null); navigate(paths.market()); }, me, preselectedItem: preselectedBuyItem }),
    routeName === 'loadouts'      && h(LoadoutLabModal,    { onClose: () => navigate(paths.market()), me }),
    routeName === 'loadout'       && h(LoadoutLabModal,    { onClose: () => navigate(paths.market()), me, loadoutId: route.params?.id }),
    routeName === 'notifications' && h(NotificationsModal, { onClose: () => navigate(paths.market()), me }),
    routeName === 'support'       && h(ProfileModal,        { onClose: () => navigate(paths.market()), me, wallet, transactions, onRefresh: loadWallet, initialTab: 'support' }),
    // Staff panels — role-gated. Non-staff users who type the URL hit a
    // plain Help modal so they're not stuck on a blank page.
    routeName === 'admin' && (isAdmin
      ? h(AdminModal, { onClose: () => navigate(paths.market()), me })
      : h(FaqModal,   { onClose: () => navigate(paths.market()) })),
    routeName === 'csr' && (isCsr
      ? h(CsrModal, { onClose: () => navigate(paths.market()), me })
      : h(FaqModal, { onClose: () => navigate(paths.market()) })),

    /* ITEM DETAIL — the only modal that isn't a menu destination. Closing it
       navigates back to /, so back/forward work naturally.

       Render states: loading spinner → ItemModal on success → "item not
       found" fallback when the route lands on a genuinely missing id.
       Without the fallback the modal rendered nothing and the user saw
       a blank page with no way to figure out what happened. */
    routeName === 'item' && !modalLoading && !selected && (
      h('div', { className: 'modal-backdrop', onClick: () => navigate(paths.market()) },
        h('div', { className: 'modal', onClick: (e) => e.stopPropagation(), style: { maxWidth: 420, textAlign: 'center', padding: '32px 24px' } },
          h('div', { style: { fontSize: 48, marginBottom: 12 } }, '🕳️'),
          h('div', { style: { fontSize: 18, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 8 } }, 'Item not found'),
          h('div', { style: { fontSize: 13, color: 'var(--text-muted)', marginBottom: 18 } },
            'The item you were looking for has been removed or never existed. It may have been merged into another entry by the catalogue sync.'),
          h('a', { className: 'btn btn-accent', href: '/' }, 'Back to marketplace')
        )
      )
    ),
    routeName === 'item' && (selected || modalLoading) && (
      modalLoading
        ? h('div', { className: 'modal-backdrop' }, h('div', { className: 'spinner', style: { margin: '0 auto' } }))
        : h(ItemModal, {
            item: selected.item,
            listings: selected.listings,
            history: selected.history,
            me,
            onClose: () => navigate(paths.market()),
            onBuy: handleBuy,
            onMakeOffer: handleMakeOffer,
            onRefresh: () => { load(); loadWallet(); },
            onCreateBuyOrder: (item) => {
              setPreselectedBuyItem(item);
              navigate(paths.buyorders());
            },
            onAddToCart: (listing) => {
              addToCart(listing);
              showToast(`Added ${listing.item?.name} to cart`, 'ok');
            },
            cartHas: (id) => cart.some(x => x.id === id)
          })
    ),

    /* SHORTCUTS HELP OVERLAY — press `?` to toggle */
    shortcutsOpen && h('div', { className: 'shortcuts-backdrop', onClick: () => setShortcutsOpen(false) },
      h('div', { className: 'shortcuts-card', onClick: e => e.stopPropagation() },
        h('div', { className: 'shortcuts-title' }, 'Keyboard Shortcuts'),
        h('div', { className: 'shortcuts-grid' },
          [
            ['/',       'Focus search'],
            ['?',       'Toggle this panel'],
            ['Esc',     'Back to market / close detail'],
            ['g m',     'Go to Market'],
            ['g d',     'Go to Database'],
            ['g p',     'Go to Profile'],
            ['g w',     'Go to Wallet'],
            ['g c',     'Go to Cart'],
            ['g l',     'Go to Loadout Lab'],
            ['g s',     'Go to Sell Items'],
            ['g f',     'Go to Watchlist (Favorites)'],
            ['g h',     'Go to Help'],
            ['g o',     'Go to Offers'],
            ['g b',     'Go to Buy Orders'],
            ['g n',     'Go to Notifications'],
            ['Ctrl-click balance', 'Toggle privacy mode']
          ].map(([k, d]) => h('div', { key: k, className: 'shortcut-row' },
            h('kbd', null, k), h('span', null, d)
          ))
        ),
        h('button', { className: 'btn btn-ghost', style: { marginTop: 14, border: '1px solid var(--border)' }, onClick: () => setShortcutsOpen(false) }, 'Close')
      )
    ),

    /* TOAST */
    toast && h('div', { className: `sale-toast ${toast.kind === 'err' ? 'err' : ''}` },
      h('div', { className: 'sale-toast-thumb', style: {
        background: toast.kind === 'err' ? 'var(--red-dim)' : 'var(--accent-dim)',
        color:      toast.kind === 'err' ? 'var(--red)'     : 'var(--accent)'
      } }, toast.kind === 'err' ? '✕' : '✓'),
      h('div', { className: 'sale-toast-text' },
        h('div', { className: 'sale-toast-line2' }, toast.text)
      )
    ),

    /* FEE CALCULATOR — sits right above the footer as a marketing strip
       so signed-out visitors see the pricing pitch after they've scrolled
       through the marketplace. Signed-in users already bought in to the
       pricing, no need to show it to them. */
    !me && h('section', { className: 'homepage-trust' },
      h('div', { className: 'fee-calc' },
        h('div', null,
          h('div', { className: 'fee-calc-title' },
            h('div', { className: 'section-title-dot' }),
            'Fee Calculator'
          ),
          h('div', { className: 'fee-calc-sub' }, "See what you'll actually take home on a sale."),
          h('div', { className: 'fee-calc-row' },
            h('label', null, 'Sale Amount ($)'),
            h('input', {
              className: 'price-input fee-calc-input',
              type: 'number', min: '1', step: '0.01',
              value: feeInput,
              onChange: e => setFeeInput(e.target.value),
              onFocus: e => e.target.select()
            })
          )
        ),
        h('div', null,
          (() => {
            const amt = Math.max(0, parseFloat(feeInput) || 0);
            const platformFee = (amt * 0.02);
            const withdrawFee = (amt * 0.015);
            const take = Math.max(0, amt - platformFee - withdrawFee);
            return h('div', { className: 'fee-calc-breakdown' },
              h('div', { className: 'fee-calc-line' },
                h('span', null, 'Platform fee (2%)'),
                h('strong', null, '−' + fmt(platformFee))
              ),
              h('div', { className: 'fee-calc-line' },
                h('span', null, 'Withdraw fee (1.5%)'),
                h('strong', null, '−' + fmt(withdrawFee))
              ),
              h('div', { className: 'fee-calc-line total' },
                h('span', null, 'You receive'),
                h('strong', null, fmt(take))
              )
            );
          })(),
          h('div', { className: 'fee-calc-note' }, 'Steam takes 12% on Workshop sales. SkinBox is 3.5% total (2% platform + 1.5% payout) — you keep nearly 3× more.')
        )
      ),
    ),

    /* FOOTER — pinned at the bottom of the site-root flex column so it
       sits below ALL content (marketplace, profile, wallet, etc.) instead
       of being glued to a specific section. See .site-root { display:flex
       flex-direction:column min-height:100vh } in styles.css. */
    h(SiteFooter, null)
  );
}

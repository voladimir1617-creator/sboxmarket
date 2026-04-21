// Top-right nav icons: notification bell + theme picker.
import { h, React, useState, useEffect, useCallback, timeAgo, signInWithSteam } from './utils.js';
import { fetchNotifications, fetchUnreadNotificationCount, markAllNotificationsRead, markNotificationRead } from './api.js';
import { navigate, paths } from './router.js';
import { MaterialIcon } from './primitives.js';

// Route a notification to the right page when the server didn't supply one.
// Kept in sync with kindFallbackPath in csfloat-modals.js — any new event kind
// should be handled in both places or (better) carry a server-side `path`.
function fallbackPath(kind) {
  if (!kind) return '/profile';
  const k = kind.toUpperCase();
  if (k === 'DEPOSIT_COMPLETE' || k === 'DEPOSIT_EXPIRED' || k.startsWith('WITHDRAWAL_') ||
      k === 'ADMIN_CREDIT' || k === 'ADMIN_DEBIT' || k === 'CSR_CREDIT' ||
      k === 'DISPUTE_CLEARED' || k === 'REFUND_ISSUED') return paths.wallet();
  if (k === 'CHARGEBACK_OPENED') return '/admin?tab=disputes';
  if (k === 'CARD_TESTING_DETECTED') return '/admin?tab=users';
  if (k === 'CART_ITEM_SOLD') return paths.cart();
  if (k === 'FRAUD_SIGNAL_HIGH') return '/admin?tab=fraud';
  if (k === 'BUY_ORDER_FILLED' || k === 'BUY_ORDER_EXPIRED') return paths.buyorders();
  if (k === 'OFFER_RECEIVED' || k === 'OFFER_ACCEPTED' || k === 'OFFER_REJECTED' || k === 'OFFER_COUNTERED') return paths.offers();
  if (k === 'SUPPORT_REPLY' || k === 'TICKET_AUTO_RESOLVED') return paths.support();
  if (k === 'STEAM_INVENTORY') return paths.sell();
  if (k === 'LISTING_REMOVED') return paths.mystall();
  if (k === 'REVIEW_RECEIVED' || k === 'REVIEW_REPLIED' || k === 'REVIEW_UPDATED' || k === 'REVIEW_DELETED') return '/profile?tab=reviews';
  if (k === 'SELLER_FOLLOWED') return paths.mystall();
  // Anything trade-ish (auction/trade/purchase) lands on the trades tab.
  if (k.startsWith('TRADE_') || k.startsWith('AUCTION_') || k === 'ITEM_PURCHASED') {
    return '/profile?tab=trades';
  }
  return paths.profile();
}

// Bucket classifier matching the Settings mute list + the Notifications
// modal filter. Kept in lockstep so a muted category is invisible from
// both the bell dropdown and the /notifications page.
function kindBucket(kind) {
  const k = (kind || '').toUpperCase();
  if (k.startsWith('TRADE_') || k === 'ITEM_PURCHASED') return 'TRADES';
  if (k.startsWith('AUCTION_')) return 'AUCTIONS';
  if (k.startsWith('OFFER_') || k === 'BUY_ORDER_FILLED' || k === 'BUY_ORDER_EXPIRED') return 'OFFERS';
  if (k === 'DEPOSIT_COMPLETE' || k === 'DEPOSIT_EXPIRED' || k.startsWith('WITHDRAWAL_') ||
      k === 'ADMIN_CREDIT' || k === 'ADMIN_DEBIT' || k === 'CSR_CREDIT' ||
      k === 'DISPUTE_CLEARED' || k === 'REFUND_ISSUED' ||
      k === 'CHARGEBACK_OPENED') return 'WALLET';
  // "We found something for you" signals — saved-search hits,
  // watchlist drops, follower-listing pings, generic price drops.
  // Grouped so a buyer can filter / mute discovery noise without
  // losing operational notifications (TRADES/AUCTIONS/OFFERS/WALLET).
  if (k === 'LISTING_MATCH' || k === 'WATCHLIST_PRICE_DROP' ||
      k === 'NEW_LISTING_FROM_SELLER' || k === 'PRICE_DROPPED' ||
      k === 'CART_ITEM_SOLD') return 'MATCHES';
  return 'OTHER';
}
function readMuted() {
  try { return new Set(JSON.parse(localStorage.getItem('sb_mute_kinds') || '[]')); }
  catch { return new Set(); }
}

// Map server `kind` → icon glyph for the dropdown.
// Batch 1068 — editorial text markers only. Mirrors the csfloat-modals.js
// kindIcon map so the nav bell preview and the full notifications feed
// use identical glyphs per kind.
const KIND_ICONS = {
  // Trades
  ITEM_PURCHASED:    '⇄',
  TRADE_VERIFIED:    '✓',
  TRADE_CANCELLED:   '✕',
  TRADE_DISPUTED:    '⚠',
  TRADE_REQUESTED:   '⇄',
  TRADE_MESSAGE:     '"',
  TRADE_ACCEPTED:    '✓',
  TRADE_SENT:        '→',
  TRADE_OPENED:      '⇄',
  TRADE_SLOW_SELLER: '…',
  TRADE_SELLER_NUDGE: '!',
  // Wallet
  WITHDRAWAL_REJECTED: '✕',
  WITHDRAWAL_COMPLETE: '$',
  DEPOSIT_COMPLETE:  '$',
  DEPOSIT_EXPIRED:   '—',
  REFUND_ISSUED:     '↩',
  CHARGEBACK_OPENED: '⚠',
  DISPUTE_CLEARED:   '✓',
  CARD_TESTING_DETECTED: '⚠',
  ADMIN_CREDIT:      '+',
  ADMIN_DEBIT:       '−',
  CSR_CREDIT:        '+',
  // Account
  ACCOUNT_BANNED:    '⚠',
  ACCOUNT_UNBANNED:  '↩',
  TWOFA_RESET:       '!',
  // Staff
  SUPPORT_REPLY:     '"',
  TICKET_AUTO_RESOLVED: '—',
  LISTING_REMOVED:   '⚠',
  REPORT_ACTIONED:   '⚠',
  REPORT_REVIEWED:   '◦',
  ADMIN_GRANTED:     '+',
  ADMIN_REVOKED:     '↓',
  CSR_GRANTED:       '+',
  CSR_REVOKED:       '↓',
  STEAM_INVENTORY:   '⇄',
  // Reviews
  REVIEW_REMINDER:   '★',
  REVIEW_RECEIVED:   '★',
  REVIEW_UPDATED:    '★',
  REVIEW_DELETED:    '✕',
  REVIEW_REPLIED:    '"',
  // Auctions
  AUCTION_WON:       '✓',
  AUCTION_SOLD:      '$',
  AUCTION_LOST:      '✕',
  AUCTION_OUTBID:    '↑',
  AUCTION_ENDING:    '◐',
  AUCTION_CANCELLED: '✕',
  AUCTION_EXPIRED_NO_BIDS: '—',
  // Offers
  OFFER_RECEIVED:    '"',
  OFFER_ACCEPTED:    '✓',
  OFFER_REJECTED:    '✕',
  OFFER_COUNTERED:   '⇄',
  // Buy orders
  BUY_ORDER_FILLED:  '↗',
  BUY_ORDER_EXPIRED: '—',
  LISTING_MATCH:     '↗',
  // Catalogue + misc
  CART_ITEM_SOLD:    '$',
  FRAUD_SIGNAL_HIGH: '⚠',
  SELLER_FOLLOWED:   '+',
  WELCOME:           '★',
  ADMIN_BROADCAST:   '!',
  ADMIN_MESSAGE:     '"',
  LOADOUT_DELETED:   '✕',
  PRICE_DROPPED:     '↓',
  NEW_LISTING_FROM_SELLER: '+',
  WATCHLIST_PRICE_DROP: '↓',
};

// Short, subtle two-tone ding triggered by the Settings "Notification
// sounds" toggle. Pure Web Audio — no external asset shipped. Guards:
//   - first call primes a single AudioContext and reuses it;
//   - bail cleanly if the browser blocks autoplay (user hasn't
//     interacted yet) or if Web Audio isn't available;
//   - bail when the user has muted sounds via Settings.
// Exported (batch 819) so the Settings modal can offer a one-click
// "Test sound" button next to the Sound effects toggle. The function
// is idempotent and reuses its own AudioContext across calls.
// `force=true` bypasses the sb_sounds mute so the Settings preview
// can always play — the setting is about incoming-notification dings,
// not a manual audition.
export function playNotifyDing(opts) {
  const force = opts === true || (opts && opts.force === true);
  try {
    if (!force && localStorage.getItem('sb_sounds') === 'false') return;
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = (playNotifyDing._ctx = playNotifyDing._ctx || new Ctx());
    if (ctx.state === 'suspended') { try { ctx.resume(); } catch (_) {} }
    const now = ctx.currentTime;
    const ping = (freq, offset, dur) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now + offset);
      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.15, now + offset + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + dur);
      osc.connect(gain).connect(ctx.destination);
      osc.start(now + offset);
      osc.stop(now + offset + dur);
    };
    ping(880, 0,    0.12);
    ping(1320, 0.08, 0.18);
  } catch (_) { /* silent — fall back to the visual bell */ }
}

export function NotificationBell({ me }) {
  const [open, setOpen]     = useState(false);
  const [items, setItems]   = useState([]);
  const [unread, setUnread] = useState(0);
  const wrapRef = React.useRef(null);
  // Sentinel: -1 until the first poll lands, so the initial "you have
  // N unread already" render doesn't trigger a ding for every unread
  // row a user already had before they loaded the page.
  const lastUnreadRef = React.useRef(-1);

  // Full fetch — 100 notification rows + unread count. Called on first
  // mount and whenever the user opens the dropdown (so the flyout
  // renders fresh rows). Derives `visibleUnread` from the filtered set
  // so muted kinds don't count toward the badge.
  const loadFull = useCallback(async () => {
    if (!me) { setItems([]); setUnread(0); return; }
    // Batch 1026 — bell dropdown only ever renders 12 rows, so request
    // 12 from the server instead of the default 100. Shaves ~85% off
    // the payload on every first-open + on every dropdown refresh.
    const data = await fetchNotifications(12);
    const muted = readMuted();
    const all = Array.isArray(data?.items) ? data.items : [];
    const visible = muted.size > 0 ? all.filter(n => !muted.has(kindBucket(n.kind))) : all;
    const visibleUnread = visible.filter(n => !n.read).length;
    setItems(visible);
    setUnread(visibleUnread);
  }, [me]);

  // Cheap unread-only poll (batch 1011) — hits `/api/notifications/unread-count`
  // instead of pulling 100 rows every 25 seconds. Falls through to the
  // full fetch when the user has any muted kinds (so the badge stays
  // muted-aware), since the cheap endpoint returns server-raw unread
  // without knowledge of the client's local mute list. Bulk of signed-in
  // users have no mutes, so the fast path is the default.
  const loadCount = useCallback(async () => {
    if (!me) { setUnread(0); return; }
    const muted = readMuted();
    if (muted.size > 0) { await loadFull(); return; }
    const n = await fetchUnreadNotificationCount();
    setUnread(n);
  }, [me, loadFull]);

  // Kept for backwards-compat with the close-handler + mark-read paths
  // below — `load` historically meant "refresh everything". Route it
  // to the full fetch so those code paths keep working unchanged.
  const load = loadFull;

  // Batch 1024 — mount-time fetch is now the cheap count-only endpoint,
  // not the 100-row full list. The vast majority of signed-in users
  // never click the bell during a session — shipping the full list on
  // every page load was waste. We fetch items lazily the first time the
  // user actually opens the dropdown. Mutes still force a full fetch
  // (see `loadCount`) so the badge stays muted-aware.
  useEffect(() => { loadCount(); }, [loadCount]);

  // Refresh the full item list whenever the dropdown opens — covers
  // both the first-open hydration AND the mid-session re-open refresh
  // so the flyout never renders rows stale from an earlier view.
  useEffect(() => {
    if (open) loadFull();
  }, [open, loadFull]);

  // Play the notify ding when the unread count *grows* between polls.
  // Sentinel -1 on first render means "don't ding yet"; we just set
  // the baseline to the current value. After that, any strict
  // increase fires the ding.
  useEffect(() => {
    const prev = lastUnreadRef.current;
    lastUnreadRef.current = unread;
    if (prev !== -1 && unread > prev) playNotifyDing();
  }, [unread]);

  // Poll every 25s while signed in so the bell stays fresh without sockets.
  // Batch 757 — skip the fetch when the tab is hidden. Saves a network
  // round-trip per 25s on every backgrounded tab; users pick up fresh
  // data when they return (visibilitychange listener below also triggers
  // an immediate refresh so the stale bell doesn't linger).
  useEffect(() => {
    if (!me) return;
    // Batch 1011 — if the dropdown is open, refresh the full list so the
    // flyout picks up fresh rows mid-poll. Closed state polls only the
    // cheap count endpoint so an idle tab doesn't ship 100 rows every 25s.
    const tick = () => {
      if (document.hidden) return;
      if (open) loadFull(); else loadCount();
    };
    const id = setInterval(tick, 25000);
    const onVisible = () => {
      if (document.hidden) return;
      if (open) loadFull(); else loadCount();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [me, loadCount, loadFull, open]);

  // Mirror unread count into the browser tab title so users glancing at a
  // background tab see "(3) SkinBox …" when something needs attention.
  // CSFloat does the same; Slack, GitHub, Gmail all use this convention.
  // Restored on unmount so the base title isn't leaked to downstream pages.
  useEffect(() => {
    const base = document.title.replace(/^\(\d+\)\s+/, '');
    document.title = unread > 0 ? `(${unread > 99 ? '99+' : unread}) ${base}` : base;
    return () => {
      document.title = document.title.replace(/^\(\d+\)\s+/, '');
    };
  }, [unread]);

  // Favicon badge — red circle with the unread count stamped on top of
  // the base favicon so background-tab users see the signal even with
  // the tab title truncated. Same convention as Slack / Discord /
  // Gmail. Pure canvas overlay, no new asset shipped. Base image is
  // cached after the first successful load; if it fails (blocked,
  // offline) we silently skip the badge so the unread prefix in the
  // title is still the primary signal.
  useEffect(() => {
    let cancelled = false;
    const applyBadge = (count) => {
      if (cancelled) return;
      try {
        const link = document.querySelector('link[rel="icon"][sizes="32x32"]')
                  || document.querySelector('link[rel="icon"]');
        if (!link) return;
        // Remember the baseline href so we can restore it when unread
        // goes back to zero. Stored on the link element so repeated
        // mounts share the same source of truth.
        if (!link.dataset.sbBaseHref) link.dataset.sbBaseHref = link.href;
        if (count === 0) { link.href = link.dataset.sbBaseHref; return; }
        const img = new Image();
        img.crossOrigin = 'anonymous';
        img.onload = () => {
          if (cancelled) return;
          const size = 32;
          const cv = document.createElement('canvas');
          cv.width = size; cv.height = size;
          const ctx = cv.getContext('2d');
          ctx.drawImage(img, 0, 0, size, size);
          const label = count > 9 ? '9+' : String(count);
          // Red circle, top-right, ~14px diameter — big enough to be
          // legible at 16px favicon scale without swallowing the crate.
          ctx.fillStyle = '#ef4444';
          ctx.beginPath(); ctx.arc(size - 7, 7, 7, 0, Math.PI * 2); ctx.fill();
          ctx.strokeStyle = '#0d1320'; ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.arc(size - 7, 7, 7, 0, Math.PI * 2); ctx.stroke();
          ctx.fillStyle = '#fff';
          ctx.font = 'bold 10px -apple-system, "Segoe UI", system-ui, sans-serif';
          ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText(label, size - 7, 7.5);
          link.href = cv.toDataURL('image/png');
        };
        img.onerror = () => { /* silent — title prefix still fires */ };
        img.src = link.dataset.sbBaseHref;
      } catch (_) { /* canvas blocked or no link — silent */ }
    };
    applyBadge(unread);
    return () => {
      cancelled = true;
      try {
        const link = document.querySelector('link[rel="icon"][sizes="32x32"]')
                  || document.querySelector('link[rel="icon"]');
        if (link && link.dataset.sbBaseHref) link.href = link.dataset.sbBaseHref;
      } catch (_) { /* silent */ }
    };
  }, [unread]);

  useEffect(() => {
    const onDoc = e => { if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener('click', onDoc);
    return () => document.removeEventListener('click', onDoc);
  }, []);

  // Opening the bell auto-clears the unread badge — CSFloat/Slack/GitHub
  // pattern. The rows keep their visual unread accent (blue border) so
  // the user can still see at a glance what's new, but the badge drops
  // to 0 immediately instead of forcing a per-row dismiss dance. Fires
  // once per open → close cycle; no-op when there's nothing unread.
  useEffect(() => {
    if (!open || !me || unread === 0) return;
    let cancelled = false;
    (async () => {
      try { await markAllNotificationsRead(); } catch (_) {}
      if (!cancelled) await load();
    })();
    return () => { cancelled = true; };
  }, [open, me]);

  const clearAll = async () => {
    if (!me) return;
    await markAllNotificationsRead();
    await load();
  };

  const onItemClick = async (n) => {
    if (!n.read) {
      // Batch 775 — optimistic local bump-down of the unread count so
      // the bell badge ticks down the moment the user clicks a row,
      // not 25s later when the next poll lands. Also flip the row's
      // .read flag in-place so the visual accent on the row drops
      // without waiting for load(). Server-side persistence still
      // fires; any failure will self-correct on the next poll.
      setUnread(u => Math.max(0, u - 1));
      setItems(prev => prev.map(row => row.id === n.id ? { ...row, read: true } : row));
      try { await markNotificationRead(n.id); } catch (_) {}
    }
    setOpen(false);
    let target = n.path || fallbackPath(n.kind);
    // Batch 744 — mirror of the NotificationsModal logic: append
    // `?highlight=<tradeId>` for TRADE_* so the trades tab scrolls the
    // right row into view. TRADE_MESSAGE already carries its own
    // `?openChat=<id>` param so we skip it.
    if (target && n.kind && typeof n.kind === 'string'
        && n.kind.toUpperCase().startsWith('TRADE_')
        && n.kind.toUpperCase() !== 'TRADE_MESSAGE'
        && n.refId && !target.includes('highlight=')
        && target.includes('tab=trades')) {
      target += (target.includes('?') ? '&' : '?') + 'highlight=' + n.refId;
    }
    if (target) navigate(target);
    else load();
  };

  return h('div', { ref: wrapRef, style: { position: 'relative' } },
    h('button', {
      className: 'nav-icon-btn',
      onClick: () => setOpen(v => !v),
      title: 'Notifications',
      'aria-label': unread > 0 ? `Notifications (${unread} unread)` : 'Notifications',
      // Batch 948 — aria-haspopup="menu" lets screen readers announce
      // the trigger as "menu button" and promises a popup appears on
      // activation. aria-expanded was already bound; paired they tell
      // the whole story to assistive tech.
      'aria-haspopup': 'menu',
      'aria-expanded': open
    },
      h(MaterialIcon, { name: 'notifications', size: 20, fill: unread > 0, color: unread > 0 ? '#fbbf24' : null }),
      unread > 0 && h('div', { className: 'nav-icon-badge' }, unread)
    ),
    open && h('div', { className: 'notif-dropdown', onClick: e => e.stopPropagation() },
      h('div', { className: 'notif-header' },
        'Notifications',
        // Batch 948 — was a clickable <span>; not keyboard-focusable,
        // not announced as a button. Now a real <button> with an explicit
        // aria-label so the SR hears "Mark all N unread notifications
        // read" instead of raw text.
        items.length > 0 && h('button', {
          className: 'notif-clear',
          onClick: clearAll,
          'aria-label': unread > 0 ? `Mark all ${unread} unread notifications read` : 'Mark all notifications read'
        }, 'Mark all read')
      ),
      items.length === 0
        ? (me
            ? h('div', { className: 'notif-empty' }, "You're all caught up.")
            : h('div', { className: 'notif-empty', style: { display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'center', padding: '14px 12px' } },
                h('div', { style: { fontSize: 12, color: 'var(--text-secondary)' } },
                  'Sign in with Steam to see your notifications.'),
                h('button', {
                  className: 'btn btn-accent',
                  style: { padding: '6px 14px', fontSize: 12 },
                  onClick: () => { signInWithSteam(); }
                }, 'Sign in with Steam')
              ))
        : [
            ...items.slice(0, 12).map(n => h('div', {
              key: n.id,
              className: `notif-item ${n.read ? '' : 'unread'}`,
              // Batch 834 — keyboard-accessible notification rows.
              // Previously plain div onClick — mouse-only. Now announces
              // as a button, accepts Enter/Space, and focuses via Tab.
              role: 'button',
              tabIndex: 0,
              'aria-label': `${n.read ? '' : 'Unread: '}${n.title}. Opens in new page.`,
              onClick: () => onItemClick(n),
              onKeyDown: (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onItemClick(n);
                }
              }
            },
              h('div', { className: 'notif-icon' }, KIND_ICONS[n.kind] || '•'),
              h('div', { className: 'notif-text' },
                n.title,
                n.body && h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 } }, n.body),
                h('div', { className: 'notif-time' }, timeAgo(n.createdAt))
              )
            )),
            // View-all footer (batch 366) — navigates to the full
            // /notifications page which has filters + group-by-day +
            // search + bulk clear. Bell dropdown shows the most-recent
            // 12 so a power user with hundreds of events has a clear
            // escape hatch to the richer view.
            h('a', {
              key: '__viewAll',
              className: 'notif-item',
              href: '/notifications',
              onClick: () => setOpen(false),
              style: {
                justifyContent: 'center',
                borderTop: '1px solid var(--border)',
                fontSize: 12, color: 'var(--accent)',
                fontWeight: 600, textDecoration: 'none'
              }
            }, items.length > 12
                ? `View all notifications (${items.length})`
                : 'View all notifications')
          ]
    )
  );
}

// Accent theme system deleted per operator directive: the editorial
// template (design.css) is the single source of truth for colours.
// Operator directive tightened 2026-04-20: default palette is MONO
// (near-white on warm near-black); --cta (#3b82f6 blue) shows up only
// on primary CTA buttons, live LEDs, and active-tab underlines.
// Previously this bootstrap hard-locked data-accent="blue", which
// painted every `var(--accent)` site-wide — icons, borders, hover
// tints — with SkinBox blue. That contradicted the mono-primary
// direction, so we now set data-accent="mono".
(function purgeLegacyTheme() {
  try {
    const r = document.documentElement;
    ['--accent','--accent-2','--accent-dim','--accent-strong','--accent-border']
      .forEach(k => r.style.removeProperty(k));
    // Mono-primary: --accent resolves to near-white; --cta stays blue
    // and is the only blue signal on the page (CTAs + LEDs + active
    // underlines). design.css owns the rest of the cascade.
    r.setAttribute('data-accent', 'mono');
    localStorage.removeItem('sb_theme');
  } catch (_) { /* private mode / SSR */ }
})();

// Kept as a no-op export so any straggler import compiles. Safe to
// remove once no module references it.
export function ThemePicker() {
  return null;
}

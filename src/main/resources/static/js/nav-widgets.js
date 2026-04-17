// Top-right nav icons: notification bell + theme picker.
import { h, React, useState, useEffect, useCallback, timeAgo, signInWithSteam } from './utils.js';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from './api.js';
import { navigate, paths } from './router.js';

// Route a notification to the right page when the server didn't supply one.
// Kept in sync with kindFallbackPath in csfloat-modals.js — any new event kind
// should be handled in both places or (better) carry a server-side `path`.
function fallbackPath(kind) {
  if (!kind) return '/profile';
  const k = kind.toUpperCase();
  if (k === 'DEPOSIT_COMPLETE' || k.startsWith('WITHDRAWAL_') ||
      k === 'ADMIN_CREDIT' || k === 'ADMIN_DEBIT' || k === 'CSR_CREDIT') return paths.wallet();
  if (k === 'BUY_ORDER_FILLED') return paths.buyorders();
  if (k === 'OFFER_RECEIVED' || k === 'OFFER_ACCEPTED' || k === 'OFFER_REJECTED') return paths.offers();
  if (k === 'SUPPORT_REPLY') return paths.support();
  if (k === 'STEAM_INVENTORY') return paths.sell();
  if (k === 'LISTING_REMOVED') return paths.mystall();
  if (k === 'REVIEW_RECEIVED') return '/profile?tab=reviews';
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
  if (k.startsWith('OFFER_') || k === 'BUY_ORDER_FILLED') return 'OFFERS';
  if (k === 'DEPOSIT_COMPLETE' || k.startsWith('WITHDRAWAL_') ||
      k === 'ADMIN_CREDIT' || k === 'ADMIN_DEBIT' || k === 'CSR_CREDIT') return 'WALLET';
  return 'OTHER';
}
function readMuted() {
  try { return new Set(JSON.parse(localStorage.getItem('sb_mute_kinds') || '[]')); }
  catch { return new Set(); }
}

// Map server `kind` → icon glyph for the dropdown.
const KIND_ICONS = {
  ITEM_PURCHASED:    '🛒',
  TRADE_VERIFIED:    '✓',
  REVIEW_REMINDER:   '★',
  AUCTION_WON:       '🏆',
  AUCTION_LOST:      '✕',
  AUCTION_OUTBID:    '↑',
  AUCTION_ENDING:    '⏰',
  OFFER_RECEIVED:    '💬',
  OFFER_ACCEPTED:    '✓',
  OFFER_REJECTED:    '✕',
  BUY_ORDER_FILLED:  '⚡',
  DEPOSIT_COMPLETE:  '$',
  WITHDRAWAL_COMPLETE: '$',
  PRICE_DROPPED:     '↓',
};

// Short, subtle two-tone ding triggered by the Settings "Notification
// sounds" toggle. Pure Web Audio — no external asset shipped. Guards:
//   - first call primes a single AudioContext and reuses it;
//   - bail cleanly if the browser blocks autoplay (user hasn't
//     interacted yet) or if Web Audio isn't available;
//   - bail when the user has muted sounds via Settings.
function playNotifyDing() {
  try {
    if (localStorage.getItem('sb_sounds') === 'false') return;
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

  const load = useCallback(async () => {
    if (!me) { setItems([]); setUnread(0); return; }
    const data = await fetchNotifications();
    const muted = readMuted();
    const all = Array.isArray(data?.items) ? data.items : [];
    const visible = muted.size > 0 ? all.filter(n => !muted.has(kindBucket(n.kind))) : all;
    const visibleUnread = visible.filter(n => !n.read).length;
    setItems(visible);
    setUnread(visibleUnread);
  }, [me]);

  useEffect(() => { load(); }, [load]);

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
  useEffect(() => {
    if (!me) return;
    const id = setInterval(load, 25000);
    return () => clearInterval(id);
  }, [me, load]);

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

  useEffect(() => {
    const onDoc = e => { if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener('click', onDoc);
    return () => document.removeEventListener('click', onDoc);
  }, []);

  const clearAll = async () => {
    if (!me) return;
    await markAllNotificationsRead();
    await load();
  };

  const onItemClick = async (n) => {
    if (!n.read) {
      try { await markNotificationRead(n.id); } catch (_) {}
    }
    setOpen(false);
    const target = n.path || fallbackPath(n.kind);
    if (target) navigate(target);
    else load();
  };

  return h('div', { ref: wrapRef, style: { position: 'relative' } },
    h('button', {
      className: 'nav-icon-btn',
      onClick: () => setOpen(v => !v),
      title: 'Notifications',
      'aria-label': unread > 0 ? `Notifications (${unread} unread)` : 'Notifications',
      'aria-expanded': open
    },
      '🔔',
      unread > 0 && h('div', { className: 'nav-icon-badge' }, unread)
    ),
    open && h('div', { className: 'notif-dropdown', onClick: e => e.stopPropagation() },
      h('div', { className: 'notif-header' },
        'Notifications',
        items.length > 0 && h('span', { className: 'notif-clear', onClick: clearAll }, 'Mark all read')
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
        : items.slice(0, 12).map(n => h('div', {
            key: n.id,
            className: `notif-item ${n.read ? '' : 'unread'}`,
            onClick: () => onItemClick(n)
          },
            h('div', { className: 'notif-icon' }, KIND_ICONS[n.kind] || '•'),
            h('div', { className: 'notif-text' },
              n.title,
              n.body && h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 } }, n.body),
              h('div', { className: 'notif-time' }, timeAgo(n.createdAt))
            )
          ))
    )
  );
}

const THEMES = [
  // Brand default — matches the electric-blue loot-crate logo glow.
  { name: 'Electric', accent: '#1ea5ff', accent2: '#4dc8ff', glow: '30,165,255'  },
  { name: 'Cyan',     accent: '#22d3ee', accent2: '#67e8f9', glow: '34,211,238'  },
  { name: 'Violet',   accent: '#a78bfa', accent2: '#c4b5fd', glow: '167,139,250' },
  { name: 'Pink',     accent: '#ec4899', accent2: '#f472b6', glow: '236,72,153'  },
  { name: 'Emerald',  accent: '#10b981', accent2: '#34d399', glow: '16,185,129'  },
  { name: 'Amber',    accent: '#f59e0b', accent2: '#fbbf24', glow: '245,158,11'  },
  { name: 'Crimson',  accent: '#ef4444', accent2: '#f87171', glow: '239,68,68'   },
];

export function applyTheme(theme) {
  const r = document.documentElement;
  r.style.setProperty('--accent',        theme.accent);
  r.style.setProperty('--accent-2',      theme.accent2);
  r.style.setProperty('--accent-dim',    `rgba(${theme.glow}, 0.12)`);
  r.style.setProperty('--accent-strong', `rgba(${theme.glow}, 0.22)`);
  r.style.setProperty('--accent-border', `rgba(${theme.glow}, 0.4)`);
}

// Apply persisted theme at module load so there's no flash of the default.
(function bootstrapTheme() {
  const saved = localStorage.getItem('sb_theme') || 'Electric';
  const t = THEMES.find(t => t.name === saved) || THEMES[0];
  applyTheme(t);
})();

export function ThemePicker() {
  const [open, setOpen]   = useState(false);
  const [theme, setTheme] = useState(() => localStorage.getItem('sb_theme') || 'Electric');
  const wrapRef = React.useRef(null);

  useEffect(() => {
    const t = THEMES.find(t => t.name === theme) || THEMES[0];
    applyTheme(t);
    localStorage.setItem('sb_theme', theme);
  }, [theme]);

  useEffect(() => {
    const onDoc = e => { if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener('click', onDoc);
    return () => document.removeEventListener('click', onDoc);
  }, []);

  return h('div', { ref: wrapRef, style: { position: 'relative' } },
    h('button', {
      className: 'nav-icon-btn',
      onClick: () => setOpen(v => !v),
      title: 'Theme',
      'aria-label': 'Accent color theme picker',
      'aria-expanded': open
    }, '🎨'),
    open && h('div', { className: 'theme-dropdown', onClick: e => e.stopPropagation() },
      h('div', { className: 'theme-title' }, 'Accent Color'),
      h('div', { className: 'theme-swatches' },
        THEMES.map(t => h('div', {
          key: t.name,
          className: `theme-swatch ${theme === t.name ? 'active' : ''}`,
          style: { background: t.accent, color: t.accent },
          onClick: () => setTheme(t.name),
          title: t.name
        }))
      )
    )
  );
}

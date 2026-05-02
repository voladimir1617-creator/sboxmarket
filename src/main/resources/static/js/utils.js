// Shared utilities + React re-exports.
// React/ReactDOM are globals loaded via UMD <script> tags before any module runs.
export const React = window.React;
export const ReactDOM = window.ReactDOM;
export const { useState, useEffect, useCallback, useMemo, useRef } = React;
export const h = React.createElement;
export const createRoot = ReactDOM.createRoot;

export const API = '/api';

// ── Display-time currency conversion ──────────────────────────
// Every amount in the database is USD. At display time we apply the user's
// selected currency from localStorage (`sb_currency`) using a small static
// rate table. In production these rates should come from a Forex API; the
// table here gives users the right UX today without a network dependency.
const FX_RATES = { USD: 1.00, EUR: 0.92, GBP: 0.78, CAD: 1.37, AUD: 1.52, BRL: 5.00, JPY: 149.0 };
const FX_SYMBOL = { USD: '$', EUR: '€', GBP: '£', CAD: 'CA$', AUD: 'A$', BRL: 'R$', JPY: '¥' };

function currentCurrency() {
  try {
    const c = localStorage.getItem('sb_currency') || 'USD';
    return FX_RATES[c] ? c : 'USD';
  } catch { return 'USD'; }
}

export const fmt = (n) => {
  const code = currentCurrency();
  const rate = FX_RATES[code];
  // Guard: null / undefined / NaN / unparseable string → $0.00. Without
  // this, cards where the server hasn't yet computed a price render as
  // "$NaN" which looks broken to the user.
  const raw = Number(n);
  const num = Number.isFinite(raw) ? raw * rate : 0;
  const min = code === 'JPY' ? 0 : 2;
  return FX_SYMBOL[code] + num.toLocaleString('en-US', { minimumFractionDigits: min, maximumFractionDigits: min });
};

export const fmtCompact = (n) => {
  const code = currentCurrency();
  const rate = FX_RATES[code];
  const raw = Number(n);
  const num = Number.isFinite(raw) ? raw * rate : 0;
  const sym = FX_SYMBOL[code];
  if (num >= 1e6) return sym + (num / 1e6).toFixed(2) + 'M';
  if (num >= 1e3) return sym + (num / 1e3).toFixed(1) + 'K';
  return fmt(n);
};

/**
 * Auto-linkify http(s) URLs in a plain-text string. Returns a React
 * children-array suitable for a single text-rendering element. Each URL
 * match becomes a safe `<a target="_blank" rel="noopener noreferrer">`;
 * everything else is preserved as text. React's children-array escaping
 * keeps it XSS-safe — never injects raw HTML, never uses innerHTML.
 *
 * Used by trade-chat messages, stall bios, support-thread messages —
 * all places where a user pastes a URL the recipient needs to click.
 *
 * @param {string} text   the raw plain-text body
 * @param {string} keyPrefix   unique prefix for the React key on each anchor
 *                             (helps when the same text might render in
 *                             multiple components on the same page)
 */
export function linkifyText(text, keyPrefix = 'lnk') {
  const src = text || '';
  const re = /(https?:\/\/[^\s<>"']+)/g;
  const parts = [];
  let last = 0, match;
  while ((match = re.exec(src)) !== null) {
    if (match.index > last) parts.push(src.slice(last, match.index));
    parts.push(h('a', {
      key: keyPrefix + '-' + match.index,
      href: match[0],
      target: '_blank',
      // Batch 713 — `nofollow ugc` on user-generated-content links so
      // a bad actor spamming URLs in reviews / trade messages /
      // support tickets can't leverage SkinBox's SEO authority to
      // boost their page rank. `ugc` is Google's specific UGC hint.
      // `noopener noreferrer` keeps the existing tab-isolation
      // + referrer-privacy guarantees.
      rel: 'nofollow ugc noopener noreferrer',
      style: { color: 'var(--accent)', textDecoration: 'underline', wordBreak: 'break-all' }
    }, match[0]));
    last = match.index + match[0].length;
  }
  if (last < src.length) parts.push(src.slice(last));
  return parts.length > 0 ? parts : src;
}

export const timeAgo = (ms) => {
  const diff = Date.now() - ms;
  if (diff < 60000) return 'Just now';
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
  return Math.floor(diff / 86400000) + 'd ago';
};

export function discountPct(listingPrice, steamPrice) {
  if (!steamPrice || !listingPrice) return 0;
  const s = parseFloat(steamPrice), p = parseFloat(listingPrice);
  if (s <= 0 || p >= s) return 0;
  return Math.round((1 - p / s) * 100);
}

/** Wrap the matched substring of `text` in a `<mark>` element so a user
 *  scanning a filtered list (FAQ, Help Center, Database search) can see
 *  exactly where their query hit. Case-insensitive. Empty / missing
 *  query is a no-op that returns the original string unchanged. */
export function highlightMatch(text, query) {
  if (!text || !query) return text || '';
  const q = String(query).trim();
  if (q.length === 0) return text;
  const lower = String(text).toLowerCase();
  const qLower = q.toLowerCase();
  const parts = [];
  let cursor = 0;
  let idx;
  while ((idx = lower.indexOf(qLower, cursor)) !== -1) {
    if (idx > cursor) parts.push(text.slice(cursor, idx));
    parts.push(h('mark', {
      key: 'm-' + idx,
      style: { background: 'rgba(30,165,255,0.35)', color: 'inherit', padding: '0 1px', borderRadius: 2 }
    }, text.slice(idx, idx + q.length)));
    cursor = idx + q.length;
  }
  if (cursor < text.length) parts.push(text.slice(cursor));
  return parts.length === 1 ? parts[0] : parts;
}

// Kicks off the Steam OpenID flow. Before navigating to the login
// endpoint, stash the current pathname+search in sessionStorage so the
// client can bounce the user back to where they were after the Steam
// callback redirects to `/?login=success`. Only stashes real pages —
// ignores `/` and URLs that already carry `login=...` so a post-login
// reload doesn't loop.
// Global DOM-based toast. The App component has its own React-controlled
// toast for buy/sell flows, but the marketplace has dozens of modals and
// handlers that run outside the App render tree (nav-widgets, async
// handlers inside modal components, /loadout share links) — threading
// `showToast` as a prop through all of them was not worth the churn.
// This helper mounts a single-slot toast into #sb-toast-host, reuses the
// same `.sale-toast` CSS, and auto-dismisses after 4.5s. Click to dismiss.
// On any unexpected failure, falls back to window.alert so the user is
// never silently left without feedback.
export function toast(text, kind = 'ok') {
  try {
    let host = document.getElementById('sb-toast-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'sb-toast-host';
      // Batch 774 — a11y: screen readers announce changes inside a
      // role=status / aria-live=polite region without stealing focus.
      // Error toasts stay on the same region rather than getting their
      // own assertive live-region — assertive is a heavier interrupt
      // and the error copy is already self-explanatory on screen.
      host.setAttribute('role', 'status');
      host.setAttribute('aria-live', 'polite');
      host.setAttribute('aria-atomic', 'true');
      document.body.appendChild(host);
    }
    while (host.firstChild) host.removeChild(host.firstChild);

    const el = document.createElement('div');
    // Batch 925 — support a third toast kind: `warn`. Same live-region
    // semantics as ok/err but renders amber with ⚠, distinguishing
    // "not-success-but-not-error" messages (Stripe cancel, session
    // timeout) from genuine wins.
    const isErr  = kind === 'err';
    const isWarn = kind === 'warn';
    el.className = 'sale-toast' + (isErr ? ' err' : (isWarn ? ' warn' : ''));

    const thumb = document.createElement('div');
    thumb.className = 'sale-toast-thumb';
    thumb.style.background = isErr ? 'var(--red-dim)' : isWarn ? 'rgba(251,191,36,0.15)' : 'var(--accent-dim)';
    thumb.style.color      = isErr ? 'var(--red)'     : isWarn ? '#fbbf24'               : 'var(--accent)';
    thumb.textContent      = isErr ? '✕'              : isWarn ? '⚠'                    : '✓';

    const textWrap = document.createElement('div');
    textWrap.className = 'sale-toast-text';
    const line = document.createElement('div');
    line.className = 'sale-toast-line2';
    line.textContent = text;
    line.style.whiteSpace = 'normal';
    line.style.overflow = 'visible';
    textWrap.appendChild(line);

    el.appendChild(thumb);
    el.appendChild(textWrap);
    host.appendChild(el);

    const timer = setTimeout(() => { if (el.parentNode) el.parentNode.removeChild(el); }, 4500);
    el.addEventListener('click', () => {
      clearTimeout(timer);
      if (el.parentNode) el.parentNode.removeChild(el);
    });
  } catch (_) {
    try { window.alert(text); } catch (_) { /* nothing else to try */ }
  }
}

export function signInWithSteam() {
  // Build the post-login destination from the current page so the user
  // lands back where they triggered auth instead of being dumped at /.
  // Codex 18:07Z owner finding: clicking Sign In on /sell, /profile,
  // /watchlist etc threw away the user's intent and conversion suffered.
  // We pass `next` to the server (authoritative — sanitized server-side)
  // AND keep the legacy sessionStorage fallback so a failed/aborted login
  // still has a backup signal for the SPA welcome-toast logic.
  let nextPath = '/';
  try {
    const cur = window.location.pathname + window.location.search + window.location.hash;
    const params = new URLSearchParams(window.location.search);
    const isLoginLanding = params.has('login');
    const isRootNoParams = window.location.pathname === '/' && !window.location.search && !window.location.hash;
    if (!isLoginLanding && !isRootNoParams) {
      sessionStorage.setItem('sb_login_return_url', cur);
      nextPath = cur;
    }
  } catch (_) { /* sessionStorage unavailable — still fire the redirect */ }
  // Only append `?next=...` when the destination differs from `/` so the
  // server doesn't get a noise param for the trivial home-page case.
  const url = (nextPath && nextPath !== '/')
    ? '/api/auth/steam/login?next=' + encodeURIComponent(nextPath)
    : '/api/auth/steam/login';
  window.location.href = url;
}

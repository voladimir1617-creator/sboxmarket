// Tiny history-API router — no framework, ~40 lines of runtime.
//
// Shape of a parsed route:
//   { name: 'item', params: { id: '5' }, path: '/item/5' }
//
// Registered patterns use colon prefixes for captures:
//   /item/:id    →  { id: '5' }
//   /stall/:id   →  { id: '76561...' }
//
// Use via the `useRoute` hook in a component — it subscribes to
// popstate events and re-renders the caller whenever the URL changes.
// Callers navigate with `navigate('/item/5')`.

import { React, useState, useEffect } from './utils.js';

const ROUTES = [
  // CSFloat-1:1 — `/` is a marketing landing page (home), distinct from
  // `/market` which is the bare marketplace grid. The home route still
  // renders the same toolbar+sidebar+grid scaffolding underneath, but
  // App prepends a hero section + featured-tabs rail above it. Anything
  // that previously routed to `/?sort=…` now lands on `home` and the
  // hero shows; navigating into `/market?sort=…` skips the hero.
  { name: 'home',          pattern: /^\/?$/                                         },
  { name: 'market',        pattern: /^\/market\/?$/                                 },
  { name: 'market',        pattern: /^\/search\/?$/                                 },
  { name: 'cart',          pattern: /^\/cart\/?$/                                   },
  { name: 'database',      pattern: /^\/db\/?$/                                     },
  // `/db` is the canonical path (paths.database() emits it), but the
  // human-readable `/database` SEO URL and any external links pointing at
  // it must resolve to the SAME 'database' route instead of 404ing. Alias
  // only — paths.database() is unchanged, so internal nav still uses /db.
  { name: 'database',      pattern: /^\/database\/?$/                               },
  { name: 'item',          pattern: /^\/item\/(\d+)\/?$/,          keys: ['id']     },
  // /stall/:id must be a numeric user id (matches Long PK on User row).
  // Pre-fix the regex matched any string, so `/stall/abc` mounted the
  // StallModal and fired 3 API calls that all 400'd before the 404 panel
  // rendered. Now `abc` falls through to the SPA 404 view directly with
  // zero network noise.
  { name: 'stall',         pattern: /^\/stall\/(\d+)\/?$/,         keys: ['id']     },
  { name: 'loadouts',      pattern: /^\/loadout\/?$/                                },
  { name: 'loadout',       pattern: /^\/loadout\/(\d+)\/?$/,       keys: ['id']     },
  { name: 'profile',       pattern: /^\/profile\/?$/                                },
  { name: 'profile',       pattern: /^\/profile\/(personal|listings|transactions|buyorders|autobids|trades|offers|reviews|support|developers)\/?$/, keys: ['tab'] },
  { name: 'wallet',        pattern: /^\/wallet\/?$/                                 },
  { name: 'wallet',        pattern: /^\/wallet\/(deposit|withdraw|history)\/?$/, keys: ['tab'] },
  { name: 'watchlist',     pattern: /^\/watchlist\/?$/                              },
  { name: 'watchlist',     pattern: /^\/watchlist\/(all|drops)\/?$/, keys: ['tab'] },
  { name: 'sell',          pattern: /^\/sell\/?$/                                   },
  { name: 'mystall',       pattern: /^\/me\/stall\/?$/                              },
  { name: 'mystall',       pattern: /^\/me\/stall\/(active|sold|analytics)\/?$/, keys: ['tab'] },
  { name: 'offers',        pattern: /^\/offers\/?$/                                 },
  { name: 'offers',        pattern: /^\/offers\/(incoming|outgoing)\/?$/, keys: ['tab'] },
  { name: 'buyorders',     pattern: /^\/buy-orders\/?$/                             },
  { name: 'notifications', pattern: /^\/notifications\/?$/                          },
  { name: 'support',       pattern: /^\/support\/?$/                                },
  { name: 'help',          pattern: /^\/help\/?$/                                   },
  { name: 'faq',           pattern: /^\/faq\/?$/                                    },
  { name: 'settings',      pattern: /^\/settings\/?$/                               },
  { name: 'affiliate',     pattern: /^\/affiliate\/?$/                              },
  { name: 'admin',         pattern: /^\/admin\/?$/                                  },
  { name: 'csr',           pattern: /^\/csr\/?$/                                    },
];

/** Parse the current `location.pathname` against the registered patterns. */
export function parsePath(path) {
  const clean = (path || '/').split('?')[0].split('#')[0];
  for (const r of ROUTES) {
    const m = clean.match(r.pattern);
    if (!m) continue;
    const params = {};
    (r.keys || []).forEach((k, i) => { params[k] = m[i + 1]; });
    return { name: r.name, params, path: clean };
  }
  // Anything else = 404 route. App.jsx renders a friendly not-found panel
  // instead of silently falling back to the market.
  return { name: 'notfound', params: {}, path: clean };
}

// Count pushState calls in the current session so `closeToPrevious()` knows
// whether `history.back()` is safe (returns the user to an in-site URL we
// actually pushed) or whether we'd accidentally navigate out to the referrer.
// replaceState doesn't add a stack entry so it doesn't count. Decrements on
// popstate so browser Back keeps the counter in sync — without that the
// counter would drift up and `closeToPrevious` could try to back past the
// origin entry (bouncing the user to the referrer).
let internalPushes = 0;

// True only while navigate()/replace is firing its own synthetic
// PopStateEvent to notify subscribers. The popstate listener below
// decrements `internalPushes` on *browser* back/forward — but navigate()
// dispatches an identical 'popstate' event, which the listener cannot
// otherwise tell apart from a real one. Without this guard the listener
// cancelled every forward navigate()'s increment in the same tick, so
// `internalPushes` was stuck at ~0 and `closeToPrevious()` always fell
// through to its fallback (wiping URL-hydrated filter state on modal
// close instead of going back to the previous in-site URL).
let dispatchingInternalPop = false;

// Scroll-restoration map — keyed by URL, value is the .layout scrollTop
// recorded just before a forward navigate(). On browser-back (popstate)
// we restore the captured value for the target URL so the user lands
// exactly where they left off (CSFloat parity). Cleared on a forward
// navigate to the same URL so a refresh starts fresh. Capped at 32
// entries so a long browsing session doesn't accumulate unbounded state.
const scrollByUrl = new Map();
const SCROLL_CAP = 32;
function snapshotScroll() {
  const layout = document.querySelector('.layout');
  const key = window.location.pathname + window.location.search;
  const top = layout ? layout.scrollTop : window.scrollY;
  if (top > 0) {
    if (scrollByUrl.size >= SCROLL_CAP) {
      // Evict oldest entry (insertion-order preserved by Map).
      const first = scrollByUrl.keys().next().value;
      if (first !== undefined) scrollByUrl.delete(first);
    }
    scrollByUrl.set(key, top);
  }
}
function restoreScrollFor(key) {
  const saved = scrollByUrl.get(key);
  const layout = document.querySelector('.layout');
  // Next frame so the route's component has rendered and the scrollHeight
  // has grown enough that the target scrollTop is reachable.
  requestAnimationFrame(() => {
    if (saved && saved > 0) {
      if (layout) layout.scrollTop = saved;
      window.scrollTo({ top: saved, behavior: 'instant' });
    } else {
      if (layout) layout.scrollTop = 0;
      window.scrollTo({ top: 0, behavior: 'instant' });
    }
  });
}
if (typeof window !== 'undefined') {
  // Disable the browser's built-in restoration so our map owns the behaviour;
  // otherwise on a hard refresh Chrome's own restoration can race ours.
  if ('scrollRestoration' in window.history) {
    try { window.history.scrollRestoration = 'manual'; } catch (_) {}
  }
  window.addEventListener('popstate', () => {
    // Only a *real* browser back/forward should decrement the counter.
    // navigate() fires its own synthetic 'popstate'; skip that one or it
    // would immediately cancel the matching pushState increment.
    if (!dispatchingInternalPop && internalPushes > 0) internalPushes--;
    // Scroll-restore fix (2026-05-20): only restore on a *real* browser
    // back/forward. navigate() dispatches an identical synthetic
    // 'popstate', but it already owns forward-navigation scroll itself
    // (clears the saved entry + jumps to top after this listener runs).
    // Letting restoreScrollFor() run during the synthetic dispatch
    // queued a second, redundant requestAnimationFrame that raced
    // navigate()'s own scrollTop=0 write. Skip it — same guard the
    // counter decrement already uses.
    if (dispatchingInternalPop) return;
    // On browser back/forward, restore the scroll position we saved for
    // the URL we're arriving at.
    const key = window.location.pathname + window.location.search;
    restoreScrollFor(key);
  });
}

/** Imperative navigate — pushes a new entry into history and fires popstate. */
export function navigate(path, replace = false) {
  if (!path) return;
  const current = window.location.pathname + window.location.search + window.location.hash;
  if (path === current) return;
  // Boss QA cycle 31 ship #22 — top progress bar. Kick the bar before
  // we touch history so the user sees feedback immediately on click,
  // even on a route whose render is synchronous and finishes < 1 frame.
  routeProgressKick();
  // Snapshot the OUTGOING url's scroll before we push the new entry, so
  // browser-back restores this exact position.
  if (!replace) snapshotScroll();
  if (replace) history.replaceState({}, '', path);
  else        { history.pushState({}, '', path); internalPushes++; }
  // Notify subscribers (useRoute, etc.). Flag this dispatch so the
  // popstate listener doesn't mistake it for a browser back/forward and
  // decrement the pushState counter. Listeners run synchronously during
  // dispatchEvent, so resetting the flag right after is safe.
  dispatchingInternalPop = true;
  try { window.dispatchEvent(new PopStateEvent('popstate')); }
  finally { dispatchingInternalPop = false; }
  // Forward navigate always lands at the top — CSFloat behaviour. Clear
  // any stale saved position for the new URL so a later back-to-back
  // doesn't accidentally restore a previous session's scroll.
  const newKey = path.split('#')[0];
  scrollByUrl.delete(newKey);
  const layout = document.querySelector('.layout');
  if (layout) layout.scrollTop = 0;
  window.scrollTo({ top: 0, behavior: 'instant' });
}

/**
 * Close-a-modal helper: goes back one step when we know the previous entry
 * is an in-site URL (we pushed it ourselves), otherwise navigates to a
 * fallback path. Used by modal onClose handlers so closing `/item/:id`
 * from a search returns to `/?q=hat` instead of wiping the query state.
 *
 * Why this matters: `navigate(paths.market())` always lands on bare `/`,
 * which means any URL-hydrated filter (search, category, rarity, listingType)
 * is lost on modal close. The pushState counter lets us safely prefer
 * `history.back()` when it's going to stay within the app.
 */
export function closeToPrevious(fallback = '/') {
  // internalPushes decrements inside the popstate listener triggered by
  // history.back(), so no manual decrement here.
  if (internalPushes > 0) {
    window.history.back();
    return;
  }
  navigate(fallback);
}

/** Build URL paths for common destinations. Keeps magic strings out of components. */
export const paths = {
  // CSFloat-1:1 — `/` is the marketing home (hero + featured), `/market`
  // is the bare marketplace grid. Most nav, modal-close callbacks, and
  // "browse market" CTAs want the grid (so they go to `/market`); only
  // the brand logo and explicit "go home" actions go to `/`.
  home:          ()     => '/',
  market:        ()     => '/market',
  database:      ()     => '/db',
  item:          (id)   => `/item/${id}`,
  stall:         (id)   => `/stall/${id}`,
  loadouts:      ()     => '/loadout',
  loadout:       (id)   => `/loadout/${id}`,
  profile:       ()     => '/profile',
  wallet:        ()     => '/wallet',
  cart:          ()     => '/cart',
  watchlist:     ()     => '/watchlist',
  sell:          ()     => '/sell',
  mystall:       ()     => '/me/stall',
  offers:        ()     => '/offers',
  buyorders:     ()     => '/buy-orders',
  notifications: ()     => '/notifications',
  support:       ()     => '/support',
  help:          ()     => '/help',
  faq:           ()     => '/faq',
  settings:      ()     => '/settings',
  affiliate:     ()     => '/affiliate',
  admin:         ()     => '/admin',
  csr:           ()     => '/csr',
};

/** React hook — subscribes to popstate so the component re-renders on URL change. */
export function useRoute() {
  const [route, setRoute] = useState(() => parsePath(window.location.pathname));
  useEffect(() => {
    const onPop = () => setRoute(parsePath(window.location.pathname));
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);
  return route;
}

/**
 * Intercept plain anchor clicks so internal links use the router instead of
 * triggering a full page reload. Mount once in App.
 */
export function installAnchorInterceptor() {
  document.addEventListener('click', (e) => {
    if (e.defaultPrevented) return;
    if (e.button !== 0) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    let el = e.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el) return;
    const href = el.getAttribute('href');
    if (!href) return;
    if (href.startsWith('http') || href.startsWith('//') || href.startsWith('mailto:')) return;
    if (el.getAttribute('target') === '_blank') return;
    if (href.startsWith('#')) return;          // hash links left alone
    if (href.startsWith('/api/')) return;      // backend auth links go via the browser
    if (href.startsWith('/h2-console') || href.startsWith('/swagger-ui')) return;
    // Reject any non-path scheme that slipped past the http/mailto check.
    // Without this, `href="javascript:…"`, `tel:`, `data:`, `blob:`,
    // `vbscript:`, `file:` etc. fall through to navigate(), which calls
    // history.pushState() with a non-same-origin URL and throws a
    // SecurityError that masks the original click intent. Let the
    // browser handle every non-/ href naturally instead.
    if (!href.startsWith('/')) return;
    // Any path that carries a file extension (/legal/terms.html,
    // /favicon.ico, /img/logo.png, /css/styles.css, downloadable PDFs,
    // etc.) needs to go through the browser to hit the real static
    // asset — if we call navigate() the SPA router treats it as an
    // unknown app route and renders the 404 page.
    const lastSeg = href.split('/').pop().split('?')[0];
    if (/\.[a-zA-Z0-9]{1,5}$/.test(lastSeg)) return;
    e.preventDefault();
    navigate(href);
  });
}

// ── Top route progress bar ─────────────────────────────────────────
// Modern SPA pattern: thin coloured bar at the very top of the viewport
// that flashes during navigation so users know their click registered.
// Pure CSS animation, no library — kicked by navigate() and the browser
// back/forward popstate listener. Single-flight: a second navigation
// while the bar is still travelling restarts the keyframe.
let _progressEl = null;
function routeProgressKick() {
  try {
    if (!_progressEl) {
      _progressEl = document.createElement('div');
      _progressEl.id = 'sb-route-progress';
      _progressEl.setAttribute('aria-hidden', 'true');
      document.body.appendChild(_progressEl);
    }
    // Restart animation by toggling the class — `void offsetWidth`
    // forces a reflow so the keyframe re-plays from 0%.
    _progressEl.classList.remove('on');
    void _progressEl.offsetWidth;
    _progressEl.classList.add('on');
  } catch (_) { /* no-op — never let a UI affordance break navigation */ }
}
// Browser back/forward should also surface the bar so the same feedback
// is consistent whether the user clicks an in-app link or hits ⌘[.
// Double-kick fix (2026-05-20): navigate() already calls
// routeProgressKick() directly, then dispatches a synthetic 'popstate'.
// Without this guard that synthetic event re-kicked the bar a second
// time in the same tick (restarting the keyframe from 0%). Skip the
// synthetic dispatch so the bar fires exactly once per navigation.
if (typeof window !== 'undefined') {
  window.addEventListener('popstate', () => {
    if (dispatchingInternalPop) return;
    routeProgressKick();
  });
}

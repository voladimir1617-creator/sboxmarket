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
  { name: 'market',        pattern: /^\/?$/                                         },
  { name: 'market',        pattern: /^\/search\/?$/                                 },
  { name: 'cart',          pattern: /^\/cart\/?$/                                   },
  { name: 'database',      pattern: /^\/db\/?$/                                     },
  { name: 'item',          pattern: /^\/item\/(\d+)\/?$/,          keys: ['id']     },
  { name: 'stall',         pattern: /^\/stall\/([^/]+)\/?$/,       keys: ['id']     },
  { name: 'loadouts',      pattern: /^\/loadout\/?$/                                },
  { name: 'loadout',       pattern: /^\/loadout\/(\d+)\/?$/,       keys: ['id']     },
  { name: 'profile',       pattern: /^\/profile\/?$/                                },
  { name: 'wallet',        pattern: /^\/wallet\/?$/                                 },
  { name: 'watchlist',     pattern: /^\/watchlist\/?$/                              },
  { name: 'sell',          pattern: /^\/sell\/?$/                                   },
  { name: 'mystall',       pattern: /^\/me\/stall\/?$/                              },
  { name: 'offers',        pattern: /^\/offers\/?$/                                 },
  { name: 'buyorders',     pattern: /^\/buy-orders\/?$/                             },
  { name: 'notifications', pattern: /^\/notifications\/?$/                          },
  { name: 'support',       pattern: /^\/support\/?$/                                },
  { name: 'help',          pattern: /^\/help\/?$/                                   },
  { name: 'faq',           pattern: /^\/faq\/?$/                                    },
  { name: 'settings',      pattern: /^\/settings\/?$/                               },
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

/** Imperative navigate — pushes a new entry into history and fires popstate. */
export function navigate(path, replace = false) {
  if (!path) return;
  const current = window.location.pathname + window.location.search + window.location.hash;
  if (path === current) return;
  if (replace) history.replaceState({}, '', path);
  else          history.pushState({}, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
  // CSFloat-style: scroll the main column to top on page change
  const layout = document.querySelector('.layout');
  if (layout) layout.scrollTop = 0;
  window.scrollTo({ top: 0, behavior: 'instant' });
}

/** Build URL paths for common destinations. Keeps magic strings out of components. */
export const paths = {
  market:        ()     => '/',
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

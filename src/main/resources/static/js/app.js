// Top-level App component + ErrorBoundary.
// Owns marketplace state, wires modals, handles Stripe/Steam redirect return.
import { h, React, useState, useEffect, useCallback, useMemo, fmt } from './utils.js';
import {
  fetchListings, fetchListingsForItem, fetchHistory, buyListing,
  fetchWallet, fetchTransactions, fetchMe, logoutSteam, confirmDeposit, makeOffer,
  adminCheck, csrCheck, checkoutCart, fetchPublicStall, fetchReviewsForUser
} from './api.js';
import { ItemImage, MaterialIcon } from './primitives.js';
import { GridCard, ListingRow, TrendCard } from './cards.js';
import { ChatPanel } from './chat.js';
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
        h('a', { href: '#' }, 'Fees & Pricing'),
        h('a', { href: '#' }, 'API Docs')
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
        '© ', new Date().getFullYear(), ' SkinBox · Not affiliated with Facepunch Studios. s&box is a trademark of Facepunch Ltd.'),
      h('div', { className: 'site-footer-meta' },
        h('span', null, 'All prices in USD'),
        h('span', { className: 'dot' }, '·'),
        h('span', null, 'Stripe-secured payments'),
        h('span', { className: 'dot' }, '·'),
        h('span', null, 'Steam OpenID auth')
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
  const [searchInput, setSearchInput]   = useState('');
  const [search, setSearch]             = useState('');
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  const [category, setCategory]         = useState('All');
  const [rarity, setRarity]             = useState('All');
  const [sort, setSort]                 = useState('price_desc');
  const [minPrice, setMinPrice]         = useState('');
  const [maxPrice, setMaxPrice]         = useState('');

  // item detail
  const [selected, setSelected]         = useState(null);
  const [modalLoading, setModalLoading] = useState(false);

  // wallet
  const [wallet, setWallet]             = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [walletOpen, setWalletOpen]     = useState(false);
  const [walletInitialTab, setWalletInitialTab] = useState('deposit');

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
  const [chatHidden, setChatHidden]     = useState(false);
  const [heroTab, setHeroTab]           = useState('topDeals');
  const [feeInput, setFeeInput]         = useState('100');

  // "Preselected" item the BuyOrdersModal uses when opened from ItemModal.
  // Not part of the URL — ephemeral state that lives only while the
  // buy-orders route is active for this specific item.
  const [preselectedBuyItem, setPreselectedBuyItem] = useState(null);

  // Public stall page data — loaded whenever we hit /stall/:id.
  // Reviews are fetched in parallel with the listings payload so the
  // rating chip + "Recent reviews" block render together.
  const [stallData, setStallData] = useState(null);
  const [stallReviews, setStallReviews] = useState(null);
  useEffect(() => {
    if (routeName !== 'stall' || !route.params?.id) {
      setStallData(null); setStallReviews(null); return;
    }
    let alive = true;
    Promise.all([
      fetchPublicStall(route.params.id),
      fetchReviewsForUser(route.params.id)
    ]).then(([stall, reviews]) => {
      if (!alive) return;
      setStallData(stall);
      setStallReviews(reviews);
    });
    return () => { alive = false; };
  }, [routeName, route.params?.id]);

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
  const cartCount = cart.length;
  const cartTotal = useMemo(() => cart.reduce((s, it) => s + (parseFloat(it.price) || 0), 0), [cart]);
  const addToCart = (listing) => {
    setCart(c => {
      if (c.find(x => x.id === listing.id)) return c;
      return [...c, {
        id:    listing.id,
        name:  listing.item?.name,
        price: listing.price,
        thumb: listing.item?.imageUrl || null
      }];
    });
  };
  const removeFromCart = (id) => setCart(c => c.filter(x => x.id !== id));
  const clearCart = () => setCart([]);
  const doCheckout = async () => {
    if (cart.length === 0) return;
    const ids = cart.map(x => x.id);
    const res = await checkoutCart(ids);
    if (res && res.error) {
      showToast(res.error, 'err');
    } else if (res && res.results) {
      const ok = res.successful || 0;
      const fail = res.failed || 0;
      showToast(`Bought ${ok} item${ok === 1 ? '' : 's'}${fail > 0 ? ` · ${fail} failed` : ''}`, fail > 0 ? 'err' : 'ok');
      // Remove everything that succeeded
      const failedIds = new Set(res.results.filter(r => r.status !== 'OK').map(r => r.listingId));
      setCart(c => c.filter(x => failedIds.has(x.id)));
      await loadWallet();
      load();
      navigate(paths.market());
    } else {
      showToast('Checkout failed', 'err');
    }
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
  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const data = await fetchListings({
        sort,
        category: category !== 'All' ? category : null,
        rarity:   rarity !== 'All'   ? rarity   : null,
        minPrice: minPrice || null,
        maxPrice: maxPrice || null,
        search:   search   || null
      });
      if (silent) {
        setListings(prev => {
          const prevIds = new Set(prev.map(l => l.id));
          const nextIds = new Set(data.map(l => l.id));
          const soldCount = [...prevIds].filter(id => !nextIds.has(id)).length;
          if (soldCount > 0) {
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
        // Resolve the actual item object from the first listing (or by ID if we
        // already have the listings loaded).
        const item = itemListings[0]?.item ||
                     listings.find(l => String(l.item?.id) === String(route.params.id))?.item;
        if (item) setSelected({ item, listings: itemListings, history });
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
      showToast('Purchase complete · added to your inventory', 'ok');
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
    const byItem = {};
    listings.filter(l => l?.item).forEach(l => {
      const current = byItem[l.item.id];
      if (!current || parseFloat(l.price) < parseFloat(current.listing.price)) {
        byItem[l.item.id] = { listing: l, count: 1 };
      }
      if (current) current.count++;
    });
    // Second pass — recount listings per item so the "1 listing" badges
    // are accurate even after we swap the representative listing.
    const counts = {};
    listings.forEach(l => { if (l?.item) counts[l.item.id] = (counts[l.item.id] || 0) + 1; });
    return Object.values(byItem)
      .map(e => ({ ...e.listing, __listingCount: counts[e.listing.item.id] || 1 }));
  }, [listings]);

  // Full-page routes vs overlay routes. CSFloat-style: most destinations
  // are real pages that replace the marketplace body; only the item detail
  // stays as a slide-in overlay on top of the grid.
  const FULL_PAGE_ROUTES = ['profile','wallet','cart','help','faq','watchlist','database','loadouts','loadout','sell','mystall','offers','buyorders','notifications','support','settings','admin','csr','notfound','stall'];
  const isFullPage = FULL_PAGE_ROUTES.includes(routeName);

  return h('div', {
    className: `site-root ${chatHidden ? 'chat-hidden' : ''} ${isFullPage ? 'full-page-mode' : ''}`
  },
    /* Chat removed — was placeholder with fake messages */

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
        me && wallet && h('button', {
          className: 'wallet-btn',
          onClick: (e) => {
            // Ctrl/meta-click on the balance toggles privacy mode, like CSFloat
            if (e.ctrlKey || e.metaKey) { e.preventDefault(); setPrivacy(p => !p); return; }
            navigate(paths.wallet());
          },
          title: 'Open wallet · Ctrl-click to toggle privacy'
        },
          h('div', { className: 'wallet-btn-icon' }, '$'),
          h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', lineHeight: 1.1 } },
            h('span', { className: 'wallet-btn-label' }, 'Balance'),
            h('span', { className: 'wallet-btn-amt' }, privacy ? '$•••••' : fmt(wallet.balance))
          )
        ),
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
                h('a', { className: 'user-menu-item', href: '/profile',            onClick: () => setMenuOpen(false) }, h(MaterialIcon, { name: 'swap_horiz', size: 18 }), 'Trades'),
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
              onChange: e => setSearchInput(e.target.value),
              'aria-label': 'Search listings'
            }),
            searchInput && h('button', {
              className: 'search-clear',
              onClick: () => { setSearchInput(''); setSearch(''); },
              title: 'Clear search',
              'aria-label': 'Clear search'
            }, '✕')
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
          ),
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
                    onToggleStar: toggleStar
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
      onClose: () => navigate(paths.market()),
      onRefresh: loadWallet,
      initialTab: walletInitialTab
    }),
    routeName === 'stall' && h(InfoModal, {
      title: stallData?.seller?.displayName
        ? `${stallData.seller.displayName}'s Stall`
        : 'Stall',
      onClose: () => navigate(paths.market())
    },
      stallData === null
        ? h('div', { className: 'spinner' })
        : h('div', null,
            h('div', { className: 'stall-hero' },
              h('div', { className: 'stall-avatar' },
                stallData.seller.avatarUrl
                  ? h('img', { src: stallData.seller.avatarUrl, alt: stallData.seller.displayName })
                  : (stallData.seller.displayName || 'U').substring(0, 2).toUpperCase()
              ),
              h('div', null,
                h('div', { className: 'stall-name' }, stallData.seller.displayName || 'Player'),
                h('div', { className: 'stall-meta' },
                  stallData.count, ' active listings · joined ',
                  stallData.seller.joinedAt ? new Date(stallData.seller.joinedAt).toLocaleDateString() : '—'
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
                )
              )
            ),
            stallData.count === 0
              ? h('div', { className: 'empty-inline' },
                  h('div', { className: 'empty-icon' }, '🏪'),
                  h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } },
                    'This seller has no active listings right now.'))
              : h('div', { className: 'listing-grid' },
                  stallData.listings.map(l => h(GridCard, {
                    key: l.id,
                    listing: l,
                    onClick: () => navigate(paths.item(l.item.id)),
                    starred: watchlist.includes(l.item.id),
                    onToggleStar: toggleStar
                  }))
                ),
            // Recent reviews strip — only shows when the seller has feedback.
            // Reviews are trade-anchored so every entry is a real buyer who
            // actually traded with this user (see ReviewService.leaveReview).
            stallReviews && stallReviews.length > 0 && h('div', { className: 'stall-reviews' },
              h('div', { className: 'stall-reviews-head' },
                h('span', { className: 'section-title-dot' }),
                `Recent reviews (${stallReviews.length})`
              ),
              h('div', { className: 'stall-reviews-list' },
                stallReviews.slice(0, 10).map(r => h('div', { key: r.id, className: 'stall-review' },
                  h('div', { className: 'stall-review-head' },
                    h('span', { className: 'stall-review-stars' }, '★'.repeat(r.rating) + '☆'.repeat(5 - r.rating)),
                    h('span', { className: 'stall-review-from' }, r.fromDisplayName || 'Anonymous'),
                    h('span', { className: 'stall-review-time' },
                      new Date(r.createdAt).toLocaleDateString()
                    )
                  ),
                  r.itemName && h('div', { className: 'stall-review-item' }, '↳ ' + r.itemName),
                  r.comment && h('div', { className: 'stall-review-body' }, r.comment)
                ))
              )
            )
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
            h('div', { style: { fontSize: 14, color: 'var(--text-secondary)' } }, 'Your cart is empty. Click any listing and add it to the cart.'))
        : h('div', null,
            h('div', { className: 'cart-list' },
              cart.map(it => h('div', { key: it.id, className: 'cart-row' },
                h('div', { className: 'cart-thumb' }, it.thumb
                  ? h('img', { src: it.thumb, alt: it.name })
                  : h('span', null, '📦')),
                h('div', { className: 'cart-info' },
                  h('div', { className: 'cart-name' }, it.name),
                  h('div', { className: 'cart-id' }, 'Listing #' + it.id)
                ),
                h('div', { className: 'cart-price' }, fmt(it.price)),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '6px 10px', fontSize: 11 },
                  onClick: () => removeFromCart(it.id)
                }, '✕')
              ))
            ),
            h('div', { className: 'cart-footer' },
              h('div', { className: 'cart-total' },
                h('span', { className: 'cart-total-label' }, 'Total'),
                h('span', { className: 'cart-total-val' }, fmt(cartTotal))
              ),
              h('div', { style: { display: 'flex', gap: 10 } },
                h('button', { className: 'btn btn-ghost', style: { border: '1px solid var(--border)' }, onClick: clearCart }, 'Clear'),
                h('button', { className: 'btn btn-accent', onClick: doCheckout }, 'Buy Now · ' + fmt(cartTotal))
              )
            )
          )
    ),
    routeName === 'faq'           && h(FaqModal,        { onClose: () => navigate(paths.market()) }),
    routeName === 'settings'      && h(SettingsModal,   { onClose: () => navigate(paths.market()) }),
    routeName === 'profile'       && h(ProfileModal,    { onClose: () => navigate(paths.market()), me, wallet, transactions, onRefresh: () => { loadWallet(); } }),
    routeName === 'sell'          && h(SellItemsModal,  { onClose: () => navigate(paths.market()), me, onRefresh: load }),
    routeName === 'mystall'       && h(MyStallModal,    { onClose: () => navigate(paths.market()), me, onRefresh: load }),
    routeName === 'offers'        && h(OffersModal,     { onClose: () => navigate(paths.market()), me, onRefresh: () => { load(); loadWallet(); } }),
    routeName === 'watchlist'     && h(WatchlistModal,  {
      onClose: () => navigate(paths.market()),
      watchlist, allListings: listings,
      onOpen: openModal, onToggleStar: toggleStar
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
       navigates back to /, so back/forward work naturally. */
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

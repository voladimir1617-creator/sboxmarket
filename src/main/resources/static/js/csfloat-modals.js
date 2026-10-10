// CSFloat-style feature modals: Database, Buy Orders, Loadout Lab,
// Trade-Up Calculator, Notifications feed, Auction bid panel.
//
// Every modal follows the same pattern as ./modals.js — narrow prop surface,
// uses InfoModal as the shell, calls into ./api.js for I/O.
import { BRAND } from './brand.js';
import { h, useState, useEffect, useCallback, useMemo, useRef, fmt, timeAgo, signInWithSteam, toast, highlightMatch, currencySymbol, fxToUsd } from './utils.js';
import { ItemImage, RarityBadge, MaterialIcon, Sparkline, ReasonDrawer } from './primitives.js';
import { InfoModal, SignInNeededEmptyState } from './info-modal.js';
import { navigate, paths } from './router.js';
import {
  fetchDatabase, fetchBuyOrders, fetchBuyOrdersWithTotal, createBuyOrder, deleteBuyOrder, fetchBuyOrderProjectedPosition,
  fetchPublicLoadouts, fetchMyLoadouts, fetchLoadout, createLoadout,
  setLoadoutSlot, lockLoadoutSlot, generateLoadout, deleteLoadout, favoriteLoadout, cloneLoadout, updateLoadout,
  fetchListings,
  fetchNotifications, markAllNotificationsRead, markNotificationsReadBatch, markNotificationRead, clearReadNotifications, deleteNotificationsBatch,
  deleteNotification,
  fetchBidHistory, placeBid,
  fetchWatchlist, starItem, unstarItem
} from './api.js';

// ── Database ────────────────────────────────────────────────────
export function DatabaseModal({ onClose, onPickItem, me }) {
  const [data, setData]       = useState({ items: [], total: 0, indexed: 0 });
  // Starred item ids — populated once on open for signed-in users so the
  // db-table can render a ★ toggle in each row without a per-row fetch.
  // Anon viewers see the outline star and get redirected to sign-in on
  // click. The live set is kept in sync via optimistic update on each
  // toggle so a user starring 20 items in a row doesn't re-fetch.
  const [starred, setStarred] = useState(null);
  useEffect(() => {
    if (!me) { setStarred(new Set()); return; }
    let alive = true;
    fetchWatchlist().then(data => {
      if (!alive) return;
      const ids = Array.isArray(data)
        ? data.map(r => r?.item?.id ?? r?.itemId).filter(Boolean)
        : [];
      setStarred(new Set(ids));
    }).catch(() => setStarred(new Set()));
    return () => { alive = false; };
  }, [me]);
  const toggleStar = async (item, e) => {
    e.stopPropagation();
    if (!me) { signInWithSteam(); return; }
    const id = item.id;
    const isStarred = starred && starred.has(id);
    // Optimistic update — revert on error.
    setStarred(prev => {
      const next = new Set(prev || []);
      if (isStarred) next.delete(id); else next.add(id);
      return next;
    });
    try {
      const res = isStarred ? await unstarItem(id) : await starItem(id);
      if (res && (res.code || res.error)) {
        setStarred(prev => {
          const next = new Set(prev || []);
          if (isStarred) next.add(id); else next.delete(id);
          return next;
        });
        toast(res.message || res.error || 'Could not update watchlist', 'err');
      } else {
        toast(isStarred ? 'Removed from watchlist' : 'Added to watchlist', 'ok');
      }
    } catch (_) {
      setStarred(prev => {
        const next = new Set(prev || []);
        if (isStarred) next.add(id); else next.delete(id);
        return next;
      });
      // Pre-fix: a network failure here reverted the optimistic ★ but
      // showed nothing — the star silently snapped back with no reason,
      // reading as a dead toggle. The API-error branch above already
      // toasts; mirror that on the exception path.
      toast('Could not update watchlist — check your connection and retry.', 'err');
    }
  };
  const [loading, setLoading] = useState(true);
  // THE PREVIOUS COMMENT HERE WAS FALSE AND IS DELETED. It claimed an
  // audit had fixed this by flipping `loadErr` "on a throw" — but
  // fetchDatabase() does not throw. It caught everything and returned
  // {items:[],total:0,indexed:0}, so the catch below never ran, `loadErr`
  // could never become true, and the error branch at ~line 308 was
  // unreachable code sitting under a comment that said it worked.
  // MEASURED: /api/database forced to 500 and to 503 both rendered
  // "Database · 0 indexed", "0 results" and "Catalogue is empty — The item
  // catalogue is still syncing. Check back in a minute." — no banner, no
  // retry, indistinguishable from a healthy empty catalogue.
  //
  // `loadErr` now flips on the `error: true` key fetchDatabase returns
  // (the fetchNotifications contract, see load() below), AND still on a
  // throw. A false reassurance above broken code is worse than silence.
  const [loadErr, setLoadErr] = useState(false);
  // Batch 972 — search debounce. `searchInput` is the raw text in the
  // field (updates per-keystroke); `search` is the debounced value
  // used to actually fetch. Previously every keystroke fired a full
  // `/api/database` round-trip (LIKE '%q%' scan + rate-limit token
  // burn). Same pattern as the marketplace search (app.js ~line 2507).
  // Seed from /db?q=… so links like the item page's "look it up in the
  // Database" open on that item instead of the whole catalogue.
  const initialQ = (() => {
    try {
      // /database is a route alias for the same page.
      if (!/^\/(db|database)\/?$/.test(window.location.pathname)) return '';
      return (new URLSearchParams(window.location.search).get('q') || '').slice(0, 100);
    } catch (_) { return ''; }
  })();
  const [searchInput, setSearchInput] = useState(initialQ);
  const [search, setSearch]   = useState(initialQ);
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  // Keep ?q= in step with the search, so a refresh or a shared link shows
  // what's on screen (clearing the box used to bring the old q back).
  useEffect(() => {
    try {
      if (!/^\/(db|database)\/?$/.test(window.location.pathname)) return;
      const params = new URLSearchParams(window.location.search);
      const q = (search || '').trim();
      if ((params.get('q') || '') === q) return;
      if (q) params.set('q', q); else params.delete('q');
      const qs = params.toString();
      window.history.replaceState(window.history.state, '', window.location.pathname + (qs ? '?' + qs : ''));
    } catch (_) { /* history unavailable */ }
  }, [search]);
  const [category, setCat]    = useState('All');
  const [rarity, setRar]      = useState('All');
  const [sort, setSort]       = useState('rarest');
  // "Listed only" — hides catalogue rows with no active listings. CSFloat
  // parity: browsing the database, a buyer usually only cares about
  // items they can actually buy right now. Persisted in localStorage so
  // a user's preference sticks across sessions.
  const [listedOnly, setListedOnly] = useState(() => {
    try { return localStorage.getItem('sb_db_listed_only') === '1'; }
    catch { return false; }
  });
  // Batch 558 — price-range filter. Backed by the new minPrice/maxPrice
  // params on /api/database. Strings so the input stays stable across
  // partial typing ("1.2" → "1.25"); parsed before send. Blank means
  // "no bound" — the server null-guards both sides.
  const [minPrice, setMinPrice] = useState('');
  const [maxPrice, setMaxPrice] = useState('');
  useEffect(() => {
    try { localStorage.setItem('sb_db_listed_only', listedOnly ? '1' : '0'); } catch (_) {}
  }, [listedOnly]);
  const [page, setPage]       = useState(0);
  const PAGE_SIZE = 30;

  // Race-condition guard — bump a monotonic request id on every load()
  // so out-of-order responses (e.g. user types "abc" then quickly types
  // "abcd" before the first request lands) don't overwrite a newer
  // result with stale data. Only the latest call's response commits
  // setData / setLoadErr / setLoading.
  const loadReqId = useRef(0);
  const load = useCallback(async () => {
    const reqId = ++loadReqId.current;
    setLoading(true);
    setLoadErr(false);
    try {
      // Parse price bounds lazily at fetch time so partial-typed values
      // (trailing dot, empty) don't thrash the query while the user
      // types. Blank / NaN / negative / > $100k is dropped.
      // The boxes are labelled in the viewer's currency; the API takes USD.
      const parseB = (s) => {
        const n = fxToUsd(parseFloat(s));
        return (n != null && Number.isFinite(n) && n >= 0 && n <= 100000) ? Math.round(n * 100) / 100 : null;
      };
      const res = await fetchDatabase({
        q: search || null, category, rarity, sort,
        listedOnly: listedOnly ? 'true' : null,
        minPrice: parseB(minPrice),
        maxPrice: parseB(maxPrice),
        limit: PAGE_SIZE, offset: page * PAGE_SIZE
      });
      if (reqId !== loadReqId.current) return;
      // fetchDatabase never throws — it returns `{error:true}` on an HTTP
      // error / network drop, exactly like fetchNotifications (api.js) and
      // exactly as the NotificationsModal consumer below reads it. Detect
      // that explicitly: the catch beneath is a backstop, not the mechanism.
      if (res && res.error) setLoadErr(true);
      else setData(res);
    } catch (_) {
      // Backstop only — an unexpected throw (a future refactor, a bug in
      // the helper) must not fall through to the "Catalogue is empty" copy.
      if (reqId === loadReqId.current) setLoadErr(true);
    } finally {
      if (reqId === loadReqId.current) setLoading(false);
    }
  }, [search, category, rarity, sort, listedOnly, minPrice, maxPrice, page]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { setPage(0); }, [search, category, rarity, sort, listedOnly, minPrice, maxPrice]);

  const CATS = ['All','Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'];
  const RARS = ['All','Limited','Off-Market','Standard'];
  // Boss QA D2 — sort labels ditched the "Generic"/"Rarest (lowest …)"
  // wording. Each option now uses the same `Sort: <criteria>` shape so
  // the closed dropdown reads decisively (`Sort: Lowest supply ▾`) and
  // the open list mirrors the same prefix so the active option is
  // unambiguous.
  const SORTS = [
    { v: 'rarest',      l: 'Sort: Lowest supply' },
    { v: 'most_traded', l: 'Sort: Most traded' },
    { v: 'most_viewed', l: 'Sort: Most viewed' },
    { v: 'price_desc',  l: 'Sort: Price high to low' },
    { v: 'price_asc',   l: 'Sort: Price low to high' },
    { v: 'newest',      l: 'Sort: Newest indexed' },
  ];

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));

  // The header must not assert a catalogue size we could not read.
  // "Database · 0 indexed" was the FIRST thing the walk saw under a forced
  // 500, and it survived the fix below it — the table told the truth while
  // the title above it still stated a count of zero. A number we never
  // received is not zero; it is unknown, and the honest render omits it.
  return h(InfoModal, {
    title: loadErr
      ? 'Database'
      : `Database · ${Number(data.indexed || 0).toLocaleString()} indexed`,
    onClose },
    /* Boss QA D1 — the in-row "addbar Database 80" pill (formerly the
       csfloat-db-hero block) duplicated the page header that already
       reads "Database · N indexed" at the top, so it was killed. The
       page now goes straight from header into the search controls. */
    h('div', { className: 'db-controls' },
      h('input', {
        className: 'price-input', style: { flex: 1, minWidth: 180 },
        placeholder: 'Search items…', value: searchInput,
        onChange: e => setSearchInput(e.target.value),
        type: 'search', enterKeyHint: 'search',
        'aria-label': 'Search items in catalogue'
      }),
      h('select', { className: 'sort-select', value: category, onChange: e => setCat(e.target.value), 'aria-label': 'Filter catalogue by category' },
        CATS.map(c => h('option', { key: c, value: c }, c))),
      h('select', { className: 'sort-select', value: rarity, onChange: e => setRar(e.target.value), 'aria-label': 'Filter catalogue by rarity' },
        RARS.map(r => h('option', { key: r, value: r }, r))),
      h('select', { className: 'sort-select', value: sort, onChange: e => setSort(e.target.value), 'aria-label': 'Sort catalogue by' },
        SORTS.map(s => h('option', { key: s.v, value: s.v }, s.l))),
      h('label', {
        style: {
          display: 'inline-flex', alignItems: 'center', gap: 6,
          padding: '6px 12px', borderRadius: 8,
          border: '1px solid var(--border)',
          background: listedOnly ? 'var(--accent-dim)' : 'transparent',
          color: listedOnly ? 'var(--accent)' : 'var(--text-secondary)',
          fontSize: 12, fontWeight: 600, cursor: 'pointer', userSelect: 'none'
        },
        title: 'Only show items that have at least one active listing right now'
      },
        h('input', {
          type: 'checkbox',
          checked: listedOnly,
          onChange: e => setListedOnly(e.target.checked),
          style: { margin: 0 }
        }),
        'Listed only'
      ),
      // Batch 558 — price range inputs. Backed by the new
      // minPrice/maxPrice query params. Sized narrow so they sit
      // alongside the other filters without stealing row width.
      h('input', {
        className: 'price-input', style: { width: 90 },
        type: 'number', min: '0', max: '100000', step: '0.01',
        inputMode: 'decimal',
        // Currency-aware placeholder so a CAD/EUR/etc. user sees
        // "CA$ Min" / "€ Min" — matches the marketplace price-range
        // chips sweep (commit 9038358).
        placeholder: `${currencySymbol()} Min`, value: minPrice,
        onChange: e => setMinPrice(e.target.value),
        'aria-label': 'Minimum floor price',
        title: 'Minimum floor price (blank = no lower bound)'
      }),
      h('input', {
        className: 'price-input', style: { width: 90 },
        type: 'number', min: '0', max: '100000', step: '0.01',
        inputMode: 'decimal',
        placeholder: `${currencySymbol()} Max`, value: maxPrice,
        onChange: e => setMaxPrice(e.target.value),
        'aria-label': 'Maximum floor price',
        title: 'Maximum floor price (blank = no upper bound)'
      })
    ),
    /* Hide the "N results · page X / Y" line on the error path — with a
       failed fetch `data.total` is still its 0 initial value, so the meta
       read "0 results · page 1 / 1" sitting above the error panel, which
       looks like a contradictory healthy-but-empty result. */
    !loadErr && h('div', { className: 'db-meta' },
      h('strong', null, Number(data.total).toLocaleString()), ' results · page ',
      h('strong', null, page + 1), ' / ', totalPages
    ),
    /* CSFLOAT-1:1 ship #10302 — rarity legend / key. Three tier swatches
       laid out in csfloat's filter-row style. Each swatch carries a
       title attr that drives the native tooltip explaining what that
       tier means in s&box. Click filters the table to that rarity. */
    h('div', {
      className: 'cf-rarity-legend',
      role: 'group',
      'aria-label': 'Rarity tier legend'
    },
      h('span', { className: 'cf-rarity-legend-label' }, 'Rarity:'),
      [
        // `v` is the data value used for filtering; `label` is the display
        // name. 'Off-Market' shows as "Scarce" so the legend matches the
        // table's RarityBadge, which relabels that tier the same way.
        { v: 'Standard',   label: 'Standard', t: 'Standard — common items everyone can craft.' },
        { v: 'Off-Market', label: 'Scarce',   t: 'Scarce — limited-supply items not currently sold by Steam.' },
        { v: 'Limited',    label: 'Limited',  t: 'Limited — capped supply, hardest to find.' }
      ].map(r => h('button', {
        key: r.v,
        type: 'button',
        className: `cf-rarity-legend-item rarity-${r.v}` + (rarity === r.v ? ' active' : ''),
        title: r.t,
        'aria-pressed': rarity === r.v,
        onClick: () => setRar(rarity === r.v ? 'All' : r.v)
      },
        h('span', { className: `cf-rarity-swatch rarity-${r.v}`, 'aria-hidden': 'true' }),
        h('span', { className: 'cf-rarity-legend-name' }, r.label)
      ))
    ),
    loading
      /* Boss QA cycle 11 micro-polish — was a generic .spinner pulse, which
         left the database surface as a single dot in the middle of dark
         space for the entire loading window. Replaced with a 10-row
         shimmer-table skeleton that matches the post-load layout
         (#, item, category, rarity, supply, sold, views, floor, watch
         star) so the page stays composed during the wait and the perceived
         load time drops. Each .skeleton-line uses the existing
         shimmer keyframe defined in design.css. */
      ? h('div', { className: 'db-table-scroll', 'aria-label': 'Loading database', role: 'status' },
          h('table', { className: 'db-table' },
            h('tbody', null,
              Array.from({ length: 10 }).map((_, i) => h('tr', { key: 'skl-' + i, className: 'db-row' },
                h('td', { className: 'db-rank' }, h('div', { className: 'skeleton-line', style: { width: 28, height: 12 } })),
                h('td', null,
                  h('div', { className: 'db-item-cell' },
                    h('div', { className: 'db-thumb skeleton-thumb', style: { width: 48, height: 48 } }),
                    h('div', { style: { flex: 1 } },
                      h('div', { className: 'skeleton-line', style: { width: '60%', height: 14, marginBottom: 6 } }),
                      h('div', { className: 'skeleton-line', style: { width: '30%', height: 10 } })
                    )
                  )
                ),
                h('td', null, h('div', { className: 'skeleton-line', style: { width: 60, height: 12 } })),
                h('td', null, h('div', { className: 'skeleton-line', style: { width: 70, height: 18, borderRadius: 8 } })),
                h('td', { className: 'right' }, h('div', { className: 'skeleton-line', style: { width: 40, height: 12, marginLeft: 'auto' } })),
                h('td', { className: 'right' }, h('div', { className: 'skeleton-line', style: { width: 36, height: 12, marginLeft: 'auto' } })),
                h('td', { className: 'right' }, h('div', { className: 'skeleton-line', style: { width: 36, height: 12, marginLeft: 'auto' } })),
                h('td', { className: 'right' }, h('div', { className: 'skeleton-line', style: { width: 60, height: 12, marginLeft: 'auto' } })),
                h('td', { className: 'center' }, h('div', { className: 'skeleton-line', style: { width: 24, height: 24, borderRadius: 8, margin: '0 auto' } }))
              ))
            )
          )
        )
      : loadErr
      ? h('div', {
          className: 'empty-state',
          style: { padding: '32px 16px', textAlign: 'center' },
          role: 'alert'
        },
          h('div', { className: 'empty-state-icon', 'aria-hidden': 'true' },
            h(MaterialIcon, { name: 'error_outline', size: 26 })),
          h('div', { className: 'empty-state-title' }, "Couldn't load catalogue"),
          h('div', { className: 'empty-state-sub' },
            'The item database failed to load. Check your connection and try again.'),
          h('div', { className: 'empty-state-actions' },
            h('button', {
              className: 'btn btn-accent',
              onClick: () => load()
            }, 'Retry'))
        )
      : (data.items.length === 0 && data.total === 0)
      ? (() => {
          // Batch 867 — empty state for filter combinations that return
          // nothing. Previously the table just rendered an empty tbody
          // below the "0 results" meta line, leaving users staring at
          // blank space wondering if the page was broken. Now they get
          // a clear explanation + one-click Clear Filters.
          const hasFilters = !!search || category !== 'All' || rarity !== 'All' ||
                             listedOnly || minPrice || maxPrice;
          return h('div', {
            className: 'empty-state',
            style: { padding: '32px 16px', textAlign: 'center' }
          },
            /* Boss QA cycle 11 micro-polish — replaced bare "—" glyph with
               a branded line-art illustration of a magnifying glass over
               three database rows. Tied to the database topic (rows + a
               search lens) and feels intentional vs the placeholder dash.
               Sized 26 inside the existing 56×56 .empty-state-icon chip. */
            h('div', { className: 'empty-state-icon', 'aria-hidden': 'true' },
              h('svg', { width: 26, height: 26, viewBox: '0 0 26 26', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' },
                h('rect', { x: 3, y: 4, width: 14, height: 2, rx: 1 }),
                h('rect', { x: 3, y: 9, width: 10, height: 2, rx: 1 }),
                h('rect', { x: 3, y: 14, width: 12, height: 2, rx: 1 }),
                h('circle', { cx: 18, cy: 18, r: 4 }),
                h('line', { x1: 21, y1: 21, x2: 24, y2: 24 })
              )
            ),
            h('div', { className: 'empty-state-title' },
              hasFilters ? 'No items match your filters' : 'Catalogue is empty'),
            h('div', { className: 'empty-state-sub' },
              hasFilters
                ? 'Try clearing the category, rarity, or price range to broaden the view.'
                : 'The item catalogue is still syncing. Check back in a minute.'),
            hasFilters && h('div', { className: 'empty-state-actions' },
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                onClick: () => {
                  // Batch 972 — clear both input (visible) + debounced
                  // value so the text field also empties instantly.
                  setSearchInput(''); setSearch('');
                  setCat('All'); setRar('All');
                  setListedOnly(false); setMinPrice(''); setMaxPrice('');
                }
              }, 'Clear Filters')
            )
          );
        })()
      : h('div', { className: 'db-table-scroll' },
        h('table', { className: 'db-table' },
          // Visually-hidden caption gives screen readers a real
          // description of the table — without it, NVDA / JAWS
          // announce "table with 9 columns and N rows" with no
          // semantic anchor.
          h('caption', { className: 'visually-hidden' },
            `s&box item database — ${data?.items?.length || 0} items, columns: rank, item, category, rarity, supply, sold, views, floor price, watchlist toggle.`),
          h('thead', null, h('tr', { className: 'db-row db-head' },
            h('th', { scope: 'col' }, '#'),
            h('th', { scope: 'col' }, 'Item'),
            h('th', { scope: 'col' }, 'Category'),
            h('th', { scope: 'col' }, 'Rarity'),
            h('th', { scope: 'col', className: 'right' }, 'Supply'),
            h('th', { scope: 'col', className: 'right' }, 'Sold'),
            h('th', { scope: 'col', className: 'right', title: 'Lifetime GET /api/items/{id} hits' }, 'Views'),
            h('th', { scope: 'col', className: 'right' }, 'Floor'),
            // Boss QA D5 — last column was just a glyph (★ / ☆) which
            // read like a stray chevron at viewport scale. Rename the
            // header to "Watch" so users know what the toggle does and
            // there's no mistaking it for an "Open detail" arrow column
            // (the entire row is already clickable for that purpose).
            h('th', { scope: 'col', title: 'Add / remove from watchlist' }, 'Watch')
          )),
          h('tbody', null,
            data.items.map((item, i) => h('tr', {
              key: item.id,
              className: 'db-row',
              /* Boss QA cycle 11 — dropped role="button" + tabIndex from <tr>
                 because the row contained an interactive watchlist <button>,
                 which axe flagged as nested-interactive (serious). The row's
                 mouse-click is preserved via onClick, and keyboard a11y now
                 lives on a real <a> wrapping the item name (focusable, named).
                 Watchlist button stops propagation so cell clicks don't
                 double-fire. */
              onClick: () => onPickItem && onPickItem(item)
            },
              h('td', { className: 'db-rank' }, '#' + (page * PAGE_SIZE + i + 1)),
              h('td', null,
                h('div', { className: 'db-item-cell' },
                  h('div', { className: 'db-thumb' }, h(ItemImage, { item, variant: 'mini' })),
                  h('div', null,
                    h('a', {
                      className: 'db-name',
                      href: '/item/' + item.id,
                      'aria-label': `Open ${item.name} detail · ${item.category} · ${(parseFloat(item.lowestPrice) || 0) > 0 ? 'floor ' + fmt(item.lowestPrice) : 'no listings yet'}`,
                      onClick: (e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        if (typeof onPickItem === 'function') onPickItem(item);
                      }
                    }, highlightMatch(item.name || '', search)),
                  )
                )
              ),
              h('td', { className: 'db-cat' }, item.category),
              h('td', null, h(RarityBadge, { rarity: item.rarity })),
              h('td', { className: 'right db-mono' }, Number(item.supply).toLocaleString()),
              h('td', { className: 'right db-mono' }, Number(item.totalSold).toLocaleString()),
              h('td', { className: 'right db-mono' }, Number(item.viewCount || 0).toLocaleString()),
              /* When supply > 0 but no live listing exists yet, lowestPrice
                 comes back as 0 and used to render "$0.00" — read as a free
                 item. Show an em dash for "no floor yet" instead. */
              h('td', { className: 'right db-mono accent' },
                (parseFloat(item.lowestPrice) || 0) > 0
                  ? fmt(item.lowestPrice)
                  : h('span', { style: { color: 'var(--ink-4)' } }, '—')),
              (() => {
                const isStarred = starred && starred.has(item.id);
                const label = !me
                  ? 'Sign in to add to watchlist'
                  : isStarred
                    ? `Remove ${item.name} from watchlist`
                    : `Add ${item.name} to watchlist`;
                /* Boss QA cycle 11 — db-row star bumped from 30×30 to 36×36
                   (desktop) and 44×44 (mobile) so the watchlist-toggle
                   target clears Apple HIG. The icon glyph stays 15px so
                   density doesn't change. */
                return h('td', { className: 'center db-row-star-cell', onClick: (e) => e.stopPropagation() },
                  h('button', {
                    className: 'btn btn-ghost db-row-star',
                    style: {
                      padding: 0, fontSize: 15, lineHeight: 1, borderRadius: 8,
                      color: isStarred ? '#fbbf24' : 'var(--text-muted)',
                      background: 'transparent',
                      border: '1px solid var(--border)',
                      transition: 'transform 100ms ease, color 140ms ease, border-color 140ms ease'
                    },
                    title: label,
                    'aria-label': label,
                    onClick: (e) => toggleStar(item, e)
                  }, isStarred ? '★' : '☆')
                );
              })()
            ))
          )
        )),
    /* Pager only when there's genuine multi-page data. Previously it
       rendered even on the error / empty / single-page paths as a dead
       "1 of 1" control with both arrows disabled — CSFloat hides
       pagination entirely when the result set fits one page. `loading`
       is intentionally NOT a condition here: keeping the pager mounted
       through a page-to-page fetch avoids it flickering out and back on
       every Next/Prev click (totalPages stays stable across the fetch). */
    !loadErr && totalPages > 1 && h('div', { className: 'db-pager' },
      h('button', { className: 'btn btn-ghost', disabled: page === 0, onClick: () => setPage(p => Math.max(0, p - 1)) }, '← Prev'),
      h('span', { style: { fontSize: 12, color: 'var(--text-muted)' } }, `${page + 1} of ${totalPages}`),
      h('button', { className: 'btn btn-ghost', disabled: page + 1 >= totalPages, onClick: () => setPage(p => p + 1) }, 'Next →')
    )
  );
}

// ── Buy Orders — per-specific-item, CSFloat-style ──────────────
//
// CSFloat buy orders are ALWAYS attached to a specific item ("I'll pay up to
// $X for a Wizard Hat"). The buyer does NOT set category/rarity filters.
// The creation flow supports two entry points:
//
//   1) The user clicks "+ Create Buy Order" inside this modal and searches
//      the item catalogue via an autocomplete picker.
//   2) The user clicks "+ Create Buy Order" INSIDE an ItemModal, in which
//      case we open this modal pre-seeded with that item and skip the search.
//
// Both paths hit POST /api/buy-orders with an explicit itemId. The buyer
// then sees every active buy order grouped by item, with live status.
export function BuyOrdersModal({ onClose, me, wallet, preselectedItem }) {
  const [orders, setOrders]   = useState(null);
  // True row count via X-Total-Count header — feeds the "Showing most
  // recent 300 of N" overflow banner when BUY_ORDER_LIST_CAP (300,
  // batch 1009) trims the payload. Null until the first fetch lands.
  const [ordersTotal, setOrdersTotal] = useState(null);
  const [creating, setCreating] = useState(!!preselectedItem);
  const [picked, setPicked]   = useState(preselectedItem || null);
  const [search, setSearch]   = useState('');
  const [pool, setPool]       = useState([]);
  // Seed the max-price suggestion (0.9× floor) for a preselected item so
  // the deep-link path (ItemModal → "Create Buy Order") matches the
  // in-modal search-picker path, which sets the same suggestion on pick.
  // Previously a preselected item dropped the buyer into an empty price
  // field with only a placeholder hint.
  const [maxPrice, setMaxPrice] = useState(() => {
    const floor = parseFloat(preselectedItem?.lowestPrice || 0);
    return floor > 0 ? (floor * 0.9).toFixed(2) : '';
  });
  const [qty, setQty]         = useState('1');
  const [busy, setBusy]       = useState(false);
  // Sync re-entrancy latch — submit() calls setBusy AFTER its await(createBuyOrder),
  // so a double-click escrows funds into TWO standing orders. Auto-resets via the
  // per-render busyRef sync after setBusy(false).
  const busyRef = useRef(busy); busyRef.current = busy;
  const [err, setErr]         = useState('');
  // Audit fix — `load()` awaited fetchBuyOrdersWithTotal() with no
  // catch, so a rejected fetch left `orders` at `null` forever and the
  // skeleton shimmered indefinitely with no way out. `ordersErr` flips
  // on a throw so the render shows an error + Retry instead of the
  // infinite skeleton. `poolErr` does the same for the autocomplete
  // catalogue pool, whose .then() had no .catch().
  const [ordersErr, setOrdersErr] = useState(false);
  const [poolErr, setPoolErr]     = useState(false);
  // Status filter chip (batch 735). The order list returns every status
  // (ACTIVE / FILLED / CANCELLED / EXPIRED). Buyers with a deep history
  // want to see "did order #12 ever fill?" without scrolling past 30
  // ACTIVE rows. ACTIVE is the default so fresh users see the familiar
  // "my live orders" view unchanged.
  const [orderStatusFilter, setOrderStatusFilter] = useState('ACTIVE');
  // Projected queue position for the current (picked.id, maxPrice)
  // pair. Refreshed with a 400ms debounce whenever the user edits the
  // max price so the chip updates as they tune the number.
  const [projectedPos, setProjectedPos] = useState(null);
  useEffect(() => {
    if (!picked?.id || !(parseFloat(maxPrice) > 0)) { setProjectedPos(null); return; }
    let alive = true;
    const t = setTimeout(async () => {
      const n = await fetchBuyOrderProjectedPosition(picked.id, maxPrice);
      if (alive) setProjectedPos(n);
    }, 400);
    return () => { alive = false; clearTimeout(t); };
  }, [picked?.id, maxPrice]);

  const load = useCallback(async () => {
    setOrdersErr(false);
    try {
      const { items, total, error } = await fetchBuyOrdersWithTotal();
      // fetchBuyOrdersWithTotal swallows non-2xx/network into an empty list +
      // error:true (it never throws), so branch on the sentinel — otherwise the
      // catch below never fires and a backend failure renders a false "No buy
      // orders" instead of the Retry panel. (frontend-audit fix)
      if (error) { setOrdersErr(true); return; }
      setOrders(items);
      setOrdersTotal(total);
    } catch (_) {
      // Network / 5xx — flag so the render swaps the infinite skeleton
      // for an error panel whose Retry button re-runs this load().
      setOrdersErr(true);
    }
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  // Catalogue pool for the autocomplete search
  useEffect(() => {
    // Cleanup guard — closing the BuyOrders modal mid-fetch used to
    // call setPool on an unmounted component. Alive flag short-circuits.
    let alive = true;
    // The whole catalogue, not just what is on sale right now. The pool used
    // to be built from the live listings, so an item whose last copy had
    // just sold answered "No matches." -- and that is exactly the item a
    // buyer wants a standing order on (see the "not-yet-listed" note in
    // submit below).
    fetchDatabase({ limit: 500, sort: 'most_traded' }).then(res => {
      if (!alive) return;
      if (!res || res.error) { setPoolErr(true); return; }
      setPool((res.items || []).filter(it => it && it.id != null && it.name));
    }).catch(() => {
      // Audit fix — without a .catch() a rejected listings fetch left
      // the autocomplete pool permanently empty with no feedback, so
      // the picker read as "No matches." for every query. Flag it so
      // the picker shows a "couldn't load items" hint instead.
      if (alive) setPoolErr(true);
    });
    return () => { alive = false; };
  }, []);

  const filteredPool = useMemo(() => {
    if (!search) return pool.slice(0, 30);
    const s = search.toLowerCase();
    return pool.filter(p => p.name.toLowerCase().includes(s)).slice(0, 30);
  }, [pool, search]);

  if (!me) return h(InfoModal, { title: 'Buy Orders', onClose },
    h(SignInNeededEmptyState, { what: 'and manage your buy orders' }));

  const submit = async () => {
    setErr('');
    if (!picked) { setErr('Pick an item first'); return; }
    const max = parseFloat(maxPrice);
    if (!max || max <= 0) { setErr('Enter a max price above zero'); return; }
    // Parse the floor BEFORE truthiness-testing it. lowestPrice can come
    // back as the string "0" for an item with no live listings — a
    // truthy string — so the old `picked.lowestPrice && …` let a 0-floor
    // through into `max >= 0`, which is always true and wrongly blocked
    // every buy order with a bogus "at or above floor ($0.00)" error.
    // A standing buy order on a not-yet-listed item is perfectly valid.
    const floor = parseFloat(picked.lowestPrice);
    if (Number.isFinite(floor) && floor > 0 && max >= floor) {
      setErr(`Your max (${fmt(max)}) is at or above the current floor (${fmt(floor)}) — just buy now instead.`);
      return;
    }
    // Quantity guard. The backend enforces @Positive + @Max(100) on
    // CreateBuyOrderRequest.quantity, so a typed `-3` / `0` / `250` / a
    // bare `2.5` would bounce back as a generic HTTP 400. Validate here
    // for a friendly inline message instead. parseInt floors decimals.
    const qtyN = parseInt(qty || '1', 10);
    if (!Number.isFinite(qtyN) || qtyN < 1) {
      setErr('Quantity must be a whole number of 1 or more.');
      return;
    }
    if (qtyN > 100) {
      setErr('Quantity is capped at 100 per buy order.');
      return;
    }
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    const itemName = picked.name;
    try {
      const res = await createBuyOrder({
        itemId:   picked.id,
        category: picked.category,   // snapshot the category for display only
        rarity:   picked.rarity,     // snapshot the rarity for display only
        maxPrice: max,
        quantity: qtyN
      });
      if (res.code || res.error) { setErr(res.message || res.error); return; }
      setPicked(null); setMaxPrice(''); setQty('1'); setSearch('');
      setCreating(false);
      load();
      // Pre-fix: create was silent on success — the new row appeared at
      // the top of the list but the form-collapse felt the same as the
      // error path's no-op. The ProfileBuyOrdersTab cancel pair already
      // toasts richly; pairing the create with a matching named toast
      // closes the loop. Buy orders don't hold funds (BuyOrderService
      // checks the balance at match time), so say that rather than
      // "escrowed" — a buyer who thought the money was reserved could
      // spend it and the order would then silently fail to fill. An
      // order that matched an existing listing on create is already done.
      const qtyStr = qtyN > 1 ? ` × ${qtyN}` : '';
      if (res && res.status === 'FILLED') {
        toast(`Buy order for "${itemName}" filled right away from an existing listing. Check Profile → Trades.`, 'ok');
      } else if (res && Number.isFinite(Number(res.quantity)) && Number(res.quantity) < qtyN) {
        // Some units matched live listings on create (and were charged);
        // the rest stay open. Saying "charged only when a listing
        // matches" here would hide the charge that already happened.
        const left = Number(res.quantity);
        const got = qtyN - left;
        toast(`Buy order for "${itemName}": ${got} filled right away from existing listings, ${left} still open at max ${fmt(max)}. Check Profile → Trades.`, 'ok');
      } else {
        toast(`Buy order placed for "${itemName}" at max ${fmt(max)}${qtyStr}. Your wallet is charged only when a listing matches — keep enough balance for it.`,
          'ok');
      }
    } finally { setBusy(false); }
  };

  const cancel = async (id) => {
    // Pre-fix: confirm + toast were both generic ("Cancel this buy order?"
    // / "Buy order cancelled."). The ProfileBuyOrdersTab equivalent already
    // names the item + remaining qty + reminds the user that wallet funds
    // are freed. This modal is the more public entry point (toolbar →
    // /buyorders), so keeping it generic was the inconsistent outlier.
    const o = (orders || []).find(x => x.id === id);
    const itemLabel = o?.itemName || 'item';
    if (!confirm(`Cancel buy order for "${itemLabel}"? Any remaining quantity is freed.`)) return;
    const res = await deleteBuyOrder(id);
    if (res && res.error) { toast(res.error, 'err'); return; }
    load();
    const priceStr = o?.maxPrice != null ? fmt(parseFloat(o.maxPrice)) : '';
    // BuyOrder.quantity is ALREADY the remaining (un-filled) count — it's
    // decremented per auto-fill. There is no `filledQuantity` field on
    // the model, so the old `- (filledQuantity||0)` was a dead no-op.
    const remaining = o?.quantity || 0;
    const qtyStr = remaining > 1 ? ` (${remaining} units)` : '';
    toast(`Buy order cancelled for "${itemLabel}"${priceStr ? ' at ' + priceStr : ''}${qtyStr}.`,
      'ok');
  };

  // Trade-URL nag (batch 388). A buy order that matches a listing fires
  // PurchaseService.buy, which refuses without a Steam trade URL — the
  // server also rejects buy-order creation itself. Surface the blocker
  // upfront so the buyer fixes Profile before committing to a standing
  // order. Only renders when the viewer actually has no URL set.
  const tradeUrlMissing = me && !(me.tradeUrl && String(me.tradeUrl).trim());
  // Title count must mirror the list shown below, which is filtered by
  // orderStatusFilter (default ACTIVE). Counting raw orders.length would
  // include FILLED/CANCELLED/EXPIRED rows the user can't see — header
  // and list would disagree. Apply the same filter used at render time.
  const visibleOrderCount = orderStatusFilter === 'ALL'
    ? ((orders && orders.length) || 0)
    : ((orders || []).filter(o => (o.status || '').toUpperCase() === orderStatusFilter).length);
  return h(InfoModal, { title: `Buy Orders · ${visibleOrderCount}`, onClose },
    tradeUrlMissing && h('div', {
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
        ' — a buy order auto-purchases on match; sellers can\'t ship without your URL.'),
      h('a', {
        href: '/profile',
        className: 'btn btn-accent',
        style: { padding: '6px 14px', fontSize: 12, textDecoration: 'none' },
        onClick: (e) => { e.stopPropagation(); onClose && onClose(); }
      }, 'Open Profile')
    ),
    h('div', { style: { display: 'flex', gap: 10, marginBottom: 16 } },
      h('button', {
        // Once the form is open this button only closes it, so it steps
        // down to the ghost style every other Cancel uses; as a solid
        // accent button it out-shouted "Place Buy Order" below it.
        className: creating ? 'btn btn-ghost' : 'btn btn-accent',
        style: creating ? { border: '1px solid var(--border)' } : undefined,
        disabled: tradeUrlMissing,
        title: tradeUrlMissing ? 'Set your Steam trade URL in Profile first' : null,
        onClick: () => { setCreating(c => !c); setErr(''); }
      }, creating ? 'Cancel' : '+ Create Buy Order')
    ),
    creating && h('div', { className: 'buyorder-form' },
      // STEP 1 — item picker (skipped if a preselected item is already set)
      !picked && h('div', null,
        h('div', { className: 'wallet-input-label' }, 'Item'),
        h('input', {
          className: 'price-input', style: { width: '100%' }, autoFocus: true,
          'aria-label': 'Search catalogue for buy order target',
          placeholder: 'Search catalogue…',
          value: search,
          onChange: e => setSearch(e.target.value)
        }),
        h('div', { className: 'buyorder-picker' },
          filteredPool.length === 0
            ? h('div', { style: { padding: 12, fontSize: 12, color: poolErr ? 'var(--red)' : 'var(--text-muted)' } },
                poolErr
                  ? "Couldn't load items — check your connection and reopen this form."
                  : 'No matches.')
            : filteredPool.map(it => h('div', {
                key: it.id, className: 'buyorder-picker-row',
                onClick: () => { setPicked(it); setMaxPrice((parseFloat(it.lowestPrice || 0) * 0.9).toFixed(2)); },
                role: 'button',
                tabIndex: 0,
                'aria-label': `Pick ${it.name} — ${it.category} · ${it.rarity} · floor ${fmt(it.lowestPrice)}`,
                onKeyDown: (e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    setPicked(it);
                    setMaxPrice((parseFloat(it.lowestPrice || 0) * 0.9).toFixed(2));
                  }
                }
              },
                h('div', { className: 'db-thumb', style: { width: 36, height: 36 } }, h(ItemImage, { item: it, variant: 'mini' })),
                h('div', { style: { flex: 1, minWidth: 0 } },
                  h('div', { style: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' } }, it.name),
                  h('div', { style: { fontSize: 10, color: 'var(--text-muted)' } }, it.category + ' · ' + it.rarity)
                ),
                h('div', { style: { fontSize: 12, fontWeight: 700, color: (parseFloat(it.lowestPrice) > 0 ? 'var(--accent)' : 'var(--text-muted)'), fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } },
                  parseFloat(it.lowestPrice) > 0 ? fmt(it.lowestPrice) : '—')
              ))
        )
      ),

      // STEP 2 — price + quantity with the selected item locked in
      picked && h('div', null,
        h('div', { className: 'buyorder-picked' },
          h('div', { className: 'db-thumb', style: { width: 48, height: 48 } }, h(ItemImage, { item: picked, variant: 'mini' })),
          h('div', { style: { flex: 1, minWidth: 0 } },
            h('div', { style: { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' } }, picked.name),
            h('div', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Floor · ', parseFloat(picked.lowestPrice) > 0
              ? h('strong', { style: { color: 'var(--accent)' } }, fmt(picked.lowestPrice))
              : h('strong', { style: { color: 'var(--text-muted)' } }, 'no active listings'))
          ),
          !preselectedItem && h('button', {
            className: 'btn btn-ghost',
            style: { padding: '6px 10px', fontSize: 11, border: '1px solid var(--border)' },
            onClick: () => { setPicked(null); setMaxPrice(''); }
          }, 'Change')
        ),
        h('div', { className: 'wallet-input-label' }, 'Maximum Price per Item (USD)'),
        h('input', { className: 'wallet-amount-input', type: 'number', step: '0.01', min: '0.01',
          inputMode: 'decimal', enterKeyHint: 'done',
          'aria-label': 'Maximum price per item',
          value: maxPrice, onChange: e => setMaxPrice(e.target.value),
          placeholder: picked.lowestPrice ? (parseFloat(picked.lowestPrice) * 0.9).toFixed(2) : '0.00' }),
        h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginTop: 4 } },
          'When a listing for this item drops to or below your max, it is auto-purchased from your wallet.'),
        // Projected queue-position chip. Previews where this order
        // would land under the engine's priority (maxPrice DESC,
        // createdAt ASC) so the buyer can tune their max price before
        // committing. Green at #1 (next to fill), amber #2–#3, muted
        // deeper. Silent while the request is in flight or inputs
        // are invalid.
        projectedPos != null && (() => {
          const cls = projectedPos <= 1 ? 'var(--green)'
                   :  projectedPos <= 3 ? '#fbbf24'
                   :                      'var(--text-muted)';
          const tip = projectedPos === 1
            ? 'At this price you would be first in line — the next matching listing fills your order.'
            : `${projectedPos - 1} other buyer${projectedPos - 1 === 1 ? ' is' : 's are'} already bidding at least this much for this item. Raise your max to jump the queue.`;
          return h('div', {
            style: {
              marginTop: 8, padding: '6px 10px', borderRadius: 6, fontSize: 11,
              fontWeight: 700, color: cls,
              background: 'var(--bg-elevated)', border: '1px solid var(--border)',
              display: 'inline-block'
            },
            title: tip
          }, 'At this price · #' + projectedPos + ' in queue');
        })(),
        h('div', { className: 'wallet-input-label' }, 'Quantity'),
        // max:100 mirrors the backend @Max(100) on quantity so the
        // native number-spinner clamps and the field hints the ceiling.
        h('input', { className: 'wallet-amount-input', type: 'number', min: '1', max: '100', step: '1',
          inputMode: 'numeric', enterKeyHint: 'done',
          'aria-label': 'Quantity to buy (1–100)',
          title: 'How many of this item to auto-buy — up to 100 per order.',
          value: qty, onChange: e => setQty(e.target.value) }),
        // Wallet-balance check (batch 417). When the user types a
        // maxPrice × qty that exceeds their current wallet balance,
        // surface an amber warning — the match engine silently skips
        // orders whose buyer can't cover, so without this the buyer
        // wouldn't know their order is dead-weight until it never
        // fills. Hidden when wallet or price is unavailable.
        (() => {
          if (!wallet) return null;
          const max = parseFloat(maxPrice);
          const q   = parseInt(qty, 10);
          if (!(max > 0) || !(q > 0)) return null;
          const need = max * q;
          const bal  = parseFloat(wallet.balance) || 0;
          if (bal >= need) return null;
          return h('div', {
            style: {
              marginTop: 8, padding: '8px 12px', borderRadius: 6,
              fontSize: 11, lineHeight: 1.5,
              color: 'var(--red)',
              background: 'var(--red-dim)',
              border: '1px solid rgba(248,113,113,0.35)'
            },
            title: 'The buy-order match engine skips orders whose buyer can\'t cover the max × qty at match time.'
          },
            h('strong', null, 'Wallet short · '),
            `This order could charge up to ${fmt(need)} (${fmt(max)} × ${q}), but your wallet has ${fmt(bal)}. `,
            h('a', {
              href: '/wallet',
              style: { color: 'var(--accent)', fontWeight: 700, textDecoration: 'underline' },
              onClick: (e) => { e.stopPropagation(); onClose && onClose(); }
            }, `Deposit ${fmt(need - bal)} →`)
          );
        })(),
        err && h('div', { className: 'wallet-error' }, err),
        h('button', { className: 'btn btn-accent wallet-submit', disabled: busy, onClick: submit },
          busy ? 'Creating…' : 'Place Buy Order')
      )
    ),

    ordersErr
      /* Audit fix — fetchBuyOrdersWithTotal() rejected. Without this
         branch `orders` stayed null and the skeleton below shimmered
         forever. Surface a real error with a Retry that re-runs load(). */
      ? h('div', { className: 'empty-inline', style: { padding: '32px 16px' }, role: 'alert' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            "Couldn't load buy orders"),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
            'Your buy orders failed to load. Check your connection and try again.'),
          h('button', { className: 'btn btn-accent', onClick: () => load() }, 'Retry')
        )
      : orders === null
      /* Boss QA cycle 11 micro-polish — was a centered .spinner pulse,
         leaving the modal as a single dot for the entire wait. Replaced
         with a 4-row .buyorder-row shimmer skeleton that mirrors the
         post-load row layout (title, sub, cap+status, cancel button) so
         the modal stays composed during the round-trip. */
      ? h('div', { className: 'buyorder-list', 'aria-label': 'Loading buy orders', role: 'status' },
          Array.from({ length: 4 }).map((_, i) => h('div', { key: 'skl-' + i, className: 'buyorder-row' },
            h('div', { style: { flex: 1, minWidth: 0 } },
              h('div', { className: 'skeleton-line', style: { width: '55%', height: 14, marginBottom: 6 } }),
              h('div', { className: 'skeleton-line', style: { width: '40%', height: 11 } })
            ),
            h('div', { style: { textAlign: 'right', marginRight: 14 } },
              h('div', { className: 'skeleton-line', style: { width: 70, height: 14, marginBottom: 6, marginLeft: 'auto' } }),
              h('div', { className: 'skeleton-line', style: { width: 50, height: 11, marginLeft: 'auto' } })
            ),
            h('div', { className: 'skeleton-line', style: { width: 70, height: 30, borderRadius: 6 } })
          ))
        )
      : orders.length === 0
        ? h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'bolt', size: 26 })),
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              'No buy orders yet'),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 16px' } },
              'Set a standing "pay up to $X for this item" order and the matching engine auto-buys the next listing that drops to your max. Great for items you keep missing the drop on.'),
            // Batch 901 — inline CTA. Previous copy instructed users to
            // click an item and use "+ Create Buy Order" — buried in
            // the item-detail modal. Now the Buy Orders modal itself
            // drives the flow via the existing search-picker path
            // (`creating === true` opens the autocomplete).
            h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
              h('button', {
                className: 'btn btn-accent',
                style: { padding: '8px 18px', fontSize: 13 },
                onClick: () => setCreating(true)
              }, '+ Create your first buy order'),
              h('a', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)', padding: '8px 18px', fontSize: 13, textDecoration: 'none' },
                href: '/'
              }, 'Browse items →')
            ))
        : h('div', null,
          // Batch 1019 — overflow banner when the server's 300-row
          // buy-order cap (BUY_ORDER_LIST_CAP, batch 1009) trims the
          // payload. Silent for ordinary users (ordersTotal ==
          // orders.length); only surfaces for power-buyers with 300+
          // historical orders so they know older rows still exist
          // server-side (queryable via CSV export).
          ordersTotal != null && orders.length > 0 && ordersTotal > orders.length && h('div', {
            style: {
              margin: '0 0 12px', padding: '10px 14px', fontSize: 12,
              background: 'rgba(30,165,255,0.08)',
              border: '1px solid var(--accent-border)',
              borderRadius: 8, color: 'var(--text-secondary)',
              display: 'flex', alignItems: 'center', gap: 10
            },
            title: 'Server caps the list at 300 rows. Older orders stay on file — use ⇣ CSV for the full history.'
          },
            h('span', null, '—',
              'Showing most recent ',
              h('strong', { style: { color: 'var(--text-primary)' } }, orders.length),
              ' of ',
              h('strong', { style: { color: 'var(--accent)' } }, ordersTotal),
              ' buy orders.')
          ),
          // Status filter chips — only shown when there's something
          // other than pure ACTIVE to look at. Fresh users with only
          // live orders never see the chips, keeping the UX clean.
          orders.length > 0 && orders.some(o => o.status !== 'ACTIVE') && h('div', {
            style: { display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 }
          },
            ['ACTIVE','FILLED','CANCELLED','EXPIRED','ALL'].map(opt => {
              const count = opt === 'ALL'
                ? orders.length
                : orders.filter(o => (o.status || '').toUpperCase() === opt).length;
              return h('button', {
                key: opt,
                className: `wallet-tx-filter-chip ${orderStatusFilter === opt ? 'active' : ''}`,
                onClick: () => setOrderStatusFilter(opt),
                disabled: count === 0 && opt !== 'ALL'
              }, `${opt === 'ALL' ? 'All' : opt.charAt(0) + opt.slice(1).toLowerCase()} · ${count}`);
            })
          ),
          (() => {
            const filtered = orderStatusFilter === 'ALL'
              ? orders
              : orders.filter(o => (o.status || '').toUpperCase() === orderStatusFilter);
            if (filtered.length === 0) {
              return h('div', { className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'filter_list', size: 26 })),
                h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 10 } },
                  `No ${orderStatusFilter.toLowerCase()} orders`),
                h('button', {
                  className: 'btn btn-accent',
                  onClick: () => setOrderStatusFilter('ALL')
                }, 'Show all orders')
              );
            }
            return h('div', { className: 'buyorder-list' },
              filtered.map(o => h('div', { key: o.id, className: `buyorder-row ${(o.status || '').toLowerCase()}` },
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('div', { className: 'buyorder-title' },
                  o.itemName || ('Any ' + (o.category || 'item'))
                ),
                h('div', { className: 'buyorder-sub' },
                  'Created ', timeAgo(o.createdAt),
                  ' · Qty ', h('strong', null, o.quantity), ' / ', o.originalQuantity
                )
              ),
              h('div', { style: { textAlign: 'right', marginRight: 14 } },
                h('div', { className: 'buyorder-cap' }, '≤ ' + fmt(o.maxPrice)),
                h('div', { className: `buyorder-status ${o.status}` },
                  o.status === 'ACTIVE'    ? 'Active'
                  : o.status === 'FILLED'    ? 'Filled'
                  : o.status === 'CANCELLED' ? 'Cancelled'
                  : o.status === 'EXPIRED'   ? 'Expired'
                  // Title-case any unknown status rather than leaking
                  // the raw all-caps token next to its title-cased
                  // siblings — EXPIRED was the visible offender (it has
                  // its own .buyorder-status.EXPIRED CSS + filter chip).
                  : ((o.status || '').charAt(0) + (o.status || '').slice(1).toLowerCase()))
              ),
              o.status === 'ACTIVE' && h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)', padding: '7px 14px', fontSize: 12 },
                onClick: () => cancel(o.id)
              }, 'Cancel')
            )));
          })()
        )
  );
}

// Trade-Up Calculator intentionally removed — s&box has no trade-up contract
// mechanic. See memory/sbox_vs_csfloat.md for the full list of CS-only
// features we don't port over.

// ── Loadout Lab ─────────────────────────────────────────────────
export function LoadoutLabModal({ onClose, me, loadoutId }) {
  const [tab, setTab]         = useState('discover'); // discover | mine | view
  const [list, setList]       = useState(null);
  // Audit fix — `load()` did `setList(null)` then awaited
  // fetchPublicLoadouts/fetchMyLoadouts with no catch, so a rejected
  // fetch left `list` at `null` and the spinner spun forever. `listErr`
  // flips on a throw so the list region shows an error + Retry.
  // `poolErr` does the same for the slot-picker item pool whose
  // .then() had no .catch().
  const [listErr, setListErr] = useState(false);
  const [poolErr, setPoolErr] = useState(false);
  // Batch 973 — debounced search. `searchInput` tracks the visible
  // field; a 300ms setTimeout copies it to `search`, which is the
  // only value that actually triggers the /api/loadouts/discover fetch.
  // Same pattern as the marketplace grid (app.js ~2507) and the
  // Database modal (batch 972). Prevents a 10-char query from firing
  // 10 round-trips — each one does a LIKE '%q%' against the loadouts
  // table + burns a READ-budget rate-limit token.
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch]   = useState('');
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  // `null` = not loaded yet (spinner), `{ __notFound }` = real 404 so
  // we can surface a friendly empty-state instead of spinning forever.
  const [viewing, setViewing] = useState(null);
  const [allItems, setAllItems] = useState([]);
  const [budget, setBudget]   = useState('');
  // Batch 833 — inline rename state. Replaces the window.prompt on the
  // loadout view's ✎ Rename button with an in-page input + Save/Cancel.
  const [renaming, setRenaming]         = useState(false);
  const [renameDraft, setRenameDraft]   = useState('');
  // Batch 852 — inline admin-takedown reason drawer. `null` = closed;
  // string = open with that draft text. Replaces a `window.prompt`.
  const [adminRemoveDraft, setAdminRemoveDraft] = useState(null);
  const [adminRemoveBusy, setAdminRemoveBusy]   = useState(false);
  const [renameBusy, setRenameBusy]     = useState(false);

  // Deep-link: /loadout/:id lands here with `loadoutId` set. Jump
  // straight to the view tab with the right row loaded instead of
  // dumping the user on discover. Ignored when id is missing.
  // B1 Boss-QA — when the requested id doesn't exist, fall through
  // to the lowest available public loadout instead of a dead-end
  // "not found" panel. Boss test path /loadout/1 always renders
  // SOMETHING valuable now, even on an installation where ids 1
  // and 2 were taken by deleted user loadouts.
  useEffect(() => {
    if (!loadoutId) return;
    let alive = true;
    (async () => {
     // Audit fix — the first fetchLoadout() below was un-caught: a
     // network reject escaped this IIFE and left `viewing` at null,
     // spinning forever. Wrap the whole body so any throw falls
     // through to the __notFound sentinel, which renders a friendly
     // panel with a "Browse public loadouts" escape.
     try {
      const data = await fetchLoadout(loadoutId);
      if (!alive) return;
      if (data) {
        // Backend now serves a server-side fallback for missing/private
        // ids: the response carries `redirectedFrom: <originalId>` plus
        // the resolved fallback loadout. Normalise to the same
        // `__redirectedFrom` flag the JS-side recovery uses (line ~810)
        // so the "Loadout #N doesn't exist" banner renders. Without
        // this, the user lands on a different loadout silently — the
        // URL still says /loadout/9999999 but the page shows id=3 with
        // no explanation. Presence of `redirectedFrom` in the payload
        // is itself the signal that a redirect happened (the API only
        // sets it on the fallback path).
        const apiRedirected = data.redirectedFrom != null
          ? Number(data.redirectedFrom)
          : null;
        setViewing(apiRedirected != null
          ? { ...data, __redirectedFrom: apiRedirected }
          : data);
        setTab('view');
        return;
      }
      // Try a recovery fallback to the lowest-id public loadout so
      // the page renders something useful instead of bouncing the
      // user out to Discover.
      try {
        const pub = await fetchPublicLoadouts('');
        const fallback = Array.isArray(pub) && pub.length > 0
          ? pub.slice().sort((a, b) => (a.id || 0) - (b.id || 0))[0]
          : null;
        if (!alive) return;
        if (fallback?.id) {
          const fresh = await fetchLoadout(fallback.id);
          if (!alive) return;
          if (fresh) {
            setViewing({ ...fresh, __redirectedFrom: Number(loadoutId) });
            setTab('view');
            return;
          }
        }
      } catch (_) {}
      setViewing({ __notFound: true });
      setTab('view');
     } catch (_) {
      // Audit fix — any un-handled reject above (first fetchLoadout)
      // lands here. Show the same friendly not-found panel instead of
      // leaving `viewing` null and the modal spinning forever.
      if (alive) { setViewing({ __notFound: true }); setTab('view'); }
     }
    })();
    return () => { alive = false; };
  }, [loadoutId]);

  // Race-condition guard — rapid tab switches (discover → mine →
  // favorites) used to fire overlapping requests with no ordering; a
  // slow `mine` response could overwrite a fresh `favorites` payload
  // because both completions called setList directly. Bump a request
  // id so only the most-recent in-flight load commits its result.
  const listReqId = useRef(0);
  const load = useCallback(async () => {
    const reqId = ++listReqId.current;
    setList(null);
    setListErr(false);
    try {
      let next = null;
      if (tab === 'discover') next = await fetchPublicLoadouts(search);
      else if (tab === 'mine' && me) next = await fetchMyLoadouts();
      else if (tab === 'favorites' && me) {
        const { fetchFavoriteLoadouts } = await import('./api.js');
        next = await fetchFavoriteLoadouts();
      }
      if (reqId === listReqId.current && next !== null) setList(next);
    } catch (_) {
      // Network / 5xx — flag so the list region swaps the infinite
      // spinner for an error panel whose Retry re-runs this load().
      if (reqId === listReqId.current) setListErr(true);
    }
  }, [tab, search, me]);
  useEffect(() => { load(); }, [load]);

  // Cache item pool for slot picker
  useEffect(() => {
    // Cleanup guard — if the loadout modal unmounts before the listings
    // fetch resolves (user closes the modal mid-load), calling
    // setAllItems / setPoolErr on the unmounted component logs a React
    // warning and wastes a render. Alive flag short-circuits both.
    let alive = true;
    fetchListings({}).then(listings => {
      if (!alive) return;
      const seen = new Set();
      const items = [];
      listings.forEach(l => {
        if (!l?.item || seen.has(l.item.id)) return;
        seen.add(l.item.id);
        items.push(l.item);
      });
      setAllItems(items);
    }).catch(() => {
      // Audit fix — without a .catch() a rejected listings fetch left
      // the slot picker's item pool permanently empty with no feedback.
      // Flag it so SlotPicker shows a "couldn't load items" hint.
      if (alive) setPoolErr(true);
    });
    return () => { alive = false; };
  }, []);

  const openLoadout = async (id) => {
    // Guard the fetch — fetchLoadout returns null on a 404 and REJECTS on
    // a network error / non-404 5xx. Either way, leaving `viewing` null
    // while tab==='view' renders an indefinite spinner. Coerce both to
    // the `__notFound` sentinel so the friendly not-found panel shows,
    // mirroring the deep-link effect's recovery.
    let data;
    try {
      data = await fetchLoadout(id);
    } catch (_) {
      data = null;
    }
    setViewing(data || { __notFound: true });
    setTab('view');
  };

  const handleCreate = async () => {
    if (!newName.trim()) return;
    const trimmed = newName.trim();
    const created = await createLoadout({ name: trimmed, visibility: 'PUBLIC' });
    // Pre-fix: error check only matched `error`, not `code` — backend
    // returns INVALID_PARAMETER / RATE_LIMITED via `code` so a rate-
    // limited create looked silent. Aligned with handleLockSlot pattern.
    if (created && (created.error || created.code)) {
      toast(created.message || created.error || 'Could not create loadout', 'err');
      return;
    }
    setNewName('');
    setCreating(false);
    if (created && created.id) openLoadout(created.id);
    // Pre-fix: success path was silent — the modal switched to the new
    // loadout view but a screen-reader user got no announcement, and
    // even sighted users seeing a slot grid full of "Empty slot" rows
    // couldn't tell at a glance whether their loadout was actually
    // saved. Mirror handleDelete's named-success toast at line 952.
    toast(`Created loadout "${trimmed}". Generate items or pin slots from your wishlist to fill it.`, 'ok');
  };

  const handleSlot = async (slot, itemId) => {
    if (!viewing) return;
    const res = await setLoadoutSlot(viewing.loadout.id, slot, itemId);
    // Pre-fix: error check only matched `res.error`, missing the
    // `code`-shaped errors (INVALID_PARAMETER, RATE_LIMITED, FORBIDDEN
    // when the user isn't the owner of the loadout). A rate-limited
    // pin-slot click looked silent — slot didn't update, no toast, no
    // hint why. Same fix-pattern handleCreate (line 890) and
    // handleLockSlot (line 916) already use.
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not update slot', 'err');
      return;
    }
    const fresh = await fetchLoadout(viewing.loadout.id);
    setViewing(fresh);
  };

  const handleLockSlot = async (slot) => {
    if (!viewing) return;
    const res = await lockLoadoutSlot(viewing.loadout.id, slot);
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not toggle lock', 'err');
      return;
    }
    const fresh = await fetchLoadout(viewing.loadout.id);
    setViewing(fresh);
    toast(res.locked ? `${slot} slot locked — Generate will skip it.` : `${slot} slot unlocked.`, 'ok');
  };

  // Batch 839 — busy state + success feedback on Generate. Previously
  // no spinner or toast; a user clicking the button saw nothing until
  // the loadout reloaded (which could be a second or two on a cold DB
  // query). Silent runs read as "did my click register?".
  const [generatingLoadout, setGeneratingLoadout] = useState(false);
  const handleGenerate = async () => {
    if (!viewing || generatingLoadout) return;
    // Tolerant parse — strip `$`, commas, currency code prefixes so a user
    // who types `$50`, `1,200.00`, or `USD 75` doesn't get a silent NaN.
    // The backend mirrors this sanitization, but parsing here lets us
    // short-circuit a round-trip on truly empty input.
    const cleaned = String(budget || '').replace(/[^0-9.\-]/g, '');
    const parsed = cleaned ? parseFloat(cleaned) : NaN;
    const b = Number.isFinite(parsed) && parsed > 0 ? parsed : 10000;
    setGeneratingLoadout(true);
    try {
      const res = await generateLoadout(viewing.loadout.id, b);
      // Pre-fix: missed `code`-shaped errors (RATE_LIMITED on rapid
      // re-roll, FORBIDDEN if the user lost ownership, INVALID_PARAMETER
      // for a malformed budget). Same baseline as handleSlot/handleDelete.
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not generate loadout', 'err');
        return;
      }
      const fresh = await fetchLoadout(viewing.loadout.id);
      setViewing(fresh);
      // Count how many slots were actually filled for the success
      // toast — if everything was locked or the budget was impossibly
      // low, the user gets "Generated 0 slots — raise the budget" as
      // a concrete nudge instead of a silent no-op. When some slots
      // got filled but others didn't (budget ran out partway), surface
      // the empty slot names explicitly so the user knows where the
      // shortfall landed instead of squinting at an 8-tile grid.
      const slotsAfter = Array.isArray(fresh?.slots) ? fresh.slots : [];
      // Count slots Generate actually changed — diff item ids against the
      // pre-generate state. Counting only `!locked && itemId` (a) under-
      // counted a run that filled around locked slots and (b) false-
      // alarmed "filled 0 slots" when every slot was already locked-and-
      // filled. Diffing by item id also counts a re-roll correctly.
      const slotsBefore = Array.isArray(viewing?.slots) ? viewing.slots : [];
      const beforeById = new Map(slotsBefore.map(s => [s.slot, s.itemId]));
      const changed = slotsAfter.filter(s => s.itemId != null && beforeById.get(s.slot) !== s.itemId).length;
      const emptyNames = slotsAfter
        .filter(s => s.itemId == null && !s.locked)
        .map(s => s.slot);
      const totalFilled = slotsAfter.filter(s => s.itemId != null).length;
      if (changed === 0 && emptyNames.length > 0) {
        toast('Generate ran but filled no new slots — raise the budget or unlock a slot.', 'err');
      } else if (emptyNames.length > 0) {
        toast(`Generated ${changed} slot${changed === 1 ? '' : 's'} — not enough budget to fill ${emptyNames.join(', ')}. Raise the cap or relock to retry.`, 'ok');
      } else if (changed === 0) {
        toast(`Loadout already complete — all ${totalFilled} slots filled.`, 'ok');
      } else {
        toast(`Generated ${changed} slot${changed === 1 ? '' : 's'} — loadout complete (${totalFilled}/${slotsAfter.length} filled).`, 'ok');
      }
    } finally { setGeneratingLoadout(false); }
  };

  const handleDelete = async () => {
    if (!viewing) return;
    const name = viewing.loadout?.name || 'this loadout';
    if (!confirm(`Delete "${name}"?\n\nThis can't be undone — the slots, favorites, and share link all go away.`)) return;
    const res = await deleteLoadout(viewing.loadout.id);
    // Pre-fix: missed `code`-shaped errors (FORBIDDEN if the user isn't
    // the owner, RATE_LIMITED, etc). Same baseline gap fixed in
    // handleSlot + handleCreate this lap.
    if (res && (res.error || res.code)) {
      toast(res.message || res.error || 'Could not delete loadout', 'err');
      return;
    }
    setViewing(null);
    setTab('mine');
    load();
    // Batch 920 — name the deleted loadout in the toast so a user
    // deleting multiple in a row sees which one just went.
    toast(`Loadout "${name}" deleted.`, 'ok');
  };

  // 404 branch — deep-link to a missing or private loadout. Kept above
  // the normal view so the rendered tab doesn't try to dereference
  // viewing.loadout.ownerUserId on the sentinel object.
  if (tab === 'view' && viewing && viewing.__notFound) {
    // Reflect the not-found state in the page title too so browser tab +
    // history don't read as a generic "Loadout · SkinBox" placeholder.
    try {
      const currentPrefix = (document.title.match(/^(\([^)]+\)\s+)/) || [, ''])[1];
      document.title = currentPrefix + `Loadout not found · ${BRAND.name}`;
    } catch (_) {}
    return h(InfoModal, { title: 'Loadout', onClose },
      h('div', { className: 'empty-inline', style: { padding: '32px 16px' } },
        h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'search_off', size: 26 })),
        h('h2', { style: { fontSize: 16, fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 6px' } }, 'Loadout not found'),
        h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
          "This loadout doesn't exist, was deleted, or is private. Try Discover to browse public sets."),
        h('button', { className: 'btn btn-accent', onClick: () => { setViewing(null); setTab('discover'); } },
          'Browse public loadouts')
      )
    );
  }
  if (tab === 'view' && viewing) {
    const isOwner = me && viewing.loadout?.ownerUserId === me.id;
    const SLOT_NAMES = ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild'];
    // Defensive: every live path supplies `slots`, but a slots-less body that
    // isn't flagged __notFound must not crash the page with `.map of undefined`.
    // Mirrors the Array.isArray(viewing?.slots) guards used elsewhere here.
    const slotsBySlot = Object.fromEntries((Array.isArray(viewing.slots) ? viewing.slots : []).map(s => [s.slot, s]));
    const redirectedFrom = viewing.__redirectedFrom;

    return h(InfoModal, { title: viewing.loadout.name, onClose },
      // B1 Boss-QA — when /loadout/{missingId} silently redirected to
      // a real public loadout, surface the redirect so the URL bar
      // doesn't lie about what's on screen.
      redirectedFrom && h('div', {
        style: {
          padding: '10px 14px', marginBottom: 14, borderRadius: 8,
          background: 'rgba(30,165,255,0.08)',
          border: '1px solid rgba(30,165,255,0.30)',
          color: 'var(--text-secondary)',
          fontSize: 12, lineHeight: 1.5
        }
      },
        h('strong', { style: { color: 'var(--text-primary)' } },
          `Loadout #${redirectedFrom} doesn't exist`),
        ' — showing the lowest-id public loadout instead. ',
        h('a', {
          href: '/loadout',
          style: { color: 'var(--accent)', textDecoration: 'none', fontWeight: 700 }
        }, 'Browse all public loadouts →')
      ),
      h('div', { className: 'loadout-action-row', style: { display: 'flex', gap: 10, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' } },
        h('button', { className: 'btn btn-ghost', onClick: () => { setViewing(null); setTab(isOwner ? 'mine' : 'discover'); } }, '← Back'),
        h('div', { className: 'loadout-action-spacer', style: { flex: 1, minWidth: 0 } }),
        h('div', { style: { color: 'var(--text-muted)', fontSize: 12, alignSelf: 'center', whiteSpace: 'nowrap' } },
          'By ', h('strong', { style: { color: 'var(--text-secondary)' } }, viewing.loadout.ownerName || 'anon'),
          ' · ❤ ', viewing.loadout.favorites
        ),
        !isOwner && me && h('button', {
          className: 'btn btn-ghost',
          title: 'Copy this loadout to your own stable — starts as PRIVATE + unlocked so you can retune before publishing',
          onClick: async () => {
            const res = await cloneLoadout(viewing.loadout.id);
            if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not clone loadout', 'err'); return; }
            toast(`Cloned to "${res?.name || 'your loadouts'}".`, 'ok');
            // Re-open the copy so the user lands directly on the new loadout.
            if (res?.id) openLoadout(res.id);
            else load();
          }
        }, '⎘ Clone'),
        !isOwner && !me && h('button', {
          className: 'btn btn-ghost',
          // Boss QA cycle 3 C3-6 — shorter label so the loadout-header
          // button row doesn't word-wrap to two lines on a 390px viewport.
          title: 'Sign in with Steam to clone this loadout',
          onClick: () => signInWithSteam(),
          style: { whiteSpace: 'nowrap' }
        }, 'Sign in to clone'),
        !isOwner && me && h('button', {
          className: `btn ${viewing.favorited ? 'btn-accent' : 'btn-ghost'}`,
          'aria-pressed': !!viewing.favorited,
          title: viewing.favorited
            ? 'Remove from your Favorites tab'
            : 'Save to your Favorites tab so you can re-find it later',
          onClick: async () => {
            // Batch 917 — use the returned `favorited` + `favorites`
            // to refresh the viewing state in place (optimistic + toast)
            // instead of re-fetching the full list. Matches the star-
            // button pattern on item cards.
            const wasFavorited = !!viewing.favorited;
            const res = await favoriteLoadout(viewing.loadout.id);
            if (res && (res.error || res.code)) {
              toast(res.message || res.error || 'Could not update favorite', 'err');
              return;
            }
            setViewing(prev => prev ? {
              ...prev,
              favorited: res?.favorited ?? !wasFavorited,
              loadout: { ...prev.loadout, favorites: res?.favorites ?? prev.loadout?.favorites ?? 0 }
            } : prev);
            const name = viewing.loadout?.name || 'loadout';
            toast(res?.favorited
              ? `Saved "${name}" to your Favorites tab.`
              : `Removed "${name}" from Favorites.`,
              'ok');
          }
        }, viewing.favorited ? '♥ Favorited' : '♡ Favorite'),
        !isOwner && !me && h('button', {
          className: 'btn btn-ghost',
          title: 'Sign in with Steam to favorite this loadout',
          onClick: () => signInWithSteam()
        }, '♡ Favorite'),
        isOwner && h('button', {
          className: 'btn btn-ghost',
          title: 'Rename this loadout',
          // Batch 833 — open the inline rename drawer instead of
          // window.prompt. The drawer renders below the toolbar and
          // focuses the input; Enter saves, Esc cancels, the in-
          // flight save is busy-guarded.
          onClick: () => {
            setRenameDraft(viewing.loadout.name || '');
            setRenaming(true);
          }
        }, '✎ Rename'),
        isOwner && h('button', {
          className: 'btn btn-ghost',
          title: viewing.loadout.visibility === 'PUBLIC'
            ? 'Make PRIVATE — hide from Discover. Only you can view it; its link stops working for everyone else.'
            : 'Make PUBLIC — show in Discover so others can favorite + clone.',
          onClick: async () => {
            const nextVis = viewing.loadout.visibility === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC';
            const res = await updateLoadout(viewing.loadout.id, { visibility: nextVis });
            if (res && (res.error || res.code)) { toast(res.message || res.error || 'Could not change visibility', 'err'); return; }
            toast(`Loadout is now ${nextVis}.`, 'ok');
            // 2026-05-20 audit fix — `load()` refetches the Discover/Mine
            // *list*, not the open `viewing` loadout, so the toggle's own
            // label + tooltip stayed stale and a second click recomputed
            // nextVis from the old value (sending the wrong target).
            // Reflect the new visibility in `viewing` in place, matching
            // the favorite handler's optimistic-update pattern above.
            setViewing(prev => prev ? {
              ...prev,
              loadout: { ...prev.loadout, visibility: nextVis }
            } : prev);
            await load();
          }
        }, viewing.loadout.visibility === 'PUBLIC' ? 'Public' : 'Private'),
        isOwner && h('button', { className: 'btn btn-ghost', style: { color: 'var(--red)', border: '1px solid rgba(248,113,113,0.3)' }, onClick: handleDelete }, 'Delete'),
        // Admin takedown — wires /api/admin/loadouts/{id} DELETE for TOS
        // violations (PII in descriptions, harassment in names, etc). The
        // owner is notified + the action is audited server-side. Hidden
        // from the owner themselves (they already have a Delete button)
        // and from non-staff viewers.
        !isOwner && me && me.role === 'ADMIN' && h('button', {
          className: 'btn btn-ghost',
          style: { color: 'var(--red)', border: '1px solid rgba(248,113,113,0.4)' },
          'aria-haspopup': 'dialog',
          'aria-expanded': adminRemoveDraft !== null,
          title: 'Admin override — remove this loadout for TOS violation. Owner is notified.',
          onClick: () => setAdminRemoveDraft(adminRemoveDraft === null ? 'Violates the community guidelines' : null)
        }, 'Admin remove')
      ),
      adminRemoveDraft !== null && h('div', { style: { marginTop: 10 } },
        h(ReasonDrawer, {
          title: 'Remove this loadout',
          hint: 'Reason is sent to the owner and logged in the audit trail.',
          initial: adminRemoveDraft,
          cta: 'Remove loadout',
          busy: adminRemoveBusy,
          onCancel: () => setAdminRemoveDraft(null),
          onSubmit: async (reason) => {
            if (!reason) return;
            setAdminRemoveBusy(true);
            try {
              const r = await fetch(`/api/admin/loadouts/${viewing.loadout.id}`, {
                method: 'DELETE',
                credentials: 'same-origin',
                headers: {
                  'Content-Type': 'application/json',
                  'X-CSRF-Token': (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || ''
                },
                body: JSON.stringify({ reason })
              });
              if (!r.ok) { toast(`Admin remove failed (HTTP ${r.status})`, 'err'); return; }
              toast('Loadout removed. Owner has been notified.', 'ok');
              setAdminRemoveDraft(null);
              setViewing(null);
              setTab('discover');
              load();
            } catch (_) {
              toast('Network error removing loadout', 'err');
            } finally { setAdminRemoveBusy(false); }
          }
        })
      ),
      isOwner && h('div', { className: 'loadout-tools' },
        h('input', {
          className: 'price-input',
          placeholder: `Max budget · ${fmt(10000)}`,
          value: budget,
          onChange: e => setBudget(e.target.value),
          style: { width: 170 },
          // inputMode/title clarifies USD intent — typed `$` and commas are
          // stripped before parse so the user can paste a price chip from
          // /wallet without a NaN.
          inputMode: 'decimal',
          title: 'Cap on AI-generate. Defaults to $10,000 if blank. $-prefix and commas are accepted.',
          'aria-label': 'Max budget for Generate'
        }),
        h('button', {
          className: 'btn btn-accent',
          onClick: handleGenerate,
          disabled: generatingLoadout,
          title: 'Auto-fill unlocked slots with the cheapest active listing per category, capped at the budget. Locked slots are preserved.'
        }, generatingLoadout ? '…' : 'Generate'),
        h('div', { style: { color: 'var(--text-muted)', fontSize: 12, marginLeft: 'auto' } },
          'Total Value · ',
          h('strong', { style: { color: 'var(--accent)' } }, fmt(viewing.loadout.totalValue))
        )
      ),
      // Batch 833 — inline rename panel. Replaces window.prompt.
      isOwner && renaming && h('div', {
        style: {
          display: 'flex', gap: 8, alignItems: 'center',
          padding: '10px 14px', marginBottom: 10, borderRadius: 8,
          background: 'rgba(30,165,255,0.06)',
          border: '1px solid var(--accent-border)'
        }
      },
        h('span', { style: { fontSize: 11, color: 'var(--text-muted)' } }, 'Rename:'),
        h('input', {
          className: 'price-input',
          'aria-label': 'New loadout name',
          value: renameDraft,
          // The server keeps 80 characters (TextSanitizer.LIMIT_SHORT).
          maxLength: 80,
          onChange: e => setRenameDraft(e.target.value),
          onKeyDown: async (e) => {
            if (e.key === 'Enter' && !renameBusy) {
              e.preventDefault();
              const trimmed = (renameDraft || '').trim();
              if (!trimmed || trimmed === viewing.loadout.name) {
                setRenaming(false); return;
              }
              setRenameBusy(true);
              try {
                const res = await updateLoadout(viewing.loadout.id, { name: trimmed });
                if (res && (res.error || res.code)) {
                  toast(res.message || res.error || 'Rename failed', 'err');
                  return;
                }
                setRenaming(false);
                // 2026-05-20 audit fix — `load()` refetches the list, not
                // the open loadout, so the modal title (viewing.loadout.name)
                // and the Save-disabled comparison stayed on the old name
                // despite the "renamed" toast. Update `viewing` in place.
                setViewing(prev => prev ? {
                  ...prev,
                  loadout: { ...prev.loadout, name: (res && res.name) || trimmed }
                } : prev);
                toast('Loadout renamed.', 'ok');
                await load();
              } finally { setRenameBusy(false); }
            } else if (e.key === 'Escape') {
              e.preventDefault();
              setRenaming(false);
            }
          },
          style: { flex: 1, minWidth: 200 },
          autoFocus: true,
          placeholder: 'Loadout name…'
        }),
        h('button', {
          className: 'btn btn-accent',
          style: { padding: '4px 12px', fontSize: 11 },
          disabled: renameBusy || !renameDraft.trim() || renameDraft.trim() === viewing.loadout.name,
          onClick: async () => {
            const trimmed = (renameDraft || '').trim();
            if (!trimmed || trimmed === viewing.loadout.name) return;
            setRenameBusy(true);
            try {
              const res = await updateLoadout(viewing.loadout.id, { name: trimmed });
              if (res && (res.error || res.code)) {
                toast(res.message || res.error || 'Rename failed', 'err');
                return;
              }
              setRenaming(false);
              // 2026-05-20 audit fix — see the Enter-key handler above:
              // `load()` doesn't refresh the open loadout, so the title
              // stayed stale. Update `viewing` in place.
              setViewing(prev => prev ? {
                ...prev,
                loadout: { ...prev.loadout, name: (res && res.name) || trimmed }
              } : prev);
              toast('Loadout renamed.', 'ok');
              await load();
            } finally { setRenameBusy(false); }
          }
        }, renameBusy ? '…' : 'Save'),
        h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '4px 10px', fontSize: 11 },
          disabled: renameBusy,
          onClick: () => setRenaming(false)
        }, 'Cancel')
      ),
      h('div', { className: 'loadout-grid' },
        SLOT_NAMES.map(slotName => {
          const s = slotsBySlot[slotName];
          return h('div', { key: slotName, className: 'loadout-slot' },
            h('div', { className: 'loadout-slot-label' }, slotName),
            s && s.itemId
              ? h('div', {
                  className: 'loadout-slot-filled',
                  style: s.locked ? {
                    borderColor: 'var(--accent)',
                    boxShadow: 'inset 0 0 0 1px rgba(30,165,255,0.35)'
                  } : undefined
                },
                  // Real product thumbnail when the backend has one,
                  // emoji glyph as fallback. Backend now decorates each
                  // slot with itemImageUrl + itemAccentColor by joining
                  // through to the Item table at read time.
                  s.itemImageUrl
                    ? h('img', {
                        src: s.itemImageUrl,
                        alt: s.itemName || '',
                        className: 'loadout-slot-img',
                        loading: 'lazy',
                        // Steam CDN 403s hotlinked (cross-origin Referer) requests —
                        // strip the referrer so the thumbnail loads; hide a genuinely
                        // broken img rather than show the broken-image glyph.
                        referrerPolicy: 'no-referrer',
                        onError: (e) => { e.currentTarget.style.display = 'none'; },
                        style: {
                          width: 96, height: 96, objectFit: 'contain',
                          background: s.itemAccentColor ? `${s.itemAccentColor}33` : 'rgba(255,255,255,0.04)',
                          borderRadius: 8,
                          padding: 6,
                          margin: '0 auto 6px',
                          display: 'block'
                        }
                      })
                    : h('div', { className: 'loadout-slot-emoji' }, s.itemEmoji || '—'),
                  h('div', { className: 'loadout-slot-name' }, s.itemName),
                  h('div', { className: 'loadout-slot-price' }, fmt(s.snapshotPrice)),
                  // Lock toggle (owner-only). Locked slots survive the
                  // Generate autopicker — CSFloat-parity behaviour for a
                  // user who wants a specific item but is happy to
                  // autofill the rest around it. Styled to co-exist
                  // with the ✕ clear button already living in the
                  // corner: lock sits just left of the clear, one
                  // shared row.
                  isOwner && h('button', {
                    className: 'loadout-slot-lock',
                    onClick: () => handleLockSlot(slotName),
                    title: s.locked
                      ? 'Locked — Generate will skip this slot. Click to unlock.'
                      : 'Lock this slot so Generate keeps it.',
                    'aria-label': s.locked ? `Unlock ${slotName} slot` : `Lock ${slotName} slot`,
                    style: {
                      position: 'absolute', top: 4, right: 26,
                      width: 18, height: 18, padding: 0,
                      border: 'none', background: 'transparent',
                      cursor: 'pointer', fontSize: 11, lineHeight: 1,
                      color: s.locked ? 'var(--accent)' : 'var(--text-muted)',
                      opacity: s.locked ? 1 : 0.55
                    }
                  }, h(MaterialIcon, { name: s.locked ? 'lock' : 'lock_open', size: 14 })),
                  isOwner && h('button', { className: 'loadout-slot-clear', onClick: () => handleSlot(slotName, null), title: 'Clear', 'aria-label': `Clear ${slotName} slot` }, '✕')
                )
              : h('div', { className: 'loadout-slot-empty' },
                  isOwner
                    ? h(SlotPicker, { slot: slotName, allItems, poolErr, onPick: (id) => handleSlot(slotName, id) })
                    : h('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, 'empty')
                )
          );
        })
      )
    );
  }

  return h(InfoModal, { title: 'Loadout Lab', onClose },
    // CSFloat-1:1 + WAI-ARIA tabs pattern: role=tablist on the container,
    // role=tab + aria-selected + roving tabindex on each tab. Arrow / Home /
    // End navigation matches the WalletModal pattern (modals.js:12906) so
    // /loadout's tabs feel identical to /wallet's for keyboard users.
    (() => {
      const TABS = me ? ['discover', 'mine', 'favorites'] : ['discover'];
      const labels = { discover: 'Discover', mine: 'My Loadouts', favorites: 'Favorites' };
      const onKey = (e) => {
        if (!['ArrowRight','ArrowLeft','Home','End'].includes(e.key)) return;
        e.preventDefault();
        const idx = TABS.indexOf(tab);
        let n = idx;
        if (e.key === 'ArrowRight') n = (idx + 1) % TABS.length;
        else if (e.key === 'ArrowLeft') n = (idx - 1 + TABS.length) % TABS.length;
        else if (e.key === 'Home') n = 0;
        else if (e.key === 'End') n = TABS.length - 1;
        setTab(TABS[n]);
      };
      return h('div', { className: 'loadout-tabs', role: 'tablist', 'aria-label': 'Loadout sections' },
        TABS.map(id => h('button', {
          key: id,
          className: `offer-tab ${tab === id ? 'active' : ''}`,
          role: 'tab',
          'aria-selected': tab === id,
          // WCAG 4.1.2 — aria-controls points at the result-region wrapper
          // around the list ternary below. All three tabs share the same
          // panel since the active tab swaps the source list (mine /
          // favorites / discover) but renders into the same area.
          'aria-controls': 'loadout-results',
          tabIndex: tab === id ? 0 : -1,
          onClick: () => setTab(id),
          onKeyDown: onKey
        }, labels[id])),
        h('div', { style: { flex: 1 } }),
        me && h('button', { className: 'btn btn-accent', onClick: () => setCreating(c => !c) }, creating ? 'Cancel' : '+ Create')
      );
    })(),
    creating && h('div', { style: { marginBottom: 14, display: 'flex', gap: 10 } },
      h('input', { className: 'price-input', 'aria-label': 'New loadout name', placeholder: 'Loadout name…', value: newName, onChange: e => setNewName(e.target.value), style: { flex: 1 } }),
      h('button', { className: 'btn btn-accent', onClick: handleCreate, disabled: !newName.trim() }, 'Create')
    ),
    tab === 'discover' && h('input', {
      className: 'price-input',
      placeholder: 'Search loadouts…',
      'aria-label': 'Search public loadouts',
      style: { width: '100%', marginBottom: 14 },
      value: searchInput,
      onChange: e => setSearchInput(e.target.value)
    }),
    // Wrap the result-region ternary so the role=tab buttons above can
    // resolve their aria-controls="loadout-results" pointer (WCAG 4.1.2).
    h('div', { id: 'loadout-results' },
    listErr
      /* Audit fix — fetchPublicLoadouts/fetchMyLoadouts rejected. Without
         this branch `list` stayed null and the spinner below spun
         forever. Surface an error with a Retry that re-runs load(). */
      ? h('div', { className: 'empty-inline', role: 'alert' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            "Couldn't load loadouts"),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 14px' } },
            'This list failed to load. Check your connection and try again.'),
          h('button', { className: 'btn btn-accent', onClick: () => load() }, 'Retry'))
      : list === null
      ? h('div', { className: 'spinner' })
      : list.length === 0
        ? (() => {
            // Batch 868 — distinguish "search matched nothing" from
            // "tab is genuinely empty" so a typo doesn't look like a
            // dead feature. Same pattern as the marketplace grid's
            // empty state.
            const searching = search.trim().length > 0;
            if (searching) {
              return h('div', { className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'search_off', size: 26 })),
                h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 10 } },
                  'No loadouts match "', h('strong', null, search.trim()), '".'),
                h('button', {
                  className: 'btn btn-ghost',
                  style: { border: '1px solid var(--border)' },
                  onClick: () => { setSearchInput(''); setSearch(''); }
                }, 'Clear search'));
            }
            // Batch 915 — per-tab empty state with concrete CTAs.
            // Prior copy told the user what to do ("Create one to get
            // started") but had no button, so users had to scroll back
            // up to the toolbar. Matches the empty-state pattern from
            // batches 900-902 / 914 across the app.
            if (tab === 'mine') {
              return h('div', { className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'checkroom', size: 26 })),
                h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
                  'No loadouts yet'),
                h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 14px', lineHeight: 1.55 } },
                  "Build a named head-to-toe set from the catalogue — try mixing a hat + shirt + shoes — and publish it so other s&box players can copy-buy."),
                h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
                  h('button', {
                    className: 'btn btn-accent',
                    style: { padding: '10px 18px', fontWeight: 700 },
                    onClick: () => setCreating(true)
                  }, '＋ Create my first loadout'),
                  h('button', {
                    className: 'btn btn-ghost',
                    style: { border: '1px solid var(--border)', padding: '10px 18px' },
                    onClick: () => setTab('discover'),
                    title: 'See what other builders have shared — good inspiration for your first loadout.'
                  }, 'Browse Discover')
                )
              );
            }
            if (tab === 'favorites') {
              return h('div', { className: 'empty-inline' },
                h('div', { className: 'empty-icon' }, '❤'),
                h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
                  'No favorites yet'),
                h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 14px', lineHeight: 1.55 } },
                  "Hit the ❤ on any public loadout and it shows up here. Great for bookmarking sets you want to copy-buy later."),
                h('button', {
                  className: 'btn btn-accent',
                  style: { padding: '10px 18px', fontWeight: 700 },
                  onClick: () => setTab('discover')
                }, 'Browse public loadouts →')
              );
            }
            // Default: Discover tab with zero public loadouts — rare path
            // (only fires on an empty platform) but worth an explicit CTA
            // for signed-in users so the first mover has a clear jump-in.
            return h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'public', size: 26 })),
              h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
                'No public loadouts yet'),
              h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 400, margin: '0 auto 14px', lineHeight: 1.55 } },
                "Be the first to share. Build a set, flip it public, and it'll land here for every visitor to browse."),
              me
                ? h('button', {
                    className: 'btn btn-accent',
                    style: { padding: '10px 18px', fontWeight: 700 },
                    onClick: () => { setTab('mine'); setCreating(true); }
                  }, 'Create the first loadout')
                /* Anon empty state used to dead-end with copy and no CTA.
                   Send them to Steam OpenID so they can become the first
                   mover instead of bouncing. */
                : h('button', {
                    className: 'btn btn-accent',
                    style: { padding: '10px 18px', fontWeight: 700 },
                    onClick: () => signInWithSteam()
                  }, 'Sign in to create a loadout')
            );
          })()
        : h('div', { className: 'loadout-list' },
            list.map(l => h('div', {
              key: l.id, className: 'loadout-card',
              onClick: () => openLoadout(l.id),
              role: 'button',
              tabIndex: 0,
              'aria-label': `Open loadout ${l.name}${l.ownerName ? ' by ' + l.ownerName : ''} (${l.favorites} favorites, ${fmt(l.totalValue)})`,
              onKeyDown: (e) => {
                const tag = (e.target?.tagName || '').toLowerCase();
                if (tag === 'button' || tag === 'a') return;
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  openLoadout(l.id);
                }
              }
            },
              h('div', { className: 'loadout-card-name' }, highlightMatch(l.name || '', search)),
              h('div', { className: 'loadout-card-meta' },
                h('span', null, '❤ ', l.favorites),
                h('span', null, fmt(l.totalValue)),
                l.filledSlots != null && h('span', null, l.filledSlots, l.filledSlots === 1 ? ' item' : ' items')
              ),
              // Item-preview strip (csfloat app-overview-entry pattern) — a
              // row of the loadout's equipped items so the card shows its
              // contents at a glance instead of rendering as an empty block.
              // previewItems is decorated server-side; absent on any legacy
              // payload, in which case the strip self-hides.
              Array.isArray(l.previewItems) && l.previewItems.length > 0 && h('div', { className: 'loadout-card-preview' },
                l.previewItems.slice(0, 8).map((pi, idx) => h('div', {
                  key: 'lcp-' + l.id + '-' + idx,
                  className: 'loadout-card-preview-tile',
                  title: (pi.itemName || pi.slot || '') + (pi.snapshotPrice != null ? ' · ' + fmt(pi.snapshotPrice) : ''),
                  style: pi.accentColor ? { borderColor: pi.accentColor } : null
                },
                  pi.imageUrl
                    ? h('img', { className: 'loadout-card-preview-img', src: pi.imageUrl, alt: '', loading: 'lazy', referrerPolicy: 'no-referrer', onError: (e) => { e.currentTarget.style.display = 'none'; } })
                    : h('span', { className: 'loadout-card-preview-emoji' }, pi.itemEmoji || '◆')
                ))
              ),
              l.ownerName && h('div', { className: 'loadout-card-owner' },
                'by ', highlightMatch(l.ownerName, search)),
              // Share / copy-link — stops the card-open click, grabs the
              // canonical /loadout/:id URL, and drops it on the clipboard.
              // Sellers / loadout curators paste this into Discord / Steam
              // groups; same affordance as the stall share button. Hidden on
              // PRIVATE loadouts: the link 404s for anyone but the owner.
              l.visibility !== 'PRIVATE' && h('button', {
                className: 'loadout-card-share',
                title: 'Copy link to this loadout',
                'aria-label': 'Copy link',
                onClick: async (e) => {
                  e.stopPropagation();
                  const url = `${window.location.origin}/loadout/${l.id}`;
                  // Batch 879 — personalise the share payload so a
                  // Discord/X preview reads "Wizard cosplay loadout on
                  // SkinBox · by @Bob · $47" instead of the raw name
                  // alone. Matches the stall-share improvement in
                  // batches 877–878.
                  const title = l.name
                    ? `${l.name} · ${BRAND.name} loadout`
                    : `${BRAND.name} loadout`;
                  const text = [
                    l.name ? `"${l.name}"` : 'Loadout',
                    l.ownerName ? `by @${l.ownerName}` : null,
                    l.totalValue != null && parseFloat(l.totalValue) > 0
                      ? `${fmt(l.totalValue)} total`
                      : null
                  ].filter(Boolean).join(' · ') +
                    ` — clone or favorite it on ${BRAND.name}.`;
                  try {
                    if (navigator.share) { await navigator.share({ title, text, url }); return; }
                    if (navigator.clipboard?.writeText) {
                      await navigator.clipboard.writeText(url);
                      toast('Loadout link copied.', 'ok');
                    } else {
                      window.prompt('Copy this loadout link:', url);
                    }
                  } catch (_) { window.prompt('Copy this loadout link:', url); }
                }
              }, h(MaterialIcon, { name: 'content_copy', size: 16 }))
            ))
          )
    )
  );
}

function SlotPicker({ slot, allItems, poolErr, onPick }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const filtered = useMemo(() => {
    let pool = allItems;
    if (slot !== 'Wild') pool = pool.filter(i => i.category === slot);
    if (q) pool = pool.filter(i => i.name.toLowerCase().includes(q.toLowerCase()));
    return pool.slice(0, 20);
  }, [allItems, slot, q]);

  if (!open) {
    return h('button', { className: 'loadout-add-btn', onClick: () => setOpen(true) }, '+ Add');
  }
  return h('div', { className: 'loadout-picker' },
    // Audit fix — aria-label was a stale "Filter loadouts" copy-paste
    // from the Discover search box. This input filters the ITEM pool for
    // a specific clothing slot, so a screen-reader user heard the wrong
    // thing. Name it after what it actually does.
    h('input', { className: 'price-input', autoFocus: true, 'aria-label': `Filter ${slot} items`, placeholder: 'Filter…', value: q,
      onChange: e => setQ(e.target.value),
      // Escape closes JUST the picker (matches the rename drawer). stopPropagation
      // keeps the InfoModal's document-level Escape from closing the whole
      // /loadout page when the user only meant to back out of item-pick.
      onKeyDown: e => { if (e.key === 'Escape') { e.stopPropagation(); setOpen(false); } },
      style: { width: '100%', marginBottom: 6 } }),
    h('div', { className: 'loadout-picker-list' },
      // Audit fix — when the catalogue pool fetch failed, `allItems` is
      // empty for every slot. Without a hint the list rendered blank and
      // read as "no items exist". Surface the load failure instead.
      filtered.length === 0 && h('div', {
        style: { padding: 10, fontSize: 11, color: poolErr ? 'var(--red)' : 'var(--text-muted)' }
      },
        poolErr
          ? "Couldn't load items — check your connection and reopen the loadout."
          : 'No items match.'),
      filtered.map(it => h('div', {
        key: it.id, className: 'loadout-picker-item',
        onClick: () => { onPick(it.id); setOpen(false); },
        role: 'button',
        tabIndex: 0,
        'aria-label': `Add ${it.name} to the ${slot} slot (${fmt(it.lowestPrice)})`,
        onKeyDown: (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onPick(it.id);
            setOpen(false);
          }
        }
      },
        h('span', { style: { fontSize: 16 } }, ({Hats:'◈',Jackets:'▲',Shirts:'■',Pants:'▮',Gloves:'◉',Boots:'▼',Accessories:'◆',Workshop:'❖'})[it.category] || '—'),
        h('span', { style: { fontSize: 12, flex: 1, minWidth: 0 } }, it.name),
        h('span', { style: { fontSize: 11, color: (parseFloat(it.lowestPrice) > 0 ? 'var(--accent)' : 'var(--text-muted)'), fontFamily: "'Roboto Mono', 'JetBrains Mono', monospace" } },
          parseFloat(it.lowestPrice) > 0 ? fmt(it.lowestPrice) : '—')
      ))
    ),
    h('button', { className: 'loadout-add-btn', style: { marginTop: 6 }, onClick: () => setOpen(false) }, 'Cancel')
  );
}

// ── Notifications (full feed) ───────────────────────────────────
export function NotificationsModal({ onClose, me }) {
  // Grouped-by-day rendering with a Today / Yesterday / date-label header per
  // section and an unread-only filter. Mirrors the way CSFloat (and Slack)
  // organise high-volume feeds so scanning a week of notifications is fast.
  const [data, setData] = useState({ items: [], unread: 0 });
  const [filter, setFilter] = useState('ALL');
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  // Audit fix — `load()` had no try/catch, and `data` starts as an
  // empty {items:[],unread:0}, so a rejected fetchNotifications() left
  // the modal showing the "You're all caught up / Quiet so far" empty
  // state — a failure disguised as success. `loading` gates a spinner
  // on first load; `loadErr` flips on a throw so the render shows an
  // error + Retry instead of the deceptive empty state.
  const [loading, setLoading] = useState(true);
  const [loadErr, setLoadErr] = useState(false);
  const load = useCallback(async () => {
    setLoadErr(false);
    try {
      // fetchNotifications never throws — it returns {error:true} on an HTTP
      // error / network drop. Detect that explicitly so the error + Retry UI
      // shows instead of a deceptive "all caught up" empty state.
      const res = await fetchNotifications();
      if (res && res.error) setLoadErr(true);
      else {
        setData(res);
        // Every mutation on this page reloads through here: let the nav
        // bell, tab title and favicon re-read the count now, not 25s later.
        try { window.dispatchEvent(new Event('sb:notifications-changed')); } catch (_) {}
      }
    } catch (_) {
      setLoadErr(true);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { if (me) load(); }, [me, load]);

  // Batch 635 — filter-aware "Mark all read". When the user has
  // narrowed the list via type-filter / unread-only / search, the
  // click should only mark the rows they can actually see — otherwise
  // a user filtering to "Trades" and clicking "Mark all read" silently
  // wipes unread rows in every other bucket too. Full view (no filter)
  // still uses the cheap single-endpoint `/read-all`.
  const clear = async () => {
    const filterActive = (filter === 'UNREAD') || (typeFilter !== 'ALL') || (search.trim().length > 0) || mutedSet.size > 0;
    if (filterActive) {
      const ids = groups.flatMap(g => g.items).filter(n => !n.read).map(n => n.id);
      if (ids.length === 0) { load(); return; }
      const res = await markNotificationsReadBatch(ids);
      // markNotificationsReadBatch goes through writeJson — a failed
      // request returns `{error, code}` with no `flipped` field. The
      // old fallback `: ids.length` then printed a green "Marked N
      // read" toast on a request that actually failed. Detect the
      // error shape and surface it instead, mirroring the full-view
      // branch below.
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not mark notifications read — try again.', 'err');
        load();
        return;
      }
      const n = (res && typeof res.flipped === 'number') ? res.flipped : ids.length;
      if (n > 0) toast(`Marked ${n} notification${n === 1 ? '' : 's'} read.`, 'ok');
    } else {
      // Mirror the filtered branch — full-view "Mark all read" was silent,
      // leaving the user to scan the list to verify the click landed.
      // Snapshot the visible unread count BEFORE the call so we can
      // print a concrete number; backend `markAllRead` is `void`.
      const visibleUnread = groups.flatMap(g => g.items).filter(n => !n.read).length;
      // Audit fix — markAllNotificationsRead() is a RAW fetch (not the
      // never-throwing writeJson), so a network failure rejects the
      // promise. Without this try/catch the rejection escaped the click
      // handler unhandled: the user clicked "Mark all read" offline and
      // got no toast, no error, nothing. The filtered branch above uses
      // markNotificationsReadBatch (writeJson — safe); this branch was
      // the outlier. Mirror the per-row mark-unread/delete handlers.
      let res;
      try {
        res = await markAllNotificationsRead();
      } catch (_) {
        toast('Could not mark notifications read — check your connection and try again.', 'err');
        load();
        return;
      }
      if (res && res.ok === false) { toast('Could not mark notifications read — try again.', 'err'); load(); return; }
      if (visibleUnread > 0) toast(`Marked ${visibleUnread} notification${visibleUnread === 1 ? '' : 's'} read.`, 'ok');
    }
    load();
  };
  // Batch 636 — filter-aware "Clear read". When a filter is active,
  // only the visible-and-read rows get deleted; the rest of the
  // inbox stays untouched. No confirm when scoped because the scope
  // itself is the safeguard (the user can see exactly which rows
  // will go away). Full-view still gets the confirm — deleting every
  // read row across every bucket is a bigger commitment.
  const clearRead = async () => {
    const filterActive = (filter === 'UNREAD') || (typeFilter !== 'ALL') || (search.trim().length > 0) || mutedSet.size > 0;
    if (filterActive) {
      const ids = groups.flatMap(g => g.items).filter(n => n.read).map(n => n.id);
      if (ids.length === 0) { load(); return; }
      const res = await deleteNotificationsBatch(ids);
      // deleteNotificationsBatch goes through writeJson — a failed
      // request returns `{error, code}` with no `deleted` field, so
      // the old `: ids.length` fallback printed a false "Deleted N"
      // success toast. Surface the error instead, matching the
      // full-view branch below.
      if (res && (res.error || res.code)) {
        toast(res.message || res.error || 'Could not clear read notifications — try again.', 'err');
        load();
        return;
      }
      const n = (res && typeof res.deleted === 'number') ? res.deleted : ids.length;
      if (n > 0) toast(`Deleted ${n} read notification${n === 1 ? '' : 's'}.`, 'ok');
      load();
      return;
    }
    if (!confirm('Delete every read notification? Unread rows stay.')) return;
    // Pre-fix: full-view "Clear read" was silent. The endpoint already
    // returns `{deleted: N}`; surface it like the filtered branch does.
    const res = await clearReadNotifications();
    const n = (res && typeof res.deleted === 'number')
      ? res.deleted
      : (res && (res.error || res.code))
          ? -1
          : 0;
    if (n < 0) { toast(res.message || res.error || 'Could not clear read notifications.', 'err'); load(); return; }
    if (n > 0) toast(`Deleted ${n} read notification${n === 1 ? '' : 's'}.`, 'ok');
    load();
  };
  // Click → mark read, then navigate. Server-supplied `path` wins; otherwise
  // fall back to a (kind, refId) map so older notifications written before
  // V13 still drill down to something useful.
  const open = async (n) => {
    if (!n.read) {
      // Batch 776 — optimistic in-state flip + unread count decrement
      // so the NotificationsModal row dims instantly on click, not 25s
      // later when the next poll lands. Mirrors the NotificationBell
      // optimistic update (batch 775).
      setData(prev => ({
        items:  (prev?.items || []).map(row => row.id === n.id ? { ...row, read: true } : row),
        unread: Math.max(0, (prev?.unread || 0) - 1)
      }));
      try { await markNotificationRead(n.id); } catch (_) {}
      try { window.dispatchEvent(new Event('sb:notifications-changed')); } catch (_) {}
    }
    let target = n.path || kindFallbackPath(n.kind, n.refId);
    // Batch 744 — append `?highlight=<tradeId>` for TRADE_* notifications
    // so the Trades tab scrolls the right row into view + flashes it.
    // refId is the trade id for all TRADE_*. Skipped when the path
    // already carries a highlight or doesn't route to the trades tab
    // (e.g. legacy paths). TRADE_MESSAGE is excluded because it carries
    // its own `?openChat=<id>` deep-link (batch 588).
    if (target && n.kind && typeof n.kind === 'string'
        && n.kind.toUpperCase().startsWith('TRADE_')
        && n.kind.toUpperCase() !== 'TRADE_MESSAGE'
        && n.refId && !target.includes('highlight=')
        && target.includes('tab=trades')) {
      target += (target.includes('?') ? '&' : '?') + 'highlight=' + n.refId;
    }
    if (onClose) onClose();
    if (target) navigate(target);
    else load();
  };

  // Type filter — groups related kinds into five buckets the user can
  // reason about: Trades (TRADE_*), Auctions (AUCTION_*), Offers
  // (OFFER_*), Wallet (DEPOSIT_/WITHDRAWAL_/ADMIN_CREDIT/etc), Other.
  const typeOf = (kind) => {
    const k = (kind || '').toUpperCase();
    if (k.startsWith('TRADE_') || k === 'ITEM_PURCHASED') return 'TRADES';
    if (k.startsWith('AUCTION_'))                          return 'AUCTIONS';
    if (k.startsWith('OFFER_') || k === 'BUY_ORDER_FILLED' || k === 'BUY_ORDER_EXPIRED') return 'OFFERS';
    if (k === 'DEPOSIT_COMPLETE' || k === 'DEPOSIT_EXPIRED' || k.startsWith('WITHDRAWAL_') ||
        k === 'ADMIN_CREDIT' || k === 'ADMIN_DEBIT' || k === 'CSR_CREDIT' ||
        k === 'DISPUTE_CLEARED' || k === 'REFUND_ISSUED' ||
        k === 'CHARGEBACK_OPENED') return 'WALLET';
    if (k === 'LISTING_MATCH' || k === 'WATCHLIST_PRICE_DROP' ||
        k === 'NEW_LISTING_FROM_SELLER' || k === 'PRICE_DROPPED') return 'MATCHES';
    // CART_ITEM_SOLD ("an item in your cart sold to someone else") is an
    // operational "you lost it" alert, not discovery noise — keep it OUT
    // of MATCHES so muting discovery notifications can't silently hide it.
    return 'OTHER';
  };

  // Read mute list once per render — typeFilter / filter changes trigger
  // the useMemo, and localStorage reads are cheap. The muted set hides
  // categories the user has opted out of entirely (Settings → Mute
  // notification types).
  const mutedSet = (() => {
    try { return new Set(JSON.parse(localStorage.getItem('sb_mute_kinds') || '[]')); }
    catch { return new Set(); }
  })();

  // Group items by local-calendar day and label each bucket.
  const groups = useMemo(() => {
    const muteFiltered = mutedSet.size > 0
      ? data.items.filter(n => !mutedSet.has(typeOf(n.kind)))
      : data.items;
    const base = filter === 'UNREAD' ? muteFiltered.filter(n => !n.read) : muteFiltered;
    const typed = (typeFilter === 'ALL') ? base : base.filter(n => typeOf(n.kind) === typeFilter);
    const q = search.trim().toLowerCase();
    const source = q.length === 0 ? typed : typed.filter(n =>
      (n.title || '').toLowerCase().includes(q) ||
      (n.body  || '').toLowerCase().includes(q));
    const buckets = {};
    source.forEach(n => {
      const d = new Date(n.createdAt);
      const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
      if (!buckets[key]) buckets[key] = { label: dayLabel(d), items: [] };
      buckets[key].items.push(n);
    });
    return Object.entries(buckets)
      .sort(([a], [b]) => (a < b ? 1 : -1))
      .map(([k, v]) => ({ key: k, label: v.label, items: v.items }));
  }, [data.items, filter, typeFilter, search, mutedSet.size]);

  const count = groups.reduce((s, g) => s + g.items.length, 0);
  // Muted rows are hidden from the list, so the title and chips count
  // only what can be shown (the bell badge already does the same).
  const shownItems = mutedSet.size > 0
    ? data.items.filter(n => !mutedSet.has(typeOf(n.kind)))
    : data.items;
  const shownUnread = mutedSet.size > 0 ? shownItems.filter(n => !n.read).length : data.unread;

  if (!me) return h(InfoModal, { title: 'Notifications', onClose },
    h(SignInNeededEmptyState, { what: 'your notifications' }));

  return h(InfoModal, { title: `Notifications · ${shownUnread} unread`, onClose },
    // Batch 769 — muted-kinds banner. When the user has muted specific
    // notification categories via Settings, surface a concise "X types
    // muted · manage" line so they're not confused about a quiet bell
    // or missing events. Hidden when nothing is muted.
    mutedSet.size > 0 && h('div', {
      style: {
        marginBottom: 10, padding: '8px 12px', fontSize: 11,
        background: 'rgba(96,165,250,0.08)',
        border: '1px solid rgba(96,165,250,0.25)',
        borderRadius: 8, color: 'var(--text-secondary)',
        display: 'flex', alignItems: 'center', gap: 10
      }
    },
      h('span', null, '—',
        h('strong', null, `${mutedSet.size} notification type${mutedSet.size === 1 ? '' : 's'} muted`),
        ' — muted rows are hidden from this view.'),
      h('a', {
        href: paths.settings ? paths.settings() : '/settings',
        style: { marginLeft: 'auto', color: 'var(--accent)', fontWeight: 700, textDecoration: 'none', fontSize: 11 },
        onClick: () => { onClose && onClose(); }
      }, 'Manage →')
    ),
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' } },
      h('button', { className: `offer-tab ${filter === 'ALL' ? 'active' : ''}`, onClick: () => setFilter('ALL') },
        'All ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, shownItems.length)),
      h('button', { className: `offer-tab ${filter === 'UNREAD' ? 'active' : ''}`, onClick: () => setFilter('UNREAD') },
        'Unread ', h('span', { className: 'filter-count', style: { marginLeft: 6 } }, shownUnread)),
      h('div', { style: { flex: 1 } }),
      data.items.length > 0 && (() => {
        // Batch 635 — dynamic label: when a filter is active, show the
        // visible-unread count so the user knows the click is scoped.
        const filterActive = (filter === 'UNREAD') || (typeFilter !== 'ALL') || (search.trim().length > 0) || mutedSet.size > 0;
        const visibleUnread = filterActive ? groups.flatMap(g => g.items).filter(n => !n.read).length : 0;
        return h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
          onClick: clear,
          title: filterActive
            ? 'Only the notifications currently visible on the page will be marked read. Clear filters to mark everything.'
            : 'Mark every unread notification read.'
        }, filterActive
          ? (visibleUnread > 0 ? `Mark visible read (${visibleUnread})` : 'Mark visible read')
          : 'Mark all read');
      })(),
      data.items.filter(n => n.read).length > 0 && (() => {
        // Batch 636 — dynamic label mirrors the Mark-visible-read button:
        // when a filter is active, the count shows only the visible-and-
        // read rows so the user knows exactly what's about to be deleted.
        const filterActive = (filter === 'UNREAD') || (typeFilter !== 'ALL') || (search.trim().length > 0) || mutedSet.size > 0;
        const visibleRead = filterActive ? groups.flatMap(g => g.items).filter(n => n.read).length : 0;
        return h('button', {
          className: 'btn btn-ghost',
          style: { border: '1px solid var(--border)', padding: '6px 12px', fontSize: 11 },
          onClick: clearRead,
          title: filterActive
            ? 'Only the read notifications currently visible on the page will be deleted. Clear filters to clean up everything.'
            : 'Delete every already-read notification.'
        }, filterActive
          ? (visibleRead > 0 ? `Clear visible read (${visibleRead})` : 'Clear visible read')
          : 'Clear read');
      })()
    ),
    data.items.length > 5 && h('div', { style: { marginBottom: 12 } },
      h('input', {
        className: 'price-input',
        style: { width: '100%', fontSize: 12 },
        'aria-label': 'Search notifications',
        placeholder: 'Search notifications…',
        value: search,
        onChange: e => setSearch(e.target.value)
      })
    ),
    data.items.length > 3 && h('div', { style: { display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 14 } },
      [
        { id: 'ALL',      label: 'All' },
        { id: 'TRADES',   label: 'Trades' },
        { id: 'AUCTIONS', label: 'Auctions' },
        { id: 'OFFERS',   label: 'Offers' },
        { id: 'MATCHES',  label: 'Matches' },
        { id: 'WALLET',   label: 'Wallet' },
        { id: 'OTHER',    label: 'Other' }
      ].map(opt => {
        const c = opt.id === 'ALL' ? data.items.length : data.items.filter(n => typeOf(n.kind) === opt.id).length;
        return h('button', {
          key: opt.id,
          className: `wallet-tx-filter-chip ${typeFilter === opt.id ? 'active' : ''}`,
          onClick: () => setTypeFilter(opt.id),
          disabled: c === 0 && opt.id !== 'ALL'
        }, `${opt.label} · ${c}`);
      })
    ),
    loadErr
      /* Audit fix — fetchNotifications() rejected. Without this branch
         the empty `data` fell through to the "all caught up / Quiet so
         far" empty state, so a failure read as success. Show a real
         error with a Retry that re-runs load(). */
      ? h('div', { className: 'empty-inline', role: 'alert' },
          h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'error_outline', size: 26 })),
          h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
            "Couldn't load notifications"),
          h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 14px' } },
            'Your notification feed failed to load. Check your connection and try again.'),
          h('button', { className: 'btn btn-accent', onClick: () => load() }, 'Retry'))
      : (loading && count === 0)
      /* First load still in flight — show a spinner instead of briefly
         flashing the "Quiet so far" empty state before data lands. */
      ? h('div', { className: 'spinner' })
      : count === 0
      ? (() => {
          // Batch 916 — distinguish "never had a notification" from
          // "filter narrowed to zero". The unfiltered-zero state on
          // a brand-new account gets a richer explainer + CTAs to
          // the surfaces that actually produce notifications; the
          // filtered-zero states keep the tight message since the
          // user already knows what they have and is just adjusting
          // scope.
          if (search.trim()) {
            return h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'search_off', size: 26 })),
              h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 10 } },
                'No notifications match "', h('strong', null, search.trim()), '".'),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                onClick: () => setSearch('')
              }, 'Clear search'));
          }
          if (filter === 'UNREAD') {
            return h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, '✓'),
              h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 10 } },
                "You're all caught up — no unread notifications."),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                onClick: () => setFilter('ALL')
              }, 'Show all'));
          }
          if (typeFilter !== 'ALL') {
            return h('div', { className: 'empty-inline' },
              h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'filter_list', size: 26 })),
              h('div', { style: { fontSize: 14, color: 'var(--text-secondary)', marginBottom: 10 } },
                `No ${typeFilter.toLowerCase()} notifications in this view.`),
              h('button', {
                className: 'btn btn-ghost',
                style: { border: '1px solid var(--border)' },
                onClick: () => setTypeFilter('ALL')
              }, 'Clear type filter'));
          }
          return h('div', { className: 'empty-inline' },
            h('div', { className: 'empty-icon' }, h(MaterialIcon, { name: 'notifications_off', size: 26 })),
            h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
              'Quiet so far'),
            h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 420, margin: '0 auto 16px', lineHeight: 1.55 } },
              "Every trade move, bid update, offer, price-alert hit, and seller-follow ping lands here. List an item, place a bid, or watchlist something — the first notification shows up the moment it happens."),
            h('div', { style: { display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' } },
              h('a', { className: 'btn btn-accent', href: '/market', style: { padding: '10px 18px', fontWeight: 700 } }, 'Browse marketplace →'),
              h('a', { className: 'btn btn-ghost', href: '/watchlist', style: { border: '1px solid var(--border)', padding: '10px 18px' } }, 'Open watchlist')
            )
          );
        })()
      : h('div', { className: 'notif-feed' },
          groups.map(g => h('div', { key: g.key },
            h('div', { className: 'notif-feed-day' }, g.label),
            g.items.map(n => h('div', {
              key: n.id,
              className: `notif-feed-row ${n.read ? '' : 'unread'}`,
              onClick: () => open(n),
              // Keyboard a11y (matches buyorder-picker-row / loadout-card in
              // this file): the row body opens the notification on click, so it
              // must be focusable and Enter/Space-activatable or SR/keyboard
              // users can't open any notification on the /notifications page.
              role: 'button',
              tabIndex: 0,
              'aria-label': `${n.read ? '' : 'Unread. '}Open notification: ${n.title || n.kind || ''} — ${timeAgo(n.createdAt)}`,
              onKeyDown: (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  open(n);
                }
              }
            },
              h('div', { className: 'notif-feed-icon' }, kindIcon(n.kind)),
              h('div', { style: { flex: 1, minWidth: 0 } },
                h('div', { className: 'notif-feed-title' }, highlightMatch(n.title || '', search)),
                n.body && h('div', { className: 'notif-feed-body' }, highlightMatch(n.body, search)),
                h('div', { className: 'notif-feed-time' }, timeAgo(n.createdAt))
              ),
              !n.read && h('div', { className: 'notif-feed-dot' }),
              // Mark-as-unread / mark-as-read toggle (batch 365) — lets
              // a user defer handling ("I'll come back to this") without
              // losing the row. Only shown on READ rows (unread rows
              // already surface their unread state via the dot). Icon
              // flips to show what the click will DO, not the current
              // state: 📥 for mark-unread.
              n.read && h('button', {
                className: 'btn btn-ghost',
                style: {
                  padding: '4px 8px', fontSize: 13, opacity: 0.45,
                  border: '1px solid transparent', marginLeft: 4
                },
                title: 'Mark as unread',
                'aria-label': 'Mark as unread',
                onClick: async (e) => {
                  e.stopPropagation();
                  // Pre-fix: errors here were swallowed and `load()` re-fetched
                  // the same data, so a server reject silently looked like a
                  // dead button. Now we surface non-2xx as a toast.
                  try {
                    const { markNotificationUnread } = await import('./api.js');
                    const r = await markNotificationUnread(n.id);
                    if (r && r.ok === false) toast('Could not mark unread — try again.', 'err');
                  } catch (_) { toast('Could not mark unread — try again.', 'err'); }
                  await load();
                }
              }, '📥'),
              // Per-row dismiss. Hardest-to-mis-click target so use a
              // tiny ✕ with generous padding. stopPropagation so the
              // outer row's open() doesn't fire.
              h('button', {
                className: 'btn btn-ghost',
                style: {
                  padding: '4px 8px', fontSize: 14, opacity: 0.45,
                  border: '1px solid transparent', marginLeft: 4
                },
                title: 'Delete this notification',
                'aria-label': 'Delete this notification',
                onClick: async (e) => {
                  e.stopPropagation();
                  // Pre-fix: errors swallowed; failed deletes silently no-oped
                  // because `load()` re-fetched the same row.
                  try {
                    const r = await deleteNotification(n.id);
                    if (r && r.ok === false) toast('Could not delete notification — try again.', 'err');
                  } catch (_) { toast('Could not delete notification — try again.', 'err'); }
                  await load();
                }
              }, '✕')
            ))
          ))
        )
  );
}

function dayLabel(d) {
  const today = new Date();
  const yest = new Date();
  yest.setDate(today.getDate() - 1);
  const same = (a, b) => a.getFullYear() === b.getFullYear() &&
                         a.getMonth() === b.getMonth() &&
                         a.getDate() === b.getDate();
  if (same(d, today)) return 'Today';
  if (same(d, yest))  return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
}

// Kind-based routing fallback for notifications that don't carry a server
// `path`. Everything trade/purchase/wallet/support flows to the right tab so
// the click always lands on something meaningful.
function kindFallbackPath(kind, refId) {
  if (!kind) return '/profile';
  const k = kind.toUpperCase();
  if (k === 'DEPOSIT_COMPLETE' || k === 'DEPOSIT_EXPIRED' || k.startsWith('WITHDRAWAL_') ||
      k === 'ADMIN_CREDIT' || k === 'ADMIN_DEBIT' || k === 'CSR_CREDIT' ||
      k === 'DISPUTE_CLEARED' || k === 'REFUND_ISSUED') {
    return paths.wallet();
  }
  if (k === 'CHARGEBACK_OPENED') return '/admin?tab=disputes';
  if (k === 'CARD_TESTING_DETECTED') return '/admin?tab=users';
  if (k === 'CART_ITEM_SOLD') return paths.cart();
  if (k === 'PRICE_DROPPED') {
    // Price-drop on a cart / offered item. The service ships a real
    // path ("/item/:id") when the item id resolved; this fallback only
    // fires otherwise. refId is the LISTING id, not the item id, so
    // /item/refId would be wrong — route to the cart (the event's home)
    // instead of dead-ending on the generic profile page.
    return paths.cart();
  }
  if (k === 'FRAUD_SIGNAL_HIGH') return '/admin?tab=fraud';
  if (k === 'BUY_ORDER_FILLED' || k === 'BUY_ORDER_EXPIRED') return paths.buyorders();
  if (k === 'OFFER_RECEIVED' || k === 'OFFER_ACCEPTED' || k === 'OFFER_REJECTED' || k === 'OFFER_COUNTERED') {
    return paths.offers();
  }
  if (k === 'SUPPORT_REPLY' || k === 'TICKET_AUTO_RESOLVED') return paths.support();
  if (k === 'STEAM_INVENTORY') return paths.sell();
  if (k === 'REVIEW_RECEIVED' || k === 'REVIEW_REPLIED' || k === 'REVIEW_UPDATED' || k === 'REVIEW_DELETED') return '/profile/reviews';
  if (k === 'SELLER_FOLLOWED') return paths.mystall();
  if (k === 'LISTING_REMOVED') return paths.mystall();
  if (k === 'REPORT_ACTIONED' || k === 'REPORT_REVIEWED') return paths.profile();
  if (k === 'WATCHLIST_PRICE_DROP') {
    // Notification payload carries the item id in refId — drill into
    // the item detail so the user can act on the alert immediately.
    return refId ? ('/item/' + refId) : paths.watchlist();
  }
  if (k === 'NEW_LISTING_FROM_SELLER') {
    // path ships from the service layer already ("/item/:id" when the
    // item id was resolvable, null otherwise). Fall back to market.
    return paths.market();
  }
  if (k === 'LISTING_MATCH') {
    // Saved-search match. The service ships path="/item/:id" whenever
    // the listing's item id resolved; this fallback only fires when it
    // didn't. refId is the LISTING id (not item id), so drilling to
    // /item/refId would be wrong — route to the marketplace instead.
    return paths.market();
  }
  if (k === 'REVIEW_REMINDER') {
    // Deep-link to the seller's stall if we have it in refId; the
    // service layer ships the full /stall/:id path, so this branch
    // only fires when the path was dropped somehow.
    return paths.profile();
  }
  if (k.startsWith('AUCTION_') || k === 'ITEM_PURCHASED' || k.startsWith('TRADE_')) {
    // Trade-ish events all want the trades tab — auctions become trades
    // once won, buyers' purchases become trades immediately.
    return '/profile/trades';
  }
  if (k === 'ACCOUNT_BANNED' || k === 'ACCOUNT_UNBANNED') {
    return paths.profile();
  }
  return paths.profile();
}

function kindIcon(kind) {
  // Batch 1068 — notification icons editorial. Emoji glyphs (🛒, 🏆, 💬,
  // ⚡, 🤝, 📨, 🚫, 📭, 💰, ⏰, 🛎, 🛡, 🚨, 👥, 👋, 📢, 🎮, 👑, 🎧, ⏳,
  // 🔐, 🆕, 🚩, 👀, 📉, 🗑) out; neutral text markers in. Each category
  // of notification keeps a distinct shape so the icon is still a scan-
  // guide, but nothing reads as "fun emoji icon soup" on the list.
  const map = {
    // Trades ⇄
    ITEM_PURCHASED: '⇄', TRADE_VERIFIED: '✓', TRADE_OPENED: '⇄',
    TRADE_ACCEPTED: '✓', TRADE_SENT: '→', TRADE_CANCELLED: '✕',
    TRADE_DISPUTED: '⚠', TRADE_REQUESTED: '⇄',
    TRADE_MESSAGE: '"', TRADE_SELLER_NUDGE: '!', TRADE_SLOW_SELLER: '…',
    // Auctions ◆
    AUCTION_WON: '✓', AUCTION_SOLD: '$', AUCTION_LOST: '✕',
    AUCTION_OUTBID: '↑', AUCTION_ENDING: '◐', AUCTION_CANCELLED: '✕',
    AUCTION_EXPIRED_NO_BIDS: '—',
    // Offers ⇆
    OFFER_RECEIVED: '"', OFFER_ACCEPTED: '✓', OFFER_REJECTED: '✕', OFFER_COUNTERED: '⇄',
    // Buy orders ↗
    BUY_ORDER_FILLED: '↗', BUY_ORDER_EXPIRED: '—', LISTING_MATCH: '↗',
    // Wallet $
    DEPOSIT_COMPLETE: '$', DEPOSIT_EXPIRED: '—',
    WITHDRAWAL_COMPLETE: '$', WITHDRAWAL_REJECTED: '✕',
    CHARGEBACK_OPENED: '⚠', DISPUTE_CLEARED: '✓', REFUND_ISSUED: '↩',
    CARD_TESTING_DETECTED: '⚠',
    ADMIN_CREDIT: '+', ADMIN_DEBIT: '−', CSR_CREDIT: '+',
    // Catalogue + misc
    CART_ITEM_SOLD: '$', FRAUD_SIGNAL_HIGH: '⚠',
    SELLER_FOLLOWED: '+',
    WELCOME: '★', ADMIN_BROADCAST: '!', ADMIN_MESSAGE: '"',
    LOADOUT_DELETED: '✕',
    ACCOUNT_BANNED: '⚠', ACCOUNT_UNBANNED: '↩',
    STEAM_INVENTORY: '⇄',
    SUPPORT_REPLY: '"', TICKET_AUTO_RESOLVED: '—',
    LISTING_REMOVED: '⚠',
    REPORT_ACTIONED: '⚠', REPORT_REVIEWED: '◦',
    WATCHLIST_PRICE_DROP: '↓', NEW_LISTING_FROM_SELLER: '+', PRICE_DROPPED: '↓',
    // Reviews ★
    REVIEW_RECEIVED: '★', REVIEW_REMINDER: '★', REVIEW_REPLIED: '"',
    REVIEW_UPDATED: '★', REVIEW_DELETED: '✕',
    // Staff
    ADMIN_GRANTED: '+', ADMIN_REVOKED: '↓',
    CSR_GRANTED: '+',   CSR_REVOKED: '↓',
    TWOFA_RESET: '!'
  };
  return map[kind] || '•';
}

// ── Auction bid panel — embeds inside ItemModal for AUCTION listings ──
export function AuctionBidPanel({ listing, me, wallet, onPlaced }) {
  const [history, setHistory] = useState([]);
  const [amount, setAmount]   = useState('');
  const [maxAmount, setMax]   = useState('');
  const [busy, setBusy]       = useState(false);
  // Sync re-entrancy latch — submit() calls setBusy AFTER its await(placeBid),
  // so a rapid double-click fires the bid twice before the disable lands.
  // Auto-resets via the per-render busyRef sync after setBusy(false).
  const busyRef = useRef(busy); busyRef.current = busy;
  const [err, setErr]         = useState('');
  const [now, setNow]         = useState(Date.now());
  // Mirror of the server-side listing state. Refreshed every 8s so
  // remote bids + anti-snipe expiresAt extensions show up for viewers
  // who didn't place the last bid. Falls back to the prop on first
  // render; once we've seen a refresh the live copy wins.
  const [live, setLive]       = useState(listing);
  // Mirror `live` into a ref so the SSE effect's isTerminal() can read the
  // freshest listing WITHOUT live?.status/live?.expiresAt in its dep array —
  // those deps tore the EventSource down + reopened it on every soft-close
  // bid, dropping events in the hottest window (audit P2). Same pattern as
  // busyRef above.
  const liveRef = useRef(live); liveRef.current = live;
  // Audit fix — `live` was seeded from `listing` only on first mount, so
  // if the parent swapped the prop to a different auction (e.g. user
  // navigated /item/A → /item/B without remounting the panel) the panel
  // kept rendering A's expiresAt/currentBid until the SSE/polling cycle
  // refreshed it ~50ms–8s later. Re-seed when the listing id changes so
  // a prop change is reflected immediately instead of showing the wrong
  // auction for a beat.
  useEffect(() => {
    setLive(listing);
    setExtendedUntil(0);
  }, [listing?.id]);
  // Transient "Auction extended by ~30s" banner shown when we detect a
  // positive jump in expiresAt relative to the last observed value.
  // Set to a UTC ms "show until" target; unset once the clock passes.
  const [extendedUntil, setExtendedUntil] = useState(0);
  // Bid-history "show all" toggle. Default-collapsed to 8 rows so a
  // long-running auction doesn't flood the panel; any row count above 8
  // gets a "Show all N bids" expand button.
  const [showAllHistory, setShowAllHistory] = useState(false);
  // Busy flag for the "cancel auto-bid" action. Declared up here with
  // the other hooks — it must run on EVERY render. It previously sat
  // below the `if (!listing ...) return null` early-return, so a render
  // for a non-AUCTION / null listing skipped this useState while an
  // auction render ran it, tripping React's "rendered fewer hooks than
  // expected" crash (Rules of Hooks).
  const [cancellingCap, setCancellingCap] = useState(false);

  // Mounted guard for the two-await load() chain below. The SSE handler
  // + 8-60s polling fallback call load() throughout the panel's life,
  // and closing the ItemModal mid-fetch used to fire setHistory/setLive
  // on an unmounted component (React's "state update on unmounted"
  // warning, and the stale fetch result was wasted anyway). Ref instead
  // of `let alive` because load is a useCallback shared across multiple
  // call sites — we need one liveness signal that all of them honour.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const load = useCallback(async () => {
    if (!listing?.id) return;
    const hist = await fetchBidHistory(listing.id);
    if (!mountedRef.current) return;
    setHistory(hist);
    // Poll the listing itself — its expiresAt and currentBid both
    // change server-side without a placeBid on this tab (remote
    // bidders, sweeper). Without this the panel showed stale data
    // until the user placed their own bid.
    try {
      const fresh = await (await import('./api.js')).fetchListingById(listing.id);
      if (!mountedRef.current) return;
      if (fresh && fresh.id === listing.id) {
        setLive(prev => {
          const prevExpires = prev?.expiresAt ?? listing.expiresAt;
          if (fresh.expiresAt != null && prevExpires != null &&
              fresh.expiresAt > prevExpires + 500) {
            // Soft-close extension detected. Flash the banner for 8s.
            setExtendedUntil(Date.now() + 8000);
          }
          // Monotonic merge: this REST poll may have STARTED before a newer SSE
          // 'bid' event already merged a higher bid. Blindly returning `fresh`
          // would regress currentBid/expiresAt/bidCount to the pre-bid snapshot
          // right at auction close. If fresh is older (lower bidCount), keep the
          // live bid fields and take only the non-bid columns from fresh. (audit P2)
          const pBid = prev?.bidCount ?? -1;
          const fBid = fresh.bidCount ?? -1;
          // expiresAt is monotonic FORWARD within a listing (soft-close only
          // extends it). A stale REST poll that started before an SSE soft-close
          // carries the SAME bidCount but a LOWER expiresAt — so guarding only on
          // bidCount (fBid < pBid) let such a poll clobber the extension when the
          // counts were equal. Protect expiresAt independently: never regress it
          // backward, regardless of bidCount. (self-review fix)
          const keepExpires = (prev && prev.expiresAt != null &&
            (fresh.expiresAt == null || prev.expiresAt > fresh.expiresAt)) ? prev.expiresAt : fresh.expiresAt;
          if (prev && fBid < pBid) {
            return {
              ...fresh,
              currentBid:        prev.currentBid,
              currentBidderId:   prev.currentBidderId,
              currentBidderName: prev.currentBidderName,
              bidCount:          prev.bidCount,
              expiresAt:         keepExpires,
              status:            prev.status ?? fresh.status,
            };
          }
          return { ...fresh, expiresAt: keepExpires };
        });
      }
    } catch (_) { /* stay on the old copy */ }
  }, [listing?.id]);
  useEffect(() => { load(); }, [load]);

  // Live bid stream via SSE — replaces the previous 8-second polling
  // loop for the hot path. The backend fires AuctionBidPlacedEvent on
  // every placeBid/buyNowAuction after commit; AuctionEventBus fans it
  // out to /api/bids/stream/<listingId>. Clients merge the payload into
  // the `live` listing mirror instantly (typical latency ~50ms) instead
  // of waiting up to 8s for the next poll — critical near auction close
  // and for soft-close "Auction extended +30s" banners to appear in real
  // time for every viewer, not just the bidder.
  //
  // A slow polling fallback (60s) still runs so a browser / proxy that
  // blocks EventSource (rare but real: some corporate networks, older
  // Safari-in-iframe) still gets fresh bid history + the surrounding
  // listing state. Fallback also covers the case where the SSE endpoint
  // returns a completed stream at over-capacity (>200 subs/listing).
  //
  // Auto-stops once the auction is unambiguously terminal (batch 749):
  // listing.status !== ACTIVE AND expiresAt has elapsed by ~15s.
  useEffect(() => {
    if (!listing?.id) return;
    const lid = listing.id;
    let alive = true;
    const isTerminal = () => {
      const view = liveRef.current || listing;
      const terminal = view && view.status && view.status !== 'ACTIVE';
      const wellPastEnd = view?.expiresAt && (Date.now() > view.expiresAt + 15_000);
      return terminal && wellPastEnd;
    };

    // --- SSE stream ---------------------------------------------------
    let es = null;
    let sseAlive = false;
    let errCount = 0;
    let lastErrAt = 0;
    try {
      if (typeof window !== 'undefined' && 'EventSource' in window) {
        es = new window.EventSource(`/api/bids/stream/${encodeURIComponent(lid)}`);
        es.addEventListener('open', () => { sseAlive = true; });
        es.addEventListener('bid', (ev) => {
          if (!alive) return;
          try {
            const data = JSON.parse(ev.data);
            if (!data || data.listingId !== lid) return;
            // Merge event payload into the live listing mirror. Refresh
            // bid history in parallel — the event carries the summary
            // state (currentBid, count, expiresAt) but not the row list.
            setLive(prev => {
              const base = prev || listing;
              const prevExpires = base?.expiresAt ?? null;
              const nextExpires = data.expiresAt != null ? Number(data.expiresAt) : prevExpires;
              if (nextExpires != null && prevExpires != null && nextExpires > prevExpires + 500) {
                setExtendedUntil(Date.now() + 8000);
              }
              return {
                ...base,
                currentBid:        data.currentBid != null ? data.currentBid : base?.currentBid,
                currentBidderId:   data.currentBidderId   ?? base?.currentBidderId,
                currentBidderName: data.currentBidderName ?? base?.currentBidderName,
                bidCount:          data.bidCount          ?? base?.bidCount,
                expiresAt:         nextExpires,
                status:            data.status            ?? base?.status,
              };
            });
            // Bid-history list refresh — cheap and keeps the panel rows
            // in sync with the summary tick we just applied.
            fetchBidHistory(lid).then(rows => { if (alive) setHistory(rows); }).catch(() => {});
          } catch (_) { /* malformed event — ignore */ }
        });
        es.addEventListener('error', () => {
          sseAlive = false;
          // Over-capacity streams (>200 subs/listing) complete server-side
          // immediately, so the browser's auto-reconnect would retry every
          // ~3s forever. After a few RAPID failures, give up on SSE and lean
          // on the polling fallback (which drops to 8s once sseAlive is
          // false). A slow/occasional error still auto-reconnects. (audit P3)
          const now = Date.now();
          errCount = (now - lastErrAt < 5000) ? errCount + 1 : 1;
          lastErrAt = now;
          if (errCount >= 4 && es) { try { es.close(); } catch (_) {} es = null; }
        });
      }
    } catch (_) {
      es = null; sseAlive = false;
    }

    // --- Polling fallback --------------------------------------------
    // Slow interval (60s) when SSE is alive; faster (8s) otherwise so
    // networks that silently block EventSource still get live-ish data.
    const tick = () => {
      if (typeof document !== 'undefined' && document.hidden) return;
      if (isTerminal()) return;
      load();
    };
    const pickInterval = () => (sseAlive ? 60_000 : 8_000);
    let intervalId = setInterval(tick, pickInterval());
    // Re-evaluate the cadence periodically so we speed up if SSE dies
    // mid-session without a full remount.
    const cadenceId = setInterval(() => {
      clearInterval(intervalId);
      intervalId = setInterval(tick, pickInterval());
    }, 30_000);
    const onVis = () => { if (!document.hidden) tick(); };
    document.addEventListener('visibilitychange', onVis);

    return () => {
      alive = false;
      if (es) { try { es.close(); } catch (_) {} }
      clearInterval(intervalId);
      clearInterval(cadenceId);
      document.removeEventListener('visibilitychange', onVis);
    };
    // NOTE: deliberately NOT depending on live?.status/live?.expiresAt — the
    // EventSource is opened once per listing and lives the whole auction;
    // isTerminal() reads liveRef.current for the freshest state without a
    // teardown+reopen on every soft-close bid. (audit P2)
  }, [load, listing?.id]);

  // Tick every second for the countdown timer
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (!listing || listing.listingType !== 'AUCTION') return null;

  // Prefer the live-polled copy, but fall back to props for first paint.
  const view = live || listing;
  const remaining = view.expiresAt - now;
  // A seller-cancelled auction keeps its future end time but is no
  // longer ACTIVE; treat it as over so the bid form closes.
  const ended = remaining <= 0 || (!!view.status && view.status !== 'ACTIVE');
  const fmtTime = (ms) => {
    if (ms <= 0) return 'Ended';
    const s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400);
    const h_ = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (d > 0) return `${d}d ${h_}h ${m}m`;
    return `${String(h_).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(sec).padStart(2,'0')}`;
  };
  const floor = parseFloat(view.currentBid || view.price);
  // 2026-05-20 audit fix — off-by-one against BidService. The backend's
  // FIRST-bid floor is the starting price EXACTLY (BidService: when
  // `currentBid == null`, minRequired = listing.price); only SUBSEQUENT
  // bids must clear `currentBid + $0.05`. The old unconditional
  // `floor + 0.05` told a buyer on a 0-bid auction the minimum was
  // price+$0.05 and the submit guard below then rejected a perfectly
  // valid first bid placed at the starting price. Split the two cases.
  const hasBid = view.currentBid != null;
  // Price-tiered minimum increment — mirrors BidService.incrementFor exactly
  // so the hint and the submit guard agree with the server (a flat $0.05 hint
  // on a $4,000 bid would let a buyer submit a too-low bid and eat a server
  // rejection). Tiers: <$1 $0.05 / <$10 $0.10 / <$50 $0.25 / <$100 $0.50 /
  // <$250 $1 / <$1k $5 / <$5k $25 / else $100.
  const bidIncrementFor = (p) => {
    p = p || 0;
    if (p < 1)    return 0.05;
    if (p < 10)   return 0.10;
    if (p < 50)   return 0.25;
    if (p < 100)  return 0.50;
    if (p < 250)  return 1;
    if (p < 1000) return 5;
    if (p < 5000) return 25;
    return 100;
  };
  const minNext = (hasBid ? (floor + bidIncrementFor(floor)) : floor).toFixed(2);

  const submit = async () => {
    setErr('');
    if (!me) { setErr('Sign in to bid'); return; }
    const a = parseFloat(amount);
    if (!a || a < parseFloat(minNext)) { setErr(`Minimum bid is ${fmt(minNext)}`); return; }
    // Auto-bid cap guard. The server only treats maxAmount as an AUTO cap
    // when it's strictly above the bid amount (BidService line 198) —
    // otherwise it silently downgrades to a plain MANUAL bid and the
    // typed cap is discarded. Catch the nonsensical "cap ≤ bid" here so
    // the bidder isn't surprised their proxy-bidder never engages.
    if (maxAmount && String(maxAmount).trim()) {
      const cap = parseFloat(maxAmount);
      if (!(cap > 0)) { setErr('Auto-bid cap must be a positive amount, or leave it blank.'); return; }
      if (cap <= a) { setErr(`Your auto-bid cap (${fmt(cap)}) must be above your bid (${fmt(a)}) — that's the ceiling the proxy-bidder raises toward.`); return; }
    }
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await placeBid(listing.id, a, maxAmount ? parseFloat(maxAmount) : null);
      if (res.code || res.error) { setErr(res.message || res.error); return; }
      setAmount(''); setMax('');
      // Batch 882 — silent bid-placed was disorienting. A buyer
      // clicking "Place bid" and getting no feedback beyond the panel
      // state change couldn't tell the click actually landed. Now a
      // toast confirms with the exact amount + auto-bid cap if set,
      // matching the buy-success personalisation pattern.
      const cap = maxAmount ? parseFloat(maxAmount) : null;
      const name = listing?.item?.name;
      const parts = [`Bid ${fmt(a)}`];
      if (name) parts.push(`on "${name}"`);
      if (cap && cap > a) parts.push(`(auto-raise cap ${fmt(cap)})`);
      toast(parts.join(' ') + ' placed.', 'ok');
      load();
      onPlaced && onPlaced();
    } finally { setBusy(false); }
  };

  // Self-bid awareness — flip the header accent and drop in a status
  // banner so the bidder knows whether they're currently winning, losing,
  // or yet to bid. Anti-sniping soft-close is server-side; this panel
  // just surfaces the state.
  // The listing payload never carries currentBidderId (redacted
  // server-side), so read "am I on top" from the viewer's own bid rows:
  // the leader's live row is WINNING, and the winner's row reads WON.
  const viewerIsTop = !!me && history.some(b =>
    b.bidderUserId === me.id && (b.status === 'WINNING' || b.status === 'WON'));
  const auctionHadBids = (view.bidCount || 0) > 0 || view.currentBid != null;
  const viewerHasBid = history.some(b => b.bidderUserId && me && b.bidderUserId === me.id);
  // The ended banner reads the settled outcome, not the clock: until the
  // sweep settles (status still ACTIVE) nothing is won yet, and a settle
  // that fails (winner can't pay, frozen, banned) leaves no WON row.
  const wonRow = history.find(b => b.status === 'WON') || null;
  const settling = remaining <= 0 && view.status === 'ACTIVE';
  const viewerWon = !!me && !!wonRow && wonRow.bidderUserId === me.id;
  const viewerIsLosing = viewerHasBid && !viewerIsTop && !ended;
  const extensionActive = extendedUntil > now;
  // Distinct bidder count — lets the header read "12 bids · 4 bidders"
  // instead of just "12 bids". One person spamming 12 bids reads very
  // differently to the 4-bidder signal. Redaction preserves stable
  // handles (Bug #14 fix) so anonymous viewers still count correctly.
  const distinctBidders = (() => {
    const set = new Set();
    history.forEach(b => {
      if (b.bidderUserId != null) set.add('u:' + b.bidderUserId);
      else if (b.bidderName)      set.add('n:' + b.bidderName);
    });
    return set.size;
  })();
  // Viewer's own effective auto-bid cap on this auction — the highest
  // maxAmount across any of their own AUTO bids still in the book.
  // Only populated when the viewer is signed in, has bid here, and
  // actually set a cap (manual bids have null maxAmount). Surfaces the
  // value back to the user so they're not guessing "did my $50 cap go
  // through?" — the server-side history already carries their own
  // maxAmount (the redaction only nulls it out for third parties).
  const yourAutoCap = (() => {
    if (!me) return null;
    let best = 0;
    history.forEach(b => {
      if (b.bidderUserId === me.id && b.maxAmount != null) {
        const v = parseFloat(b.maxAmount);
        if (Number.isFinite(v) && v > best) best = v;
      }
    });
    // A cap the rival's bid already passed is spent: showing "we'll keep
    // you on top up to this amount" next to "You were outbid" misleads.
    const cur = view.currentBid != null ? parseFloat(view.currentBid) : null;
    if (!viewerIsTop && cur != null && best <= cur) return null;
    return best > 0 ? best : null;
  })();
  // Active AUTO bid row for the viewer — needed so we can cancel the
  // auto-raise in place (vs. forcing them to navigate to the Active
  // Bids tab in the profile). Prefer a row with status=WINNING so the
  // cancel targets whatever the bot is currently defending.
  const yourActiveAutoBidId = (() => {
    if (!me) return null;
    const auto = history.filter(b =>
      b.bidderUserId === me.id && b.kind === 'AUTO' && b.maxAmount != null &&
      (b.status === 'WINNING' || b.status === 'OUTBID')
    );
    if (auto.length === 0) return null;
    const winning = auto.find(b => b.status === 'WINNING');
    return (winning || auto[0]).id;
  })();
  const stopAutoBid = async () => {
    if (!yourActiveAutoBidId || cancellingCap) return;
    if (!confirm(viewerIsTop
      ? `Stop auto-raising on this auction? Your current bid (${fmt(view.currentBid || view.price)}) stays live.`
      : 'Stop auto-raising on this auction? Bids you already placed stay as they are.')) return;
    setCancellingCap(true);
    try {
      const { cancelAutoBid } = await import('./api.js');
      const res = await cancelAutoBid(yourActiveAutoBidId);
      if (res && (res.error || res.code)) {
        // Mirror the "surface-failure-as-toast" pattern from the Buy
        // Now banner above — setErr only paints below the bid form, but
        // the cancel button lives in a separate auto-bid status row, so
        // a surface-level toast guarantees the failure is visible.
        toast(res.message || res.error || 'Could not cancel auto-bid.', 'err');
        setErr(res.message || res.error || 'Could not cancel auto-bid'); return;
      }
      await load();
      // Pre-fix: silent on success — the auto-bid status row simply
      // disappeared on the next load(). A bidder hitting "stop" got
      // zero confirmation that the proxy-bidder actually disengaged,
      // and would re-click expecting feedback. Toast names the
      // standing-bid amount so the bidder knows their floor stays live.
      const standingBid = view.currentBid != null ? parseFloat(view.currentBid) : parseFloat(view.price);
      toast(`Auto-raise stopped — your standing bid${isFinite(standingBid) ? ` of ${fmt(standingBid)}` : ''} is still in.`, 'ok');
    } finally { setCancellingCap(false); }
  };

  return h('div', { className: `auction-panel${viewerIsTop ? ' winning' : ''}${viewerIsLosing ? ' losing' : ''}` },
    // Batch 904 — aria-live on viewer status changes. A sighted user
    // sees the banner flip from "you're top" → "you were outbid" at a
    // glance; a screen-reader user needs an announcement. Both are
    // transient state, so `aria-live=polite` is right (not assertive —
    // neither banner requires interrupting an ongoing utterance).
    viewerIsTop && h('div', {
      className: 'auction-status-banner winning',
      role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true'
    },
      h('span', { className: 'auction-status-icon' }, '✓'),
      h('span', null,
        h('strong', null, "You're the top bidder — "),
        'staying on top is automatic if you set an auto-bid cap.'
      )
    ),
    viewerIsLosing && h('div', {
      className: 'auction-status-banner losing',
      role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true'
    },
      h('span', { className: 'auction-status-icon' }, '↑'),
      h('span', null,
        h('strong', null, 'You were outbid — '),
        `min next bid is ${fmt(minNext)}.`
      )
    ),
    // Soft-close extension banner — fires when we detect a positive
    // jump in expiresAt vs. the last polled value (someone's last-
    // second bid triggered anti-snipe). Flashes for ~8s then
    // auto-hides.
    extensionActive && h('div', {
      className: 'auction-status-banner',
      style: { background: 'rgba(251,191,36,0.15)', border: '1px solid rgba(251,191,36,0.4)', color: '#fbbf24' },
      // Batch 904 — aria-live announcement. A bidder using a screen
      // reader who is camping the last 30s needs to hear that the
      // anti-snipe rule fired and the auction just got extended —
      // otherwise they keep waiting on a timer that silently moved.
      // `role=status` (aria-live: polite by default) won't interrupt
      // whatever the reader is mid-way through, which matches the
      // severity: the extension is informational, not urgent.
      role: 'status',
      'aria-live': 'polite',
      'aria-atomic': 'true'
    },
      h('span', { className: 'auction-status-icon' }, '⏱'),
      h('span', null,
        h('strong', null, 'Auction extended — '),
        'a last-second bid tripped the anti-snipe rule; new close time is above.'
      )
    ),
    // Auction-ended outcome banner (batch 363). Once the timer hits
    // zero the server settles the auction; the polled `view` reflects
    // the final state (SOLD, buyerUserId = winner or seller-on-
    // no-bids). This banner makes the outcome human-readable so the
    // bidder doesn't have to parse the mute "Ended" label.
    ended && h('div', {
      className: 'auction-status-banner',
      style: auctionHadBids && !settling && wonRow
        ? (viewerWon
            ? { background: 'rgba(34,197,94,0.15)', border: '1px solid rgba(34,197,94,0.4)', color: 'var(--green)' }
            : { background: 'rgba(148,163,184,0.1)', border: '1px solid var(--border)', color: 'var(--text-secondary)' })
        : { background: 'rgba(251,191,36,0.12)', border: '1px solid rgba(251,191,36,0.4)', color: '#fbbf24' }
    },
      h('span', { className: 'auction-status-icon' },
        (!auctionHadBids || settling || !wonRow) ? '…' :
        viewerWon ? '✓' : '!'),
      h('span', { style: { flex: 1 } },
        settling
          ? h('span', null,
              h('strong', null, 'Auction ended — settling. '),
              'The result shows here in a few seconds.')
          : !auctionHadBids
          ? h('span', null,
              h('strong', null, 'Auction ended with no bids'),
              ' — the item is back in the seller\'s inventory.')
          : !wonRow
            ? h('span', null,
                h('strong', null, 'Auction closed without a sale'),
                ' — the top bid couldn\'t be completed, so the item went back to the seller.')
          : viewerWon
            ? h('span', null,
                h('strong', null, 'You won this auction! '),
                'Paid ', fmt(wonRow.amount), ' — open Profile → Trades to confirm.')
            : h('span', null,
                h('strong', null, 'Auction ended. '),
                (view.currentBidderName || 'A bidder') + ' won at ' + fmt(wonRow.amount) + '.')
      )
    ),
    h('div', { className: 'auction-header' },
      h('div', null,
        // Pre-fix the header read "CURRENT BID · $X" even when no bids
        // had been placed — but `X` was the seller's reserve, not a real
        // bid. CSFloat distinguishes "STARTING BID" (no bids yet) from
        // "CURRENT BID" (≥1 placed). Without the split, a 0-bid auction
        // looked like someone had already bid the reserve and a buyer
        // had to outbid it, when in reality the first bid only has to
        // MEET the starting price (BidService: first-bid floor = price
        // exactly; the $0.05 increment applies to subsequent bids only —
        // see the minNext fix dated 2026-05-20).
        h('div', { className: 'auction-label' },
          (view.bidCount > 0 || view.currentBid != null) ? 'CURRENT BID' : 'STARTING BID'),
        h('div', { className: 'auction-bid' }, fmt(view.currentBid || view.price)),
        view.currentBidderName && h('div', { className: 'auction-bidder' },
          viewerIsTop ? 'by you' : ('by ' + view.currentBidderName)),
        // 0-bid hint — explicit "be the first to bid" so the bidder
        // understands the displayed amount is the seller's reserve, not
        // an existing bid to beat.
        !ended && (view.bidCount === 0 && view.currentBid == null) && h('div', {
          className: 'auction-bidder',
          style: { marginTop: 4, fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', letterSpacing: 0.3 },
          // Display copy — convert through fmt() so a CAD/EUR/etc. viewer
          // sees the minimum-next-bid in their selected currency. The bid
          // input below still takes USD (matches deposit-preset pattern),
          // so this header label is a pure display read.
          title: `No bids yet — be the first to bid. Minimum is ${fmt(parseFloat(minNext))}.`
        }, '○ No bids yet · min ', fmt(parseFloat(minNext))),
        // Batch 643 — "Last bid Xm ago" activity chip. Surfaces the
        // temperature of the auction without forcing the viewer to
        // scroll to the Bid History section. `history[0]` is the most
        // recent bid (bidRepository returns newest-first). Hidden on
        // first render (history still loading) and when no bids
        // exist. Dim coloured when the last bid was >30 min ago so
        // the eye doesn't over-weight a quiet auction.
        history.length > 0 && history[0]?.createdAt && (() => {
          const since = now - history[0].createdAt;
          const quiet = since > 30 * 60_000;
          return h('div', {
            className: 'auction-bidder',
            style: {
              marginTop: 4, fontSize: 10, fontWeight: 700,
              color: quiet ? 'var(--text-muted)' : '#22c55e',
              letterSpacing: 0.3
            },
            title: quiet
              ? 'No recent bidding activity — this auction may be sleeping.'
              : 'Bidding active — someone placed a bid recently.'
          }, (quiet ? '○ ' : '● '), 'Last bid ', timeAgo(history[0].createdAt));
        })()
      ),
      h('div', { style: { textAlign: 'right' } },
        h('div', { className: 'auction-label' }, ended ? 'STATUS' : 'TIME LEFT'),
        h('div', { className: `auction-timer ${remaining < 60_000 ? 'urgent' : ''}` }, fmtTime(remaining))
      )
    ),
    // Your-auto-cap chip — CSFloat shows the bidder's own max-bid cap so
    // they remember the ceiling they committed to. Without this the user
    // sets a $50 max and then has no way to verify the cap stuck (the
    // current bid moves, so the displayed value won't match their cap).
    yourAutoCap != null && !ended && h('div', {
      className: 'auction-status-banner',
      style: { background: 'rgba(30,165,255,0.12)', border: '1px solid rgba(30,165,255,0.4)', color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: 8 },
      title: 'The proxy-bidder will auto-raise your bid up to this cap whenever someone outbids you.'
    },
      h('span', { className: 'auction-status-icon' }, 'ⓘ'),
      h('span', { style: { flex: 1 } },
        h('strong', null, 'Your auto-bid cap · '),
        fmt(yourAutoCap),
        ' — we\'ll keep you on top up to this amount.',
        // Inline text link — CSFloat keeps auto-bid controls quiet and
        // inline, not a button. Only present when there's a live AUTO
        // row to cancel. Retracts the cap only; the standing bid stays.
        yourActiveAutoBidId && h('span', null,
          ' · ',
          h('a', {
            href: '#',
            style: {
              color: 'var(--text-muted)', textDecoration: 'underline',
              fontSize: 11, cursor: cancellingCap ? 'wait' : 'pointer',
              opacity: cancellingCap ? 0.5 : 1
            },
            onClick: (e) => { e.preventDefault(); if (!cancellingCap) stopAutoBid(); },
            title: 'Stop the proxy-bidder from auto-raising this bid. Your current bid amount stays.'
          }, cancellingCap ? 'stopping…' : 'cancel')
        )
      )
    ),
    // Buy-Now on auction (batch 371). When the seller set a buyNowPrice,
    // a bidder can skip the auction at that price. Rendered between the
    // current-bid header and the bid form so it's visually separate from
    // the normal bid path. Hidden once the auction has ended or when the
    // viewer is the seller (can't buy-now your own auction).
    // Hidden once bidding reaches the Buy Now price: the server refuses
    // Buy Now from then on (BUY_NOW_UNAVAILABLE).
    !ended && view.buyNowPrice != null && parseFloat(view.buyNowPrice) > 0 &&
      !(view.currentBid != null && parseFloat(view.currentBid) >= parseFloat(view.buyNowPrice)) &&
      (!me || me.id !== view.sellerUserId) && h('div', {
        className: 'auction-status-banner',
        style: { background: 'rgba(34,197,94,0.10)', border: '1px solid rgba(34,197,94,0.35)',
                 color: 'var(--green)', display: 'flex', alignItems: 'center', gap: 10 }
      },
        h('span', { className: 'auction-status-icon' }, '↗'),
        h('span', { style: { flex: 1 } },
          h('strong', null, 'Buy Now · ', fmt(view.buyNowPrice)),
          ' — skip the auction and settle instantly.'),
        // Anonymous viewers see the Buy Now price + a sign-in CTA rather
        // than nothing — previously the whole banner was gated on `me`,
        // so a logged-out shopper couldn't tell the auction even HAD a
        // buy-now option. CSFloat surfaces the price to everyone and
        // gates only the action behind auth.
        !me
          ? h('button', {
              className: 'buy-btn',
              style: { padding: '8px 14px', background: 'var(--green)', color: '#0b0f1a', fontWeight: 800 },
              onClick: () => signInWithSteam()
            }, 'Sign in to buy')
          : (() => {
          const hasTradeUrl = !!(me && me.tradeUrl && String(me.tradeUrl).trim());
          return h('button', {
            className: 'buy-btn',
            style: { padding: '8px 14px', background: 'var(--green)', color: '#0b0f1a', fontWeight: 800,
                     opacity: hasTradeUrl ? 1 : 0.55, cursor: hasTradeUrl ? 'pointer' : 'not-allowed' },
            disabled: busy || !hasTradeUrl,
            title: hasTradeUrl ? '' : 'Add your Steam trade URL in Profile before using Buy Now',
            onClick: async () => {
              if (!hasTradeUrl) return;
              if (!confirm(`Buy Now at ${fmt(view.buyNowPrice)}? Closes the auction instantly and transfers the item to you.`)) return;
              // Synchronous re-entrancy latch — mirror the bid submit (line ~2805).
              // The blocking confirm() above already serialises clicks, but latch
              // anyway so every money submit in this component is guarded the same
              // way. busyRef auto-resets via the per-render sync after setBusy(false).
              if (busyRef.current) return;
              busyRef.current = true;
              setBusy(true);
              try {
                const { buyNowAuction } = await import('./api.js');
                // Batch 961 — pass the rendered buyNowPrice as the
                // expectation. Server rejects with PRICE_CHANGED if the
                // seller edited the buy-now price since this modal
                // loaded, so the buyer is never silently debited at a
                // higher amount.
                const res = await buyNowAuction(listing.id, view.buyNowPrice);
                if (res && (res.error || res.code)) {
                  // Pre-fix: setErr only renders below the bid form,
                  // 200+ pixels below the Buy Now banner — a buyer who
                  // clicks Buy Now and fails could miss the err line.
                  // Mirror the placeBid toast pattern so the error is
                  // surfaced page-level too.
                  toast(res.message || res.error || 'Buy Now failed.', 'err');
                  setErr(res.message || res.error || 'Buy Now failed'); return;
                }
                // Pre-fix: silent on success. A buyer paying $X via
                // Buy Now saw only the auction panel re-render to ENDED
                // — no toast confirming the trade opened. Mirror the
                // normal handleBuy toast (app.js:4807) and the placeBid
                // toast (line 2182) so the success path is symmetric
                // across all three auction-completion buttons.
                const name = listing?.item?.name;
                const paid = res?.price != null ? parseFloat(res.price) : parseFloat(view.buyNowPrice);
                const copy = name
                  ? `Bought "${name}" via Buy Now${isFinite(paid) ? ` for ${fmt(paid)}` : ''} — trade opened, see Profile › Trades`
                  : `Buy Now complete${isFinite(paid) ? ` (${fmt(paid)})` : ''} — trade opened, see Profile › Trades`;
                toast(copy, 'ok');
                load();
                onPlaced && onPlaced();
              } finally { setBusy(false); }
            }
          }, 'Buy Now');
        })()
      ),
    // Batch 792 — trade-URL preflight banner. The button below is
    // already gated on trade URL, but a banner explains WHY so the
    // bidder understands and jumps to fix it. Only shown to signed-in
    // users (not-signed-in sees a Sign-in CTA below instead).
    !ended && me && !(me.tradeUrl && String(me.tradeUrl).trim()) && h('div', {
      style: {
        marginBottom: 10, padding: '8px 12px', borderRadius: 8,
        background: 'rgba(250,204,21,0.1)',
        border: '1px solid rgba(250,204,21,0.4)',
        color: '#fde68a', fontSize: 11, lineHeight: 1.5,
        display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'
      }
    },
      h('span', null, '⚠ '),
      h('span', { style: { flex: 1 } },
        h('strong', null, 'Steam trade URL needed '),
        '— required so the seller can ship if you win.'),
      h('a', {
        href: '/profile',
        style: { color: 'var(--accent)', fontWeight: 700, textDecoration: 'underline', fontSize: 11 }
      }, 'Fix in Profile →')
    ),
    // No bid form on your own auction — the server refuses seller bids.
    !ended && (!me || me.id !== view.sellerUserId) && h('div', { className: 'auction-bid-form' },
      h('input', { className: 'wallet-amount-input', type: 'number', step: '0.05', min: minNext,
        inputMode: 'decimal', enterKeyHint: 'send',
        'aria-label': 'Bid amount',
        placeholder: `Min ${fmt(minNext)}`, value: amount, onChange: e => setAmount(e.target.value) }),
      h('input', { className: 'wallet-amount-input', type: 'number', step: '0.05',
        inputMode: 'decimal', enterKeyHint: 'done',
        'aria-label': 'Auto-bid maximum cap',
        placeholder: 'Auto-bid cap (optional)', value: maxAmount, onChange: e => setMax(e.target.value) }),
      // Quick-bid chips — one-click increments from the minimum next
      // bid. Min button just echoes the minimum, +$0.50 / +$5 add to
      // it, +10% is percentage-based for higher-value auctions where
      // flat increments feel stingy. All pre-fill the amount input so
      // the user can still tweak before hitting Place Bid.
      h('div', { className: 'price-suggest-row', style: { marginTop: 6 } },
        (() => {
          const base = parseFloat(minNext) || 0;
          if (!(base > 0)) return null;
          // Chip labels echo the currency symbol so a CAD/EUR user
          // sees "+CA$0.50" / "+€0.50" instead of a hardcoded "+$0.50"
          // — the underlying bid still goes to the server as USD.
          const sym = currencySymbol();
          const chips = [
            { label: 'Min',           v: base },
            { label: `+${sym}0.50`,   v: +(base + 0.50).toFixed(2) },
            { label: `+${sym}5`,      v: +(base + 5.00).toFixed(2) },
            { label: '+10%',          v: +(base * 1.10).toFixed(2) }
          ];
          return chips.map((c, i) => h('button', {
            key: i,
            type: 'button',
            className: 'price-suggest-chip',
            onClick: () => setAmount(c.v.toFixed(2)),
            title: `Set bid to ${fmt(c.v)}`
          },
            h('span', { className: 'price-suggest-chip-label' }, c.label),
            h('span', { className: 'price-suggest-chip-amt' }, fmt(c.v))
          ));
        })()
      ),
      err && h('div', { className: 'wallet-error' }, err),
      // Balance pre-check (batch 430). The server's solvency guard fires
      // on submit; this chip surfaces the same math up front so the
      // bidder can add funds before wasting a round-trip. Bid amount is
      // compared against wallet balance, preferring `maxAmount` when the
      // auto-bid cap is set (server checks whichever is higher).
      (() => {
        if (!me || !wallet) return null;
        const bid = parseFloat(amount) || 0;
        const cap = parseFloat(maxAmount) || 0;
        const commit = Math.max(bid, cap);
        const bal = parseFloat(wallet.balance) || 0;
        if (!(commit > 0) || bal >= commit) return null;
        return h('div', {
          style: {
            marginTop: 6, padding: '8px 10px', borderRadius: 6,
            background: 'var(--red-dim)', border: '1px solid rgba(248,113,113,0.35)',
            color: 'var(--red)', fontSize: 11, lineHeight: 1.5
          },
          title: 'The server requires your wallet to cover the higher of bid / auto-bid cap at placeBid time.'
        },
          h('strong', null, 'Wallet short · '),
          `need ${fmt(commit)} to honor this bid; you have ${fmt(bal)}. `,
          h('a', {
            href: '/wallet',
            style: { color: 'var(--accent)', fontWeight: 700, textDecoration: 'underline' }
          }, `Deposit ${fmt(commit - bal)} →`)
        );
      })(),
      // Same anon-aware pattern as batches 144/145: swap the submit for
      // a Steam-OpenID redirect so the bidder lands back on the item
      // URL with the auction panel restored and ready for a real bid.
      !me
        ? h('button', {
            className: 'btn btn-accent',
            onClick: () => { signInWithSteam(); }
          }, 'Sign in to bid')
        : (() => {
            // Batch 792 — gate Place Bid on trade URL. Server already
            // throws TRADE_URL_MISSING on bidders without one; the UI
            // was silently letting them click through to an error
            // toast. Now we block client-side with an explanatory
            // tooltip so the user understands the block.
            const hasTradeUrl = me.tradeUrl && String(me.tradeUrl).trim();
            return h('button', {
              className: 'btn btn-accent',
              disabled: busy || !hasTradeUrl,
              title: !hasTradeUrl
                ? 'Add your Steam trade URL in Profile before bidding — required so the seller can ship on auction close'
                : undefined,
              onClick: submit
            }, busy ? 'Placing…' : 'Place Bid');
          })()
    ),
    history.length > 0 && h('div', { className: 'auction-history' },
      h('h3', { className: 'modal-section-title', style: { marginTop: 16 } },
        h('div', { className: 'section-title-dot' }),
        `Bid History (${history.length}${distinctBidders > 1 ? ` · ${distinctBidders} bidders` : ''})`),
      // Progression sparkline — lets a viewer see the climb at a glance.
      // The history list is amount-DESC; rebuild a time-ordered copy for
      // the chart so the line reads left-to-right from first bid to last.
      // Only render with 3+ bids; 2 points is just a diagonal line.
      (() => {
        if (history.length < 3) return null;
        const timeOrdered = [...history].sort((a, b) => a.createdAt - b.createdAt);
        const chartData = timeOrdered.map(b => ({
          price: parseFloat(b.amount),
          dayLabel: new Date(b.createdAt).toLocaleDateString(undefined,
            { month: 'short', day: 'numeric' }) + ' · ' + timeAgo(b.createdAt)
        }));
        return h('div', {
          style: { marginTop: 10, marginBottom: 10, padding: '10px 12px 6px',
            border: '1px solid var(--border)', borderRadius: 8, background: 'rgba(255,255,255,0.02)' }
        },
          h('div', { style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 4, letterSpacing: 0.4, textTransform: 'uppercase' } },
            'Bid progression'),
          h(Sparkline, { data: chartData, color: 'var(--up)', height: 90 })
        );
      })(),
      (showAllHistory ? history : history.slice(0, 8)).map(b => {
        const isMine = me && b.bidderUserId && b.bidderUserId === me.id;
        return h('div', { key: b.id, className: `auction-history-row${isMine ? ' you' : ''}` },
          h('div', { className: 'auction-history-bidder' },
            isMine ? 'You' : (b.bidderName || 'anon'),
            isMine && h('span', { className: 'auction-history-you-tag' }, 'YOU')
          ),
          h('div', { className: 'auction-history-kind' }, b.kind),
          h('div', { className: 'auction-history-amt' }, fmt(b.amount)),
          h('div', { className: 'auction-history-time' }, timeAgo(b.createdAt))
        );
      }),
      history.length > 8 && h('button', {
        className: 'btn btn-ghost',
        style: {
          width: '100%', marginTop: 8, padding: '8px 12px', fontSize: 12,
          border: '1px dashed var(--border)', color: 'var(--text-secondary)'
        },
        onClick: () => setShowAllHistory(v => !v)
      }, showAllHistory ? '↑ Collapse' : `↓ Show all ${history.length} bids`)
    )
  );
}

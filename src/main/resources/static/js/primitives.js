// Low-level visual primitives used by cards, rows, and modals.
import { h, useState, useEffect, useRef } from './utils.js';

/**
 * Renders a Google Material Symbols Rounded glyph. The font file is loaded
 * once in index.html via Google Fonts — we just inject a span with the
 * codepoint name. Consistent line-weight icons beat the mixed emoji set we
 * had in the user menu previously.
 *
 * Usage: h(MaterialIcon, { name: 'storefront', size: 18, fill: true })
 */
export function MaterialIcon({ name, size, fill, className, color }) {
  return h('span', {
    className: `material-symbols-rounded mi ${className || ''}`,
    style: {
      fontSize:              size ? size + 'px' : null,
      fontVariationSettings: fill ? '"FILL" 1' : null,
      color:                 color || null
    },
    'aria-hidden': true
  }, name);
}

// Map a Steam CDN image URL onto a higher-resolution size variant. The raw
// URLs Steam hands back look like `.../econ/image/{hash}/330x192` — just
// replacing the suffix gives us a crisper image at the same path. The grid
// thumbnails show images at ~300-400px on retina so the extra pixels matter.
//
// DEFENSIVE: callers can hand us anything — the /sell page crashed here
// once because Steam's inventory JSON returned a Uint8Array-like object
// for `imageUrl` on a specific item. Accept only actual strings; anything
// else falls through to the poster glyph.
function upscaleSteamImage(url, variant) {
  if (typeof url !== 'string' || !url) return null;
  // Only touch URLs that end with a recognisable `/{w}x{h}` suffix.
  return url.replace(/\/\d+x\d+(\?.*)?$/, '/' + variant);
}

// Category → fallback glyph — used when imageUrl is missing or the Steam CDN
// returns a 404. Batch 1068: emojis out (🎩🧥👕👖🧤🥾💍), editorial geometric
// glyphs in (◈▲■▮◉▼◆❖) per the operator's design template. Matches the
// s&box clothing slot vocabulary.
const CATEGORY_GLYPH = {
  Hats:        '◈',
  Jackets:     '▲',
  Shirts:      '■',
  Pants:       '▮',
  Gloves:      '◉',
  Boots:       '▼',
  Accessories: '◆',
  Workshop:    '❖'
};

function posterGlyph(item) {
  // Batch 1068 — ignore the legacy `item.iconEmoji` field from the DB. That
  // column was seeded with CS-style category emojis (🎩 🧥 👕 …) when the
  // marketplace launched; the editorial redesign bans emoji chrome so we
  // fall back to the geometric CATEGORY_GLYPH map instead, keyed on the
  // item's editorial category. The DB field still exists for back-compat
  // but no longer reaches the render path.
  if (!item) return '❖';
  return CATEGORY_GLYPH[item.category] || '❖';
}

/**
 * Opens the Steam Community Market listings page for a specific s&box item.
 * s&box's Steam app id is 590830. `market_hash_name` is URL-encoded; Steam
 * opens the exact item's listings page.
 *
 * Renders as a tiny pill button with an inline Steam logo svg. We call
 * stopPropagation so the card's own click handler doesn't also fire.
 */
export function SteamMarketLink({ item, compact }) {
  if (!item?.name) return null;
  const url = `https://steamcommunity.com/market/listings/590830/${encodeURIComponent(item.name)}`;
  return h('a', {
    className: `steam-market-link ${compact ? 'compact' : ''}`,
    href: url,
    target: '_blank',
    rel: 'noopener noreferrer',
    onClick: e => e.stopPropagation(),
    title: `View "${item.name}" on Steam Community Market`,
    // a11y: title alone is unreliable (only shows on hover, screen readers
    // skip it inconsistently). aria-label gives the link a real accessible
    // name for assistive tech + a11y scanners. SVG inside is decorative-
    // only (aria-hidden=true), so without the explicit label the link
    // would announce as nothing.
    'aria-label': `View "${item.name}" on Steam Community Market (opens in new tab)`
  },
    h('svg', {
      viewBox: '0 0 24 24',
      width: compact ? 13 : 15,
      height: compact ? 13 : 15,
      fill: 'currentColor',
      'aria-hidden': true
    },
      // Canonical Steam Valve logomark — shared with the sign-in buttons
      // for a consistent brand mark across every Steam touchpoint in the UI.
      h('path', {
        d: 'M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z'
      })
    ),
    !compact && h('span', null, 'Steam')
  );
}

/**
 * Lazy-loading image with a proper skeleton and category-based fallback.
 * Variants:
 *   'thumb'  — 330x192  (rows, tickers)
 *   'card'   — 512x384  (grid cards, hero tabs)
 *   'hero'   — 1024x768 (modal hero)
 */
export function ItemImage({ item, alt, variant = 'card' }) {
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  if (!item) return h('span', null, '—');

  const url = item.imageUrl && !failed
    ? upscaleSteamImage(item.imageUrl,
        variant === 'hero'  ? '1024x768' :
        variant === 'thumb' ? '330x192'  : '512x384')
    : null;

  if (!url) {
    return h('span', { className: 'item-poster', 'data-variant': variant }, posterGlyph(item));
  }
  return h('img', {
    src: url,
    alt: alt || item.name,
    loading: 'lazy',
    decoding: 'async',
    draggable: false,
    className: `item-img ${loaded ? 'loaded' : 'loading'}`,
    onLoad: (e) => {
      // Steam CDN sometimes returns 200 with a tiny/transparent pixel for
      // items whose source image was delisted — onError never fires, so
      // we'd render an "empty rectangle" card. Treat suspiciously small
      // natural dimensions as a load failure and fall back to the poster.
      if (e.target.naturalWidth < 10 || e.target.naturalHeight < 10) {
        setFailed(true);
      } else {
        setLoaded(true);
      }
    },
    onError: () => setFailed(true)
  });
}

/**
 * Avatar image with graceful fallback to a two-letter initials chip.
 * Many surfaces (nav, stall hero, seller strip, messages) render a
 * Steam avatar URL directly — when the CDN 404s (renamed user,
 * rate-limit, transient outage) the default `<img>` tag shows a broken
 * image icon, which reads as a bug. This primitive flips to the same
 * initials chip we already use for users with no avatarUrl. The styling
 * hooks live at the call site so each surface can size / position the
 * image however it needs; we only own the fallback swap behavior.
 */
export function Avatar({ src, name, alt, className, style }) {
  const [failed, setFailed] = useState(false);
  const safeName = (name || '').trim() || 'U';
  const initials = safeName.substring(0, 2).toUpperCase();
  if (!src || failed) {
    // Caller-provided styling wins — we only seed sensible defaults when
    // the caller didn't specify size / background.
    const wrapStyle = Object.assign({
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      fontWeight: 700, fontSize: 13, color: 'var(--text-primary)',
      background: 'var(--bg-card)', border: '1px solid var(--border)',
      borderRadius: '50%', width: 32, height: 32
    }, style || {});
    return h('span', { className, style: wrapStyle, 'aria-label': safeName }, initials);
  }
  return h('img', {
    src,
    alt: alt || safeName,
    loading: 'lazy',
    decoding: 'async',
    draggable: false,
    className,
    style,
    onError: () => setFailed(true)
  });
}

// Boss QA F4 cycle 9 — rarity colour map. Even if only Standard is
// seeded today, ship the full ladder so future tiers (Scarce / Rare /
// Legendary) render the boss-spec colours the moment seed data adds
// them. Apply as inline style so it wins against any prior class CSS
// without needing a dedicated rule per tier.
//
// Boss QA cycle 14 A6 — Standard moved off the hand-rolled gray-300 hex
// onto var(--ink-2). The literal #d1d5db read fine on the dark body, but
// on themed surfaces (light theme, accent swap) it stayed cold-gray and
// fell out of contrast. --ink-2 is the brand "secondary text" token; it
// re-resolves per theme so contrast holds wherever the badge renders.
const RARITY_COLORS = {
  'Standard':   'var(--ink-2)',  // resolves per theme — see comment above
  'Off-Market': '#d4a418', // amber (display label = "Scarce")
  'Scarce':     '#d4a418',
  'Rare':       '#1ea5ff', // cta blue
  'Legendary':  '#8b5cf6'  // purple
};
export function RarityBadge({ rarity }) {
  // Boss QA D3 — items priced and live on the market were rendering an
  // "OFF-Market" badge because the schema's `rarity = 'Off-Market'` value
  // means low-supply (<5% of total) for s&box items. The badge text was
  // read as "no longer for sale", which contradicted the visible price.
  // Map the underlying Off-Market rarity to a clearer "Scarce" label
  // while keeping the data layer + filter chips on the original token.
  const display = rarity === 'Off-Market' ? 'Scarce' : rarity;
  const color = RARITY_COLORS[rarity] || RARITY_COLORS[display] || RARITY_COLORS.Standard;
  const tint = `color-mix(in oklab, ${color} 16%, transparent)`;
  const edge = `color-mix(in oklab, ${color} 38%, transparent)`;
  return h('span', {
    className: `rarity-badge rarity-${rarity}`,
    style: { color, background: tint, border: `1px solid ${edge}` }
  }, display);
}

export function RarityBar({ score, compact }) {
  const pct = Math.round(parseFloat(score || 0) * 100);
  return h('div', { className: 'rarity-bar-wrap' },
    h('div', { className: 'rarity-bar-outer', style: compact ? { width: 60 } : {} },
      h('div', { className: 'rarity-bar-inner', style: { width: pct + '%' } })
    ),
    h('span', { className: 'rarity-score-val' }, parseFloat(score || 0).toFixed(4))
  );
}

/**
 * Decorative rarity-band bar. CSFloat shows a gradient bar (green→red)
 * with a thumb marking the skin's CS float (0.0-1.0). s&box items have
 * no float / paint-seed (CS-only mechanics — see `s&box vs CSFloat`
 * memory), so we keep the silhouette but make it represent rarity:
 *
 *   Limited     → 0-15%  (green band)
 *   Off-Market  → 30-50% (yellow band)
 *   Standard    → 55-80% (red band)
 *
 * Within a rarity zone the listing id picks the exact thumb position
 * so cards of the same rarity don't all share the spot.
 *
 * The meta text under the bar reads the rarity band name + the real
 * listing id (`Standard · #35`). Earlier versions printed a synthetic
 * `0.56590…` float and a `(#N)` paint-seed-style rank — both CS-only
 * mechanics that don't exist in s&box, so they were misleading data.
 */
export function FloatBar({ rarity, listingId, compact }) {
  // H1/I1/S5/Boss-QA: there is no float/wear/condition mechanic on s&box
  // items — only CS-GO has it. Keeping the red→green gradient bar under
  // every card was leaking CS chrome into a non-CS marketplace and made
  // the cards look generic. Component now renders nothing; we keep the
  // export so existing callers don't crash.
  return null;
}

/**
 * Price-history sparkline with a hover tooltip and min/max markers. The
 * tooltip follows the mouse along the x axis and snaps to the nearest
 * data point. Pure inline SVG — no external chart lib.
 */
export function Sparkline({ data, color, height }) {
  const [hover, setHover] = useState(null);
  if (!data || data.length < 2) return null;
  // Batch 1068 — default to the editorial ink-2 color so a caller that
  // forgets `color` doesn't hit `undefined.replace` (previously NPE'd
  // in the gradient-id expression below). Sparkline is also used inside
  // the notification feed + profile chips, both of which may render
  // before their color context is resolved.
  const colorSafe = color || '#c8cfe0';
  const prices = data.map(d => parseFloat(d.price));
  const min = Math.min(...prices), max = Math.max(...prices);
  const range = max - min || 1;
  const W = 600, H = height || 140;
  const padTop = H * 0.09, bandH = H * 0.82;

  const pts = prices.map((p, i) => {
    const x = (i / (prices.length - 1)) * W;
    const y = H - ((p - min) / range) * bandH - padTop;
    return { x, y, price: p, label: data[i].dayLabel };
  });
  const polyline = pts.map(p => `${p.x},${p.y}`).join(' ');
  const area = `0,${H} ${polyline} ${W},${H}`;
  // Hash-ish id that survives non-hex color inputs (e.g. var(--up))
  // so callers can pass design-token colors instead of raw hex.
  const gradId = 'grad-' + String(colorSafe).replace(/[^a-z0-9]/gi, '');
  const minIdx = prices.indexOf(min);
  const maxIdx = prices.indexOf(max);

  const onMove = (e) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / rect.width * W;
    // Find nearest data point
    let nearest = 0;
    let best = Infinity;
    pts.forEach((p, i) => {
      const d = Math.abs(p.x - relX);
      if (d < best) { best = d; nearest = i; }
    });
    setHover(nearest);
  };

  // Batch 828 — a11y label on the SVG so a screen reader announces
  // the chart as "Price history chart, $min to $max, N points" rather
  // than skipping it entirely (svgs default to "image" with no label).
  // Hover interactions stay mouse-only — low value for a keyboard-
  // driven reader and adds significant tab-stop churn.
  const firstPrice = prices[0];
  const lastPrice  = prices[prices.length - 1];
  const deltaPct   = firstPrice > 0 ? Math.round(((lastPrice - firstPrice) / firstPrice) * 100) : 0;
  const chartDesc  = `Price history chart, ${prices.length} points. ` +
    `Range $${min.toFixed(2)} to $${max.toFixed(2)}. ` +
    (deltaPct > 0 ? `Up ${deltaPct}% overall.`
     : deltaPct < 0 ? `Down ${Math.abs(deltaPct)}% overall.`
     : 'Flat overall.');
  return h('div', { className: 'sparkline-wrap' },
    h('svg', {
      className: 'chart',
      viewBox: `0 0 ${W} ${H}`,
      preserveAspectRatio: 'none',
      onMouseMove: onMove,
      onMouseLeave: () => setHover(null),
      role: 'img',
      'aria-label': chartDesc
    },
      h('defs', null,
        h('linearGradient', { id: gradId, x1: '0', y1: '0', x2: '0', y2: '1' },
          h('stop', { offset: '0%',   stopColor: colorSafe, stopOpacity: '0.35' }),
          h('stop', { offset: '100%', stopColor: colorSafe, stopOpacity: '0' })
        )
      ),
      // Gridlines at 25% / 50% / 75%
      [0.25, 0.5, 0.75].map(f => h('line', {
        key: f,
        x1: 0, x2: W, y1: padTop + bandH * f, y2: padTop + bandH * f,
        stroke: 'rgba(255,255,255,0.04)', strokeWidth: 1
      })),
      h('polygon',  { points: area,     fill: `url(#${gradId})` }),
      h('polyline', { points: polyline, fill: 'none', stroke: colorSafe, strokeWidth: '2.2',
                      strokeLinejoin: 'round', strokeLinecap: 'round' }),
      // Min / max dots so the viewer can spot the extremes at a glance
      h('circle', { cx: pts[minIdx].x, cy: pts[minIdx].y, r: 4, fill: 'var(--down)', stroke: 'var(--bg)',  strokeWidth: 2 }),
      h('circle', { cx: pts[maxIdx].x, cy: pts[maxIdx].y, r: 4, fill: 'var(--up)',   stroke: 'var(--bg)',  strokeWidth: 2 }),
      // Hover crosshair + point
      hover !== null && h('line', {
        x1: pts[hover].x, x2: pts[hover].x, y1: 0, y2: H,
        stroke: colorSafe, strokeOpacity: 0.25, strokeWidth: 1, strokeDasharray: '3 3'
      }),
      hover !== null && h('circle', {
        cx: pts[hover].x, cy: pts[hover].y, r: 5,
        fill: colorSafe, stroke: 'var(--bg)', strokeWidth: 2
      })
    ),
    hover !== null && h('div', {
      className: 'sparkline-tooltip',
      style: { left: `${(pts[hover].x / W) * 100}%` }
    },
      h('div', { className: 'sparkline-tt-price' }, '$' + pts[hover].price.toFixed(2)),
      h('div', { className: 'sparkline-tt-date' }, pts[hover].label || '')
    )
  );
}

// Shared inline "reason / note" drawer — replaces `window.prompt()` across
// moderation + report flows (stall admin-remove, report-review, loadout
// takedown, etc). Accessible dialog semantics (role=dialog,
// aria-labelledby), autofocus-with-cursor-at-end, Esc cancels,
// Ctrl+Enter submits, running char counter, submit disabled on empty.
// Lives in primitives so both app.js and csfloat-modals.js can import
// without creating an import cycle (batches 850–852).
export function ReasonDrawer({ title, hint, initial, cta, busy, onCancel, onSubmit, maxLen = 500 }) {
  const [text, setText]   = useState(initial || '');
  const textareaRef       = useRef(null);
  useEffect(() => {
    const id = requestAnimationFrame(() => {
      if (textareaRef.current) {
        textareaRef.current.focus({ preventScroll: true });
        try {
          const t = textareaRef.current;
          t.selectionStart = t.value.length;
          t.selectionEnd   = t.value.length;
        } catch (_) {}
      }
    });
    const onKey = (e) => {
      if (e.key === 'Escape' && !busy) { e.stopPropagation(); onCancel(); }
    };
    document.addEventListener('keydown', onKey);
    return () => { cancelAnimationFrame(id); document.removeEventListener('keydown', onKey); };
  }, [onCancel, busy]);
  const trimmed  = (text || '').trim();
  const canSubmit = trimmed.length > 0 && trimmed.length <= maxLen && !busy;
  return h('div', {
    role: 'dialog',
    'aria-modal': 'false',
    'aria-labelledby': 'reason-drawer-title',
    style: {
      width: '100%', padding: 12,
      background: 'var(--bg-elevated, #1a1c20)',
      border: '1px solid var(--border)', borderRadius: 6
    }
  },
    h('div', {
      id: 'reason-drawer-title',
      style: { fontSize: 12, fontWeight: 700, marginBottom: 4 }
    }, title),
    hint && h('div', {
      style: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 8, lineHeight: 1.5 }
    }, hint),
    h('textarea', {
      ref: textareaRef,
      value: text,
      maxLength: maxLen,
      rows: 3,
      onChange: (e) => setText(e.target.value),
      onKeyDown: (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && canSubmit) {
          e.preventDefault(); onSubmit(trimmed);
        }
      },
      'aria-label': title,
      style: {
        width: '100%', padding: '6px 8px', fontSize: 12,
        background: 'var(--bg, #0f1115)', color: 'var(--text)',
        border: '1px solid var(--border)', borderRadius: 4,
        resize: 'vertical', fontFamily: 'inherit'
      }
    }),
    h('div', {
      style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 }
    },
      h('span', { style: { fontSize: 10, color: 'var(--text-muted)' } },
        `${trimmed.length}/${maxLen} · Ctrl+Enter to send`),
      h('div', { style: { display: 'flex', gap: 6 } },
        h('button', {
          className: 'btn btn-ghost',
          style: { padding: '5px 12px', fontSize: 11 },
          onClick: onCancel,
          disabled: busy
        }, 'Cancel'),
        h('button', {
          className: 'btn btn-primary',
          style: { padding: '5px 12px', fontSize: 11 },
          disabled: !canSubmit,
          onClick: () => onSubmit(trimmed)
        }, busy ? 'Sending…' : cta)
      )
    )
  );
}

// Inline date-range filter used next to CSV-export buttons on the
// /profile trades / offers / bids / my-stall sold panels. Two native
// `<input type="date">` controls (browser-native picker, accessible
// for free) wired to caller-provided `from` / `to` epoch-ms state. The
// caller is responsible for using the values to filter the visible row
// list AND to append `?from=…&to=…` query params on the CSV download
// link — matches the controller params added in batch 1101.
//
// Conventions:
//   - Empty input -> caller state is null -> no bound on that side.
//   - `to` is treated as inclusive end-of-day (23:59:59.999) so picking
//     "Mar 31" on the right does the right thing for tax-quarter
//     exports without forcing the user to think in UTC.
//   - A clear button surfaces only when at least one bound is set.
export function DateRangeFilter({ from, to, onChange, compact }) {
  const toIsoDay = (ms) => {
    if (ms == null) return '';
    const d = new Date(Number(ms));
    if (isNaN(d.getTime())) return '';
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  };
  const fromStartOfDay = (s) => {
    if (!s) return null;
    const [y, m, d] = s.split('-').map(Number);
    if (!y || !m || !d) return null;
    return new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
  };
  const fromEndOfDay = (s) => {
    if (!s) return null;
    const [y, m, d] = s.split('-').map(Number);
    if (!y || !m || !d) return null;
    return new Date(y, m - 1, d, 23, 59, 59, 999).getTime();
  };
  const inputStyle = {
    border:  '1px solid var(--border)',
    background:  'var(--bg-card)',
    color:   'var(--text-primary)',
    borderRadius: 6,
    padding: compact ? '3px 6px' : '4px 8px',
    fontSize: 11,
    fontFamily: 'var(--font-mono, ui-monospace, SFMono-Regular, monospace)'
  };
  const hasBound = from != null || to != null;
  return h('span', {
    className: 'date-range-filter',
    style: { display: 'inline-flex', alignItems: 'center', gap: 4 }
  },
    h('span', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em' } }, 'From'),
    h('input', {
      type: 'date',
      style: inputStyle,
      value: toIsoDay(from),
      max:   toIsoDay(to) || undefined,
      onChange: (e) => onChange({ from: fromStartOfDay(e.target.value), to }),
      'aria-label': 'Date range start'
    }),
    h('span', { style: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em' } }, 'To'),
    h('input', {
      type: 'date',
      style: inputStyle,
      value: toIsoDay(to),
      min:   toIsoDay(from) || undefined,
      onChange: (e) => onChange({ from, to: fromEndOfDay(e.target.value) }),
      'aria-label': 'Date range end'
    }),
    hasBound && h('button', {
      type: 'button',
      className: 'btn btn-ghost',
      style: { border: '1px solid var(--border)', padding: '2px 6px', fontSize: 10 },
      onClick: () => onChange({ from: null, to: null }),
      title: 'Clear date filter',
      'aria-label': 'Clear date filter'
    }, '×')
  );
}

// Helper: append ?from=&to= to a CSV-export URL when bounds are set.
// Used by the CSV anchor links so a user filtering "last quarter" gets
// the matching server-side slice in the download.
export function appendDateRange(href, from, to) {
  if (from == null && to == null) return href;
  const sep = href.includes('?') ? '&' : '?';
  const parts = [];
  if (from != null) parts.push('from=' + encodeURIComponent(String(from)));
  if (to   != null) parts.push('to='   + encodeURIComponent(String(to)));
  return href + sep + parts.join('&');
}

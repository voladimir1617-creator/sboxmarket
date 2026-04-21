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
    title: `View "${item.name}" on Steam Community Market`
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
    onLoad: () => setLoaded(true),
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

export function RarityBadge({ rarity }) {
  return h('span', { className: `rarity-badge rarity-${rarity}` }, rarity);
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
  const gradId = 'grad-' + colorSafe.replace('#', '');
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
      h('circle', { cx: pts[minIdx].x, cy: pts[minIdx].y, r: 4, fill: '#f87171', stroke: '#1a1a2a', strokeWidth: 2 }),
      h('circle', { cx: pts[maxIdx].x, cy: pts[maxIdx].y, r: 4, fill: '#4ade80', stroke: '#1a1a2a', strokeWidth: 2 }),
      // Hover crosshair + point
      hover !== null && h('line', {
        x1: pts[hover].x, x2: pts[hover].x, y1: 0, y2: H,
        stroke: colorSafe, strokeOpacity: 0.25, strokeWidth: 1, strokeDasharray: '3 3'
      }),
      hover !== null && h('circle', {
        cx: pts[hover].x, cy: pts[hover].y, r: 5,
        fill: colorSafe, stroke: '#0d1320', strokeWidth: 2
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

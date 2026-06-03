// Low-level visual primitives used by cards, rows, and modals.
import { h, useState, useEffect, useRef, fmt, timeAgo } from './utils.js';

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
  /* Boss QA cycle 13 — original implementation only RESIZED URLs that
     already had a `/{w}x{h}` suffix. The /api/items + /api/listings
     payloads return raw `.../econ/image/{hash}` URLs WITHOUT a size
     suffix; those slipped through unchanged and Steam's CDN returned
     the full original (400-500KB). The 'mini' variant was therefore
     a no-op on /db (the page that needed it most). Now: if the URL
     already ends with /WxH, swap it; otherwise append /WxH so we get
     the smaller variant from Steam's CDN regardless of incoming format.
     Only touch akamaihd Steam CDN URLs to avoid mangling other hosts. */
  if (!/steamcommunity-a\.akamaihd\.net\/economy\/image\//.test(url)) return url;
  if (/\/\d+x\d+(\?.*)?$/.test(url)) {
    return url.replace(/\/\d+x\d+(\?.*)?$/, '/' + variant);
  }
  // Strip any trailing query string before appending the size, then re-attach.
  const m = url.match(/^(.+?)(\?.*)?$/);
  return (m[1].replace(/\/$/, '')) + '/' + variant + (m[2] || '');
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
 *   'mini'   — 96x96     (db rows, picker thumbs)
 *   'thumb'  — 330x192   (rows, tickers)
 *   'card'   — 512x384   (grid cards, hero tabs)
 *   'hero'   — 1024x768  (modal hero)
 */
export function ItemImage({ item, alt, variant = 'card' }) {
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  // Reset the failed/loaded flags when the underlying image URL changes.
  // Without this, a React-recycled ItemImage (same DOM slot, new `item`
  // prop after a grid sort / list refresh) keeps a stale `failed: true`
  // and renders the poster glyph for a perfectly valid new image.
  const srcKey = item && item.imageUrl;
  useEffect(() => { setFailed(false); setLoaded(false); }, [srcKey]);
  if (!item) return h('span', null, '—');

  const url = item.imageUrl && !failed
    ? upscaleSteamImage(item.imageUrl,
        /* Boss QA cycle 13 — added 'mini' variant. /db row thumbs render at
           48×48 (or 28×28 for the .sm modifier), but the default 'card'
           variant pulls 512×384 PNGs from Steam — ~250-360KB each, scaled
           down 10× by the browser. /db has 20+ rows, so a /db visit was
           burning 5MB+ on PNGs that nobody saw at full res. /96x96 is a
           Steam CDN size variant and lands at ~3-5KB each — that drops a
           /db cold-load from ~10MB to ~5MB. */
        variant === 'hero'  ? '1024x768' :
        variant === 'thumb' ? '330x192'  :
        variant === 'mini'  ? '96x96'    : '512x384')
    : null;

  if (!url) {
    // Poster fallback. The `mini` variant has no dedicated CSS size rule
    // (design.css styles only thumb / hero); reuse 'thumb' as its data
    // attribute so the small-cell glyph picks up the 22px sizing instead
    // of overflowing its 48×48 / 28×28 db-row cell at the default 40px.
    const posterVariant = variant === 'mini' ? 'thumb' : variant;
    // a11y: with no image, the glyph IS the only visual for the item —
    // give it an accessible name so screen readers announce the item
    // rather than skipping a decorative-looking span.
    return h('span', {
      className: 'item-poster',
      'data-variant': posterVariant,
      role: 'img',
      'aria-label': alt || item.name || 'Item image unavailable'
    }, posterGlyph(item));
  }
  return h('img', {
    src: url,
    alt: alt || item.name || '',
    loading: 'lazy',
    decoding: 'async',
    // Steam's CDN (steamcommunity-a.akamaihd.net) returns 403 for hotlinked
    // requests that carry a cross-origin `Referer` header — the dominant
    // cause of the "~45% of /db thumbnails render as broken-image boxes on
    // cold load" the audit flagged. The page origin differs from Steam's, so
    // every thumbnail ships our referrer and a slice of them get bounced.
    // `no-referrer` strips the header so the CDN serves the image; it's a
    // no-op on hosts that don't gate on referrer, so it's safe for every
    // consumer (cards, /db rows, modal hero) that shares this primitive.
    referrerPolicy: 'no-referrer',
    draggable: false,
    className: `item-img ${loaded ? 'loaded' : 'loading'}`,
    // ref-callback mount guard: an <img> whose src already 404'd on a prior
    // render (browser HTTP cache remembers the failure) can mount in the
    // `complete` state with naturalWidth 0 and fire NEITHER onLoad nor
    // onError — React attaches the handlers after the cached result resolves.
    // Without this, that image stays a broken-image box forever, which is
    // exactly the cold-load failure mode the audit saw. Inspecting the node
    // synchronously on attach lets us flip to the poster glyph immediately.
    ref: (node) => {
      if (!node) return;
      if (node.complete) {
        if (node.naturalWidth === 0 || node.naturalHeight === 0) setFailed(true);
        else if (node.naturalWidth < 10 || node.naturalHeight < 10) setFailed(true);
        // Cached-SUCCESS case: an image already decoded at mount fires neither
        // onLoad nor onError, so without this the card stayed opacity:0
        // ("loading") forever — the market grid rendered ~37/38 blank cards on
        // cold/cached load. Reveal a valid cached image immediately.
        else setLoaded(true);
      }
    },
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
  // Clear the failed flag when `src` changes — a React-recycled Avatar
  // (same seller-cell DOM slot, new seller after a marketplace refresh)
  // would otherwise stay stuck on the initials chip for a valid new URL.
  useEffect(() => { setFailed(false); }, [src]);
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

// Rarity colour map — fallback ONLY. The CSFloat-accurate gradient pills
// for the shipped s&box tiers (Standard / Limited / Off-Market) live in
// design.css as `.rarity-badge.rarity-{tier}` rules. This map is the
// inline-style fallback for any tier the stylesheet does NOT cover
// (future seed data: Scarce / Rare / Legendary) so the badge still gets
// a sensible colour instead of falling back to flat gray.
//
// CRITICAL: do NOT add Standard / Limited / Off-Market here. Emitting an
// inline `style` for a CSS-covered tier overrides the class gradient
// (inline beats class specificity) and the badge loses its CSFloat look.
const RARITY_COLORS = {
  'Scarce':     '#d4a418', // amber
  'Rare':       '#1ea5ff', // cta blue
  'Legendary':  '#8b5cf6'  // purple
};
// Tiers whose full visual treatment is owned by design.css. For these we
// emit the class only and pass NO inline style, so the gradient pill,
// accent left-rail, and per-theme text colour all apply correctly.
const CSS_STYLED_RARITIES = new Set(['Standard', 'Limited', 'Off-Market']);
export function RarityBadge({ rarity }) {
  // Guard: a missing rarity (non-entity payload / partial DTO) otherwise
  // rendered an empty colored pill with a meaningless `rarity-undefined`
  // class. Render nothing instead — matches how the other primitives bail
  // on absent data.
  if (!rarity) return null;
  // Normalise to a string — defensive against a numeric / enum-object
  // rarity slipping through from an unexpected payload shape.
  const tier = String(rarity);
  // Boss QA D3 — items priced and live on the market were rendering an
  // "OFF-Market" badge because the schema's `rarity = 'Off-Market'` value
  // means low-supply (<5% of total) for s&box items. The badge text was
  // read as "no longer for sale", which contradicted the visible price.
  // Map the underlying Off-Market rarity to a clearer "Scarce" label
  // while keeping the data layer + filter chips on the original token.
  const display = tier === 'Off-Market' ? 'Scarce' : tier;
  // 2026-05-20: the CSFloat-1:1 parity tooltip (design.css ship #10304 —
  // `.db-table .rarity-badge[title]::after`) was wired up CSS-side but never
  // fired because RarityBadge emitted no `title`. Supply a concise tier
  // description (mirrors the cf-rarity-legend copy in csfloat-modals.js) so
  // the hover tooltip works on /db rows as the stylesheet intends. Keyed on
  // the canonical `tier`, not the relabelled `display`, so an Off-Market
  // pill explains the underlying scarcity tier.
  const RARITY_TITLE = {
    'Standard':   'Standard — common items everyone can craft.',
    'Off-Market': 'Off-Market — scarce items not currently sold by Steam.',
    'Limited':    'Limited — capped supply, hardest to find.'
  };
  // a11y: the gradient + text alone don't tell assistive tech this pill
  // is a rarity tier — give it an explicit role + label.
  const base = {
    className: `rarity-badge rarity-${tier}`,
    role: 'img',
    'aria-label': `Rarity: ${display}`,
    title: RARITY_TITLE[tier] || `${display} rarity`
  };
  // CSS-covered tier → class only; the stylesheet draws the pill.
  if (CSS_STYLED_RARITIES.has(tier)) return h('span', base, display);
  // Unknown tier → inline-style fallback so it still reads as a tier.
  const color = RARITY_COLORS[tier] || RARITY_COLORS[display] || 'var(--ink-2)';
  const tint = `color-mix(in oklab, ${color} 16%, transparent)`;
  const edge = `color-mix(in oklab, ${color} 38%, transparent)`;
  return h('span', Object.assign({}, base, {
    style: { color, background: tint, border: `1px solid ${edge}` }
  }), display);
}

export function RarityBar({ score, compact }) {
  // Guard: a non-numeric / NaN score (partial DTO, unparseable string)
  // otherwise rendered `width: NaN%` (bar collapses) and the literal
  // text "NaN" — matches the NaN-safety pattern in fmt() / timeAgo().
  const raw = parseFloat(score);
  const val = Number.isFinite(raw) ? raw : 0;
  // Clamp to 0–100 so an out-of-range score can't overflow the track.
  const pct = Math.min(100, Math.max(0, Math.round(val * 100)));
  return h('div', { className: 'rarity-bar-wrap' },
    h('div', { className: 'rarity-bar-outer', style: compact ? { width: 60 } : {} },
      h('div', { className: 'rarity-bar-inner', style: { width: pct + '%' } })
    ),
    h('span', { className: 'rarity-score-val' }, val.toFixed(4))
  );
}

/* (2026-05-21) `FloatBar` removed. s&box items have no float/wear/
   paint-seed mechanic, so the component had been gutted to `return
   null` and every call site is now deleted — keeping a dead export
   only invited new callers to wire up CS-only chrome. */

/**
 * Price-history sparkline with a hover tooltip and min/max markers. The
 * tooltip follows the mouse along the x axis and snaps to the nearest
 * data point. Pure inline SVG — no external chart lib.
 */
export function Sparkline({ data, color, height, showAxes }) {
  const [hover, setHover] = useState(null);
  if (!data || data.length < 2) return null;
  // Default to CSFloat's signature price-line blue (rgb(35,123,255)) so a
  // caller that forgets `color` still draws the on-brand thin blue line
  // instead of a muted gray that reads as "disabled". This also guards the
  // gradient-id expression below from `undefined.replace`. The ItemModal
  // passes an explicit trend color (var(--up)/var(--down)) for its red/green
  // up-or-down semantics, so this default only affects color-less callers
  // (notification feed, profile chips) — which should look like CSFloat.
  const colorSafe = color || 'rgb(35,123,255)';
  // Keep the original index alongside the price so the dayLabel lookup
  // and min/max markers stay correct after we drop bad points. A single
  // NaN price (malformed history row, in-flight DTO) would otherwise
  // poison Math.min/Math.max → every coordinate becomes NaN → the SVG
  // path string is `NaN,NaN …` and the whole chart renders blank.
  const series = data
    .map((d, i) => ({ price: parseFloat(d && d.price), label: d && d.dayLabel }))
    .filter(d => Number.isFinite(d.price));
  // Need at least two real points to draw a line.
  if (series.length < 2) return null;
  const prices = series.map(d => d.price);
  const min = Math.min(...prices), max = Math.max(...prices);
  const range = max - min || 1;
  const W = 600, H = height || 140;
  // When axis labels are enabled (item-page variant only — see `showAxes`
  // below) reserve a strip at the bottom of the viewBox so the date ticks
  // sit *below* the plotted line instead of overlapping it / getting
  // clipped at the SVG edge. Small inline sparklines (cards, bid chart,
  // notification feed) pass no `showAxes`, so they keep the full band and
  // gain zero axis chrome.
  const axisH = showAxes ? 16 : 0;
  const padTop = H * 0.09, bandH = H * 0.82 - axisH;

  const pts = series.map((d, i) => {
    const x = (i / (series.length - 1)) * W;
    const y = H - axisH - ((d.price - min) / range) * bandH - padTop;
    return { x, y, price: d.price, label: d.label };
  });
  const polyline = pts.map(p => `${p.x},${p.y}`).join(' ');
  const area = `0,${H} ${polyline} ${W},${H}`;
  // Hash-ish id that survives non-hex color inputs (e.g. var(--up))
  // so callers can pass design-token colors instead of raw hex.
  const gradId = 'grad-' + String(colorSafe).replace(/[^a-z0-9]/gi, '');
  const minIdx = prices.indexOf(min);
  const maxIdx = prices.indexOf(max);

  // ── Static axis labels (CSFloat parity) ────────────────────────────
  // Opt-in via `showAxes` so only the large item-page price chart gets
  // them; the shared inline sparklines (cards, bid-progression chart,
  // notification feed) stay clean. The caller that owns the item-page
  // chart (modals.js) must pass `showAxes: true` — tracked as a follow-up
  // since that file is outside this component's ownership.
  //
  // X ticks: first / middle / last date, anchored start/middle/end so the
  // outer two never clip at the SVG edges. Colour + font come from the
  // existing `svg.chart text` parity CSS (fill rgb(158,167,177), Roboto);
  // we also set them inline so any wrapper the CSS selector doesn't cover
  // still renders muted, not default black.
  const AXIS_FILL = 'rgb(158,167,177)';
  const dateLabel = (p) => (p && p.label) ? p.label : '';
  const xTicks = showAxes ? [
    { x: 0,     anchor: 'start',  text: dateLabel(pts[0]) },
    { x: W / 2, anchor: 'middle', text: dateLabel(pts[Math.floor((pts.length - 1) / 2)]) },
    { x: W,     anchor: 'end',    text: dateLabel(pts[pts.length - 1]) }
  ].filter(t => t.text) : [];

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

  // Resolve the hovered point safely. The `data` prop can shrink while the
  // pointer is still over the chart — the price-history modal swaps the
  // series when the user clicks a shorter range (7D/1M/…). The stale
  // `hover` index then points past the end of the rebuilt `pts` array, and
  // `pts[hover].x` threw "Cannot read properties of undefined". Clamp the
  // index to the current series so a mid-hover dataset swap can't crash.
  const hoverIdx = hover !== null && hover >= 0 && hover < pts.length ? hover : null;
  const hoverPt  = hoverIdx !== null ? pts[hoverIdx] : null;

  // Batch 828 — a11y label on the SVG so a screen reader announces
  // the chart as "Price history chart, $min to $max, N points" rather
  // than skipping it entirely (svgs default to "image" with no label).
  // Hover interactions stay mouse-only — low value for a keyboard-
  // driven reader and adds significant tab-stop churn.
  const firstPrice = prices[0];
  const lastPrice  = prices[prices.length - 1];
  const deltaPct   = firstPrice > 0 ? Math.round(((lastPrice - firstPrice) / firstPrice) * 100) : 0;
  const chartDesc  = `Price history chart, ${prices.length} points. ` +
    `Range ${fmt(min)} to ${fmt(max)}. ` +
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
      // 2026-05-20: stroke was hardcoded `rgba(255,255,255,0.04)` — invisible
      // on a light theme (white-on-white). Use the design-token border colour
      // at low opacity so the gridlines render correctly in every theme,
      // consistent with the var(--bg)/var(--up)/var(--down) tokens this
      // component already uses for every other stroke/fill.
      [0.25, 0.5, 0.75].map(f => h('line', {
        key: f,
        x1: 0, x2: W, y1: padTop + bandH * f, y2: padTop + bandH * f,
        stroke: 'var(--line, var(--border))', strokeOpacity: 0.5, strokeWidth: 1
      })),
      h('polygon',  { points: area,     fill: `url(#${gradId})` }),
      // CSFloat parity: a thin (~1.6px) line, not a heavy 2.2px stroke.
      h('polyline', { points: polyline, fill: 'none', stroke: colorSafe, strokeWidth: '1.6',
                      strokeLinejoin: 'round', strokeLinecap: 'round' }),
      // Min / max dots so the viewer can spot the extremes at a glance
      // (r3 to sit proportionate to the slimmer line, CSFloat-style).
      h('circle', { cx: pts[minIdx].x, cy: pts[minIdx].y, r: 3, fill: 'var(--down)', stroke: 'var(--bg)',  strokeWidth: 2 }),
      h('circle', { cx: pts[maxIdx].x, cy: pts[maxIdx].y, r: 3, fill: 'var(--up)',   stroke: 'var(--bg)',  strokeWidth: 2 }),
      // X-axis date ticks (item-page variant only) — sit in the reserved
      // `axisH` strip at the bottom so they never overlap the plotted line.
      xTicks.map((t, i) => h('text', {
        key: 'xt' + i,
        className: 'axis-tick',
        x: t.x, y: H - 3,
        'text-anchor': t.anchor,
        fill: AXIS_FILL, fontSize: 10
      }, t.text)),
      // Y-axis price extremes (item-page variant only) — max near the top
      // band edge, min near the band floor, hugging the left gutter. Kept
      // out of the small sparklines by the same `showAxes` gate.
      showAxes && h('text', {
        className: 'axis-tick',
        x: 4, y: padTop + 9,
        'text-anchor': 'start',
        fill: AXIS_FILL, fontSize: 10
      }, fmt(max)),
      showAxes && h('text', {
        className: 'axis-tick',
        x: 4, y: padTop + bandH - 2,
        'text-anchor': 'start',
        fill: AXIS_FILL, fontSize: 10
      }, fmt(min)),
      // Hover crosshair + point
      hoverPt && h('line', {
        x1: hoverPt.x, x2: hoverPt.x, y1: 0, y2: H,
        stroke: colorSafe, strokeOpacity: 0.25, strokeWidth: 1, strokeDasharray: '3 3'
      }),
      hoverPt && h('circle', {
        cx: hoverPt.x, cy: hoverPt.y, r: 5,
        fill: colorSafe, stroke: 'var(--bg)', strokeWidth: 2
      })
    ),
    hoverPt && h('div', {
      className: 'sparkline-tooltip',
      style: { left: `${(hoverPt.x / W) * 100}%` }
    },
      h('div', { className: 'sparkline-tt-price' }, fmt(hoverPt.price)),
      h('div', { className: 'sparkline-tt-date' }, hoverPt.label || '')
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
  const panelRef          = useRef(null);
  // Per-instance id for the dialog's title element. A stall / profile page
  // renders one ReasonDrawer per review row (admin-remove + report flows),
  // so a hardcoded id collided across every open drawer — `aria-labelledby`
  // then resolved ambiguously and a screen reader announced the wrong
  // drawer's heading. A ref-stored unique id keeps each dialog correctly
  // labelled.
  const titleIdRef = useRef(null);
  if (titleIdRef.current === null) {
    titleIdRef.current = 'reason-drawer-title-' +
      Math.random().toString(36).slice(2, 9);
  }
  const titleId = titleIdRef.current;
  // Batch 1167 — a11y upgrade. Previously the drawer:
  //   • Focused the textarea on mount (good)
  //   • Closed on Escape (good)
  //   • Had NO focus trap, so Tab walked straight out into the page
  //     behind, stranding a keyboard user.
  //   • Did NOT restore focus to the triggering button on close, so a
  //     screen-reader user was dropped on document.body.
  // The single effect below adds both — capture activeElement on mount,
  // restore it on unmount, and intercept Tab to keep focus cycling
  // inside the panel. Pattern matches InfoModal's focus contract.
  //
  // Refs hold the latest onCancel / busy so the effect's deps array
  // can stay empty — without this the trap would tear down + rebuild on
  // every parent re-render (parents pass `() => setX(null)` inline), and
  // each rebuild prematurely restores focus to the trigger, breaking the
  // textarea autofocus the user is mid-typing into.
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const busyRef = useRef(busy);
  busyRef.current = busy;
  useEffect(() => {
    const prev = document.activeElement;
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
      if (e.key === 'Escape' && !busyRef.current) { e.stopPropagation(); onCancelRef.current && onCancelRef.current(); return; }
      if (e.key !== 'Tab' || !panelRef.current) return;
      // Filter to focusables actually rendered (visible, not display:none).
      // A ReasonDrawer body holds an enabled/disabled state on the Submit
      // button depending on draft length — disabled buttons are skipped
      // by the trap naturally via the :not([disabled]) selector.
      const focusables = Array.prototype.filter.call(
        panelRef.current.querySelectorAll(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
        ),
        el => {
          if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') return false;
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0;
        }
      );
      if (!focusables.length) return;
      const first = focusables[0];
      const last  = focusables[focusables.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && (active === first || !panelRef.current.contains(active))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (active === last || !panelRef.current.contains(active))) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      cancelAnimationFrame(id);
      document.removeEventListener('keydown', onKey);
      try {
        if (prev && typeof prev.focus === 'function' && document.contains(prev)) {
          prev.focus({ preventScroll: true });
        }
      } catch (_) {}
    };
  }, []);
  // Synchronous re-entrancy latch shared by every ReasonDrawer consumer (admin
  // force-release / force-cancel / withdrawal-reject / dispute-action / ban,
  // plus review replies / moderation notes). `busy` is async state, so a rapid
  // double-click — or Ctrl+Enter then Enter-on-button — could fire two POSTs
  // before it re-renders; for the money actions that risks a double payout/
  // refund. This ref latches synchronously and resets when the consumer's
  // onSubmit promise settles (matches submittingRef/busyRef on the customer
  // money path). Sync consumers reset on the next microtask — a harmless no-op.
  const submittingRef = useRef(false);
  const guardedSubmit = (val) => {
    if (submittingRef.current) return;
    submittingRef.current = true;
    Promise.resolve(onSubmit(val)).finally(() => { submittingRef.current = false; });
  };
  const trimmed  = (text || '').trim();
  const canSubmit = trimmed.length > 0 && trimmed.length <= maxLen && !busy;
  return h('div', {
    ref: panelRef,
    // ReasonDrawer renders inline within its host row (review reply,
    // moderation note) rather than as a top-level overlay, so the
    // dialog has its own focus trap but does NOT claim aria-modal —
    // declaring aria-modal=true on a non-overlay element makes assistive
    // tech treat the rest of the page as inert, which would be a lie
    // here. role=dialog + aria-labelledby still give the surface a
    // proper announced identity.
    role: 'dialog',
    'aria-modal': 'false',
    'aria-labelledby': titleId,
    style: {
      width: '100%', padding: 12,
      background: 'var(--bg-elevated, #1a1c20)',
      border: '1px solid var(--border)', borderRadius: 6
    }
  },
    h('div', {
      id: titleId,
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
          e.preventDefault(); guardedSubmit(trimmed);
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
          onClick: () => guardedSubmit(trimmed)
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

// ── Price freshness chip ─────────────────────────────────────────
// Surfaces "Prices updated Xs ago" so a buyer can tell at a glance
// the floor numbers haven't drifted from reality. Reads
// /api/items/price-refresh-status which reports the more-recent of:
//   • ListingFloorRefreshService (every 60s — covers cancels, sales,
//     new listings — the dominant signal)
//   • SteamMarketPriceService    (every 30 min — Steam Market floor
//     for unlisted items, rate-limited)
// Polls every 30s — same cadence as the marketplace grid's silent
// refetch so the chip and the prices stay in lockstep. A separate
// 15s in-place tick advances `timeAgo()` between server polls so
// the chip doesn't freeze at "Just now". Visibility-gated so a
// backgrounded tab doesn't tick.
//
// Variants:
//   • default → full-card chip (used above the marketplace grid)
//   • compact: true → slim inline variant (used in the Sell modal
//     where vertical space is tight)
export function PriceFreshnessChip({ compact = false }) {
  const [status, setStatus] = useState(null);
  const [, forceTick] = useState(0);
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const r = await fetch('/api/items/price-refresh-status', { credentials: 'same-origin' });
        if (!r.ok) return;
        const d = await r.json();
        if (alive) setStatus(d);
      } catch (_) { /* silent — chip just hides */ }
    };
    load();
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') load();
    }, 30_000);
    const tickId = setInterval(() => forceTick(n => n + 1), 15_000);
    const onVis = () => { if (document.visibilityState === 'visible') load(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false;
      clearInterval(id);
      clearInterval(tickId);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  if (!status || !(status.lastUpdatedAt > 0)) return null;
  const ageMs = Date.now() - status.lastUpdatedAt;
  // Stale-after threshold — if the last sweep was more than 5 min
  // ago, paint amber so the user (and ops) notice the scheduler is
  // wedged. Healthy: green. Sweep cadence is 60s so anything past
  // 300s is genuinely off the rails.
  const stale = ageMs > 5 * 60_000;
  const dotColor   = stale ? 'var(--amber, #d4a015)' : 'var(--green, #28a745)';
  const text       = 'Prices updated ' + timeAgo(status.lastUpdatedAt);
  const titleAttr  = 'Last sweep: ' + new Date(status.lastUpdatedAt).toLocaleString()
                   + '\nFloor sweep every 60s · Steam Market every 30 min';
  if (compact) {
    return h('span', {
      style: {
        display: 'inline-flex', alignItems: 'center', gap: 6,
        fontSize: 11, color: 'var(--text-muted)',
        fontWeight: 600, letterSpacing: '0.02em'
      },
      title: titleAttr
    },
      h('span', {
        style: {
          width: 7, height: 7, borderRadius: '50%',
          background: dotColor,
          boxShadow: '0 0 6px ' + dotColor,
          animation: stale ? 'none' : 'pulse 2.4s ease-in-out infinite',
          flexShrink: 0
        },
        'aria-hidden': 'true'
      }),
      text
    );
  }
  return h('div', {
    className: 'price-freshness-chip',
    style: {
      display: 'inline-flex', alignItems: 'center', gap: 8,
      padding: '6px 12px',
      borderRadius: 999,
      background: 'var(--bg-card)',
      border: '1px solid var(--border)',
      fontSize: 12, color: 'var(--text-secondary)',
      fontWeight: 600, letterSpacing: '0.02em'
    },
    title: titleAttr,
    role: 'status',
    'aria-live': 'polite'
  },
    h('span', {
      style: {
        width: 8, height: 8, borderRadius: '50%',
        background: dotColor,
        boxShadow: '0 0 8px ' + dotColor,
        animation: stale ? 'none' : 'pulse 2.4s ease-in-out infinite',
        flexShrink: 0
      },
      'aria-hidden': 'true'
    }),
    text
  );
}

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

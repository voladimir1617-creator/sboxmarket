// Trade Protection — buyer-facing opt-in panel + protected badge.
//
// Trade Protection is an optional paid add-on a buyer can enable while a
// trade is still in escrow. If the trade later fails through no fault of
// the buyer's, they're auto-refunded the FULL item price — no support
// ticket. A trade that completes normally simply keeps the small fee.
//
// This module exports one component, `TradeProtectionPanel`, which the
// trade list in modals.js renders inside each trade row. The component
// owns its own data fetching (quote + current protection state) so the
// caller only has to hand it the trade object + viewer identity.
//
// Backend contract (already live):
//   GET  /api/trade-protection/quote?price=<decimal>
//          → { price, fee, ratePercent, minFee, coverageAmount }
//   GET  /api/trades/{id}/protection
//          → { tradeId, protected, protection }
//   POST /api/trades/{id}/protection
//          → TradeProtection | { error, code }
import { h, useState, useEffect, fmt, toast } from './utils.js';
import { MaterialIcon } from './primitives.js';
import { fetchTradeProtectionQuote, fetchTradeProtection, enableTradeProtection } from './api.js';

// Escrow states in which a buyer can still opt into protection. Mirrors
// the `openStates` list the trades tab uses for its "open" filter — a
// trade that has already verified / cancelled / disputed is past the
// point where cover can be added.
const PROTECTABLE_STATES = ['PENDING_SELLER_ACCEPT', 'PENDING_SELLER_SEND', 'PENDING_BUYER_CONFIRM'];

// Fee = 2% of the trade price, floored at $0.25. Used as a client-side
// fallback when the public quote endpoint is unreachable so the opt-in
// button can still render a concrete price rather than a spinner.
const RATE_PERCENT = 2;
const MIN_FEE = 0.25;
function computeFee(price) {
  const p = parseFloat(price) || 0;
  // 2026-05-20: round the 2% cut to cents half-up before applying the
  // $0.25 floor, mirroring the backend's quote() (setScale(2,
  // ROUND_HALF_UP)). Without the round, a price like $33.33 left an
  // unrounded 0.6666 in state — fmt() masked it on screen, but the
  // fallback no longer matches the authoritative quote bit-for-bit.
  const pct = Math.round(p * RATE_PERCENT) / 100;
  return Math.max(MIN_FEE, pct);
}

// Map a server error `code` onto friendly inline copy. Anything not
// listed falls through to the server's own `message`/`error` string so
// we never swallow a useful backend message.
function friendlyError(res) {
  switch (res && res.code) {
    case 'INSUFFICIENT_BALANCE':
      return 'Not enough wallet balance — top up to protect this trade.';
    case 'NO_WALLET':
      return 'Add a wallet before enabling Trade Protection.';
    case 'WALLET_FROZEN':
      return 'Your wallet is frozen — protection can’t be charged right now.';
    case 'PROTECTION_EXISTS':
      return 'This trade is already protected.';
    case 'TRADE_NOT_PROTECTABLE':
      return 'This trade can no longer be protected.';
    default:
      return (res && (res.message || res.error)) || 'Couldn’t enable protection — please try again.';
  }
}

// Per-status presentation for an already-protected trade. ACTIVE = cover
// is live; CLAIMED = the buyer was paid out; EXPIRED = the trade settled
// fine and cover lapsed unused.
function statusLine(status, coverageAmount) {
  const cover = fmt(coverageAmount != null ? coverageAmount : 0);
  switch (status) {
    case 'ACTIVE':
      return { text: `Covered for ${cover}`, color: 'var(--green)' };
    case 'CLAIMED':
      return { text: 'Protection paid out', color: 'var(--accent)' };
    case 'EXPIRED':
      return { text: 'Cover completed', color: 'var(--text-muted)' };
    default:
      return { text: `Covered for ${cover}`, color: 'var(--green)' };
  }
}

// ── Protected badge ────────────────────────────────────────────
// Shown to BOTH buyer and seller once a trade carries protection.
// Read-only — a tasteful shield row, no actions.
function ProtectedBadge({ protection }) {
  const status = (protection && protection.status) || 'ACTIVE';
  const line = statusLine(status, protection && protection.coverageAmount);
  return h('div', {
    className: 'trade-protection-badge',
    style: {
      gridColumn: '1 / -1',
      marginTop: 8,
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '8px 12px',
      borderRadius: 6,
      background: 'rgba(34,197,94,0.08)',
      border: '1px solid rgba(34,197,94,0.28)'
    }
  },
    h('span', {
      'aria-hidden': true,
      style: {
        width: 30, height: 30, flex: '0 0 30px',
        borderRadius: 6, background: 'rgba(34,197,94,0.14)',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center'
      }
    }, h(MaterialIcon, { name: 'verified_user', size: 18, fill: true, color: 'var(--green)' })),
    h('div', { style: { display: 'flex', flexDirection: 'column', minWidth: 0 } },
      h('div', {
        style: { fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }
      }, 'Trade Protected'),
      h('div', {
        style: { fontSize: 11, color: line.color, fontWeight: 600 }
      }, line.text)
    )
  );
}

// ── Opt-in panel ───────────────────────────────────────────────
// Shown only to the BUYER while the trade is unprotected and still in
// escrow. Explains the add-on, shows the fee, and offers a one-click
// enable button.
function OptInPanel({ trade, fee, loadingFee, onEnable, busy, error }) {
  const coverage = fmt(parseFloat(trade.price) || 0);
  return h('div', {
    className: 'trade-protection-panel',
    style: {
      gridColumn: '1 / -1',
      marginTop: 8,
      padding: '10px 12px',
      borderRadius: 6,
      background: 'var(--bg-elevated)',
      border: '1px solid var(--accent-border)'
    }
  },
    h('div', { style: { display: 'flex', alignItems: 'flex-start', gap: 10 } },
      h('span', {
        'aria-hidden': true,
        style: {
          width: 30, height: 30, flex: '0 0 30px',
          borderRadius: 6, background: 'rgba(30,165,255,0.1)',
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center'
        }
      }, h(MaterialIcon, { name: 'shield', size: 18, color: 'var(--accent)' })),
      h('div', { style: { flex: 1, minWidth: 0 } },
        h('div', {
          style: { fontSize: 12, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }
        }, 'Add Trade Protection'),
        h('div', {
          style: { fontSize: 11, color: 'var(--text-secondary)', lineHeight: 1.5 }
        }, `If this trade fails through no fault of yours, get ${coverage} refunded automatically — no support ticket.`)
      )
    ),
    // Inline error — friendly mapped copy from a failed enable POST.
    error && h('div', {
      style: {
        marginTop: 8,
        padding: '7px 10px',
        borderRadius: 5,
        fontSize: 11,
        background: 'var(--red-dim)',
        border: '1px solid rgba(248,113,113,0.3)',
        color: 'var(--red)'
      }
    }, error),
    h('div', {
      style: {
        marginTop: 8,
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        flexWrap: 'wrap'
      }
    },
      h('button', {
        className: 'buy-btn',
        disabled: busy || loadingFee,
        onClick: onEnable,
        title: loadingFee
          ? 'Fetching the protection fee…'
          : `Charge ${fmt(fee)} from your wallet to protect this trade`
      }, busy
        ? 'Enabling…'
        : loadingFee
          ? 'Enable Protection'
          : `Enable Protection · ${fmt(fee)}`),
      h('span', {
        style: { fontSize: 10, color: 'var(--text-muted)' }
      }, `${RATE_PERCENT}% of trade price · one-time fee`)
    )
  );
}

/**
 * TradeProtectionPanel — drop-in row for the trade detail / trade list
 * view. Decides on its own which of three states to render:
 *
 *   1. protected   → ProtectedBadge (buyer + seller)
 *   2. buyer can opt in (unprotected, still in escrow) → OptInPanel
 *   3. anything else → nothing
 *
 * Props:
 *   trade      — the trade object from /api/trades. May already embed a
 *                `protected` boolean and/or `protection` object; if so we
 *                trust it and skip the GET.
 *   me         — the signed-in user ({ id, ... }).
 *   onChanged  — optional callback fired after protection is enabled, so
 *                the parent can refresh its trade list.
 */
export function TradeProtectionPanel({ trade, me, onChanged }) {
  if (!trade || !me) return null;

  const isBuyer = trade.buyerUserId === me.id;
  const isSeller = trade.sellerUserId === me.id;
  // Only participants ever see this panel.
  if (!isBuyer && !isSeller) return null;

  // Seed from any protection data the trade object already carries so we
  // can render instantly without a round-trip. `protected` may be a bare
  // boolean; `protection` the nested record. Either may be absent.
  const embeddedProtection = trade.protection || null;
  const embeddedFlag = typeof trade.protected === 'boolean'
    ? trade.protected
    : (embeddedProtection ? true : null);

  // `protection` holds the resolved record (or null = confirmed
  // unprotected). `undefined` = not yet known, still loading.
  const [protection, setProtection] = useState(
    embeddedProtection ? embeddedProtection : (embeddedFlag === false ? null : undefined)
  );
  const [fee, setFee] = useState(() => computeFee(trade.price));
  const [loadingFee, setLoadingFee] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // Resolve protection state — only when the trade object didn't already
  // tell us. Skips the call entirely when the embedded fields answered.
  useEffect(() => {
    let cancelled = false;
    if (protection !== undefined) return;  // already known
    (async () => {
      const res = await fetchTradeProtection(trade.id);
      if (cancelled) return;
      // res shape: { tradeId, protected, protection }. A null res means
      // the read failed (401/403/404/network) — treat as unprotected so
      // a buyer in escrow still gets the opt-in panel rather than a
      // permanent spinner.
      setProtection(res && res.protection ? res.protection : null);
    })();
    return () => { cancelled = true; };
  }, [trade.id]);

  // Fetch the authoritative fee for the opt-in panel. Only the buyer of
  // an unprotected, still-escrowed trade ever sees the button, so the
  // quote call is scoped to exactly that case. Falls back to the
  // client-side 2%/$0.25 compute if the public endpoint is unreachable.
  const showOptIn = isBuyer
    && protection === null
    && PROTECTABLE_STATES.includes(trade.state);
  useEffect(() => {
    if (!showOptIn) return;
    let cancelled = false;
    setLoadingFee(true);
    (async () => {
      const q = await fetchTradeProtectionQuote(trade.price);
      if (cancelled) return;
      if (q && q.fee != null) setFee(parseFloat(q.fee));
      else setFee(computeFee(trade.price));
      setLoadingFee(false);
    })();
    return () => { cancelled = true; };
  }, [showOptIn, trade.price]);

  const onEnable = async () => {
    setBusy(true);
    setError('');
    try {
      const res = await enableTradeProtection(trade.id);
      if (res && (res.error || res.code)) {
        const msg = friendlyError(res);
        setError(msg);
        toast(msg, 'err');
        return;
      }
      // Success — `res` is the created TradeProtection. Flip straight to
      // the protected badge without waiting on a refetch.
      setProtection(res);
      toast('Trade Protection enabled — you’re covered.', 'ok');
      if (typeof onChanged === 'function') {
        try { onChanged(); } catch (_) {}
      }
    } finally {
      setBusy(false);
    }
  };

  // Still resolving — render nothing rather than a spinner so the trade
  // row doesn't visibly jump.
  if (protection === undefined) return null;

  // Protected → badge for buyer AND seller.
  if (protection) {
    return h(ProtectedBadge, { protection });
  }

  // Unprotected + buyer + in escrow → opt-in panel.
  if (showOptIn) {
    return h(OptInPanel, { trade, fee, loadingFee, onEnable, busy, error });
  }

  // Buyer on a terminal unprotected trade — a subtle, unobtrusive note.
  if (isBuyer && !PROTECTABLE_STATES.includes(trade.state)) {
    return h('div', {
      className: 'trade-protection-none',
      style: {
        gridColumn: '1 / -1',
        marginTop: 6,
        fontSize: 11,
        color: 'var(--text-muted)',
        display: 'flex',
        alignItems: 'center',
        gap: 6
      }
    },
      h(MaterialIcon, { name: 'shield', size: 14, color: 'var(--text-muted)' }),
      h('span', null, 'This trade was not protected.')
    );
  }

  // Seller on an unprotected trade — nothing to show.
  return null;
}

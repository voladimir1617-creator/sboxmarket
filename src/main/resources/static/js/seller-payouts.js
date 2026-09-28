// Seller payouts card — top of My Stall.
//
// Answers the four questions a seller has about their money: how much can I
// cash out now, how much is still on its way from sales, what have I already
// been paid, and when does the next payout land. Figures come straight from
// GET /api/wallet/payouts; the card does no money arithmetic of its own.
//
// "Request payout" opens the existing Wallet → Withdraw form rather than
// duplicating it, so 2FA, the daily cap, fee preview, dispute holds and the
// Stripe cash-out setup all stay in one place.
import { h, useState, useEffect, fmt } from './utils.js';
import { fetchSellerPayouts } from './api.js';
import { navigate } from './router.js';

const STATUS_LABEL = {
  PENDING:   'On its way',
  COMPLETED: 'Paid',
  CANCELLED: 'Cancelled',
  REJECTED:  'Rejected',
  FAILED:    'Failed',
  REVERSED:  'Returned'
};

const num = (v) => parseFloat(v || 0) || 0;
const day = (ms) => new Date(ms).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
const plural = (c, one, many) => c + ' ' + (c === 1 ? one : many);

// Next payout, in one sentence. Order: money already moving, then money the
// seller can request now, then the next sale that releases into the balance.
export function nextPayoutLine(p, now = Date.now()) {
  const moving = (p.history || []).find(t => t.status === 'PENDING' && t.expectedAt);
  if (num(p.inFlight && p.inFlight.amount) > 0 && moving) {
    return { date: moving.expectedAt, text: 'Your requested payout should reach your bank by' };
  }
  if (num(p.available) > 0) {
    return { date: p.arrivalIfRequestedNow, text: 'Request now and it should reach your bank by' };
  }
  if (p.nextReleaseAt) {
    return { date: Math.max(p.nextReleaseAt, now), text: 'Your next sale releases to your balance by' };
  }
  return { date: null, text: 'No payout scheduled. Sales release to your balance once the buyer confirms.' };
}

export function SellerPayoutsCard({ refreshKey, mask }) {
  const [p, setP] = useState(undefined);        // undefined = loading, null = failed
  const [reload, setReload] = useState(0);
  const [showAll, setShowAll] = useState(false);
  useEffect(() => {
    let alive = true;
    fetchSellerPayouts().then(r => { if (alive) setP(r); });
    return () => { alive = false; };
  }, [refreshKey, reload]);
  const money = mask || fmt;

  if (p === undefined) return null;
  if (p === null) {
    return h('section', { className: 'seller-payouts', 'data-testid': 'seller-payouts', style: { margin: '0 0 14px', fontSize: 12, color: 'var(--ink-3)' } },
      'Could not load your payouts. ',
      h('button', { type: 'button', className: 'btn btn-ghost', style: { padding: '2px 8px', fontSize: 12 }, onClick: () => setReload(n => n + 1) }, 'Retry'));
  }

  const available = num(p.available);
  const pending = p.pending || {};
  const onHold = p.onHold || {};
  const inFlight = p.inFlight || {};
  const paidOut = p.paidOut || {};
  const next = nextPayoutLine(p);
  const history = p.history || [];
  const shown = showAll ? history : history.slice(0, 3);

  const action = p.frozen
    ? { label: 'Wallet frozen', disabled: true, tip: 'Staff froze this wallet. Open a support ticket to resolve it.' }
    : available <= 0
      ? { label: 'Request payout', disabled: true, tip: 'Nothing to cash out yet. Sale money lands here once the buyer confirms.' }
      : !p.cashoutReady
        ? { label: 'Set up cash-out', disabled: false, tip: 'Link your bank or debit card through Stripe once, then request payouts.' }
        : { label: 'Request payout', disabled: false, tip: 'Opens the withdraw form with your balance.' };

  const tiles = [
    { key: 'available', label: 'Available', amt: available, dir: 'in',
      sub: 'Ready to cash out' },
    { key: 'pending', label: 'Pending', amt: num(pending.amount), dir: '',
      sub: (pending.count > 0 ? plural(pending.count, 'sale', 'sales') + ' in escrow' : 'No sales in escrow')
        + (num(onHold.amount) > 0 ? ' · ' + money(onHold.amount) + ' on hold' : '') },
    { key: 'paid', label: 'Paid out', amt: num(paidOut.amount), dir: '',
      sub: (paidOut.count > 0 ? plural(paidOut.count, 'payout', 'payouts') : 'No payouts yet')
        + (num(inFlight.amount) > 0 ? ' · ' + money(inFlight.amount) + ' on its way' : '') }
  ];

  return h('section', {
    className: 'seller-payouts wallet-activity',
    'data-testid': 'seller-payouts',
    'aria-label': 'Payouts',
    style: { margin: '0 0 14px', textAlign: 'left' }
  },
    h('div', { className: 'wallet-activity-head', style: { flexWrap: 'wrap' } },
      h('span', { className: 'wallet-activity-title' }, 'Payouts'),
      h('button', {
        type: 'button',
        // No `btn-accent` class: InfoModal restyles itself (and stretches the
        // button into a footer CTA) whenever it holds a button.btn-accent.
        className: 'seller-payouts-cta',
        style: {
          height: 32, padding: '0 14px', borderRadius: 8, border: 0,
          background: 'var(--cta)', color: 'var(--cta-ink, #fff)', font: '600 12px/1 var(--ui)',
          cursor: action.disabled ? 'not-allowed' : 'pointer', opacity: action.disabled ? 0.45 : 1
        },
        disabled: action.disabled,
        title: action.tip,
        'data-testid': 'seller-payouts-request',
        onClick: () => navigate('/wallet/withdraw')
      }, action.label)
    ),
    h('div', {
      className: 'wallet-activity-grid',
      style: { gridTemplateColumns: 'repeat(auto-fit, minmax(100px, 1fr))' }
    },
      tiles.map(t => h('div', {
        key: t.key,
        className: 'wallet-activity-tile ' + t.dir + (t.amt === 0 ? ' is-zero' : ''),
        'data-kind': t.key
      },
        h('div', { className: 'wallet-activity-label' }, t.label),
        h('div', { className: 'wallet-activity-amt' }, money(t.amt)),
        h('div', { className: 'wallet-activity-sub' }, t.sub)
      ))
    ),
    h('div', { className: 'wallet-activity-sub', 'data-testid': 'seller-payouts-next', style: { margin: '10px 2px 0', fontSize: 12 } },
      h('strong', { style: { color: 'var(--ink-2)' } }, 'Next payout: '),
      next.text,
      next.date ? h('span', null, ' ', h('strong', { style: { color: 'var(--ink)' } }, day(next.date)), '.') : null,
      !p.live && h('span', { style: { color: 'var(--ink-4)' } }, ' Test mode: payouts are recorded, no real money moves.')
    ),
    history.length > 0 && h('div', { style: { marginTop: 12 } },
      h('div', { className: 'wallet-activity-label', style: { margin: '0 2px 6px' } }, 'Payout history'),
      h('div', { role: 'list', style: { border: '1px solid var(--line)', borderRadius: 'var(--r-md)', overflow: 'hidden' } },
        shown.map((t, i) => {
          const arriving = t.status === 'COMPLETED' && t.expectedAt && t.expectedAt > Date.now();
          const tone = t.status === 'COMPLETED' ? 'var(--up)'
            : t.status === 'PENDING' ? 'var(--ink-2)' : 'var(--ink-4)';
          return h('div', {
            key: t.id,
            role: 'listitem',
            style: {
              display: 'flex', alignItems: 'center', gap: 12, padding: '9px 12px',
              background: 'var(--bg-1)', fontSize: 12,
              borderTop: i === 0 ? 'none' : '1px solid var(--line)'
            }
          },
            h('span', { style: { color: 'var(--ink-3)', minWidth: 92 } }, day(t.createdAt)),
            h('span', { style: { color: tone, fontWeight: 600, flex: 1, minWidth: 0 } },
              STATUS_LABEL[t.status] || t.status,
              (t.status === 'PENDING' || arriving) && t.expectedAt
                ? h('span', { style: { color: 'var(--ink-3)', fontWeight: 400 } }, ' · bank by ' + day(t.expectedAt))
                : null),
            h('span', { style: { fontFamily: 'var(--mono)', color: 'var(--ink)', whiteSpace: 'nowrap' } }, money(t.amount))
          );
        })
      ),
      history.length > 3 && h('button', {
        type: 'button',
        className: 'btn btn-ghost',
        style: { marginTop: 6, padding: '2px 6px', fontSize: 12 },
        onClick: () => setShowAll(v => !v)
      }, showAll ? 'Show fewer' : 'Show all ' + history.length)
    )
  );
}

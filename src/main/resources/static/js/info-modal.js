// Shared shell for every info-style modal. Lives in its own file so both
// modals.js and csfloat-modals.js can import it without circular deps.
//
// When the `inline` global class `full-page-mode` is present on the document
// (App sets it based on the current route), the info-modal renders as a
// full-width page region instead of an overlay. CSS handles the difference.
// This is how we make /profile, /wallet, /help, /admin, etc. look like real
// pages in CSFloat's shape without rewriting every modal body.
import { h, signInWithSteam } from './utils.js';

export function InfoModal({ title, onClose, children }) {
  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal info-modal', onClick: e => e.stopPropagation() },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close' }, '✕'),
      h('div', { className: 'info-modal-header' }, title),
      h('div', { className: 'info-modal-body' }, children)
    )
  );
}

// Shared "sign in required" empty state with a real "Sign in with Steam"
// button, used by every modal that hides its content from anonymous
// viewers (Profile, My Stall, Reviews, Trades, Offers, Buy Orders,
// Notifications). Lives here so modals.js AND csfloat-modals.js can
// import it without creating a circular dep.
export function SignInNeededEmptyState({ what }) {
  return h('div', { className: 'empty-inline' },
    h('div', { className: 'empty-icon' }, '🔒'),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Sign in required'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
      `Sign in with your Steam account to see ${what}.`),
    h('button', {
      className: 'btn btn-accent',
      onClick: () => { signInWithSteam(); }
    }, 'Sign in with Steam')
  );
}

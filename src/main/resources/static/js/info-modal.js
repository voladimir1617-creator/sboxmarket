// Shared shell for every info-style modal. Lives in its own file so both
// modals.js and csfloat-modals.js can import it without circular deps.
//
// When the `inline` global class `full-page-mode` is present on the document
// (App sets it based on the current route), the info-modal renders as a
// full-width page region instead of an overlay. CSS handles the difference.
// This is how we make /profile, /wallet, /help, /admin, etc. look like real
// pages in CSFloat's shape without rewriting every modal body.
import { h } from './utils.js';

export function InfoModal({ title, onClose, children }) {
  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', { className: 'modal info-modal', onClick: e => e.stopPropagation() },
      h('button', { className: 'modal-close', onClick: onClose, 'aria-label': 'Close' }, '✕'),
      h('div', { className: 'info-modal-header' }, title),
      h('div', { className: 'info-modal-body' }, children)
    )
  );
}

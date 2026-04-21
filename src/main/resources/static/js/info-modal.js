// Shared shell for every info-style modal. Lives in its own file so both
// modals.js and csfloat-modals.js can import it without circular deps.
//
// When the `inline` global class `full-page-mode` is present on the document
// (App sets it based on the current route), the info-modal renders as a
// full-width page region instead of an overlay. CSS handles the difference.
// This is how we make /profile, /wallet, /help, /admin, etc. look like real
// pages in CSFloat's shape without rewriting every modal body.
import { h, useEffect, useRef, signInWithSteam } from './utils.js';

export function InfoModal({ title, onClose, children }) {
  // Batch 821 — a11y: proper focus management + Escape-to-close.
  // Previous InfoModal shipped without `role=dialog` / `aria-modal=true`,
  // without a focus jump into the modal, and without Escape handling.
  // Keyboard-only users were stranded on the triggering button; screen
  // readers didn't announce the dialog. Fixes:
  //   1. role=dialog + aria-modal=true so assistive tech knows to
  //      trap user attention.
  //   2. aria-labelledby pointing at the header so SR reads the title.
  //   3. Focus moves to the close button on mount. Previous focus is
  //      remembered and restored on unmount so closing the modal
  //      returns the user to the button they pressed.
  //   4. Escape key calls onClose — the backdrop click still works for
  //      mouse users.
  //
  // None of this changes visual behaviour. Pure a11y lift.
  const panelRef = useRef(null);
  const closeRef = useRef(null);
  useEffect(() => {
    const prev = document.activeElement;
    // Defer one tick so React has committed the DOM.
    const id = requestAnimationFrame(() => {
      if (closeRef.current) {
        try { closeRef.current.focus({ preventScroll: true }); }
        catch { closeRef.current.focus(); }
      }
    });
    const onKey = (e) => {
      if (e.key === 'Escape' && typeof onClose === 'function') {
        e.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      cancelAnimationFrame(id);
      document.removeEventListener('keydown', onKey);
      // Restore focus to the trigger so keyboard / screen-reader users
      // resume where they were. Guard against a trigger that was
      // removed from the DOM mid-lifecycle.
      try {
        if (prev && typeof prev.focus === 'function' && document.contains(prev)) {
          prev.focus({ preventScroll: true });
        }
      } catch (_) {}
    };
  }, [onClose]);
  const headerId = 'info-modal-h-' + (title || '').replace(/[^a-z0-9]/gi, '-').toLowerCase();
  return h('div', { className: 'modal-backdrop', onClick: onClose },
    h('div', {
      ref: panelRef,
      className: 'modal info-modal',
      onClick: e => e.stopPropagation(),
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': headerId
    },
      h('button', {
        ref: closeRef,
        className: 'modal-close',
        onClick: onClose,
        'aria-label': 'Close'
      }, '✕'),
      h('div', { className: 'info-modal-header', id: headerId }, title),
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
    h('div', { className: 'empty-icon' }, '—'),
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

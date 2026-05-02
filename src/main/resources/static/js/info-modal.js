// Shared shell for every info-style modal. Lives in its own file so both
// modals.js and csfloat-modals.js can import it without circular deps.
//
// When the `inline` global class `full-page-mode` is present on the document
// (App sets it based on the current route), the info-modal renders as a
// full-width page region instead of an overlay. CSS handles the difference.
// This is how we make /profile, /wallet, /help, /admin, etc. look like real
// pages in CSFloat's shape without rewriting every modal body.
import { h, useEffect, useRef, signInWithSteam } from './utils.js';
import { MaterialIcon } from './primitives.js';

export function InfoModal({ title, onClose, children, wide }) {
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
  /* In full-page mode (.site-root.full-page-mode), the .modal-backdrop is
     `position: static` and fills normal page flow — clicking anywhere on
     the page outside the .modal would otherwise navigate back to /. Skip
     the onClose call when full-page-mode is active so dedicated routes
     (/wallet, /profile, /cart, /watchlist, etc.) read like real pages. */
  const handleBackdropClick = (e) => {
    if (document.querySelector('.site-root.full-page-mode')) return;
    onClose && onClose();
  };
  return h('div', { className: 'modal-backdrop', onClick: handleBackdropClick },
    h('div', {
      ref: panelRef,
      className: 'modal info-modal' + (wide ? ' wide' : ''),
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
      h('h1', { className: 'info-modal-header', id: headerId }, title),
      h('div', { className: 'info-modal-body' }, children)
    )
  );
}

// Shared "sign in required" empty state with a real "Sign in with Steam"
// button, used by every modal that hides its content from anonymous
// viewers (Profile, My Stall, Reviews, Trades, Offers, Buy Orders,
// Notifications). Lives here so modals.js AND csfloat-modals.js can
// import it without creating a circular dep.
//
// `mailto`, when provided, renders a secondary "or email …" link below
// the primary Steam button. Use it for surfaces (e.g. /support) where
// the destination is itself a way to reach the team and an anon user
// arriving via a footer/help link should still have a no-login fallback
// instead of hitting a brick wall.
//   { to: 'support@…', subject: '…', label: '…' }
export function SignInNeededEmptyState({ what, mailto }) {
  const mailHref = mailto && mailto.to
    ? `mailto:${mailto.to}` + (mailto.subject ? `?subject=${encodeURIComponent(mailto.subject)}` : '')
    : null;
  return h('div', { className: 'empty-inline' },
    h('div', {
      className: 'empty-icon',
      // G10 Boss QA — sign-in lock icon was 26px and read as a tiny
      // afterthought on /watchlist anon. Bumped to 64px in an accent-
      // tinted chip so the empty state has visual weight. Inline SVG
      // (not MaterialIcon) so the glyph is reliable even when the
      // Material Symbols font hasn't finished loading — e.g. during
      // headless screenshots where fonts.googleapis.com may time out.
      style: {
        width: 72, height: 72, borderRadius: 18,
        margin: '0 auto 14px',
        background: 'color-mix(in oklab, var(--accent) 8%, var(--bg-1))',
        border: '1px solid color-mix(in oklab, var(--accent) 18%, var(--line))',
        color: 'color-mix(in oklab, var(--accent) 85%, var(--ink-2))',
        display: 'grid', placeItems: 'center'
      }
    },
      h('svg', {
        width: 36, height: 36, viewBox: '0 0 24 24', fill: 'none',
        stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round',
        strokeLinejoin: 'round', 'aria-hidden': true
      },
        h('rect', { x: 3, y: 11, width: 18, height: 11, rx: 2 }),
        h('path', { d: 'M7 11V7a5 5 0 0 1 10 0v4' })
      )),
    h('div', { style: { fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 } },
      'Sign in required'),
    h('div', { style: { fontSize: 13, color: 'var(--text-secondary)', maxWidth: 360, margin: '0 auto 16px' } },
      `Sign in with your Steam account to see ${what}.`),
    h('button', {
      className: 'btn btn-accent',
      onClick: () => { signInWithSteam(); }
    }, 'Sign in with Steam'),
    mailHref && h('div', { style: { marginTop: 14, fontSize: 12, color: 'var(--text-secondary)' } },
      h('span', null, 'No Steam account? '),
      h('a', {
        href: mailHref,
        style: { color: 'var(--cta)', textDecoration: 'none', fontWeight: 600 }
      }, mailto.label || `Email ${mailto.to}`)
    )
  );
}

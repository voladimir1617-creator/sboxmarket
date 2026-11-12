// Shared by app.js and modals.js. They live here, not in modals.js, so that
// app.js can use them on the first screen while modals.js loads later.
import { useEffect, useRef } from './utils.js';

export function fmtDelay(ms) {
  const m = Number(ms);
  if (!Number.isFinite(m) || m <= 0) return null;
  const hours = m / 3_600_000;
  if (hours < 1) return Math.max(1, Math.round(m / 60_000)) + 'm';
  if (hours < 24) return Math.round(hours) + 'h';
  return Math.round(hours / 24) + 'd';
}

// Batch 1167 — full dialog a11y helper for the larger modal shells
// (ItemModal, WalletModal, ReviewModal, ConfirmTradeModal,
// BulkAdjustDrawer). Wraps:
//   • Escape-to-close
//   • Initial focus into the panel (first focusable or panel itself)
//   • Focus restoration to the triggering element on unmount
//   • Tab/Shift+Tab focus trap inside the panel
//
// Pure additive — does not alter visual behaviour. Pattern mirrors
// InfoModal in info-modal.js so the keyboard contract is consistent
// across every dialog surface in the app.
//
// `enabled` lets a caller suppress the hook (e.g. ItemModal in
// page mode where the dialog is actually a routed page).
export function useDialogA11y(panelRef, onClose, enabled = true) {
  useEffect(() => {
    if (!enabled) return;
    if (typeof onClose !== 'function') return;
    const prev = document.activeElement;
    // Defer one tick so the panel's children have mounted and a
    // focus() call lands on a real focusable instead of nothing.
    const rafId = requestAnimationFrame(() => {
      if (!panelRef.current) return;
      // Prefer the first natural focusable inside the panel — usually
      // the close button or a primary action. Fall back to the panel
      // itself (tabIndex=-1 so focus() lands; declared on the panel
      // element by the caller).
      // Skip hidden ones (a full-page route hides .modal-close, and focus()
      // on it no-ops, leaving focus on the opener behind the dialog) and
      // roving-tabindex ones a Tab would not land on.
      const target = Array.prototype.find.call(
        panelRef.current.querySelectorAll(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
        ),
        el => el.tabIndex >= 0 && el.getClientRects().length > 0
      ) || panelRef.current;
      try { target.focus({ preventScroll: true }); }
      catch { try { target.focus(); } catch (_) {} }
    });
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !panelRef.current) return;
      // Filter out hidden elements — calling .focus() on a display:none
      // node silently no-ops and breaks the trap.
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
      cancelAnimationFrame(rafId);
      document.removeEventListener('keydown', onKey);
      try {
        if (prev && typeof prev.focus === 'function' && document.contains(prev)) {
          prev.focus({ preventScroll: true });
        }
      } catch (_) {}
    };
  }, [onClose, enabled, panelRef]);
}

// For an inline panel (no dialog shell) that takes focus when it opens:
// remember what had focus just before it opened, and when it closes with
// focus dropped to <body> (the focused control unmounted with the panel),
// hand focus back to that opener. Escape out of the item page's offer
// drawer or price-alert editor used to leave a keyboard user on <body>.
export function useReturnFocus(open) {
  const openerRef = useRef(null);
  const wasOpen = useRef(open);
  // Read during render: the DOM still shows the pre-open focus here, before
  // the panel mounts and autoFocus moves it.
  if (open && !wasOpen.current) openerRef.current = document.activeElement;
  wasOpen.current = open;
  useEffect(() => {
    if (open) return;
    const opener = openerRef.current;
    openerRef.current = null;
    const a = document.activeElement;
    if (!opener || !document.contains(opener)) return;
    if (a && a !== document.body && document.contains(a)) return;
    try { opener.focus({ preventScroll: true }); } catch (_) {}
  }, [open]);
}

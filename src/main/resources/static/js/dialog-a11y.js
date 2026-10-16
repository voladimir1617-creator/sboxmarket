// Shared by app.js and modals.js. They live here, not in modals.js, so that
// app.js can use them on the first screen while modals.js loads later.
import { useEffect } from './utils.js';

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
      const focusables = panelRef.current.querySelectorAll(
        'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
      );
      const target = focusables[0] || panelRef.current;
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

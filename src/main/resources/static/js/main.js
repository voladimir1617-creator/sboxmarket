// Entry point. Mounts <App/> wrapped in the error boundary.
// Loaded as a module from index.html: <script type="module" src="/js/main.js"></script>
import { h, createRoot } from './utils.js';
// ?v= cache-buster bumped with each app.js change so browsers re-fetch the
// bundle instead of reusing a stale ES-module cache entry (a bare './app.js'
// specifier is cached indefinitely; the live /market kept rendering an old
// build — e.g. the removed market-stats band — until this query changed).
import { ErrorBoundary, App } from './app.js?v=165';

// Global safety nets — log to console AND forward to the server so ops
// can see production crashes that never trip React's ErrorBoundary
// (async errors, event-handler throws, third-party script failures).
// Debounced 2s to keep a runaway tight-loop error from flooding our log
// storage; frequency alone distinguishes a real bug from a hot loop.
// Identical to the ErrorBoundary reporter in /api/client-errors payload
// shape — the server treats them uniformly.
let _lastReportAt = 0;
function reportClientError(message, stack) {
  const now = Date.now();
  if (now - _lastReportAt < 2000) return;   // 2s debounce
  _lastReportAt = now;
  try {
    // /api/client-errors is CSRF-exempt (batch 688) — skip the
    // cookie parse + header so a crash that fires before the cookie
    // is parseable still lands a log line.
    fetch('/api/client-errors', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: String(message || '').slice(0, 500),
        stack:   String(stack || '').slice(0, 4000),
        url:     String(location.href || '').slice(0, 500),
        userAgent: String(navigator.userAgent || '').slice(0, 300)
      })
    }).catch(() => { /* swallow — reporting failures must not re-crash */ });
  } catch (_) { /* noop */ }
}
window.addEventListener('error', e => {
  console.error('[window error]', e.error || e.message);
  reportClientError(e.message || e.error?.message, e.error?.stack);
});
window.addEventListener('unhandledrejection', e => {
  console.error('[unhandled rejection]', e.reason);
  const r = e.reason;
  reportClientError(r?.message || String(r), r?.stack);
});

// Batch 927 — skip-link focus-move. The static <a class="skip-link"> in
// index.html uses href="#main"; browsers scroll that into view on click
// but don't move focus, so the NEXT Tab still fires from the skip-link
// rather than the content area. Listen for the click and explicitly
// focus the target so keyboard users land inside <main>.
document.addEventListener('click', (e) => {
  const a = e.target && e.target.closest && e.target.closest('a.skip-link');
  if (!a) return;
  const href = a.getAttribute('href') || '';
  if (!href.startsWith('#')) return;
  const id = href.slice(1);
  if (!id) return;
  const el = document.getElementById(id);
  if (!el) return;
  // tabindex=-1 lets otherwise non-focusable elements receive .focus().
  // Cleared on blur so the DOM doesn't accumulate stale attrs.
  el.setAttribute('tabindex', '-1');
  el.focus({ preventScroll: false });
  el.addEventListener('blur', () => el.removeAttribute('tabindex'), { once: true });
});

createRoot(document.getElementById('root')).render(h(ErrorBoundary, null, h(App)));

// Centralised fetch helpers. Every call the frontend makes lives here so
// request shaping, error handling and the API base path are in one spot.
import { API } from './utils.js';

// ── CSRF interceptor ──────────────────────────────────────────
// The server plants an `sbox_csrf` double-submit cookie on every response.
// For any non-GET request to /api/** we have to echo it back in the
// X-CSRF-Token header. Rather than thread this through every helper, we
// monkey-patch the global fetch once so every existing call picks it up.
(function installCsrfInterceptor() {
  if (typeof window === 'undefined' || window.__sboxFetchPatched) return;
  window.__sboxFetchPatched = true;
  const native = window.fetch.bind(window);
  const csrfToken = () => {
    try {
      const m = document.cookie.match(/(?:^|; )sbox_csrf=([^;]+)/);
      return m ? decodeURIComponent(m[1]) : '';
    } catch { return ''; }
  };
  window.fetch = function patchedFetch(input, init) {
    const req = init || {};
    const method = (req.method || (typeof input === 'object' && input?.method) || 'GET').toUpperCase();
    const url = typeof input === 'string' ? input : input?.url || '';
    const isApi = url.includes('/api/');
    const isWrite = method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS';
    if (isApi && isWrite) {
      req.credentials = req.credentials || 'same-origin';
      req.headers = Object.assign({}, req.headers || {}, { 'X-CSRF-Token': csrfToken() });
    }
    return native(input, req);
  };
})();

async function safeJson(url, opts, meta) {
  try {
    const r = await fetch(url, opts);
    if (!r.ok) {
      // Session-revoked broadcast (batch 604). Before this, a 401 on
      // a read operation (e.g. /api/profile/me after an admin force-
      // logout) silently returned null — the user saw empty states
      // without knowing their session died. Now reads dispatch the
      // same `sb:session-expired` event that write-ops already fire,
      // so the App flips `me` back to null + shows the friendly
      // toast. Debounced inside the app listener (10s) so a burst of
      // stale reads doesn't flash the toast repeatedly.
      if (r.status === 401) {
        try { window.dispatchEvent(new CustomEvent('sb:session-expired')); } catch (_) {}
      }
      // Maintenance / outage broadcast (batch 711). 503 on any read
      // means the pod is rejecting traffic — typically /api/ready's
      // DB probe is failing over or the admin flipped maintenance
      // mode. Flip the SPA into a "SkinBox is temporarily unavailable"
      // banner instead of flashing empty-state cards across the UI.
      // Debounced inside the App listener. 502/504 from the edge nginx
      // mean the upstream pod is crashing/timing out — same UX intent.
      if (r.status === 503 || r.status === 502 || r.status === 504) {
        try { window.dispatchEvent(new CustomEvent('sb:service-unavailable')); } catch (_) {}
      }
      // Suppress the warn for callers that have explicitly opted into
      // expected non-2xx (read-by-id endpoints where the SPA already
      // surfaces a branded "not found" empty state — stall/loadout/item
      // dead links). Without this opt-out, every dead-link landing fired
      // 3-4 console.warns that read as a bug to anyone tailing the tab.
      const muted = meta && Array.isArray(meta.expect) && meta.expect.indexOf(r.status) >= 0;
      if (!muted) console.warn(`[${url}] HTTP ${r.status}`);
      return null;
    }
    // Clear the maintenance banner as soon as a real response lands —
    // lets the UI auto-recover without a manual refresh when the pod
    // comes back.
    try { window.dispatchEvent(new CustomEvent('sb:service-restored')); } catch (_) {}
    return await r.json();
  } catch (e) {
    // Network-level failure (offline, DNS, TLS, timeout, CORS abort).
    // Pre-fix this swallowed silently — every dependent fetch returned
    // null, every consumer rendered "no listings / no items / no orders"
    // empty states, and the user had no signal that the network was
    // actually down. Now we fire the same service-unavailable event the
    // 503/502/504 branches use so the existing top-of-app banner says
    // "SkinBox is temporarily unavailable. Refreshed data will appear
    // once service is restored." The banner self-clears on the next 2xx
    // (sb:service-restored) so the UI auto-recovers without a refresh.
    try { window.dispatchEvent(new CustomEvent('sb:service-unavailable')); } catch (_) {}
    console.error(`[${url}] fetch failed:`, e);
    return null;
  }
}

/**
 * Safe wrapper for write operations (POST/PUT/DELETE). Unlike safeJson
 * (which returns null on error), write ops need to surface the server's
 * error message so the UI can show it. Returns the parsed JSON on success,
 * or { error: "...", code: "..." } on failure — never throws.
 */
async function writeJson(url, opts) {
  try {
    const r = await fetch(url, opts);
    let body;
    try { body = await r.json(); } catch { body = null; }
    if (!r.ok) {
      // Session-expired special case — 401 on a write operation means the
      // cookie went stale (server restart, explicit sign-out in another
      // tab, Steam session timeout). Surface a friendly code the UI can
      // map to a "Sign in again" toast + button rather than the generic
      // "Request failed (HTTP 401)" string which reads as a bug.
      if (r.status === 401) {
        // Broadcast so the App can flip `me` back to null (the nav avatar
        // becomes "Sign in with Steam" again) without waiting for a
        // page refresh. Listeners are optional — the returned error
        // object is still the primary failure signal.
        try { window.dispatchEvent(new CustomEvent('sb:session-expired')); } catch (_) {}
        return {
          error: body?.error || body?.message || 'Your session expired — sign in again to continue.',
          code:  'SESSION_EXPIRED'
        };
      }
      // 429 friendly message (batch 423). The server emits a Retry-After
      // header (seconds); surface it so the toast says "wait Ns" instead
      // of a generic "Too many requests" — saves the user from
      // reflexively re-clicking and burning their next bucket window.
      if (r.status === 429) {
        const retry = parseInt(r.headers.get('Retry-After'), 10);
        const wait  = Number.isFinite(retry) && retry > 0 ? retry : 0;
        const tail  = wait > 0 ? ` Try again in ${wait}s.` : ' Slow down a moment.';
        return {
          error: (body?.message || 'Too many requests.') + tail,
          code:  'RATE_LIMITED',
          retryAfter: wait
        };
      }
      // Batch 711 — 503 on write mirrors the 503-on-read broadcast.
      // Lets the service-unavailable banner fire regardless of which
      // op triggered the degraded state. 502/504 from the edge nginx
      // get the same banner — same root cause, same recovery.
      if (r.status === 503 || r.status === 502 || r.status === 504) {
        try { window.dispatchEvent(new CustomEvent('sb:service-unavailable')); } catch (_) {}
      }
      // Batch 989 — VALIDATION_FAILED responses carry `details.fields`
      // mapping field name → human error ("price must be at least $0.01").
      // The generic message "Request body failed validation" is useless
      // to a user — they don't know which field to fix. Pull the first
      // field error into the surfaced message so the toast reads
      // "Price must be at least $0.01" instead. Callers that want the
      // full per-field map still get it via `res.details.fields`.
      let msg = body?.error || body?.message || `Request failed (HTTP ${r.status})`;
      if (body?.code === 'VALIDATION_FAILED' && body?.details?.fields) {
        const entries = Object.values(body.details.fields);
        if (entries.length > 0) {
          const first = entries[0];
          if (typeof first === 'string' && first) {
            // Capitalise leading char so "price must be..." reads as a
            // real sentence. No deeper grammar munging — server-side
            // messages are already well-formed.
            msg = first.charAt(0).toUpperCase() + first.slice(1);
          }
        }
      }
      const code = body?.code || 'SERVER_ERROR';
      // Correlation-id suffix for 5xx responses (batch 738). The server
      // emits one per request via CorrelationIdFilter; returning it to
      // the UI lets the default toast read "Error · ref AB12CD" which
      // the user can paste directly into a support ticket. Only applied
      // for 500-class errors — 4xx user-actionable messages (validation,
      // permission, rate-limit) don't benefit from a trace id and the
      // suffix would just be noise.
      const cid = body?.correlationId || r.headers.get('X-Correlation-Id');
      const messageWithRef = (r.status >= 500 && cid)
        ? `${msg} · ref ${String(cid).slice(0, 8)}`
        : msg;
      // Batch 983 — forward `details` from the server. GlobalExceptionHandler
      // attaches structured `{required, available, shortfall}` on
      // INSUFFICIENT_BALANCE (batch 963) and field-error maps on
      // VALIDATION_FAILED. Pre-fix, writeJson dropped it on the floor
      // so the frontend's "Top up $X" precise-shortfall toast (batch
      // 963 handleBuy) never had data to render.  `message` is also
      // passed through so callers that read `res.message` (pre-refactor
      // mixed-shape code in app.js + modals.js) keep working.
      return { error: messageWithRef, code, correlationId: cid,
               message: body?.message, details: body?.details };
    }
    // Any 2xx clears the banner (service-restored), same as reads.
    try { window.dispatchEvent(new CustomEvent('sb:service-restored')); } catch (_) {}
    return body;
  } catch (e) {
    // Network-level failure on a write op (offline, DNS, TLS, timeout).
    // Mirror the read-side fix — fire the service-unavailable event so
    // the banner appears even when the action that surfaced the failure
    // was a POST/PUT/DELETE, not a GET. Caller still receives the
    // structured { error, code: 'NETWORK_ERROR' } so the toast renders.
    try { window.dispatchEvent(new CustomEvent('sb:service-unavailable')); } catch (_) {}
    console.error(`[${url}] write failed:`, e);
    return { error: 'Network error — please try again', code: 'NETWORK_ERROR' };
  }
}

// Prime the CSRF cookie on first module load by hitting a cheap GET. If
// a first-time visitor's first click is a POST we otherwise race the
// server's cookie-plant.
try { fetch('/api/wallet', { credentials: 'same-origin' }).catch(() => {}); } catch {}

// ── Listings ───────────────────────────────────────────────────
export async function fetchListings(params = {}) {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== null && v !== undefined && v !== '') q.append(k, v);
  });
  const data = await safeJson(`${API}/listings?${q}`);
  // ListingController returns a bare array when limit==100 && offset==0,
  // and a { items, total, limit, offset } wrapper otherwise. Unwrap so
  // every caller sees a plain array regardless of which branch fired.
  if (Array.isArray(data)) return data;
  if (data && Array.isArray(data.items)) return data.items;
  return [];
}

export async function fetchHistory(itemId) {
  // The endpoint always returns 200 with a (possibly empty) list for any
  // id, so there's no 404 to mute here.
  const data = await safeJson(`${API}/items/${itemId}/history`);
  return Array.isArray(data) ? data : [];
}

/** Public marketplace rollup — { volume24h, activeListings, floorPrice }.
 *  Drives the public trust-signal strip under the homepage hero. */
export async function fetchMarketStats() {
  return (await safeJson(`${API}/listings/stats`)) || null;
}

/** Single item lookup used by WatchlistModal to surface starred items
 *  even when there are no active listings in the marketplace. */
export async function fetchItem(itemId) {
  // The endpoint now returns 200 with `{notFound: true}` for missing ids
  // (so Chrome doesn't auto-log a fetch 404 to console). Translate the
  // sentinel back to null so every existing caller's truthy/null check
  // keeps working without changes.
  const data = await safeJson(`${API}/items/${itemId}`, undefined, { expect: [404] });
  if (data && data.notFound) return null;
  return data || null;
}

/** Bulk item lookup — one round-trip for a list of ids instead of one
 *  `GET /api/items/{id}` per id. Drives the recently-viewed rail/pills
 *  refetch-and-validate pass (kills the ~18-GET N+1 on navigation).
 *  The batch endpoint OMITS ids that no longer resolve (no
 *  `{notFound:true}` sentinel — that's the per-id contract), so the
 *  returned array can be shorter than `ids`. Callers diff against
 *  their input to detect dropped ids. Returns [] on any failure or
 *  empty input. Server caps the id count at 50. */
export async function fetchItemsByIds(ids) {
  const list = Array.isArray(ids)
    ? ids.map(x => x).filter(x => x != null && x !== '')
    : [];
  if (list.length === 0) return [];
  const csv = list.map(x => encodeURIComponent(x)).join(',');
  const data = await safeJson(`${API}/items/batch?ids=${csv}`);
  return Array.isArray(data) ? data : [];
}

export async function fetchSimilar(itemId) {
  const data = await safeJson(`${API}/items/${itemId}/similar`);
  return Array.isArray(data) ? data : [];
}

/** Indexed counts of SOLD listings over rolling 24h/7d/30d windows,
 *  plus 30d volume and the most-recent sold price + timestamp. The
 *  failure fallback returns the COMPLETE shape (numeric fields 0,
 *  nullable fields null) so the ItemModal velocity consumer never sees
 *  `undefined` for soldLast24h / volumeLast30d / lastSoldPrice /
 *  lastSoldAt on a failed fetch. */
export async function fetchItemVelocity(itemId) {
  return (await safeJson(`${API}/items/${itemId}/velocity`))
    || { soldLast24h: 0, soldLast7d: 0, soldLast30d: 0, volumeLast30d: 0, lastSoldPrice: null, lastSoldAt: null };
}

/** Buy a listing by id. Optional `expectedPrice` pins what the user
 *  saw in the modal; server rejects with `PRICE_CHANGED` if the price
 *  has drifted (seller edit between modal render and Buy click). */
export async function buyListing(id, expectedPrice) {
  const body = expectedPrice != null ? { expectedPrice: String(expectedPrice) } : null;
  return writeJson(`${API}/listings/${id}/buy`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined
  });
}

/** GET a single listing by id. Returns null on 404 (listing sold / cancelled).
 *  The 404 is an expected outcome here — the cart-stub refresh flow probes
 *  ids that may have been bought/cancelled since they were carted — so it's
 *  muted to keep the console clean (same opt-out as the other read-by-id
 *  endpoints: item / stall / loadout). */
export async function fetchListingById(id) {
  return safeJson(`${API}/listings/${id}`, undefined, { expect: [404] });
}

/** User-facing report. Returns { reportCount, thanks } on success, or
 *  { error / code, message } on refusal (self-report, duplicate, rate-limit). */
export async function reportListing(id, reason, note) {
  return writeJson(`${API}/listings/${id}/report`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason, note })
  });
}
/** Open a FRAUD-category support ticket reporting another user. */
export async function reportUser(targetUserId, reason, context) {
  return writeJson(`${API}/support/report-user/${targetUserId}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason, context })
  });
}

export async function fetchReportReasons() {
  const data = await safeJson(`${API}/listings/report-reasons`);
  return Array.isArray(data) ? data : [];
}

// ── Server-side watchlist price alerts ──────────────────────────
export async function fetchWatchlistAlerts() {
  const data = await safeJson(`${API}/watchlist/alerts`);
  return Array.isArray(data) ? data : [];
}
export async function createWatchlistAlert(itemId, targetPrice) {
  return writeJson(`${API}/watchlist/alerts`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ itemId, targetPrice })
  });
}
export async function cancelWatchlistAlert(id) {
  return writeJson(`${API}/watchlist/alerts/${id}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
export async function setEmailNotifications(enabled) {
  return writeJson(`${API}/profile/email-notifications`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !!enabled })
  });
}
/** Per-bucket email-mute preferences (layered on top of the global
 *  emailNotificationsEnabled kill switch). Buckets that can be muted:
 *  TRADES / AUCTIONS / WATCHLIST / FOLLOWS. Transactional emails
 *  (verification, withdrawal approval, ban) cannot be muted. */
export async function fetchEmailMutes() {
  try {
    const r = await fetch(`${API}/profile/email-mutes`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}
export async function setEmailMutes(muted) {
  return writeJson(`${API}/profile/email-mutes`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ muted: Array.isArray(muted) ? muted : [] })
  });
}

export async function requestAccountDeletion() {
  return writeJson(`${API}/profile/delete-account`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function cancelAccountDeletion() {
  return writeJson(`${API}/profile/delete-account/cancel`, {
    method: 'POST', credentials: 'same-origin'
  });
}
/** Batch 697 — force-invalidate every live session on the caller's
 *  account by bumping the server-side sessionEpoch. Includes the
 *  current session; caller should redirect to `/` immediately after. */
export async function signOutEverywhere() {
  return writeJson(`${API}/profile/sign-out-everywhere`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function clearFiredWatchlistAlerts() {
  return writeJson(`${API}/watchlist/alerts/clear-fired`, {
    method: 'POST', credentials: 'same-origin'
  });
}

// ── Follow seller ────────────────────────────────────────────────
/** Anonymous-friendly: returns { following: false, followerCount: N } for
 *  signed-out viewers. Fires once on every stall-page load. */
export async function fetchFollowStatus(sellerId) {
  return safeJson(`${API}/follows/status/${sellerId}`);
}
export async function followSeller(sellerId) {
  return writeJson(`${API}/follows/${sellerId}`, { method: 'POST', credentials: 'same-origin' });
}
export async function unfollowSeller(sellerId) {
  return writeJson(`${API}/follows/${sellerId}`, { method: 'DELETE', credentials: 'same-origin' });
}
export async function fetchFollowing() {
  const data = await safeJson(`${API}/follows`);
  return Array.isArray(data) ? data : [];
}
/** Unfollow every seller in one call. Returns `{unfollowed:N}`. */
export async function unfollowAllSellers() {
  return writeJson(`${API}/follows`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** Bulk mute / unmute new-listing pings across every seller the user
 *  follows (batch 295). Follow rows stay — only the bell + email fan-
 *  out is suppressed when muted. Returns `{touched:N, muted:bool}`. */
export async function setAllSellerMuted(muted) {
  return writeJson(`${API}/follows/mute-all`, {
    method: 'PATCH', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ muted: !!muted })
  });
}
/** Mute / un-mute the new-listing pings for one followed seller (V36
 *  / batch 279). Keeps the follow row alive — only the bell + email
 *  fan-out is suppressed for that seller. */
export async function setSellerMuted(sellerId, muted) {
  return writeJson(`${API}/follows/${sellerId}/mute`, {
    method: 'PATCH', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ muted: !!muted })
  });
}

/** Fetch all listings for a specific item by its item ID.
 *  Uses the dedicated /api/listings/item/{id} endpoint instead of the
 *  general /api/listings query which doesn't support itemId filtering. */
export async function fetchListingsForItem(itemId) {
  const data = await safeJson(`${API}/listings/item/${itemId}`, undefined, { expect: [404] });
  return Array.isArray(data) ? data : [];
}

/** "More from this seller" rail on the ItemModal. Returns up to 8
 *  other active visible listings from the same seller, excluding
 *  the item the modal is currently showing. Null-guarded: returns
 *  [] for system listings where sellerUserId is missing. */
export async function fetchOtherFromSeller(sellerUserId, excludeItemId, limit = 8) {
  if (!sellerUserId || !excludeItemId) return [];
  const data = await safeJson(
    `${API}/listings/seller/${sellerUserId}/other?excludeItemId=${excludeItemId}&limit=${limit}`);
  return Array.isArray(data) ? data : [];
}

export async function fetchInventory() {
  const data = await safeJson(`${API}/listings/inventory`);
  return Array.isArray(data) ? data : [];
}

/** Variant that also returns the true inventory row count (from the
 *  server's `X-Total-Count` header) so the SellItemsModal can render
 *  "Showing most recent 500 of N" when the 500-row display cap is
 *  hit. Keeps the plain `fetchInventory()` array contract stable for
 *  every other caller. */
export async function fetchInventoryWithTotal() {
  try {
    const res = await fetch(`${API}/listings/inventory`, { credentials: 'same-origin' });
    if (!res.ok) return { items: [], total: 0 };
    const items = await res.json();
    const totalHeader = res.headers.get('X-Total-Count');
    const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
    const fallback = Array.isArray(items) ? items.length : 0;
    return {
      items: Array.isArray(items) ? items : [],
      total: Number.isFinite(parsed) ? parsed : fallback
    };
  } catch {
    return { items: [], total: 0 };
  }
}

/** Sale history for the signed-in seller — last 200 SOLD listings,
 *  newest first. Drives the MyStall "Sold" tab. */
export async function fetchMyStallSold() {
  const data = await safeJson(`${API}/listings/my-stall/sold`);
  return Array.isArray(data) ? data : [];
}

/** Variant with `X-Total-Count` — enables the MyStall Sold tab's
 *  "Showing most recent 200 of N" overflow banner for power-sellers
 *  with 200+ closed sales. Mirrors the inventory / trades / buy-orders
 *  end-to-end pattern. */
export async function fetchMyStallSoldWithTotal() {
  try {
    const res = await fetch(`${API}/listings/my-stall/sold`, { credentials: 'same-origin' });
    if (!res.ok) return { items: [], total: 0 };
    const items = await res.json();
    const totalHeader = res.headers.get('X-Total-Count');
    const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
    const fallback = Array.isArray(items) ? items.length : 0;
    return {
      items: Array.isArray(items) ? items : [],
      total: Number.isFinite(parsed) ? parsed : fallback
    };
  } catch {
    return { items: [], total: 0 };
  }
}
/** Apply a percent adjustment (±50 max) to every active non-auction
 *  listing in the signed-in user's stall. Returns { touched, skipped }. */
export async function bulkAdjustStall(percent) {
  return writeJson(`${API}/listings/my-stall/bulk-adjust`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ percent })
  });
}
export async function fetchMyStall() {
  const data = await safeJson(`${API}/listings/my-stall`);
  return Array.isArray(data) ? data : [];
}

/** Variant with `X-Total-Count` — drives the "Showing most recent 500
 *  of N" banner on the MyStall Active tab when a prolific seller
 *  crosses the display cap (batch 1033). Keeps plain `fetchMyStall()`
 *  array contract stable for existing callers. */
export async function fetchMyStallWithTotal() {
  try {
    const res = await fetch(`${API}/listings/my-stall`, { credentials: 'same-origin' });
    if (!res.ok) return { items: [], total: 0 };
    const items = await res.json();
    const totalHeader = res.headers.get('X-Total-Count');
    const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
    const fallback = Array.isArray(items) ? items.length : 0;
    return {
      items: Array.isArray(items) ? items : [],
      total: Number.isFinite(parsed) ? parsed : fallback
    };
  } catch {
    return { items: [], total: 0 };
  }
}

/** Public recent-sales strip for a stall — last 10 sold listings,
 *  price + soldAt + item only. No buyer identities. Pass a limit up
 *  to 200 when you want enough samples for a sales sparkline. */
export async function fetchPublicStallSold(userId, limit) {
  const qs = (Number.isFinite(+limit) && limit > 0) ? `?limit=${Math.min(+limit, 200)}` : '';
  const data = await safeJson(`${API}/listings/stall/${userId}/recent-sales${qs}`, undefined, { expect: [404] });
  return Array.isArray(data) ? data : [];
}
export async function fetchPublicStall(userId) {
  // Endpoint returns 200 with `{notFound: true}` for missing ids (keeps
  // Chrome's auto-logged fetch 404 out of the console). Translate the
  // sentinel to null so the SPA's existing `__notFound` branch still
  // fires from the standard `stall || { __notFound: true }` fallback.
  const data = await safeJson(`${API}/listings/stall/${userId}`, undefined, { expect: [404] });
  if (data && data.notFound) return null;
  return data;
}

export async function relistItem(listingId, price, opts = {}) {
  const body = { listingId, price };
  if (opts.listingType)   body.listingType = opts.listingType;
  if (opts.durationHours) body.durationHours = opts.durationHours;
  if (opts.description)   body.description = opts.description;
  if (opts.buyNowPrice != null) body.buyNowPrice = opts.buyNowPrice;
  // Batch 646 — optional auto-accept threshold (0..1). Client sends
  // the fraction form; SellItemsModal converts from the percent input.
  if (opts.maxDiscount != null) body.maxDiscount = opts.maxDiscount;
  return writeJson(`${API}/listings/sell`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function cancelListing(listingId) {
  return writeJson(`${API}/listings/${listingId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}

// ── Wallet ──────────────────────────────────────────────────────
export async function fetchWallet() {
  try {
    const r = await fetch(`${API}/wallet`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}

export async function fetchTransactions() {
  try {
    const r = await fetch(`${API}/wallet/transactions`, { credentials: 'same-origin' });
    if (!r.ok) return [];
    const data = await r.json();
    return Array.isArray(data) ? data : [];
  } catch (_) { return []; }
}

// Buyer-side spending summary (batch 846). Shape:
//   { spentLifetime, spent30d, spent7d, purchasesLifetime, purchases30d, purchases7d }
// Returns null on any non-2xx so the caller can hide the strip silently
// instead of rendering a broken empty state.
export async function fetchWalletSpend() {
  try {
    const r = await fetch(`${API}/wallet/spend`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return r.json();
  } catch (_) { return null; }
}

export async function depositFunds(amount) {
  return writeJson(`${API}/wallet/deposit`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
  });
}

/** Cancel a PENDING withdrawal the user requested. Credits the wallet
 *  back; only works before the withdrawal has been approved + paid out. */
export async function cancelPendingWithdrawal(txId) {
  return writeJson(`${API}/wallet/withdraw/${txId}/cancel`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function withdrawFunds(amount, destination, totpCode) {
  return writeJson(`${API}/wallet/withdraw`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, destination, totpCode })
  });
}

export async function confirmDeposit(sessionId) {
  return writeJson(`${API}/wallet/confirm-deposit?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'POST', credentials: 'same-origin'
  });
}

// ── Steam auth ──────────────────────────────────────────────────
export async function fetchMe() {
  try {
    const r = await fetch(`${API}/auth/steam/me`, { credentials: 'same-origin' });
    // 401 is the legacy anonymous response, retained as a fallback. Newer
    // backends return 200 with `{ signedIn: false }` for anon to keep the
    // browser DevTools console clean (no red "401" line on every page load).
    if (r.status === 401) return null;
    if (!r.ok) return null;
    const data = await r.json();
    if (data && data.signedIn === false) return null;
    return data;
  } catch { return null; }
}

export async function logoutSteam() {
  await fetch(`${API}/auth/steam/logout`, { method: 'POST', credentials: 'same-origin' });
}

// ── Offers ──────────────────────────────────────────────────────
export async function fetchIncomingOffers() {
  const data = await safeJson(`${API}/offers/incoming`);
  return Array.isArray(data) ? data : [];
}

/** Your live PENDING/COUNTERED offer on one specific listing (batch
 *  368) — or null if you haven't offered on it. Drives the "You offered
 *  $X" chip on the ItemModal listings row so a buyer revisiting a
 *  listing immediately sees their own offer state. */
export async function fetchMyOfferForListing(listingId) {
  const data = await safeJson(`${API}/offers/mine-for-listing/${listingId}`);
  return (data && typeof data === 'object') ? (data.offer || null) : null;
}

export async function fetchOutgoingOffers() {
  const data = await safeJson(`${API}/offers/outgoing`);
  return Array.isArray(data) ? data : [];
}

/** { listingId: { bestAmount, count, newestAt } } for every active
 *  listing of the caller that has at least one PENDING buyer offer.
 *  Drives the MyStall "Best offer $X · N pending" chip. */
export async function fetchBestOfferPerListing() {
  const data = await safeJson(`${API}/offers/best-per-listing`);
  return data && typeof data === 'object' ? data : {};
}

export async function makeOffer(listingId, amount, message) {
  const body = { listingId, amount };
  // Optional 280-char buyer note. Empty / whitespace-only collapses out
  // of the payload so the server stores null instead of an empty row.
  const trimmed = (message || '').trim();
  if (trimmed) body.message = trimmed;
  return writeJson(`${API}/offers`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function acceptOffer(offerId) {
  return writeJson(`${API}/offers/${offerId}/accept`, { method: 'POST', credentials: 'same-origin' });
}

export async function rejectOffer(offerId, reply) {
  const body = {};
  const trimmed = (reply || '').trim();
  if (trimmed) body.reply = trimmed;
  return writeJson(`${API}/offers/${offerId}/reject`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function cancelOffer(offerId) {
  return writeJson(`${API}/offers/${offerId}`, { method: 'DELETE', credentials: 'same-origin' });
}

/** Bulk-cancel every PENDING outgoing offer for the caller. Returns
 *  `{cancelled:N}` — zero-row callers get `{cancelled:0}`, not a 404. */
export async function cancelAllOutgoingOffers() {
  return writeJson(`${API}/offers/outgoing/cancel-all`, {
    method: 'POST', credentials: 'same-origin'
  });
}

export async function counterOffer(offerId, amount, message) {
  const body = { amount };
  const trimmed = (message || '').trim();
  if (trimmed) body.message = trimmed;
  return writeJson(`${API}/offers/${offerId}/counter`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

/** Buyer-side raise — lets a buyer escalate their own pending offer
 *  without waiting for the seller. Backend cancels the original and
 *  creates a new PENDING offer threaded via parentOfferId. */
export async function raiseOffer(offerId, amount, message) {
  const body = { amount };
  const trimmed = (message || '').trim();
  if (trimmed) body.message = trimmed;
  return writeJson(`${API}/offers/${offerId}/raise`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function fetchOfferThread(listingId) {
  const data = await safeJson(`${API}/offers/thread/${listingId}`);
  return Array.isArray(data) ? data : [];
}

// ── Buy Orders ──────────────────────────────────────────────────
export async function fetchBuyOrders() {
  const data = await safeJson(`${API}/buy-orders`);
  return Array.isArray(data) ? data : [];
}

/** Variant that also returns the server's true buy-order count via
 *  the `X-Total-Count` header — feeds the "Showing most recent 300 of
 *  N" banner once a user crosses BUY_ORDER_LIST_CAP (batch 1009).
 *  Leaves the plain `fetchBuyOrders()` array contract intact. */
export async function fetchBuyOrdersWithTotal() {
  try {
    const res = await fetch(`${API}/buy-orders`, { credentials: 'same-origin' });
    if (!res.ok) return { items: [], total: 0 };
    const items = await res.json();
    const totalHeader = res.headers.get('X-Total-Count');
    const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
    const fallback = Array.isArray(items) ? items.length : 0;
    return {
      items: Array.isArray(items) ? items : [],
      total: Number.isFinite(parsed) ? parsed : fallback
    };
  } catch {
    return { items: [], total: 0 };
  }
}

export async function createBuyOrder(payload) {
  return writeJson(`${API}/buy-orders`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export async function deleteBuyOrder(id) {
  return writeJson(`${API}/buy-orders/${id}`, { method: 'DELETE', credentials: 'same-origin' });
}

/** Bulk-cancel every ACTIVE buy order the caller owns. Returns
 *  `{cancelled:N}` on success — zero-row callers get `{cancelled:0}`
 *  rather than a 404, so the UI can always render the count. */
export async function cancelAllBuyOrders() {
  return writeJson(`${API}/buy-orders/cancel-all`, {
    method: 'POST', credentials: 'same-origin'
  });
}

/** Edit an ACTIVE buy order in place. Either field can be null to
 *  leave it unchanged; the backend applies the 100k cap / quantity
 *  bounds on its own. Shrinks remaining fills when `quantity` is
 *  lower; refuses to grow past the original. */
export async function updateBuyOrder(id, { maxPrice, quantity }) {
  return writeJson(`${API}/buy-orders/${id}`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ maxPrice, quantity })
  });
}

// Bump a buy order's updatedAt so the 30-day auto-expire sweep resets
// without the user having to open the edit flow and re-submit
// unchanged values. The existing update endpoint already bumps
// updatedAt on any PUT so an empty body is sufficient (batch 857).
export async function bumpBuyOrder(id) {
  return writeJson(`${API}/buy-orders/${id}`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: '{}'
  });
}

// ── Bids / Auctions ─────────────────────────────────────────────
export async function placeBid(listingId, amount, maxAmount) {
  return writeJson(`${API}/bids`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingId, amount, maxAmount })
  });
}

export async function fetchBidHistory(listingId) {
  const data = await safeJson(`${API}/bids/listing/${listingId}`);
  return Array.isArray(data) ? data : [];
}

export async function fetchAutoBids() {
  const data = await safeJson(`${API}/bids/auto`);
  return Array.isArray(data) ? data : [];
}
/** All bids the user currently has LIVE (WINNING or OUTBID) — includes both
 *  MANUAL and AUTO rows so the Profile Active-Bids tab can show every auction
 *  they're still in. */
export async function fetchActiveBids() {
  const data = await safeJson(`${API}/bids/my-active`);
  return Array.isArray(data) ? data : [];
}
/** Past bids — WON, LOST, CANCELLED. Drives the Profile → Bids → Past
 *  sub-tab (batch 361). Capped at 100 most-recent server-side. */
export async function fetchPastBids() {
  const data = await safeJson(`${API}/bids/my-past`);
  return Array.isArray(data) ? data : [];
}
/** Cancel the auto-raise on one specific bid (winning bid stays put). */
export async function cancelAutoBid(bidId) {
  return writeJson(`${API}/bids/auto/${bidId}/cancel`, {
    method: 'POST', credentials: 'same-origin'
  });
}
/** Bulk cancel every active auto-raise the user owns. */
export async function cancelAllAutoBids() {
  return writeJson(`${API}/bids/auto/cancel-all`, {
    method: 'POST', credentials: 'same-origin'
  });
}

// ── Notifications ───────────────────────────────────────────────
/** Default: up to 100 rows (full NotificationsModal view). Callers
 *  that only need the 12 newest (nav bell dropdown) pass `limit=12`
 *  to shave ~85% off the payload. */
export async function fetchNotifications(limit) {
  const qs = (limit != null && Number.isFinite(limit)) ? `?limit=${limit}` : '';
  try {
    const r = await fetch(`${API}/notifications${qs}`, { credentials: 'same-origin' });
    if (!r.ok) return { items: [], unread: 0 };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { items: [], unread: 0 };
  } catch (_) { return { items: [], unread: 0 }; }
}

/** Cheap unread-count for the nav bell's 25-second poll — avoids
 *  shipping 100 notification rows on every tick just to compute a
 *  single integer. Falls through to 0 on any error so the bell
 *  doesn't drop stale unread ticks on a transient network blip. */
export async function fetchUnreadNotificationCount() {
  try {
    const r = await fetch(`${API}/notifications/unread-count`, { credentials: 'same-origin' });
    if (!r.ok) return 0;
    const data = await r.json();
    const n = parseInt(data?.unread, 10);
    return Number.isFinite(n) ? n : 0;
  } catch {
    return 0;
  }
}

export async function markNotificationRead(id) {
  return fetch(`${API}/notifications/${id}/read`, { method: 'POST', credentials: 'same-origin' });
}

/** Flip a notification back to unread (batch 365) — lets a user
 *  defer handling without losing the row. */
export async function markNotificationUnread(id) {
  return fetch(`${API}/notifications/${id}/unread`, { method: 'POST', credentials: 'same-origin' });
}

export async function clearReadNotifications() {
  return writeJson(`${API}/notifications/clear-read`, {
    method: 'POST', credentials: 'same-origin'
  });
}

/** Delete a single notification. Silent no-op when the id doesn't
 *  exist or isn't yours. */
export async function deleteNotification(id) {
  return fetch(`${API}/notifications/${id}`, { method: 'DELETE', credentials: 'same-origin' });
}
export async function markAllNotificationsRead() {
  return fetch(`${API}/notifications/read-all`, { method: 'POST', credentials: 'same-origin' });
}

/** Batch 636 — scoped counterpart to clearReadNotifications(). Sends
 *  the visible-and-read ids so deletion only touches rows the user
 *  can actually see. Returns `{deleted: N}`. */
export async function deleteNotificationsBatch(ids) {
  if (!Array.isArray(ids) || ids.length === 0) return { deleted: 0 };
  return writeJson(`${API}/notifications/delete-batch`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  });
}

/** Batch 635 — filter-scoped "Mark visible read". Sends the current
 *  visible-unread ids so the server flip only touches rows the user
 *  is actually seeing on the page. Returns `{flipped: N}`. */
export async function markNotificationsReadBatch(ids) {
  if (!Array.isArray(ids) || ids.length === 0) return { flipped: 0 };
  return writeJson(`${API}/notifications/read-batch`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  });
}

// ── Database ────────────────────────────────────────────────────
export async function fetchDatabase(params = {}) {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== null && v !== undefined && v !== '') q.append(k, v);
  });
  try {
    const r = await fetch(`${API}/database?${q}`);
    if (!r.ok) return { items: [], total: 0, indexed: 0 };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { items: [], total: 0, indexed: 0 };
  } catch (_) { return { items: [], total: 0, indexed: 0 }; }
}

// ── Loadouts ────────────────────────────────────────────────────
export async function fetchPublicLoadouts(search) {
  const data = await safeJson(`${API}/loadouts/discover${search ? '?search=' + encodeURIComponent(search) : ''}`);
  return Array.isArray(data) ? data : [];
}

export async function fetchMyLoadouts() {
  const data = await safeJson(`${API}/loadouts/mine`);
  return Array.isArray(data) ? data : [];
}

/** Loadouts the signed-in user has favorited, newest-favorite first.
 *  Empty array for anon viewers (401 on the underlying endpoint). */
export async function fetchFavoriteLoadouts() {
  const data = await safeJson(`${API}/loadouts/favorites`);
  return Array.isArray(data) ? data : [];
}

export async function fetchLoadout(id) {
  // Endpoint returns 200 with `{notFound: true}` for missing OR private-
  // from-this-viewer loadouts (keeps Chrome's fetch 404 out of console).
  // Translate to null so the LoadoutLabModal's existing `__notFound`
  // sentinel branch still fires from `data || { __notFound: true }`.
  const data = await safeJson(`${API}/loadouts/${id}`, undefined, { expect: [404] });
  if (data && data.notFound) return null;
  return data;
}

export async function createLoadout(payload) {
  return writeJson(`${API}/loadouts`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export async function setLoadoutSlot(id, slot, itemId) {
  return writeJson(`${API}/loadouts/${id}/slot/${encodeURIComponent(slot)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ itemId })
  });
}

/** Toggle the lock on a single loadout slot. Locked slots are preserved
 *  by the /generate autopicker; unlocked slots get overwritten with the
 *  cheapest-within-budget candidate. Returns { slot, locked } on success. */
export async function lockLoadoutSlot(id, slot) {
  return writeJson(`${API}/loadouts/${id}/slot/${encodeURIComponent(slot)}/lock`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' }
  });
}

export async function generateLoadout(id, budget) {
  return writeJson(`${API}/loadouts/${id}/generate`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ budget })
  });
}

/** Toggle the favorite flag on a loadout. Returns the parsed body —
 *  `{favorited, favorites}` on success, or `{error, code}` on failure
 *  (FORBIDDEN / 404 / rate-limit). The sole consumer (LoadoutLabModal)
 *  branches on `res.error || res.code` and reads `res.favorited` /
 *  `res.favorites`, so this must go through writeJson — a raw fetch
 *  Response carries none of those fields and a failed toggle would
 *  silently read as success. */
export async function favoriteLoadout(id) {
  return writeJson(`${API}/loadouts/${id}/favorite`, { method: 'POST', credentials: 'same-origin' });
}

/** Duplicate a PUBLIC loadout (or the viewer's own private one) into the
 *  viewer's stable. The copy starts PRIVATE and unlocked — they can retune
 *  freely before republishing. Returns the new Loadout body so the caller
 *  can navigate straight to the new id. */
export async function cloneLoadout(id) {
  return writeJson(`${API}/loadouts/${id}/clone`, {
    method: 'POST',
    credentials: 'same-origin'
  });
}

/** Owner-only metadata update. Any subset of { name, description, visibility }.
 *  Used for rename + switching a cloned PRIVATE loadout to PUBLIC. */
export async function updateLoadout(id, patch) {
  return writeJson(`${API}/loadouts/${id}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch || {})
  });
}

/** Delete a loadout the caller owns. Returns the parsed body, or
 *  `{error, code}` on refusal (FORBIDDEN when the caller isn't the
 *  owner, rate-limit). The consumer branches on `res.error || res.code`,
 *  so this goes through writeJson — a raw fetch Response has neither
 *  field and a rejected delete would silently look successful. */
export async function deleteLoadout(id) {
  return writeJson(`${API}/loadouts/${id}`, { method: 'DELETE', credentials: 'same-origin' });
}

// ── Admin ───────────────────────────────────────────────────────
export async function adminCheck() {
  try {
    const r = await fetch(`${API}/admin/check`, { credentials: 'same-origin' });
    if (!r.ok) return { admin: false };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { admin: false };
  } catch (_) { return { admin: false }; }
}
export async function adminStats() { return (await safeJson(`${API}/admin/stats`)) || {}; }
export async function adminWithdrawals(status = 'PENDING') {
  const data = await safeJson(`${API}/admin/withdrawals?status=${encodeURIComponent(status)}`);
  return Array.isArray(data) ? data : [];
}
export async function adminApproveWithdrawal(id, payoutRef) {
  return writeJson(`${API}/admin/withdrawals/${id}/approve`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payoutRef })
  });
}
export async function adminRejectWithdrawal(id, reason) {
  return writeJson(`${API}/admin/withdrawals/${id}/reject`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
/** Admin users listing (batch 765). `opts` can carry `search`, `role`,
 *  and `banned`. `search` takes precedence (same as the service). */
export async function adminUsers(opts) {
  // Legacy call-shape: `adminUsers("some query string")`. Normalise to
  // the options-bag shape so we keep one fetch path.
  if (typeof opts === 'string' || opts == null) {
    opts = opts ? { search: opts } : {};
  }
  const params = new URLSearchParams();
  if (opts.search) params.set('search', opts.search);
  if (opts.role && opts.role !== 'ANY') params.set('role', opts.role);
  if (opts.banned === true)  params.set('banned', 'true');
  if (opts.banned === false) params.set('banned', 'false');
  const qs = params.toString();
  const data = await safeJson(`${API}/admin/users${qs ? '?' + qs : ''}`);
  return Array.isArray(data) ? data : [];
}
/** Consolidated per-user staff summary (batch 549) — wallet + dispute
 *  + 2FA + email-verified state in one round-trip. Returns null if the
 *  endpoint fails so the drawer can still render basic SteamUser fields. */
export async function adminUserSummary(id) {
  try {
    return await safeJson(`${API}/admin/users/${id}/summary`);
  } catch (_) {
    return null;
  }
}
/** Sign-in history for the caller — 20 most-recent USER_SIGN_IN
 *  audit rows (batch 569). Returns [] on error. */
export async function fetchSignInHistory() {
  const data = await safeJson(`${API}/profile/sign-in-history`);
  return Array.isArray(data) ? data : [];
}

/** Batch 714 — security-relevant audit events (2FA resets, API key
 *  mints/revocations, admin actions, withdrawals, disputes). Up to 50
 *  rows, newest-first, whitelisted event types only. 401 for anon. */
export async function fetchSecurityActivity() {
  const data = await safeJson(`${API}/profile/security-activity`);
  return Array.isArray(data) ? data : [];
}
/** Admin drill-down: 100 most-recent wallet transactions for a user
 *  (batch 568). Returns [] on error so the drawer still renders. */
export async function adminUserTransactions(id) {
  try {
    const data = await safeJson(`${API}/admin/users/${id}/transactions`);
    return Array.isArray(data) ? data : [];
  } catch (_) {
    return [];
  }
}
export async function adminBanUser(id, reason) {
  return writeJson(`${API}/admin/users/${id}/ban`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
export async function adminUnbanUser(id) {
  return writeJson(`${API}/admin/users/${id}/unban`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminForceLogout(id) {
  return writeJson(`${API}/admin/users/${id}/force-logout`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminGrant(id) {
  return writeJson(`${API}/admin/users/${id}/grant-admin`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminRevoke(id) {
  return writeJson(`${API}/admin/users/${id}/revoke-admin`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminGrantCsr(id) {
  return writeJson(`${API}/admin/users/${id}/grant-csr`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminRevokeCsr(id) {
  return writeJson(`${API}/admin/users/${id}/revoke-csr`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminReset2fa(id, note) {
  return writeJson(`${API}/admin/users/${id}/reset-2fa`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ note })
  });
}
export async function adminDeletionRequests() {
  const data = await safeJson(`${API}/admin/users/deletion-requests`);
  return Array.isArray(data) ? data : [];
}
export async function adminFinalizeDeletion(id) {
  return writeJson(`${API}/admin/users/${id}/finalize-deletion`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function adminReadNotes(id) {
  return safeJson(`${API}/admin/users/${id}/notes`);
}
export async function adminWriteNotes(id, notes) {
  return writeJson(`${API}/admin/users/${id}/notes`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notes })
  });
}
export async function adminCreditWallet(id, amount, note) {
  return writeJson(`${API}/admin/users/${id}/credit`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, note })
  });
}
/** Freeze a user's wallet (batch 509). Softer than a ban — refuses
 *  money-in/out but keeps the account usable otherwise. Reason required. */
export async function adminFreezeWallet(id, reason) {
  return writeJson(`${API}/admin/users/${id}/wallet/freeze`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
/** Lift a wallet freeze. Idempotent — no-op if already unfrozen. */
export async function adminUnfreezeWallet(id) {
  return writeJson(`${API}/admin/users/${id}/wallet/unfreeze`, {
    method: 'POST', credentials: 'same-origin'
  });
}
/** Admin direct message to a single user (batch 580). Rejected for
 *  banned targets server-side. title required, body + path optional. */
export async function adminMessageUser(id, title, body, path) {
  return writeJson(`${API}/admin/users/${id}/message`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, body, path })
  });
}
/** Admin broadcast notification to every non-banned user (batch 566).
 *  Returns {sent, batches} on success. title is required; body + path
 *  optional. 120/500/200-char server-side caps. */
export async function adminBroadcast(title, body, path) {
  return writeJson(`${API}/admin/broadcast`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, body, path })
  });
}
export async function adminRemoveListing(id, reason) {
  return writeJson(`${API}/admin/listings/${id}/remove`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
export async function adminReportedListings() {
  const data = await safeJson(`${API}/admin/listings/reported`);
  return Array.isArray(data) ? data : [];
}
export async function adminDismissReports(id, note) {
  return writeJson(`${API}/admin/listings/${id}/dismiss-reports`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ note })
  });
}
export async function adminTickets(status, search) {
  // Batch 576 — optional free-text narrows the triage queue by
  // subject / username / category. Both params encode safely; neither
  // widens the response shape.
  const qp = new URLSearchParams();
  if (status) qp.set('status', status);
  if (search) qp.set('search', search);
  const suffix = qp.toString() ? '?' + qp.toString() : '';
  const data = await safeJson(`${API}/admin/tickets${suffix}`);
  return Array.isArray(data) ? data : [];
}
export async function adminTicket(id) { return safeJson(`${API}/admin/tickets/${id}`); }
export async function adminTicketReply(id, body) {
  return writeJson(`${API}/admin/tickets/${id}/reply`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
}
export async function adminCloseTicket(id) {
  return writeJson(`${API}/admin/tickets/${id}/close`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminTrades(state = 'ALL') {
  const data = await safeJson(`${API}/admin/trades?state=${encodeURIComponent(state)}`);
  return Array.isArray(data) ? data : [];
}
export async function adminReleaseTrade(id, reason) {
  return writeJson(`${API}/admin/trades/${id}/release`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
export async function adminDeleteTradeMessage(id) {
  return writeJson(`${API}/admin/trade-messages/${id}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}

export async function adminCancelTrade(id, reason) {
  return writeJson(`${API}/admin/trades/${id}/cancel`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
export async function adminAudit(params = {}) {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v) q.append(k, v); });
  const data = await safeJson(`${API}/admin/audit?${q}`);
  return Array.isArray(data) ? data : [];
}
/** Fraud-signal rollup — multiple IPs per user, shared IPs across accounts,
 *  rapid withdraw-after-deposit, high-velocity purchases. Rolls up the
 *  last 24h of AuditLog into a prioritized triage list. */
export async function adminFraudSignals() {
  const data = await safeJson(`${API}/admin/fraud`);
  return Array.isArray(data) ? data : [];
}

/** Batch 700 — admin fraud-triage helper. Paste a prefix fragment
 *  from a log line (e.g. `sbx_live_abc12`) and get back every matching
 *  key with owner id + label + scope + revoked-flag + last-used. */
export async function adminApiKeyLookup(prefix) {
  const p = (prefix || '').trim();
  if (p.length < 3) return [];
  const data = await safeJson(`${API}/admin/api-keys/lookup?prefix=${encodeURIComponent(p)}`);
  return Array.isArray(data) ? data : [];
}

/** Admin chargeback queue (batch 462) — every DEPOSIT transaction in
 *  DISPUTED state, flagged via Stripe's charge.dispute.created webhook.
 *  Returns the user info inline so the table can render without an N+1
 *  lookup loop. */
export async function adminDisputes() {
  const data = await safeJson(`${API}/admin/disputes`);
  return Array.isArray(data) ? data : [];
}

/** Clear a DISPUTED deposit (batch 467) — flips it back to COMPLETED so
 *  the user's withdrawal hold lifts. Used when the chargeback resolves
 *  in our favour, or staff verify the dispute is a false positive. */
export async function adminClearDispute(txId, reason) {
  return writeJson(`${API}/admin/transactions/${txId}/clear-dispute`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason || '' })
  });
}
export async function adminRefundDeposit(id, amount) {
  return writeJson(`${API}/admin/deposits/${id}/refund`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
  });
}
export async function adminSimulateListings(count = 20) {
  return writeJson(`${API}/admin/simulate/listings`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ count })
  });
}
export async function adminClearSimulated() {
  return writeJson(`${API}/admin/simulate/clear`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function adminCountSimulated() {
  return (await safeJson(`${API}/admin/simulate/count`)) || { count: 0 };
}
export async function adminSyncScmm() {
  try {
    const r = await fetch(`${API}/admin/sync-scmm`, {
      method: 'POST', credentials: 'same-origin'
    });
    if (!r.ok) return { error: `HTTP ${r.status}` };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : {};
  } catch (_) {
    // Consumer (SimulatorPanel.runSync) has no catch — without this a
    // network blip throws past its try/finally and the busy spinner
    // never clears with no error toast. Return the { error } shape it
    // already branches on.
    return { error: 'Network error — try again' };
  }
}
/** Kicks off the Steam Community Market priceoverview sync in a background
 *  thread server-side (returns immediately — full sync takes ~11 min for 80
 *  items × 8s throttle). Caller polls /api/items afterwards to see the
 *  updated lowestPrice + trendPercent. Admin-gated. */
export async function adminSyncSteamPrices() {
  return writeJson(`${API}/admin/sync-prices`, {
    method: 'POST', credentials: 'same-origin'
  });
}
// ── Reviews ─────────────────────────────────────────────────────
export async function leaveReview(tradeId, rating, comment) {
  return writeJson(`${API}/reviews`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tradeId, rating, comment })
  });
}
export async function fetchReviewsForUser(userId) {
  // 404 = unknown user (paired with a dead stall landing); not a bug.
  const data = await safeJson(`${API}/reviews/user/${userId}`, undefined, { expect: [404] });
  return Array.isArray(data) ? data : [];
}
/** Reviews the signed-in user has authored (as a buyer). Auth-gated —
 *  returns [] when anonymous. Drives the Profile → Reviews → Given tab. */
export async function fetchMyAuthoredReviews() {
  const data = await safeJson(`${API}/reviews/mine`);
  return Array.isArray(data) ? data : [];
}
export async function fetchReviewSummary(userId) {
  return (await safeJson(`${API}/reviews/user/${userId}/summary`)) || { count: 0, average: null };
}
/** Verified trades between the viewer and this seller, each tagged with
 *  whether the viewer has already reviewed it. Drives the "Leave a review"
 *  CTA on the public stall page. Returns [] for anonymous viewers. */
export async function fetchEligibleReviews(sellerUserId) {
  const data = await safeJson(`${API}/reviews/eligible/${sellerUserId}`, undefined, { expect: [404] });
  return Array.isArray(data) ? data : [];
}
/** Every unreviewed verified trade for the signed-in user, across every
 *  seller. Drives the Profile → Reviews "N trades to review" chip + list.
 *  Returns { count: 0, items: [] } for anonymous viewers. */
export async function fetchPendingReviews() {
  const data = await safeJson(`${API}/reviews/pending`);
  return (data && typeof data === 'object') ? data : { count: 0, items: [] };
}

/** Users the signed-in caller has blocked. Auth-gated — returns
 *  { count: 0, items: [] } for anonymous callers. Drives Profile →
 *  Personal → Blocked list. */
export async function fetchBlockedUsers() {
  const data = await safeJson(`${API}/profile/blocks`);
  return (data && typeof data === 'object') ? data : { count: 0, items: [] };
}
/** Block another user — non-destructive, silent to the blocked user. */
export async function blockUser(userId) {
  return writeJson(`${API}/profile/blocks/${userId}`, {
    method: 'POST', credentials: 'same-origin'
  });
}
/** Reverse: remove a block. Idempotent — 0 rows deleted for an
 *  already-unblocked pair is not an error. */
export async function unblockUser(userId) {
  return writeJson(`${API}/profile/blocks/${userId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** Bulk-unblock — clears the caller's entire block list. Batch 355. */
export async function unblockAllUsers() {
  return writeJson(`${API}/profile/blocks`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** Seller posts a public reply (or clears with empty string) on one of
 *  their own received reviews. Only the review's toUserId can call this. */
export async function replyToReview(reviewId, reply) {
  return writeJson(`${API}/reviews/${reviewId}/reply`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reply })
  });
}
/** Remove a review the signed-in user authored. Server enforces
 *  fromUserId == caller — nobody else can delete a review. */
export async function deleteReview(reviewId) {
  return writeJson(`${API}/reviews/${reviewId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** Top sellers over the last N days (default 7) — powers the "This
 *  week" homepage rail per CSFloat Manual §4. Rolling window is more
 *  honest social proof than the all-time leaderboard (which is frozen
 *  in a few veteran sellers forever). Response is a list of
 *  { sellerUserId, displayName, avatarUrl, saleCount, totalRevenue,
 *    rating: {average, count} | null }. Capped server-side. */
export async function fetchTopSellers(days = 7, limit = 8) {
  const d = Math.max(1, Math.min(parseInt(days, 10) || 7, 90));
  const l = Math.max(1, Math.min(parseInt(limit, 10) || 8, 20));
  const data = await safeJson(`${API}/sellers/top?days=${d}&limit=${l}`);
  return Array.isArray(data) ? data : [];
}
/** Signed-in seller's progress toward the Verified Seller badge.
 *  Returns {verified, soldCount, salesNeeded, ratingAverage, ratingCount,
 *  ratingOk, thresholdSales, thresholdRating}. 401 for anon. */
export async function fetchMyVerificationProgress() {
  return safeJson(`${API}/sellers/me/verification-progress`);
}

/** Public seller search by display name (batch 666). Returns an array of
 *  {sellerUserId, displayName, avatarUrl, activeListings, soldCount}.
 *  Empty-query and <2-char queries short-circuit to [] without a round-trip.
 *  Backend caps response size at 25 rows; we cap `limit` at 25 client-side
 *  to match. */
export async function searchSellers(q, limit = 10) {
  const trimmed = (q || '').trim();
  if (trimmed.length < 2) return [];
  const l = Math.max(1, Math.min(parseInt(limit, 10) || 10, 25));
  const url = `${API}/sellers/search?q=${encodeURIComponent(trimmed)}&limit=${l}`;
  const data = await safeJson(url);
  return Array.isArray(data) ? data : [];
}

/** Signed-in seller's revenue + sold counts across lifetime / 30d / 7d
 *  windows. Drives the MyStall header summary chips. 401 for anon. */
export async function fetchMyStallEarnings() {
  return safeJson(`${API}/listings/my-stall/earnings`);
}

/** Bulk verified-seller lookup — drives the ✓ badge on marketplace
 *  cards next to the seller name. Returns `{userId: true}` (only
 *  verified ids emitted; missing ids = false). Public endpoint, cap
 *  200 input ids. Empty input → empty map without a round-trip. */
export async function fetchVerifiedSellers(userIds) {
  if (!Array.isArray(userIds) || userIds.length === 0) return {};
  const ids = [...new Set(userIds.filter(Boolean))].join(',');
  if (!ids) return {};
  const data = await safeJson(`${API}/sellers/verified?ids=${encodeURIComponent(ids)}`);
  return (data && typeof data === 'object') ? data : {};
}

/** Bulk Steam-avatar-URL lookup for the marketplace grid + ItemModal
 *  Active Listings rows. Returns `{sellerUserId: avatarUrl}` — sellers
 *  without a Steam profile photo on file are absent so the frontend
 *  falls through to the monogram. Public endpoint, cap 200 ids. */
export async function fetchSellerAvatars(userIds) {
  if (!Array.isArray(userIds) || userIds.length === 0) return {};
  const ids = [...new Set(userIds.filter(Boolean))].join(',');
  if (!ids) return {};
  const data = await safeJson(`${API}/sellers/avatars?ids=${encodeURIComponent(ids)}`);
  return (data && typeof data === 'object') ? data : {};
}

/** Bulk displayName lookup. Replaces per-seller `/api/listings/stall/{id}`
 *  fan-out in surfaces that only need the name. Public endpoint, cap 200 ids. */
export async function fetchSellerNames(userIds) {
  if (!Array.isArray(userIds) || userIds.length === 0) return {};
  const ids = [...new Set(userIds.filter(Boolean))].join(',');
  if (!ids) return {};
  const data = await safeJson(`${API}/sellers/names?ids=${encodeURIComponent(ids)}`);
  return (data && typeof data === 'object') ? data : {};
}

/** Batch 710 — bulk typical-ship-time lookup. Median over 90d per
 *  seller. Returns `{sellerUserId: msNumber}` with sellers below the
 *  3-sample noise floor absent. Public endpoint, cap 200 ids. */
export async function fetchSellerShipTimes(userIds) {
  if (!Array.isArray(userIds) || userIds.length === 0) return {};
  const ids = [...new Set(userIds.filter(Boolean))].join(',');
  if (!ids) return {};
  const data = await safeJson(`${API}/sellers/ship-times?ids=${encodeURIComponent(ids)}`);
  return (data && typeof data === 'object') ? data : {};
}
/** Newest active listings — powers the "Just listed" rail on the
 *  marketplace home. 20-row cap server-side, excludes hidden rows. */
export async function fetchJustListed() {
  const data = await safeJson(`${API}/listings/just-listed`);
  return Array.isArray(data) ? data : [];
}
/** Active BUY_NOW listings sorted by deepest % discount vs Steam. */
export async function fetchTopDeals() {
  const data = await safeJson(`${API}/listings/top-deals`);
  return Array.isArray(data) ? data : [];
}
/** Platform-wide "just sold" feed — social-proof ticker on the homepage.
 *  Named `fetchPlatformRecentSales` to keep the per-item `fetchRecentSales`
 *  unambiguous — they're structurally different payloads. */
export async function fetchPlatformRecentSales(limit) {
  const qs = limit ? `?limit=${limit}` : '';
  const data = await safeJson(`${API}/listings/recent-sales${qs}`);
  return Array.isArray(data) ? data : [];
}
/** Auctions ending within the next hour — powers the "Ending soon" rail
 *  on the marketplace home. Public endpoint, 20-row cap server-side. */
export async function fetchAuctionsEndingSoon(withinMs) {
  const qs = withinMs ? `?withinMs=${withinMs}` : '';
  const data = await safeJson(`${API}/listings/ending-soon${qs}`);
  return Array.isArray(data) ? data : [];
}
/** Last N actual sale rows for an item — powers the "Recent sales" strip
 *  on the ItemModal. Counterparties are NOT returned (privacy). `limit`
 *  defaults to 10 (CSFloat parity); pass up to 50 when the user clicks
 *  "Show more" on the strip for a deeper-history view. */
export async function fetchRecentSales(itemId, limit) {
  const qs = limit ? `?limit=${limit}` : '';
  const data = await safeJson(`${API}/items/${itemId}/recent-sales${qs}`);
  return Array.isArray(data) ? data : [];
}
/** Aggregate buy-order signals for an item: count of ACTIVE orders
 *  plus top-of-book (highest maxPrice). Drives the "N buyers want
 *  this" + "Best bid $X" chips on item detail. Aggregate only — no
 *  counterparty identities leaked. */
export async function fetchBuyOrderCountForItem(itemId) {
  const data = await safeJson(`${API}/buy-orders/count/item/${itemId}`);
  return {
    count:   Number(data?.count || 0),
    bestBid: data?.bestBid != null ? Number(data.bestBid) : null
  };
}

/** Batch 639 — top-N ACTIVE buy orders for this item (aggregate only,
 *  no counterparty identity). Drives the CSFloat-style Buy Orders
 *  table on the item detail modal. Returns an array of
 *  `{id, maxPrice, quantity, createdAt}`. */
export async function fetchBuyOrdersForItem(itemId, limit = 10) {
  if (!itemId) return [];
  const data = await safeJson(`${API}/buy-orders/for-item/${itemId}?limit=${limit}`);
  return Array.isArray(data) ? data : [];
}

/** Projected queue position for a hypothetical buy order at (itemId,
 *  maxPrice). Drives the "#N in queue" preview on the create form.
 *  Returns null when inputs are missing or invalid. */
export async function fetchBuyOrderProjectedPosition(itemId, maxPrice) {
  if (!itemId || !(parseFloat(maxPrice) > 0)) return null;
  const qs = `?itemId=${encodeURIComponent(itemId)}&maxPrice=${encodeURIComponent(maxPrice)}`;
  const data = await safeJson(`${API}/buy-orders/projected-position${qs}`);
  return data?.position != null ? Number(data.position) : null;
}
/** Public watcher count for an item — number of ACTIVE price alerts
 *  pinned to the item id. Drives the "N watching" chip on item
 *  detail. Aggregate only — no watcher identities exposed. */
export async function fetchWatchlistCountForItem(itemId) {
  const data = await safeJson(`${API}/watchlist/alerts/count/item/${itemId}`);
  return Number(data?.watching || 0);
}

/** Recent visible active listings from sellers the signed-in user
 *  follows — drives the home-page "From sellers you follow" rail.
 *  Empty array for signed-out users or users who follow nobody. */
export async function fetchFollowingFeed() {
  const data = await safeJson(`${API}/follows/feed`);
  return Array.isArray(data) ? data : [];
}

/** Bulk cart-row freshness probe. The cart is persisted client-side
 *  so a row can go stale between "add" and "checkout" — someone else
 *  buys it, the seller pulls it, or the seller re-prices it. This
 *  endpoint returns one record per requested id with `active` and the
 *  current `price`. Max 50 ids per call (server-capped). */
export async function checkListingsActive(ids) {
  if (!Array.isArray(ids) || ids.length === 0) return [];
  const rows = await writeJson(`${API}/listings/check-active`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids })
  });
  return Array.isArray(rows) ? rows : [];
}
/** Pending offer counts keyed by role — backs the nav offers badge.
 *  Returns { incomingPending, outgoingPending } — the nav surfaces
 *  incoming (actionable for the seller) as the primary count. */
export async function fetchOfferCounts() {
  const data = await safeJson(`${API}/offers/counts`);
  return data && typeof data === 'object' ? data : { incomingPending: 0, outgoingPending: 0 };
}
/** Current sitewide announcement banner or null. Polled every 2 minutes
 *  so a freshly posted ops message reaches browsers already on the page. */
export async function fetchAnnouncement() {
  const data = await safeJson(`${API}/announcement`);
  return data?.announcement ?? null;
}

// ── CSR ─────────────────────────────────────────────────────────
export async function csrCheck() {
  try {
    const r = await fetch(`${API}/csr/check`, { credentials: 'same-origin' });
    if (!r.ok) return { csr: false };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { csr: false };
  } catch (_) { return { csr: false }; }
}
export async function csrStats() { return (await safeJson(`${API}/csr/stats`)) || {}; }
export async function csrLookup(q) {
  try {
    const r = await fetch(`${API}/csr/users/lookup?q=${encodeURIComponent(q)}`, { credentials: 'same-origin' });
    if (!r.ok) return { matches: [] };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { matches: [] };
  } catch (_) { return { matches: [] }; }
}
export async function csrTickets(status, search) {
  // Batch 581 — mirror admin ticket search: free-text narrows the
  // CSR queue by subject / username / category.
  const qp = new URLSearchParams();
  if (status) qp.set('status', status);
  if (search) qp.set('search', search);
  const suffix = qp.toString() ? '?' + qp.toString() : '';
  const data = await safeJson(`${API}/csr/tickets${suffix}`);
  return Array.isArray(data) ? data : [];
}
export async function csrTicket(id) { return safeJson(`${API}/csr/tickets/${id}`); }
export async function csrTicketReply(id, body) {
  return writeJson(`${API}/csr/tickets/${id}/reply`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
}
export async function csrCloseTicket(id) {
  return writeJson(`${API}/csr/tickets/${id}/close`, { method: 'POST', credentials: 'same-origin' });
}
export async function csrGoodwill(userId, amount, note) {
  return writeJson(`${API}/csr/users/${userId}/goodwill`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, note })
  });
}
export async function csrFlagListing(id, reason) {
  return writeJson(`${API}/csr/listings/${id}/flag`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}

// ── Cart (bulk checkout) ────────────────────────────────────────
/** Bulk-buy the listings in `listingIds`. Optional `expectedPrices`
 *  map (`{listingId: price}`) pins what the client saw in the cart
 *  confirm dialog — server rejects rows where the live price has
 *  drifted (seller edit mid-click) with code `PRICE_CHANGED` instead
 *  of debiting at the surprise amount. */
export async function checkoutCart(listingIds, expectedPrices) {
  const body = { listingIds };
  if (expectedPrices && typeof expectedPrices === 'object') {
    body.expectedPrices = expectedPrices;
  }
  return writeJson(`${API}/cart/checkout`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

// ── Trades (escrow state machine) ───────────────────────────────
export async function fetchTrades() {
  const data = await safeJson(`${API}/trades`);
  return Array.isArray(data) ? data : [];
}

/** Variant that also returns the server's true trade count via the
 *  `X-Total-Count` header — feeds the "Showing most recent 200 of N"
 *  overflow banner in the Profile → Trades tab. Leaves the array
 *  contract of plain `fetchTrades()` intact for other callers. */
export async function fetchTradesWithTotal() {
  try {
    const res = await fetch(`${API}/trades`, { credentials: 'same-origin' });
    if (!res.ok) return { items: [], total: 0 };
    const items = await res.json();
    const totalHeader = res.headers.get('X-Total-Count');
    const parsed = totalHeader != null ? parseInt(totalHeader, 10) : NaN;
    const fallback = Array.isArray(items) ? items.length : 0;
    return {
      items: Array.isArray(items) ? items : [],
      total: Number.isFinite(parsed) ? parsed : fallback
    };
  } catch {
    return { items: [], total: 0 };
  }
}
export async function tradeAccept(id) {
  return writeJson(`${API}/trades/${id}/accept`, { method: 'POST', credentials: 'same-origin' });
}
/** Mark the seller's side of a trade as sent (batch 773). Optionally
 *  attaches the Steam trade-offer URL so the buyer can open it in one
 *  click from their Trades tab. Omit the URL to preserve the legacy
 *  no-body POST contract. */
export async function tradeMarkSent(id, tradeOfferUrl) {
  const body = (tradeOfferUrl && tradeOfferUrl.trim())
    ? JSON.stringify({ tradeOfferUrl: tradeOfferUrl.trim() })
    : null;
  return writeJson(`${API}/trades/${id}/sent`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body
  });
}
export async function tradeConfirm(id) {
  return writeJson(`${API}/trades/${id}/confirm`, { method: 'POST', credentials: 'same-origin' });
}
export async function fetchTradeMessages(tradeId) {
  const data = await safeJson(`${API}/trades/${tradeId}/messages`);
  return Array.isArray(data) ? data : [];
}
export async function postTradeMessage(tradeId, body) {
  return writeJson(`${API}/trades/${tradeId}/messages`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
}

export async function tradeDispute(id, reason) {
  return writeJson(`${API}/trades/${id}/dispute`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
export async function tradeCancel(id, reason) {
  return writeJson(`${API}/trades/${id}/cancel`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}

// ── Trade Protection (optional paid buyer add-on) ───────────────
/** Public quote for the Trade Protection fee on a given trade price.
 *  Returns `{ price, fee, ratePercent, minFee, coverageAmount }` so the
 *  opt-in panel can render the exact fee before the buyer commits.
 *  Read-only — uses safeJson, which returns null on any non-2xx so the
 *  panel can fall back to a client-side 2% / $0.25-floor compute. */
export async function fetchTradeProtectionQuote(price) {
  return safeJson(`${API}/trade-protection/quote?price=${encodeURIComponent(price)}`);
}
/** Current protection state for a trade — `{ tradeId, protected, protection }`.
 *  Participant-only; safeJson returns null on 401/403/404 so the caller
 *  treats "couldn't read" as "not protected" without throwing. */
export async function fetchTradeProtection(tradeId) {
  return safeJson(`${API}/trades/${tradeId}/protection`);
}
/** Enable Trade Protection on a trade (buyer-only). Returns the created
 *  TradeProtection on success, or `{ error, code }` on failure — the
 *  panel maps codes (INSUFFICIENT_BALANCE, NO_WALLET, WALLET_FROZEN,
 *  PROTECTION_EXISTS, TRADE_NOT_PROTECTABLE) to friendly inline copy. */
export async function enableTradeProtection(tradeId) {
  return writeJson(`${API}/trades/${tradeId}/protection`, {
    method: 'POST', credentials: 'same-origin'
  });
}

// ── 2FA + email (profile-level hardening) ──────────────────────
export async function setEmail(email) {
  return writeJson(`${API}/profile/email`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email })
  });
}
/** Set or clear the user's Steam trade offer URL. Validated server-side;
 *  pass '' to clear. Used by Profile → Personal Info and nudged on any
 *  active trade row where the counterparty has no URL yet. */
export async function setTradeUrl(tradeUrl) {
  return writeJson(`${API}/profile/trade-url`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tradeUrl })
  });
}
/** Set or clear the seller's self-written public stall bio. Passing
 *  empty string / null clears it. Sanitised server-side (HTML-stripped,
 *  500-char cap). Surfaces on the public /stall/{id} page. */
export async function setStallBio(bio) {
  return writeJson(`${API}/profile/stall-bio`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ bio: bio ?? '' })
  });
}
export async function verifyEmail(token) {
  return writeJson(`${API}/profile/email/verify`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token })
  });
}
/** Regenerate + resend the email-verification token. */
export async function resendEmailVerification() {
  return writeJson(`${API}/profile/email/resend`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function enroll2fa() {
  return writeJson(`${API}/profile/2fa/enroll`, { method: 'POST', credentials: 'same-origin' });
}
export async function confirm2fa(code) {
  return writeJson(`${API}/profile/2fa/confirm`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code })
  });
}
export async function disable2fa(code) {
  return writeJson(`${API}/profile/2fa/disable`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code })
  });
}
/** Regenerate the user's 2FA backup codes — requires a current TOTP
 *  code. Returns a plaintext list shown once, never retrievable again. */
export async function regenerate2faBackupCodes(code) {
  return writeJson(`${API}/profile/2fa/regenerate-codes`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code })
  });
}
/** How many unused backup codes the user has left. Opaque count — the
 *  codes themselves are never returned by this endpoint. */
export async function fetch2faRecoveryStatus() {
  try {
    const r = await fetch(`${API}/profile/2fa/recovery-status`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}

// ── Profile aggregate ───────────────────────────────────────────
export async function fetchProfile() {
  try {
    const r = await fetch(`${API}/profile/me`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}
/** Count of things that need the user's attention — unanswered trades,
 *  offers waiting on them, disputed trades. Drives the nav-avatar red
 *  dot. Returns null on 401 (anonymous) so the caller can short-circuit
 *  without rendering a badge. */
export async function fetchPendingActions() {
  try {
    const r = await fetch(`${API}/profile/pending-actions`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}
/** Toggle a "helpful" upvote on a review. Single POST — the server
 *  inserts the vote if it doesn't exist, deletes it if it does.
 *  Returns `{helpfulCount, viewerHasVoted}` — the authoritative post-
 *  toggle state so the UI can replace its optimistic figures. */
export async function toggleReviewHelpful(reviewId) {
  return writeJson(`${API}/reviews/${reviewId}/helpful`, {
    method: 'POST', credentials: 'same-origin'
  });
}
/** Server-side watchlist (cross-device sync). The localStorage cache
 *  is still maintained by the App, but these helpers let the signed-in
 *  session persist + read the authoritative set from the server. */
export async function fetchWatchlist() {
  try {
    const r = await fetch(`${API}/watchlist`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}
export async function starItem(itemId) {
  return writeJson(`${API}/watchlist/${itemId}`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function unstarItem(itemId) {
  return writeJson(`${API}/watchlist/${itemId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** Clear every starred row in one call. Returns `{cleared:N}`. */
export async function clearWatchlist() {
  return writeJson(`${API}/watchlist`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** One-shot bridge — POST the localStorage ids to merge into the
 *  server set on first sign-in after this feature ships. Returns the
 *  authoritative post-merge list. */
export async function bulkMergeWatchlist(ids) {
  return writeJson(`${API}/watchlist/bulk`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids: Array.isArray(ids) ? ids : [] })
  });
}
/** Server-side cart (cross-device sync) — listing-id only. The
 *  localStorage `sb_cart` cache stays as the offline source for the
 *  per-row metadata (name, price snapshot, thumb), but the set of
 *  listing ids in it is shadowed by the server. */
export async function fetchCartIds() {
  try {
    const r = await fetch(`${API}/cart`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}
export async function addCartItem(listingId) {
  return writeJson(`${API}/cart/${listingId}`, {
    method: 'POST', credentials: 'same-origin'
  });
}
export async function removeCartItem(listingId) {
  return writeJson(`${API}/cart/${listingId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
export async function clearServerCart() {
  return writeJson(`${API}/cart`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
export async function bulkMergeCart(ids) {
  return writeJson(`${API}/cart/bulk`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids: Array.isArray(ids) ? ids : [] })
  });
}
/** Server-side saved-search persistence (cross-device sync). The
 *  localStorage `sb_saved_searches` array is still kept as the
 *  offline cache; the server set is the source of truth for
 *  signed-in users. */
export async function fetchSavedSearches() {
  try {
    const r = await fetch(`${API}/saved-searches`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}
export async function upsertSavedSearch(entry) {
  return writeJson(`${API}/saved-searches`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(entry || {})
  });
}
export async function deleteSavedSearchById(id) {
  return writeJson(`${API}/saved-searches/${id}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
/** Bulk-delete — wipes every saved search the user owns. Parity with
 *  the watchlist "Clear all" + follow "Unfollow all" affordances. */
export async function deleteAllSavedSearches() {
  return writeJson(`${API}/saved-searches`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}
export async function bulkMergeSavedSearches(entries) {
  return writeJson(`${API}/saved-searches/bulk`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ entries: Array.isArray(entries) ? entries : [] })
  });
}

// ── Steam inventory + sync ──────────────────────────────────────
export async function fetchSteamInventory() {
  try {
    const r = await fetch(`${API}/steam/inventory`, { credentials: 'same-origin' });
    if (!r.ok) return { items: [], count: 0 };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { items: [], count: 0 };
  } catch (_) { return { items: [], count: 0 }; }
}

export async function syncSteam() {
  try {
    const r = await fetch(`${API}/steam/sync`, { method: 'POST', credentials: 'same-origin' });
    if (!r.ok) return { ok: false };
    const data = await r.json();
    return (data && typeof data === 'object') ? data : { ok: false };
  } catch (_) { return { ok: false }; }
}

export async function listFromSteam(assetId, price, opts = {}) {
  const body = { assetId, price };
  if (opts.listingType)   body.listingType = opts.listingType;
  if (opts.durationHours) body.durationHours = opts.durationHours;
  if (opts.description)   body.description = opts.description;
  if (opts.buyNowPrice != null) body.buyNowPrice = opts.buyNowPrice;
  // Batch 646 — auto-accept threshold on first-list (matches relistItem).
  if (opts.maxDiscount != null) body.maxDiscount = opts.maxDiscount;
  return writeJson(`${API}/steam/list`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

/** Auction Buy-Now — closes the auction instantly at the seller's
 *  pre-set `buyNowPrice` and awards the listing to the caller. Only
 *  valid on AUCTION listings that have a buyNowPrice set. Returns
 *  400 with `NO_BUY_NOW` if the auction doesn't support Buy Now.
 *  Optional `expectedPrice` pins what the user saw; server rejects
 *  with `PRICE_CHANGED` if the seller edited the Buy-Now price
 *  between modal render and click (batch 961). */
export async function buyNowAuction(listingId, expectedPrice) {
  const body = expectedPrice != null ? { expectedPrice: String(expectedPrice) } : null;
  return writeJson(`${API}/bids/listing/${listingId}/buy-now`, {
    method: 'POST', credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined
  });
}

/** Bulk-list multiple Steam inventory items at a single flat price
 *  (batch 370). Per-asset failures are returned in the `failed` array
 *  without aborting the batch. BUY_NOW only — auction duration semantics
 *  on a batch get weird (single expiry for 8 auctions?).
 *  Batch 648 — optional `maxDiscount` (0..1) applies uniformly to
 *  every listing in the batch. Null = no auto-accept. */
export async function bulkListFromSteam(assetIds, price, opts = {}) {
  const body = { assetIds: Array.isArray(assetIds) ? assetIds : [], price };
  if (opts.maxDiscount != null) body.maxDiscount = opts.maxDiscount;
  return writeJson(`${API}/steam/list-bulk`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

// ── Support tickets ─────────────────────────────────────────────
export async function fetchSupportTickets() {
  const data = await safeJson(`${API}/support/tickets`);
  return Array.isArray(data) ? data : [];
}

export async function fetchSupportTicket(id) {
  return safeJson(`${API}/support/tickets/${id}`);
}

export async function createSupportTicket(payload) {
  return writeJson(`${API}/support/tickets`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export async function replySupportTicket(id, body) {
  return writeJson(`${API}/support/tickets/${id}/reply`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
}

export async function resolveSupportTicket(id) {
  return writeJson(`${API}/support/tickets/${id}/resolve`, {
    method: 'POST', credentials: 'same-origin'
  });
}

// Reopen a RESOLVED ticket (batch 858) — flips status to WAITING_STAFF
// so the same thread continues instead of forcing the user to open a
// brand-new ticket with no context.
export async function reopenSupportTicket(id) {
  return writeJson(`${API}/support/tickets/${id}/reopen`, {
    method: 'POST', credentials: 'same-origin'
  });
}

// ── API keys ────────────────────────────────────────────────────
export async function fetchApiKeys() {
  const data = await safeJson(`${API}/api-keys`);
  return Array.isArray(data) ? data : [];
}

export async function createApiKey(label, scope) {
  const body = { label };
  if (scope === 'RO' || scope === 'RW') body.scope = scope;
  return writeJson(`${API}/api-keys`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function revokeApiKey(id) {
  return writeJson(`${API}/api-keys/${id}`, { method: 'DELETE', credentials: 'same-origin' });
}

/** Batch 705 — security panic button. Revokes every non-revoked key
 *  on the caller's account in one round-trip. Idempotent — zero-key
 *  callers get `{revoked: 0}` rather than a 404. */
export async function revokeAllApiKeys() {
  return writeJson(`${API}/api-keys`, { method: 'DELETE', credentials: 'same-origin' });
}

// ── My Stall ────────────────────────────────────────────────────
export async function updateStallListing(id, patch) {
  return writeJson(`${API}/listings/${id}/stall`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  });
}

export async function setAwayMode(hidden, untilEpochMs) {
  const body = { hidden };
  if (untilEpochMs != null && Number.isFinite(Number(untilEpochMs))) {
    body.until = Number(untilEpochMs);
  }
  return writeJson(`${API}/listings/away`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}
/** Read the current vacation-mode state — `{hidden, until}`. Drives
 *  the My Stall toolbar's "scheduled return" chip on first paint so a
 *  returning seller sees their resume time without flipping the toggle. */
export async function fetchAwayMode() {
  try {
    const r = await fetch(`${API}/listings/away`, { credentials: 'same-origin' });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}

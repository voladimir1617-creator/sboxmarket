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

async function safeJson(url, opts) {
  try {
    const r = await fetch(url, opts);
    if (!r.ok) { console.warn(`[${url}] HTTP ${r.status}`); return null; }
    return await r.json();
  } catch (e) {
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
      const msg = body?.error || body?.message || `Request failed (HTTP ${r.status})`;
      const code = body?.code || 'SERVER_ERROR';
      return { error: msg, code };
    }
    return body;
  } catch (e) {
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
  const data = await safeJson(`${API}/items/${itemId}/history`);
  return Array.isArray(data) ? data : [];
}

/** Single item lookup used by WatchlistModal to surface starred items
 *  even when there are no active listings in the marketplace. */
export async function fetchItem(itemId) {
  return (await safeJson(`${API}/items/${itemId}`)) || null;
}

export async function fetchSimilar(itemId) {
  const data = await safeJson(`${API}/items/${itemId}/similar`);
  return Array.isArray(data) ? data : [];
}

export async function buyListing(id) {
  return writeJson(`${API}/listings/${id}/buy`, { method: 'POST', credentials: 'same-origin' });
}

/** Fetch all listings for a specific item by its item ID.
 *  Uses the dedicated /api/listings/item/{id} endpoint instead of the
 *  general /api/listings query which doesn't support itemId filtering. */
export async function fetchListingsForItem(itemId) {
  const data = await safeJson(`${API}/listings/item/${itemId}`);
  return Array.isArray(data) ? data : [];
}

export async function fetchInventory() {
  const data = await safeJson(`${API}/listings/inventory`);
  return Array.isArray(data) ? data : [];
}

export async function fetchMyStall() {
  const data = await safeJson(`${API}/listings/my-stall`);
  return Array.isArray(data) ? data : [];
}

export async function fetchPublicStall(userId) {
  return safeJson(`${API}/listings/stall/${userId}`);
}

export async function relistItem(listingId, price) {
  return writeJson(`${API}/listings/sell`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingId, price })
  });
}

export async function cancelListing(listingId) {
  return writeJson(`${API}/listings/${listingId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
}

// ── Wallet ──────────────────────────────────────────────────────
export async function fetchWallet() {
  const r = await fetch(`${API}/wallet`, { credentials: 'same-origin' });
  if (!r.ok) return null;
  return r.json();
}

export async function fetchTransactions() {
  const r = await fetch(`${API}/wallet/transactions`, { credentials: 'same-origin' });
  if (!r.ok) return [];
  return r.json();
}

export async function depositFunds(amount) {
  return writeJson(`${API}/wallet/deposit`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
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
    if (r.status === 401) return null;
    if (!r.ok) return null;
    return r.json();
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

export async function fetchOutgoingOffers() {
  const data = await safeJson(`${API}/offers/outgoing`);
  return Array.isArray(data) ? data : [];
}

export async function makeOffer(listingId, amount) {
  return writeJson(`${API}/offers`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingId, amount })
  });
}

export async function acceptOffer(offerId) {
  return writeJson(`${API}/offers/${offerId}/accept`, { method: 'POST', credentials: 'same-origin' });
}

export async function rejectOffer(offerId) {
  return writeJson(`${API}/offers/${offerId}/reject`, { method: 'POST', credentials: 'same-origin' });
}

export async function cancelOffer(offerId) {
  return writeJson(`${API}/offers/${offerId}`, { method: 'DELETE', credentials: 'same-origin' });
}

export async function counterOffer(offerId, amount) {
  return writeJson(`${API}/offers/${offerId}/counter`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
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

// ── Notifications ───────────────────────────────────────────────
export async function fetchNotifications() {
  const r = await fetch(`${API}/notifications`, { credentials: 'same-origin' });
  if (!r.ok) return { items: [], unread: 0 };
  return r.json();
}

export async function markNotificationRead(id) {
  return fetch(`${API}/notifications/${id}/read`, { method: 'POST', credentials: 'same-origin' });
}

export async function markAllNotificationsRead() {
  return fetch(`${API}/notifications/read-all`, { method: 'POST', credentials: 'same-origin' });
}

// ── Database ────────────────────────────────────────────────────
export async function fetchDatabase(params = {}) {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== null && v !== undefined && v !== '') q.append(k, v);
  });
  const r = await fetch(`${API}/database?${q}`);
  if (!r.ok) return { items: [], total: 0, indexed: 0 };
  return r.json();
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

export async function fetchLoadout(id) {
  return safeJson(`${API}/loadouts/${id}`);
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

export async function generateLoadout(id, budget) {
  return writeJson(`${API}/loadouts/${id}/generate`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ budget })
  });
}

export async function favoriteLoadout(id) {
  return fetch(`${API}/loadouts/${id}/favorite`, { method: 'POST', credentials: 'same-origin' });
}

export async function deleteLoadout(id) {
  return fetch(`${API}/loadouts/${id}`, { method: 'DELETE', credentials: 'same-origin' });
}

// ── Admin ───────────────────────────────────────────────────────
export async function adminCheck() {
  const r = await fetch(`${API}/admin/check`, { credentials: 'same-origin' });
  if (!r.ok) return { admin: false };
  return r.json();
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
export async function adminUsers(search) {
  const q = search ? '?search=' + encodeURIComponent(search) : '';
  const data = await safeJson(`${API}/admin/users${q}`);
  return Array.isArray(data) ? data : [];
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
export async function adminGrant(id) {
  return writeJson(`${API}/admin/users/${id}/grant-admin`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminRevoke(id) {
  return writeJson(`${API}/admin/users/${id}/revoke-admin`, { method: 'POST', credentials: 'same-origin' });
}
export async function adminCreditWallet(id, amount, note) {
  return writeJson(`${API}/admin/users/${id}/credit`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, note })
  });
}
export async function adminRemoveListing(id, reason) {
  return writeJson(`${API}/admin/listings/${id}/remove`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
}
export async function adminTickets(status) {
  const q = status ? '?status=' + encodeURIComponent(status) : '';
  const data = await safeJson(`${API}/admin/tickets${q}`);
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
  const r = await fetch(`${API}/admin/sync-scmm`, {
    method: 'POST', credentials: 'same-origin'
  });
  if (!r.ok) return { error: `HTTP ${r.status}` };
  return r.json();
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
  const data = await safeJson(`${API}/reviews/user/${userId}`);
  return Array.isArray(data) ? data : [];
}
export async function fetchReviewSummary(userId) {
  return (await safeJson(`${API}/reviews/user/${userId}/summary`)) || { count: 0, average: null };
}

// ── CSR ─────────────────────────────────────────────────────────
export async function csrCheck() {
  const r = await fetch(`${API}/csr/check`, { credentials: 'same-origin' });
  if (!r.ok) return { csr: false };
  return r.json();
}
export async function csrStats() { return (await safeJson(`${API}/csr/stats`)) || {}; }
export async function csrLookup(q) {
  const r = await fetch(`${API}/csr/users/lookup?q=${encodeURIComponent(q)}`, { credentials: 'same-origin' });
  if (!r.ok) return { matches: [] };
  return r.json();
}
export async function csrTickets(status) {
  const q = status ? '?status=' + encodeURIComponent(status) : '';
  const data = await safeJson(`${API}/csr/tickets${q}`);
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
export async function checkoutCart(listingIds) {
  return writeJson(`${API}/cart/checkout`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingIds })
  });
}

// ── Trades (escrow state machine) ───────────────────────────────
export async function fetchTrades() {
  const data = await safeJson(`${API}/trades`);
  return Array.isArray(data) ? data : [];
}
export async function tradeAccept(id) {
  return writeJson(`${API}/trades/${id}/accept`, { method: 'POST', credentials: 'same-origin' });
}
export async function tradeMarkSent(id) {
  return writeJson(`${API}/trades/${id}/sent`, { method: 'POST', credentials: 'same-origin' });
}
export async function tradeConfirm(id) {
  return writeJson(`${API}/trades/${id}/confirm`, { method: 'POST', credentials: 'same-origin' });
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

// ── 2FA + email (profile-level hardening) ──────────────────────
export async function setEmail(email) {
  return writeJson(`${API}/profile/email`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email })
  });
}
export async function verifyEmail(token) {
  return writeJson(`${API}/profile/email/verify`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token })
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

// ── Profile aggregate ───────────────────────────────────────────
export async function fetchProfile() {
  const r = await fetch(`${API}/profile/me`, { credentials: 'same-origin' });
  if (!r.ok) return null;
  return r.json();
}

// ── Steam inventory + sync ──────────────────────────────────────
export async function fetchSteamInventory() {
  const r = await fetch(`${API}/steam/inventory`, { credentials: 'same-origin' });
  if (!r.ok) return { items: [], count: 0 };
  return r.json();
}

export async function syncSteam() {
  const r = await fetch(`${API}/steam/sync`, { method: 'POST', credentials: 'same-origin' });
  if (!r.ok) return { ok: false };
  return r.json();
}

export async function listFromSteam(assetId, price) {
  return writeJson(`${API}/steam/list`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assetId, price })
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

// ── API keys ────────────────────────────────────────────────────
export async function fetchApiKeys() {
  const data = await safeJson(`${API}/api-keys`);
  return Array.isArray(data) ? data : [];
}

export async function createApiKey(label) {
  return writeJson(`${API}/api-keys`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label })
  });
}

export async function revokeApiKey(id) {
  return writeJson(`${API}/api-keys/${id}`, { method: 'DELETE', credentials: 'same-origin' });
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

export async function setAwayMode(hidden) {
  return writeJson(`${API}/listings/away`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ hidden })
  });
}

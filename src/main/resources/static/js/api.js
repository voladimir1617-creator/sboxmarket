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
    const m = document.cookie.match(/(?:^|; )sbox_csrf=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
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
  const r = await fetch(`${API}/listings/${id}/buy`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
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
  const r = await fetch(`${API}/listings/sell`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingId, price })
  });
  return r.json();
}

export async function cancelListing(listingId) {
  const r = await fetch(`${API}/listings/${listingId}`, {
    method: 'DELETE', credentials: 'same-origin'
  });
  return r.json();
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
  const r = await fetch(`${API}/wallet/deposit`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
  });
  return r.json();
}

export async function withdrawFunds(amount, destination, totpCode) {
  const r = await fetch(`${API}/wallet/withdraw`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, destination, totpCode })
  });
  return r.json();
}

export async function confirmDeposit(sessionId) {
  const r = await fetch(`${API}/wallet/confirm-deposit?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'POST', credentials: 'same-origin'
  });
  return r.json();
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
  const r = await fetch(`${API}/offers`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingId, amount })
  });
  return r.json();
}

export async function acceptOffer(offerId) {
  const r = await fetch(`${API}/offers/${offerId}/accept`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}

export async function rejectOffer(offerId) {
  const r = await fetch(`${API}/offers/${offerId}/reject`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}

export async function cancelOffer(offerId) {
  const r = await fetch(`${API}/offers/${offerId}`, { method: 'DELETE', credentials: 'same-origin' });
  return r.json();
}

export async function counterOffer(offerId, amount) {
  const r = await fetch(`${API}/offers/${offerId}/counter`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
  });
  return r.json();
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
  const r = await fetch(`${API}/buy-orders`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  return r.json();
}

export async function deleteBuyOrder(id) {
  const r = await fetch(`${API}/buy-orders/${id}`, { method: 'DELETE', credentials: 'same-origin' });
  return r.json();
}

// ── Bids / Auctions ─────────────────────────────────────────────
export async function placeBid(listingId, amount, maxAmount) {
  const r = await fetch(`${API}/bids`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingId, amount, maxAmount })
  });
  return r.json();
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
  const r = await fetch(`${API}/loadouts`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  return r.json();
}

export async function setLoadoutSlot(id, slot, itemId) {
  const r = await fetch(`${API}/loadouts/${id}/slot/${encodeURIComponent(slot)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ itemId })
  });
  return r.json();
}

export async function generateLoadout(id, budget) {
  const r = await fetch(`${API}/loadouts/${id}/generate`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ budget })
  });
  return r.json();
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
  const r = await fetch(`${API}/admin/withdrawals/${id}/approve`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payoutRef })
  });
  return r.json();
}
export async function adminRejectWithdrawal(id, reason) {
  const r = await fetch(`${API}/admin/withdrawals/${id}/reject`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}
export async function adminUsers(search) {
  const q = search ? '?search=' + encodeURIComponent(search) : '';
  const data = await safeJson(`${API}/admin/users${q}`);
  return Array.isArray(data) ? data : [];
}
export async function adminBanUser(id, reason) {
  const r = await fetch(`${API}/admin/users/${id}/ban`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}
export async function adminUnbanUser(id) {
  const r = await fetch(`${API}/admin/users/${id}/unban`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function adminGrant(id) {
  const r = await fetch(`${API}/admin/users/${id}/grant-admin`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function adminRevoke(id) {
  const r = await fetch(`${API}/admin/users/${id}/revoke-admin`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function adminCreditWallet(id, amount, note) {
  const r = await fetch(`${API}/admin/users/${id}/credit`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, note })
  });
  return r.json();
}
export async function adminRemoveListing(id, reason) {
  const r = await fetch(`${API}/admin/listings/${id}/remove`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}
export async function adminTickets(status) {
  const q = status ? '?status=' + encodeURIComponent(status) : '';
  const data = await safeJson(`${API}/admin/tickets${q}`);
  return Array.isArray(data) ? data : [];
}
export async function adminTicket(id) { return safeJson(`${API}/admin/tickets/${id}`); }
export async function adminTicketReply(id, body) {
  const r = await fetch(`${API}/admin/tickets/${id}/reply`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
  return r.json();
}
export async function adminCloseTicket(id) {
  const r = await fetch(`${API}/admin/tickets/${id}/close`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function adminTrades(state = 'ALL') {
  const data = await safeJson(`${API}/admin/trades?state=${encodeURIComponent(state)}`);
  return Array.isArray(data) ? data : [];
}
export async function adminReleaseTrade(id, reason) {
  const r = await fetch(`${API}/admin/trades/${id}/release`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}
export async function adminCancelTrade(id, reason) {
  const r = await fetch(`${API}/admin/trades/${id}/cancel`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
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
  const r = await fetch(`${API}/admin/deposits/${id}/refund`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
  });
  return r.json();
}
export async function adminSimulateListings(count = 20) {
  const r = await fetch(`${API}/admin/simulate/listings`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ count })
  });
  return r.json();
}
export async function adminClearSimulated() {
  const r = await fetch(`${API}/admin/simulate/clear`, {
    method: 'POST', credentials: 'same-origin'
  });
  return r.json();
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
  const r = await fetch(`${API}/reviews`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tradeId, rating, comment })
  });
  return r.json();
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
  const r = await fetch(`${API}/csr/tickets/${id}/reply`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
  return r.json();
}
export async function csrCloseTicket(id) {
  const r = await fetch(`${API}/csr/tickets/${id}/close`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function csrGoodwill(userId, amount, note) {
  const r = await fetch(`${API}/csr/users/${userId}/goodwill`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, note })
  });
  return r.json();
}
export async function csrFlagListing(id, reason) {
  const r = await fetch(`${API}/csr/listings/${id}/flag`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}

// ── Cart (bulk checkout) ────────────────────────────────────────
export async function checkoutCart(listingIds) {
  const r = await fetch(`${API}/cart/checkout`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ listingIds })
  });
  return r.json();
}

// ── Trades (escrow state machine) ───────────────────────────────
export async function fetchTrades() {
  const data = await safeJson(`${API}/trades`);
  return Array.isArray(data) ? data : [];
}
export async function tradeAccept(id) {
  const r = await fetch(`${API}/trades/${id}/accept`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function tradeMarkSent(id) {
  const r = await fetch(`${API}/trades/${id}/sent`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function tradeConfirm(id) {
  const r = await fetch(`${API}/trades/${id}/confirm`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function tradeDispute(id, reason) {
  const r = await fetch(`${API}/trades/${id}/dispute`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}
export async function tradeCancel(id, reason) {
  const r = await fetch(`${API}/trades/${id}/cancel`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason })
  });
  return r.json();
}

// ── 2FA + email (profile-level hardening) ──────────────────────
export async function setEmail(email) {
  const r = await fetch(`${API}/profile/email`, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email })
  });
  return r.json();
}
export async function verifyEmail(token) {
  const r = await fetch(`${API}/profile/email/verify`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token })
  });
  return r.json();
}
export async function enroll2fa() {
  const r = await fetch(`${API}/profile/2fa/enroll`, { method: 'POST', credentials: 'same-origin' });
  return r.json();
}
export async function confirm2fa(code) {
  const r = await fetch(`${API}/profile/2fa/confirm`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code })
  });
  return r.json();
}
export async function disable2fa(code) {
  const r = await fetch(`${API}/profile/2fa/disable`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code })
  });
  return r.json();
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
  const r = await fetch(`${API}/steam/list`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assetId, price })
  });
  return r.json();
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
  const r = await fetch(`${API}/support/tickets`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  return r.json();
}

export async function replySupportTicket(id, body) {
  const r = await fetch(`${API}/support/tickets/${id}/reply`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
  return r.json();
}

export async function resolveSupportTicket(id) {
  const r = await fetch(`${API}/support/tickets/${id}/resolve`, {
    method: 'POST', credentials: 'same-origin'
  });
  return r.json();
}

// ── API keys ────────────────────────────────────────────────────
export async function fetchApiKeys() {
  const data = await safeJson(`${API}/api-keys`);
  return Array.isArray(data) ? data : [];
}

export async function createApiKey(label) {
  const r = await fetch(`${API}/api-keys`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label })
  });
  return r.json();
}

export async function revokeApiKey(id) {
  const r = await fetch(`${API}/api-keys/${id}`, { method: 'DELETE', credentials: 'same-origin' });
  return r.json();
}

// ── My Stall ────────────────────────────────────────────────────
export async function updateStallListing(id, patch) {
  const r = await fetch(`${API}/listings/${id}/stall`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  });
  return r.json();
}

export async function setAwayMode(hidden) {
  const r = await fetch(`${API}/listings/away`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ hidden })
  });
  return r.json();
}

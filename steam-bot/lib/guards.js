'use strict';

/*
 * Input checks and small pure helpers shared by the real sidecar (index.js via
 * bot-core.js) and the fake Steam (fake-bot.js). No dependencies, so the fake
 * and the tests run without npm install.
 */

const crypto = require('crypto');

const STEAM_ID_BASE = 76561197960265728n;

/** Steam caps a trade-offer message at 128 characters and cuts the rest off. */
const MAX_OFFER_MESSAGE = 128;

/** One offer carries one listing's item today; this leaves room and still
 *  stops a runaway caller from building an offer that empties the bot. */
const MAX_ASSETS_PER_OFFER = 20;

const ASSET_ID = /^\d{1,20}$/;
const OFFER_ID = /^\d{1,20}$/;

/**
 * Parse a Steam trade URL into { partner (32-bit account id), steamId64, token }.
 * Only https://steamcommunity.com/tradeoffer/new/?partner=N&token=T is accepted:
 * anything else is either a typo or someone steering the bot at a lookalike.
 */
function parseTradeUrl(raw) {
  if (typeof raw !== 'string' || raw.length > 512) return null;
  let url;
  try { url = new URL(raw.trim()); } catch (e) { return null; }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  if (url.hostname !== 'steamcommunity.com' && url.hostname !== 'www.steamcommunity.com') return null;
  if (url.username || url.password || url.port) return null;
  if (!/^\/tradeoffer\/new\/?$/.test(url.pathname)) return null;
  const partner = url.searchParams.get('partner');
  const token = url.searchParams.get('token');
  if (!partner || !/^\d{1,10}$/.test(partner) || BigInt(partner) === 0n || BigInt(partner) > 0xFFFFFFFFn) return null;
  if (!token || !/^[A-Za-z0-9_-]{1,32}$/.test(token)) return null;
  return { partner, steamId64: (STEAM_ID_BASE + BigInt(partner)).toString(), token };
}

/** The trade URL with its token blanked, for logs. The token lets anyone send
 *  that user offers, so it never goes into a log line. */
function redactTradeUrl(raw) {
  return String(raw == null ? '' : raw).replace(/([?&]token=)[^&#\s]*/gi, '$1***');
}

/**
 * Fit the offer message into Steam's 128 characters without losing the
 * security code. The backend writes "<text> · code ABC123"; the seller is told
 * to check that code before accepting, so if the text runs long it is the TEXT
 * that gets shortened, never the code at the end. Control characters and line
 * breaks are dropped so the message shows as one plain line.
 */
function fitOfferMessage(raw) {
  if (raw == null) return '';
  // eslint-disable-next-line no-control-regex
  let msg = String(raw).replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (msg.length <= MAX_OFFER_MESSAGE) return msg;
  const m = msg.match(/(\s*·?\s*code\s+[A-Za-z0-9-]{1,32})$/);
  if (!m) return msg.slice(0, MAX_OFFER_MESSAGE);
  const tail = m[1];
  const head = msg.slice(0, msg.length - tail.length).slice(0, MAX_OFFER_MESSAGE - tail.length - 1).trimEnd();
  return (head + '…' + tail).slice(-MAX_OFFER_MESSAGE);
}

/**
 * Check a POST /offers/send or /offers/request body.
 * Returns { ok: true, value: { partnerTradeUrl, partner, assetIds, message } }
 * or { ok: false, status, error, message }.
 */
function validateOfferBody(body) {
  const b = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  const { partnerTradeUrl, assetIds, message } = b;
  if (!partnerTradeUrl || !Array.isArray(assetIds) || assetIds.length === 0) {
    return bad(400, 'BAD_REQUEST', 'partnerTradeUrl and non-empty assetIds[] are required');
  }
  const partner = parseTradeUrl(partnerTradeUrl);
  if (!partner) {
    return bad(400, 'BAD_TRADE_URL', 'trade url is not a valid Steam trade url');
  }
  if (assetIds.length > MAX_ASSETS_PER_OFFER) {
    return bad(400, 'BAD_REQUEST', 'at most ' + MAX_ASSETS_PER_OFFER + ' assetIds per offer');
  }
  const ids = [];
  for (const id of assetIds) {
    const s = typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? String(id)
      : typeof id === 'string' ? id.trim() : null;
    if (!s || !ASSET_ID.test(s) || /^0+$/.test(s)) {
      return bad(400, 'BAD_REQUEST', 'every assetId must be a Steam asset id (digits only)');
    }
    if (ids.includes(s)) return bad(400, 'BAD_REQUEST', 'assetId ' + s + ' is listed twice');
    ids.push(s);
  }
  if (message != null && typeof message !== 'string') {
    return bad(400, 'BAD_REQUEST', 'message must be a string');
  }
  return { ok: true, value: { partnerTradeUrl: partnerTradeUrl.trim(), partner, assetIds: ids,
    message: fitOfferMessage(message) } };
}

function isValidOfferId(id) {
  return typeof id === 'string' && OFFER_ID.test(id) && !/^0+$/.test(id);
}

/** Constant-time bearer-token check. Accepts "Bearer x" or a bare token. */
function tokenMatches(header, expected) {
  if (typeof expected !== 'string' || expected.length === 0) return false;
  const raw = typeof header === 'string' ? header : '';
  const got = raw.startsWith('Bearer ') ? raw.slice(7) : raw;
  if (!got) return false;
  const a = crypto.createHash('sha256').update(got).digest();
  const b = crypto.createHash('sha256').update(expected).digest();
  return crypto.timingSafeEqual(a, b) && got.length === expected.length;
}

// Steam EResult values the trade endpoints actually return.
const ERESULT = {
  Fail: 2, InvalidPassword: 5, LoggedInElsewhere: 6, InvalidParam: 8, Busy: 10, InvalidState: 11,
  AccessDenied: 15, Timeout: 16, ServiceUnavailable: 20, LimitExceeded: 25, Revoked: 26,
  RateLimitExceeded: 84, AccountLoginDeniedThrottle: 87, TwoFactorCodeMismatch: 88,
};

// steam-tradeoffer-manager sets err.cause on a few send failures whose message
// carries no eresult (lib/helpers.js makeAnError). The cause says more than the
// eresult or the text, so it is read first.
const CAUSE_CODES = {
  TradeBan: 'TRADE_BANNED_OR_UNTRADABLE',          // partner is trade banned: retrying never helps
  TargetCannotTrade: 'TRADE_BANNED_OR_UNTRADABLE', // partner's account cannot trade
  OfferLimitExceeded: 'LIMIT_EXCEEDED',            // too many offers outstanding; clears as they settle
  ItemServerUnavailable: 'STEAM_UNAVAILABLE',
};

/** Steam's eresult as a number. steamcommunity's inventory reader stores it as
 *  the regex-captured STRING (users.js: err.eresult = match[2]), which a plain
 *  typeof-number check dropped, leaving the text fallback to decide. */
function eresultOf(err) {
  if (!err) return null;
  if (typeof err.eresult === 'number') return err.eresult;
  if (typeof err.eresult === 'string' && /^\d{1,4}$/.test(err.eresult)) return Number(err.eresult);
  return null;
}

/**
 * Normalise a Steam/network failure into { code, message, retryable } for the
 * backend. steam-tradeoffer-manager's err.cause is read first, then Steam's
 * numeric eresult, then the message text, which changes between library
 * versions.
 *
 * Two mappings the old classifier got wrong:
 *   - eresult 25 (LimitExceeded) was RATE_LIMITED (HTTP 429). On a trade offer
 *     the library itself tags it OfferLimitExceeded ("sent too many trade
 *     offers"), and Steam also uses it for a full partner inventory: neither
 *     is request throttling. It is LIMIT_EXCEEDED now. It stays retryable,
 *     because both clear on their own and the backend halts a non-retryable
 *     delivery until the trade URL or asset changes, stranding a sold item.
 *   - any message containing "inventory" was INVENTORY_ERROR and FINAL. Only
 *     eresult 26 (Revoked: the asset is gone) is final now; an inventory
 *     failure known only from its text (e.g. a Steam 500 while loading the
 *     inventory) is retryable.
 */
function classifyError(err) {
  const msg = (err && err.message ? err.message : String(err)) || 'unknown error';
  const eresult = eresultOf(err);
  const cause = err && typeof err.cause === 'string' ? err.cause : null;
  let code = 'ERROR';
  let finalInventory = false;
  if (cause && CAUSE_CODES[cause]) code = CAUSE_CODES[cause];
  else if (eresult === ERESULT.LimitExceeded) code = 'LIMIT_EXCEEDED';
  else if (eresult === ERESULT.RateLimitExceeded) code = 'RATE_LIMITED';
  else if (eresult === ERESULT.AccessDenied) code = 'BAD_TRADE_URL';
  else if (eresult === ERESULT.Revoked) { code = 'INVENTORY_ERROR'; finalInventory = true; }
  else if (eresult === ERESULT.Timeout || eresult === ERESULT.ServiceUnavailable || eresult === ERESULT.Busy) code = 'STEAM_UNAVAILABLE';
  else if (eresult === ERESULT.InvalidState) code = 'TRADE_BANNED_OR_UNTRADABLE';
  else if (/rate limit|too many requests|\b429\b/i.test(msg)) code = 'RATE_LIMITED';
  else if (/escrow|trade hold/i.test(msg)) code = 'ESCROW_HOLD';
  else if (/not friends|trade url|trade token|access denied/i.test(msg)) code = 'BAD_TRADE_URL';
  else if (/inventory/i.test(msg)) code = 'INVENTORY_ERROR';
  else if (/revoked|not logged|cookies|session/i.test(msg)) code = 'NOT_READY';
  else if (/timed? ?out|ETIMEDOUT|ECONNRESET|EAI_AGAIN|socket hang up|\b5\d\d\b/i.test(msg)) code = 'STEAM_UNAVAILABLE';
  const retryable = code === 'RATE_LIMITED' || code === 'STEAM_UNAVAILABLE' || code === 'NOT_READY' ||
    code === 'ERROR' || code === 'LIMIT_EXCEEDED' || (code === 'INVENTORY_ERROR' && !finalInventory);
  return { code, message: msg, retryable };
}

// Addresses the HTTP API may bind to without an explicit opt-in. Shared with
// fake-bot.js, which has refused anything else from the start.
const LOOPBACK_HOSTS = new Set(['127.0.0.1', '::1', 'localhost']);

/**
 * null when BOT_HOST is fine, else the problem to report. The trade API has
 * only the bearer token in front of it, and /health (no token) shows the bot's
 * SteamID and last error, so a non-loopback bind needs BOT_ALLOW_REMOTE=yes.
 */
function bindHostProblem(host, allowRemote) {
  const h = String(host == null ? '' : host).trim().toLowerCase();
  if (LOOPBACK_HOSTS.has(h)) return null;
  if (String(allowRemote == null ? '' : allowRemote).trim() === 'yes') return null;
  return 'BOT_HOST="' + host + '" is not a loopback address (127.0.0.1, ::1, localhost). The bot ' +
    'API would be reachable from the network with only BOT_API_TOKEN in front of it, and /health ' +
    'needs no token at all. Use BOT_HOST=127.0.0.1, or set BOT_ALLOW_REMOTE=yes if a private-' +
    'network bind is really intended.';
}

/** Body of the 401 on a bad token. A wrong token is a CONFIGURATION error: the
 *  same request fails the same way until someone fixes BOT_API_TOKEN on one
 *  side, so the backend must not retry it (SteamBotResult reads retryable). */
const UNAUTHORIZED_BODY = Object.freeze({
  ok: false,
  error: 'unauthorized',
  configError: true,
  retryable: false,
  message: 'missing or wrong bearer token: BOT_API_TOKEN must be the same value in the app and the bot',
});

/** Express middleware: bearer token on every route except /health. */
function requireToken(expected) {
  return (req, res, next) => {
    if (req.path === '/health') return next();
    if (!tokenMatches(req.get('authorization') || '', expected)) {
      return res.status(401).json(Object.assign({}, UNAUTHORIZED_BODY));
    }
    return next();
  };
}

/** HTTP status for a classified send/accept failure. */
function httpStatusFor(code) {
  if (code === 'RATE_LIMITED') return 429;
  if (code === 'NOT_READY' || code === 'STEAM_UNAVAILABLE') return 503;
  if (code === 'BAD_TRADE_URL') return 400;
  return 502;
}

function bad(status, error, message) {
  return { ok: false, status, error, message };
}

module.exports = {
  MAX_OFFER_MESSAGE,
  MAX_ASSETS_PER_OFFER,
  ERESULT,
  parseTradeUrl,
  redactTradeUrl,
  fitOfferMessage,
  validateOfferBody,
  isValidOfferId,
  tokenMatches,
  classifyError,
  httpStatusFor,
  LOOPBACK_HOSTS,
  bindHostProblem,
  UNAUTHORIZED_BODY,
  requireToken,
};

'use strict';

/*
 * sboxmarket Steam trade-offer bot sidecar.
 *
 * Logs in a dedicated Steam bot account, then exposes a small token-authenticated
 * HTTP API the Spring Boot backend (SteamTradeBotService.groovy) calls to:
 *   - send a trade offer to a buyer for s&box (Steam app 590830) and auto-confirm it
 *   - poll the status of an offer (active / accepted / declined / expired / ...)
 *   - accept an incoming offer (e.g. when a seller deposits an item into bot escrow)
 *   - read the bot's own app-590830 inventory
 *
 * SECURITY: every credential comes from the environment. Nothing is hardcoded.
 * The HTTP API is protected by a shared bearer token (BOT_API_TOKEN); bind the
 * process to localhost / a private network and never expose it publicly.
 */

const express = require('express');
const SteamUser = require('steam-user');
const SteamCommunity = require('steamcommunity');
const TradeOfferManager = require('steam-tradeoffer-manager');
const SteamTotp = require('steam-totp');

// ---------------------------------------------------------------------------
// Config (env only)
// ---------------------------------------------------------------------------
const CONFIG = {
  username: process.env.STEAM_BOT_USERNAME,
  password: process.env.STEAM_BOT_PASSWORD,
  sharedSecret: process.env.STEAM_BOT_SHARED_SECRET,
  identitySecret: process.env.STEAM_BOT_IDENTITY_SECRET,
  apiKey: process.env.STEAM_BOT_API_KEY || null, // optional; manager can fetch one after login
  apiToken: process.env.BOT_API_TOKEN,
  port: parseInt(process.env.BOT_PORT || '4000', 10),
  host: process.env.BOT_HOST || '127.0.0.1',
  appId: parseInt(process.env.STEAM_BOT_APP_ID || '590830', 10), // s&box
  contextId: process.env.STEAM_BOT_CONTEXT_ID || '2',           // default Steam inventory context
  pollInterval: parseInt(process.env.STEAM_BOT_POLL_INTERVAL_MS || '15000', 10),
  confirmRetryMs: parseInt(process.env.STEAM_BOT_CONFIRM_RETRY_MS || '20000', 10),
};

function requireConfig() {
  const missing = [];
  for (const key of ['username', 'password', 'sharedSecret', 'identitySecret', 'apiToken']) {
    if (!CONFIG[key]) missing.push(key);
  }
  if (missing.length) {
    // eslint-disable-next-line no-console
    console.error('[bot] Missing required env vars: ' + missing.join(', ') +
      '. See README.md / .env.example.');
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// Steam clients
// ---------------------------------------------------------------------------
const client = new SteamUser();
const community = new SteamCommunity();
let manager = null;

// Runtime state
const state = {
  loggedIn: false,
  steamId: null,
  lastError: null,
  cookiesReady: false,
};

// Map our friendly status strings off the TradeOfferManager ETradeOfferState enum.
function offerStateName(stateNum) {
  const ETradeOfferState = TradeOfferManager.ETradeOfferState;
  switch (stateNum) {
    case ETradeOfferState.Invalid: return 'invalid';
    case ETradeOfferState.Active: return 'active';
    case ETradeOfferState.Accepted: return 'accepted';
    case ETradeOfferState.Countered: return 'countered';
    case ETradeOfferState.Expired: return 'expired';
    case ETradeOfferState.Canceled: return 'canceled';
    case ETradeOfferState.Declined: return 'declined';
    case ETradeOfferState.InvalidItems: return 'invalid_items';
    case ETradeOfferState.CreatedNeedsConfirmation: return 'needs_confirmation';
    case ETradeOfferState.CanceledBySecondFactor: return 'canceled_2fa';
    case ETradeOfferState.InEscrow: return 'in_escrow';
    default: return 'unknown';
  }
}

function logIn() {
  const logOnOptions = {
    accountName: CONFIG.username,
    password: CONFIG.password,
    twoFactorCode: SteamTotp.generateAuthCode(CONFIG.sharedSecret),
  };
  // eslint-disable-next-line no-console
  console.log('[bot] Logging in as %s ...', CONFIG.username);
  client.logOn(logOnOptions);
}

client.on('loggedOn', () => {
  state.loggedIn = true;
  state.steamId = client.steamID ? client.steamID.getSteamID64() : null;
  // eslint-disable-next-line no-console
  console.log('[bot] Logged on. SteamID=%s', state.steamId);
  client.setPersona(SteamUser.EPersonaState.Online);
});

client.on('error', (err) => {
  state.loggedIn = false;
  state.lastError = err && err.message ? err.message : String(err);
  // eslint-disable-next-line no-console
  console.error('[bot] Steam client error:', state.lastError);
  // steam-user auto-reconnects for most errors; for fatal ones we surface via /health.
});

client.on('disconnected', (eresult, msg) => {
  state.loggedIn = false;
  state.cookiesReady = false;
  // eslint-disable-next-line no-console
  console.warn('[bot] Disconnected (%s): %s', eresult, msg);
});

client.on('webSession', (sessionID, cookies) => {
  // Build the manager lazily once we have web cookies (needed every reconnect).
  if (!manager) {
    manager = new TradeOfferManager({
      steam: client,
      community: community,
      language: 'en',
      pollInterval: CONFIG.pollInterval,
      cancelTime: 1000 * 60 * 60 * 24 * 14, // auto-cancel our unaccepted sent offers after 14 days
    });
    wireManagerEvents();
  }

  const finishSetup = (err) => {
    if (err) {
      state.lastError = 'setCookies: ' + (err.message || String(err));
      // eslint-disable-next-line no-console
      console.error('[bot] Failed to set trade cookies:', state.lastError);
      return;
    }
    state.cookiesReady = true;
    // eslint-disable-next-line no-console
    console.log('[bot] Trade manager ready (cookies set).');
  };

  // If an explicit API key was provided, pin it; otherwise let the manager fetch/derive one.
  if (CONFIG.apiKey) {
    manager.setCookies(cookies, CONFIG.apiKey, finishSetup);
  } else {
    manager.setCookies(cookies, finishSetup);
  }

  community.setCookies(cookies);
  community.startConfirmationChecker(CONFIG.confirmRetryMs, CONFIG.identitySecret);
});

function wireManagerEvents() {
  manager.on('newOffer', (offer) => {
    // eslint-disable-next-line no-console
    console.log('[bot] Incoming offer #%s from %s', offer.id,
      offer.partner ? offer.partner.getSteamID64() : 'unknown');
    // We do NOT auto-accept incoming offers here; the backend decides via
    // POST /offers/incoming/:id/accept so escrow accounting stays authoritative server-side.
  });

  manager.on('sentOfferChanged', (offer) => {
    // eslint-disable-next-line no-console
    console.log('[bot] Sent offer #%s -> %s', offer.id, offerStateName(offer.state));
  });

  manager.on('receivedOfferChanged', (offer) => {
    // eslint-disable-next-line no-console
    console.log('[bot] Received offer #%s -> %s', offer.id, offerStateName(offer.state));
  });
}

// ---------------------------------------------------------------------------
// Helpers for the HTTP layer
// ---------------------------------------------------------------------------
function ready() {
  return state.loggedIn && state.cookiesReady && manager != null;
}

// Confirm a just-sent offer that needs mobile confirmation, using the identity secret.
function confirmSentOffer(offer, cb) {
  community.acceptConfirmationForObject(CONFIG.identitySecret, offer.id, (err) => {
    if (err) {
      // eslint-disable-next-line no-console
      console.error('[bot] Confirmation failed for offer #%s: %s', offer.id, err.message || err);
      return cb(err);
    }
    // eslint-disable-next-line no-console
    console.log('[bot] Confirmed offer #%s', offer.id);
    cb(null);
  });
}

// Normalise common Steam/network failures into a stable shape for the backend.
function classifyError(err) {
  const msg = (err && err.message ? err.message : String(err)) || 'unknown error';
  let code = 'ERROR';
  if (/rate limit|too many requests|429/i.test(msg)) code = 'RATE_LIMITED';
  else if (/escrow|hold/i.test(msg)) code = 'ESCROW_HOLD';
  else if (/not friends|trade url|token/i.test(msg)) code = 'BAD_TRADE_URL';
  else if (/inventory/i.test(msg)) code = 'INVENTORY_ERROR';
  else if (/revoked|not logged|cookies|session/i.test(msg)) code = 'NOT_READY';
  return { code, message: msg };
}

// ---------------------------------------------------------------------------
// HTTP API
// ---------------------------------------------------------------------------
const app = express();
app.use(express.json({ limit: '256kb' }));

// Bearer-token auth on every route except /health.
app.use((req, res, next) => {
  if (req.path === '/health') return next();
  const auth = req.get('authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : auth;
  if (!token || token !== CONFIG.apiToken) {
    return res.status(401).json({ ok: false, error: 'unauthorized' });
  }
  next();
});

app.get('/health', (req, res) => {
  res.json({
    ok: true,
    loggedIn: state.loggedIn,
    ready: ready(),
    steamId: state.steamId,
    appId: CONFIG.appId,
    lastError: state.lastError,
  });
});

// POST /offers/send { partnerTradeUrl, assetIds: [..], message }
// Creates + sends a trade offer GIVING the listed app-590830 assets to the partner,
// then auto-confirms it via the identity secret.
app.post('/offers/send', (req, res) => {
  if (!ready()) {
    const e = classifyError(new Error('bot not ready / not logged in'));
    return res.status(503).json({ ok: false, error: e.code, message: e.message });
  }
  const { partnerTradeUrl, assetIds, message } = req.body || {};
  if (!partnerTradeUrl || !Array.isArray(assetIds) || assetIds.length === 0) {
    return res.status(400).json({ ok: false, error: 'BAD_REQUEST',
      message: 'partnerTradeUrl and non-empty assetIds[] are required' });
  }

  let offer;
  try {
    offer = manager.createOffer(partnerTradeUrl); // accepts a full trade URL (partner + token)
  } catch (err) {
    const e = classifyError(err);
    return res.status(400).json({ ok: false, error: e.code, message: e.message });
  }

  const items = assetIds.map((id) => ({
    appid: CONFIG.appId,
    contextid: CONFIG.contextId,
    assetid: String(id),
    amount: 1,
  }));
  offer.addMyItems(items);
  if (message) offer.setMessage(String(message));

  offer.send((err, status) => {
    if (err) {
      const e = classifyError(err);
      const http = e.code === 'RATE_LIMITED' ? 429 : 502;
      return res.status(http).json({ ok: false, error: e.code, message: e.message });
    }

    // status is 'pending' (needs our mobile confirmation) or 'sent'.
    const respond = (confirmed, confirmError) => {
      res.json({
        ok: true,
        offerId: offer.id,
        status: confirmed ? 'sent' : (status || 'pending'),
        steamStatus: status,
        confirmed: !!confirmed,
        confirmError: confirmError ? classifyError(confirmError).message : null,
      });
    };

    if (status === 'pending') {
      confirmSentOffer(offer, (cerr) => respond(!cerr, cerr));
    } else {
      respond(true, null);
    }
  });
});

// GET /offers/:id -> normalized status
app.get('/offers/:id', (req, res) => {
  if (!ready()) {
    return res.status(503).json({ ok: false, error: 'NOT_READY', message: 'bot not ready' });
  }
  manager.getOffer(req.params.id, (err, offer) => {
    if (err) {
      const e = classifyError(err);
      const http = /no matching|not found/i.test(e.message) ? 404 : 502;
      return res.status(http).json({ ok: false, error: e.code, message: e.message });
    }
    res.json({
      ok: true,
      offerId: offer.id,
      status: offerStateName(offer.state),
      stateNum: offer.state,
      isOurOffer: offer.isOurOffer,
      partner: offer.partner ? offer.partner.getSteamID64() : null,
      escrowEndsUnix: offer.escrowEnds ? Math.floor(offer.escrowEnds.getTime() / 1000) : null,
      tradeId: offer.tradeID || null,
    });
  });
});

// POST /offers/incoming/:id/accept -> accept an incoming offer (and confirm if needed)
app.post('/offers/incoming/:id/accept', (req, res) => {
  if (!ready()) {
    return res.status(503).json({ ok: false, error: 'NOT_READY', message: 'bot not ready' });
  }
  manager.getOffer(req.params.id, (err, offer) => {
    if (err) {
      const e = classifyError(err);
      const http = /no matching|not found/i.test(e.message) ? 404 : 502;
      return res.status(http).json({ ok: false, error: e.code, message: e.message });
    }
    if (offer.isOurOffer) {
      return res.status(400).json({ ok: false, error: 'BAD_REQUEST',
        message: 'offer is outgoing, not incoming' });
    }
    offer.accept((aerr, status) => {
      if (aerr) {
        const e = classifyError(aerr);
        const http = e.code === 'RATE_LIMITED' ? 429 : 502;
        return res.status(http).json({ ok: false, error: e.code, message: e.message });
      }
      // status: 'pending' | 'accepted'. Confirm if Steam asks for mobile confirmation.
      if (status === 'pending') {
        community.acceptConfirmationForObject(CONFIG.identitySecret, offer.id, (cerr) => {
          res.json({
            ok: true,
            offerId: offer.id,
            status: cerr ? 'pending' : 'accepted',
            confirmed: !cerr,
            confirmError: cerr ? classifyError(cerr).message : null,
          });
        });
      } else {
        res.json({ ok: true, offerId: offer.id, status: 'accepted', confirmed: true });
      }
    });
  });
});

// GET /inventory -> bot's own app-590830 inventory
app.get('/inventory', (req, res) => {
  if (!ready()) {
    return res.status(503).json({ ok: false, error: 'NOT_READY', message: 'bot not ready' });
  }
  manager.getInventoryContents(CONFIG.appId, CONFIG.contextId, true, (err, inventory) => {
    if (err) {
      const e = classifyError(err);
      return res.status(502).json({ ok: false, error: e.code, message: e.message });
    }
    res.json({
      ok: true,
      appId: CONFIG.appId,
      count: inventory.length,
      items: inventory.map((it) => ({
        assetId: it.assetid,
        classId: it.classid,
        instanceId: it.instanceid,
        marketHashName: it.market_hash_name,
        name: it.name,
        tradable: it.tradable,
      })),
    });
  });
});

// Fallback 404
app.use((req, res) => res.status(404).json({ ok: false, error: 'not_found' }));

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
function main() {
  requireConfig();
  logIn();
  app.listen(CONFIG.port, CONFIG.host, () => {
    // eslint-disable-next-line no-console
    console.log('[bot] HTTP API listening on http://%s:%d (app %d)',
      CONFIG.host, CONFIG.port, CONFIG.appId);
  });
}

// Graceful shutdown
['SIGINT', 'SIGTERM'].forEach((sig) => {
  process.on(sig, () => {
    // eslint-disable-next-line no-console
    console.log('[bot] %s received, shutting down.', sig);
    try { client.logOff(); } catch (e) { /* ignore */ }
    process.exit(0);
  });
});

main();

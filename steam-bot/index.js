'use strict';

/*
 * sboxmarket Steam trade-offer bot sidecar.
 *
 * Logs in a dedicated Steam bot account, then exposes a small token-authenticated
 * HTTP API the Spring Boot backend (SteamTradeBotService.groovy) calls to:
 *   - send a trade offer to a buyer for s&box (Steam app 590830) and auto-confirm it
 *   - REQUEST a specific item FROM a seller (deposit/escrow leg) — the bot sends an
 *     offer that receives the asset and gives nothing (addTheirItem)
 *   - poll the status of an offer (active / accepted / declined / expired / ...)
 *   - accept an incoming offer (alternative deposit path)
 *   - read the bot's own app-590830 inventory (confirm an asset is now held)
 *
 * SECURITY: every credential comes from the environment. Nothing is hardcoded.
 * The HTTP API is protected by a shared bearer token (BOT_API_TOKEN); bind the
 * process to localhost / a private network and never expose it publicly.
 */

// ---------------------------------------------------------------------------
// Config (env only)
//
// NOTE ON ORDERING: config + validation deliberately come BEFORE the require()
// calls below. Node evaluates requires in source order, so a sidecar with no
// node_modules used to die on "Cannot find module 'express'" and never reach
// the credential check — the operator learned nothing about the four secrets
// they had not set yet. Validation first means one run reports every real
// problem.
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

// ---------------------------------------------------------------------------
// Startup validation
//
// Runs before the Steam libraries load, before logIn(), and before the HTTP
// server binds its port. A half-started bot is worse than a dead one: it still
// answers /health, so the backend keeps calling it, and every trade comes back
// 503 NOT_READY — indistinguishable from a Steam outage. Fail here instead, and
// name EVERY problem in one pass so the operator fixes them in one edit rather
// than discovering them one restart at a time.
//
// The old check reported the INTERNAL key names ("username, password,
// sharedSecret") — none of which appear in .env.example or the README, so the
// error told the operator nothing about which line to fill in.
// ---------------------------------------------------------------------------

// No usable default; the process genuinely cannot function without these.
const REQUIRED_ENV = [
  { name: 'STEAM_BOT_USERNAME',
    purpose: 'Steam login name of the dedicated bot account (README step 1).' },
  { name: 'STEAM_BOT_PASSWORD',
    purpose: 'Password for that same bot account (README step 1).' },
  { name: 'STEAM_BOT_SHARED_SECRET',
    purpose: 'base64 shared_secret from the bot mobile authenticator; generates the ' +
      'Steam Guard login code, so login is impossible without it (README step 3).' },
  { name: 'STEAM_BOT_IDENTITY_SECRET',
    purpose: 'base64 identity_secret from the same authenticator; auto-confirms outgoing ' +
      'trade offers. Without it every sent offer stalls unconfirmed (README step 3).' },
  { name: 'BOT_API_TOKEN',
    purpose: 'Shared bearer token required on every request except /health. Must match the ' +
      'backend BOT_API_TOKEN exactly, or the backend gets 401 on every call (README step 5).' },
];

// The literal values .env.example ships. They are in a tracked file, so anyone
// reading the repo knows them — treating them as "set" would run the sidecar on
// a publicly known API token.
const ENV_PLACEHOLDERS = new Set([
  'your_bot_steam_login',
  'your_bot_steam_password',
  'base64_shared_secret_here',
  'base64_identity_secret_here',
  'change_me_long_random_shared_token',
]);

// Optional, each with a working default. Absent is fine — only a value that is
// PRESENT and unparseable is an error. parseInt() would silently accept "4000x"
// as 4000 and turn "abc" into NaN, and app.listen(NaN) binds a random port the
// backend can never reach.
const NUMERIC_ENV = [
  { name: 'BOT_PORT', min: 1, max: 65535, def: '4000' },
  { name: 'STEAM_BOT_APP_ID', min: 1, max: null, def: '590830' },
  { name: 'STEAM_BOT_CONTEXT_ID', min: 0, max: null, def: '2' },
  { name: 'STEAM_BOT_POLL_INTERVAL_MS', min: 1, max: null, def: '15000' },
  { name: 'STEAM_BOT_CONFIRM_RETRY_MS', min: 1, max: null, def: '20000' },
];

function validateEnvOrExit() {
  const problems = [];

  for (const item of REQUIRED_ENV) {
    const raw = process.env[item.name];
    const value = raw === undefined || raw === null ? '' : String(raw).trim();
    if (value === '') {
      problems.push(item.name + ' is missing or blank. ' + item.purpose);
    } else if (ENV_PLACEHOLDERS.has(value)) {
      problems.push(item.name + ' is still the .env.example placeholder "' + value +
        '". ' + item.purpose);
    }
  }

  for (const item of NUMERIC_ENV) {
    const raw = process.env[item.name];
    if (raw === undefined || raw === null || String(raw).trim() === '') continue; // default applies
    const num = Number(String(raw).trim());
    const range = item.max === null ? item.min + ' or greater'
      : 'between ' + item.min + ' and ' + item.max;
    if (!Number.isInteger(num) || num < item.min || (item.max !== null && num > item.max)) {
      problems.push(item.name + '="' + raw + '" is not a whole number ' + range +
        '. Unset it to use the default (' + item.def + ').');
    }
  }

  if (!problems.length) return;

  // eslint-disable-next-line no-console
  console.error('[bot] Refusing to start: %d configuration problem(s).', problems.length);
  for (const problem of problems) {
    // eslint-disable-next-line no-console
    console.error('[bot]   * ' + problem);
  }
  // eslint-disable-next-line no-console
  console.error('[bot] Fix these in steam-bot/.env (copy .env.example), then run:');
  // eslint-disable-next-line no-console
  console.error('[bot]   node --env-file=.env index.js');
  // eslint-disable-next-line no-console
  console.error('[bot] This process does NOT auto-load .env — without --env-file (or a ' +
    'process manager that injects env) every variable above reads as missing.');
  process.exit(1);
}

validateEnvOrExit();

// ---------------------------------------------------------------------------
// Dependencies (loaded only once the config is known-good)
// ---------------------------------------------------------------------------
function loadDependency(name) {
  try {
    return require(name);
  } catch (err) {
    if (err && err.code === 'MODULE_NOT_FOUND' && String(err.message).includes(name)) {
      // eslint-disable-next-line no-console
      console.error('[bot] Dependency "%s" is not installed. Run `npm install` in ' +
        'steam-bot/ before starting the sidecar.', name);
      process.exit(1);
    }
    throw err; // a real failure inside the module — do not disguise it
  }
}

const express = loadDependency('express');
const SteamUser = loadDependency('steam-user');
const SteamCommunity = loadDependency('steamcommunity');
const TradeOfferManager = loadDependency('steam-tradeoffer-manager');
const SteamTotp = loadDependency('steam-totp');

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

  // steam-tradeoffer-manager v2's signature is:
  //     setCookies(cookies[, familyViewPin], callback)
  // The middle positional argument is a FAMILY VIEW PIN, not an API key. There
  // is no API-key parameter at all — the manager obtains its own key from the
  // web session during setCookies.
  //
  // This used to pass CONFIG.apiKey there whenever STEAM_BOT_API_KEY was set,
  // which handed the Steam Web API key to parentalUnlock() as a family-view
  // PIN. That call fails, finishSetup receives the error, cookiesReady stays
  // false, ready() stays false — and every single endpoint returns 503
  // NOT_READY forever. The bot would never have sent one trade offer. Since
  // .env.example ships STEAM_BOT_API_KEY as a documented option, an operator
  // following the README and pasting their key in would have hit exactly this
  // and had no way to tell it apart from a Steam outage.
  //
  // Only reachable with a real Steam login, so no test could have caught it and
  // the code has never been executed once. Pass the callback in the position
  // the library actually reads it from.
  manager.setCookies(cookies, finishSetup);
  if (CONFIG.apiKey) {
    console.log('[bot] STEAM_BOT_API_KEY is set but unused — steam-tradeoffer-manager ' +
      'derives its own Web API key from the login session. Safe to leave blank.');
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

// POST /offers/request { partnerTradeUrl, assetIds: [..], message }
// Creates + sends a trade offer that REQUESTS (receives, gives nothing) the
// listed app-590830 assets FROM the partner — the seller→bot deposit/escrow
// leg. Uses addTheirItem. The seller accepts the offer in their own Steam
// client (their confirmation, not ours). Since the bot gives nothing, the
// offer normally needs no mobile confirmation from us; we still handle a
// 'pending' status defensively (e.g. if a future change adds bot-side items).
app.post('/offers/request', (req, res) => {
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
    offer = manager.createOffer(partnerTradeUrl); // full trade URL (partner + token)
  } catch (err) {
    const e = classifyError(err);
    return res.status(400).json({ ok: false, error: e.code, message: e.message });
  }

  // addTheirItem — the bot RECEIVES these assets from the partner and gives
  // nothing. This is what makes the offer a deposit/escrow request.
  const items = assetIds.map((id) => ({
    appid: CONFIG.appId,
    contextid: CONFIG.contextId,
    assetid: String(id),
    amount: 1,
  }));
  items.forEach((it) => offer.addTheirItem(it));
  if (message) offer.setMessage(String(message));

  offer.send((err, status) => {
    if (err) {
      const e = classifyError(err);
      const http = e.code === 'RATE_LIMITED' ? 429 : 502;
      return res.status(http).json({ ok: false, error: e.code, message: e.message });
    }
    // status is 'pending' (would need OUR confirmation — only if we were also
    // giving items) or 'sent'. A pure receive offer is 'sent' immediately and
    // simply waits for the seller to accept on their side.
    const respond = (confirmed, confirmError) => {
      res.json({
        ok: true,
        offerId: offer.id,
        status: status || 'sent',
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
  // Config was validated at module load, before the Steam libraries were even
  // required — see validateEnvOrExit(). Nothing reaches here half-configured.
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

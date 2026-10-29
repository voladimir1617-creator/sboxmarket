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
 *   - cancel an offer it sent, so a deposit or delivery the backend gave up on
 *     can no longer be accepted
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
  allowRemote: process.env.BOT_ALLOW_REMOTE,
  // Offers awaiting confirmation + the sent registry, kept across restarts.
  stateFile: process.env.STEAM_BOT_STATE_FILE && process.env.STEAM_BOT_STATE_FILE.trim()
    ? process.env.STEAM_BOT_STATE_FILE.trim()
    : require('path').join(__dirname, 'data', 'state.json'),
};

// No dependencies (crypto only), so it is safe to load before validation.
const guards = require('./lib/guards');

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

  // Loopback only, like fake-bot.js, unless the operator opts in explicitly.
  const hostProblem = guards.bindHostProblem(CONFIG.host, CONFIG.allowRemote);
  if (hostProblem) problems.push(hostProblem);

  // The go-live gate. This process logs in to a REAL Steam account and moves
  // REAL skins, and the whole marketplace stays in test mode until the owner
  // says "go live". Until then the local loop runs on the fake bot instead
  // (npm run fake), which needs no account and touches nothing on Steam.
  if (String(process.env.STEAM_BOT_LIVE || '').trim() !== 'yes') {
    problems.push('STEAM_BOT_LIVE is not "yes". This sidecar trades real skins on a real Steam ' +
      'account, so it stays off until the owner says go live. For a local test run use ' +
      '"npm run fake" instead, which needs no Steam account.');
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
const { createBotCore } = require('./lib/bot-core');
const { createReconnector, webSessionRetryDelayMs } = require('./lib/reconnect');
const { createStateStore } = require('./lib/state-store');
const { createSteamGuard } = require('./lib/totp');

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

// Login codes from shared_secret on Steam's clock; a rejected code waits for the
// next 30 s window. See lib/totp.js. Without this listener steam-user prompts
// on stdin, which hangs a bot run under a service manager.
const steamGuard = createSteamGuard({
  SteamTotp,
  sharedSecret: CONFIG.sharedSecret,
  onStop: (why) => {
    state.lastError = why;
    // eslint-disable-next-line no-console
    console.error('[bot] ' + why);
  },
});
client.on('steamGuard', steamGuard.onSteamGuard);

function logIn() {
  steamGuard.loginCode().then((twoFactorCode) => {
    // eslint-disable-next-line no-console
    console.log('[bot] Logging in as %s ...', CONFIG.username);
    client.logOn({ accountName: CONFIG.username, password: CONFIG.password, twoFactorCode });
  });
}

const reconnector = createReconnector({ logIn });

// A failed setCookies used to leave cookiesReady false until the next full
// re-login, so every endpoint answered 503 NOT_READY indefinitely. Ask for a
// new web session on a growing delay instead (webSession then retries setCookies).
const webRetry = { timer: null, attempts: 0 };
function scheduleWebLogOnRetry() {
  if (webRetry.timer) return;
  webRetry.attempts += 1;
  const delay = webSessionRetryDelayMs(webRetry.attempts);
  // eslint-disable-next-line no-console
  console.warn('[bot] Requesting a new web session in %ds (attempt %d).', Math.round(delay / 1000), webRetry.attempts);
  webRetry.timer = setTimeout(() => {
    webRetry.timer = null;
    if (state.loggedIn) client.webLogOn();
  }, delay);
}
function clearWebLogOnRetry(resetAttempts) {
  if (webRetry.timer) { clearTimeout(webRetry.timer); webRetry.timer = null; }
  if (resetAttempts) webRetry.attempts = 0;
}

client.on('loggedOn', () => {
  reconnector.onLoggedOn();
  steamGuard.onLoggedOn();
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
  // steam-user re-connects by itself after a plain disconnect, but an 'error'
  // event means it has given up. Log in again on a growing delay (never after
  // a wrong password, which would get the account locked).
  reconnector.onFatalError(err);
});

client.on('disconnected', (eresult, msg) => {
  state.loggedIn = false;
  state.cookiesReady = false;
  clearWebLogOnRetry(false); // a re-login brings a fresh webSession anyway
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
      // Web API calls authenticate with the session's access token, so no Web
      // API key is needed (or looked up) at all. steam-tradeoffer-manager 2.13
      // (the version in package-lock) already defaults this to true; it is set
      // explicitly so an older 2.11.x resolved from "^2.11.5" behaves the same.
      useAccessToken: true,
    });
    wireManagerEvents();
  }

  const finishSetup = (err) => {
    if (err) {
      state.lastError = 'setCookies: ' + (err.message || String(err));
      // eslint-disable-next-line no-console
      console.error('[bot] Failed to set trade cookies:', state.lastError);
      scheduleWebLogOnRetry();
      return;
    }
    clearWebLogOnRetry(true);
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
      'uses the login session\'s access token (useAccessToken). Safe to leave blank.');
  }

  community.setCookies(cookies);
  // NOT community.startConfirmationChecker(..., identitySecret): with the
  // secret it confirms EVERY pending confirmation on the account, including a
  // trade someone else created with a stolen web session to empty the bot.
  // The bot confirms only offers it sent itself (bot-core confirmOwn), and
  // re-tries the ones whose first confirmation failed on its own timer below.
});

// Steam web sessions expire on their own; ask for a fresh one instead of
// letting every call fail until the next full re-login.
community.on('sessionExpired', () => {
  state.cookiesReady = false;
  // eslint-disable-next-line no-console
  console.warn('[bot] Steam web session expired; requesting a new one.');
  if (state.loggedIn) client.webLogOn();
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
    console.log('[bot] Sent offer #%s -> %s', offer.id, core.stateName(offer.state));
  });

  manager.on('receivedOfferChanged', (offer) => {
    // eslint-disable-next-line no-console
    console.log('[bot] Received offer #%s -> %s', offer.id, core.stateName(offer.state));
  });
}

// ---------------------------------------------------------------------------
// Trade logic (lib/bot-core.js, tested against a stand-in Steam)
// ---------------------------------------------------------------------------
function ready() {
  return state.loggedIn && state.cookiesReady && manager != null;
}

const core = createBotCore({
  getManager: () => manager,
  community,
  identitySecret: CONFIG.identitySecret,
  appId: CONFIG.appId,
  contextId: CONFIG.contextId,
  states: TradeOfferManager.ETradeOfferState,
  isReady: ready,
  // Offers awaiting the bot's mobile confirmation (and the sent registry the
  // duplicate check reads) survive a restart. Default steam-bot/data/state.json,
  // gitignored; override with STEAM_BOT_STATE_FILE.
  store: createStateStore(CONFIG.stateFile),
});

// Our own offers whose confirmation failed get another try on this timer.
setInterval(() => {
  core.retryConfirmations().catch((err) => {
    // eslint-disable-next-line no-console
    console.error('[bot] confirmation retry failed:', err && err.message ? err.message : err);
  });
}, CONFIG.confirmRetryMs).unref();

// ---------------------------------------------------------------------------
// HTTP API
// ---------------------------------------------------------------------------
const app = express();
app.use(express.json({ limit: '256kb' }));

// Bearer-token auth on every route except /health (constant-time compare). The
// 401 says retryable:false + configError:true: a token mismatch never fixes
// itself, so the backend must stop retrying it every 30 s and surface it.
app.use(guards.requireToken(CONFIG.apiToken));

app.get('/health', (req, res) => {
  res.json({
    ok: true,
    loggedIn: state.loggedIn,
    ready: ready(),
    steamId: state.steamId,
    appId: CONFIG.appId,
    lastError: state.lastError,
    pendingConfirmations: core.pendingConfirmations().length,
  });
});

const reply = (res) => ([status, body]) => res.status(status).json(body);

// Delivery and return leg: the bot GIVES the assets to the partner.
app.post('/offers/send', (req, res) => core.send(req.body).then(reply(res)));
// Deposit leg: the bot REQUESTS the assets from the seller and gives nothing.
app.post('/offers/request', (req, res) => core.request(req.body).then(reply(res)));
app.get('/offers/:id', (req, res) => core.getOffer(req.params.id).then(reply(res)));
app.post('/offers/incoming/:id/accept', (req, res) => core.acceptIncoming(req.params.id).then(reply(res)));
// Cancel an offer the bot sent (deposit timeout, listing cancel, trade refund).
// 200 cancelled (or already dead), 409 ALREADY_ACCEPTED when the item moved.
app.post('/offers/:id/cancel', (req, res) => core.cancelOffer(req.params.id).then(reply(res)));
app.get('/inventory', (req, res) => core.inventory().then(reply(res)));

// Fallback 404
app.use((req, res) => res.status(404).json({ ok: false, error: 'not_found' }));

// A malformed JSON body (or any other Express error) answers in JSON, so the
// backend reads an error code instead of an HTML page.
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  const status = err && err.type === 'entity.parse.failed' ? 400 : (err && err.status) || 500;
  res.status(status).json({ ok: false, error: status === 400 ? 'BAD_REQUEST' : 'ERROR',
    message: status === 400 ? 'body is not valid JSON' : 'internal error' });
});

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

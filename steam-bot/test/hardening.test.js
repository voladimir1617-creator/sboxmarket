'use strict';

// Restart-safe confirmations, loopback-only bind, non-retryable 401, error
// classification and Steam Guard timing. No network, no Steam account.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const {
  createBotCore, confirmBackoffMs, CONFIRM_FAST_MS, CONFIRM_BACKOFF_MAX_MS, CONFIRM_GIVE_UP_MS,
} = require('../lib/bot-core');
const { createStateStore } = require('../lib/state-store');
const g = require('../lib/guards');
const { createFakeBot } = require('../fake-bot');
const { createSteamGuard, msUntilNextWindow, MAX_WRONG_CODES } = require('../lib/totp');
const { webSessionRetryDelayMs, MAX_DELAY_MS } = require('../lib/reconnect');
const { createStandInSteam, S } = require('./helpers/stand-in-steam');

const BUYER_URL = 'https://steamcommunity.com/tradeoffer/new/?partner=22&token=xyz';
const quiet = { log() {}, warn() {}, error() {} };

function tmpFile() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sbox-bot-state-'));
  return path.join(dir, 'nested', 'state.json');
}

function setup(file, opts = {}) {
  const steam = createStandInSteam();
  let clock = opts.clock || 0;
  const mk = () => createBotCore({
    getManager: () => steam.manager, community: steam.community, identitySecret: 'id-secret',
    log: quiet, now: () => clock, store: createStateStore(file, { log: quiet }),
  });
  return { steam, core: mk(), mk, tick: (ms) => { clock += ms; }, setClock: (t) => { clock = t; } };
}

// ---------------------------------------------------------------------------
// 3. Offers awaiting confirmation survive a restart and keep being retried
// ---------------------------------------------------------------------------

test('an offer awaiting confirmation is restored from the state file and confirmed after a restart', async () => {
  const file = tmpFile();
  const { steam, core, mk } = setup(file);
  steam.knobs.confirmFails = 1;
  const [, sent] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(sent.status, 'pending');
  assert.ok(fs.existsSync(file), 'state written on the first failed confirmation');
  const onDisk = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.deepStrictEqual(onDisk.needsConfirm.map(([id]) => id), [sent.offerId]);
  assert.ok(!JSON.stringify(onDisk).includes('token=xyz'), 'no trade URL token on disk');

  const restarted = mk(); // new process, same file, same Steam
  assert.deepStrictEqual(restarted.pendingConfirmations(), [sent.offerId]);
  const out = await restarted.retryConfirmations();
  assert.deepStrictEqual(out.confirmed, [sent.offerId]);
  assert.strictEqual(steam.offers.get(sent.offerId).state, S.Active);
  assert.deepStrictEqual(JSON.parse(fs.readFileSync(file, 'utf8')).needsConfirm, []);
});

test('after a restart a retried give-send still finds the offer waiting on confirmation', async () => {
  // Steam's active-only list does not show needs_confirmation offers, so only
  // the restored sent registry stops a second offer for the same skin.
  const file = tmpFile();
  const { steam, core, mk } = setup(file);
  steam.knobs.confirmFails = 99;
  const [, first] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  const [st, again] = await mk().send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(st, 200);
  assert.strictEqual(again.offerId, first.offerId);
  assert.strictEqual(again.duplicate, true);
  assert.strictEqual(steam.calls.send, 1);
});

test('past the first hour retries back off (bounded) instead of giving up; a week ends it', async () => {
  const file = tmpFile();
  const { steam, core, mk, tick, setClock } = setup(file);
  steam.knobs.confirmFails = 1000;
  const [, sent] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });

  setClock(CONFIRM_FAST_MS + 1);
  let out = await core.retryConfirmations();
  assert.deepStrictEqual(out.failed, [sent.offerId]);
  out = await core.retryConfirmations();
  assert.deepStrictEqual(out.deferred, [sent.offerId], 'waits for its backoff');
  tick(confirmBackoffMs(1));
  out = await core.retryConfirmations();
  assert.deepStrictEqual(out.failed, [sent.offerId], 'tried again once the delay passed');

  // The backoff schedule survives a restart too.
  const restarted = mk();
  out = await restarted.retryConfirmations();
  assert.deepStrictEqual(out.deferred, [sent.offerId]);

  // Still waiting for its confirmation and Steam comes back: it gets confirmed.
  steam.knobs.confirmFails = 0;
  tick(CONFIRM_BACKOFF_MAX_MS);
  out = await restarted.retryConfirmations();
  assert.deepStrictEqual(out.confirmed, [sent.offerId]);

  // A different offer stuck for over a week is finally dropped.
  steam.knobs.confirmFails = 1000;
  const [, other] = await restarted.send({ partnerTradeUrl: BUYER_URL, assetIds: ['701'] });
  tick(CONFIRM_GIVE_UP_MS + 1);
  out = await restarted.retryConfirmations();
  assert.deepStrictEqual(out.dropped, [other.offerId]);
  assert.deepStrictEqual(restarted.pendingConfirmations(), []);
});

test('the confirmation backoff doubles and is capped', () => {
  assert.strictEqual(confirmBackoffMs(1), 60 * 1000);
  assert.strictEqual(confirmBackoffMs(2), 120 * 1000);
  assert.strictEqual(confirmBackoffMs(50), CONFIRM_BACKOFF_MAX_MS);
});

test('a corrupt state file is moved aside and the bot starts empty', () => {
  const file = tmpFile();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, '{not json');
  const loaded = createStateStore(file, { log: quiet }).load();
  assert.deepStrictEqual(loaded, { sent: [], needsConfirm: [] });
  assert.ok(!fs.existsSync(file));
  assert.ok(fs.readdirSync(path.dirname(file)).some((n) => n.startsWith('state.json.corrupt-')));
});

test('a missing state file is an empty start, and junk rows are ignored', () => {
  const file = tmpFile();
  const store = createStateStore(file, { log: quiet });
  assert.deepStrictEqual(store.load(), { sent: [], needsConfirm: [] });
  assert.ok(store.save({ sent: [['123', { kind: 'give', assetIds: ['1'] }], ['abc', {}], 'x'], needsConfirm: [] }));
  assert.deepStrictEqual(store.load().sent, [['123', { kind: 'give', assetIds: ['1'] }]]);
});

// ---------------------------------------------------------------------------
// 4. BOT_HOST is loopback-only unless BOT_ALLOW_REMOTE=yes
// ---------------------------------------------------------------------------

test('BOT_HOST must be loopback unless BOT_ALLOW_REMOTE=yes', () => {
  for (const h of ['127.0.0.1', '::1', 'localhost', 'LOCALHOST', ' 127.0.0.1 ']) {
    assert.strictEqual(g.bindHostProblem(h, undefined), null, h);
  }
  for (const h of ['0.0.0.0', '::', '192.168.1.10', '', 'example.com', '127.0.0.2']) {
    assert.match(g.bindHostProblem(h, undefined), /not a loopback address/, h);
    assert.match(g.bindHostProblem(h, 'no'), /BOT_ALLOW_REMOTE=yes/, h);
    assert.match(g.bindHostProblem(h, 'true'), /not a loopback/, 'only the exact word yes opts in');
  }
  assert.strictEqual(g.bindHostProblem('0.0.0.0', 'yes'), null);
});

function runIndex(extraEnv) {
  // Every value is a dummy. Each case below fails validation, which exits
  // before any Steam library is even loaded, so nothing reaches the network.
  const env = {
    PATH: process.env.PATH,
    STEAM_BOT_USERNAME: 'dummy-user', STEAM_BOT_PASSWORD: 'dummy-pass',
    STEAM_BOT_SHARED_SECRET: 'AAAAAAAAAAAAAAAAAAAAAAAAAAA=', STEAM_BOT_IDENTITY_SECRET: 'AAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    BOT_API_TOKEN: 'x'.repeat(40), STEAM_BOT_STATE_FILE: tmpFile(),
    ...extraEnv,
  };
  return spawnSync(process.execPath, [path.join(__dirname, '..', 'index.js')],
    { env, encoding: 'utf8', timeout: 15000 });
}

test('the real bot refuses to start on a non-loopback BOT_HOST', () => {
  const r = runIndex({ STEAM_BOT_LIVE: 'yes', BOT_HOST: '0.0.0.0' });
  assert.strictEqual(r.status, 1, r.stderr);
  assert.match(r.stderr, /1 configuration problem/);
  assert.match(r.stderr, /BOT_HOST="0\.0\.0\.0" is not a loopback address/);
});

test('BOT_ALLOW_REMOTE=yes lifts the bind check (the go-live gate still stops this run)', () => {
  const r = runIndex({ STEAM_BOT_LIVE: 'no', BOT_HOST: '0.0.0.0', BOT_ALLOW_REMOTE: 'yes' });
  assert.strictEqual(r.status, 1, r.stderr);
  assert.doesNotMatch(r.stderr, /BOT_HOST/);
  assert.match(r.stderr, /STEAM_BOT_LIVE is not "yes"/);
});

// ---------------------------------------------------------------------------
// 5. A 401 is a non-retryable configuration error
// ---------------------------------------------------------------------------

function fakeRes() {
  return { statusCode: 200, body: null, status(c) { this.statusCode = c; return this; }, json(b) { this.body = b; return this; } };
}

test('the real bot\'s token check answers 401 with retryable:false', () => {
  const mw = g.requireToken('right-token');
  const req = (p, auth) => ({ path: p, get: (h) => (h.toLowerCase() === 'authorization' ? auth : undefined) });
  let passed = 0;
  const next = () => { passed += 1; };

  const res = fakeRes();
  mw(req('/inventory', 'Bearer wrong-token'), res, next);
  assert.strictEqual(res.statusCode, 401);
  assert.strictEqual(res.body.ok, false);
  assert.strictEqual(res.body.error, 'unauthorized');
  assert.strictEqual(res.body.retryable, false, 'the backend must not retry a token mismatch');
  assert.strictEqual(res.body.configError, true);

  const none = fakeRes();
  mw(req('/offers/send', undefined), none, next);
  assert.strictEqual(none.statusCode, 401);
  assert.strictEqual(none.body.retryable, false);

  mw(req('/health', undefined), fakeRes(), next);
  mw(req('/inventory', 'Bearer right-token'), fakeRes(), next);
  assert.strictEqual(passed, 2);
});

test('the fake bot\'s 401 carries the same retryable:false body', async () => {
  const bot = createFakeBot({ apiToken: 'right-token', acceptAfterMs: -1 });
  await new Promise((r) => bot.server.listen(0, '127.0.0.1', r));
  try {
    const res = await fetch(`http://127.0.0.1:${bot.server.address().port}/inventory`,
      { headers: { authorization: 'Bearer wrong' } });
    assert.strictEqual(res.status, 401);
    const body = await res.json();
    assert.strictEqual(body.retryable, false);
    assert.strictEqual(body.error, 'unauthorized');
  } finally { bot.server.close(); }
});

test('classification: err.cause first, string eresult read, text-only inventory errors retryable', () => {
  const e = (msg, extra) => Object.assign(new Error(msg), extra || {});
  // steam-tradeoffer-manager helpers.js sets cause with no eresult in the text.
  const ban = g.classifyError(e('You cannot trade with Bob because they have a trade ban.', { cause: 'TradeBan' }));
  assert.deepStrictEqual([ban.code, ban.retryable], ['TRADE_BANNED_OR_UNTRADABLE', false]);
  const cannot = g.classifyError(e('Bob is not available to trade. More information will be shown to Bob', { cause: 'TargetCannotTrade' }));
  assert.strictEqual(cannot.retryable, false);
  const limit = g.classifyError(e('You have sent too many trade offers', { cause: 'OfferLimitExceeded', eresult: 25 }));
  assert.deepStrictEqual([limit.code, limit.retryable], ['LIMIT_EXCEEDED', true]);
  assert.strictEqual(g.httpStatusFor('LIMIT_EXCEEDED'), 502, 'not a 429: nothing is throttled');
  const itemServer = g.classifyError(e("unable to contact the game's item server", { cause: 'ItemServerUnavailable', eresult: 20 }));
  assert.deepStrictEqual([itemServer.code, itemServer.retryable], ['STEAM_UNAVAILABLE', true]);

  // steamcommunity users.js stores the inventory eresult as a captured string.
  const revoked = g.classifyError(e('Item revoked', { eresult: '26' }));
  assert.deepStrictEqual([revoked.code, revoked.retryable], ['INVENTORY_ERROR', false]);
  const busy = g.classifyError(e('Failed loading inventory', { eresult: '16' }));
  assert.deepStrictEqual([busy.code, busy.retryable], ['STEAM_UNAVAILABLE', true]);

  // An inventory failure known only from its text is not final any more.
  const text = g.classifyError(e('There was an error loading the inventory'));
  assert.deepStrictEqual([text.code, text.retryable], ['INVENTORY_ERROR', true]);
  const gone = g.classifyError(e('x', { eresult: 26 }));
  assert.strictEqual(gone.retryable, false);
});

// ---------------------------------------------------------------------------
// 1 + 2. web-session retry delay, Steam Guard codes (no stdin, next window)
// ---------------------------------------------------------------------------

test('a failed setCookies is retried on a growing, capped delay', () => {
  assert.strictEqual(webSessionRetryDelayMs(1), 60 * 1000);
  assert.strictEqual(webSessionRetryDelayMs(2), 120 * 1000);
  assert.strictEqual(webSessionRetryDelayMs(30), MAX_DELAY_MS);
});

test('the next TOTP window is computed on Steam\'s clock', () => {
  assert.strictEqual(msUntilNextWindow(0, 0), 31000);
  assert.strictEqual(msUntilNextWindow(29000, 0), 2000);
  assert.strictEqual(msUntilNextWindow(0, 10), 21000, 'Steam 10 s ahead: its window ends sooner');
  assert.strictEqual(msUntilNextWindow(0, -10), 11000);
});

function guardHarness() {
  let clock = 1000 * 30 * 1000 + 5000; // 5 s into a window
  const timers = [];
  const stops = [];
  const totp = {
    offsetCalls: 0,
    getTimeOffset(cb) { this.offsetCalls += 1; setImmediate(() => cb(null, 0, 1)); },
    generateAuthCode: (secret, off) => 'C' + Math.floor((clock / 1000 + off) / 30),
  };
  const guard = createSteamGuard({
    SteamTotp: totp, sharedSecret: 's', log: quiet, now: () => clock,
    setTimer: (fn, ms) => { const t = { fn, ms }; timers.push(t); return t; },
    onStop: (why) => stops.push(why),
  });
  return { guard, totp, timers, stops, advance: (ms) => { clock += ms; } };
}

const flush = () => new Promise((r) => setImmediate(r));

test('a rejected Steam Guard code waits for the next window and sends a different code', async () => {
  const h = guardHarness();
  const first = await h.guard.loginCode();
  const got = [];
  h.guard.onSteamGuard(null, (c) => got.push(c), true);
  await flush(); await flush();
  const wait = h.timers.find((t) => t.ms !== 10000);
  assert.ok(wait, 'a wait was scheduled');
  assert.strictEqual(wait.ms, 26000, '25 s left in this window + 1 s margin');
  assert.deepStrictEqual(got, [], 'nothing resent inside the same window');
  h.advance(wait.ms);
  wait.fn();
  await flush();
  assert.strictEqual(got.length, 1);
  assert.notStrictEqual(got[0], first);
  assert.ok(h.totp.offsetCalls >= 2, 'Steam\'s clock re-read after a rejection');
});

test('a missing code is answered at once; an email code or repeated rejections stop without prompting', async () => {
  const h = guardHarness();
  const got = [];
  h.guard.onSteamGuard(null, (c) => got.push(c), false);
  await flush(); await flush();
  assert.strictEqual(got.length, 1);

  h.guard.onSteamGuard('gmail.com', () => assert.fail('no email code is ever invented'), false);
  assert.match(h.stops[0], /EMAIL Steam Guard code/);

  for (let i = 0; i < MAX_WRONG_CODES + 1; i++) h.guard.onSteamGuard(null, () => {}, true);
  assert.match(h.stops[1], /rejected 4 Steam Guard codes in a row/);
  h.guard.onLoggedOn();
  assert.strictEqual(h.guard.wrongInARow(), 0);
});

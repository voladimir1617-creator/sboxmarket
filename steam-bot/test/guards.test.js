'use strict';

const test = require('node:test');
const assert = require('node:assert');
const g = require('../lib/guards');
const { createReconnector, BASE_DELAY_MS, MAX_DELAY_MS, THROTTLED_DELAY_MS } = require('../lib/reconnect');

test('trade urls: only real Steam trade links', () => {
  assert.strictEqual(g.parseTradeUrl('https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc').steamId64,
    '76561197960265739');
  for (const bad of [
    'https://steamcommunity.com.evil.example/tradeoffer/new/?partner=1&token=a',
    'https://evil.example/?u=steamcommunity.com/tradeoffer/new/?partner=1&token=a',
    'https://user@steamcommunity.com/tradeoffer/new/?partner=1&token=a',
    'https://steamcommunity.com/tradeoffer/new/?partner=1',
    'https://steamcommunity.com/tradeoffer/new/?partner=0&token=a',
    'https://steamcommunity.com/tradeoffer/new/?partner=99999999999&token=a',
    'https://steamcommunity.com/tradeoffer/new/?partner=1&token=a b',
    'https://steamcommunity.com/id/someone/?partner=1&token=a',
    'javascript:alert(1)',
    42,
  ]) assert.strictEqual(g.parseTradeUrl(bad), null, String(bad));
});

test('trade tokens never reach a log line', () => {
  assert.strictEqual(g.redactTradeUrl('https://steamcommunity.com/tradeoffer/new/?partner=1&token=SECRET'),
    'https://steamcommunity.com/tradeoffer/new/?partner=1&token=***');
});

test('offer message: fits 128 chars and keeps the security code', () => {
  assert.strictEqual(g.fitOfferMessage(null), '');
  assert.strictEqual(g.fitOfferMessage('sboxmarket escrow · code ABC123'), 'sboxmarket escrow · code ABC123');
  const fitted = g.fitOfferMessage('y'.repeat(300) + ' · code ABC123');
  assert.ok(fitted.length <= g.MAX_OFFER_MESSAGE);
  assert.ok(fitted.endsWith(' · code ABC123'));
  assert.strictEqual(g.fitOfferMessage('z'.repeat(300)).length, 128);
});

test('the API token check is exact', () => {
  assert.ok(g.tokenMatches('Bearer s3cret', 's3cret'));
  assert.ok(g.tokenMatches('s3cret', 's3cret'));
  for (const bad of ['', 'Bearer ', 'Bearer s3cre', 'Bearer s3cret!', 'bearer s3cret', null]) {
    assert.ok(!g.tokenMatches(bad, 's3cret'), String(bad));
  }
  assert.ok(!g.tokenMatches('Bearer x', ''), 'an empty configured token matches nothing');
});

test('Steam failures map to stable codes, eresult first', () => {
  const e = (msg, eresult) => Object.assign(new Error(msg), eresult == null ? {} : { eresult });
  assert.strictEqual(g.classifyError(e('x', 25)).code, 'RATE_LIMITED');
  assert.strictEqual(g.classifyError(e('x', 15)).code, 'BAD_TRADE_URL');
  assert.strictEqual(g.classifyError(e('x', 26)).code, 'INVENTORY_ERROR');
  assert.strictEqual(g.classifyError(e('x', 16)).code, 'STEAM_UNAVAILABLE');
  assert.strictEqual(g.classifyError(e('x', 11)).code, 'TRADE_BANNED_OR_UNTRADABLE');
  assert.strictEqual(g.classifyError(e('HTTP error 429')).code, 'RATE_LIMITED');
  assert.strictEqual(g.classifyError(e('socket hang up')).code, 'STEAM_UNAVAILABLE');
  assert.strictEqual(g.classifyError(e('Not Logged In')).code, 'NOT_READY');
  assert.strictEqual(g.classifyError(e('household goods')).code, 'ERROR', '"hold" inside a word is not an escrow hold');
  assert.strictEqual(g.classifyError(e('x', 11)).retryable, false);
  assert.strictEqual(g.classifyError(e('x', 20)).retryable, true);
});

function fakeTimers() {
  const pending = [];
  return {
    pending,
    setTimer: (fn, ms) => { const t = { fn, ms }; pending.push(t); return t; },
    clearTimer: (t) => { const i = pending.indexOf(t); if (i >= 0) pending.splice(i, 1); },
  };
}
const quiet = { log() {}, warn() {}, error() {} };

test('after a fatal Steam error the bot logs in again, waiting longer each time', () => {
  const timers = fakeTimers();
  let logins = 0;
  const r = createReconnector({ logIn: () => { logins += 1; }, ...timers, log: quiet });
  const delays = [];
  for (let i = 0; i < 8; i++) delays.push(r.onFatalError(Object.assign(new Error('x'), { eresult: 2 })));
  assert.strictEqual(delays[0], BASE_DELAY_MS);
  assert.strictEqual(delays[1], BASE_DELAY_MS * 2);
  assert.strictEqual(delays[7], MAX_DELAY_MS);
  assert.strictEqual(timers.pending.length, 1, 'one re-login queued at a time');
  timers.pending[0].fn();
  assert.strictEqual(logins, 1);
  r.onLoggedOn();
  assert.strictEqual(r.onFatalError(new Error('x')), BASE_DELAY_MS, 'a good login resets the wait');
});

test('a wrong password is not retried; Steam\'s login throttle gets the long wait', () => {
  const timers = fakeTimers();
  const r = createReconnector({ logIn: () => {}, ...timers, log: quiet });
  assert.strictEqual(r.onFatalError(Object.assign(new Error('throttled'), { eresult: 87 })), THROTTLED_DELAY_MS);
  assert.strictEqual(r.onFatalError(Object.assign(new Error('InvalidPassword'), { eresult: 5 })), null);
  assert.ok(r.isStopped());
  assert.strictEqual(r.onFatalError(new Error('later')), null);
});

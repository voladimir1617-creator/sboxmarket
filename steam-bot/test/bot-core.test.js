'use strict';

// The real sidecar's trade logic (lib/bot-core.js) against a stand-in Steam.
// node --test steam-bot/test   (no npm install needed)
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { createBotCore } = require('../lib/bot-core');
const { createStandInSteam, S } = require('./helpers/stand-in-steam');

const SELLER_URL = 'https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc';
const BUYER_URL = 'https://steamcommunity.com/tradeoffer/new/?partner=22&token=xyz';
const OTHER_BUYER_URL = 'https://steamcommunity.com/tradeoffer/new/?partner=33&token=qqq';
const SELLER = '76561197960265739';
const quiet = { log() {}, warn() {}, error() {} };

function setup(opts = {}) {
  const steam = createStandInSteam(opts);
  const mk = () => createBotCore({
    getManager: () => steam.manager, community: steam.community, identitySecret: 'id-secret',
    isReady: opts.isReady, log: quiet, now: opts.now,
  });
  return { steam, core: mk(), mk };
}

test('a retried delivery gets the offer already sent, not a second offer', async () => {
  const { steam, core } = setup();
  const [s1, b1] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  const [s2, b2] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(s1, 200);
  assert.strictEqual(s2, 200);
  assert.strictEqual(b2.offerId, b1.offerId);
  assert.strictEqual(b2.duplicate, true);
  assert.strictEqual(steam.calls.send, 1);
});

test('two identical sends at the same moment make one Steam offer', async () => {
  const { steam, core } = setup({ sendDelayMs: 20 });
  const [[, a], [, b]] = await Promise.all([
    core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] }),
    core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] }),
  ]);
  assert.strictEqual(a.offerId, b.offerId);
  assert.strictEqual(steam.calls.send, 1);
});

test('after a restart the live offer on Steam still stops a second send', async () => {
  const { steam, core, mk } = setup();
  const [, first] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] });
  const restarted = mk(); // empty memory, same Steam
  const [, again] = await restarted.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] });
  assert.strictEqual(again.offerId, first.offerId);
  assert.strictEqual(steam.calls.send, 1);
});

test('one held skin is never offered to two buyers', async () => {
  const { steam, core } = setup();
  const [ok] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  const [st, body] = await core.send({ partnerTradeUrl: OTHER_BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(ok, 200);
  assert.strictEqual(st, 409);
  assert.strictEqual(body.error, 'ASSET_COMMITTED');
  assert.strictEqual(steam.calls.send, 1);
});

test('two buyers racing for one skin: one offer, the other refused', async () => {
  const { steam, core } = setup({ sendDelayMs: 20 });
  const results = await Promise.all([
    core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] }),
    core.send({ partnerTradeUrl: OTHER_BUYER_URL, assetIds: ['700'] }),
  ]);
  assert.deepStrictEqual(results.map(([s]) => s).sort(), [200, 409]);
  assert.strictEqual(steam.calls.send, 1);
});

test('a declined or expired offer can be sent again; an accepted deposit is not re-requested', async () => {
  const { steam, core } = setup();
  const [, first] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  steam.settle(first.offerId, S.Declined);
  const [, second] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.notStrictEqual(second.offerId, first.offerId);
  steam.settle(second.offerId, S.Expired);
  const [, third] = await core.send({ partnerTradeUrl: OTHER_BUYER_URL, assetIds: ['700'] });
  assert.ok(third.ok, 'an expired offer no longer holds the skin');

  const [, dep] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] });
  steam.settle(dep.offerId, S.Accepted);
  const [, depAgain] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] });
  assert.strictEqual(depAgain.offerId, dep.offerId);
  assert.strictEqual(depAgain.duplicate, true);
  assert.strictEqual(steam.calls.send, 4);
});

test('the security code reaches the Steam offer, even behind a long message', async () => {
  const { steam, core } = setup();
  const [, short] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'],
    message: 'sboxmarket escrow · code K7Q2ZP' });
  assert.strictEqual(steam.offers.get(short.offerId).message, 'sboxmarket escrow · code K7Q2ZP');
  assert.strictEqual((await core.getOffer(short.offerId))[1].message, 'sboxmarket escrow · code K7Q2ZP');

  const long = 'x'.repeat(200) + ' · code K7Q2ZP';
  const [, l] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['501'], message: long });
  const sentMsg = steam.offers.get(l.offerId).message;
  assert.ok(sentMsg.length <= 128);
  assert.ok(sentMsg.endsWith('code K7Q2ZP'), sentMsg);

  const [, nl] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['502'], message: 'line1\nline2\u0000 · code AB12' });
  assert.strictEqual(steam.offers.get(nl.offerId).message, 'line1 line2 · code AB12');
});

test('an incoming offer that asks for the bot\'s items is refused, never accepted', async () => {
  const { steam, core } = setup();
  const swap = steam.incoming({ fromId64: SELLER, give: ['700'], receive: ['900'] });
  const [st, body] = await core.acceptIncoming(swap.id);
  assert.strictEqual(st, 409);
  assert.strictEqual(body.error, 'GIVES_ITEMS');
  assert.deepStrictEqual(steam.calls.accept, []);

  const old = steam.incoming({ fromId64: SELLER, receive: ['901'], state: S.Canceled });
  assert.strictEqual((await core.acceptIncoming(old.id))[1].error, 'OFFER_NOT_ACTIVE');
  const empty = steam.incoming({ fromId64: SELLER });
  assert.strictEqual((await core.acceptIncoming(empty.id))[1].error, 'NOTHING_TO_RECEIVE');

  const gift = steam.incoming({ fromId64: SELLER, receive: ['902'] });
  const [ok, acc] = await core.acceptIncoming(gift.id);
  assert.strictEqual(ok, 200);
  assert.strictEqual(acc.status, 'accepted');
  assert.deepStrictEqual(steam.calls.accept, [gift.id]);

  const [s3, own] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(s3, 200);
  assert.strictEqual((await core.acceptIncoming(own.offerId))[0], 400, 'our own offer is not incoming');
});

test('a failed confirmation is retried for the bot\'s own offer only', async () => {
  const { steam, core } = setup();
  steam.knobs.confirmFails = 1;
  const [, sent] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(sent.confirmed, false);
  assert.strictEqual(sent.status, 'pending');
  assert.deepStrictEqual(core.pendingConfirmations(), [sent.offerId]);

  // Someone else's trade waiting on the account's confirmation is never touched.
  const foreign = steam.incoming({ fromId64: SELLER, give: ['800'], state: S.CreatedNeedsConfirmation });
  const out = await core.retryConfirmations();
  assert.deepStrictEqual(out.confirmed, [sent.offerId]);
  assert.ok(!steam.calls.confirm.includes(foreign.id));
  assert.deepStrictEqual(core.pendingConfirmations(), []);
  assert.strictEqual(steam.offers.get(sent.offerId).state, S.Active);
});

test('a confirmation stuck past an hour keeps being retried; a settled one is dropped', async () => {
  let clock = 0;
  const { steam, core } = setup({ now: () => clock });
  steam.knobs.confirmFails = 5;
  const [, a] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  const [, b] = await core.send({ partnerTradeUrl: OTHER_BUYER_URL, assetIds: ['701'] });
  steam.settle(b.offerId, S.Canceled);
  clock = 61 * 60 * 1000;
  const out = await core.retryConfirmations();
  assert.deepStrictEqual(out.dropped, [b.offerId]);
  assert.deepStrictEqual(out.failed, [a.offerId], 'still tried after the first hour');
  assert.deepStrictEqual(core.pendingConfirmations(), [a.offerId]);
});

test('when Steam cannot be asked about live offers, nothing is sent', async () => {
  const { steam, core } = setup();
  steam.knobs.getOffersFails = true;
  const [st, body] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(st, 503);
  assert.strictEqual(body.retryable, true);
  assert.strictEqual(steam.calls.send, 0);
});

test('a send Steam rejects is reported with a stable code and can be retried', async () => {
  const { steam, core } = setup();
  steam.knobs.sendError = Object.assign(new Error('There was an error sending your trade offer.'), { eresult: 84 });
  const [st, body] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(st, 429);
  assert.strictEqual(body.error, 'RATE_LIMITED');
  assert.strictEqual(body.retryable, true);
  const [ok] = await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] });
  assert.strictEqual(ok, 200);
});

test('bad input is refused before Steam is asked anything', async () => {
  const { steam, core } = setup();
  const cases = [
    [{ partnerTradeUrl: 'https://steamcommunity.com.evil.example/tradeoffer/new/?partner=1&token=a', assetIds: ['1'] }, 'BAD_TRADE_URL'],
    [{ partnerTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1', assetIds: ['1'] }, 'BAD_TRADE_URL'],
    [{ partnerTradeUrl: BUYER_URL, assetIds: ['12a'] }, 'BAD_REQUEST'],
    [{ partnerTradeUrl: BUYER_URL, assetIds: [{ $gt: '' }] }, 'BAD_REQUEST'],
    [{ partnerTradeUrl: BUYER_URL, assetIds: ['5', '5'] }, 'BAD_REQUEST'],
    [{ partnerTradeUrl: BUYER_URL, assetIds: Array.from({ length: 21 }, (_, i) => String(i + 1)) }, 'BAD_REQUEST'],
    [{ partnerTradeUrl: BUYER_URL, assetIds: ['5'], message: { x: 1 } }, 'BAD_REQUEST'],
    [null, 'BAD_REQUEST'],
  ];
  for (const [body, code] of cases) {
    const [st, out] = await core.send(body);
    assert.strictEqual(st, 400, JSON.stringify(body));
    assert.strictEqual(out.error, code, JSON.stringify(body));
  }
  assert.strictEqual((await core.getOffer('../inventory'))[0], 400);
  assert.strictEqual((await core.acceptIncoming('1 OR 1'))[0], 400);
  assert.strictEqual(steam.calls.send, 0);
});

test('nothing moves while the bot is not logged in', async () => {
  const { steam, core } = setup({ isReady: () => false });
  for (const [st, body] of [
    await core.send({ partnerTradeUrl: BUYER_URL, assetIds: ['700'] }),
    await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] }),
    await core.getOffer('1'),
    await core.acceptIncoming('1'),
    await core.inventory(),
  ]) {
    assert.strictEqual(st, 503);
    assert.strictEqual(body.error, 'NOT_READY');
  }
  assert.strictEqual(steam.calls.send, 0);
});

test('offer status, unknown offers and the bot inventory keep the backend\'s shape', async () => {
  const { core } = setup();
  const [, sent] = await core.request({ partnerTradeUrl: SELLER_URL, assetIds: ['500'] });
  const [st, o] = await core.getOffer(sent.offerId);
  assert.strictEqual(st, 200);
  assert.strictEqual(o.status, 'active');
  assert.strictEqual(o.partner, SELLER);
  assert.strictEqual((await core.getOffer('42'))[0], 404);
  const [, inv] = await core.inventory();
  assert.deepStrictEqual(inv.items[0], { assetId: '111', classId: '9', instanceId: '0',
    marketHashName: 'Test Hat', name: 'Test Hat', tradable: true });
});

test('the inventory includes trade-protected skins, flagged not tradable', async () => {
  // A deposit under Steam's 7-day Trade Protection reads non-tradable. Asking
  // Steam for tradable items only hid it, so the backend never saw it land.
  const { steam, core } = setup();
  const [st, inv] = await core.inventory();
  assert.strictEqual(st, 200);
  assert.strictEqual(steam.calls.inventoryTradableOnly, false);
  assert.strictEqual(inv.count, 2);
  assert.deepStrictEqual(inv.items.map((it) => [it.assetId, it.tradable]), [['111', true], ['112', false]]);
});

test('index.js never hands the identity secret to the confirm-everything poller', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'index.js'), 'utf8')
    .replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.ok(!/startConfirmationChecker\s*\(/.test(src), 'startConfirmationChecker confirms every trade on the account');
  // guards.requireToken wraps the constant-time tokenMatches (tested in hardening.test.js).
  assert.ok(/app\.use\(guards\.requireToken\(CONFIG\.apiToken\)\)/.test(src), 'API token is compared in constant time');
  assert.ok(/createBotCore\(/.test(src), 'routes go through the tested core');
});

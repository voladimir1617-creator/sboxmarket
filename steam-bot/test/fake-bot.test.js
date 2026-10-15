'use strict';

// node --test steam-bot/test   (no npm install needed)
const test = require('node:test');
const assert = require('node:assert');
const { createFakeBot, partnerSteamId, BOT_STEAM_ID } = require('../fake-bot');

const TOKEN = 'test-token';
const SELLER_URL = 'https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc';
const BUYER_URL = 'https://steamcommunity.com/tradeoffer/new/?partner=22&token=xyz';
const SELLER = partnerSteamId(SELLER_URL);
const BUYER = partnerSteamId(BUYER_URL);

async function withBot(opts, fn) {
  const bot = createFakeBot({ apiToken: TOKEN, acceptAfterMs: -1, ...opts });
  await new Promise((r) => bot.server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${bot.server.address().port}`;
  const call = async (method, path, body, token = TOKEN) => {
    const res = await fetch(base + path, {
      method,
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, body: await res.json() };
  };
  try { await fn(call, bot); } finally { bot.server.close(); }
}

test('trade url partner maps to the steamid64 Steam uses', () => {
  assert.strictEqual(partnerSteamId(SELLER_URL), '76561197960265739');
  assert.strictEqual(partnerSteamId('https://evil.example/tradeoffer/new/?partner=1&token=a'), null);
  assert.strictEqual(partnerSteamId('https://steamcommunity.com/tradeoffer/new/?partner=1'), null);
});

test('every route but /health and public inventories needs the token', () => withBot({}, async (call) => {
  assert.strictEqual((await call('GET', '/health', null, null)).status, 200);
  assert.strictEqual((await call('GET', `/inventory/${SELLER}/590830/2`, null, null)).status, 200);
  assert.strictEqual((await call('GET', '/inventory', null, null)).status, 401);
  assert.strictEqual((await call('GET', '/inventory', null, 'wrong')).status, 401);
  assert.strictEqual((await call('POST', '/offers/request', {}, null)).status, 401);
}));

test('deposit then delivery moves the skin seller -> bot -> buyer with fresh asset ids', () => withBot({}, async (call) => {
  const inv = (await call('GET', `/inventory/${SELLER}/590830/2`)).body;
  const asset = inv.assets[0];
  const name = inv.descriptions.find((d) => d.classid === asset.classid).market_hash_name;

  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  assert.strictEqual(dep.status, 200);
  assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.status, 'active');
  await call('POST', `/fake/offers/${dep.body.offerId}/accept`);
  assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.status, 'accepted');

  const held = (await call('GET', '/inventory')).body.items;
  assert.strictEqual(held.length, 1);
  assert.strictEqual(held[0].marketHashName, name);
  assert.notStrictEqual(held[0].assetId, asset.assetid, 'Steam re-issues the asset id on receipt');
  const sellerAfter = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets;
  assert.ok(!sellerAfter.some((a) => a.assetid === asset.assetid));

  const send = await call('POST', '/offers/send', { partnerTradeUrl: BUYER_URL, assetIds: [held[0].assetId] });
  assert.strictEqual(send.status, 200);
  await call('POST', `/fake/offers/${send.body.offerId}/accept`);
  assert.strictEqual((await call('GET', '/inventory')).body.count, 0);
  const buyerInv = (await call('GET', `/inventory/${BUYER}/590830/2`)).body;
  assert.ok(buyerInv.descriptions.some((d) => d.market_hash_name === name));
}));

test('a protected deposit reads not tradable, cannot be sent, and can be reversed', () => withBot({ protectMs: 60000 }, async (call) => {
  const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  await call('POST', `/fake/offers/${dep.body.offerId}/accept`);

  const [held] = (await call('GET', '/inventory')).body.items;
  assert.strictEqual(held.tradable, false, 'Trade Protection makes a fresh deposit non-tradable');
  const send = await call('POST', '/offers/send', { partnerTradeUrl: BUYER_URL, assetIds: [held.assetId] });
  assert.strictEqual(send.status, 502);
  assert.strictEqual(send.body.error, 'ITEM_NOT_TRADABLE');

  const before = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.total_inventory_count;
  const rev = await call('POST', `/fake/bot-items/${held.assetId}/reverse`);
  assert.strictEqual(rev.body.reversedTo, SELLER);
  assert.strictEqual((await call('GET', '/inventory')).body.count, 0, 'a reversed deposit leaves the bot');
  assert.strictEqual((await call('GET', `/inventory/${SELLER}/590830/2`)).body.total_inventory_count, before + 1);
}));

test('unlocking ends the protection early so the bot can deliver', () => withBot({ protectMs: 60000 }, async (call) => {
  const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  await call('POST', `/fake/offers/${dep.body.offerId}/accept`);
  const [held] = (await call('GET', '/inventory')).body.items;
  assert.strictEqual((await call('POST', `/fake/bot-items/${held.assetId}/unlock`)).body.tradable, true);
  assert.strictEqual((await call('GET', '/inventory')).body.items[0].tradable, true);
  const send = await call('POST', '/offers/send', { partnerTradeUrl: BUYER_URL, assetIds: [held.assetId] });
  assert.strictEqual(send.status, 200);
  assert.strictEqual((await call('POST', '/fake/bot-items/1/unlock', null, null)).status, 401);
}));

test('the bot cannot send what it does not hold, nor request what the seller lacks', () => withBot({}, async (call) => {
  const send = await call('POST', '/offers/send', { partnerTradeUrl: BUYER_URL, assetIds: ['123'] });
  assert.strictEqual(send.status, 502);
  const req = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: ['123'] });
  assert.strictEqual(req.status, 502);
  const bad = await call('POST', '/offers/request', { partnerTradeUrl: 'nope', assetIds: ['1'] });
  assert.strictEqual(bad.body.error, 'BAD_TRADE_URL');
}));

test('a declined deposit leaves the skin with the seller', () => withBot({}, async (call) => {
  const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  await call('POST', `/fake/offers/${dep.body.offerId}/decline`);
  assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.status, 'declined');
  assert.strictEqual((await call('GET', '/inventory')).body.count, 0);
  const still = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets;
  assert.ok(still.some((a) => a.assetid === asset.assetid));
}));

test('an item gone before the partner accepts ends invalid_items', () => withBot({}, async (call, bot) => {
  const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  bot.inventoryOf(SELLER).length = 0; // seller traded it away meanwhile
  await call('POST', `/fake/offers/${dep.body.offerId}/accept`);
  assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.status, 'invalid_items');
}));

test('partners accept by themselves after FAKE_ACCEPT_AFTER_MS', () => {
  let clock = 0;
  return withBot({ acceptAfterMs: 1000, now: () => clock }, async (call) => {
    const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
    const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
    assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.status, 'active');
    clock = 1000;
    assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.status, 'accepted');
  });
});

test('the bot starts empty and a user can be handed skins', () => withBot({}, async (call) => {
  assert.strictEqual((await call('GET', '/inventory')).body.count, 0);
  await call('POST', `/fake/inventory/${BUYER}`, { items: ['Gift Hat'] });
  const inv = (await call('GET', `/inventory/${BUYER}/590830/2`)).body;
  assert.ok(inv.descriptions.some((d) => d.market_hash_name === 'Gift Hat'));
  assert.strictEqual(BOT_STEAM_ID.length, 17);
}));

test('a retried deposit request gets the same offer back, not a second one', () => withBot({}, async (call) => {
  const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
  const first = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  const again = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  assert.strictEqual(again.status, 200);
  assert.strictEqual(again.body.offerId, first.body.offerId);
  assert.strictEqual(again.body.duplicate, true);
}));

test('a held skin promised to one buyer cannot be offered to another', () => withBot({}, async (call) => {
  const asset = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets[0];
  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [asset.assetid] });
  await call('POST', `/fake/offers/${dep.body.offerId}/accept`);
  const held = (await call('GET', '/inventory')).body.items[0].assetId;
  const toBuyer = await call('POST', '/offers/send', { partnerTradeUrl: BUYER_URL, assetIds: [held] });
  assert.strictEqual(toBuyer.status, 200);
  const toOther = await call('POST', '/offers/send', {
    partnerTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=33&token=qqq', assetIds: [held] });
  assert.strictEqual(toOther.status, 409);
  assert.strictEqual(toOther.body.error, 'ASSET_COMMITTED');
  // Once the buyer declines, the skin is free to go back to the seller.
  await call('POST', `/fake/offers/${toBuyer.body.offerId}/decline`);
  const back = await call('POST', '/offers/send', { partnerTradeUrl: SELLER_URL, assetIds: [held] });
  assert.strictEqual(back.status, 200);
}));

test('the security code written into the offer is what Steam shows the seller', () => withBot({}, async (call) => {
  const [a, b] = (await call('GET', `/inventory/${SELLER}/590830/2`)).body.assets;
  const dep = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [a.assetid],
    message: 'sboxmarket escrow · code K7Q2ZP' });
  assert.strictEqual((await call('GET', `/offers/${dep.body.offerId}`)).body.message, 'sboxmarket escrow · code K7Q2ZP');
  const long = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: [b.assetid],
    message: 'w'.repeat(200) + ' · code K7Q2ZP' });
  const shown = (await call('GET', `/offers/${long.body.offerId}`)).body.message;
  assert.ok(shown.length <= 128 && shown.endsWith('code K7Q2ZP'), shown);
}));

test('malformed ids and lookalike trade urls are refused', () => withBot({}, async (call) => {
  assert.strictEqual((await call('GET', '/offers/abc')).status, 400);
  const look = await call('POST', '/offers/request', {
    partnerTradeUrl: 'https://steamcommunity.com.evil.example/tradeoffer/new/?partner=11&token=abc', assetIds: ['1'] });
  assert.strictEqual(look.body.error, 'BAD_TRADE_URL');
  const dup = await call('POST', '/offers/request', { partnerTradeUrl: SELLER_URL, assetIds: ['1', '1'] });
  assert.strictEqual(dup.status, 400);
}));

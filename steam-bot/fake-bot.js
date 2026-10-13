'use strict';

/*
 * Fake Steam for the sboxmarket bot sidecar.
 *
 * Speaks exactly the HTTP API index.js speaks (/health, /offers/send,
 * /offers/request, /offers/:id, /offers/incoming/:id/accept, /inventory), but
 * against in-memory inventories instead of Steam. Nothing here logs in, holds a
 * secret, or opens a connection to Steam; it needs no npm install.
 *
 * It also plays every user's public Steam inventory at
 * GET /inventory/:steamId64/:appId/:contextId in Steam Community's own JSON
 * shape, so the backend can be pointed at it with
 * steam.inventory.base-url=http://127.0.0.1:4000 and a seller can list, deposit
 * and sell a skin end to end on a local run with no Steam account at all.
 *
 * What it imitates, because the backend depends on it:
 *   - an offer stays 'active' until the partner acts, then settles all at once;
 *   - Steam gives a received item a NEW asset id, keeping its market_hash_name,
 *     which is why custody-confirm matches by name as well as by id;
 *   - an offer whose items are gone by the time it settles ends 'invalid_items'.
 *
 * Partners "accept" on their own after FAKE_ACCEPT_AFTER_MS (default 2000; a
 * negative value means never). POST /fake/offers/:id/accept and
 * /fake/offers/:id/decline settle one by hand, and POST /fake/inventory/:steamId64
 * { "items": ["Name", ...] } gives a user skins to sell.
 *
 * Run: npm run fake   (BOT_API_TOKEN must match the backend's)
 */

const http = require('http');
const { parseTradeUrl, validateOfferBody, isValidOfferId, tokenMatches } = require('./lib/guards');

const BOT_STEAM_ID = '76561190000000001';
const DEFAULT_ITEMS = ['Fake Wizard Hat', 'Fake Leather Jacket', 'Fake Aviator Glasses'];
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '::1']);

/** steamid64 from a trade URL's partner= (a 32-bit account id), or null. */
function partnerSteamId(tradeUrl) {
  const parsed = parseTradeUrl(tradeUrl);
  return parsed ? parsed.steamId64 : null;
}

function createFakeBot(opts = {}) {
  const apiToken = opts.apiToken;
  const appId = String(opts.appId || 590830);
  const acceptAfterMs = opts.acceptAfterMs == null ? 2000 : Number(opts.acceptAfterMs);
  const seedItems = opts.seedItems || DEFAULT_ITEMS;
  const now = opts.now || (() => Date.now());
  if (!apiToken) throw new Error('BOT_API_TOKEN is required, even for the fake bot');

  let nextAsset = 1000000001;
  let nextOffer = 9000001;
  let nextClass = 1;
  const classIds = new Map();      // market_hash_name -> classid
  const inventories = new Map();   // steamId64 -> [item]
  const offers = new Map();        // offerId -> offer

  function makeItem(name) {
    if (!classIds.has(name)) classIds.set(name, String(nextClass++));
    return { assetId: String(nextAsset++), classId: classIds.get(name), marketHashName: name };
  }

  /** A user's inventory, seeded with a few skins the first time it is read. */
  function inventoryOf(steamId) {
    if (!inventories.has(steamId)) {
      inventories.set(steamId, steamId === BOT_STEAM_ID ? [] : seedItems.map(makeItem));
    }
    return inventories.get(steamId);
  }

  function take(steamId, assetIds) {
    const inv = inventoryOf(steamId);
    if (!assetIds.every((id) => inv.some((it) => it.assetId === id))) return null;
    const taken = assetIds.map((id) => inv.splice(inv.findIndex((it) => it.assetId === id), 1)[0]);
    return taken;
  }

  /** Move the items across, re-issuing asset ids the way Steam does. */
  function settle(offer, outcome) {
    if (offer.state !== 'active') return;
    if (outcome !== 'accepted') { offer.state = outcome; return; }
    const [from, to] = offer.kind === 'give' ? [BOT_STEAM_ID, offer.partner] : [offer.partner, BOT_STEAM_ID];
    const moved = take(from, offer.assetIds);
    if (!moved) { offer.state = 'invalid_items'; return; }
    moved.forEach((it) => inventoryOf(to).push(makeItem(it.marketHashName)));
    offer.state = 'accepted';
  }

  // The same request checks the real sidecar makes (lib/guards.js), and the
  // same answer to a retried send: the offer already out, not a second one.
  function createOffer(kind, body) {
    const checked = validateOfferBody(body);
    if (!checked.ok) return [checked.status, { ok: false, error: checked.error, message: checked.message }];
    const { partner: { steamId64: partner }, assetIds: ids, message } = checked.value;
    for (const o of offers.values()) {
      if (o.kind !== kind || !o.assetIds.some((a) => ids.includes(a))) continue;
      const exact = o.partner === partner && o.assetIds.length === ids.length && ids.every((a) => o.assetIds.includes(a));
      if (exact && (o.state === 'active' || o.state === 'accepted')) {
        return [200, { ok: true, offerId: o.id, status: 'sent', steamStatus: o.state, confirmed: true,
          confirmError: null, duplicate: true }];
      }
      if (o.state === 'active') {
        return [409, { ok: false, error: 'ASSET_COMMITTED', retryable: false,
          message: 'an item in this offer is already in live offer #' + o.id }];
      }
    }
    const holder = kind === 'give' ? BOT_STEAM_ID : partner;
    const inv = inventoryOf(holder);
    if (!ids.every((id) => inv.some((it) => it.assetId === id))) {
      return [502, { ok: false, error: 'INVENTORY_ERROR',
        message: 'an item in this offer is not in the inventory it should come from' }];
    }
    const offer = { id: String(nextOffer++), kind, partner, assetIds: ids, message, state: 'active', createdAt: now() };
    offers.set(offer.id, offer);
    return [200, { ok: true, offerId: offer.id, status: 'sent', steamStatus: 'sent',
      confirmed: true, confirmError: null }];
  }

  function offerStatus(id) {
    const offer = offers.get(id);
    if (!offer) return [404, { ok: false, error: 'ERROR', message: 'no matching offer' }];
    if (offer.state === 'active' && acceptAfterMs >= 0 && now() - offer.createdAt >= acceptAfterMs) {
      settle(offer, 'accepted');
    }
    return [200, { ok: true, offerId: offer.id, status: offer.state, isOurOffer: true,
      partner: offer.partner, message: offer.message || null, escrowEndsUnix: null, tradeId: offer.state === 'accepted' ? `T${offer.id}` : null }];
  }

  /** Steam Community's public inventory JSON for one user. */
  function steamInventoryJson(steamId) {
    const inv = inventoryOf(steamId);
    const descriptions = new Map();
    inv.forEach((it) => descriptions.set(it.classId, {
      appid: Number(appId), classid: it.classId, instanceid: '0',
      market_hash_name: it.marketHashName, name: it.marketHashName,
      type: 'Clothing', tradable: 1, marketable: 1,
    }));
    return {
      assets: inv.map((it) => ({ appid: Number(appId), contextid: '2', assetid: it.assetId,
        classid: it.classId, instanceid: '0', amount: '1' })),
      descriptions: [...descriptions.values()],
      total_inventory_count: inv.length,
      success: 1,
    };
  }

  function route(method, path, body, auth) {
    let m;
    if (method === 'GET' && path === '/health') {
      return [200, { ok: true, loggedIn: true, ready: true, steamId: BOT_STEAM_ID, appId: Number(appId),
        lastError: null, fake: true }];
    }
    // Public, like Steam's own: /inventory/:steamId64/:appId/:contextId
    if (method === 'GET' && (m = path.match(/^\/inventory\/(\d{17})\/(\d+)\/(\d+)$/))) {
      if (m[2] !== appId) return [200, { assets: [], descriptions: [], total_inventory_count: 0, success: 1 }];
      return [200, steamInventoryJson(m[1])];
    }
    if (!tokenMatches(auth, apiToken)) return [401, { ok: false, error: 'unauthorized' }];

    if (method === 'POST' && path === '/offers/send') return createOffer('give', body);
    if (method === 'POST' && path === '/offers/request') return createOffer('receive', body);
    if (method === 'GET' && (m = path.match(/^\/offers\/([^/]+)$/))) {
      if (!isValidOfferId(m[1])) return [400, { ok: false, error: 'BAD_REQUEST', message: 'offer id must be digits' }];
      return offerStatus(m[1]);
    }
    if (method === 'POST' && /^\/offers\/incoming\/\d+\/accept$/.test(path)) {
      return [404, { ok: false, error: 'ERROR', message: 'no matching incoming offer' }];
    }
    if (method === 'GET' && path === '/inventory') {
      const inv = inventoryOf(BOT_STEAM_ID);
      return [200, { ok: true, appId: Number(appId), count: inv.length,
        items: inv.map((it) => ({ assetId: it.assetId, classId: it.classId, instanceId: '0',
          marketHashName: it.marketHashName, name: it.marketHashName, tradable: true })) }];
    }
    if (method === 'POST' && (m = path.match(/^\/fake\/offers\/(\d+)\/(accept|decline)$/))) {
      const offer = offers.get(m[1]);
      if (!offer) return [404, { ok: false, error: 'not_found' }];
      settle(offer, m[2] === 'accept' ? 'accepted' : 'declined');
      return [200, { ok: true, offerId: offer.id, status: offer.state }];
    }
    if (method === 'POST' && (m = path.match(/^\/fake\/inventory\/(\d{17})$/))) {
      const names = body && Array.isArray(body.items) ? body.items.map(String) : [];
      const added = names.map(makeItem);
      inventoryOf(m[1]).push(...added);
      return [200, { ok: true, added }];
    }
    return [404, { ok: false, error: 'not_found' }];
  }

  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; if (raw.length > 262144) req.destroy(); });
    req.on('end', () => {
      let body = null;
      if (raw) {
        try { body = JSON.parse(raw); } catch (e) {
          res.writeHead(400, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, error: 'BAD_REQUEST', message: 'body is not JSON' }));
        }
      }
      const path = req.url.split('?')[0];
      const [status, payload] = route(req.method, path, body, req.headers.authorization || '');
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(payload));
    });
  });

  return { server, BOT_STEAM_ID, inventoryOf, offers };
}

module.exports = { createFakeBot, partnerSteamId, BOT_STEAM_ID };

if (require.main === module) {
  const host = process.env.BOT_HOST || '127.0.0.1';
  const port = parseInt(process.env.BOT_PORT || '4000', 10);
  if (!LOOPBACK.has(host)) {
    console.error('[fake-bot] refusing to listen on %s: the fake bot is for this machine only', host);
    process.exit(1);
  }
  let bot;
  try {
    bot = createFakeBot({
      apiToken: process.env.BOT_API_TOKEN,
      acceptAfterMs: process.env.FAKE_ACCEPT_AFTER_MS,
    });
  } catch (e) {
    console.error('[fake-bot] %s', e.message);
    process.exit(1);
  }
  bot.server.listen(port, host, () => {
    console.log('[fake-bot] FAKE Steam bot on http://%s:%d. No Steam account, nothing real moves.', host, port);
  });
}

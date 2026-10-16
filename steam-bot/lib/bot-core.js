'use strict';

/*
 * The sidecar's trade logic, apart from Express and the Steam login so it can
 * be driven by tests against a stand-in TradeOfferManager. index.js wires it to
 * the real steam-tradeoffer-manager; nothing in here imports a Steam library.
 *
 * Every handler resolves to [httpStatus, jsonBody] and never throws.
 *
 * What it guards, beyond what Steam itself checks:
 *   - a retried send (the backend times out at 15 s, Steam plus a mobile
 *     confirmation can take longer, and the backend then re-sends) gets the
 *     SAME offer back instead of a second offer for the same skin;
 *   - one skin the bot holds is never promised to two people at once;
 *   - an incoming offer that asks the bot to GIVE anything is never accepted;
 *   - the bot confirms only offers it created itself (index.js no longer hands
 *     the identity secret to steamcommunity's confirm-everything poller);
 *   - the offer message keeps its security code inside Steam's 128 characters.
 */

const {
  validateOfferBody, isValidOfferId, classifyError, httpStatusFor, redactTradeUrl,
} = require('./guards');

// steam-tradeoffer-manager's ETradeOfferState / EOfferFilter. Stable Steam
// values; injected by index.js from the library and defaulted here for tests.
const DEFAULT_STATES = {
  Invalid: 1, Active: 2, Accepted: 3, Countered: 4, Expired: 5, Canceled: 6, Declined: 7,
  InvalidItems: 8, CreatedNeedsConfirmation: 9, CanceledBySecondFactor: 10, InEscrow: 11,
};
const ACTIVE_ONLY_FILTER = 1;

const REGISTRY_LIMIT = 5000;
const CONFIRM_GIVE_UP_MS = 60 * 60 * 1000;

function createBotCore(opts) {
  const getManager = opts.getManager;
  const community = opts.community;
  const identitySecret = opts.identitySecret;
  const appId = String(opts.appId || 590830);
  const contextId = String(opts.contextId || '2');
  const S = Object.assign({}, DEFAULT_STATES, opts.states || {});
  const isReady = opts.isReady || (() => true);
  const log = opts.log || console;
  const now = opts.now || (() => Date.now());

  // Offers this process sent: offerId -> { kind, partner, assetIds, key, at }.
  const sent = new Map();
  // key -> Promise of the send in progress, so two identical calls share one send.
  const inflight = new Map();
  // asset ids a give-offer is being built for right now -> key.
  const committing = new Map();
  // offerId -> { at, attempts } for our own offers whose confirmation failed.
  const needsConfirm = new Map();

  const LIVE = new Set([S.Active, S.CreatedNeedsConfirmation, S.InEscrow]);

  function stateName(n) {
    switch (n) {
      case S.Invalid: return 'invalid';
      case S.Active: return 'active';
      case S.Accepted: return 'accepted';
      case S.Countered: return 'countered';
      case S.Expired: return 'expired';
      case S.Canceled: return 'canceled';
      case S.Declined: return 'declined';
      case S.InvalidItems: return 'invalid_items';
      case S.CreatedNeedsConfirmation: return 'needs_confirmation';
      case S.CanceledBySecondFactor: return 'canceled_2fa';
      case S.InEscrow: return 'in_escrow';
      default: return 'unknown';
    }
  }

  const notReady = () => [503, { ok: false, error: 'NOT_READY', message: 'bot not ready / not logged in' }];

  function fail(err, fallbackStatus) {
    const e = classifyError(err);
    return [fallbackStatus || httpStatusFor(e.code), { ok: false, error: e.code, message: e.message, retryable: e.retryable }];
  }

  const p = (fn) => new Promise((resolve, reject) => fn((err, ...rest) => (err ? reject(err) : resolve(rest))));

  function partnerOf(offer) {
    if (!offer || !offer.partner) return null;
    return typeof offer.partner.getSteamID64 === 'function' ? offer.partner.getSteamID64() : String(offer.partner);
  }

  function assetsOf(offer, kind) {
    const list = (kind === 'give' ? offer.itemsToGive : offer.itemsToReceive) || [];
    return list.filter((it) => String(it.appid) === appId).map((it) => String(it.assetid));
  }

  function sameSet(a, b) {
    return a.length === b.length && a.every((x) => b.includes(x));
  }

  function remember(offerId, entry) {
    sent.set(String(offerId), entry);
    if (sent.size > REGISTRY_LIMIT) sent.delete(sent.keys().next().value);
  }

  /**
   * Look for a live offer already covering this request.
   * Returns { duplicate: offer } | { conflict: offerId } | {}.
   * Throws when Steam cannot be asked: a send is refused rather than risked.
   */
  async function findExisting(kind, v) {
    const manager = getManager();
    const candidates = new Map();

    // 1. Offers this process sent, refreshed from Steam (catches offers still
    //    waiting on our own confirmation, which an active-only list can miss).
    for (const [id, entry] of sent) {
      if (entry.kind !== kind) continue;
      if (!entry.assetIds.some((a) => v.assetIds.includes(a))) continue;
      let offer;
      try {
        [offer] = await p((cb) => manager.getOffer(id, cb));
      } catch (err) {
        // Steam no longer has it: nothing to collide with. Anything else is
        // a failed check and refuses the send.
        if (/no matching|not found/i.test(String(err && err.message))) { sent.delete(id); continue; }
        throw err;
      }
      candidates.set(String(offer.id), offer);
    }

    // 2. Every live offer Steam knows we sent: survives a sidecar restart.
    const [sentOffers] = await p((cb) => manager.getOffers(ACTIVE_ONLY_FILTER, cb));
    for (const offer of sentOffers || []) candidates.set(String(offer.id), offer);

    for (const offer of candidates.values()) {
      const assets = assetsOf(offer, kind);
      if (!assets.some((a) => v.assetIds.includes(a))) continue;
      const samePartner = partnerOf(offer) === v.partner.steamId64;
      const exact = samePartner && sameSet(assets, v.assetIds);
      if (exact && (LIVE.has(offer.state) || offer.state === S.Accepted)) return { duplicate: offer };
      if (LIVE.has(offer.state)) return { conflict: String(offer.id) };
    }
    return {};
  }

  async function confirmOwn(offer) {
    try {
      await p((cb) => community.acceptConfirmationForObject(identitySecret, offer.id, cb));
      needsConfirm.delete(String(offer.id));
      log.log('[bot] confirmed offer #%s', offer.id);
      return null;
    } catch (err) {
      if (!needsConfirm.has(String(offer.id))) needsConfirm.set(String(offer.id), { at: now(), attempts: 0 });
      log.error('[bot] confirmation failed for offer #%s, will retry: %s', offer.id, err.message || err);
      return err;
    }
  }

  async function doSend(kind, v, key) {
    const manager = getManager();
    let existing;
    try {
      existing = await findExisting(kind, v);
    } catch (err) {
      log.error('[bot] %s refused: could not check for an existing offer: %s', kind, err.message || err);
      const e = classifyError(err);
      return [503, { ok: false, error: e.code === 'ERROR' ? 'STEAM_UNAVAILABLE' : e.code,
        message: 'could not check Steam for an offer already covering these items: ' + e.message, retryable: true }];
    }
    if (existing.duplicate) {
      const o = existing.duplicate;
      log.log('[bot] %s to %s for [%s] already sent as offer #%s (%s); not sending again',
        kind, v.partner.steamId64, v.assetIds.join(','), o.id, stateName(o.state));
      return [200, { ok: true, offerId: String(o.id), status: o.state === S.CreatedNeedsConfirmation ? 'pending' : 'sent',
        steamStatus: stateName(o.state), confirmed: o.state !== S.CreatedNeedsConfirmation, confirmError: null, duplicate: true }];
    }
    if (existing.conflict) {
      log.warn('[bot] %s to %s refused: an item in [%s] is already in live offer #%s',
        kind, v.partner.steamId64, v.assetIds.join(','), existing.conflict);
      return [409, { ok: false, error: 'ASSET_COMMITTED', retryable: false,
        message: 'an item in this offer is already in live offer #' + existing.conflict }];
    }

    let offer;
    try {
      offer = manager.createOffer(v.partnerTradeUrl);
      const items = v.assetIds.map((id) => ({ appid: Number(appId), contextid: contextId, assetid: id, amount: 1 }));
      if (kind === 'give') offer.addMyItems(items);
      else items.forEach((it) => offer.addTheirItem(it));
      if (v.message) offer.setMessage(v.message);
    } catch (err) {
      return fail(err, 400);
    }

    let status;
    try {
      [status] = await p((cb) => offer.send(cb));
    } catch (err) {
      const e = classifyError(err);
      log.error('[bot] %s to %s for [%s] failed: %s %s', kind, redactTradeUrl(v.partnerTradeUrl),
        v.assetIds.join(','), e.code, e.message);
      return fail(err);
    }

    remember(offer.id, { kind, partner: v.partner.steamId64, assetIds: v.assetIds, key, at: now() });
    log.log('[bot] %s offer #%s sent to %s for [%s] (%s)', kind, offer.id, v.partner.steamId64,
      v.assetIds.join(','), status);

    let confirmErr = null;
    if (status === 'pending') confirmErr = await confirmOwn(offer);
    return [200, {
      ok: true,
      offerId: String(offer.id),
      status: status === 'pending' && confirmErr ? 'pending' : 'sent',
      steamStatus: status,
      confirmed: !confirmErr,
      confirmError: confirmErr ? classifyError(confirmErr).message : null,
    }];
  }

  async function createAndSend(kind, body) {
    if (!isReady()) return notReady();
    const checked = validateOfferBody(body);
    // The same body is refused the same way every time, so a retry never helps.
    if (!checked.ok) return [checked.status, { ok: false, error: checked.error, message: checked.message, retryable: false }];
    const v = checked.value;
    const key = kind + '|' + v.partner.steamId64 + '|' + [...v.assetIds].sort().join(',');

    if (inflight.has(key)) {
      const [status, payload] = await inflight.get(key);
      return [status, payload.ok ? Object.assign({}, payload, { duplicate: true }) : payload];
    }
    if (kind === 'give') {
      const busy = v.assetIds.find((a) => committing.has(a) && committing.get(a) !== key);
      if (busy) {
        return [409, { ok: false, error: 'ASSET_COMMITTED', retryable: false,
          message: 'asset ' + busy + ' is being sent to someone else right now' }];
      }
      v.assetIds.forEach((a) => committing.set(a, key));
    }
    const run = doSend(kind, v, key).catch((err) => fail(err, 500));
    inflight.set(key, run);
    try {
      return await run;
    } finally {
      inflight.delete(key);
      if (kind === 'give') v.assetIds.forEach((a) => { if (committing.get(a) === key) committing.delete(a); });
    }
  }

  async function getOffer(id) {
    if (!isReady()) return notReady();
    if (!isValidOfferId(id)) return [400, { ok: false, error: 'BAD_REQUEST', message: 'offer id must be digits' }];
    try {
      const [offer] = await p((cb) => getManager().getOffer(id, cb));
      return [200, {
        ok: true,
        offerId: String(offer.id),
        status: stateName(offer.state),
        stateNum: offer.state,
        isOurOffer: offer.isOurOffer,
        partner: partnerOf(offer),
        message: offer.message || null,
        escrowEndsUnix: offer.escrowEnds ? Math.floor(offer.escrowEnds.getTime() / 1000) : null,
        tradeId: offer.tradeID || null,
      }];
    } catch (err) {
      const e = classifyError(err);
      return [/no matching|not found/i.test(e.message) ? 404 : httpStatusFor(e.code),
        { ok: false, error: e.code, message: e.message, retryable: e.retryable }];
    }
  }

  async function acceptIncoming(id) {
    if (!isReady()) return notReady();
    if (!isValidOfferId(id)) return [400, { ok: false, error: 'BAD_REQUEST', message: 'offer id must be digits' }];
    let offer;
    try {
      [offer] = await p((cb) => getManager().getOffer(id, cb));
    } catch (err) {
      const e = classifyError(err);
      return [/no matching|not found/i.test(e.message) ? 404 : httpStatusFor(e.code),
        { ok: false, error: e.code, message: e.message }];
    }
    if (offer.isOurOffer) {
      return [400, { ok: false, error: 'BAD_REQUEST', message: 'offer is outgoing, not incoming' }];
    }
    if (offer.state !== S.Active) {
      return [409, { ok: false, error: 'OFFER_NOT_ACTIVE', message: 'offer is ' + stateName(offer.state) }];
    }
    if ((offer.itemsToGive || []).length > 0) {
      // A deposit only ever brings items IN. An incoming offer that asks for
      // anything of the bot's is a swap at best and a theft attempt at worst.
      log.warn('[bot] refused incoming offer #%s from %s: it asks the bot to give %d item(s)',
        offer.id, partnerOf(offer), offer.itemsToGive.length);
      return [409, { ok: false, error: 'GIVES_ITEMS', message: 'incoming offer asks the bot to give items; refused' }];
    }
    if ((offer.itemsToReceive || []).length === 0) {
      return [409, { ok: false, error: 'NOTHING_TO_RECEIVE', message: 'incoming offer has no items for the bot' }];
    }
    try {
      const [status] = await p((cb) => offer.accept(cb));
      log.log('[bot] accepted incoming offer #%s from %s (%s)', offer.id, partnerOf(offer), status);
      if (status === 'pending') {
        const cerr = await confirmOwn(offer);
        return [200, { ok: true, offerId: String(offer.id), status: cerr ? 'pending' : 'accepted', confirmed: !cerr,
          confirmError: cerr ? classifyError(cerr).message : null }];
      }
      return [200, { ok: true, offerId: String(offer.id), status: 'accepted', confirmed: true }];
    } catch (err) {
      return fail(err);
    }
  }

  async function inventory() {
    if (!isReady()) return notReady();
    try {
      // tradableOnly=false: a skin a seller just deposited sits in the bot
      // under Steam's 7-day Trade Protection and reads non-tradable. Asking
      // for tradable items only hid it, so the backend could never see a
      // protected deposit land and treated it as missing. Every item comes
      // back now, with Steam's own tradable flag, and the backend decides.
      const [items] = await p((cb) => getManager().getInventoryContents(Number(appId), contextId, false, cb));
      return [200, {
        ok: true,
        appId: Number(appId),
        count: items.length,
        items: items.map((it) => ({
          assetId: it.assetid,
          classId: it.classid,
          instanceId: it.instanceid,
          marketHashName: it.market_hash_name,
          name: it.name,
          tradable: it.tradable !== false,
        })),
      }];
    } catch (err) {
      return fail(err, 502);
    }
  }

  /**
   * Re-try confirming our own offers whose first confirmation failed. Only ids
   * this process sent are ever touched; an offer no longer waiting for a
   * confirmation, or one stuck past an hour, is dropped from the list.
   */
  async function retryConfirmations() {
    const out = { confirmed: [], dropped: [], failed: [] };
    if (!isReady()) return out;
    for (const [id, entry] of [...needsConfirm]) {
      let offer;
      try {
        [offer] = await p((cb) => getManager().getOffer(id, cb));
      } catch (err) {
        out.failed.push(id);
        continue;
      }
      if (offer.state !== S.CreatedNeedsConfirmation) {
        needsConfirm.delete(id);
        out.dropped.push(id);
        continue;
      }
      if (now() - entry.at > CONFIRM_GIVE_UP_MS) {
        needsConfirm.delete(id);
        out.dropped.push(id);
        log.error('[bot] gave up confirming offer #%s after %d attempts; Steam cancels it on its own', id, entry.attempts);
        continue;
      }
      entry.attempts += 1;
      const err = await confirmOwn(offer);
      (err ? out.failed : out.confirmed).push(id);
    }
    return out;
  }

  return {
    send: (body) => createAndSend('give', body),
    request: (body) => createAndSend('receive', body),
    getOffer,
    acceptIncoming,
    inventory,
    retryConfirmations,
    stateName,
    pendingConfirmations: () => [...needsConfirm.keys()],
  };
}

module.exports = { createBotCore, DEFAULT_STATES };

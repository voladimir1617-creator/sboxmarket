'use strict';

/*
 * A stand-in for steam-tradeoffer-manager + steamcommunity, just enough of
 * their callback API for lib/bot-core.js. Nothing here touches the network.
 *
 * It behaves the way the real libraries do where the bot depends on it:
 *   - setMessage cuts at 128 characters, as TradeOffer.setMessage does;
 *   - an offer that gives items comes back 'pending' and waits in
 *     CreatedNeedsConfirmation until the bot confirms it;
 *   - getOffers(ActiveOnly) lists only offers this account sent that are live.
 */

const { DEFAULT_STATES: S } = require('../../lib/bot-core');
const { parseTradeUrl } = require('../../lib/guards');

function steamId(id64) {
  return { getSteamID64: () => id64 };
}

function createStandInSteam(opts = {}) {
  const offers = new Map();
  const calls = { send: 0, confirm: [], accept: [], cancel: [] };
  let nextId = 5000001;
  const knobs = {
    sendDelayMs: opts.sendDelayMs || 0,
    confirmFails: 0,
    getOffersFails: false,
    sendError: null,
    cancelError: null,
    // The partner accepts in the moment between the bot's read and its cancel.
    acceptBeforeCancel: false,
  };

  function makeOffer(partnerId64, isOurOffer) {
    const offer = {
      id: null, state: null, isOurOffer, partner: steamId(partnerId64), message: '',
      itemsToGive: [], itemsToReceive: [], escrowEnds: null, tradeID: null,
      addMyItems(items) { this.itemsToGive.push(...items); },
      addTheirItem(item) { this.itemsToReceive.push(item); },
      setMessage(m) {
        if (this.id) throw new Error('Cannot set message in an already-sent offer');
        this.message = m.toString().substring(0, 128);
      },
      send(cb) {
        setTimeout(() => {
          calls.send += 1;
          if (knobs.sendError) { const e = knobs.sendError; knobs.sendError = null; return cb(e); }
          this.id = String(nextId++);
          const needsConf = this.itemsToGive.length > 0;
          this.state = needsConf ? S.CreatedNeedsConfirmation : S.Active;
          offers.set(this.id, this);
          cb(null, needsConf ? 'pending' : 'sent');
        }, knobs.sendDelayMs);
      },
      // As TradeOffer.cancel: only an Active or CreatedNeedsConfirmation offer
      // can be cancelled; on success the offer reads Canceled.
      cancel(cb) {
        setImmediate(() => {
          calls.cancel.push(this.id);
          if (knobs.acceptBeforeCancel) { knobs.acceptBeforeCancel = false; this.state = S.Accepted; }
          if (knobs.cancelError) { const e = knobs.cancelError; knobs.cancelError = null; return cb(e); }
          if (this.state !== S.Active && this.state !== S.CreatedNeedsConfirmation) {
            return cb(new Error('Offer #' + this.id + ' is not active, so it may not be cancelled or declined'));
          }
          this.state = S.Canceled;
          cb(null);
        });
      },
      accept(cb) {
        calls.accept.push(this.id);
        this.state = S.Accepted;
        setImmediate(() => cb(null, 'accepted'));
      },
    };
    return offer;
  }

  const manager = {
    createOffer(tradeUrl) {
      const parsed = parseTradeUrl(tradeUrl);
      if (!parsed) throw new Error('Invalid trade URL');
      return makeOffer(parsed.steamId64, true);
    },
    getOffer(id, cb) {
      setImmediate(() => {
        const o = offers.get(String(id));
        if (!o) return cb(new Error('No matching offer found'));
        cb(null, o);
      });
    },
    getOffers(filter, cb) {
      setImmediate(() => {
        if (knobs.getOffersFails) return cb(new Error('HTTP error 502'));
        const live = new Set([S.Active, S.InEscrow]);
        const all = [...offers.values()];
        cb(null, all.filter((o) => o.isOurOffer && live.has(o.state)), all.filter((o) => !o.isOurOffer && live.has(o.state)));
      });
    },
    getInventoryContents(appid, ctx, tradableOnly, cb) {
      calls.inventoryTradableOnly = tradableOnly;
      const items = [{ assetid: '111', classid: '9', instanceid: '0',
        market_hash_name: 'Test Hat', name: 'Test Hat', tradable: true },
      { assetid: '112', classid: '9', instanceid: '0',
        market_hash_name: 'Test Hat', name: 'Test Hat', tradable: false }];
      setImmediate(() => cb(null, tradableOnly ? items.filter((it) => it.tradable) : items));
    },
  };

  const community = {
    acceptConfirmationForObject(secret, id, cb) {
      setImmediate(() => {
        calls.confirm.push(String(id));
        if (knobs.confirmFails > 0) { knobs.confirmFails -= 1; return cb(new Error('Could not act on confirmation')); }
        const o = offers.get(String(id));
        if (o && o.state === S.CreatedNeedsConfirmation) o.state = S.Active;
        cb(null);
      });
    },
  };

  /** An offer someone else sent the bot. */
  function incoming({ fromId64, give = [], receive = [], state = S.Active }) {
    const o = makeOffer(fromId64, false);
    o.id = String(nextId++);
    o.state = state;
    o.itemsToGive = give.map((a) => ({ appid: 590830, contextid: '2', assetid: a }));
    o.itemsToReceive = receive.map((a) => ({ appid: 590830, contextid: '2', assetid: a }));
    offers.set(o.id, o);
    return o;
  }

  /** The partner acts on an offer. */
  function settle(id, state) { offers.get(String(id)).state = state; }

  return { manager, community, offers, calls, knobs, incoming, settle };
}

module.exports = { createStandInSteam, S };

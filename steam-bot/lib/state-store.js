'use strict';

/*
 * A tiny JSON file that keeps bot-core's two registries across restarts:
 *   - sent:         offers this bot created (offerId -> { kind, partner, assetIds, key, at })
 *   - needsConfirm: our own give-offers still waiting for the mobile
 *                   confirmation (offerId -> { at, attempts, nextAt })
 *
 * Without it a crash or reboot between "offer sent, confirmation failed" and
 * the next successful retry left the offer in needs_confirmation forever: the
 * backend polls it, nothing confirms it again, and the buyer never gets an offer
 * to accept. The file holds offer ids, SteamIDs and asset ids only; never a
 * secret, a trade URL or a token.
 *
 * Writes go to a temp file that is then renamed over the old one, so a crash
 * mid-write leaves the previous good copy. A file that cannot be read or parsed
 * is moved aside (state.json.corrupt-<time>) and the bot starts empty rather
 * than refusing to start.
 */

const fs = require('fs');
const path = require('path');

const VERSION = 1;

function createStateStore(filePath, opts = {}) {
  const log = opts.log || console;
  const file = path.resolve(filePath);

  function load() {
    let raw;
    try {
      raw = fs.readFileSync(file, 'utf8');
    } catch (err) {
      if (err && err.code === 'ENOENT') return { sent: [], needsConfirm: [] };
      log.error('[bot] could not read state file %s: %s; starting empty', file, err.message || err);
      return { sent: [], needsConfirm: [] };
    }
    try {
      const data = JSON.parse(raw);
      return {
        sent: Array.isArray(data.sent) ? data.sent.filter(validPair) : [],
        needsConfirm: Array.isArray(data.needsConfirm) ? data.needsConfirm.filter(validPair) : [],
      };
    } catch (err) {
      const aside = file + '.corrupt-' + Date.now();
      try { fs.renameSync(file, aside); } catch (e) { /* keep going */ }
      log.error('[bot] state file %s is not valid JSON (%s); moved to %s, starting empty',
        file, err.message || err, aside);
      return { sent: [], needsConfirm: [] };
    }
  }

  function save(data) {
    const body = JSON.stringify({ version: VERSION, savedAt: new Date().toISOString(),
      sent: data.sent, needsConfirm: data.needsConfirm });
    const tmp = file + '.tmp-' + process.pid;
    try {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(tmp, body, { encoding: 'utf8', mode: 0o600 });
      fs.renameSync(tmp, file);
      return true;
    } catch (err) {
      try { fs.unlinkSync(tmp); } catch (e) { /* nothing to clean */ }
      log.error('[bot] could not write state file %s: %s', file, err.message || err);
      return false;
    }
  }

  return { file, load, save };
}

function validPair(p) {
  return Array.isArray(p) && p.length === 2 && /^\d{1,20}$/.test(String(p[0])) && p[1] && typeof p[1] === 'object';
}

module.exports = { createStateStore };

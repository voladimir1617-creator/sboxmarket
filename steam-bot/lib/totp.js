'use strict';

/*
 * Steam Guard codes for the bot's login, made from shared_secret with the
 * Steam server's clock rather than the PC's.
 *
 * Why this exists:
 *   - With no 'steamGuard' listener, steam-user 5 answers a rejected or missing
 *     code by prompting on STDIN (components/09-logon.js _steamGuardPrompt).
 *     Under a service manager that is a silent hang: no 'error' event, so the
 *     reconnector never runs, and /health shows loggedIn:false, lastError:null.
 *   - A PC clock more than ~30 s off makes every generated code wrong.
 *   - A restart that logs in again inside the same 30 s window re-sends a code
 *     Steam has already used, and Steam refuses it.
 *
 * So: ask Steam for its time (SteamTotp.getTimeOffset, cached, with a timeout),
 * generate codes with that offset, and when Steam says the last code was wrong
 * wait for the NEXT 30 s window instead of resending the same code. A few
 * wrong codes in a row means the secret itself is wrong; then stop and say so
 * instead of feeding Steam failed logins until it throttles the account.
 *
 * Nothing here reads stdin, ever.
 */

const WINDOW_MS = 30 * 1000;
const WINDOW_MARGIN_MS = 1000;
const OFFSET_MAX_AGE_MS = 60 * 60 * 1000;
const OFFSET_TIMEOUT_MS = 10 * 1000;
const MAX_WRONG_CODES = 3;

/** ms from nowMs until a fresh TOTP window starts (plus a small margin), on
 *  Steam's clock: offsetSec seconds ahead of ours. */
function msUntilNextWindow(nowMs, offsetSec) {
  const steamMs = nowMs + (offsetSec || 0) * 1000;
  return WINDOW_MS - (((steamMs % WINDOW_MS) + WINDOW_MS) % WINDOW_MS) + WINDOW_MARGIN_MS;
}

function createSteamGuard(opts) {
  const SteamTotp = opts.SteamTotp;
  const sharedSecret = opts.sharedSecret;
  const log = opts.log || console;
  const now = opts.now || (() => Date.now());
  const setTimer = opts.setTimer || ((fn, ms) => setTimeout(fn, ms));
  const onStop = opts.onStop || (() => {});

  let offset = 0;
  let offsetAt = null; // when offset was last fetched (our clock)
  let lastCode = null;
  let wrongInARow = 0;

  /** Steam's clock offset in seconds. Cached for an hour; on failure keeps the
   *  last known value (0 before the first success) and logs it. Never rejects. */
  function getOffset(force) {
    if (!force && offsetAt !== null && now() - offsetAt < OFFSET_MAX_AGE_MS) return Promise.resolve(offset);
    return new Promise((resolve) => {
      let done = false;
      const finish = (err, value) => {
        if (done) return;
        done = true;
        if (err) {
          log.warn('[bot] Could not read Steam\'s clock (%s); using offset %ds. Keep the PC clock synced.',
            err.message || err, offset);
        } else {
          offset = value;
          offsetAt = now();
          if (Math.abs(offset) >= 10) {
            log.warn('[bot] This PC clock is %ds off Steam\'s. Codes use Steam\'s time, but sync the clock ' +
              '(Windows: Settings > Time > Sync now).', offset);
          }
        }
        resolve(offset);
      };
      // steam-totp's request has no timeout of its own.
      const t = setTimer(() => finish(new Error('timed out')), OFFSET_TIMEOUT_MS);
      if (t && typeof t.unref === 'function') t.unref();
      try {
        SteamTotp.getTimeOffset((err, off) => finish(err, err ? null : Number(off) || 0));
      } catch (err) {
        finish(err);
      }
    });
  }

  async function code(force) {
    const off = await getOffset(force);
    lastCode = SteamTotp.generateAuthCode(sharedSecret, off);
    return lastCode;
  }

  /** The code for a fresh logOn(). */
  function loginCode() {
    return code(false);
  }

  /** steam-user 'steamGuard' listener: (domain, callback, lastCodeWrong). */
  function onSteamGuard(domain, callback, lastCodeWrong) {
    if (domain) {
      // An EMAIL code: the account is not on the mobile authenticator the bot
      // needs, and no code we can make will satisfy it. Stop, never prompt.
      onStop('Steam asked for an EMAIL Steam Guard code (sent to ' + domain + '). The bot account must use ' +
        'the mobile authenticator whose shared_secret is in STEAM_BOT_SHARED_SECRET. Fix that and restart.');
      return;
    }
    if (!lastCodeWrong) {
      // Steam wants a code and we sent none (or it was dropped): make one now.
      code(false).then(callback);
      return;
    }
    wrongInARow += 1;
    if (wrongInARow > MAX_WRONG_CODES) {
      onStop('Steam rejected ' + wrongInARow + ' Steam Guard codes in a row. STEAM_BOT_SHARED_SECRET is ' +
        'probably wrong (or the authenticator was moved). Not retrying, so the account is not throttled. ' +
        'Fix the secret and restart.');
      return;
    }
    // The code was refused: our clock may be off, or this code was already used
    // (a quick re-login). Re-read Steam's time, then wait for the next window so
    // the next code is a different one.
    const previous = lastCode;
    getOffset(true).then((off) => {
      const wait = msUntilNextWindow(now(), off);
      log.warn('[bot] Steam Guard code rejected (%d in a row); trying a fresh one in %ds.',
        wrongInARow, Math.round(wait / 1000));
      setTimer(() => {
        code(false).then((c) => {
          if (c === previous) {
            // Still the same window by Steam's clock: wait one more.
            setTimer(() => code(false).then(callback), msUntilNextWindow(now(), off));
          } else {
            callback(c);
          }
        });
      }, wait);
    });
  }

  function onLoggedOn() {
    wrongInARow = 0;
  }

  return { getOffset, loginCode, onSteamGuard, onLoggedOn, wrongInARow: () => wrongInARow };
}

module.exports = { createSteamGuard, msUntilNextWindow, WINDOW_MS, MAX_WRONG_CODES };

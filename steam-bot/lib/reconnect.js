'use strict';

/*
 * Re-login policy for the Steam client.
 *
 * steam-user re-connects by itself after an ordinary disconnect, but after a
 * fatal 'error' event (Steam kicked the session, logged in elsewhere, a login
 * that failed) it stays logged off for good. Before this, the bot then sat at
 * /health ready=false until someone restarted it by hand, and every deposit,
 * delivery and return waited.
 *
 * The policy: try again with a growing delay. A wrong password is NOT retried:
 * Steam locks an account that keeps failing logins, which would take the bot
 * down for far longer than one missed retry. Steam's login throttle gets the
 * long delay straight away.
 */

const { ERESULT } = require('./guards');

const BASE_DELAY_MS = 30 * 1000;
const MAX_DELAY_MS = 15 * 60 * 1000;
const THROTTLED_DELAY_MS = 30 * 60 * 1000;

function createReconnector(opts) {
  const logIn = opts.logIn;
  const setTimer = opts.setTimer || ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = opts.clearTimer || ((t) => clearTimeout(t));
  const log = opts.log || console;

  let failures = 0;
  let timer = null;
  let stopped = false;

  function onLoggedOn() {
    failures = 0;
    if (timer) { clearTimer(timer); timer = null; }
  }

  /** Returns the delay scheduled in ms, or null when it will not retry. */
  function onFatalError(err) {
    const eresult = err && typeof err.eresult === 'number' ? err.eresult : null;
    if (stopped) return null;
    if (eresult === ERESULT.InvalidPassword) {
      stopped = true;
      if (timer) { clearTimer(timer); timer = null; }
      log.error('[bot] Steam rejected the bot password. Not retrying, so the account is not locked ' +
        'for repeated failed logins. Fix STEAM_BOT_PASSWORD and restart the sidecar.');
      return null;
    }
    if (eresult === ERESULT.LoggedInElsewhere) {
      log.error('[bot] Someone else logged in to the bot account. If that was not you, change its ' +
        'password and authenticator before restarting.');
    }
    failures += 1;
    let delay = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** (failures - 1));
    if (eresult === ERESULT.AccountLoginDeniedThrottle || eresult === ERESULT.RateLimitExceeded) {
      delay = THROTTLED_DELAY_MS;
    }
    if (timer) clearTimer(timer);
    log.warn('[bot] Logging in again in %ds (attempt %d).', Math.round(delay / 1000), failures);
    timer = setTimer(() => { timer = null; logIn(); }, delay);
    return delay;
  }

  return { onLoggedOn, onFatalError, isStopped: () => stopped, failures: () => failures };
}

// After a failed setCookies (no web session usable for trading), ask steam-user
// for a new web session: 60 s, doubling, capped at 15 min.
const WEB_RETRY_BASE_MS = 60 * 1000;

/** Delay before the n-th (1-based) webLogOn retry. */
function webSessionRetryDelayMs(n) {
  return Math.min(MAX_DELAY_MS, WEB_RETRY_BASE_MS * 2 ** Math.max(0, n - 1));
}

module.exports = {
  createReconnector, webSessionRetryDelayMs, BASE_DELAY_MS, MAX_DELAY_MS, THROTTLED_DELAY_MS,
};
